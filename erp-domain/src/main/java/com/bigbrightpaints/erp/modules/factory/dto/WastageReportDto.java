package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WastageReportDto(
        Long productionLogId,
        String productionCode,
        String productName,
        String batchColour,
        BigDecimal mixedQuantity,
        BigDecimal totalPackedQuantity,
        BigDecimal wastageQuantity,
        BigDecimal wastagePercentage,
        BigDecimal wastageValue,
        Instant producedAt
) {
}
