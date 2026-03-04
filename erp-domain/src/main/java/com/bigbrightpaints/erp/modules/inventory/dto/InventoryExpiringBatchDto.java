package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InventoryExpiringBatchDto(
        String batchType,
        Long batchId,
        UUID publicId,
        String itemCode,
        String itemName,
        String batchCode,
        BigDecimal quantityAvailable,
        BigDecimal unitCost,
        Instant manufacturedAt,
        LocalDate expiryDate,
        Long daysUntilExpiry
) {
}
