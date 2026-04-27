package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.controller.StatementReportController;
import com.bigbrightpaints.erp.modules.accounting.controller.StatementReportControllerSupport;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

@Tag("critical")
class TS_RuntimeAccountingExportCoverageTest {

  @Test
  void supplierPdfExportEndpoints_emitAuditMetadataAndPdfHeaders() {
    StatementService statementService = mock(StatementService.class);
    AuditService auditService = mock(AuditService.class);
    StatementReportController controller = newController(statementService, auditService);

    when(statementService.supplierStatementPdf(eq(20L), any(), any()))
        .thenReturn("supplier".getBytes());
    when(statementService.supplierAgingPdf(eq(40L), any(), any()))
        .thenReturn("supplier-aging".getBytes());

    var statementResponse = controller.supplierStatementPdf(20L, null, null);
    var agingResponse = controller.supplierAgingPdf(40L, null, null);

    assertThat(statementResponse.getHeaders().getFirst("Content-Disposition"))
        .contains("supplier-statement.pdf");
    assertThat(agingResponse.getHeaders().getFirst("Content-Disposition"))
        .contains("supplier-aging.pdf");

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService, org.mockito.Mockito.times(2))
        .logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
    List<Map<String, String>> calls = metadataCaptor.getAllValues();
    assertThat(calls)
        .anySatisfy(
            metadata ->
                assertThat(metadata)
                    .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_STATEMENT")
                    .containsEntry("resourceId", "20")
                    .containsEntry("operation", "EXPORT")
                    .containsEntry("format", "pdf"));
    assertThat(calls)
        .anySatisfy(
            metadata ->
                assertThat(metadata)
                    .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_AGING")
                    .containsEntry("resourceId", "40")
                    .containsEntry("operation", "EXPORT")
                    .containsEntry("format", "pdf"));
  }

  @Test
  void supplierPdfExportEndpoints_remainAvailableWhenAuditServiceUnavailable() {
    StatementService statementService = mock(StatementService.class);
    StatementReportController controller = newController(statementService, null);
    when(statementService.supplierStatementPdf(eq(20L), any(), any()))
        .thenReturn("supplier".getBytes());

    var response = controller.supplierStatementPdf(20L, null, null);

    assertThat(response.getBody()).isEqualTo("supplier".getBytes());
  }

  private StatementReportController newController(
      StatementService statementService, AuditService auditService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            mock(AccountingService.class),
            mock(SalesReturnService.class),
            statementService,
            auditService));
  }
}
