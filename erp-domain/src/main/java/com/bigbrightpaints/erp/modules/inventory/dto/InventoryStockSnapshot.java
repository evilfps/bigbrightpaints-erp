package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record InventoryStockSnapshot(
    Long materialId,
    String name,
    String sku,
    BigDecimal quantity,
    BigDecimal reorderLevel,
    BigDecimal minStock,
    BigDecimal maxStock,
    String status) {}
