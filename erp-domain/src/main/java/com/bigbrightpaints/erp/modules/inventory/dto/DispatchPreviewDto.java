package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTO for the dispatch confirmation modal - shows what was ordered vs what's available to ship.
 */
public record DispatchPreviewDto(
        Long packagingSlipId,
        String slipNumber,
        String status,
        Long salesOrderId,
        String salesOrderNumber,
        String dealerName,
        String dealerCode,
        Instant createdAt,
        BigDecimal totalOrderedAmount,
        BigDecimal totalAvailableAmount,
        GstBreakdown gstBreakdown,
        List<LinePreview> lines
) {
    public record LinePreview(
            Long lineId,
            Long finishedGoodId,
            String productCode,
            String productName,
            String batchCode,
            BigDecimal orderedQuantity,
            BigDecimal availableQuantity,
            BigDecimal suggestedShipQuantity,
            BigDecimal unitPrice,
            BigDecimal lineSubtotal,
            BigDecimal lineTax,
            BigDecimal lineTotal,
            boolean hasShortage
    ) {}

    public record GstBreakdown(
            BigDecimal taxableAmount,
            BigDecimal cgst,
            BigDecimal sgst,
            BigDecimal igst,
            BigDecimal totalTax,
            BigDecimal grandTotal
    ) {}
}
