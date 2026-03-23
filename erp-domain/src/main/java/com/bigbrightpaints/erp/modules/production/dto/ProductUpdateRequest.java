package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.Map;

public record ProductUpdateRequest(
        String productName,
        String category,
        String itemClass,
        String defaultColour,
        String sizeLabel,
        String unitOfMeasure,
        String hsnCode,
        BigDecimal basePrice,
        BigDecimal gstRate,
        BigDecimal minDiscountPercent,
        BigDecimal minSellingPrice,
        Map<String, Object> metadata,
        Boolean active
) {}
