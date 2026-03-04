package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;

public record RawMaterialAdjustmentLineDto(
        Long rawMaterialId,
        String rawMaterialName,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal amount,
        String note
) {
}
