package com.bigbrightpaints.erp.modules.sales.dto;

import java.math.BigDecimal;
import java.util.List;

public record DispatchConfirmResponse(
        Long packingSlipId,
        Long salesOrderId,
        Long finalInvoiceId,
        Long arJournalEntryId,
        List<CogsPostingDto> cogsPostings,
        boolean dispatched,
        List<AccountPostingDto> arPostings,
        GstBreakdownDto gstBreakdown
) {
    public record CogsPostingDto(Long inventoryAccountId, Long cogsAccountId, BigDecimal cost) {}
    public record AccountPostingDto(Long accountId, String accountName, BigDecimal debit, BigDecimal credit) {}
    public record GstBreakdownDto(BigDecimal taxableAmount, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal totalTax) {}
}
