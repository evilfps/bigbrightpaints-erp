package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryBatchMovementDto(
        Long id,
        String movementType,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal totalCost,
        Instant createdAt,
        String source,
        String referenceType,
        String referenceId,
        Long journalEntryId,
        Long packingSlipId
) {
}
