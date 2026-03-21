package com.bigbrightpaints.erp.modules.production.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record CatalogProductRequest(
        @NotNull(message = "Brand is required")
        Long brandId,
        @NotBlank(message = "Product name is required")
        String name,
        List<String> colors,
        List<String> sizes,
        List<@Valid CatalogProductCartonSizeRequest> cartonSizes,
        @NotBlank(message = "Unit of measure is required")
        String unitOfMeasure,
        @NotBlank(message = "HSN code is required")
        String hsnCode,
        @DecimalMin(value = "0.00", message = "Base price cannot be negative")
        BigDecimal basePrice,
        @NotNull(message = "GST rate is required")
        @DecimalMin(value = "0.00", message = "GST rate cannot be negative")
        @DecimalMax(value = "100.00", message = "GST rate cannot be greater than 100")
        BigDecimal gstRate,
        @DecimalMin(value = "0.00", message = "Minimum discount percent cannot be negative")
        @DecimalMax(value = "100.00", message = "Minimum discount percent cannot be greater than 100")
        BigDecimal minDiscountPercent,
        @DecimalMin(value = "0.00", message = "Minimum selling price cannot be negative")
        BigDecimal minSellingPrice,
        Map<String, Object> metadata,
        Boolean active
) {
}
