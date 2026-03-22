package com.bigbrightpaints.erp.modules.production.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CatalogProductEntryResponse(
        boolean preview,
        UUID variantGroupId,
        String productFamilyName,
        Long brandId,
        String brandName,
        String brandCode,
        String category,
        String unitOfMeasure,
        String hsnCode,
        BigDecimal basePrice,
        BigDecimal gstRate,
        BigDecimal minDiscountPercent,
        BigDecimal minSellingPrice,
        Map<String, Object> metadata,
        int candidateCount,
        DownstreamEffects downstreamEffects,
        List<Member> members,
        List<Conflict> conflicts
) {

    public record DownstreamEffects(
            int finishedGoodMembers,
            int rawMaterialMembers
    ) {
    }

    public record Member(
            Long id,
            UUID publicId,
            String sku,
            String productName,
            String color,
            String size,
            SkuReadinessDto readiness
    ) {
    }

    public record Conflict(
            String sku,
            String reason,
            String productName,
            String color,
            String size
    ) {
    }
}
