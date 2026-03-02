package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.validation.ValidationUtils;

import java.time.LocalDate;

public final class ReportQueryRequestBuilder {

    private ReportQueryRequestBuilder() {
    }

    public static FinancialReportQueryRequest fromPeriodAndRange(Long periodId,
                                                                 LocalDate startDate,
                                                                 LocalDate endDate,
                                                                 LocalDate comparativeStartDate,
                                                                 LocalDate comparativeEndDate,
                                                                 Long comparativePeriodId,
                                                                 String exportFormat) {
        if ((startDate == null) != (endDate == null)) {
            throw ValidationUtils.invalidInput("Both startDate and endDate must be provided together");
        }
        if (startDate != null) {
            ValidationUtils.validateDateRange(startDate, endDate, "startDate", "endDate");
        }

        if ((comparativeStartDate == null) != (comparativeEndDate == null)) {
            throw ValidationUtils.invalidInput("Both comparativeStartDate and comparativeEndDate must be provided together");
        }
        if (comparativeStartDate != null) {
            ValidationUtils.validateDateRange(comparativeStartDate, comparativeEndDate,
                    "comparativeStartDate", "comparativeEndDate");
        }

        return new FinancialReportQueryRequest(
                periodId,
                startDate,
                endDate,
                null,
                null,
                comparativeStartDate,
                comparativeEndDate,
                comparativePeriodId,
                exportFormat
        );
    }

    public static FinancialReportQueryRequest fromAsOfDate(LocalDate asOfDate) {
        return new FinancialReportQueryRequest(
                null,
                null,
                null,
                asOfDate,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static FinancialReportQueryRequest empty() {
        return new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
