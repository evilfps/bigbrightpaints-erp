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

@Tag("critical")
class TS_truthsuite_p2p_Settlement_Test {

    private InvoiceSettlementPolicy policy;
    private Invoice invoice;

    @BeforeEach
    void init() {
        policy = new InvoiceSettlementPolicy();
        invoice = new Invoice();
        invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.DRAFT.name());
        invoice.setTotalAmount(BigDecimal.valueOf(100));
        invoice.setOutstandingAmount(BigDecimal.valueOf(100));
        policy.ensureIssuable(invoice);
    }

    @Test
    void settlementOverrideFailClosedOnMissingReferenceAndWrongReason() {
        SettlementApprovalDecision validOverride = new SettlementApprovalDecision(
                "APP-S1",
                "maker-a",
                "checker-b",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));
        SettlementApprovalDecision wrongReason = new SettlementApprovalDecision(
                "APP-S2",
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
                        " ",
                        validOverride));
        assertThrows(IllegalArgumentException.class, () ->
                policy.applySettlementWithOverride(
                        invoice,
                        BigDecimal.valueOf(40),
                        BigDecimal.TEN,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "SETTLE-S2",
                        wrongReason));
    }

    @Test
    void settlementOverrideSucceedsWithValidApprovalAndEnforcesSupplierExceptionCode() {
        SettlementApprovalDecision validOverride = new SettlementApprovalDecision(
                "APP-S3",
                "maker-a",
                "checker-b",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));
        SettlementApprovalDecision supplierException = new SettlementApprovalDecision(
                "APP-S4",
                "maker-c",
                "checker-d",
                SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

        policy.applySettlementWithOverride(
                invoice,
                BigDecimal.valueOf(40),
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "SETTLE-S3",
                validOverride);

        assertEquals(BigDecimal.valueOf(50), invoice.getOutstandingAmount());
        assertEquals(InvoiceSettlementPolicy.InvoiceStatus.PARTIAL.name(), invoice.getStatus());
        assertEquals(SettlementApprovalReasonCode.SUPPLIER_EXCEPTION,
                policy.requireSupplierExceptionApproval(supplierException).reasonCode());
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireSupplierExceptionApproval(validOverride));
    }
}
