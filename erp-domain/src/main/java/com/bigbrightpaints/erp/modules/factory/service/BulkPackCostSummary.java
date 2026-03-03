package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.util.Map;

public record BulkPackCostSummary(BigDecimal totalCost,
                                  Map<Long, BigDecimal> accountTotals,
                                  Map<Integer, BigDecimal> lineCosts) {

    public static BulkPackCostSummary empty() {
        return new BulkPackCostSummary(BigDecimal.ZERO, Map.of(), Map.of());
    }
}
