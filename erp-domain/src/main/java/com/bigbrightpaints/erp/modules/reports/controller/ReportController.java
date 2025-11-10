package com.bigbrightpaints.erp.modules.reports.controller;

import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/reports/account-statement")
    public ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> accountStatement() {
        return ResponseEntity.ok(ApiResponse.success(reportService.accountStatement()));
    }

    @GetMapping("/accounting/reports/aged-debtors")
    public ResponseEntity<ApiResponse<List<AgedDebtorDto>>> agedDebtors() {
        return ResponseEntity.ok(ApiResponse.success(reportService.agedDebtors()));
    }
}
