package com.bigbrightpaints.erp.modules.accounting.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.security.SensitiveDisclosurePolicyOwner;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerStatementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class StatementReportController {

  private final StatementReportControllerSupport support;

  public StatementReportController(StatementReportControllerSupport support) {
    this.support = support;
  }

  @GetMapping("/gst/return")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<GstReturnDto>> generateGstReturn(
      @RequestParam(required = false) String period) {
    return ResponseEntity.ok(ApiResponse.success(support.generateGstReturn(period)));
  }

  @GetMapping("/gst/reconciliation")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<GstReconciliationDto>> getGstReconciliation(
      @RequestParam(required = false) String period) {
    return ResponseEntity.ok(ApiResponse.success(support.getGstReconciliation(period)));
  }

  @GetMapping("/sales/returns")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
  public ResponseEntity<ApiResponse<List<JournalEntryDto>>> listSalesReturns() {
    return ResponseEntity.ok(ApiResponse.success("Sales returns", support.listSalesReturns()));
  }

  @PostMapping("/sales/returns/preview")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<SalesReturnPreviewDto>> previewSalesReturn(
      @Valid @RequestBody SalesReturnRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Sales return preview", support.previewSalesReturn(request)));
  }

  @PostMapping("/sales/returns")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> recordSalesReturn(
      @Valid @RequestBody SalesReturnRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Credit note posted", support.recordSalesReturn(request)));
  }

  @GetMapping("/statements/suppliers/{supplierId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PartnerStatementResponse>> supplierStatement(
      @PathVariable Long supplierId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ResponseEntity.ok(ApiResponse.success(support.supplierStatement(supplierId, from, to)));
  }

  @GetMapping("/aging/suppliers/{supplierId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<AgingSummaryResponse>> supplierAging(
      @PathVariable Long supplierId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String buckets) {
    return ResponseEntity.ok(ApiResponse.success(support.supplierAging(supplierId, asOf, buckets)));
  }

  @GetMapping(value = "/statements/suppliers/{supplierId}/pdf", produces = "application/pdf")
  @PreAuthorize(SensitiveDisclosurePolicyOwner.ADMIN_ONLY)
  public ResponseEntity<byte[]> supplierStatementPdf(
      @PathVariable Long supplierId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=supplier-statement.pdf")
        .body(support.supplierStatementPdf(supplierId, from, to));
  }

  @GetMapping(value = "/aging/suppliers/{supplierId}/pdf", produces = "application/pdf")
  @PreAuthorize(SensitiveDisclosurePolicyOwner.ADMIN_ONLY)
  public ResponseEntity<byte[]> supplierAgingPdf(
      @PathVariable Long supplierId,
      @RequestParam(required = false) String asOf,
      @RequestParam(required = false) String buckets) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=supplier-aging.pdf")
        .body(support.supplierAgingPdf(supplierId, asOf, buckets));
  }

  @GetMapping("/accounts/{accountId}/balance/as-of")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<BigDecimal>> getBalanceAsOf(
      @PathVariable Long accountId, @RequestParam String date) {
    return ResponseEntity.ok(
        ApiResponse.success("Balance as of " + date, support.getBalanceAsOf(accountId, date)));
  }

  @GetMapping("/trial-balance/as-of")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<TemporalBalanceService.TrialBalanceSnapshot>>
      getTrialBalanceAsOf(@RequestParam String date) {
    return ResponseEntity.ok(
        ApiResponse.success("Trial balance as of " + date, support.getTrialBalanceAsOf(date)));
  }

  @GetMapping("/accounts/{accountId}/activity")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<
          ApiResponse<StatementReportControllerSupport.AccountActivitySummaryResponse>>
      getAccountActivity(
          @PathVariable Long accountId,
          @RequestParam(required = false) String startDate,
          @RequestParam(required = false) String endDate,
          @RequestParam(required = false) String from,
          @RequestParam(required = false) String to) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Account activity report",
            support.getAccountActivity(accountId, startDate, endDate, from, to)));
  }

  @GetMapping("/date-context")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountingDateContext() {
    return ResponseEntity.ok(
        ApiResponse.success("Accounting date context", support.getAccountingDateContext()));
  }

  @GetMapping("/accounts/{accountId}/balance/compare")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<StatementReportControllerSupport.BalanceComparisonResponse>>
      compareBalances(
          @PathVariable Long accountId,
          @RequestParam(required = false) String from,
          @RequestParam(required = false) String to,
          @RequestParam(required = false) String date1,
          @RequestParam(required = false) String date2) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Balance comparison", support.compareBalances(accountId, from, to, date1, date2)));
  }
}
