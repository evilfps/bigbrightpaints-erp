package com.bigbrightpaints.erp.modules.reports.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GstReturnReportDto(
        Long periodId,
        String periodLabel,
        LocalDate periodStart,
        LocalDate periodEnd,
        GstComponentSummary outputTax,
        GstComponentSummary inputTaxCredit,
        GstComponentSummary netLiability,
        List<GstRateSummary> rateSummaries,
        List<GstTransactionDetail> transactionDetails,
        ReportMetadata metadata
) {

    public record GstComponentSummary(
            BigDecimal cgst,
            BigDecimal sgst,
            BigDecimal igst,
            BigDecimal total
    ) {
    }

    public record GstRateSummary(
            BigDecimal taxRate,
            BigDecimal taxableAmount,
            BigDecimal outputTax,
            BigDecimal inputTaxCredit,
            BigDecimal netTax,
            BigDecimal outputCgst,
            BigDecimal outputSgst,
            BigDecimal outputIgst,
            BigDecimal inputCgst,
            BigDecimal inputSgst,
            BigDecimal inputIgst
    ) {
    }

    public record GstTransactionDetail(
            String sourceType,
            Long sourceId,
            String referenceNumber,
            LocalDate transactionDate,
            String partyName,
            BigDecimal taxRate,
            BigDecimal taxableAmount,
            BigDecimal cgst,
            BigDecimal sgst,
            BigDecimal igst,
            BigDecimal totalTax,
            String direction
    ) {
    }
}
