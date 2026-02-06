package com.bigbrightpaints.erp.truthsuite.inventory;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_InventoryCogsLinkageScanContractTest {

    private static final String FG_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java";
    private static final String SCAN_SQL = "../scripts/db_predeploy_scans.sql";

    @Test
    void dispatchMovementsCarryPackingSlipAndJournalReferences() {
        TruthSuiteFileAssert.assertContains(
                FG_SERVICE,
                "movement.setPackingSlipId(packingSlipId);",
                "movement.setJournalEntryId(journalEntryId);",
                "findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(");
    }

    @Test
    void predeployScansBlockMissingDispatchMovementAndCogsLinkage() {
        TruthSuiteFileAssert.assertContains(
                SCAN_SQL,
                "-- 18) Dispatched slips without dispatch inventory movements (NO-GO)",
                "-- 19) Dispatch inventory movements not linked to slip COGS journal (NO-GO)");
    }
}
