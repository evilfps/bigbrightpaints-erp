package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record JournalEntryRequest(
        String referenceNumber,
        @NotNull LocalDate entryDate,
        String memo,
        Long dealerId,
        Long supplierId,
        Boolean adminOverride,
        @NotEmpty List<@Valid JournalLineRequest> lines,
        String currency,
        BigDecimal fxRate,
        String sourceModule,
        String sourceReference,
        String journalType,
        List<String> attachmentReferences
) {
    public JournalEntryRequest(String referenceNumber,
                               @NotNull LocalDate entryDate,
                               String memo,
                               Long dealerId,
                               Long supplierId,
                               Boolean adminOverride,
                               @NotEmpty List<@Valid JournalLineRequest> lines) {
        this(referenceNumber, entryDate, memo, dealerId, supplierId, adminOverride, lines, null, null, null, null, null, List.of());
    }

    public JournalEntryRequest(String referenceNumber,
                               @NotNull LocalDate entryDate,
                               String memo,
                               Long dealerId,
                               Long supplierId,
                               Boolean adminOverride,
                               @NotEmpty List<@Valid JournalLineRequest> lines,
                               String currency,
                               BigDecimal fxRate) {
        this(referenceNumber, entryDate, memo, dealerId, supplierId, adminOverride, lines, currency, fxRate, null, null, null, List.of());
    }

    public JournalEntryRequest(String referenceNumber,
                               @NotNull LocalDate entryDate,
                               String memo,
                               Long dealerId,
                               Long supplierId,
                               Boolean adminOverride,
                               @NotEmpty List<@Valid JournalLineRequest> lines,
                               String currency,
                               BigDecimal fxRate,
                               String sourceModule,
                               String sourceReference,
                               String journalType) {
        this(referenceNumber, entryDate, memo, dealerId, supplierId, adminOverride, lines, currency, fxRate, sourceModule, sourceReference, journalType, List.of());
    }

    public record JournalLineRequest(@NotNull Long accountId,
                                     String description,
                                     @NotNull BigDecimal debit,
                                     @NotNull BigDecimal credit,
                                     BigDecimal foreignCurrencyAmount) {
        public JournalLineRequest(@NotNull Long accountId,
                                  String description,
                                  @NotNull BigDecimal debit,
                                  @NotNull BigDecimal credit) {
            this(accountId, description, debit, credit, null);
        }
    }
}
