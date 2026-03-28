package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.exception.GlobalExceptionHandler;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.reports.dto.AccountStatementEntryDto;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.GstReturnReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@Tag("critical")
class ReportControllerContractTest {

  @Test
  void balanceSheet_withAsOfDateDelegatesToQueryRequestBuilder() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    BalanceSheetDto expected =
        new BalanceSheetDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);
    when(reportService.balanceSheet(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class)))
        .thenReturn(expected);

    ResponseEntity<ApiResponse<BalanceSheetDto>> response =
        controller.balanceSheet("2026-03-31", null, null, null, null, null, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(reportService)
        .balanceSheet(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class));
  }

  @Test
  void profitLoss_withAsOfDateDelegatesToQueryRequestBuilder() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    ProfitLossDto expected =
        new ProfitLossDto(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null);
    when(reportService.profitLoss(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class)))
        .thenReturn(expected);

    ResponseEntity<ApiResponse<ProfitLossDto>> response =
        controller.profitLoss("2026-03-31", null, null, null, null, null, null, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(reportService)
        .profitLoss(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class));
  }

  @Test
  void trialBalance_withDateRangeAndComparativeParametersDelegatesToQueryRequestBuilder() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    TrialBalanceDto expected =
        new TrialBalanceDto(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, null, null);
    when(reportService.trialBalance(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class)))
        .thenReturn(expected);

    ResponseEntity<ApiResponse<TrialBalanceDto>> response =
        controller.trialBalance(
            null,
            100L,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 28),
            99L,
            "CSV");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(reportService)
        .trialBalance(
            any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class));
  }

  @Test
  void agedDebtors_usesCanonicalReportsPathAndDelegatesToReportServiceWithQueryRequest() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    when(reportService.agedDebtors(any())).thenReturn(List.of());

    ResponseEntity<ApiResponse<List<com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto>>>
        response =
            controller.agedDebtors(77L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "PDF");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    verify(reportService).agedDebtors(any());
  }

  @Test
  void accountStatement_serializesJournalEntryIdInApiResponse() throws Exception {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    when(reportService.accountStatement())
        .thenReturn(
            List.of(
                new AccountStatementEntryDto(
                    "Dealer Trace",
                    LocalDate.of(2026, 2, 12),
                    "INV-9001",
                    new BigDecimal("300.00"),
                    new BigDecimal("25.00"),
                    new BigDecimal("275.00"),
                    9001L)));

    ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> response =
        controller.accountStatement();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).hasSize(1);
    assertThat(response.getBody().data().get(0).journalEntryId()).isEqualTo(9001L);

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String json = mapper.writeValueAsString(response.getBody());
    assertThat(json).contains("\"journalEntryId\":9001");
  }

  @Test
  void accountStatement_serializesNullJournalEntryIdInApiResponse() throws Exception {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    when(reportService.accountStatement())
        .thenReturn(
            List.of(
                new AccountStatementEntryDto(
                    "Dealer No Journal",
                    LocalDate.of(2026, 2, 12),
                    "BALANCE",
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"),
                    null)));

    ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> response =
        controller.accountStatement();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).hasSize(1);
    assertThat(response.getBody().data().get(0).journalEntryId()).isNull();

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String json = mapper.writeValueAsString(response.getBody());
    assertThat(json).contains("\"journalEntryId\":null");
  }

  @Test
  void gstReturn_delegatesToServiceWithPeriodId() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    ReportController controller = controller(reportService, exportApprovalService);
    GstReturnReportDto expected =
        new GstReturnReportDto(
            10L,
            "March 2026",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            new GstReturnReportDto.GstComponentSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new GstReturnReportDto.GstComponentSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            new GstReturnReportDto.GstComponentSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            List.of(),
            List.of(),
            null);
    when(reportService.gstReturn(10L)).thenReturn(expected);

    ResponseEntity<ApiResponse<GstReturnReportDto>> response = controller.gstReturn(10L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(reportService).gstReturn(10L);
  }

  @Test
  void balanceSheetHierarchy_delegatesToCanonicalReportHost() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    AccountHierarchyService accountHierarchyService = mock(AccountHierarchyService.class);
    ReportController controller =
        new ReportController(
            reportService,
            accountHierarchyService,
            mock(AgingReportService.class),
            exportApprovalService);
    AccountHierarchyService.BalanceSheetHierarchy expected =
        new AccountHierarchyService.BalanceSheetHierarchy(
            List.of(), BigDecimal.ZERO, List.of(), BigDecimal.ZERO, List.of(), BigDecimal.ZERO);
    when(accountHierarchyService.getBalanceSheetHierarchy()).thenReturn(expected);

    ResponseEntity<ApiResponse<AccountHierarchyService.BalanceSheetHierarchy>> response =
        controller.balanceSheetHierarchy();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(accountHierarchyService).getBalanceSheetHierarchy();
  }

  @Test
  void incomeStatementHierarchy_delegatesToCanonicalReportHost() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    AccountHierarchyService accountHierarchyService = mock(AccountHierarchyService.class);
    ReportController controller =
        new ReportController(
            reportService,
            accountHierarchyService,
            mock(AgingReportService.class),
            exportApprovalService);
    AccountHierarchyService.IncomeStatementHierarchy expected =
        new AccountHierarchyService.IncomeStatementHierarchy(
            List.of(),
            BigDecimal.ZERO,
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of(),
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    when(accountHierarchyService.getIncomeStatementHierarchy()).thenReturn(expected);

    ResponseEntity<ApiResponse<AccountHierarchyService.IncomeStatementHierarchy>> response =
        controller.incomeStatementHierarchy();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(accountHierarchyService).getIncomeStatementHierarchy();
  }

  @Test
  void agedReceivables_parsesAsOfDateForCanonicalReportHost() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    AgingReportService agingReportService = mock(AgingReportService.class);
    ReportController controller =
        new ReportController(
            reportService,
            mock(AccountHierarchyService.class),
            agingReportService,
            exportApprovalService);
    AgingReportService.AgedReceivablesReport expected =
        new AgingReportService.AgedReceivablesReport(
            LocalDate.of(2026, 2, 28),
            List.of(),
            new AgingReportService.AgingBuckets(),
            BigDecimal.ZERO);
    when(agingReportService.getAgedReceivablesReport(LocalDate.of(2026, 2, 28)))
        .thenReturn(expected);

    ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> response =
        controller.agedReceivables("2026-02-28");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(agingReportService).getAgedReceivablesReport(LocalDate.of(2026, 2, 28));
  }

  @Test
  void agedReceivables_withoutAsOfDateUsesDefaultServiceDate() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    AgingReportService agingReportService = mock(AgingReportService.class);
    ReportController controller =
        new ReportController(
            reportService,
            mock(AccountHierarchyService.class),
            agingReportService,
            exportApprovalService);
    AgingReportService.AgedReceivablesReport expected =
        new AgingReportService.AgedReceivablesReport(
            LocalDate.of(2026, 3, 20),
            List.of(),
            new AgingReportService.AgingBuckets(),
            BigDecimal.ZERO);
    when(agingReportService.getAgedReceivablesReport()).thenReturn(expected);

    ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> response =
        controller.agedReceivables(null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(agingReportService).getAgedReceivablesReport();
  }

  @Test
  void agedReceivables_blankAsOfDateUsesDefaultServiceDate() {
    ReportService reportService = mock(ReportService.class);
    ExportApprovalService exportApprovalService = mock(ExportApprovalService.class);
    AgingReportService agingReportService = mock(AgingReportService.class);
    ReportController controller =
        new ReportController(
            reportService,
            mock(AccountHierarchyService.class),
            agingReportService,
            exportApprovalService);
    AgingReportService.AgedReceivablesReport expected =
        new AgingReportService.AgedReceivablesReport(
            LocalDate.of(2026, 3, 20),
            List.of(),
            new AgingReportService.AgingBuckets(),
            BigDecimal.ZERO);
    when(agingReportService.getAgedReceivablesReport()).thenReturn(expected);

    ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> response =
        controller.agedReceivables("  ");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).isEqualTo(expected);
    verify(agingReportService).getAgedReceivablesReport();
  }

  @Test
  void agedReceivables_moduleDisabledUsesGlobalForbiddenContractOnCanonicalReportHost()
      throws Exception {
    AgingReportService agingReportService = mock(AgingReportService.class);
    when(agingReportService.getAgedReceivablesReport())
        .thenThrow(
            new ApplicationException(
                ErrorCode.MODULE_DISABLED,
                "Module REPORTS_ADVANCED is disabled for the current tenant"));

    reportControllerMvc(agingReportService)
        .perform(get("/api/v1/reports/aging/receivables"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(
            jsonPath("$.message")
                .value("Module REPORTS_ADVANCED is disabled for the current tenant"))
        .andExpect(jsonPath("$.data.code").value(ErrorCode.MODULE_DISABLED.getCode()))
        .andExpect(
            jsonPath("$.data.reason")
                .value("Module REPORTS_ADVANCED is disabled for the current tenant"))
        .andExpect(jsonPath("$.data.path").value("/api/v1/reports/aging/receivables"))
        .andExpect(jsonPath("$.data.traceId").isNotEmpty());
  }

  private ReportController controller(
      ReportService reportService, ExportApprovalService exportApprovalService) {
    return new ReportController(
        reportService,
        mock(AccountHierarchyService.class),
        mock(AgingReportService.class),
        exportApprovalService);
  }

  private MockMvc reportControllerMvc(AgingReportService agingReportService) {
    ReportController controller =
        new ReportController(
            mock(ReportService.class),
            mock(AccountHierarchyService.class),
            agingReportService,
            mock(ExportApprovalService.class));
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }
}
