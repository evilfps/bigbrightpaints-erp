package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RawMaterialAdjustmentDto(
        Long id,
        UUID publicId,
        String referenceNumber,
        LocalDate adjustmentDate,
        String status,
        String reason,
        BigDecimal totalAmount,
        Long journalEntryId,
        List<RawMaterialAdjustmentLineDto> lines
) {
}
