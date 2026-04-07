package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record FinishedGoodStockSummaryDto(
    Long finishedGoodId,
    String productCode,
    String name,
    BigDecimal totalStock,
    BigDecimal reservedStock,
    BigDecimal availableStock,
    BigDecimal weightedAverageCost) {}
