package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

final class ReportFixtures {

    private ReportFixtures() {
    }

    static ReportQuerySupport.FinancialQueryWindow window(LocalDate startDate,
                                                          LocalDate endDate,
                                                          LocalDate asOfDate) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", 7000L);
        company.setCode("RPT");
        company.setTimezone("UTC");
        return new ReportQuerySupport.FinancialQueryWindow(
                company,
                startDate,
                endDate,
                asOfDate,
                null,
                null,
                ReportSource.LIVE,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );
    }
}
