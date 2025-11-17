package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record UnpackedBatchDto(
        Long id,
        String productionCode,
        String productName,
        String batchColour,
        BigDecimal mixedQuantity,
        BigDecimal packedQuantity,
        BigDecimal remainingQuantity,
        String status,
        Instant producedAt
) {}
