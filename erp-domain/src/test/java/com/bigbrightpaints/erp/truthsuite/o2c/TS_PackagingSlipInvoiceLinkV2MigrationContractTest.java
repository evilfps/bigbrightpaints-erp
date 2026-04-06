package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
class TS_PackagingSlipInvoiceLinkV2MigrationContractTest {

  private static final String LEGACY_MIGRATION =
      "src/main/resources/db/migration/V167__backfill_packaging_slip_invoice_links.sql";
  private static final String V2_MIGRATION =
      "src/main/resources/db/migration_v2/V177__backfill_packaging_slip_invoice_links.sql";

  @Test
  void packagingSlipInvoiceBackfillLivesOnlyOnFlywayV2Track() {
    assertFalse(Files.exists(TruthSuiteFileAssert.resolve(LEGACY_MIGRATION)));
    assertTrue(Files.exists(TruthSuiteFileAssert.resolve(V2_MIGRATION)));
    TruthSuiteFileAssert.assertContains(
        V2_MIGRATION,
        "UPDATE packaging_slips p",
        "SET invoice_id = COALESCE(fulfillment_invoice.id, current_invoices.invoice_id)",
        "AND p.invoice_id IS NULL",
        "RAISE NOTICE 'Explicit packaging slip invoice links available for % rows'");
  }
}
