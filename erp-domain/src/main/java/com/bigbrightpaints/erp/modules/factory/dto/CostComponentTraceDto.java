package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;

public record CostComponentTraceDto(
        BigDecimal productionMaterialCost,
        BigDecimal laborCost,
        BigDecimal overheadCost,
        BigDecimal packagingCost,
        BigDecimal totalCost,
        BigDecimal mixedQuantity,
        BigDecimal packedQuantity,
        BigDecimal blendedUnitCost
) {
}
