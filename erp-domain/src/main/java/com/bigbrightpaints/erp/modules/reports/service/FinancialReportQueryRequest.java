package com.bigbrightpaints.erp.modules.reports.service;

import java.time.LocalDate;

public record FinancialReportQueryRequest(
        Long periodId,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate asOfDate,
        Long companyId,
        LocalDate comparativeStartDate,
        LocalDate comparativeEndDate,
        Long comparativePeriodId,
        String exportFormat
) {
}
