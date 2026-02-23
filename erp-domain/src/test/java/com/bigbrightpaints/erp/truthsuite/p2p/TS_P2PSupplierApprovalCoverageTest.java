package com.bigbrightpaints.erp.truthsuite.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalDecision;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalPolicy;
import com.bigbrightpaints.erp.modules.purchasing.service.SupplierApprovalReasonCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("reconciliation")
class TS_P2PSupplierApprovalCoverageTest {

    private final SupplierApprovalPolicy policy = new SupplierApprovalPolicy();

    @Test
    void supplierApprovalDecisionFailsClosedWhenMakerCheckerBoundaryDiffersOnlyByCaseAndWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> new SupplierApprovalDecision(
                "APP-SUP-301",
                " maker-user ",
                "MAKER-USER",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("approvalSource", "workflow")));
    }

    @Test
    void supplierApprovalDecisionAcceptsAllConfiguredApprovalSourceAliases() {
        SupplierApprovalDecision fromApprovalSource = new SupplierApprovalDecision(
                "APP-SUP-302A",
                "maker-a",
                "checker-a",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("approvalSource", "workflow"));

        SupplierApprovalDecision fromSource = new SupplierApprovalDecision(
                "APP-SUP-302B",
                "maker-b",
                "checker-b",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("source", "portal"));

        SupplierApprovalDecision fromSourceSystem = new SupplierApprovalDecision(
                "APP-SUP-302C",
                "maker-c",
                "checker-c",
                SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("sourceSystem", "mobile-app"));

        SupplierApprovalDecision fromWorkflowSource = new SupplierApprovalDecision(
                "APP-SUP-302D",
                "maker-d",
                "checker-d",
                SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("workflowSource", "backoffice"));

        assertEquals("workflow", fromApprovalSource.immutableAuditMetadata().get("approvalSource"));
        assertEquals("portal", fromSource.immutableAuditMetadata().get("source"));
        assertEquals("mobile-app", fromSourceSystem.immutableAuditMetadata().get("sourceSystem"));
        assertEquals("backoffice", fromWorkflowSource.immutableAuditMetadata().get("workflowSource"));
    }

    @Test
    void supplierApprovalDecisionBuildsDeterministicAuditMetadataAndKeepsItImmutable() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ticket", " TKT-ERP-STAGE-100 ");
        metadata.put("source", " approvals-ui ");
        metadata.put(" ", "ignored");
        metadata.put("emptyValue", "   ");

        SupplierApprovalDecision decision = new SupplierApprovalDecision(
                " APP-SUP-303 ",
                " maker-user ",
                " checker-user ",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T01:00:00Z"),
                metadata);

        Map<String, String> auditMetadata = decision.immutableAuditMetadata();
        assertEquals("TKT-ERP-STAGE-100", auditMetadata.get("ticket"));
        assertEquals("approvals-ui", auditMetadata.get("source"));
        assertEquals("APP-SUP-303", auditMetadata.get("approvalId"));
        assertEquals("maker-user", auditMetadata.get("makerUserId"));
        assertEquals("checker-user", auditMetadata.get("checkerUserId"));
        assertEquals("SUPPLIER_EXCEPTION", auditMetadata.get("reasonCode"));
        assertEquals("2026-02-20T01:00:00Z", auditMetadata.get("approvedAt"));
        assertThrows(UnsupportedOperationException.class, () -> auditMetadata.put("newKey", "newValue"));
    }

    @Test
    void supplierApprovalPolicyFailsClosedForMissingOrWrongApprovalContext() {
        IllegalStateException missingApproval = assertThrows(
                IllegalStateException.class,
                () -> policy.requireSupplierExceptionApproval(null));
        assertEquals("Supplier exception approval is required", missingApproval.getMessage());

        SupplierApprovalDecision supplierException = new SupplierApprovalDecision(
                "APP-SUP-304",
                "maker-e",
                "checker-e",
                SupplierApprovalReasonCode.SUPPLIER_EXCEPTION,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("sourceSystem", "workflow"));

        IllegalArgumentException wrongReason = assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireSettlementOverrideApproval(supplierException));
        assertEquals("Settlement override approval must use SETTLEMENT_OVERRIDE reason code", wrongReason.getMessage());

        SupplierApprovalDecision settlementOverride = new SupplierApprovalDecision(
                "APP-SUP-305",
                "maker-f",
                "checker-f",
                SupplierApprovalReasonCode.SETTLEMENT_OVERRIDE,
                Instant.parse("2026-02-20T00:00:00Z"),
                metadataWithSource("source", "workflow"));

        assertSame(settlementOverride, policy.requireSettlementOverrideApproval(settlementOverride));
    }

    private Map<String, String> metadataWithSource(String key, String value) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ticket", "TKT-ERP-STAGE-100");
        metadata.put(key, value);
        return metadata;
    }
}
