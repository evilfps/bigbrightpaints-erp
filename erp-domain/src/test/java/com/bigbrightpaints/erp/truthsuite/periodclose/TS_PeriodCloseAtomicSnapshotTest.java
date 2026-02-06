package com.bigbrightpaints.erp.truthsuite.periodclose;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_PeriodCloseAtomicSnapshotTest {

    private static final String PERIOD_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java";

    @Test
    void closePeriodLocksThenCapturesSnapshotBeforeClosedState() {
        TruthSuiteFileAssert.assertContainsInOrder(
                PERIOD_SERVICE,
                "AccountingPeriod period = accountingPeriodRepository.lockByCompanyAndId(company, periodId)",
                "periodCloseHook.onPeriodCloseLocked(company, period);",
                "snapshotService.captureSnapshot(company, period, user);",
                "period.setStatus(AccountingPeriodStatus.CLOSED);");
    }

    @Test
    void closedPeriodRejectsOperationalPostingViaOpenPeriodGuard() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                "public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate)",
                "if (period.getStatus() != AccountingPeriodStatus.OPEN) {",
                "\"Accounting period \" + period.getLabel() + \" is locked/closed\"");
    }

    @Test
    void reopenUsesCanonicalReverseBoundaryAndDropsSnapshot() {
        TruthSuiteFileAssert.assertContains(
                PERIOD_SERVICE,
                ".ifPresent(closing -> reverseClosingJournalIfNeeded(closing, period, request.reason()));",
                "snapshotService.deleteSnapshotForPeriod(company, period);",
                "accountingFacade.reverseClosingEntryForPeriodReopen(closing, period, reason);");
    }
}
