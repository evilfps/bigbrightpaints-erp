package com.bigbrightpaints.erp.truthsuite.inventory;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
class TS_OpeningStockBatchKeyV2MigrationContractTest {

    private static final String V2_MIGRATION =
            "src/main/resources/db/migration_v2/V166__opening_stock_batch_key_contract_alignment.sql";

    @Test
    void v2MigrationHardCutsLegacyReplayBackfillToBatchKeyContract() {
        TruthSuiteFileAssert.assertContains(
                V2_MIGRATION,
                "SET opening_stock_batch_key = idempotency_key",
                "OR replay_protection_key IS NOT NULL",
                "CASE WHEN replay_protection_key IS NULL THEN 0 ELSE 1 END");
        TruthSuiteFileAssert.assertContainsInOrder(
                V2_MIGRATION,
                "DROP INDEX IF EXISTS uq_opening_stock_imports_company_batch_key;",
                "SET opening_stock_batch_key = idempotency_key",
                "CASE WHEN replay_protection_key IS NULL THEN 0 ELSE 1 END",
                "ALTER COLUMN opening_stock_batch_key SET NOT NULL;",
                "ALTER TABLE public.opening_stock_imports DROP COLUMN IF EXISTS replay_protection_key;",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_opening_stock_import_company_batch_key");
    }
}
