package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record JournalEntryRequest(
        @NotBlank String referenceNumber,
        @NotNull LocalDate entryDate,
        String memo,
        Long dealerId,
        @NotEmpty List<@Valid JournalLineRequest> lines
) {
    public record JournalLineRequest(@NotNull Long accountId,
                                     String description,
                                     @NotNull BigDecimal debit,
                                     @NotNull BigDecimal credit) {}
}
