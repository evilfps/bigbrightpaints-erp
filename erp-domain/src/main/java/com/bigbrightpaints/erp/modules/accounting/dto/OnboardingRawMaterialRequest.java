package com.bigbrightpaints.erp.modules.accounting.dto;

import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record OnboardingRawMaterialRequest(
        @NotBlank String name,
        @NotBlank String sku,
        @NotBlank String unitType,
        MaterialType materialType,
        @DecimalMin(value = "0.00") BigDecimal reorderLevel,
        @DecimalMin(value = "0.00") BigDecimal minStock,
        @DecimalMin(value = "0.00") BigDecimal maxStock,
        Long inventoryAccountId
) {}
