package com.bigbrightpaints.erp.modules.production.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

public record CatalogItemRequest(
        @NotNull(message = "brandId is required")
        Long brandId,
        @NotBlank(message = "name is required")
        String name,
        @NotBlank(message = "itemClass is required")
        String itemClass,
        String color,
        String size,
        @NotBlank(message = "unitOfMeasure is required")
        String unitOfMeasure,
        @NotBlank(message = "hsnCode is required")
        String hsnCode,
        @DecimalMin(value = "0.00", message = "basePrice cannot be negative")
        BigDecimal basePrice,
        @NotNull(message = "gstRate is required")
        @DecimalMin(value = "0.00", message = "gstRate cannot be negative")
        @DecimalMax(value = "100.00", message = "gstRate cannot be greater than 100")
        BigDecimal gstRate,
        @DecimalMin(value = "0.00", message = "minDiscountPercent cannot be negative")
        @DecimalMax(value = "100.00", message = "minDiscountPercent cannot be greater than 100")
        BigDecimal minDiscountPercent,
        @DecimalMin(value = "0.00", message = "minSellingPrice cannot be negative")
        BigDecimal minSellingPrice,
        Map<String, Object> metadata,
        Boolean active
) {
}
