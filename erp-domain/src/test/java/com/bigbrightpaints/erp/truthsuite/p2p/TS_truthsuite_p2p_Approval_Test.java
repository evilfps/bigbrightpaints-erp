package com.bigbrightpaints.erp.truthsuite.p2p;

import com.bigbrightpaints.erp.modules.invoice.service.SettlementApprovalDecision;
import com.bigbrightpaints.erp.modules.invoice.service.SettlementApprovalReasonCode;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalDecision;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalPolicy;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalReasonCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("critical")
class TS_truthsuite_p2p_Approval_Test {

    @Test
    void approvalContractsFailClosedAndEnforceMakerChecker() {
        assertThrows(IllegalArgumentException.class, () -> new SettlementApprovalDecision(
                "APP-A1",
                "maker",
                "checker",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("approvalSource", "workflow")));
        assertThrows(IllegalArgumentException.class, () -> new SettlementApprovalDecision(
                "APP-A2",
                "maker",
                "checker",
                SettlementApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095")));
        assertThrows(IllegalArgumentException.class, () -> new SupplierApprovalDecision(
                "APP-A3",
                "same-user",
                "same-user",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow")));
    }

    @Test
    void supplierApprovalPolicyEnforcesReasonCodesAndImmutableAuditMetadata() {
        SupplierApprovalPolicy policy = new SupplierApprovalPolicy();
        SupplierApprovalDecision supplierException = new SupplierApprovalDecision(
                "APP-A4",
                "maker-a",
                "checker-b",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));
        SupplierApprovalDecision settlementOverride = new SupplierApprovalDecision(
                "APP-A5",
                "maker-c",
                "checker-d",
                SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                Map.of("ticket", "TKT-ERP-STAGE-095", "approvalSource", "workflow"));

        assertEquals(SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                policy.requireSupplierExceptionApproval(supplierException).reasonCode());
        assertEquals(SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                policy.requireSettlementOverrideApproval(settlementOverride).reasonCode());
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireSupplierExceptionApproval(settlementOverride));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireSettlementOverrideApproval(supplierException));
        assertThrows(UnsupportedOperationException.class,
                () -> settlementOverride.immutableAuditMetadata().put("x", "y"));
    }
}
