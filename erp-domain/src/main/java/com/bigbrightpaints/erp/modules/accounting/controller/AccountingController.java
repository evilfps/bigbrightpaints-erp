package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.util.StringUtils;

import java.util.List;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountingController {

    private final AccountingService accountingService;
    private final SalesReturnService salesReturnService;
    private final AccountingPeriodService accountingPeriodService;
    private final ReconciliationService reconciliationService;
    private final StatementService statementService;
    private final TaxService taxService;
    private final TemporalBalanceService temporalBalanceService;
    private final AccountHierarchyService accountHierarchyService;
    private final AgingReportService agingReportService;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;

    public AccountingController(AccountingService accountingService,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService) {
        this.accountingService = accountingService;
        this.salesReturnService = salesReturnService;
        this.accountingPeriodService = accountingPeriodService;
        this.reconciliationService = reconciliationService;
        this.statementService = statementService;
        this.taxService = taxService;
        this.temporalBalanceService = temporalBalanceService;
        this.accountHierarchyService = accountHierarchyService;
        this.agingReportService = agingReportService;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
    }

    /**
     * Translate business exceptions to 400 for API clients (prevents 500 on validation/state errors).
     */
    @ExceptionHandler(ApplicationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleApplicationException(ApplicationException ex) {
        return ApiResponse.failure(ex.getUserMessage(), null);
    }

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<AccountDto>>> accounts() {
        return ResponseEntity.ok(ApiResponse.success(accountingService.listAccounts()));
    }

    @GetMapping("/default-accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CompanyDefaultAccountsResponse>> defaultAccounts() {
        CompanyDefaultAccountsService.DefaultAccounts defaults = companyDefaultAccountsService.getDefaults();
        return ResponseEntity.ok(ApiResponse.success(
                new CompanyDefaultAccountsResponse(
                        defaults.inventoryAccountId(),
                        defaults.cogsAccountId(),
                        defaults.revenueAccountId(),
                        defaults.discountAccountId(),
                        defaults.taxAccountId()
                )));
    }

    @PutMapping("/default-accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<CompanyDefaultAccountsResponse>> updateDefaultAccounts(
            @Valid @RequestBody CompanyDefaultAccountsRequest request) {
        CompanyDefaultAccountsService.DefaultAccounts defaults = companyDefaultAccountsService.updateDefaults(
                request.inventoryAccountId(),
                request.cogsAccountId(),
                request.revenueAccountId(),
                request.discountAccountId(),
                request.taxAccountId()
        );
        return ResponseEntity.ok(ApiResponse.success(
                "Default accounts updated",
                new CompanyDefaultAccountsResponse(
                        defaults.inventoryAccountId(),
                        defaults.cogsAccountId(),
                        defaults.revenueAccountId(),
                        defaults.discountAccountId(),
                        defaults.taxAccountId()
                )));
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountDto>> createAccount(@Valid @RequestBody AccountRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Account created", accountingService.createAccount(request)));
    }

    @GetMapping("/journal-entries")
    @Timed(value = "erp.accounting.journal_entries.list", description = "List journal entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<JournalEntryDto>>> journalEntries(@RequestParam(required = false) Long dealerId,
                                                                             @RequestParam(required = false) Long supplierId,
                                                                             @RequestParam(defaultValue = "0") int page,
                                                                             @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(ApiResponse.success(accountingService.listJournalEntries(dealerId, supplierId, page, size)));
    }

    @PostMapping("/journal-entries")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> createJournalEntry(@Valid @RequestBody JournalEntryRequest request) {
        String idempotencyKey = request != null ? request.referenceNumber() : null;
        if (AccountingFacade.isReservedReferenceNamespace(idempotencyKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Reference number is reserved for system journals; use a client idempotency key without system prefixes")
                    .withDetail("referenceNumber", idempotencyKey);
        }
        JournalEntryRequest sanitized = request == null ? null : new JournalEntryRequest(
                null,
                request.entryDate(),
                request.memo(),
                request.dealerId(),
                request.supplierId(),
                request.adminOverride(),
                request.lines(),
                request.currency(),
                request.fxRate()
        );
        return ResponseEntity.ok(ApiResponse.success("Journal entry posted",
                accountingService.createManualJournalEntry(sanitized, idempotencyKey)));
    }

    @PostMapping("/journal-entries/{entryId}/reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> reverseJournalEntry(@PathVariable Long entryId,
                                                                            @RequestBody(required = false) JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Journal entry corrected", accountingService.reverseJournalEntry(entryId, request)));
    }
    
    @PostMapping("/journal-entries/{entryId}/cascade-reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<java.util.List<JournalEntryDto>>> cascadeReverseJournalEntry(
            @PathVariable Long entryId,
            @RequestBody JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Journal entries reversed with related entries", 
                accountingService.cascadeReverseRelatedEntries(entryId, request)));
    }

    @PostMapping("/receipts/dealer")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordDealerReceipt(
            @Valid @RequestBody DealerReceiptRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DealerReceiptRequest resolved = applyIdempotencyKey(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Receipt recorded", accountingService.recordDealerReceipt(resolved)));
    }

    @PostMapping("/receipts/dealer/hybrid")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordDealerHybridReceipt(
            @Valid @RequestBody DealerReceiptSplitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DealerReceiptSplitRequest resolved = applyIdempotencyKey(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Receipt recorded", accountingService.recordDealerReceiptSplit(resolved)));
    }

    @PostMapping("/settlements/dealers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> settleDealer(@Valid @RequestBody DealerSettlementRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Settlement recorded", accountingService.settleDealerInvoices(request)));
    }

    @PostMapping("/payroll/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordPayrollPayment(@Valid @RequestBody PayrollPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll payment recorded", accountingService.recordPayrollPayment(request)));
    }

    @PostMapping("/suppliers/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordSupplierPayment(
            @Valid @RequestBody SupplierPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        SupplierPaymentRequest resolved = applyIdempotencyKey(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Supplier payment recorded", accountingService.recordSupplierPayment(resolved)));
    }

    @PostMapping("/settlements/suppliers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> settleSupplier(
            @Valid @RequestBody SupplierSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        SupplierSettlementRequest resolved = applyIdempotencyKey(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Settlement recorded", accountingService.settleSupplierInvoices(resolved)));
    }

    @PostMapping("/credit-notes")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postCreditNote(@Valid @RequestBody CreditNoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit note posted", accountingService.postCreditNote(request)));
    }

    @PostMapping("/debit-notes")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postDebitNote(@Valid @RequestBody DebitNoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Debit note posted", accountingService.postDebitNote(request)));
    }

    @PostMapping("/accruals")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postAccrual(@Valid @RequestBody AccrualRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accrual posted", accountingService.postAccrual(request)));
    }

    private DealerReceiptRequest applyIdempotencyKey(DealerReceiptRequest request, String idempotencyKey) {
        if (request == null) {
            return request;
        }
        if (StringUtils.hasText(request.idempotencyKey()) || !StringUtils.hasText(idempotencyKey)) {
            return request;
        }
        return new DealerReceiptRequest(
                request.dealerId(),
                request.cashAccountId(),
                request.amount(),
                request.referenceNumber(),
                request.memo(),
                idempotencyKey,
                request.allocations()
        );
    }

    private DealerReceiptSplitRequest applyIdempotencyKey(DealerReceiptSplitRequest request, String idempotencyKey) {
        if (request == null) {
            return request;
        }
        if (StringUtils.hasText(request.idempotencyKey()) || !StringUtils.hasText(idempotencyKey)) {
            return request;
        }
        return new DealerReceiptSplitRequest(
                request.dealerId(),
                request.incomingLines(),
                request.referenceNumber(),
                request.memo(),
                idempotencyKey
        );
    }

    private SupplierPaymentRequest applyIdempotencyKey(SupplierPaymentRequest request, String idempotencyKey) {
        if (request == null) {
            return request;
        }
        if (StringUtils.hasText(request.idempotencyKey()) || !StringUtils.hasText(idempotencyKey)) {
            return request;
        }
        return new SupplierPaymentRequest(
                request.supplierId(),
                request.cashAccountId(),
                request.amount(),
                request.referenceNumber(),
                request.memo(),
                idempotencyKey,
                request.allocations()
        );
    }

    private SupplierSettlementRequest applyIdempotencyKey(SupplierSettlementRequest request, String idempotencyKey) {
        if (request == null) {
            return request;
        }
        if (StringUtils.hasText(request.idempotencyKey()) || !StringUtils.hasText(idempotencyKey)) {
            return request;
        }
        return new SupplierSettlementRequest(
                request.supplierId(),
                request.cashAccountId(),
                request.discountAccountId(),
                request.writeOffAccountId(),
                request.fxGainAccountId(),
                request.fxLossAccountId(),
                request.settlementDate(),
                request.referenceNumber(),
                request.memo(),
                idempotencyKey,
                request.adminOverride(),
                request.allocations()
        );
    }

    @GetMapping("/gst/return")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<GstReturnDto>> generateGstReturn(@RequestParam(required = false) String period) {
        YearMonth target = period != null && !period.isBlank() ? YearMonth.parse(period) : null;
        return ResponseEntity.ok(ApiResponse.success(taxService.generateGstReturn(target)));
    }

    @PostMapping("/bad-debts/write-off")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> writeOffBadDebt(@Valid @RequestBody BadDebtWriteOffRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bad debt written off", accountingService.writeOffBadDebt(request)));
    }

    @GetMapping("/sales/returns")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<JournalEntryDto>>> listSalesReturns() {
        // Returns are stored as credit note journal entries - filter by reference prefix
        return ResponseEntity.ok(ApiResponse.success("Sales returns", 
            accountingService.listJournalEntriesByReferencePrefix("CN-")));
    }

    @PostMapping("/sales/returns")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
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

    @PostMapping("/periods/{periodId}/lock")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> lockPeriod(@PathVariable Long periodId,
                                                                       @RequestBody(required = false) AccountingPeriodLockRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting period locked", accountingPeriodService.lockPeriod(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/reopen")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> reopenPeriod(@PathVariable Long periodId,
                                                                         @RequestBody(required = false) AccountingPeriodReopenRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting period reopened", accountingPeriodService.reopenPeriod(periodId, request)));
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

    /* Statements & Aging */
    @GetMapping("/statements/dealers/{dealerId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerStatementResponse>> dealerStatement(@PathVariable Long dealerId,
                                                                                 @RequestParam(required = false) String from,
                                                                                 @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
                statementService.dealerStatement(dealerId,
                        from != null ? java.time.LocalDate.parse(from) : null,
                        to != null ? java.time.LocalDate.parse(to) : null)));
    }

    @GetMapping("/statements/suppliers/{supplierId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerStatementResponse>> supplierStatement(@PathVariable Long supplierId,
                                                                                   @RequestParam(required = false) String from,
                                                                                   @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
                statementService.supplierStatement(supplierId,
                        from != null ? java.time.LocalDate.parse(from) : null,
                        to != null ? java.time.LocalDate.parse(to) : null)));
    }

    @GetMapping("/aging/dealers/{dealerId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingSummaryResponse>> dealerAging(@PathVariable Long dealerId,
                                                                         @RequestParam(required = false) String asOf,
                                                                         @RequestParam(required = false) String buckets) {
        return ResponseEntity.ok(ApiResponse.success(
                statementService.dealerAging(dealerId,
                        asOf != null ? java.time.LocalDate.parse(asOf) : null,
                        buckets)));
    }

    @GetMapping("/aging/suppliers/{supplierId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingSummaryResponse>> supplierAging(@PathVariable Long supplierId,
                                                                           @RequestParam(required = false) String asOf,
                                                                           @RequestParam(required = false) String buckets) {
        return ResponseEntity.ok(ApiResponse.success(
                statementService.supplierAging(supplierId,
                        asOf != null ? java.time.LocalDate.parse(asOf) : null,
                        buckets)));
    }

    @GetMapping(value = "/statements/dealers/{dealerId}/pdf", produces = "application/pdf")
    @Operation(summary = "Download dealer statement PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "PDF document",
            content = @Content(
                    mediaType = "application/pdf",
                    schema = @Schema(type = "string", format = "binary")
            )
    )
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<byte[]> dealerStatementPdf(@PathVariable Long dealerId,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        byte[] pdf = statementService.dealerStatementPdf(dealerId,
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=dealer-statement.pdf")
                .body(pdf);
    }

    @GetMapping(value = "/statements/suppliers/{supplierId}/pdf", produces = "application/pdf")
    @Operation(summary = "Download supplier statement PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "PDF document",
            content = @Content(
                    mediaType = "application/pdf",
                    schema = @Schema(type = "string", format = "binary")
            )
    )
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<byte[]> supplierStatementPdf(@PathVariable Long supplierId,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        byte[] pdf = statementService.supplierStatementPdf(supplierId,
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=supplier-statement.pdf")
                .body(pdf);
    }

    @GetMapping(value = "/aging/dealers/{dealerId}/pdf", produces = "application/pdf")
    @Operation(summary = "Download dealer aging PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "PDF document",
            content = @Content(
                    mediaType = "application/pdf",
                    schema = @Schema(type = "string", format = "binary")
            )
    )
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<byte[]> dealerAgingPdf(@PathVariable Long dealerId,
                                                 @RequestParam(required = false) String asOf,
                                                 @RequestParam(required = false) String buckets) {
        byte[] pdf = statementService.dealerAgingPdf(dealerId,
                asOf != null ? java.time.LocalDate.parse(asOf) : null,
                buckets);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=dealer-aging.pdf")
                .body(pdf);
    }

    @GetMapping(value = "/aging/suppliers/{supplierId}/pdf", produces = "application/pdf")
    @Operation(summary = "Download supplier aging PDF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "PDF document",
            content = @Content(
                    mediaType = "application/pdf",
                    schema = @Schema(type = "string", format = "binary")
            )
    )
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<byte[]> supplierAgingPdf(@PathVariable Long supplierId,
                                                   @RequestParam(required = false) String asOf,
                                                   @RequestParam(required = false) String buckets) {
        byte[] pdf = statementService.supplierAgingPdf(supplierId,
                asOf != null ? java.time.LocalDate.parse(asOf) : null,
                buckets);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=supplier-aging.pdf")
                .body(pdf);
    }

    /* Inventory valuation and WIP */
    @PostMapping("/inventory/landed-cost")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordLandedCost(@Valid @RequestBody LandedCostRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Landed cost posted", accountingService.recordLandedCost(request)));
    }

    @PostMapping("/inventory/revaluation")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> revalueInventory(@Valid @RequestBody InventoryRevaluationRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Inventory revaluation posted", accountingService.revalueInventory(request)));
    }

    @PostMapping("/inventory/wip-adjustment")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> adjustWip(@Valid @RequestBody WipAdjustmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("WIP adjustment posted", accountingService.adjustWip(request)));
    }

    /* Audit digest */
    @GetMapping("/audit/digest")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AuditDigestResponse>> auditDigest(@RequestParam(required = false) String from,
                                                                        @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
                accountingService.auditDigest(
                        from != null ? java.time.LocalDate.parse(from) : null,
                        to != null ? java.time.LocalDate.parse(to) : null)));
    }

    @GetMapping(value = "/audit/digest.csv", produces = "text/csv")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<String> auditDigestCsv(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        String csv = accountingService.auditDigestCsv(
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        return ResponseEntity.ok(csv);
    }

    // ==================== TEMPORAL QUERIES (Event Sourcing) ====================

    @GetMapping("/accounts/{accountId}/balance/as-of")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<java.math.BigDecimal>> getBalanceAsOf(
            @PathVariable Long accountId,
            @RequestParam String date) {
        java.time.LocalDate asOfDate = java.time.LocalDate.parse(date);
        return ResponseEntity.ok(ApiResponse.success(
                "Balance as of " + date,
                temporalBalanceService.getBalanceAsOfDate(accountId, asOfDate)));
    }

    @GetMapping("/trial-balance/as-of")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<TemporalBalanceService.TrialBalanceSnapshot>> getTrialBalanceAsOf(
            @RequestParam String date) {
        java.time.LocalDate asOfDate = java.time.LocalDate.parse(date);
        return ResponseEntity.ok(ApiResponse.success(
                "Trial balance as of " + date,
                temporalBalanceService.getTrialBalanceAsOf(asOfDate)));
    }

    @GetMapping("/accounts/{accountId}/activity")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<TemporalBalanceService.AccountActivityReport>> getAccountActivity(
            @PathVariable Long accountId,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(ApiResponse.success(
                "Account activity report",
                temporalBalanceService.getAccountActivity(
                        accountId,
                        java.time.LocalDate.parse(startDate),
                        java.time.LocalDate.parse(endDate))));
    }

    @GetMapping("/accounts/{accountId}/balance/compare")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<TemporalBalanceService.BalanceComparison>> compareBalances(
            @PathVariable Long accountId,
            @RequestParam String date1,
            @RequestParam String date2) {
        return ResponseEntity.ok(ApiResponse.success(
                "Balance comparison",
                temporalBalanceService.compareBalances(
                        accountId,
                        java.time.LocalDate.parse(date1),
                        java.time.LocalDate.parse(date2))));
    }

    // ==================== ACCOUNT HIERARCHY ====================

    @GetMapping("/accounts/tree")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<java.util.List<AccountHierarchyService.AccountNode>>> getChartOfAccountsTree() {
        return ResponseEntity.ok(ApiResponse.success(
                "Chart of accounts hierarchy",
                accountHierarchyService.getChartOfAccountsTree()));
    }

    @GetMapping("/accounts/tree/{type}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<java.util.List<AccountHierarchyService.AccountNode>>> getAccountTreeByType(
            @PathVariable String type) {
        AccountType accountType = AccountType.valueOf(type.toUpperCase());
        return ResponseEntity.ok(ApiResponse.success(
                "Account hierarchy for " + type,
                accountHierarchyService.getTreeByType(accountType)));
    }

    @GetMapping("/reports/balance-sheet/hierarchy")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountHierarchyService.BalanceSheetHierarchy>> getBalanceSheetHierarchy() {
        return ResponseEntity.ok(ApiResponse.success(
                "Hierarchical balance sheet",
                accountHierarchyService.getBalanceSheetHierarchy()));
    }

    @GetMapping("/reports/income-statement/hierarchy")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountHierarchyService.IncomeStatementHierarchy>> getIncomeStatementHierarchy() {
        return ResponseEntity.ok(ApiResponse.success(
                "Hierarchical income statement",
                accountHierarchyService.getIncomeStatementHierarchy()));
    }

    // ==================== AGING REPORTS (Sub-Ledger) ====================

    @GetMapping("/reports/aging/receivables")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingReportService.AgedReceivablesReport>> getAgedReceivables(
            @RequestParam(required = false) String asOfDate) {
        if (asOfDate != null) {
            return ResponseEntity.ok(ApiResponse.success(
                    "Aged receivables report",
                    agingReportService.getAgedReceivablesReport(java.time.LocalDate.parse(asOfDate))));
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Aged receivables report",
                agingReportService.getAgedReceivablesReport()));
    }

    @GetMapping("/reports/aging/dealer/{dealerId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingReportService.DealerAgingDetail>> getDealerAging(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Dealer aging summary",
                agingReportService.getDealerAging(dealerId)));
    }

    @GetMapping("/reports/aging/dealer/{dealerId}/detailed")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingReportService.DealerAgingDetailedReport>> getDealerAgingDetailed(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Dealer aging detail with invoices",
                agingReportService.getDealerAgingDetailed(dealerId)));
    }

    @GetMapping("/reports/dso/dealer/{dealerId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AgingReportService.DSOReport>> getDealerDSO(
            @PathVariable Long dealerId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Days Sales Outstanding report",
                agingReportService.getDealerDSO(dealerId)));
    }
}
