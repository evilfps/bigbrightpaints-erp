package com.bigbrightpaints.erp.modules.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DispatchConfirmationResponse(
        Long packagingSlipId,
        String slipNumber,
        String status,
        Instant confirmedAt,
        String confirmedBy,
        BigDecimal totalOrderedAmount,
        BigDecimal totalShippedAmount,
        BigDecimal totalBackorderAmount,
        Long journalEntryId,
        Long cogsJournalEntryId,
        List<LineResult> lines,
        Long backorderSlipId,
        String transporterName,
        String driverName,
        String vehicleNumber,
        String challanReference,
        String deliveryChallanNumber,
        String deliveryChallanPdfPath
) {

    public DispatchConfirmationResponse(Long packagingSlipId,
                                        String slipNumber,
                                        String status,
                                        Instant confirmedAt,
                                        String confirmedBy,
                                        BigDecimal totalOrderedAmount,
                                        BigDecimal totalShippedAmount,
                                        BigDecimal totalBackorderAmount,
                                        Long journalEntryId,
                                        Long cogsJournalEntryId,
                                        List<LineResult> lines,
                                        Long backorderSlipId) {
        this(packagingSlipId, slipNumber, status, confirmedAt, confirmedBy, totalOrderedAmount, totalShippedAmount,
                totalBackorderAmount, journalEntryId, cogsJournalEntryId, lines, backorderSlipId,
                null, null, null, null, null, null);
    }

    public record LineResult(
            Long lineId,
            String productCode,
            String productName,
            BigDecimal orderedQuantity,
            BigDecimal shippedQuantity,
            BigDecimal backorderQuantity,
            BigDecimal unitCost,
            BigDecimal lineTotal,
            String notes
    ) {}
}
