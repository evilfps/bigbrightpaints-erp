package com.bigbrightpaints.erp.modules.factory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to pack a bulk FG batch into sized child SKUs.
 * Converts parent SKU (e.g., Safari-WHITE) into child SKUs (Safari-WHITE-1L, Safari-WHITE-4L).
 */
public record BulkPackRequest(
    @NotNull(message = "Bulk batch ID is required")
    Long bulkBatchId,

    @NotEmpty(message = "At least one pack line is required")
    @Valid
    List<PackLine> packs,

    LocalDate packDate,
    String packedBy,
    String notes,
    /**
     * Optional idempotency key. If omitted, the server derives a deterministic key from the request payload.
     * Supplying a key allows callers to intentionally create multiple identical pack operations safely.
     */
    String idempotencyKey,
    Boolean packagingAlreadyConsumed
) {
    public BulkPackRequest(Long bulkBatchId,
                           List<PackLine> packs,
                           LocalDate packDate,
                           String packedBy,
                           String notes,
                           String idempotencyKey) {
        this(bulkBatchId, packs, packDate, packedBy, notes, idempotencyKey, null);
    }

    public boolean shouldConsumePackaging() {
        return !Boolean.TRUE.equals(packagingAlreadyConsumed);
    }

    /**
     * A single packing line: creates child batches for a specific size SKU.
     */
    public record PackLine(
        @NotNull(message = "Child SKU ID is required")
        Long childSkuId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        BigDecimal quantity,

        String sizeLabel,
        String unit
    ) {}
}
