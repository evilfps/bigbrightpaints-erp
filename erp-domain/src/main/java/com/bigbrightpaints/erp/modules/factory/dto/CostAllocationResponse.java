package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.util.List;

public record CostAllocationResponse(
        Integer year,
        Integer month,
        int batchesProcessed,
        BigDecimal totalLitersProduced,
        BigDecimal totalLaborAllocated,
        BigDecimal totalOverheadAllocated,
        BigDecimal avgCostPerLiter,
        List<Long> journalEntryIds,
        String summary
) {
}
