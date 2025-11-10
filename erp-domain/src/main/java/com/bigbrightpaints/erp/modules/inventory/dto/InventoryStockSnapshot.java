package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record InventoryStockSnapshot(String name,
                                     String sku,
                                     BigDecimal currentStock,
                                     BigDecimal reorderLevel,
                                     String status) {}
