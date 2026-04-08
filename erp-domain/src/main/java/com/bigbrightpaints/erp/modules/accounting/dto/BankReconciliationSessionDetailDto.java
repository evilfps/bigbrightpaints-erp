package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record BankReconciliationSessionDetailDto(
    Long sessionId,
    String referenceNumber,
    Long bankAccountId,
    String bankAccountCode,
    String bankAccountName,
    @JsonFormat(pattern = "yyyy-MM-dd") LocalDate statementDate,
    BigDecimal statementEndingBalance,
    String status,
    Long accountingPeriodId,
    String note,
    String createdBy,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
    String completedBy,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant completedAt,
    List<StatementItemDto> matchedItems,
    List<StatementItemDto> unmatchedItems,
    BankReconciliationSummaryDto summary) {

  public record StatementItemDto(
      Long itemId,
      Long bankItemId,
      Long journalLineId,
      Long journalEntryId,
      String referenceNumber,
      @JsonFormat(pattern = "yyyy-MM-dd") LocalDate entryDate,
      String memo,
      BigDecimal debit,
      BigDecimal credit,
      BigDecimal netAmount,
      @JsonFormat(shape = JsonFormat.Shape.STRING) Instant matchedAt,
      String matchedBy) {}
}
