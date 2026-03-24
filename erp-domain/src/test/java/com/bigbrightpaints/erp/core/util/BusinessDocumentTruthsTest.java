package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("critical")
class BusinessDocumentTruthsTest {

    @Test
    void salesOrderLifecycle_staysNotEligibleBeforeInvoiceOrJournalTruthExists() {
        SalesOrder order = new SalesOrder();
        order.setStatus("READY_TO_SHIP");

        assertThat(BusinessDocumentTruths.salesOrderLifecycle(order).workflowStatus()).isEqualTo("READY_TO_SHIP");
        assertThat(BusinessDocumentTruths.salesOrderLifecycle(order).accountingStatus()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void salesOrderLifecycle_becomesPendingWhenInvoiceTruthExistsWithoutJournal() {
        SalesOrder order = new SalesOrder();
        order.setStatus("READY_TO_SHIP");
        order.setFulfillmentInvoiceId(123L);

        assertThat(BusinessDocumentTruths.salesOrderLifecycle(order).accountingStatus()).isEqualTo("PENDING");
    }

    @Test
    void salesOrderLifecycle_becomesPostedWhenSalesJournalTruthExists() {
        SalesOrder order = new SalesOrder();
        order.setStatus("INVOICED");
        order.setSalesJournalEntryId(456L);

        assertThat(BusinessDocumentTruths.salesOrderLifecycle(order).accountingStatus()).isEqualTo("POSTED");
    }

    @Test
    void salesOrderLifecycle_defaultsAndPendingStateAreDerivedFromFulfillmentTruth() {
        SalesOrder order = new SalesOrder();
        order.setFulfillmentInvoiceId(123L);

        assertThat(BusinessDocumentTruths.salesOrderLifecycle(null).workflowStatus()).isEqualTo("DRAFT");
        assertThat(BusinessDocumentTruths.salesOrderLifecycle(null).accountingStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(BusinessDocumentTruths.salesOrderLifecycle(order).accountingStatus()).isEqualTo("PENDING");
    }

    @Test
    void packagingSlipLifecycle_marksPostedWhenInvoiceOrJournalTruthExists() {
        PackagingSlip slip = new PackagingSlip();
        slip.setStatus("DISPATCHED");
        slip.setInvoiceId(77L);

        assertThat(BusinessDocumentTruths.packagingSlipLifecycle(slip).workflowStatus()).isEqualTo("DISPATCHED");
        assertThat(BusinessDocumentTruths.packagingSlipLifecycle(slip).accountingStatus()).isEqualTo("POSTED");
    }

    @Test
    void packagingSlipLifecycle_defaultsToPendingWhenNoPostingTruthExists() {
        assertThat(BusinessDocumentTruths.packagingSlipLifecycle(null).workflowStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.packagingSlipLifecycle(null).accountingStatus()).isEqualTo("PENDING");
    }

    @Test
    void packagingSlipLifecycle_usesCogsJournalWhenInvoiceTruthIsAbsent() {
        PackagingSlip slip = new PackagingSlip();
        slip.setStatus("DISPATCHED");
        slip.setCogsJournalEntryId(91L);

        assertThat(BusinessDocumentTruths.packagingSlipLifecycle(slip).accountingStatus()).isEqualTo("POSTED");
    }

    @Test
    void invoiceLifecycle_andJournalLifecycle_followWorkflowAndJournalState() {
        JournalEntry postedJournal = new JournalEntry();
        postedJournal.setStatus("POSTED");

        JournalEntry reversedJournal = new JournalEntry();
        reversedJournal.setStatus("VOIDED");

        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", postedJournal).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("BLOCKED", null).accountingStatus()).isEqualTo("BLOCKED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("VOIDED", reversedJournal).accountingStatus()).isEqualTo("REVERSED");
        assertThat(BusinessDocumentTruths.journalLifecycle(reversedJournal).accountingStatus()).isEqualTo("REVERSED");
    }

    @Test
    void invoiceLifecycle_andJournalLifecycle_coverDraftPendingAndFailedBranches() {
        JournalEntry failedJournal = new JournalEntry();
        failedJournal.setStatus("FAILED");

        assertThat(BusinessDocumentTruths.invoiceLifecycle(null, null).accountingStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", null).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.purchaseLifecycle(new RawMaterialPurchase()).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.journalLifecycle(failedJournal).accountingStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void goodsReceiptLifecycle_andPurchaseLifecycle_followLinkedPurchaseJournalTruth() {
        JournalEntry purchaseJournal = new JournalEntry();
        purchaseJournal.setStatus("POSTED");

        RawMaterialPurchase purchase = new RawMaterialPurchase();
        purchase.setStatus("POSTED");
        purchase.setJournalEntry(purchaseJournal);

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setStatus("INVOICED");

        assertThat(BusinessDocumentTruths.purchaseLifecycle(purchase).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(receipt, purchase).workflowStatus()).isEqualTo("INVOICED");
        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(receipt, purchase).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(receipt, null).accountingStatus()).isEqualTo("PENDING");
    }

    @Test
    void purchaseLifecycle_andSettlementLifecycle_coverBlockedAndReversedBranches() {
        RawMaterialPurchase blockedPurchase = new RawMaterialPurchase();
        blockedPurchase.setStatus("BLOCKED");

        JournalEntry reversedJournal = new JournalEntry();
        reversedJournal.setStatus("VOIDED");

        assertThat(BusinessDocumentTruths.purchaseLifecycle(blockedPurchase).accountingStatus()).isEqualTo("BLOCKED");
        assertThat(BusinessDocumentTruths.settlementLifecycle(reversedJournal).accountingStatus()).isEqualTo("REVERSED");
    }

    @Test
    void lifecycleHelpers_coverPostedBlockedAndCancelledJournalStatuses() {
        JournalEntry paidJournal = new JournalEntry();
        paidJournal.setStatus("PAID");
        JournalEntry settledJournal = new JournalEntry();
        settledJournal.setStatus("SETTLED");
        JournalEntry closedJournal = new JournalEntry();
        closedJournal.setStatus("CLOSED");
        JournalEntry blockedJournal = new JournalEntry();
        blockedJournal.setStatus("BLOCKED");
        JournalEntry cancelledJournal = new JournalEntry();
        cancelledJournal.setStatus("CANCELLED");

        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", paidJournal).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", settledJournal).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", closedJournal).accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", blockedJournal).accountingStatus()).isEqualTo("BLOCKED");
        assertThat(BusinessDocumentTruths.journalLifecycle(cancelledJournal).accountingStatus()).isEqualTo("REVERSED");
    }

    @Test
    void invoiceAndGoodsReceiptLifecycle_coverPendingAndNotEligibleBranches() {
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setStatus("RECEIVED");

        assertThat(BusinessDocumentTruths.invoiceLifecycle("DRAFT", null).accountingStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("ISSUED", null).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(receipt, null).accountingStatus()).isEqualTo("PENDING");

        JournalEntry failedJournal = new JournalEntry();
        failedJournal.setStatus("FAILED");
        assertThat(BusinessDocumentTruths.journalLifecycle(failedJournal).accountingStatus()).isEqualTo("BLOCKED");
    }

    @Test
    void settlementLifecycle_andReference_preserveDocumentMetadata() {
        JournalEntry settlementJournal = new JournalEntry();
        ReflectionTestUtils.setField(settlementJournal, "id", 808L);
        settlementJournal.setStatus("POSTED");

        LinkedBusinessReferenceDto reference = BusinessDocumentTruths.reference(
                "SETTLEMENT",
                "SETTLEMENT_ALLOCATION",
                707L,
                "idem-707",
                BusinessDocumentTruths.settlementLifecycle(settlementJournal),
                settlementJournal.getId()
        );

        assertThat(reference.documentId()).isEqualTo(707L);
        assertThat(reference.journalEntryId()).isEqualTo(808L);
        assertThat(reference.lifecycle().workflowStatus()).isEqualTo("ALLOCATED");
        assertThat(reference.lifecycle().accountingStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.settlementLifecycle(null).accountingStatus()).isEqualTo("PENDING");
    }

    @Test
    void lifecycleHelpers_coverNullBlankAndDefaultBranches() {
        JournalEntry draftJournal = new JournalEntry();
        draftJournal.setStatus(" ");

        RawMaterialPurchase linkedPurchase = new RawMaterialPurchase();
        linkedPurchase.setStatus(" ");

        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(null, linkedPurchase).workflowStatus()).isEqualTo("RECEIVED");
        assertThat(BusinessDocumentTruths.goodsReceiptLifecycle(null, linkedPurchase).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.purchaseLifecycle(null).workflowStatus()).isEqualTo("POSTED");
        assertThat(BusinessDocumentTruths.purchaseLifecycle(null).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.journalLifecycle(null).workflowStatus()).isEqualTo("DRAFT");
        assertThat(BusinessDocumentTruths.journalLifecycle(null).accountingStatus()).isEqualTo("PENDING");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("   ", draftJournal).accountingStatus()).isEqualTo("NOT_ELIGIBLE");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("cancelled", null).accountingStatus()).isEqualTo("REVERSED");
        assertThat(BusinessDocumentTruths.invoiceLifecycle("issued", draftJournal).accountingStatus()).isEqualTo("PENDING");
    }
}
