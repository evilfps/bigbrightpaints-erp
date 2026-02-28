package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record InventoryBatchTraceabilityDto(
        Long batchId,
        UUID publicId,
        String batchType,
        String itemCode,
        String itemName,
        String batchNumber,
        Instant manufacturingDate,
        LocalDate expiryDate,
        BigDecimal quantityTotal,
        BigDecimal quantityAvailable,
        BigDecimal unitCost,
        String source,
        List<InventoryBatchMovementDto> movements
) {
}
