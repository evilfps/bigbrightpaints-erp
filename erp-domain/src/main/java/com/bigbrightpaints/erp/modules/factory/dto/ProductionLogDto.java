package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductionLogDto(
        Long id,
        UUID publicId,
        String productionCode,
        Instant producedAt,
        String brandName,
        String productName,
        String skuCode,
        String batchColour,
        BigDecimal batchSize,
        String unitOfMeasure,
        BigDecimal mixedQuantity,
        BigDecimal totalPackedQuantity,
        BigDecimal wastageQuantity,
        String status,
        String createdBy,
        BigDecimal unitCost,
        BigDecimal materialCostTotal,
        BigDecimal laborCostTotal,
        BigDecimal overheadCostTotal,
        Long salesOrderId,
        String salesOrderNumber
) {}
