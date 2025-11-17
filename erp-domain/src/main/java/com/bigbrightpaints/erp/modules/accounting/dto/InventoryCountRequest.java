package com.bigbrightpaints.erp.modules.accounting.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InventoryCountRequest(
        @NotNull InventoryCountTarget target,
        @NotNull Long itemId,
        @NotNull BigDecimal physicalQuantity,
        @NotNull BigDecimal unitCost,
        @NotNull Long adjustmentAccountId,
        LocalDate countDate,
        Long accountingPeriodId,
        Boolean markAsComplete,
        String note) {}

