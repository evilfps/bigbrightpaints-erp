package com.bigbrightpaints.erp.modules.reports.controller;

import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/reports/balance-sheet")
    public ResponseEntity<ApiResponse<BalanceSheetDto>> balanceSheet() {
        return ResponseEntity.ok(ApiResponse.success(reportService.balanceSheet()));
    }

    @GetMapping("/reports/profit-loss")
    public ResponseEntity<ApiResponse<ProfitLossDto>> profitLoss() {
        return ResponseEntity.ok(ApiResponse.success(reportService.profitLoss()));
    }

    @GetMapping("/reports/cash-flow")
    public ResponseEntity<ApiResponse<CashFlowDto>> cashFlow() {
        return ResponseEntity.ok(ApiResponse.success(reportService.cashFlow()));
    }

    @GetMapping("/reports/inventory-valuation")
    public ResponseEntity<ApiResponse<InventoryValuationDto>> inventoryValuation() {
        return ResponseEntity.ok(ApiResponse.success(reportService.inventoryValuation()));
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
    public ResponseEntity<ApiResponse<TrialBalanceDto>> trialBalance() {
        return ResponseEntity.ok(ApiResponse.success(reportService.trialBalance()));
    }

    @GetMapping("/reports/account-statement")
    public ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> accountStatement() {
        return ResponseEntity.ok(ApiResponse.success(reportService.accountStatement()));
    }

    @GetMapping("/accounting/reports/aged-debtors")
    public ResponseEntity<ApiResponse<List<AgedDebtorDto>>> agedDebtors() {
        return ResponseEntity.ok(ApiResponse.success(reportService.agedDebtors()));
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
}
