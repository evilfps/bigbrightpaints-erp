package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.SensitiveDisclosurePolicyOwner;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

class StatementReportControllerExportGovernanceContractTest {

  @Test
  void supplierPdfExports_requireAdminAuthorityAnnotation() throws NoSuchMethodException {
    Method supplierStatementPdf =
        StatementReportController.class.getMethod(
            "supplierStatementPdf", Long.class, String.class, String.class);
    Method supplierAgingPdf =
        StatementReportController.class.getMethod(
            "supplierAgingPdf", Long.class, String.class, String.class);

    assertThat(supplierStatementPdf.getAnnotation(PreAuthorize.class)).isNotNull();
    assertThat(supplierStatementPdf.getAnnotation(PreAuthorize.class).value())
        .isEqualTo(SensitiveDisclosurePolicyOwner.ADMIN_ONLY);
    assertThat(supplierAgingPdf.getAnnotation(PreAuthorize.class)).isNotNull();
    assertThat(supplierAgingPdf.getAnnotation(PreAuthorize.class).value())
        .isEqualTo(SensitiveDisclosurePolicyOwner.ADMIN_ONLY);
  }

  @Test
  void supplierStatementPdf_logsDeterministicDataExportEvidence() {
    StatementService statementService = mock(StatementService.class);
    AuditService auditService = mock(AuditService.class);
    byte[] pdf = "pdf".getBytes();
    when(statementService.supplierStatementPdf(
            17L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
        .thenReturn(pdf);

    StatementReportController controller =
        new StatementReportController(
            new StatementReportControllerSupport(
                mock(AccountingService.class),
                mock(SalesReturnService.class),
                statementService,
                auditService));

    ResponseEntity<byte[]> response =
        controller.supplierStatementPdf(17L, "2026-01-01", "2026-01-31");

    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=supplier-statement.pdf");
    assertThat(response.getBody()).isEqualTo(pdf);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_STATEMENT")
        .containsEntry("resourceId", "17")
        .containsEntry("operation", "EXPORT")
        .containsEntry("format", "pdf");
  }
}
