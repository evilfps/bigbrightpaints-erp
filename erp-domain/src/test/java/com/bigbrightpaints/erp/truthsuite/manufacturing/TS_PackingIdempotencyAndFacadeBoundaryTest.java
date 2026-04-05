package com.bigbrightpaints.erp.truthsuite.manufacturing;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_PackingIdempotencyAndFacadeBoundaryTest {

  private static final String PACKING_CONTROLLER =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/controller/PackingController.java";
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
  void packingControllerUsesCanonicalHeaderOnlyAndRemovesRetiredMutations() {
    TruthSuiteFileAssert.assertContains(
        PACKING_CONTROLLER,
        "@PostMapping(\"/packing-records\")",
        "unsupportedLegacyHeader(\"X-Request-Id\")",
        "\" is not supported for packing records; use Idempotency-Key\"",
        "\"Idempotency-Key header is required\"");

    String controllerSource = TruthSuiteFileAssert.read(PACKING_CONTROLLER);
    assertFalse(controllerSource.contains("@PostMapping(\"/pack\")"));
    assertFalse(controllerSource.contains("/packing-records/{productionLogId}/complete"));
  }

  @Test
  void packingPostsThroughAccountingFacadeAndLinksInventoryMovement() {
    TruthSuiteFileAssert.assertContains(
        PACKING_SERVICE,
        "JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo,"
            + " lines);",
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
