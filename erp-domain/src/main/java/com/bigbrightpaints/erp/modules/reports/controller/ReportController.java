package com.bigbrightpaints.erp.modules.reports.controller;

import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestCreateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDto;
import com.bigbrightpaints.erp.modules.admin.dto.ExportRequestDownloadResponse;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class ReportController {

    private final ReportService reportService;
    private final AccountHierarchyService accountHierarchyService;
    private final AgingReportService agingReportService;
    private final ExportApprovalService exportApprovalService;

    public ReportController(ReportService reportService,
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
            return ResponseEntity.ok(ApiResponse.success(
                    reportService.balanceSheet(ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
        }
        return ResponseEntity.ok(ApiResponse.success(reportService.balanceSheet(
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
            return ResponseEntity.ok(ApiResponse.success(
                    reportService.profitLoss(ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
        }
        return ResponseEntity.ok(ApiResponse.success(reportService.profitLoss(
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
    public ResponseEntity<ApiResponse<InventoryValuationDto>> inventoryValuation(@RequestParam(required = false) String date) {
        if (date != null && !date.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(
                    reportService.inventoryValuationAsOf(java.time.LocalDate.parse(date))));
        }
        return ResponseEntity.ok(ApiResponse.success(reportService.inventoryValuation()));
    }

    @GetMapping("/reports/gst-return")
    public ResponseEntity<ApiResponse<GstReturnReportDto>> gstReturn(@RequestParam(required = false) Long periodId) {
        return ResponseEntity.ok(ApiResponse.success(reportService.gstReturn(periodId)));
    }

    @GetMapping("/reports/inventory-reconciliation")
    public ResponseEntity<ApiResponse<ReconciliationSummaryDto>> inventoryReconciliation() {
        return ResponseEntity.ok(ApiResponse.success(reportService.inventoryReconciliation()));
    }

    @GetMapping("/reports/balance-warnings")
    public ResponseEntity<ApiResponse<List<BalanceWarningDto>>> balanceWarnings() {
        return ResponseEntity.ok(ApiResponse.success(reportService.balanceWarnings()));
    }

    @GetMapping("/reports/reconciliation-dashboard")
    public ResponseEntity<ApiResponse<ReconciliationDashboardDto>> reconciliationDashboard(@RequestParam Long bankAccountId,
                                                                                           @RequestParam(required = false) BigDecimal statementBalance) {
        return ResponseEntity.ok(ApiResponse.success(reportService.reconciliationDashboard(bankAccountId, statementBalance)));
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
            return ResponseEntity.ok(ApiResponse.success(
                    reportService.trialBalance(ReportQueryRequestBuilder.fromAsOfDate(java.time.LocalDate.parse(date)))));
        }
        return ResponseEntity.ok(ApiResponse.success(reportService.trialBalance(
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
    public ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> accountStatement() {
        return ResponseEntity.ok(ApiResponse.success(reportService.accountStatement()));
    }

    @GetMapping("/reports/aged-debtors")
    public ResponseEntity<ApiResponse<List<AgedDebtorDto>>> agedDebtors(
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) java.time.LocalDate startDate,
            @RequestParam(required = false) java.time.LocalDate endDate,
            @RequestParam(required = false) String exportFormat) {
        return ResponseEntity.ok(ApiResponse.success(reportService.agedDebtors(
                ReportQueryRequestBuilder.fromPeriodAndRange(
                        periodId,
                        startDate,
                        endDate,
                        null,
                        null,
                        null,
                        exportFormat))));
    }

    @GetMapping("/reports/balance-sheet/hierarchy")
    public ResponseEntity<ApiResponse<AccountHierarchyService.BalanceSheetHierarchy>> balanceSheetHierarchy() {
        return ResponseEntity.ok(ApiResponse.success(
                "Hierarchical balance sheet",
                accountHierarchyService.getBalanceSheetHierarchy()));
    }

    @GetMapping("/reports/income-statement/hierarchy")
    public ResponseEntity<ApiResponse<AccountHierarchyService.IncomeStatementHierarchy>> incomeStatementHierarchy() {
        return ResponseEntity.ok(ApiResponse.success(
                "Hierarchical income statement",
                accountHierarchyService.getIncomeStatementHierarchy()));
    }

    @GetMapping("/reports/aging/receivables")
    public ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> agedReceivables(
            @RequestParam(required = false) String asOfDate) {
        if (asOfDate != null && !asOfDate.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(
                    "Aged receivables report",
                    agingReportService.getAgedReceivablesReport(java.time.LocalDate.parse(asOfDate.trim()))));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Aged receivables report",
                agingReportService.getAgedReceivablesReport()));
    }

    @GetMapping("/reports/aging/dealer/{dealerId}")
    public ResponseEntity<ApiResponse<AgingReportService.DealerAgingDetail>> dealerAging(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Dealer aging summary",
                agingReportService.getDealerAging(dealerId)));
    }

    @GetMapping("/reports/aging/dealer/{dealerId}/detailed")
    public ResponseEntity<ApiResponse<AgingReportService.DealerAgingDetailedReport>> dealerAgingDetailed(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Dealer aging detail with invoices",
                agingReportService.getDealerAgingDetailed(dealerId)));
    }

    @GetMapping("/reports/dso/dealer/{dealerId}")
    public ResponseEntity<ApiResponse<AgingReportService.DSOReport>> dealerDso(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Days Sales Outstanding report",
                agingReportService.getDealerDSO(dealerId)));
    }

    @GetMapping("/reports/wastage")
    public ResponseEntity<ApiResponse<List<WastageReportDto>>> wastageReport() {
        return ResponseEntity.ok(ApiResponse.success(reportService.wastageReport()));
    }

    @GetMapping("/reports/production-logs/{id}/cost-breakdown")
    public ResponseEntity<ApiResponse<CostBreakdownDto>> costBreakdown(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(reportService.costBreakdown(id)));
    }

    @GetMapping("/reports/monthly-production-costs")
    public ResponseEntity<ApiResponse<MonthlyProductionCostDto>> monthlyProductionCosts(
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ResponseEntity.ok(ApiResponse.success(reportService.monthlyProductionCosts(year, month)));
    }

    @PostMapping("/exports/request")
    public ResponseEntity<ApiResponse<ExportRequestDto>> requestExport(
            @RequestBody ExportRequestCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Export request queued", exportApprovalService.createRequest(request)));
    }

    @GetMapping("/exports/{requestId}/download")
    public ResponseEntity<ApiResponse<ExportRequestDownloadResponse>> downloadExport(
            @PathVariable Long requestId) {
        return ResponseEntity.ok(ApiResponse.success(exportApprovalService.resolveDownload(requestId)));
    }
}
