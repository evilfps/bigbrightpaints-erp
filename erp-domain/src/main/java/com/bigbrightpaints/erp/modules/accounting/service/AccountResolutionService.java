package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;

@Service
class AccountResolutionService {

  private final CompanyEntityLookup companyEntityLookup;
  private final AccountRepository accountRepository;
  private final CompanyClock companyClock;

  AccountResolutionService(
      CompanyEntityLookup companyEntityLookup,
      AccountRepository accountRepository,
      CompanyClock companyClock) {
    this.companyEntityLookup = companyEntityLookup;
    this.accountRepository = accountRepository;
    this.companyClock = companyClock;
  }

  Dealer requireDealer(Company company, Long dealerId) {
    return companyEntityLookup.requireDealer(company, dealerId);
  }

  Supplier requireSupplier(Company company, Long supplierId) {
    return companyEntityLookup.requireSupplier(company, supplierId);
  }

  Account requireAccount(Company company, Long accountId) {
    return companyEntityLookup.requireAccount(company, accountId);
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
    if (requestedCashAccountId != null) {
      return requestedCashAccountId;
    }
    return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
        .filter(Account::isActive)
        .filter(account -> account.getType() == null || account.getType() == AccountType.ASSET)
        .filter(account -> !isReceivableAccount(account) && !isPayableAccount(account))
        .filter(
            account -> {
              String code =
                  account.getCode() == null ? "" : account.getCode().toUpperCase(Locale.ROOT);
              String name =
                  account.getName() == null ? "" : account.getName().toUpperCase(Locale.ROOT);
              return code.contains("CASH")
                  || code.contains("BANK")
                  || name.contains("CASH")
                  || name.contains("BANK");
            })
        .map(Account::getId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(
            () ->
                new ApplicationException(
                    ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "cashAccountId is required when no active default cash/bank account is"
                        + " configured for "
                        + operation));
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
    if (account == null) {
      return false;
    }
    String code = account.getCode() == null ? "" : account.getCode().trim().toUpperCase(Locale.ROOT);
    return code.startsWith("AR") || code.contains("RECEIVABLE");
  }

  boolean isPayableAccount(Account account) {
    if (account == null) {
      return false;
    }
    String code = account.getCode() == null ? "" : account.getCode().trim().toUpperCase(Locale.ROOT);
    return code.startsWith("AP") || code.contains("PAYABLE");
  }

  LocalDate currentDate(Company company) {
    return companyClock.today(company);
  }
}
