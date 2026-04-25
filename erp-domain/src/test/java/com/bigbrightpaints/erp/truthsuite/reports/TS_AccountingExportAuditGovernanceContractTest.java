package com.bigbrightpaints.erp.truthsuite.reports;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.truthsuite.support.TruthSuiteFileAssert;

@Tag("critical")
@Tag("security")
class TS_AccountingExportAuditGovernanceContractTest {

  private static final String STATEMENT_REPORT_CONTROLLER =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/StatementReportController.java";
  private static final String STATEMENT_REPORT_SUPPORT =
      "src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/StatementReportControllerSupport.java";

  @Test
  void supplierPdfExportEndpointsRemainAdminOnly() {
    TruthSuiteFileAssert.assertContainsInOrder(
        STATEMENT_REPORT_CONTROLLER,
        "@GetMapping(value = \"/statements/suppliers/{supplierId}/pdf\", produces ="
            + " \"application/pdf\")",
        "@PreAuthorize(SensitiveDisclosurePolicyOwner.ADMIN_ONLY)",
        "public ResponseEntity<byte[]> supplierStatementPdf(",
        "@GetMapping(value = \"/aging/suppliers/{supplierId}/pdf\", produces ="
            + " \"application/pdf\")",
        "@PreAuthorize(SensitiveDisclosurePolicyOwner.ADMIN_ONLY)",
        "public ResponseEntity<byte[]> supplierAgingPdf(");
  }

  @Test
  void csvExportAuditEvidenceStaysDeterministic() {
    TruthSuiteFileAssert.assertContainsInOrder(
        STATEMENT_REPORT_SUPPORT,
        "private void logAccountingExport(String resourceType, Long resourceId, String format)",
        "metadata.put(\"resourceType\", resourceType);",
        "metadata.put(\"resourceId\", resourceId != null ? resourceId.toString() : \"\");",
        "metadata.put(\"operation\", \"EXPORT\");",
        "metadata.put(\"format\", format);",
        "auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);");
  }
}
