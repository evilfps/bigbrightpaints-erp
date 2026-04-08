package com.bigbrightpaints.erp.modules.reports.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDto;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class ReportController {

  private final ReportService reportService;
  private final AccountHierarchyService accountHierarchyService;
  private final AgingReportService agingReportService;
  private final ExportApprovalService exportApprovalService;

  public ReportController(
      ReportService reportService,
      AccountHierarchyService accountHierarchyService,
      AgingReportService agingReportService,
      ExportApprovalService exportApprovalService) {
    this.reportService = reportService;
    this.accountHierarchyService = accountHierarchyService;
    this.agingReportService = agingReportService;
    this.exportApprovalService = exportApprovalService;
  }

  @GetMapping("/reports/balance-sheet")
  public ResponseEntity<ApiResponse<BalanceSheetDto>> balanceSheet(
      @RequestParam(required = false) String date,
      @RequestParam(required = false) Long periodId,
      @RequestParam(required = false) java.time.LocalDate startDate,
      @RequestParam(required = false) java.time.LocalDate endDate,
      @RequestParam(required = false) java.time.LocalDate comparativeStartDate,
      @RequestParam(required = false) java.time.LocalDate comparativeEndDate,
      @RequestParam(required = false) Long comparativePeriodId,
      @RequestParam(required = false) String exportFormat) {
    if (date != null && !date.isBlank()) {
      return ResponseEntity.ok(
          ApiResponse.success(
              reportService.balanceSheet(
                  ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.balanceSheet(
                ReportQueryRequestBuilder.fromPeriodAndRange(
                    periodId,
                    startDate,
                    endDate,
                    comparativeStartDate,
                    comparativeEndDate,
                    comparativePeriodId,
                    exportFormat))));
  }

  @GetMapping("/reports/profit-loss")
  public ResponseEntity<ApiResponse<ProfitLossDto>> profitLoss(
      @RequestParam(required = false) String date,
      @RequestParam(required = false) Long periodId,
      @RequestParam(required = false) java.time.LocalDate startDate,
      @RequestParam(required = false) java.time.LocalDate endDate,
      @RequestParam(required = false) java.time.LocalDate comparativeStartDate,
      @RequestParam(required = false) java.time.LocalDate comparativeEndDate,
      @RequestParam(required = false) Long comparativePeriodId,
      @RequestParam(required = false) String exportFormat) {
    if (date != null && !date.isBlank()) {
      return ResponseEntity.ok(
          ApiResponse.success(
              reportService.profitLoss(
                  ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.profitLoss(
                ReportQueryRequestBuilder.fromPeriodAndRange(
                    periodId,
                    startDate,
                    endDate,
                    comparativeStartDate,
                    comparativeEndDate,
                    comparativePeriodId,
                    exportFormat))));
  }

  @GetMapping("/reports/cash-flow")
  public ResponseEntity<ApiResponse<CashFlowDto>> cashFlow() {
    return ResponseEntity.ok(ApiResponse.success(reportService.cashFlow()));
  }

  @GetMapping("/reports/inventory-valuation")
  public ResponseEntity<ApiResponse<InventoryValuationDto>> inventoryValuation(
      @RequestParam(required = false) String date) {
    if (date != null && !date.isBlank()) {
      return ResponseEntity.ok(
          ApiResponse.success(
              reportService.inventoryValuationAsOf(java.time.LocalDate.parse(date))));
    }
    return ResponseEntity.ok(ApiResponse.success(reportService.inventoryValuation()));
  }

  @GetMapping("/reports/gst-return")
  public ResponseEntity<ApiResponse<GstReturnReportDto>> gstReturn(
      @RequestParam(required = false) Long periodId) {
    return ResponseEntity.ok(ApiResponse.success(reportService.gstReturn(periodId)));
  }

  @GetMapping("/reports/inventory-reconciliation")
  public ResponseEntity<ApiResponse<InventoryReconciliationReportDto>> inventoryReconciliation() {
    return ResponseEntity.ok(ApiResponse.success(reportService.inventoryReconciliationReport()));
  }

  @GetMapping("/reports/balance-warnings")
  public ResponseEntity<ApiResponse<List<BalanceWarningDto>>> balanceWarnings() {
    return ResponseEntity.ok(ApiResponse.success(reportService.balanceWarnings()));
  }

  @GetMapping("/reports/reconciliation-dashboard")
  public ResponseEntity<ApiResponse<ReconciliationDashboardDto>> reconciliationDashboard(
      @RequestParam(required = false) Long bankAccountId,
      @RequestParam(required = false) BigDecimal statementBalance) {
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.reconciliationDashboard(bankAccountId, statementBalance)));
  }

  @GetMapping("/reports/trial-balance")
  public ResponseEntity<ApiResponse<TrialBalanceDto>> trialBalance(
      @RequestParam(required = false) String date,
      @RequestParam(required = false) Long periodId,
      @RequestParam(required = false) java.time.LocalDate startDate,
      @RequestParam(required = false) java.time.LocalDate endDate,
      @RequestParam(required = false) java.time.LocalDate comparativeStartDate,
      @RequestParam(required = false) java.time.LocalDate comparativeEndDate,
      @RequestParam(required = false) Long comparativePeriodId,
      @RequestParam(required = false) String exportFormat) {
    if (date != null && !date.isBlank()) {
      return ResponseEntity.ok(
          ApiResponse.success(
              reportService.trialBalance(
                  ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.trialBalance(
                ReportQueryRequestBuilder.fromPeriodAndRange(
                    periodId,
                    startDate,
                    endDate,
                    comparativeStartDate,
                    comparativeEndDate,
                    comparativePeriodId,
                    exportFormat))));
  }

  @GetMapping("/reports/account-statement")
  public ResponseEntity<ApiResponse<AccountStatementReportDto>> accountStatement(
      @RequestParam Long accountId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.accountStatement(
                accountId, parseOptionalDate(from, "from"), parseOptionalDate(to, "to"))));
  }

  @GetMapping("/reports/aged-debtors")
  public ResponseEntity<ApiResponse<List<AgedDebtorDto>>> agedDebtors(
      @RequestParam(required = false) Long periodId,
      @RequestParam(required = false) java.time.LocalDate startDate,
      @RequestParam(required = false) java.time.LocalDate endDate,
      @RequestParam(required = false) String exportFormat) {
    return ResponseEntity.ok(
        ApiResponse.success(
            reportService.agedDebtors(
                ReportQueryRequestBuilder.fromPeriodAndRange(
                    periodId, startDate, endDate, null, null, null, exportFormat))));
  }

  @GetMapping("/reports/balance-sheet/hierarchy")
  public ResponseEntity<ApiResponse<AccountHierarchyService.BalanceSheetHierarchy>>
      balanceSheetHierarchy() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Hierarchical balance sheet", accountHierarchyService.getBalanceSheetHierarchy()));
  }

  @GetMapping("/reports/income-statement/hierarchy")
  public ResponseEntity<ApiResponse<AccountHierarchyService.IncomeStatementHierarchy>>
      incomeStatementHierarchy() {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Hierarchical income statement",
            accountHierarchyService.getIncomeStatementHierarchy()));
  }

  @GetMapping("/reports/aging/receivables")
  public ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> agedReceivables(
      @RequestParam(required = false) String asOfDate) {
    if (asOfDate != null && !asOfDate.isBlank()) {
      return ResponseEntity.ok(
          ApiResponse.success(
              "Aged receivables report",
              agingReportService.getAgedReceivablesReport(
                  java.time.LocalDate.parse(asOfDate.trim()))));
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            "Aged receivables report", agingReportService.getAgedReceivablesReport()));
  }

  @GetMapping("/reports/wastage")
  public ResponseEntity<ApiResponse<List<WastageReportDto>>> wastageReport() {
    return ResponseEntity.ok(ApiResponse.success(reportService.wastageReport()));
  }

  @GetMapping("/reports/product-costing")
  public ResponseEntity<ApiResponse<ProductCostingReportDto>> productCosting(
      @RequestParam Long itemId) {
    return ResponseEntity.ok(ApiResponse.success(reportService.productCosting(itemId)));
  }

  @GetMapping("/reports/cost-allocation")
  public ResponseEntity<ApiResponse<CostAllocationReportDto>> costAllocationReport() {
    return ResponseEntity.ok(ApiResponse.success(reportService.costAllocationReport()));
  }

  @GetMapping("/reports/production-logs/{id}/cost-breakdown")
  public ResponseEntity<ApiResponse<CostBreakdownDto>> costBreakdown(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(reportService.costBreakdown(id)));
  }

  @GetMapping(
      value = "/reports/monthly-production-costs",
      params = {"year", "month"})
  public ResponseEntity<ApiResponse<MonthlyProductionCostDto>> monthlyProductionCostsByPeriod(
      @RequestParam Integer year, @RequestParam Integer month) {
    return ResponseEntity.ok(
        ApiResponse.success(reportService.monthlyProductionCosts(year, month)));
  }

  @GetMapping("/reports/monthly-production-costs")
  public ResponseEntity<ApiResponse<List<MonthlyProductionCostEntryDto>>> monthlyProductionCosts() {
    return ResponseEntity.ok(ApiResponse.success(reportService.monthlyProductionCosts()));
  }

  @PostMapping("/exports/request")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<ApiResponse<ExportRequestDto>> requestExport(
      @RequestBody ExportRequestCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.success(
                "Export request queued", exportApprovalService.createRequest(request)));
  }

  @GetMapping("/exports/{requestId}/download")
  public ResponseEntity<byte[]> downloadExport(@PathVariable Long requestId) {
    ExportApprovalService.ExportDownloadPayload payload =
        exportApprovalService.resolveDownload(requestId);
    return ResponseEntity.ok()
        .contentType(resolveMediaType(payload.contentType()))
        .header("Content-Disposition", "attachment; filename=" + payload.fileName())
        .body(payload.content());
  }

  private LocalDate parseOptionalDate(String raw, String parameterName) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (RuntimeException ex) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_DATE,
              "Invalid " + parameterName + " date format; expected ISO date yyyy-MM-dd")
          .withDetail(parameterName, raw);
    }
  }

  private MediaType resolveMediaType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    try {
      return MediaType.parseMediaType(contentType);
    } catch (IllegalArgumentException ex) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
