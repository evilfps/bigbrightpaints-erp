package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_P2PPurchaseAuditTrailRepositoryCompatibilityTest {

    private static final String RAW_MATERIAL_PURCHASE_REPOSITORY =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/RawMaterialPurchaseRepository.java";

    @Test
    void purchasingRepositorySupportsAuditTrailJournalLookups() {
        TruthSuiteFileAssert.assertContains(
                RAW_MATERIAL_PURCHASE_REPOSITORY,
                "Optional<RawMaterialPurchase> findByCompanyAndJournalEntry(Company company, JournalEntry journalEntry);",
                "List<RawMaterialPurchase> findByCompanyAndJournalEntry_IdIn(Company company, List<Long> journalEntryIds);");
    }

    @Test
    void auditTrailJournalLookupsKeepDocumentLinkEntityGraph() {
        TruthSuiteFileAssert.assertContainsInOrder(
                RAW_MATERIAL_PURCHASE_REPOSITORY,
                "@EntityGraph(attributePaths = {\"supplier\", \"journalEntry\", \"purchaseOrder\", \"goodsReceipt\"})",
                "Optional<RawMaterialPurchase> findByCompanyAndJournalEntry(Company company, JournalEntry journalEntry);",
                "@EntityGraph(attributePaths = {\"supplier\", \"journalEntry\", \"purchaseOrder\", \"goodsReceipt\"})",
                "List<RawMaterialPurchase> findByCompanyAndJournalEntry_IdIn(Company company, List<Long> journalEntryIds);");
    }
}
