package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record BankReconciliationSessionItemsUpdateRequest(
    List<Long> addJournalLineIds,
    List<Long> removeJournalLineIds,
    String note,
    List<BankStatementMatchRequest> matches) {

  public BankReconciliationSessionItemsUpdateRequest(
      List<Long> addJournalLineIds, List<Long> removeJournalLineIds, String note) {
    this(addJournalLineIds, removeJournalLineIds, note, null);
  }

  public record BankStatementMatchRequest(
      Long bankItemId, Long journalEntryId, Long journalLineId) {}
}
