package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record InventoryValuationGroupDto(
        String groupType,
        String groupKey,
        BigDecimal totalValue,
        long itemCount,
        long lowStockItems
) {
}
