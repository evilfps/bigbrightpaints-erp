package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProductionLogPackingRecordDto(
        Long packingRecordId,
        Long sizeVariantId,
        String sizeVariantLabel,
        Long childBatchCount,
        Long finishedGoodId,
        String finishedGoodCode,
        String finishedGoodName,
        Long finishedGoodBatchId,
        UUID finishedGoodBatchPublicId,
        String finishedGoodBatchCode,
        String packagingSize,
        BigDecimal quantityPacked,
        LocalDate packedDate,
        String packedBy
) {
}
