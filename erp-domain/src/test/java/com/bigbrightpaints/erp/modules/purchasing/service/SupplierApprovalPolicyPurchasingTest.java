package com.bigbrightpaints.erp.modules.purchasing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplierApprovalPolicyPurchasingTest {

    private SupplierApprovalPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new SupplierApprovalPolicy();
    }

    @Test
    void supplierExceptionApprovalFailsClosedWhenMissing() {
        assertThrows(IllegalStateException.class, () -> policy.requireSupplierExceptionApproval(null));
    }

    @Test
    void settlementOverrideApprovalRejectsWrongReasonCode() {
        SupplierApprovalDecision decision = new SupplierApprovalDecision(
                "APP-1",
                "maker-user",
                "checker-user",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

        assertThrows(IllegalArgumentException.class, () -> policy.requireSettlementOverrideApproval(decision));
    }

    @Test
    void supplierApprovalDecisionEnforcesMakerChecker() {
        assertThrows(IllegalArgumentException.class, () -> new SupplierApprovalDecision(
                "APP-2",
                "same-user",
                "same-user",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow")));
    }

    @Test
    void supplierApprovalDecisionBuildsImmutableAuditMetadata() {
        SupplierApprovalDecision decision = new SupplierApprovalDecision(
                "APP-3",
                "maker-user",
                "checker-user",
                SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

        assertEquals("SETTLEMENT_OVERRIDE", decision.immutableAuditMetadata().get("reasonCode"));
        assertThrows(UnsupportedOperationException.class,
                () -> decision.immutableAuditMetadata().put("newKey", "newValue"));
    }

    @Test
    void supplierApprovalMetadataRequiresTicket() {
        assertThrows(IllegalArgumentException.class, () -> new SupplierApprovalDecision(
                "APP-4",
                "maker-user",
                "checker-user",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("approvalSource", "workflow")));
    }
}
