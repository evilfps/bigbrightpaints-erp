package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.util.Map;

public record BulkPackCostingContext(BigDecimal bulkUnitCost,
                                     BigDecimal fallbackPackagingCostPerUnit,
                                     Map<Integer, BigDecimal> lineCosts,
                                     boolean hasLineCosts) {
}
