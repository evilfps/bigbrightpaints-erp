package com.bigbrightpaints.erp.truthsuite.periodclose;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_PeriodCloseBoundaryCoverageTest {

    private static final String PERIOD_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java";

    @Test
    void closePeriodChecksUninvoicedReceiptsBeforeChecklistForceBypass() {
        TruthSuiteFileAssert.assertContainsInOrder(
                PERIOD_SERVICE,
                "boolean force = request != null && Boolean.TRUE.equals(request.force());",
                "assertNoUninvoicedReceipts(company, period);",
                "if (!force) {",
                "assertChecklistComplete(company, period);");
    }

    @Test
    void checklistValidationRemainsFailClosedForUnresolvedAndStructuralDriftSignals() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "UNRESOLVED_CONTROLS_PREFIX + formatUnresolvedControls(unresolvedControls)",
                "if (diagnostics.unbalancedJournals() > 0) {",
                "if (diagnostics.unlinkedDocuments() > 0) {",
                "if (diagnostics.unpostedDocuments() > 0) {",
                "if (uninvoicedReceipts > 0) {");
    }

    @Test
    void checklistMutationGuardRejectsClosedPeriodWrites() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "private void assertChecklistMutable(AccountingPeriod period) {",
                "if (period.getStatus() == AccountingPeriodStatus.CLOSED) {",
                "Checklist cannot be updated for a closed period");
    }

    @Test
    void requireOpenPeriodRejectsLockedOrClosedStates() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {",
                "if (period.getStatus() != AccountingPeriodStatus.OPEN) {",
                "\"Accounting period \" + period.getLabel() + \" is locked/closed\"");
    }

    @Test
    void reopenBoundaryReversesClosingEntryAndDropsSnapshot() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                ".ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, reason));",
                "snapshotService.deleteSnapshotForPeriod(company, period);",
                "accountingFacade.reverseClosingEntryForPeriodReopen(closing, period, reason);");
    }
}
