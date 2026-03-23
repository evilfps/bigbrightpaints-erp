package com.bigbrightpaints.erp.modules.inventory.dto;

import java.time.Instant;

public record OpeningStockImportHistoryItem(
        Long id,
        String idempotencyKey,
        String openingStockBatchKey,
        String referenceNumber,
        String fileName,
        Long journalEntryId,
        int rowsProcessed,
        int rawMaterialBatchesCreated,
        int finishedGoodBatchesCreated,
        int errorCount,
        Instant createdAt
) {}
