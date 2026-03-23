package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class CostingMethodService {

    private final AccountingPeriodService accountingPeriodService;
    private final CompanyContextService companyContextService;

    public CostingMethodService(AccountingPeriodService accountingPeriodService,
                                CompanyContextService companyContextService) {
        this.accountingPeriodService = accountingPeriodService;
        this.companyContextService = companyContextService;
    }

    public CostingMethod resolveActiveMethodForCurrentCompany(LocalDate referenceDate) {
        Company company = companyContextService.requireCurrentCompany();
        return resolveActiveMethod(company, referenceDate);
    }

    public CostingMethod resolveActiveMethod(Company company, LocalDate referenceDate) {
        AccountingPeriod period = accountingPeriodService.ensurePeriod(company, referenceDate);
        if (period == null || period.getCostingMethod() == null) {
            return CostingMethod.FIFO;
        }
        return period.getCostingMethod();
    }
}
