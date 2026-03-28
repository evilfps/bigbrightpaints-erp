package com.bigbrightpaints.erp.truthsuite.p2p;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("abuse")
@Tag("reconciliation")
class TS_P2PPurchaseSettlementBoundaryTest {

  private static final String PURCHASING_CONTROLLER =
      "src/main/java/com/bigbrightpaints/erp/modules/purchasing/controller/RawMaterialPurchaseController.java";
  private static final String ACCOUNTING_CONTROLLER =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java";
  private static final String ACCOUNTING_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java";
  private static final String PURCHASING_SERVICE =
      "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";
  private static final String SUPPLIER_SETTLEMENT_REQUEST =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/dto/SupplierSettlementRequest.java";

  @Test
  void purchasingAndSupplierSettlementEndpointsRemainIsolated() {
    TruthSuiteFileAssert.assertContains(
        PURCHASING_CONTROLLER,
        "@RequestMapping(\"/api/v1/purchasing/raw-material-purchases\")",
        "public ResponseEntity<ApiResponse<RawMaterialPurchaseResponse>> createPurchase(");

    TruthSuiteFileAssert.assertContains(
        ACCOUNTING_CONTROLLER,
        "@RequestMapping(\"/api/v1/accounting\")",
        "@PostMapping(\"/settlements/suppliers\")");
    String accountingController = TruthSuiteFileAssert.read(ACCOUNTING_CONTROLLER);
    assertFalse(
        accountingController.contains("@PostMapping(\"/suppliers/payments\")"),
        "Accounting controller must expose supplier-money flow only through settlement endpoints");

    String purchasingController = TruthSuiteFileAssert.read(PURCHASING_CONTROLLER);
    assertFalse(
        purchasingController.contains("/settlements/suppliers"),
        "Purchasing controller must not expose supplier settlement endpoints");
  }

  @Test
  void purchaseCreationFlowPostsPurchaseJournalOnly() {
    TruthSuiteFileAssert.assertContainsInOrder(
        PURCHASING_SERVICE,
        "public RawMaterialPurchaseResponse createPurchase(RawMaterialPurchaseRequest request)",
        "JournalEntryDto entry = postPurchaseEntry(",
        "request,",
        "supplier,",
        "inventoryDebits,",
        "taxAmount,",
        "totalAmount,",
        "referenceNumber,",
        "gstBreakdown);",
        "purchase = purchaseRepository.save(purchase);");

    String source = TruthSuiteFileAssert.read(PURCHASING_SERVICE);
    assertFalse(
        source.contains("settleSupplierInvoices("),
        "Purchase creation flow must not invoke supplier settlement path");
    assertFalse(
        source.contains("recordSupplierPayment("),
        "Purchase creation flow must not invoke supplier payment path");
  }

  @Test
  void supplierSettlementRequestContractDoesNotIncludePurchaseInvoiceFields() {
    String source = TruthSuiteFileAssert.read(SUPPLIER_SETTLEMENT_REQUEST);
    assertFalse(
        source.contains("invoiceNumber"),
        "Supplier settlement request must not accept purchase invoice number field");
    assertFalse(
        source.contains("goodsReceiptId"),
        "Supplier settlement request must not accept goods receipt field");
  }

  @Test
  void supplierSettlementFlowRetainsOpenItemAndIdempotencyFailClosedGuards() {
    TruthSuiteFileAssert.assertContains(
        ACCOUNTING_SERVICE,
        "On-account supplier settlement allocations cannot include discount/write-off/FX"
            + " adjustments",
        "Settlement allocation exceeds purchase outstanding amount",
        "remainingByPurchase.put(purchase.getId(),"
            + " currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));",
        "validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER");
  }
}
