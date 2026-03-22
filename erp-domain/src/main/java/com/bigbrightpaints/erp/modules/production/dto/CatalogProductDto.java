package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CatalogProductDto(
        Long id,
        UUID publicId,
        Long brandId,
        String brandName,
        String brandCode,
        String name,
        String sku,
        String category,
        String itemClass,
        UUID variantGroupId,
        String productFamilyName,
        List<String> colors,
        List<String> sizes,
        List<CatalogProductCartonSizeDto> cartonSizes,
        String unitOfMeasure,
        String hsnCode,
        BigDecimal basePrice,
        BigDecimal gstRate,
        BigDecimal minDiscountPercent,
        BigDecimal minSellingPrice,
        Map<String, Object> metadata,
        boolean active,
        SkuReadinessDto readiness
) {
}
