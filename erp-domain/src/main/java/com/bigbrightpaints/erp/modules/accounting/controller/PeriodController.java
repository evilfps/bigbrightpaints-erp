package com.bigbrightpaints.erp.modules.accounting.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestActionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PeriodCloseRequestDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class PeriodController {

  private final AccountingPeriodService accountingPeriodService;

  public PeriodController(AccountingPeriodService accountingPeriodService) {
    this.accountingPeriodService = accountingPeriodService;
  }

  @GetMapping("/periods")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<AccountingPeriodDto>>> listPeriods() {
    return ResponseEntity.ok(ApiResponse.success(accountingPeriodService.listPeriods()));
  }

  @PostMapping("/periods")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<AccountingPeriodDto>> createOrUpdatePeriod(
      @Valid @RequestBody AccountingPeriodRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Accounting period saved", accountingPeriodService.createOrUpdatePeriod(request)));
  }

  @PutMapping("/periods/{periodId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<AccountingPeriodDto>> updatePeriod(
      @PathVariable Long periodId, @Valid @RequestBody AccountingPeriodRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Accounting period updated", accountingPeriodService.updatePeriod(periodId, request)));
  }

  @PostMapping("/periods/{periodId}/request-close")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PeriodCloseRequestDto>> requestPeriodClose(
      @PathVariable Long periodId,
      @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Period close request submitted",
            accountingPeriodService.requestPeriodClose(periodId, request)));
  }

  @PostMapping("/periods/{periodId}/approve-close")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<AccountingPeriodDto>> approvePeriodClose(
      @PathVariable Long periodId,
      @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Accounting period close approved",
            accountingPeriodService.approvePeriodClose(periodId, request)));
  }

  @PostMapping("/periods/{periodId}/reject-close")
  @PreAuthorize("hasAuthority('ROLE_ADMIN')")
  public ResponseEntity<ApiResponse<PeriodCloseRequestDto>> rejectPeriodClose(
      @PathVariable Long periodId,
      @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Accounting period close rejected",
            accountingPeriodService.rejectPeriodClose(periodId, request)));
  }

  @PostMapping("/periods/{periodId}/reopen")
  @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
  public ResponseEntity<ApiResponse<AccountingPeriodDto>> reopenPeriod(
      @PathVariable Long periodId,
      @RequestBody(required = false) AccountingPeriodReopenRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Accounting period reopened", accountingPeriodService.reopenPeriod(periodId, request)));
  }

  @GetMapping("/month-end/checklist")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<MonthEndChecklistDto>> checklist(
      @RequestParam(required = false) Long periodId) {
    return ResponseEntity.ok(
        ApiResponse.success(accountingPeriodService.getMonthEndChecklist(periodId)));
  }

  @PostMapping("/month-end/checklist/{periodId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<MonthEndChecklistDto>> updateChecklist(
      @PathVariable Long periodId, @RequestBody MonthEndChecklistUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Checklist updated",
            accountingPeriodService.updateMonthEndChecklist(periodId, request)));
  }
}
