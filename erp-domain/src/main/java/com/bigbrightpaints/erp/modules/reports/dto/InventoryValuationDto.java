package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;

public record InventoryValuationDto(BigDecimal totalValue,
                                     long lowStockItems) {}
