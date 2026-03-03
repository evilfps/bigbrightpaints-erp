package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RawMaterialTraceDto(
        Long movementId,
        Long rawMaterialId,
        String rawMaterialSku,
        String rawMaterialName,
        Long rawMaterialBatchId,
        String rawMaterialBatchCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        String movementType,
        String referenceType,
        String referenceId,
        Instant movedAt,
        Long journalEntryId
) {
}
