package com.bigbrightpaints.erp.truthsuite.reports;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("critical")
@Tag("security")
class TS_AccountingExportAuditGovernanceContractTest {

    private static final String ACCOUNTING_CONTROLLER =
            "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java";

    @Test
    void auditDigestEndpointsRemainAdminOnly() {
        TruthSuiteFileAssert.assertContainsInOrder(
                ACCOUNTING_CONTROLLER,
                "@GetMapping(\"/audit/digest\")",
                "@PreAuthorize(\"hasAuthority('ROLE_ADMIN')\")",
                "public ResponseEntity<ApiResponse<AuditDigestResponse>> auditDigest(",
                "@GetMapping(value = \"/audit/digest.csv\", produces = \"text/csv\")",
                "@PreAuthorize(\"hasAuthority('ROLE_ADMIN')\")",
                "public ResponseEntity<String> auditDigestCsv(");
    }

    @Test
    void csvExportAuditEvidenceStaysDeterministic() {
        TruthSuiteFileAssert.assertContainsInOrder(
                ACCOUNTING_CONTROLLER,
                "private void logAccountingExport(String resourceType, Long resourceId, String format)",
                "metadata.put(\"resourceType\", resourceType);",
                "metadata.put(\"resourceId\", resourceId != null ? resourceId.toString() : \"\");",
                "metadata.put(\"operation\", \"EXPORT\");",
                "metadata.put(\"format\", format);",
                "auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);");
    }
}
