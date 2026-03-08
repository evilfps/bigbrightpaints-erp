package com.bigbrightpaints.erp.modules.purchasing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PurchaseReturnPreviewDto(
        Long purchaseId,
        String purchaseInvoiceNumber,
        Long rawMaterialId,
        String rawMaterialName,
        BigDecimal requestedQuantity,
        BigDecimal remainingReturnableQuantity,
        BigDecimal lineAmount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        LocalDate returnDate,
        String referenceNumber
) {
}
