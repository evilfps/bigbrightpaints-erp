package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record RawMaterialBatchRequest(
        @NotBlank String batchCode,
        @NotNull BigDecimal quantity,
        @NotBlank String unit,
        @NotNull BigDecimal costPerUnit,
        String supplier,
        String notes
) {}
