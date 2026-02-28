package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record FinishedGoodLowStockThresholdRequest(
        @NotNull @PositiveOrZero BigDecimal threshold
) {
}
