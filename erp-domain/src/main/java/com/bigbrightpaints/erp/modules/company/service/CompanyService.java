package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CompanyService {

    private static final String LIFECYCLE_STATE_METADATA_KEY = "companyLifecycleState";
    private static final String LIFECYCLE_PREVIOUS_STATE_METADATA_KEY = "companyPreviousLifecycleState";
    private static final String LIFECYCLE_REASON_METADATA_KEY = "companyLifecycleReason";
    private static final String LIFECYCLE_EVIDENCE_METADATA_KEY = "lifecycleEvidence";
    private static final String LIFECYCLE_EVIDENCE_VALUE = "immutable-audit-log";
    private static final String LIFECYCLE_UPDATED_REASON = "tenant-lifecycle-state-updated";
    private static final String LIFECYCLE_SUPER_ADMIN_REQUIRED_REASON = "super-admin-required-for-tenant-lifecycle-control";
    private static final String METRICS_SUPER_ADMIN_REQUIRED_REASON = "super-admin-required-for-tenant-metrics-read";
    private static final String METRICS_READ_REASON = "tenant-metrics-read";
    private static final long ERROR_RATE_BASIS_POINTS_SCALE = 10_000L;

    private final CompanyRepository repository;
    private final AuditService auditService;
    private final UserAccountRepository userAccountRepository;
    private final AuditLogRepository auditLogRepository;

    public CompanyService(CompanyRepository repository) {
        this(repository, null, null, null);
    }

    @Autowired
    public CompanyService(CompanyRepository repository,
                          AuditService auditService,
                          UserAccountRepository userAccountRepository,
                          AuditLogRepository auditLogRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.userAccountRepository = userAccountRepository;
        this.auditLogRepository = auditLogRepository;
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
        requireSuperAdminForTenantConfigurationUpdate();
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

    @Transactional
    public CompanyLifecycleStateDto updateLifecycleState(Long companyId, CompanyLifecycleStateRequest request) {
        CompanyLifecycleState requestedState = CompanyLifecycleState.fromRequestValue(request.state());
        String lifecycleReason = normalizeLifecycleReason(request.reason());
        Authentication authentication = requireSuperAdminForLifecycleControl(companyId, requestedState, lifecycleReason);

        Company company = repository.lockById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found"));

        CompanyLifecycleState previousState = resolveLifecycleStateById(company.getId());
        company.setLifecycleState(requestedState);
        company.setLifecycleReason(lifecycleReason);
        persistLifecycleAuditEvidence(company, previousState, requestedState, lifecycleReason, authentication);
        return new CompanyLifecycleStateDto(
                company.getId(),
                company.getCode(),
                previousState.name(),
                requestedState.name(),
                lifecycleReason);
    }

    public Company findByCode(String code) {
        return repository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + code));
    }

    public boolean isRuntimeAccessAllowed(Long companyId) {
        return resolveLifecycleStateById(companyId) == CompanyLifecycleState.ACTIVE;
    }

    public CompanyLifecycleState resolveLifecycleStateByCode(String companyCode) {
        if (!StringUtils.hasText(companyCode)) {
            return CompanyLifecycleState.BLOCKED;
        }
        return repository.findByCodeIgnoreCase(companyCode.trim())
                .map(Company::getId)
                .map(this::resolveLifecycleStateById)
                .orElse(CompanyLifecycleState.BLOCKED);
    }

    public CompanyLifecycleState resolveLifecycleStateById(Long companyId) {
        if (companyId == null) {
            return CompanyLifecycleState.BLOCKED;
        }
        return repository.findById(companyId)
                .map(company -> company.getLifecycleState() == null ? CompanyLifecycleState.ACTIVE : company.getLifecycleState())
                .orElse(CompanyLifecycleState.BLOCKED);
    }

    public CompanyTenantMetricsDto getTenantMetrics(Long companyId) {
        Authentication authentication = requireSuperAdminForTenantMetrics(companyId);
        Company company = repository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found"));
        CompanyLifecycleState state = company.getLifecycleState() == null ? CompanyLifecycleState.ACTIVE : company.getLifecycleState();
        long activeUserCount = countActiveUsers(companyId);
        long apiActivityCount = countApiActivity(companyId);
        long apiErrorCount = countApiFailureActivity(companyId);
        long apiErrorRateInBasisPoints = calculateErrorRateInBasisPoints(apiActivityCount, apiErrorCount);
        auditAuthorityDecision(true, METRICS_READ_REASON, company.getCode(), authentication);
        return new CompanyTenantMetricsDto(
                company.getId(),
                company.getCode(),
                state.name(),
                company.getLifecycleReason(),
                activeUserCount,
                apiActivityCount,
                apiErrorCount,
                apiErrorRateInBasisPoints);
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

    private Authentication requireSuperAdminForLifecycleControl(Long companyId,
                                                                CompanyLifecycleState requestedState,
                                                                String lifecycleReason) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            Company target = companyId == null ? null : repository.findById(companyId).orElse(null);
            auditLifecycleDenied(
                    companyId,
                    target == null ? null : target.getCode(),
                    requestedState,
                    lifecycleReason,
                    authentication);
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant lifecycle control");
        }
        return authentication;
    }

    private Authentication requireSuperAdminForTenantBootstrap(String requestedCompanyCode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            auditAuthorityDecision(false, "super-admin-required-for-tenant-bootstrap", requestedCompanyCode, authentication);
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant bootstrap");
        }
        return authentication;
    }

    private Authentication requireSuperAdminForTenantMetrics(Long companyId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            Company target = companyId == null ? null : repository.findById(companyId).orElse(null);
            String targetCompanyCode = target == null ? null : target.getCode();
            auditAuthorityDecision(false, METRICS_SUPER_ADMIN_REQUIRED_REASON, targetCompanyCode, authentication);
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant metrics");
        }
        return authentication;
    }

    private void requireSuperAdminForTenantConfigurationUpdate() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            throw new AccessDeniedException("SUPER_ADMIN authority required for tenant configuration updates");
        }
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(granted -> authority.equalsIgnoreCase(granted));
    }

    private String normalizeLifecycleReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("Lifecycle reason is required");
        }
        return reason.trim();
    }

    private void persistLifecycleAuditEvidence(Company company,
                                               CompanyLifecycleState previousState,
                                               CompanyLifecycleState requestedState,
                                               String lifecycleReason,
                                               Authentication authentication) {
        if (auditService == null) {
            return;
        }
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("actor", resolveActor(authentication));
        metadata.put("reason", LIFECYCLE_UPDATED_REASON);
        metadata.put("tenantScope", resolveTenantScope(authentication));
        metadata.put("targetCompanyCode", company.getCode());
        metadata.put("targetCompanyId", String.valueOf(company.getId()));
        metadata.put(LIFECYCLE_PREVIOUS_STATE_METADATA_KEY, previousState.name());
        metadata.put(LIFECYCLE_STATE_METADATA_KEY, requestedState.name());
        metadata.put(LIFECYCLE_REASON_METADATA_KEY, lifecycleReason);
        metadata.put(LIFECYCLE_EVIDENCE_METADATA_KEY, LIFECYCLE_EVIDENCE_VALUE);
        auditService.logAuthSuccess(
                AuditEvent.CONFIGURATION_CHANGED,
                resolveActor(authentication),
                company.getCode(),
                metadata);
    }

    private void auditLifecycleDenied(Long companyId,
                                      String targetCompanyCode,
                                      CompanyLifecycleState requestedState,
                                      String lifecycleReason,
                                      Authentication authentication) {
        if (auditService == null) {
            return;
        }
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("actor", resolveActor(authentication));
        metadata.put("reason", LIFECYCLE_SUPER_ADMIN_REQUIRED_REASON);
        metadata.put("tenantScope", resolveTenantScope(authentication));
        if (companyId != null) {
            metadata.put("targetCompanyId", String.valueOf(companyId));
        }
        if (StringUtils.hasText(targetCompanyCode)) {
            metadata.put("targetCompanyCode", targetCompanyCode.trim());
        }
        metadata.put(LIFECYCLE_STATE_METADATA_KEY, requestedState.name());
        metadata.put(LIFECYCLE_REASON_METADATA_KEY, lifecycleReason);
        metadata.put(LIFECYCLE_EVIDENCE_METADATA_KEY, LIFECYCLE_EVIDENCE_VALUE);
        auditService.logAuthFailure(
                AuditEvent.ACCESS_DENIED,
                resolveActor(authentication),
                targetCompanyCode,
                metadata);
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

    private long countActiveUsers(Long companyId) {
        if (userAccountRepository == null) {
            return 0L;
        }
        return userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(companyId);
    }

    private long countApiActivity(Long companyId) {
        if (auditLogRepository == null || companyId == null) {
            return 0L;
        }
        return auditLogRepository.countApiActivityByCompanyId(companyId);
    }

    private long countApiFailureActivity(Long companyId) {
        if (auditLogRepository == null || companyId == null) {
            return 0L;
        }
        return auditLogRepository.countApiFailureActivityByCompanyId(companyId);
    }

    private long calculateErrorRateInBasisPoints(long apiActivityCount, long apiErrorCount) {
        if (apiActivityCount <= 0L || apiErrorCount <= 0L) {
            return 0L;
        }
        long boundedErrorCount = Math.min(apiErrorCount, apiActivityCount);
        return (boundedErrorCount * ERROR_RATE_BASIS_POINTS_SCALE) / apiActivityCount;
    }
}
