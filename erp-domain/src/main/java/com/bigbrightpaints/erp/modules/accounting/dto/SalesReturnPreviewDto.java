package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

public record SalesReturnPreviewDto(
        Long invoiceId,
        String invoiceNumber,
        BigDecimal totalReturnAmount,
        BigDecimal totalInventoryValue,
        List<LinePreview> lines
) {
    public record LinePreview(
            Long invoiceLineId,
            String productCode,
            BigDecimal requestedQuantity,
            BigDecimal alreadyReturnedQuantity,
            BigDecimal remainingQuantityAfterReturn,
            BigDecimal lineAmount,
            BigDecimal taxAmount,
            BigDecimal inventoryUnitCost,
            BigDecimal inventoryValue
    ) {
    }
}
