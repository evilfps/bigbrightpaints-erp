package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;

@Service
class PeriodValidationService {

  private final AccountingCoreSupport accountingCoreSupport;

  PeriodValidationService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  void validateEntryDate(
      Company company, LocalDate entryDate, boolean overrideRequested, boolean overrideAuthorized) {
    accountingCoreSupport.validateEntryDate(
        company, entryDate, overrideRequested, overrideAuthorized);
  }

  boolean hasEntryDateOverrideAuthority() {
    return accountingCoreSupport.hasEntryDateOverrideAuthority();
  }

  <T> T runWithSystemEntryDateOverride(Supplier<T> action) {
    return accountingCoreSupport.runWithSystemEntryDateOverride(action);
  }

  JournalEntryDto createJournalEntryForReversal(
      JournalEntryRequest payload, boolean allowClosedPeriodOverride) {
    return accountingCoreSupport.createJournalEntryForReversal(payload, allowClosedPeriodOverride);
  }
}
