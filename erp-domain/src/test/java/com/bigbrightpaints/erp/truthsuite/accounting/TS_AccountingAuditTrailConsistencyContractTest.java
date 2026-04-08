package com.bigbrightpaints.erp.truthsuite.accounting;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("reconciliation")
class TS_AccountingAuditTrailConsistencyContractTest {

  private static final String AUDIT_TRAIL_CLASSIFIER =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailClassifier.java";
  private static final String AUDIT_TRAIL_DETAIL_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailTransactionDetailService.java";
  private static final String AUDIT_TRAIL_QUERY_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingAuditTrailTransactionQueryService.java";
  private static final String DEALER_RECEIPT_POSTING_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerReceiptPostingService.java";
  private static final String DEALER_SETTLEMENT_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerSettlementService.java";
  private static final String SUPPLIER_PAYMENT_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/SupplierPaymentService.java";
  private static final String SUPPLIER_SETTLEMENT_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/SupplierSettlementService.java";

  @Test
  void listTransactionsClampsPageBoundsAndUsesDeterministicSort() {
    TruthSuiteFileAssert.assertContains(
        AUDIT_TRAIL_QUERY_SERVICE,
        "int safePage = Math.max(page, 0);",
        "int safeSize = Math.max(1, Math.min(size, 200));",
        "PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, \"entryDate\", \"id\"))");
  }

  @Test
  void transactionDetailDeduplicatesLinkedDocumentsByTypeAndId() {
    TruthSuiteFileAssert.assertContains(
        AUDIT_TRAIL_DETAIL_SERVICE,
        "item -> item.documentType() + \":\" + item.documentId(),",
        "(left, right) -> left))",
        "dedupedLinkedDocuments");
  }

  @Test
  void consistencyAssessmentRemainsFailClosedForDriftSignals() {
    TruthSuiteFileAssert.assertContains(
        AUDIT_TRAIL_CLASSIFIER,
        "if (totalDebit.compareTo(totalCredit) != 0) {",
        "status = \"ERROR\";",
        "if (entry.getStatus() == JournalEntryStatus.POSTED && entry.getPostedAt() == null) {",
        "if (likelySettlement && (allocations == null || allocations.isEmpty())) {",
        "Settlement-like reference has no settlement allocation rows.");
  }

  @Test
  void moduleDerivationIncludesSettlementFallbackAndAccountingDefault() {
    TruthSuiteFileAssert.assertContainsInOrder(
        AUDIT_TRAIL_CLASSIFIER,
        "if (reference.startsWith(\"SET\") || reference.startsWith(\"RCPT\") ||"
            + " reference.contains(\"SETTLEMENT\")) {",
        "return \"SETTLEMENT\";",
        "return \"ACCOUNTING\";");
  }

  @Test
  void receiptAndSupplierSettlementPathsEmitAccountingEventStorePartnerEvents() {
    TruthSuiteFileAssert.assertContains(
        DEALER_RECEIPT_POSTING_SERVICE, "recordDealerReceiptPostedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        DEALER_RECEIPT_POSTING_SERVICE, "recordSettlementAllocatedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        DEALER_SETTLEMENT_SERVICE, "recordDealerReceiptPostedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        DEALER_SETTLEMENT_SERVICE, "recordSettlementAllocatedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        SUPPLIER_PAYMENT_SERVICE, "recordSupplierPaymentPostedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        SUPPLIER_PAYMENT_SERVICE, "recordSettlementAllocatedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        SUPPLIER_SETTLEMENT_SERVICE, "recordSupplierPaymentPostedEventSafe(");
    TruthSuiteFileAssert.assertContains(
        SUPPLIER_SETTLEMENT_SERVICE, "recordSettlementAllocatedEventSafe(");
  }
}
