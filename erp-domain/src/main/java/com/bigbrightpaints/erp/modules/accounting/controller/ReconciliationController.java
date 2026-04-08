package com.bigbrightpaints.erp.modules.accounting.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCompletionRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionCreateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionDetailDto;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionItemsUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSessionSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyListResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.ReconciliationDiscrepancyResolveRequest;
import com.bigbrightpaints.erp.modules.accounting.service.BankReconciliationSessionService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class ReconciliationController {

  private final ReconciliationService reconciliationService;
  private final BankReconciliationSessionService bankReconciliationSessionService;

  public ReconciliationController(
      ReconciliationService reconciliationService,
      BankReconciliationSessionService bankReconciliationSessionService) {
    this.reconciliationService = reconciliationService;
    this.bankReconciliationSessionService = bankReconciliationSessionService;
  }

  @PostMapping("/reconciliation/bank/sessions")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<BankReconciliationSessionSummaryDto>>
      startBankReconciliationSession(
          @Valid @RequestBody BankReconciliationSessionCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.success(
                "Bank reconciliation session started",
                bankReconciliationSessionService.startSession(request)));
  }

  @PutMapping("/reconciliation/bank/sessions/{sessionId}/items")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>>
      updateBankReconciliationSessionItems(
          @PathVariable Long sessionId,
          @RequestBody BankReconciliationSessionItemsUpdateRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Bank reconciliation session updated",
            bankReconciliationSessionService.updateItems(sessionId, request)));
  }

  @PostMapping("/reconciliation/bank/sessions/{sessionId}/complete")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>>
      completeBankReconciliationSession(
          @PathVariable Long sessionId,
          @RequestBody(required = false) BankReconciliationSessionCompletionRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Bank reconciliation session completed",
            bankReconciliationSessionService.completeSession(sessionId, request)));
  }

  @GetMapping("/reconciliation/bank/sessions")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PageResponse<BankReconciliationSessionSummaryDto>>>
      listBankReconciliationSessions(
          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(
        ApiResponse.success(bankReconciliationSessionService.listSessions(page, size)));
  }

  @GetMapping("/reconciliation/bank/sessions/{sessionId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>>
      getBankReconciliationSession(@PathVariable Long sessionId) {
    return ResponseEntity.ok(
        ApiResponse.success(bankReconciliationSessionService.getSessionDetail(sessionId)));
  }

  @GetMapping("/reconciliation/subledger")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<ReconciliationService.SubledgerReconciliationReport>>
      reconcileSubledger() {
    return ResponseEntity.ok(
        ApiResponse.success(reconciliationService.reconcileSubledgerBalances()));
  }

  @GetMapping("/reconciliation/discrepancies")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<ReconciliationDiscrepancyListResponse>>
      listReconciliationDiscrepancies(
          @RequestParam(required = false) String status,
          @RequestParam(required = false) String type) {
    return ResponseEntity.ok(
        ApiResponse.success(
            reconciliationService.listDiscrepancies(
                parseDiscrepancyStatus(status), parseDiscrepancyType(type))));
  }

  @PostMapping("/reconciliation/discrepancies/{discrepancyId}/resolve")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<ReconciliationDiscrepancyDto>> resolveReconciliationDiscrepancy(
      @PathVariable Long discrepancyId,
      @Valid @RequestBody ReconciliationDiscrepancyResolveRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Reconciliation discrepancy resolved",
            reconciliationService.resolveDiscrepancy(discrepancyId, request)));
  }

  @GetMapping("/reconciliation/inter-company")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<ReconciliationService.InterCompanyReconciliationReport>>
      reconcileInterCompany(
          @RequestParam(value = "companyA", required = false) Long companyA,
          @RequestParam(value = "companyB", required = false) Long companyB) {
    return ResponseEntity.ok(
        ApiResponse.success(reconciliationService.interCompanyReconcile(companyA, companyB)));
  }

  private ReconciliationDiscrepancyStatus parseDiscrepancyStatus(String rawStatus) {
    if (!StringUtils.hasText(rawStatus)) {
      return null;
    }
    try {
      return ReconciliationDiscrepancyStatus.valueOf(rawStatus.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Invalid reconciliation discrepancy status: " + rawStatus,
          ex);
    }
  }

  private ReconciliationDiscrepancyType parseDiscrepancyType(String rawType) {
    if (!StringUtils.hasText(rawType)) {
      return null;
    }
    try {
      return ReconciliationDiscrepancyType.valueOf(rawType.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Invalid reconciliation discrepancy type: " + rawType,
          ex);
    }
  }
}
