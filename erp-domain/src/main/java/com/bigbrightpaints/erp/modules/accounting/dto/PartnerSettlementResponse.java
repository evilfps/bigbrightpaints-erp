package com.bigbrightpaints.erp.modules.accounting.dto;

import java.math.BigDecimal;
import java.util.List;

public record PartnerSettlementResponse(
        JournalEntryDto journalEntry,
        BigDecimal totalApplied,
        BigDecimal cashAmount,
        BigDecimal totalDiscount,
        BigDecimal totalWriteOff,
        BigDecimal totalFxGain,
        BigDecimal totalFxLoss,
        List<Allocation> allocations
) {

    public record Allocation(
            Long invoiceId,
            Long purchaseId,
            BigDecimal appliedAmount,
            BigDecimal discountAmount,
            BigDecimal writeOffAmount,
            BigDecimal fxAdjustment,
            SettlementAllocationApplication applicationType,
            String memo
    ) {}
}
