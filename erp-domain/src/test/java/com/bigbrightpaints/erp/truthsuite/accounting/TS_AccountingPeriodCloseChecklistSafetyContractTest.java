package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_AccountingPeriodCloseChecklistSafetyContractTest {

    private static final String PERIOD_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java";

    @Test
    void periodCloseAlwaysChecksReceiptsBeforeForceBypass() {
        TruthSuiteFileAssert.assertContainsInOrder(
                PERIOD_SERVICE,
                "boolean force = request != null && Boolean.TRUE.equals(request.force());",
                "assertNoUninvoicedReceipts(company, period);",
                "if (!force) {",
                "assertChecklistComplete(company, period);");
    }

    @Test
    void unresolvedChecklistControlsRemainDeterministicAndGuided() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "private static final List<String> RECONCILIATION_CONTROL_ORDER = List.of(",
                "\"inventoryReconciled\",",
                "\"arReconciled\",",
                "\"apReconciled\");",
                "private static final Map<String, String> UNRESOLVED_CONTROL_GUIDANCE = Map.of(",
                "return List.copyOf(unresolved);",
                "UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls)");
    }

    @Test
    void checklistCloseRejectsStructuralDriftSignals() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "if (diagnostics.unbalancedJournals() > 0) {",
                "if (diagnostics.unlinkedDocuments() > 0) {",
                "if (diagnostics.unpostedDocuments() > 0) {",
                "Un-invoiced goods receipts exist in this period (");
    }
}
