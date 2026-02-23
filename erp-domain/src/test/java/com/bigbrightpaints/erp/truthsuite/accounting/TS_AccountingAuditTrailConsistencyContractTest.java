package com.bigbrightpaints.erp.truthsuite.accounting;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_AccountingAuditTrailConsistencyContractTest {

    private static final String AUDIT_TRAIL_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailService.java";

    @Test
    void listTransactionsClampsPageBoundsAndUsesDeterministicSort() {
        TruthSuiteFileAssert.assertContains(
                AUDIT_TRAIL_SERVICE,
                "int safePage = Math.max(page, 0);",
                "int safeSize = Math.max(1, Math.min(size, 200));",
                "PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, \"entryDate\", \"id\"))");
    }

    @Test
    void transactionDetailDeduplicatesLinkedDocumentsByTypeAndId() {
        TruthSuiteFileAssert.assertContains(
                AUDIT_TRAIL_SERVICE,
                "item -> item.documentType() + \":\" + item.documentId(),",
                "(left, right) -> left))",
                "dedupedLinkedDocuments");
    }

    @Test
    void consistencyAssessmentRemainsFailClosedForDriftSignals() {
        TruthSuiteFileAssert.assertContains(
                AUDIT_TRAIL_SERVICE,
                "if (totalDebit.compareTo(totalCredit) != 0) {",
                "status = \"ERROR\";",
                "if (\"POSTED\".equalsIgnoreCase(entry.getStatus()) && entry.getPostedAt() == null) {",
                "if (likelySettlement && (allocations == null || allocations.isEmpty())) {",
                "Settlement-like reference has no settlement allocation rows.");
    }

    @Test
    void moduleDerivationIncludesSettlementFallbackAndAccountingDefault() {
        TruthSuiteFileAssert.assertContainsInOrder(
                AUDIT_TRAIL_SERVICE,
                "if (reference.startsWith(\"SET\") || reference.startsWith(\"RCPT\") || reference.contains(\"SETTLEMENT\")) {",
                "return \"SETTLEMENT\";",
                "return \"ACCOUNTING\";");
    }
}
