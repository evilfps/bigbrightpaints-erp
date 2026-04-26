package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
  private AccountingComplianceAuditService accountingComplianceAuditService;

  @Autowired
  public CompanyDefaultAccountsService(
      CompanyContextService companyContextService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyRepository companyRepository) {
    this.companyContextService = companyContextService;
    this.accountingLookupService = accountingLookupService;
    this.companyRepository = companyRepository;
  }

  @Autowired(required = false)
  void setAccountingComplianceAuditService(
      AccountingComplianceAuditService accountingComplianceAuditService) {
    this.accountingComplianceAuditService = accountingComplianceAuditService;
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
    return updateDefaults(
        inventoryAccountId,
        cogsAccountId,
        revenueAccountId,
        discountAccountId,
        fgDiscountAccountId,
        taxAccountId,
        List.of());
  }

  @Transactional
  public DefaultAccounts updateDefaults(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId,
      List<String> clearAccountFields) {
    Company company = companyContextService.requireCurrentCompany();
    DefaultAccounts before = snapshot(company);
    Set<DefaultAccountSlot> clearSlots = resolveClearSlots(clearAccountFields);
    validateNoClearSetConflicts(
        clearSlots,
        inventoryAccountId,
        cogsAccountId,
        revenueAccountId,
        discountAccountId,
        fgDiscountAccountId,
        taxAccountId);
    applyClearSlots(company, clearSlots);
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
    DefaultAccounts after = snapshot(company);
    recordDefaultAccountsAudit(company, before, after, clearSlots);
    return after;
  }

  public Long resolveAutoSettlementCashAccountId(
      Company company, Long requestedCashAccountId, String operation) {
    if (company == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Company context is required");
    }
    if (requestedCashAccountId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "cashAccountId is required for " + operation);
    }
    Account account = accountingLookupService.requireAccount(company, requestedCashAccountId);
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

  private DefaultAccounts snapshot(Company company) {
    return new DefaultAccounts(
        company.getDefaultInventoryAccountId(),
        company.getDefaultCogsAccountId(),
        company.getDefaultRevenueAccountId(),
        company.getDefaultDiscountAccountId(),
        company.getDefaultDiscountAccountId(),
        company.getDefaultTaxAccountId());
  }

  private Set<DefaultAccountSlot> resolveClearSlots(List<String> clearAccountFields) {
    if (clearAccountFields == null || clearAccountFields.isEmpty()) {
      return Set.of();
    }
    Set<DefaultAccountSlot> slots = new LinkedHashSet<>();
    for (String field : clearAccountFields) {
      if (field == null || field.isBlank()) {
        continue;
      }
      slots.add(resolveClearSlot(field));
    }
    return slots;
  }

  private DefaultAccountSlot resolveClearSlot(String field) {
    String normalized = field.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    return switch (normalized) {
      case "inventory", "inventoryaccountid", "defaultinventoryaccountid", "fgvaluationaccountid" ->
          DefaultAccountSlot.INVENTORY;
      case "cogs", "cogsaccountid", "defaultcogsaccountid", "fgcogsaccountid" ->
          DefaultAccountSlot.COGS;
      case "revenue", "revenueaccountid", "defaultrevenueaccountid", "fgrevenueaccountid" ->
          DefaultAccountSlot.REVENUE;
      case "discount", "discountaccountid", "fgdiscountaccountid", "defaultdiscountaccountid" ->
          DefaultAccountSlot.DISCOUNT;
      case "tax", "taxaccountid", "defaulttaxaccountid", "fgtaxaccountid" -> DefaultAccountSlot.TAX;
      default ->
          throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
              "Unsupported default account clear field: " + field);
    };
  }

  private void validateNoClearSetConflicts(
      Set<DefaultAccountSlot> clearSlots,
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId) {
    if (clearSlots.contains(DefaultAccountSlot.INVENTORY) && inventoryAccountId != null) {
      rejectClearSetConflict("inventoryAccountId");
    }
    if (clearSlots.contains(DefaultAccountSlot.COGS) && cogsAccountId != null) {
      rejectClearSetConflict("cogsAccountId");
    }
    if (clearSlots.contains(DefaultAccountSlot.REVENUE) && revenueAccountId != null) {
      rejectClearSetConflict("revenueAccountId");
    }
    if (clearSlots.contains(DefaultAccountSlot.DISCOUNT)
        && (discountAccountId != null || fgDiscountAccountId != null)) {
      rejectClearSetConflict("discountAccountId");
    }
    if (clearSlots.contains(DefaultAccountSlot.TAX) && taxAccountId != null) {
      rejectClearSetConflict("taxAccountId");
    }
  }

  private void rejectClearSetConflict(String field) {
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        field + " cannot be set and cleared in the same default-account update");
  }

  private void applyClearSlots(Company company, Set<DefaultAccountSlot> clearSlots) {
    if (clearSlots.isEmpty()) {
      return;
    }
    if (clearSlots.contains(DefaultAccountSlot.INVENTORY)) {
      company.setDefaultInventoryAccountId(null);
    }
    if (clearSlots.contains(DefaultAccountSlot.COGS)) {
      company.setDefaultCogsAccountId(null);
    }
    if (clearSlots.contains(DefaultAccountSlot.REVENUE)) {
      company.setDefaultRevenueAccountId(null);
    }
    if (clearSlots.contains(DefaultAccountSlot.DISCOUNT)) {
      company.setDefaultDiscountAccountId(null);
    }
    if (clearSlots.contains(DefaultAccountSlot.TAX)) {
      company.setDefaultTaxAccountId(null);
      company.setGstOutputTaxAccountId(null);
      company.setGstPayableAccountId(null);
    }
  }

  private void recordDefaultAccountsAudit(
      Company company,
      DefaultAccounts before,
      DefaultAccounts after,
      Set<DefaultAccountSlot> slots) {
    if (accountingComplianceAuditService == null || before.equals(after)) {
      return;
    }
    accountingComplianceAuditService.recordDefaultAccountsChange(company, before, after, slots);
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

  public enum DefaultAccountSlot {
    INVENTORY,
    COGS,
    REVENUE,
    DISCOUNT,
    TAX
  }

  public record DefaultAccounts(
      Long inventoryAccountId,
      Long cogsAccountId,
      Long revenueAccountId,
      Long discountAccountId,
      Long fgDiscountAccountId,
      Long taxAccountId) {}
}
