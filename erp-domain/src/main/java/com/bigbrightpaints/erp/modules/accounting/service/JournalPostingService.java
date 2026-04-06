package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;

@Service
class JournalPostingService {

  private final AccountingCoreSupport accountingCoreSupport;

  JournalPostingService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return accountingCoreSupport.createJournalEntry(request);
  }

  JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return accountingCoreSupport.createStandardJournal(request);
  }

  JournalEntryDto createManualJournal(ManualJournalRequest request) {
    return accountingCoreSupport.createManualJournal(request);
  }
}
