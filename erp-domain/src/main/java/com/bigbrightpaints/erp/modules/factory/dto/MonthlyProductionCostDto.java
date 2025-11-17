package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;

public record MonthlyProductionCostDto(
        Integer year,
        Integer month,
        int batchCount,
        BigDecimal totalLitersProduced,
        BigDecimal totalMaterialCost,
        BigDecimal totalLaborCost,
        BigDecimal totalOverheadCost,
        BigDecimal totalCost,
        BigDecimal avgCostPerLiter,
        BigDecimal totalWastageQuantity,
        BigDecimal wastagePercentage
) {
}
