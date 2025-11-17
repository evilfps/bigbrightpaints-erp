package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountingController {

    private final AccountingService accountingService;
    private final SalesReturnService salesReturnService;
    private final AccountingPeriodService accountingPeriodService;
    private final ReconciliationService reconciliationService;

    public AccountingController(AccountingService accountingService,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService) {
        this.accountingService = accountingService;
        this.salesReturnService = salesReturnService;
        this.accountingPeriodService = accountingPeriodService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/accounts")
    public ResponseEntity<ApiResponse<List<AccountDto>>> accounts() {
        return ResponseEntity.ok(ApiResponse.success(accountingService.listAccounts()));
    }

    @PostMapping("/accounts")
    public ResponseEntity<ApiResponse<AccountDto>> createAccount(@Valid @RequestBody AccountRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Account created", accountingService.createAccount(request)));
    }

    @GetMapping("/journal-entries")
    public ResponseEntity<ApiResponse<List<JournalEntryDto>>> journalEntries(@RequestParam(required = false) Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.listJournalEntries(dealerId)));
    }

    @PostMapping("/journal-entries")
    public ResponseEntity<ApiResponse<JournalEntryDto>> createJournalEntry(@Valid @RequestBody JournalEntryRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Journal entry posted", accountingService.createJournalEntry(request)));
    }

    @PostMapping("/journal-entries/{entryId}/reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> reverseJournalEntry(@PathVariable Long entryId,
                                                                            @RequestBody(required = false) JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Journal entry corrected", accountingService.reverseJournalEntry(entryId, request)));
    }

    @PostMapping("/receipts/dealer")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordDealerReceipt(@Valid @RequestBody DealerReceiptRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Receipt recorded", accountingService.recordDealerReceipt(request)));
    }

    @PostMapping("/payroll/payments")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordPayrollPayment(@Valid @RequestBody PayrollPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll payment recorded", accountingService.recordPayrollPayment(request)));
    }

    @PostMapping("/suppliers/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordSupplierPayment(@Valid @RequestBody SupplierPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Supplier payment recorded", accountingService.recordSupplierPayment(request)));
    }

    @PostMapping("/sales/returns")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordSalesReturn(@Valid @RequestBody SalesReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit note posted", salesReturnService.processReturn(request)));
    }

    @GetMapping("/periods")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<AccountingPeriodDto>>> listPeriods() {
        return ResponseEntity.ok(ApiResponse.success(accountingPeriodService.listPeriods()));
    }

    @PostMapping("/periods/{periodId}/close")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> closePeriod(@PathVariable Long periodId,
                                                                        @RequestBody(required = false) AccountingPeriodCloseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting period closed", accountingPeriodService.closePeriod(periodId, request)));
    }

    @PostMapping("/bank-reconciliation")
    public ResponseEntity<ApiResponse<BankReconciliationSummaryDto>> reconcileBank(@Valid @RequestBody BankReconciliationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(reconciliationService.reconcileBank(request)));
    }

    @PostMapping("/inventory/physical-count")
    public ResponseEntity<ApiResponse<InventoryCountResponse>> recordInventoryCount(@Valid @RequestBody InventoryCountRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Inventory count recorded", reconciliationService.recordInventoryCount(request)));
    }

    @GetMapping("/month-end/checklist")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<MonthEndChecklistDto>> checklist(@RequestParam(required = false) Long periodId) {
        return ResponseEntity.ok(ApiResponse.success(accountingPeriodService.getMonthEndChecklist(periodId)));
    }

    @PostMapping("/month-end/checklist/{periodId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<MonthEndChecklistDto>> updateChecklist(@PathVariable Long periodId,
                                                                             @RequestBody MonthEndChecklistUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Checklist updated", accountingPeriodService.updateMonthEndChecklist(periodId, request)));
    }
}
