package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record InventoryValuationItemDto(
        Long inventoryItemId,
        String inventoryType,
        String code,
        String name,
        String category,
        String brand,
        BigDecimal quantityOnHand,
        BigDecimal reservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        boolean lowStock
) {
}
