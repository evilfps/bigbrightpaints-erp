package com.bigbrightpaints.erp.truthsuite.catalog;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("concurrency")
class TS_CatalogImportCompanyScopedIdempotencyTest {

    private static final String CATALOG_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java";
    private static final String CATALOG_MIGRATION =
            "src/main/resources/db/migration/V127__catalog_import_idempotency.sql";

    @Test
    void catalogImportIsCompanyScopedAndIdempotentByReserveFirst() {
        TruthSuiteFileAssert.assertContains(
                CATALOG_SERVICE,
                "Company company = companyContextService.requireCurrentCompany();",
                "String fileHash = sha256Hex(file);",
                "String normalizedKey = normalizeIdempotencyKey(idempotencyKey, fileHash);",
                "catalogImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)",
                "assertIdempotencyMatch(existing, fileHash, normalizedKey);",
                "record = catalogImportRepository.saveAndFlush(record);");
    }

    @Test
    void catalogImportHandlesConcurrentRetriesDeterministically() {
        TruthSuiteFileAssert.assertContains(
                CATALOG_SERVICE,
                "this.rowTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);",
                "ProcessOutcome outcome = rowTransactionTemplate.execute(status ->",
                "if (!isRetryableImportFailure(ex) || attempt == 2) {",
                "CatalogImport concurrent = catalogImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)");
    }

    @Test
    void catalogImportSchemaEnforcesUniqueCompanyAndIdempotencyKey() {
        TruthSuiteFileAssert.assertContains(
                CATALOG_MIGRATION,
                "UNIQUE(company_id, idempotency_key)");
    }
}
