package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record FinishedGoodLowStockThresholdDto(
        Long finishedGoodId,
        String productCode,
        BigDecimal threshold
) {
}
