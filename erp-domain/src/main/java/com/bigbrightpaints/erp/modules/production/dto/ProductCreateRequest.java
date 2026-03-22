package com.bigbrightpaints.erp.modules.production.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Map;

public record ProductCreateRequest(
        Long brandId,
        String brandName,
        String brandCode,
        @NotBlank(message = "Product name is required")
        String productName,
        @NotBlank(message = "Category is required")
        String category,
        @NotBlank(message = "Item class is required")
        String itemClass,
        String defaultColour,
        String sizeLabel,
        String unitOfMeasure,
        String hsnCode,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String customSkuCode,
        BigDecimal basePrice,
        BigDecimal gstRate,
        BigDecimal minDiscountPercent,
        BigDecimal minSellingPrice,
        Map<String, Object> metadata
) {}
