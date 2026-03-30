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
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.controller.AccountingController;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
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
@Tag("security")
class TS_AccountingControllerExportAuditRuntimeCoverageTest {

  @SuppressWarnings("unchecked")
  @Test
  void supplierStatementPdf_logsDeterministicExportAuditEvidence() {
    StatementService statementService = mock(StatementService.class);
    when(statementService.supplierStatementPdf(eq(17L), any(), any())).thenReturn("pdf".getBytes());
    AccountingController controller = newController(statementService);
    AuditService auditService = mock(AuditService.class);
    ReflectionTestUtils.setField(controller, "auditService", auditService);

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
    AccountingController controller = newController(mock(StatementService.class));
    ReflectionTestUtils.invokeMethod(
        controller, "logAccountingExport", "ACCOUNTING_SUPPLIER_STATEMENT", 42L, "pdf");

    AuditService auditService = mock(AuditService.class);
    ReflectionTestUtils.setField(controller, "auditService", auditService);
    ReflectionTestUtils.invokeMethod(
        controller, "logAccountingExport", "ACCOUNTING_SUPPLIER_STATEMENT", 42L, "pdf");

    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService).logSuccess(eq(AuditEvent.DATA_EXPORT), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("resourceType", "ACCOUNTING_SUPPLIER_STATEMENT")
        .containsEntry("resourceId", "42")
        .containsEntry("operation", "EXPORT")
        .containsEntry("format", "pdf");
  }

  private static AccountingController newController(StatementService statementService) {
    return new AccountingController(
        mock(AccountingService.class),
        null,
        null,
        null,
        null,
        null,
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
  }
}
