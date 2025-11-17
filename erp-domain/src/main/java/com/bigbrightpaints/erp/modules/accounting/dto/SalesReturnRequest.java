package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record SalesReturnRequest(
        @NotNull Long invoiceId,
        @NotBlank String reason,
        @NotEmpty List<@Valid ReturnLine> lines
) {
    public record ReturnLine(
            @NotNull Long invoiceLineId,
            @NotNull BigDecimal quantity
    ) {}
}
