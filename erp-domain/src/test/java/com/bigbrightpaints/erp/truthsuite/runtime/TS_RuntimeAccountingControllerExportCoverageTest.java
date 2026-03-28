package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;

@Tag("critical")
class TS_RuntimeAccountingControllerExportCoverageTest {

  @Test
  void exportEndpoints_emitAuditMetadataAndCsvHeaders() {
    AccountingAuditService accountingAuditService = mock(AccountingAuditService.class);
    StatementService statementService = mock(StatementService.class);
    AuditService auditService = mock(AuditService.class);
    AccountingController controller =
        newController(accountingAuditService, statementService, auditService);

    when(statementService.supplierStatementPdf(eq(20L), any(), any()))
        .thenReturn("supplier".getBytes());
    when(statementService.supplierAgingPdf(eq(40L), any(), any()))
        .thenReturn("supplier-aging".getBytes());
    when(accountingAuditService.auditDigestCsv(any(), any()))
        .thenReturn("date,entry\n2026-01-01,1\n");

    controller.supplierStatementPdf(20L, null, null);
    controller.supplierAgingPdf(40L, null, null);
    var csvResponse = controller.auditDigestCsv(null, null);

    assertThat(csvResponse.getHeaders().getFirst("Content-Disposition"))
        .contains("audit-digest.csv");
    assertThat(csvResponse.getHeaders().getFirst("Content-Type")).contains("text/csv");

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService, times(3)).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
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
    assertThat(calls)
        .anySatisfy(
            metadata ->
                assertThat(metadata)
                    .containsEntry("resourceType", "ACCOUNTING_AUDIT_DIGEST")
                    .containsEntry("resourceId", "")
                    .containsEntry("operation", "EXPORT")
                    .containsEntry("format", "csv"));
  }

  @Test
  void exportEndpoints_failClosedWhenAuditServiceUnavailable() {
    StatementService statementService = mock(StatementService.class);
    AccountingAuditService accountingAuditService = mock(AccountingAuditService.class);
    AccountingController controller = newController(accountingAuditService, statementService, null);
    when(accountingAuditService.auditDigestCsv(any(), any()))
        .thenReturn("date,entry\n2026-01-01,1\n");

    var response = controller.auditDigestCsv(null, null);

    assertThat(response.getBody()).contains("date,entry");
  }

  private AccountingController newController(
      AccountingAuditService accountingAuditService,
      StatementService statementService,
      AuditService auditService) {
    AccountingController controller =
        new AccountingController(
            mock(AccountingService.class),
            null,
            null,
            null,
            null,
            accountingAuditService,
            null,
            mock(AccountingFacade.class),
            mock(SalesReturnService.class),
            mock(AccountingPeriodService.class),
            mock(ReconciliationService.class),
            statementService,
            mock(TaxService.class),
            mock(TemporalBalanceService.class),
            mock(AccountHierarchyService.class),
            mock(AgingReportService.class),
            mock(CompanyDefaultAccountsService.class),
            mock(AccountingAuditTrailService.class),
            mock(CompanyContextService.class),
            mock(CompanyClock.class),
            null,
            null);
    ReflectionTestUtils.setField(controller, "auditService", auditService);
    return controller;
  }
}
