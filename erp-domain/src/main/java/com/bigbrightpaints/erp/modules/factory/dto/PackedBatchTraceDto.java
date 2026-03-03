package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PackedBatchTraceDto(
        Long packingRecordId,
        Long finishedGoodBatchId,
        UUID finishedGoodBatchPublicId,
        String finishedGoodBatchCode,
        String finishedGoodCode,
        String finishedGoodName,
        String sizeLabel,
        BigDecimal packedQuantity,
        BigDecimal unitCost,
        BigDecimal totalValue,
        String accountingReference,
        Long journalEntryId
) {
}
