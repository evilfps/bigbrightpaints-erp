package com.bigbrightpaints.erp.modules.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RawMaterialRequest(
        @NotBlank String name,
        @NotBlank String sku,
        @NotBlank String unitType,
        @NotNull BigDecimal reorderLevel,
        @NotNull BigDecimal minStock,
        @NotNull BigDecimal maxStock
) {}
