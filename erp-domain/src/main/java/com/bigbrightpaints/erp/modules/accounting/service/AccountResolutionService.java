package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

@Service
class AccountResolutionService {

  private final CompanyScopedSalesLookupService salesLookupService;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyDefaultAccountsService companyDefaultAccountsService;
  private final CompanyClock companyClock;

  AccountResolutionService(
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyDefaultAccountsService companyDefaultAccountsService,
      CompanyClock companyClock) {
    this.salesLookupService = salesLookupService;
    this.purchasingLookupService = purchasingLookupService;
    this.accountingLookupService = accountingLookupService;
    this.companyDefaultAccountsService = companyDefaultAccountsService;
    this.companyClock = companyClock;
  }

  Dealer requireDealer(Company company, Long dealerId) {
    return salesLookupService.requireDealer(company, dealerId);
  }

  Supplier requireSupplier(Company company, Long supplierId) {
    return purchasingLookupService.requireSupplier(company, supplierId);
  }

  Account requireAccount(Company company, Long accountId) {
    return accountingLookupService.requireAccount(company, accountId);
  }

  Account requireCashAccountForSettlement(Company company, Long accountId, String operation) {
    return requireCashAccountForSettlement(company, accountId, operation, true);
  }

  Account requireCashAccountForSettlement(
      Company company, Long accountId, String operation, boolean requireActive) {
    Account account = requireAccount(company, accountId);
    if (requireActive && !account.isActive()) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Cash/bank account for " + operation + " must be active")
          .withDetail("operation", operation)
          .withDetail("accountId", account.getId())
          .withDetail("accountCode", account.getCode())
          .withDetail("active", false);
    }
    if (account.getType() != null && account.getType() != AccountType.ASSET) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Cash/bank account for " + operation + " must be an ASSET account")
          .withDetail("operation", operation)
          .withDetail("accountId", account.getId())
          .withDetail("accountCode", account.getCode())
          .withDetail("accountType", account.getType().name());
    }
    if (isReceivableAccount(account) || isPayableAccount(account)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Cash/bank account for " + operation + " cannot be AR/AP control account")
          .withDetail("operation", operation)
          .withDetail("accountId", account.getId())
          .withDetail("accountCode", account.getCode())
          .withDetail("accountName", account.getName());
    }
    return account;
  }

  Long resolveAutoSettlementCashAccountId(
      Company company, Long requestedCashAccountId, String operation) {
    return companyDefaultAccountsService.resolveAutoSettlementCashAccountId(
        company, requestedCashAccountId, operation);
  }

  Account requireDealerReceivable(Dealer dealer) {
    Account account = dealer != null ? dealer.getReceivableAccount() : null;
    if (account == null || account.getId() == null) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR, "Dealer receivable account is required");
    }
    return account;
  }

  Account requireSupplierPayable(Supplier supplier) {
    Account account = supplier != null ? supplier.getPayableAccount() : null;
    if (account == null || account.getId() == null) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR, "Supplier payable account is required");
    }
    return account;
  }

  boolean isReceivableAccount(Account account) {
    if (account == null || account.getType() != AccountType.ASSET) {
      return false;
    }
    String code =
        account.getCode() == null
            ? ""
            : account.getCode().trim().toUpperCase(java.util.Locale.ROOT);
    String name =
        account.getName() == null
            ? ""
            : account.getName().trim().toUpperCase(java.util.Locale.ROOT);
    return isTokenMatch(code, "AR") || name.contains("ACCOUNTS RECEIVABLE");
  }

  boolean isPayableAccount(Account account) {
    if (account == null || account.getType() != AccountType.LIABILITY) {
      return false;
    }
    String code =
        account.getCode() == null
            ? ""
            : account.getCode().trim().toUpperCase(java.util.Locale.ROOT);
    String name =
        account.getName() == null
            ? ""
            : account.getName().trim().toUpperCase(java.util.Locale.ROOT);
    return isTokenMatch(code, "AP") || name.contains("ACCOUNTS PAYABLE");
  }

  LocalDate currentDate(Company company) {
    return companyClock.today(company);
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
}
