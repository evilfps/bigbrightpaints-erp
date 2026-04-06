package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

import jakarta.transaction.Transactional;

@Service
public class CompanyAccountingSettingsService {

  private final CompanyContextService companyContextService;
  private final CompanyRepository companyRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;

  public CompanyAccountingSettingsService(
      CompanyContextService companyContextService,
      CompanyRepository companyRepository,
      CompanyScopedAccountingLookupService accountingLookupService) {
    this.companyContextService = companyContextService;
    this.companyRepository = companyRepository;
    this.accountingLookupService = accountingLookupService;
  }

  public PayrollAccountDefaults requirePayrollDefaults() {
    Company company = companyContextService.requireCurrentCompany();
    Account expense = company.getPayrollExpenseAccount();
    Account cash = company.getPayrollCashAccount();
    if (expense == null || cash == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Payroll account defaults are not configured for company " + company.getCode());
    }
    return new PayrollAccountDefaults(expense.getId(), cash.getId());
  }

  @Transactional
  public void updatePayrollDefaults(Long expenseAccountId, Long cashAccountId) {
    Company company = companyContextService.requireCurrentCompany();
    if (expenseAccountId != null) {
      Account expense = accountingLookupService.requireAccount(company, expenseAccountId);
      company.setPayrollExpenseAccount(expense);
    }
    if (cashAccountId != null) {
      Account cash = accountingLookupService.requireAccount(company, cashAccountId);
      company.setPayrollCashAccount(cash);
    }
    companyRepository.save(company);
  }

  public record PayrollAccountDefaults(Long expenseAccountId, Long cashAccountId) {}

  public TaxAccountConfiguration requireTaxAccounts() {
    Company company = companyContextService.requireCurrentCompany();
    List<String> missing = new ArrayList<>();
    if (company.getGstInputTaxAccountId() == null) {
      missing.add("gstInputTaxAccountId");
    }
    if (company.getGstOutputTaxAccountId() == null) {
      missing.add("gstOutputTaxAccountId");
    }
    if (!missing.isEmpty()) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
              "GST tax accounts are not configured for company " + company.getCode())
          .withDetail("missing", missing);
    }
    return new TaxAccountConfiguration(
        company.getGstInputTaxAccountId(),
        company.getGstOutputTaxAccountId(),
        company.getGstPayableAccountId());
  }

  public record TaxAccountConfiguration(
      Long inputTaxAccountId, Long outputTaxAccountId, Long payableAccountId) {}
}
