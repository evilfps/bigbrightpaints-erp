package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PackingRecordDto(
        Long id,
        Long productionLogId,
        String productionCode,
        Long sizeVariantId,
        String sizeVariantLabel,
        Long finishedGoodBatchId,
        String finishedGoodBatchCode,
        Integer childBatchCount,
        String packagingSize,
        BigDecimal quantityPacked,
        Integer piecesCount,
        Integer boxesCount,
        Integer piecesPerBox,
        LocalDate packedDate,
        String packedBy
) {}
