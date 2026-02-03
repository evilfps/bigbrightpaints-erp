package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
public class CompanyService {

    private final CompanyRepository repository;

    public CompanyService(CompanyRepository repository) {
        this.repository = repository;
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
        Company company = new Company();
        company.setName(request.name());
        company.setCode(request.code());
        company.setTimezone(request.timezone());
        company.setDefaultGstRate(request.defaultGstRate());
        return toDto(repository.save(company));
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
}
