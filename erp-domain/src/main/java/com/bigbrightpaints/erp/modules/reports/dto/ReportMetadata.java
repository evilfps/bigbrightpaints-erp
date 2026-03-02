package com.bigbrightpaints.erp.modules.reports.dto;

import java.time.LocalDate;

public record ReportMetadata(
        LocalDate asOfDate,
        LocalDate startDate,
        LocalDate endDate,
        ReportSource source,
        Long accountingPeriodId,
        String accountingPeriodStatus,
        Long snapshotId,
        boolean pdfReady,
        boolean csvReady,
        String requestedExportFormat
) {
    public ReportMetadata(LocalDate asOfDate,
                          ReportSource source,
                          Long accountingPeriodId,
                          String accountingPeriodStatus,
                          Long snapshotId) {
        this(asOfDate,
                asOfDate != null ? asOfDate.withDayOfMonth(1) : null,
                asOfDate,
                source,
                accountingPeriodId,
                accountingPeriodStatus,
                snapshotId,
                true,
                true,
                null);
    }
}
