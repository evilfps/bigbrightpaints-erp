package com.bigbrightpaints.erp.modules.accounting.controller;

import java.time.LocalDate;
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

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/accounting")
public class JournalController {

  private final AccountingService accountingService;
  private final JournalEntryService journalEntryService;
  private final CreditDebitNoteService creditDebitNoteService;
  private final AccountingFacade accountingFacade;

  public JournalController(
      AccountingService accountingService,
      JournalEntryService journalEntryService,
      CreditDebitNoteService creditDebitNoteService,
      AccountingFacade accountingFacade) {
    this.accountingService = accountingService;
    this.journalEntryService = journalEntryService;
    this.creditDebitNoteService = creditDebitNoteService;
    this.accountingFacade = accountingFacade;
  }

  @GetMapping("/journal-entries")
  @Timed(value = "erp.accounting.journal_entries.list", description = "List journal entries")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<List<JournalEntryDto>>> journalEntries(
      @RequestParam(required = false) Long dealerId,
      @RequestParam(required = false) Long supplierId,
      @RequestParam(required = false, name = "source") String source,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    return ResponseEntity.ok(
        ApiResponse.success(
            accountingService.listJournalEntries(dealerId, supplierId, page, size, source)));
  }

  @GetMapping("/journals")
  @Timed(value = "erp.accounting.journals.list", description = "List journals with filters")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<PageResponse<JournalListItemDto>>> listJournals(
      @RequestParam(required = false) LocalDate fromDate,
      @RequestParam(required = false) LocalDate toDate,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String sourceModule,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return ResponseEntity.ok(
        ApiResponse.success(
            accountingService.listJournals(fromDate, toDate, type, sourceModule, page, size)));
  }

  @PostMapping("/journal-entries")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> createJournalEntry(
      @Valid @RequestBody JournalEntryRequest request) {
    String idempotencyKey = request != null ? request.referenceNumber() : null;
    if (AccountingFacade.isReservedReferenceNamespace(idempotencyKey)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Reference number is reserved for system journals; use a client idempotency key"
                  + " without system prefixes")
          .withDetail("referenceNumber", idempotencyKey);
    }
    return ResponseEntity.ok(
        ApiResponse.success(
            "Journal entry posted",
            accountingService.createManualJournalEntry(
                sanitizeManualJournalRequest(request), idempotencyKey)));
  }

  @PostMapping("/journal-entries/{entryId}/reverse")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> reverseJournalEntry(
      @PathVariable Long entryId,
      @RequestBody(required = false) JournalEntryReversalRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Journal entry corrected", journalEntryService.reverseJournalEntry(entryId, request)));
  }

  @PostMapping("/payroll/payments")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> recordPayrollPayment(
      @Valid @RequestBody PayrollPaymentRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Payroll payment recorded", accountingFacade.recordPayrollPayment(request)));
  }

  @PostMapping("/credit-notes")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> postCreditNote(
      @Valid @RequestBody CreditNoteRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Credit note posted", creditDebitNoteService.postCreditNote(request)));
  }

  @PostMapping("/debit-notes")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> postDebitNote(
      @Valid @RequestBody DebitNoteRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Debit note posted", creditDebitNoteService.postDebitNote(request)));
  }

  @PostMapping("/accruals")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> postAccrual(
      @Valid @RequestBody AccrualRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success("Accrual posted", creditDebitNoteService.postAccrual(request)));
  }

  @PostMapping("/bad-debts/write-off")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
  public ResponseEntity<ApiResponse<JournalEntryDto>> writeOffBadDebt(
      @Valid @RequestBody BadDebtWriteOffRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(
            "Bad debt written off", creditDebitNoteService.writeOffBadDebt(request)));
  }

  private JournalEntryRequest sanitizeManualJournalRequest(JournalEntryRequest request) {
    if (request == null) {
      return null;
    }
    return new JournalEntryRequest(
        null,
        request.entryDate(),
        request.memo(),
        request.dealerId(),
        request.supplierId(),
        request.adminOverride(),
        request.lines(),
        request.currency(),
        request.fxRate(),
        null,
        null,
        null,
        request.attachmentReferences());
  }
}
