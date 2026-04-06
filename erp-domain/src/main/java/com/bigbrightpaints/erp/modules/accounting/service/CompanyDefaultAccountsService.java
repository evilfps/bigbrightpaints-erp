package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

import jakarta.transaction.Transactional;

/**
 * Manages company-wide default accounts for automatic postings.
 * Ensures correct account types are used to prevent mis-mapping (e.g., revenue to cash).
 */
@Service
public class CompanyDefaultAccountsService {

  private final CompanyContextService companyContextService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyRepository companyRepository;

  @Autowired
  public CompanyDefaultAccountsService(
      CompanyContextService companyContextService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyRepository companyRepository) {
    this.companyContextService = companyContextService;
    this.accountingLookupService = accountingLookupService;
    this.companyRepository = companyRepository;
  }

  public CompanyDefaultAccountsService(
      CompanyContextService companyContextService,
      CompanyEntityLookup companyEntityLookup,
      CompanyRepository companyRepository) {
    this(
        companyContextService,
        CompanyScopedAccountingLookupService.fromLegacy(companyEntityLookup),
        companyRepository);
  }

  public DefaultAccounts requireDefaults() {
    Company company = companyContextService.requireCurrentCompany();
    requireConfiguredDefault(
        company.getDefaultInventoryAccountId(), company.getCode(), "fgValuationAccountId");
    requireConfiguredDefault(
        company.getDefaultCogsAccountId(), company.getCode(), "fgCogsAccountId");
    requireConfiguredDefault(
        company.getDefaultRevenueAccountId(), company.getCode(), "fgRevenueAccountId");
    requireConfiguredDefault(
        company.getDefaultDiscountAccountId(), company.getCode(), "fgDiscountAccountId");
    requireConfiguredDefault(company.getDefaultTaxAccountId(), company.getCode(), "fgTaxAccountId");
    return new DefaultAccounts(
        company.getDefaultInventoryAccountId(),
        company.getDefaultCogsAccountId(),
        company.getDefaultRevenueAccountId(),
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  public DefaultAccounts getDefaults() {
    Company company = companyContextService.requireCurrentCompany();
    return new DefaultAccounts(
        company.getDefaultInventoryAccountId(),
        company.getDefaultCogsAccountId(),
        company.getDefaultRevenueAccountId(),
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  @Transactional
  public DefaultAccounts updateDefaults(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long taxAccountId) {
    Company company = companyContextService.requireCurrentCompany();
    if (inventoryAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, inventoryAccountId);
      requireType(account, AccountType.ASSET, "inventory");
      company.setDefaultInventoryAccountId(account.getId());
    }
    if (cogsAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, cogsAccountId);
      requireType(account, AccountType.COGS, "COGS");
      company.setDefaultCogsAccountId(account.getId());
    }
    if (revenueAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, revenueAccountId);
      requireType(account, AccountType.REVENUE, "revenue");
      company.setDefaultRevenueAccountId(account.getId());
    }
    if (discountAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, discountAccountId);
      if (!(AccountType.REVENUE.equals(account.getType())
          || AccountType.EXPENSE.equals(account.getType()))) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Discount account must be revenue or expense");
      }
      company.setDefaultDiscountAccountId(account.getId());
    }
    if (taxAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, taxAccountId);
      requireType(account, AccountType.LIABILITY, "tax");
      company.setDefaultTaxAccountId(account.getId());
      if (isNonGstMode(company)) {
        company.setGstInputTaxAccountId(null);
        company.setGstOutputTaxAccountId(null);
        company.setGstPayableAccountId(null);
      } else {
        company.setGstOutputTaxAccountId(account.getId());
        company.setGstPayableAccountId(account.getId());
      }
    }
    companyRepository.save(company);
    return new DefaultAccounts(
        company.getDefaultInventoryAccountId(),
        company.getDefaultCogsAccountId(),
        company.getDefaultRevenueAccountId(),
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  private void requireType(Account account, AccountType expected, String purpose) {
    if (!expected.equals(account.getType())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Account "
              + account.getCode()
              + " is not a valid "
              + purpose
              + " account (expected type "
              + expected
              + ")");
    }
  }

  private void requireConfiguredDefault(Long accountId, String companyCode, String fieldName) {
    if (accountId != null) {
      return;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
        "Default "
            + fieldName
            + " is not configured for company "
            + companyCode
            + ". Configure company default accounts to enable product posting.");
  }

  private boolean isNonGstMode(Company company) {
    BigDecimal defaultGstRate = company.getDefaultGstRate();
    return defaultGstRate != null && defaultGstRate.compareTo(BigDecimal.ZERO) == 0;
  }

  public record DefaultAccounts(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long taxAccountId) {}
}
