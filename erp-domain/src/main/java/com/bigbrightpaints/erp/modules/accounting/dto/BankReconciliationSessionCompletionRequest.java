package com.bigbrightpaints.erp.modules.accounting.dto;

public record BankReconciliationSessionCompletionRequest(
        String note,
        Long accountingPeriodId) {
}
