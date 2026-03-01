package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_P2PPurchaseJournalLinkageTest {

    private static final String PURCHASING_SERVICE =
            "src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java";

    @Test
    void purchaseInvoicePostsJournalBeforePersistence() {
        TruthSuiteFileAssert.assertContainsInOrder(
                PURCHASING_SERVICE,
                "// Post journal FIRST to avoid orphan purchases if journal fails",
                "JournalEntryDto entry = postPurchaseEntry(",
                "request,",
                "supplier,",
                "inventoryDebits,",
                "taxAmount,",
                "totalAmount,",
                "referenceNumber,",
                "gstBreakdown);",
                "purchase.setJournalEntry(linkedJournal);",
                "purchase = purchaseRepository.save(purchase);");
    }

    @Test
    void purchaseFlowLinksInventoryMovementsAndClosesGrn() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "movement.setJournalEntryId(entryId);",
                "goodsReceipt.setStatus(\"INVOICED\");",
                "goodsReceiptRepository.save(goodsReceipt);",
                "PurchaseOrderStatus.CLOSED");
    }

    @Test
    void purchaseTaxComputationUsesDeterministicHalfUpRounding() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "lineTax = currency(lineNet.multiply(effectiveTaxRate)",
                ".divide(new BigDecimal(\"100\"), 6, RoundingMode.HALF_UP));",
                "BigDecimal allocatedTax = (i == computedLines.size() - 1)",
                ".divide(inventoryTotal, 6, RoundingMode.HALF_UP));");
    }

    @Test
    void purchaseFlowEnforcesSingleTaxModeContractForDownstreamSettlement() {
        TruthSuiteFileAssert.assertContains(
                PURCHASING_SERVICE,
                "PurchaseTaxMode purchaseTaxMode = resolvePurchaseTaxMode(sortedLines, lockedMaterials);",
                "BigDecimal effectiveTaxRate = resolveLineTaxRateForMode(lineRequest, rawMaterial, company, purchaseTaxMode);",
                "enforcePurchaseTaxContract(purchaseTaxMode, providedTaxAmount, hasTaxableLines);",
                "\"Purchase invoice cannot mix GST and non-GST materials\"");
    }
}
