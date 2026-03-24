package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_SubledgerControlReconciliationContractTest {

    private static final String ACCOUNTING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java";
    private static final String REPORT_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java";
    private static final String SCAN_SQL = "../scripts/db_predeploy_scans.sql";

    @Test
    void accountingServiceRequiresPartnerContextOnArApControlAccounts() {
        TruthSuiteFileAssert.assertContains(
                ACCOUNTING_SERVICE,
                "\"Posting to AR requires a dealer context\"",
                "\"Posting to AP requires a supplier context\"",
                "\"Dealer receivable account \"",
                "\"Supplier payable account \"");
    }

    @Test
    void reportServiceExposesDeterministicReconciliationViews() {
        TruthSuiteFileAssert.assertContains(
                REPORT_SERVICE,
                "public ReconciliationSummaryDto inventoryReconciliation()",
                "public InventoryValuationDto inventoryValuationAsOf(LocalDate asOfDate)",
                "public ReconciliationDashboardDto reconciliationDashboard(Long bankAccountId, BigDecimal statementBalance)",
                "public List<AgedDebtorDto> agedDebtors(FinancialReportQueryRequest request)",
                "boolean inventoryBalanced = inventoryVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;");
    }

    @Test
    void predeployScansBlockArAndApControlDrift() {
        TruthSuiteFileAssert.assertContains(
                SCAN_SQL,
                "-- 16) AR subledger vs AR control-account mismatch (NO-GO)",
                "-- 17) AP subledger vs AP control-account mismatch (NO-GO)");
    }
}
