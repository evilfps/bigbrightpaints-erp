package com.bigbrightpaints.erp.truthsuite.reports;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_ReportSnapshotSourceSelectionTest {

    private static final String REPORT_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java";

    @Test
    void closedPeriodReportsRequireSnapshotAsSourceOfTruth() {
        TruthSuiteFileAssert.assertContains(
                REPORT_SERVICE,
                "if (period != null && period.getStatus() == AccountingPeriodStatus.CLOSED) {",
                "snapshotRepository.findByCompanyAndPeriod(company, period)",
                "\"Closed period snapshot is required for reports\"",
                "source = ReportSource.SNAPSHOT;");
    }

    @Test
    void trialBalancePathUsesSnapshotLinesForClosedPeriods() {
        TruthSuiteFileAssert.assertContains(
                REPORT_SERVICE,
                "if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {",
                "snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(context.snapshot())",
                "return snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(context.snapshot()).stream()");
    }

    @Test
    void reportMetadataExposesSnapshotIdentityForAuditability() {
        TruthSuiteFileAssert.assertContains(
                REPORT_SERVICE,
                "Long snapshotId = snapshot != null ? snapshot.getId() : null;",
                "return new ReportMetadata(asOfDate, source, periodId, status, snapshotId);");
    }
}
