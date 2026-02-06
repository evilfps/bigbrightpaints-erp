package com.bigbrightpaints.erp.truthsuite.manufacturing;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_PackingIdempotencyAndFacadeBoundaryTest {

    private static final String PACKING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java";
    private static final String FG_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java";

    @Test
    void packingRequestUsesReserveFirstIdempotencyWithMismatchFailClosed() {
        TruthSuiteFileAssert.assertContains(
                PACKING_SERVICE,
                "IdempotencyReservation reservation = reserveIdempotencyRecord(",
                "findByCompanyAndIdempotencyKey(company, idempotencyKey);",
                "catch (DataIntegrityViolationException ex) {",
                "\"Idempotency payload mismatch for packing request\"",
                "\"Idempotency key already used for a different production log\"");
    }

    @Test
    void packingPostsThroughAccountingFacadeAndLinksInventoryMovement() {
        TruthSuiteFileAssert.assertContains(
                PACKING_SERVICE,
                "JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo, lines);",
                "movement.setJournalEntryId(entry.id());",
                "inventoryMovementRepository.save(movement);");
    }

    @Test
    void dispatchMovementsAreLinkedByPackingSlipAndJournalEntry() {
        TruthSuiteFileAssert.assertContains(
                FG_SERVICE,
                "movement.setPackingSlipId(packingSlipId);",
                "findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(",
                "movement.setJournalEntryId(journalEntryId);");
    }
}
