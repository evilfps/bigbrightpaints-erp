package com.bigbrightpaints.erp.modules.hr.controller;

import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService;
import com.bigbrightpaints.erp.modules.hr.service.PayrollService.*;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payroll Management API (HR module)
 */
@RestController
@RequestMapping("/api/v1/payroll")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class HrPayrollController {

    private final PayrollService payrollService;

    public HrPayrollController(PayrollService payrollService) {
        this.payrollService = payrollService;
    }

    // ===== PAYROLL RUNS =====

    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<List<PayrollRunDto>>> listPayrollRuns() {
        return ResponseEntity.ok(ApiResponse.success("Payroll runs", payrollService.listPayrollRuns()));
    }

    @GetMapping("/runs/weekly")
    public ResponseEntity<ApiResponse<List<PayrollRunDto>>> listWeeklyPayrollRuns() {
        return ResponseEntity.ok(ApiResponse.success("Weekly payroll runs",
            payrollService.listPayrollRunsByType(PayrollRun.RunType.WEEKLY)));
    }

    @GetMapping("/runs/monthly")
    public ResponseEntity<ApiResponse<List<PayrollRunDto>>> listMonthlyPayrollRuns() {
        return ResponseEntity.ok(ApiResponse.success("Monthly payroll runs",
            payrollService.listPayrollRunsByType(PayrollRun.RunType.MONTHLY)));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<ApiResponse<PayrollRunDto>> getPayrollRun(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(payrollService.getPayrollRun(id)));
    }

    @GetMapping("/runs/{id}/lines")
    public ResponseEntity<ApiResponse<List<PayrollRunLineDto>>> getPayrollRunLines(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Payroll run lines", payrollService.getPayrollRunLines(id)));
    }

    // ===== CREATE PAYROLL RUNS =====

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<PayrollRunDto>> createPayrollRun(@Valid @RequestBody CreatePayrollRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll run created", payrollService.createPayrollRun(request)));
    }

    @PostMapping("/runs/weekly")
    public ResponseEntity<ApiResponse<PayrollRunDto>> createWeeklyPayrollRun(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEndingDate) {
        return ResponseEntity.ok(ApiResponse.success("Weekly payroll run created",
            payrollService.createWeeklyPayrollRun(weekEndingDate)));
    }

    @PostMapping("/runs/monthly")
    public ResponseEntity<ApiResponse<PayrollRunDto>> createMonthlyPayrollRun(
            @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.success("Monthly payroll run created",
            payrollService.createMonthlyPayrollRun(year, month)));
    }

    // ===== PAYROLL WORKFLOW =====

    @PostMapping("/runs/{id}/calculate")
    public ResponseEntity<ApiResponse<PayrollRunDto>> calculatePayroll(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Payroll calculated", payrollService.calculatePayroll(id)));
    }

    @PostMapping("/runs/{id}/approve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PayrollRunDto>> approvePayroll(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Payroll approved", payrollService.approvePayroll(id)));
    }

    @PostMapping("/runs/{id}/post")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PayrollRunDto>> postPayroll(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Payroll posted to accounting",
            payrollService.postPayrollToAccounting(id)));
    }

    @PostMapping("/runs/{id}/mark-paid")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PayrollRunDto>> markAsPaid(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String paymentReference = request.get("paymentReference");
        return ResponseEntity.ok(ApiResponse.success("Payroll marked as paid",
            payrollService.markAsPaid(id, paymentReference)));
    }

    // ===== PAY SUMMARIES (What to Pay) =====

    @GetMapping("/summary/weekly")
    public ResponseEntity<ApiResponse<WeeklyPaySummaryDto>> getWeeklyPaySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEndingDate) {
        return ResponseEntity.ok(ApiResponse.success("Weekly pay summary",
            payrollService.getWeeklyPaySummary(weekEndingDate)));
    }

    @GetMapping("/summary/monthly")
    public ResponseEntity<ApiResponse<MonthlyPaySummaryDto>> getMonthlyPaySummary(
            @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.success("Monthly pay summary",
            payrollService.getMonthlyPaySummary(year, month)));
    }

    // ===== CURRENT PERIOD SHORTCUTS =====

    @GetMapping("/summary/current-week")
    public ResponseEntity<ApiResponse<WeeklyPaySummaryDto>> getCurrentWeekPaySummary() {
        LocalDate today = LocalDate.now();
        LocalDate saturday = today.plusDays(6 - today.getDayOfWeek().getValue());
        return ResponseEntity.ok(ApiResponse.success("Current week pay summary",
            payrollService.getWeeklyPaySummary(saturday)));
    }

    @GetMapping("/summary/current-month")
    public ResponseEntity<ApiResponse<MonthlyPaySummaryDto>> getCurrentMonthPaySummary() {
        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(ApiResponse.success("Current month pay summary",
            payrollService.getMonthlyPaySummary(today.getYear(), today.getMonthValue())));
    }
}
