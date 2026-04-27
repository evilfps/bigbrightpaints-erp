package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
class AccountCatalogService {

  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final AccountingDtoMapperService accountingDtoMapperService;
  private final ApplicationEventPublisher eventPublisher;
  private final AccountingComplianceAuditService accountingComplianceAuditService;

  AccountCatalogService(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingDtoMapperService accountingDtoMapperService,
      ApplicationEventPublisher eventPublisher,
      org.springframework.beans.factory.ObjectProvider<AccountingComplianceAuditService>
          accountingComplianceAuditServiceProvider) {
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.accountingDtoMapperService = accountingDtoMapperService;
    this.eventPublisher = eventPublisher;
    this.accountingComplianceAuditService =
        accountingComplianceAuditServiceProvider.getIfAvailable();
  }

  List<AccountDto> listAccounts() {
    Company company = companyContextService.requireCurrentCompany();
    return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
        .map(accountingDtoMapperService::toAccountDto)
        .toList();
  }

  @Transactional
  AccountDto createAccount(AccountRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String normalizedCode = normalizeAccountCode(request.code());
    String normalizedName = normalizeAccountName(request.name());
    ensureCodeAvailable(company, normalizedCode);

    Account account = new Account();
    account.setCompany(company);
    account.setCode(normalizedCode);
    account.setName(normalizedName);
    account.setType(request.type());
    if (request.parentId() != null) {
      Account parent =
          accountRepository
              .findByCompanyAndId(company, request.parentId())
              .orElseThrow(
                  () ->
                      new ApplicationException(
                          ErrorCode.VALIDATION_INVALID_REFERENCE, "Parent account not found"));
      if (parent.getType() != request.type()) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Child account must have same type as parent");
      }
      account.setParent(parent);
    }
    Account saved = saveWithDuplicateGuard(company, account, normalizedCode);
    if (company.getId() != null) {
      eventPublisher.publishEvent(
          new com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent(
              company.getId()));
    }
    if (accountingComplianceAuditService != null) {
      accountingComplianceAuditService.recordAccountCreated(company, saved);
    }
    return accountingDtoMapperService.toAccountDto(saved);
  }

  private void ensureCodeAvailable(Company company, String normalizedCode) {
    accountRepository
        .findByCompanyAndCodeIgnoreCase(company, normalizedCode)
        .ifPresent(
            existing -> {
              throw duplicateCodeException(company, normalizedCode)
                  .withDetail("existingAccountId", existing.getId());
            });
  }

  private Account saveWithDuplicateGuard(Company company, Account account, String normalizedCode) {
    try {
      return accountRepository.save(account);
    } catch (DataIntegrityViolationException ex) {
      if (isDuplicateCodeViolation(ex)) {
        throw duplicateCodeException(company, normalizedCode);
      }
      throw ex;
    }
  }

  private ApplicationException duplicateCodeException(Company company, String normalizedCode) {
    String companyCode =
        company != null && company.getCode() != null ? company.getCode() : "UNKNOWN";
    return new ApplicationException(
            ErrorCode.BUSINESS_DUPLICATE_ENTRY,
            "Account code '" + normalizedCode + "' already exists for company " + companyCode)
        .withDetail("field", "code")
        .withDetail("code", normalizedCode)
        .withDetail("companyCode", companyCode);
  }

  private boolean isDuplicateCodeViolation(Throwable error) {
    Throwable cursor = error;
    while (cursor != null) {
      String message = cursor.getMessage();
      if (message != null) {
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.contains("uq_accounts_company_code_ci")
            || normalized.contains("accounts_company_id_code_key")
            || (normalized.contains("accounts")
                && normalized.contains("company_id")
                && normalized.contains("code")
                && normalized.contains("unique"))) {
          return true;
        }
      }
      cursor = cursor.getCause();
    }
    return false;
  }

  private String normalizeAccountCode(String code) {
    return normalizeRequiredField(code, "code");
  }

  private String normalizeAccountName(String name) {
    return normalizeRequiredField(name, "name");
  }

  private String normalizeRequiredField(String value, String fieldName) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Account " + fieldName + " is required")
          .withDetail(fieldName, value);
    }
    return normalized;
  }
}
