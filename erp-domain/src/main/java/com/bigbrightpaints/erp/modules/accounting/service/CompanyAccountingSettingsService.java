package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class CompanyAccountingSettingsService {

    private final CompanyContextService companyContextService;
    private final CompanyRepository companyRepository;
    private final AccountRepository accountRepository;

    public CompanyAccountingSettingsService(CompanyContextService companyContextService,
                                            CompanyRepository companyRepository,
                                            AccountRepository accountRepository) {
        this.companyContextService = companyContextService;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
    }

    public PayrollAccountDefaults requirePayrollDefaults() {
        Company company = companyContextService.requireCurrentCompany();
        Account expense = company.getPayrollExpenseAccount();
        Account cash = company.getPayrollCashAccount();
        if (expense == null || cash == null) {
            throw new IllegalStateException("Payroll account defaults are not configured for company " + company.getCode());
        }
        return new PayrollAccountDefaults(expense.getId(), cash.getId());
    }

    @Transactional
    public void updatePayrollDefaults(Long expenseAccountId, Long cashAccountId) {
        Company company = companyContextService.requireCurrentCompany();
        if (expenseAccountId != null) {
            Account expense = accountRepository.findByCompanyAndId(company, expenseAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Expense account not found"));
            company.setPayrollExpenseAccount(expense);
        }
        if (cashAccountId != null) {
            Account cash = accountRepository.findByCompanyAndId(company, cashAccountId)
                    .orElseThrow(() -> new IllegalArgumentException("Cash account not found"));
            company.setPayrollCashAccount(cash);
        }
        companyRepository.save(company);
    }

    public record PayrollAccountDefaults(Long expenseAccountId, Long cashAccountId) {}
}
