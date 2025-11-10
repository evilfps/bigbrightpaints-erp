package com.bigbrightpaints.erp.modules.inventory.dto;

public record StockSummaryDto(long totalMaterials,
                              long lowStockMaterials,
                              long criticalStockMaterials,
                              long totalBatches) {}
