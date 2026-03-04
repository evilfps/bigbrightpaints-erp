package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record BankReconciliationSessionDetailDto(
        Long sessionId,
        String referenceNumber,
        Long bankAccountId,
        String bankAccountCode,
        String bankAccountName,
        LocalDate statementDate,
        BigDecimal statementEndingBalance,
        String status,
        Long accountingPeriodId,
        String note,
        String createdBy,
        Instant createdAt,
        String completedBy,
        Instant completedAt,
        List<ClearedItemDto> clearedItems,
        List<BankReconciliationSummaryDto.BankReconciliationItemDto> unclearedDeposits,
        List<BankReconciliationSummaryDto.BankReconciliationItemDto> unclearedChecks,
        BankReconciliationSummaryDto summary) {

    public record ClearedItemDto(
            Long itemId,
            Long journalLineId,
            Long journalEntryId,
            String referenceNumber,
            LocalDate entryDate,
            String memo,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal netAmount,
            Instant clearedAt,
            String clearedBy) {
    }
}
