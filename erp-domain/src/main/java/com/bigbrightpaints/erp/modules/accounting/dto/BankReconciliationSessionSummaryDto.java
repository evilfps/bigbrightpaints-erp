package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record BankReconciliationSessionSummaryDto(
        Long sessionId,
        String referenceNumber,
        Long bankAccountId,
        String bankAccountCode,
        String bankAccountName,
        LocalDate statementDate,
        BigDecimal statementEndingBalance,
        String status,
        String createdBy,
        Instant createdAt,
        Instant completedAt,
        BankReconciliationSummaryDto summary,
        int clearedItemCount) {
}
