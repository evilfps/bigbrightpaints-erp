package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CompanyService {

    private final CompanyRepository repository;
    private final AuditService auditService;

    public CompanyService(CompanyRepository repository) {
        this(repository, null);
    }

    @Autowired
    public CompanyService(CompanyRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<CompanyDto> findAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    public List<CompanyDto> findAll(Set<Company> companies) {
        if (companies == null || companies.isEmpty()) {
            return List.of();
        }
        return companies.stream().map(this::toDto).toList();
    }

    public CompanyDto create(CompanyRequest request) {
        Authentication authentication = requireSuperAdminForTenantBootstrap(request.code());
        Company company = new Company();
        company.setName(request.name());
        company.setCode(request.code());
        company.setTimezone(request.timezone());
        company.setDefaultGstRate(request.defaultGstRate());
        Company saved = repository.save(company);
        auditAuthorityDecision(true, "tenant-bootstrap-created", request.code(), authentication);
        return toDto(saved);
    }

    @Transactional
    public CompanyDto update(Long id, CompanyRequest request, Set<Company> allowedCompanies) {
        requireMembershipById(id, allowedCompanies);
        Company company = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Company not found"));
        company.setName(request.name());
        company.setCode(request.code());
        company.setTimezone(request.timezone());
        company.setDefaultGstRate(request.defaultGstRate());
        return toDto(company);
    }

    public void delete(Long id, Set<Company> allowedCompanies) {
        requireMembershipById(id, allowedCompanies);
        repository.deleteById(id);
    }

    public CompanyDto switchCompany(String companyCode, Set<Company> allowedCompanies) {
        requireMembershipByCode(companyCode, allowedCompanies);
        return toDto(findByCode(companyCode));
    }

    public Company findByCode(String code) {
        return repository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + code));
    }

    private void requireMembershipById(Long companyId, Set<Company> allowedCompanies) {
        if (companyId == null || allowedCompanies == null || allowedCompanies.isEmpty()) {
            throw new AccessDeniedException("Not allowed to access company");
        }
        boolean member = allowedCompanies.stream().anyMatch(company -> companyId.equals(company.getId()));
        if (!member) {
            throw new AccessDeniedException("Not allowed to access company");
        }
    }

    private void requireMembershipByCode(String companyCode, Set<Company> allowedCompanies) {
        String normalizedCode = companyCode == null ? "" : companyCode.trim();
        if (!StringUtils.hasText(normalizedCode) || allowedCompanies == null || allowedCompanies.isEmpty()) {
            throw new AccessDeniedException("Not allowed to access company");
        }
        boolean member = allowedCompanies.stream()
                .map(Company::getCode)
                .filter(StringUtils::hasText)
                .anyMatch(code -> code.equalsIgnoreCase(normalizedCode));
        if (!member) {
            throw new AccessDeniedException("Not allowed to access company");
        }
    }

    private CompanyDto toDto(Company company) {
        return new CompanyDto(
                company.getId(),
                company.getPublicId(),
                company.getName(),
                company.getCode(),
                company.getTimezone(),
                company.getDefaultGstRate());
    }

    private Authentication requireSuperAdminForTenantBootstrap(String requestedCompanyCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            auditAuthorityDecision(false, "super-admin-required-for-tenant-bootstrap", requestedCompanyCode, authentication);
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant bootstrap");
        }
        return authentication;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(granted -> authority.equalsIgnoreCase(granted));
    }

    private void auditAuthorityDecision(boolean granted, String reason, String targetCompanyCode, Authentication authentication) {
        if (auditService == null) {
            return;
        }
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("actor", resolveActor(authentication));
        metadata.put("reason", reason);
        metadata.put("tenantScope", resolveTenantScope(authentication));
        if (StringUtils.hasText(targetCompanyCode)) {
            metadata.put("targetCompanyCode", targetCompanyCode.trim());
        }
        if (granted) {
            auditService.logSuccess(AuditEvent.ACCESS_GRANTED, metadata);
        } else {
            auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
        }
    }

    private String resolveActor(Authentication authentication) {
        if (authentication == null) {
            return "anonymous";
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? name.trim() : "anonymous";
    }

    private String resolveTenantScope(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.getUser() != null && principal.getUser().getCompanies() != null) {
            String scope = principal.getUser().getCompanies().stream()
                    .map(Company::getCode)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.joining(","));
            if (StringUtils.hasText(scope)) {
                return scope;
            }
        }
        return "none";
    }
}
