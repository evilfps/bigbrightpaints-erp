package com.bigbrightpaints.erp.modules.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PackingRecordDto(
        Long id,
        String packagingSize,
        BigDecimal quantityPacked,
        Integer piecesCount,
        Integer boxesCount,
        Integer piecesPerBox,
        LocalDate packedDate,
        String packedBy
) {}
