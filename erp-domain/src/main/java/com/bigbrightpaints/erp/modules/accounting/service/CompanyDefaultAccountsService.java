package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
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
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  @Transactional
  public DefaultAccounts updateDefaults(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
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
    Long resolvedDiscountAccountId =
        resolveDiscountAccountId(discountAccountId, fgDiscountAccountId);
    if (resolvedDiscountAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, resolvedDiscountAccountId);
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
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  public Long resolveAutoSettlementCashAccountId(
      Company company, Long requestedCashAccountId, String operation) {
    if (company == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company context is required");
    }
    if (requestedCashAccountId != null) {
      Account account = accountingLookupService.requireAccount(company, requestedCashAccountId);
      validateSettlementCashAccount(account, operation);
      return account.getId();
    }
    Account configuredCashAccount = company.getPayrollCashAccount();
    if (configuredCashAccount == null || configuredCashAccount.getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "cashAccountId is required when no company default settlement cash account is"
              + " configured for "
              + operation);
    }
    Account account =
        accountingLookupService.requireAccount(company, configuredCashAccount.getId());
    validateSettlementCashAccount(account, operation);
    return account.getId();
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

  private Long resolveDiscountAccountId(Long discountAccountId, Long fgDiscountAccountId) {
    if (discountAccountId == null) {
      return fgDiscountAccountId;
    }
    if (fgDiscountAccountId == null || discountAccountId.equals(fgDiscountAccountId)) {
      return discountAccountId;
    }
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "discountAccountId and fgDiscountAccountId must match when both are provided");
  }

  private void validateSettlementCashAccount(Account account, String operation) {
    if (account == null || account.getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "cashAccountId is required for " + operation);
    }
    if (!account.isActive()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Cash/bank account for " + operation + " must be active");
    }
    if (account.getType() != null && account.getType() != AccountType.ASSET) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Cash/bank account for " + operation + " must be an ASSET account");
    }
    if (isReceivableAccount(account) || isPayableAccount(account)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Cash/bank account for " + operation + " cannot be AR/AP control account");
    }
  }

  private boolean isReceivableAccount(Account account) {
    if (account == null || account.getType() != AccountType.ASSET) {
      return false;
    }
    String code = IdempotencyUtils.normalizeUpperToken(account.getCode());
    String name = IdempotencyUtils.normalizeUpperToken(account.getName());
    return isTokenMatch(code, "AR") || name.contains("ACCOUNTS RECEIVABLE");
  }

  private boolean isPayableAccount(Account account) {
    if (account == null || account.getType() != AccountType.LIABILITY) {
      return false;
    }
    String code = IdempotencyUtils.normalizeUpperToken(account.getCode());
    String name = IdempotencyUtils.normalizeUpperToken(account.getName());
    return isTokenMatch(code, "AP") || name.contains("ACCOUNTS PAYABLE");
  }

  private boolean isTokenMatch(String value, String token) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return value.equals(token)
        || value.startsWith(token + "-")
        || value.endsWith("-" + token)
        || value.contains("-" + token + "-");
  }

  public record DefaultAccounts(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId) {}
}
