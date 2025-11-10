package com.bigbrightpaints.erp.modules.company.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import org.springframework.stereotype.Service;

@Service
public class CompanyContextService {

    private final CompanyRepository companyRepository;

    public CompanyContextService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public Company requireCurrentCompany() {
        String code = CompanyContextHolder.getCompanyId();
        if (code == null) {
            throw new IllegalStateException("No active company in context");
        }
        return companyRepository.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + code));
    }
}
