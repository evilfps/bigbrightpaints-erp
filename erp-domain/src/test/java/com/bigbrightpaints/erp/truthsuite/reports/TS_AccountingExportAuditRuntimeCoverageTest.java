package com.bigbrightpaints.erp.truthsuite.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.controller.StatementReportController;
import com.bigbrightpaints.erp.modules.accounting.controller.StatementReportControllerSupport;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

@Tag("critical")
@Tag("security")
class TS_AccountingExportAuditRuntimeCoverageTest {

  @SuppressWarnings("unchecked")
  @Test
  void supplierStatementPdf_logsDeterministicExportAuditEvidence() {
    StatementService statementService = mock(StatementService.class);
    when(statementService.supplierStatementPdf(eq(17L), any(), any())).thenReturn("pdf".getBytes());
    AuditService auditService = mock(AuditService.class);
    StatementReportController controller = newController(statementService, auditService);

    ResponseEntity<byte[]> response =
        controller.supplierStatementPdf(17L, "2026-02-01", "2026-02-28");

    assertThat(response.getBody()).isEqualTo("pdf".getBytes());
    verify(statementService)
        .supplierStatementPdf(17L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_STATEMENT")
        .containsEntry("resourceId", "17")
        .containsEntry("operation", "EXPORT")
        .containsEntry("format", "pdf");
  }

  @SuppressWarnings("unchecked")
  @Test
  void logAccountingExport_handlesMissingAuditService_andResourceIdBranch() {
    StatementService statementService = mock(StatementService.class);
    when(statementService.supplierStatementPdf(eq(42L), any(), any())).thenReturn("pdf".getBytes());
    StatementReportController controllerWithoutAudit = newController(statementService, null);
    ResponseEntity<byte[]> response = controllerWithoutAudit.supplierStatementPdf(42L, null, null);
    assertThat(response.getBody()).isEqualTo("pdf".getBytes());

    AuditService auditService = mock(AuditService.class);
    StatementReportController controller = newController(statementService, auditService);
    controller.supplierStatementPdf(42L, null, null);

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_STATEMENT")
        .containsEntry("resourceId", "42")
        .containsEntry("operation", "EXPORT")
        .containsEntry("format", "pdf");
  }

  private static StatementReportController newController(
      StatementService statementService, AuditService auditService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            mock(AccountingService.class),
            mock(SalesReturnService.class),
            statementService,
            auditService));
  }
}
