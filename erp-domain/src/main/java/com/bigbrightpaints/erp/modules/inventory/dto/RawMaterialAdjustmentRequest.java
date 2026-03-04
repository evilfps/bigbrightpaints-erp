package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RawMaterialAdjustmentRequest(
        LocalDate adjustmentDate,
        @NotNull AdjustmentDirection direction,
        @NotNull Long adjustmentAccountId,
        String reason,
        Boolean adminOverride,
        @NotBlank String idempotencyKey,
        @NotEmpty List<@Valid LineRequest> lines
) {

    public enum AdjustmentDirection {
        INCREASE,
        DECREASE
    }

    public record LineRequest(
            @NotNull Long rawMaterialId,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitCost,
            String note
    ) {
    }
}
