package com.bigbrightpaints.erp.modules.accounting.dto;

import java.util.List;

public record BankReconciliationSessionItemsUpdateRequest(
        List<Long> addJournalLineIds,
        List<Long> removeJournalLineIds,
        String note) {
}
