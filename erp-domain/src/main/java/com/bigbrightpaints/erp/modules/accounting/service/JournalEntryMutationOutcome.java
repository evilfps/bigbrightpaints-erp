package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;

record JournalEntryMutationOutcome(JournalEntryDto journalEntry, boolean replayed) {

  static JournalEntryMutationOutcome created(JournalEntryDto journalEntry) {
    return new JournalEntryMutationOutcome(journalEntry, false);
  }

  static JournalEntryMutationOutcome replayed(JournalEntryDto journalEntry) {
    return new JournalEntryMutationOutcome(journalEntry, true);
  }
}
