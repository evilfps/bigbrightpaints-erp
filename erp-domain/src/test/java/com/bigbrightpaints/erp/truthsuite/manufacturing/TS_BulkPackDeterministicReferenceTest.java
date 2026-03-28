package com.bigbrightpaints.erp.truthsuite.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("concurrency")
@Tag("reconciliation")
class TS_BulkPackDeterministicReferenceTest {

  private static final String BULK_PACK_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java";
  private static final String BULK_PACK_READ_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingReadService.java";
  private static final String PACKING_MIGRATION =
      "src/main/resources/db/migration/V132__packing_idempotency_and_slip_uniqueness.sql";

  @Test
  void retiredBulkPackMutationSurfaceIsAbsentFromRuntimeService() {
    String source = TruthSuiteFileAssert.read(BULK_PACK_SERVICE);
    assertThat(source).doesNotContain("BulkPackResponse pack(");
    assertThat(source).contains("listBulkBatches(");
    assertThat(source).contains("listChildBatches(");
  }

  @Test
  void bulkPackReadServiceStillUsesHistoricalReferenceReplayLookups() {
    TruthSuiteFileAssert.assertContains(
        BULK_PACK_READ_SERVICE,
        "findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(",
        "findByRawMaterialCompanyAndReferenceTypeAndReferenceId(",
        "Partial bulk pack detected for reference");
  }

  @Test
  void schemaGuardsPreventDuplicateActivePackagingSlips() {
    TruthSuiteFileAssert.assertContains(
        PACKING_MIGRATION,
        "CREATE UNIQUE INDEX uq_packaging_slips_order_primary_active",
        "CREATE UNIQUE INDEX uq_packaging_slips_order_backorder_active");
  }
}
