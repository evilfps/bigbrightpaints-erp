package com.bigbrightpaints.erp.modules.purchasing.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RawMaterialPurchaseLineRequest(
        @NotNull Long rawMaterialId,
        String batchCode,
        @NotNull BigDecimal quantity,
        String unit,
        @NotNull BigDecimal costPerUnit,
        String notes
) {}
