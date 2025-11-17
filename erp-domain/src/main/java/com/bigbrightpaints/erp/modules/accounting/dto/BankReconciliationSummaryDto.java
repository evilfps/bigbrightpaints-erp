package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BankReconciliationSummaryDto(
        Long accountId,
        String accountCode,
        String accountName,
        LocalDate statementDate,
        BigDecimal ledgerBalance,
        BigDecimal statementEndingBalance,
        BigDecimal outstandingDeposits,
        BigDecimal outstandingChecks,
        BigDecimal difference,
        boolean balanced,
        List<BankReconciliationItemDto> unclearedDeposits,
        List<BankReconciliationItemDto> unclearedChecks) {

    public record BankReconciliationItemDto(
            Long journalEntryId,
            String referenceNumber,
            LocalDate entryDate,
            String memo,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal netAmount) {}
}

