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

    @Valid
    List<MaterialConsumption> packagingMaterials,

    /**
     * If true, do not consume packaging materials during this bulk-to-size operation.
     *
     * Intended for workflows where packaging was already consumed earlier (e.g. via
     * `/api/v1/factory/packing-records`) and this call is only converting bulk FG into
     * child sized SKUs for sales/dispatch.
     */
    Boolean skipPackagingConsumption,

    LocalDate packDate,
    String packedBy,
    String notes
) {
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

    /**
     * Optional packaging material to consume (buckets, cans, cartons).
     */
    public record MaterialConsumption(
        @NotNull(message = "Material ID is required")
        Long materialId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be positive")
        BigDecimal quantity,

        String unit
    ) {}
}
