package com.bigbrightpaints.erp.truthsuite.manufacturing;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_BulkPackDeterministicReferenceTest {

    private static final String BULK_PACK_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java";
    private static final String PACKING_MIGRATION =
            "src/main/resources/db/migration/V132__packing_idempotency_and_slip_uniqueness.sql";

    @Test
    void bulkPackReferenceDerivesFromDeterministicPayloadAndIdempotencyKey() {
        TruthSuiteFileAssert.assertContains(
                BULK_PACK_SERVICE,
                "String idempotencyKey = StringUtils.hasText(request.idempotencyKey())",
                "String hash = sha256Hex(fingerprint + \"|\" + idempotencyKey, 12);",
                "return trimReference(\"PACK-\", batchCode, hash, 64);");
    }

    @Test
    void bulkPackRetryReadsPriorEffectsInsteadOfDoublePosting() {
        TruthSuiteFileAssert.assertContains(
                BULK_PACK_SERVICE,
                "BulkPackResponse idempotent = resolveIdempotentPack(company, bulkBatch, packReference);",
                "findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(",
                "findByRawMaterialCompanyAndReferenceTypeAndReferenceId(",
                "JournalEntryDto journal = accountingFacade.postPackingJournal(reference, entryDate, memo, lines);");
    }

    @Test
    void schemaGuardsPreventDuplicateActivePackagingSlips() {
        TruthSuiteFileAssert.assertContains(
                PACKING_MIGRATION,
                "CREATE UNIQUE INDEX uq_packaging_slips_order_primary_active",
                "CREATE UNIQUE INDEX uq_packaging_slips_order_backorder_active");
    }
}
