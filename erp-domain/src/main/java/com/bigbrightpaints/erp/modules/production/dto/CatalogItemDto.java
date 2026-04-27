package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record CatalogItemDto(
    Long id,
    UUID publicId,
    Long rawMaterialId,
    Long brandId,
    String brandName,
    String brandCode,
    UUID variantGroupId,
    String productFamilyName,
    String name,
    String code,
    String itemClass,
    String color,
    String size,
    String unitOfMeasure,
    String hsnCode,
    BigDecimal basePrice,
    BigDecimal gstRate,
    BigDecimal minDiscountPercent,
    BigDecimal minSellingPrice,
    Map<String, Object> metadata,
    boolean active,
    CatalogItemStockDto stock,
    SkuReadinessDto readiness) {}
