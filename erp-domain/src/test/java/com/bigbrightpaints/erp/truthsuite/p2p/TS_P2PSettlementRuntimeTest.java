package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.invoice.service.SettlementApprovalDecision;
import com.bigbrightpaints.erp.modules.invoice.service.SettlementApprovalReasonCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("critical")
@Tag("reconciliation")
class TS_P2PSettlementRuntimeTest {

    private InvoiceSettlementPolicy policy;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        policy = new InvoiceSettlementPolicy();
        invoice = new Invoice();
        invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.DRAFT.name());
        invoice.setTotalAmount(BigDecimal.valueOf(100));
        invoice.setOutstandingAmount(BigDecimal.valueOf(100));
        policy.ensureIssuable(invoice);
    }

    @Test
    void settlementOverrideFailsClosedWhenReferenceMissing() {
        SettlementApprovalDecision approval = approvedOverride("APP-SET-101");
        assertThrows(IllegalArgumentException.class, () ->
                policy.applySettlementWithOverride(
                        invoice,
                        BigDecimal.valueOf(40),
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        " ",
                        approval));
    }

    @Test
    void settlementOverrideFailsClosedWhenReasonCodeDoesNotMatch() {
        SettlementApprovalDecision wrongReason = new SettlementApprovalDecision(
                "APP-SET-102",
                "maker-a",
                "checker-b",
                SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

        assertThrows(IllegalArgumentException.class, () ->
                policy.applySettlementWithOverride(
                        invoice,
                        BigDecimal.valueOf(40),
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "SETTLE-OVERRIDE-102",
                        wrongReason));
    }

    @Test
    void settlementOverrideSucceedsWithValidApproval() {
        policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(40),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "SETTLE-OVERRIDE-103",
                approvedOverride("APP-SET-103"));

        assertEquals(BigDecimal.valueOf(50), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
    }

    @Test
    void supplierExceptionApprovalEnforcesReasonCode() {
        SettlementApprovalDecision supplierException = new SettlementApprovalDecision(
                "APP-SET-104",
                "maker-a",
                "checker-b",
                SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));
        SettlementApprovalDecision settlementOverride = approvedOverride("APP-SET-105");

        assertEquals(SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
                policy.requireSupplierExceptionApproval(supplierException).reasonCode());
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireSupplierExceptionApproval(settlementOverride));
    }

    @Test
    void settlementOverrideSkipsApprovalWhenNoOverrideComponentsArePresent() {
        policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(25),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "SETTLE-OVERRIDE-106",
                null);

        assertEquals(BigDecimal.valueOf(75), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
    }

    @Test
    void settlementOverrideEvaluatesWriteOffAndFxAdjustmentApprovalPaths() {
        policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(20),
                BigDecimal.ZERO,
                BigDecimal.valueOf(5),
                BigDecimal.ZERO,
                "SETTLE-OVERRIDE-107A",
                approvedOverride("APP-SET-107A"));

        assertEquals(BigDecimal.valueOf(75), invoice.getOutstandingAmount());

        policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(10),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(5),
                "SETTLE-OVERRIDE-107B",
                approvedOverride("APP-SET-107B"));

        assertEquals(BigDecimal.valueOf(60), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
    }

    @Test
    void settlementOverrideRejectsNegativeDiscountAndWriteOff() {
        assertThrows(IllegalArgumentException.class, () ->
                policy.applySettlementWithOverride(
                        invoice,
                        BigDecimal.valueOf(30),
                        BigDecimal.valueOf(-1),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "SETTLE-OVERRIDE-108A",
                        approvedOverride("APP-SET-108A")));

        assertThrows(IllegalArgumentException.class, () ->
                policy.applySettlementWithOverride(
                        invoice,
                        BigDecimal.valueOf(30),
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(-1),
                        BigDecimal.ZERO,
                        "SETTLE-OVERRIDE-108B",
                        approvedOverride("APP-SET-108B")));
    }

    @Test
    void settlementOverrideCoversNullAppliedAndFxAmounts() {
        policy.applySettlementWithOverride(
                invoice,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                "SETTLE-OVERRIDE-109",
                null);

        assertEquals(BigDecimal.valueOf(100), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.ISSUED.name(), invoice.getStatus());
    }

    @Test
    void settlementOverrideFailsClosedWhenSupplierExceptionApprovalMissing() {
        assertThrows(IllegalStateException.class, () -> policy.requireSupplierExceptionApproval(null));
    }

    @Test
    void settlementIsIdempotentByReference() {
        policy.applySettlement(invoice, BigDecimal.valueOf(40), "SETTLE-REF-110");
        policy.applySettlement(invoice, BigDecimal.valueOf(10), "SETTLE-REF-110");

        assertEquals(BigDecimal.valueOf(60), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
        assertEquals(1, invoice.getPaymentReferences().size());
        assertTrue(invoice.getPaymentReferences().contains("SETTLE-REF-110"));
    }

    @Test
    void settlementFailsClosedOnVoidAndReversedInvoices() {
        invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.VOID.name());
        assertThrows(IllegalStateException.class,
                () -> policy.applySettlement(invoice, BigDecimal.valueOf(10), "SETTLE-VOID-111"));
        assertEquals(BigDecimal.valueOf(100), invoice.getOutstandingAmount());
        assertEquals(0, invoice.getPaymentReferences().size());

        invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.REVERSED.name());
        assertThrows(IllegalStateException.class,
                () -> policy.applySettlement(invoice, BigDecimal.valueOf(10), "SETTLE-REV-111"));
        assertEquals(BigDecimal.valueOf(100), invoice.getOutstandingAmount());
        assertEquals(0, invoice.getPaymentReferences().size());
    }

    private SettlementApprovalDecision approvedOverride(String approvalId) {
        return new SettlementApprovalDecision(
                approvalId,
                "maker-a",
                "checker-b",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));
    }
}
