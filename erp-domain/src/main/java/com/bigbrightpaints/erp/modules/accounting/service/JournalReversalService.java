package com.bigbrightpaints.erp.modules.accounting.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;

@Service
class JournalReversalService {

  private final AccountingCoreSupport accountingCoreSupport;

  JournalReversalService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
    return accountingCoreSupport.reverseJournalEntry(entryId, request);
  }

  List<JournalEntryDto> cascadeReverseRelatedEntries(
      Long primaryEntryId, JournalEntryReversalRequest request) {
    return accountingCoreSupport.cascadeReverseRelatedEntries(primaryEntryId, request);
  }
}
