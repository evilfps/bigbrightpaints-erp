package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record ProductionProductDto(
        Long id,
        UUID publicId,
        Long brandId,
        String brandName,
        String brandCode,
        String productName,
        String category,
        String defaultColour,
        String sizeLabel,
        String unitOfMeasure,
        String hsnCode,
        String skuCode,
        UUID variantGroupId,
        String productFamilyName,
        boolean active,
        BigDecimal basePrice,
        BigDecimal gstRate,
        BigDecimal minDiscountPercent,
        BigDecimal minSellingPrice,
        Map<String, Object> metadata
) {}
