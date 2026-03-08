package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.accounting.service.DealerReceiptService;
import com.bigbrightpaints.erp.modules.accounting.service.SettlementService;
import com.bigbrightpaints.erp.modules.accounting.service.CreditDebitNoteService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditService;
import com.bigbrightpaints.erp.modules.accounting.service.InventoryAccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReconciliationService;
import com.bigbrightpaints.erp.modules.accounting.service.TaxService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountHierarchyService;
import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingAuditTrailService;
import com.bigbrightpaints.erp.modules.accounting.service.BankReconciliationSessionService;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.ReconciliationDiscrepancyType;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import io.micrometer.core.annotation.Timed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.time.YearMonth;
import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/accounting")
public class AccountingController {

    private final AccountingService accountingService;
    private final JournalEntryService journalEntryService;
    private final DealerReceiptService dealerReceiptService;
    private final SettlementService settlementService;
    private final CreditDebitNoteService creditDebitNoteService;
    private final AccountingAuditService accountingAuditService;
    private final InventoryAccountingService inventoryAccountingService;
    private final AccountingFacade accountingFacade;
    private final SalesReturnService salesReturnService;
    private final AccountingPeriodService accountingPeriodService;
    private final ReconciliationService reconciliationService;
    private final StatementService statementService;
    private final TaxService taxService;
    private final TemporalBalanceService temporalBalanceService;
    private final AccountHierarchyService accountHierarchyService;
    private final AgingReportService agingReportService;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final AccountingAuditTrailService accountingAuditTrailService;
    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;
    private final BankReconciliationSessionService bankReconciliationSessionService;
    private final AuditService auditService;

    public AccountingController(AccountingService accountingService,
                                JournalEntryService journalEntryService,
                                DealerReceiptService dealerReceiptService,
                                SettlementService settlementService,
                                CreditDebitNoteService creditDebitNoteService,
                                AccountingAuditService accountingAuditService,
                                InventoryAccountingService inventoryAccountingService,
                                AccountingFacade accountingFacade,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                AccountingAuditTrailService accountingAuditTrailService,
                                CompanyContextService companyContextService,
                                CompanyClock companyClock,
                                BankReconciliationSessionService bankReconciliationSessionService) {
        this(accountingService,
                journalEntryService,
                dealerReceiptService,
                settlementService,
                creditDebitNoteService,
                accountingAuditService,
                inventoryAccountingService,
                accountingFacade,
                salesReturnService,
                accountingPeriodService,
                reconciliationService,
                statementService,
                taxService,
                temporalBalanceService,
                accountHierarchyService,
                agingReportService,
                companyDefaultAccountsService,
                accountingAuditTrailService,
                companyContextService,
                companyClock,
                bankReconciliationSessionService,
                null);
    }

    @Autowired
    public AccountingController(AccountingService accountingService,
                                JournalEntryService journalEntryService,
                                DealerReceiptService dealerReceiptService,
                                SettlementService settlementService,
                                CreditDebitNoteService creditDebitNoteService,
                                AccountingAuditService accountingAuditService,
                                InventoryAccountingService inventoryAccountingService,
                                AccountingFacade accountingFacade,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                AccountingAuditTrailService accountingAuditTrailService,
                                CompanyContextService companyContextService,
                                CompanyClock companyClock,
                                BankReconciliationSessionService bankReconciliationSessionService,
                                AuditService auditService) {
        this.accountingService = accountingService;
        this.journalEntryService = journalEntryService;
        this.dealerReceiptService = dealerReceiptService;
        this.settlementService = settlementService;
        this.creditDebitNoteService = creditDebitNoteService;
        this.accountingAuditService = accountingAuditService;
        this.inventoryAccountingService = inventoryAccountingService;
        this.accountingFacade = accountingFacade;
        this.salesReturnService = salesReturnService;
        this.accountingPeriodService = accountingPeriodService;
        this.reconciliationService = reconciliationService;
        this.statementService = statementService;
        this.taxService = taxService;
        this.temporalBalanceService = temporalBalanceService;
        this.accountHierarchyService = accountHierarchyService;
        this.agingReportService = agingReportService;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.accountingAuditTrailService = accountingAuditTrailService;
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
        this.bankReconciliationSessionService = bankReconciliationSessionService;
        this.auditService = auditService;
    }

    public AccountingController(AccountingService accountingService,
                                AccountingFacade accountingFacade,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                AccountingAuditTrailService accountingAuditTrailService,
                                CompanyContextService companyContextService,
                                CompanyClock companyClock) {
        this(accountingService,
                accountingFacade,
                salesReturnService,
                accountingPeriodService,
                reconciliationService,
                statementService,
                taxService,
                temporalBalanceService,
                accountHierarchyService,
                agingReportService,
                companyDefaultAccountsService,
                accountingAuditTrailService,
                companyContextService,
                companyClock,
                null,
                null);
    }

    public AccountingController(AccountingService accountingService,
                                AccountingFacade accountingFacade,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                AccountingAuditTrailService accountingAuditTrailService,
                                CompanyContextService companyContextService,
                                CompanyClock companyClock,
                                AuditService auditService) {
        this(accountingService,
                accountingFacade,
                salesReturnService,
                accountingPeriodService,
                reconciliationService,
                statementService,
                taxService,
                temporalBalanceService,
                accountHierarchyService,
                agingReportService,
                companyDefaultAccountsService,
                accountingAuditTrailService,
                companyContextService,
                companyClock,
                null,
                auditService);
    }

    public AccountingController(AccountingService accountingService,
                                AccountingFacade accountingFacade,
                                SalesReturnService salesReturnService,
                                AccountingPeriodService accountingPeriodService,
                                ReconciliationService reconciliationService,
                                StatementService statementService,
                                TaxService taxService,
                                TemporalBalanceService temporalBalanceService,
                                AccountHierarchyService accountHierarchyService,
                                AgingReportService agingReportService,
                                CompanyDefaultAccountsService companyDefaultAccountsService,
                                AccountingAuditTrailService accountingAuditTrailService,
                                CompanyContextService companyContextService,
                                CompanyClock companyClock,
                                BankReconciliationSessionService bankReconciliationSessionService,
                                AuditService auditService) {
        this(accountingService,
                bridgeJournalEntryService(accountingService),
                bridgeDealerReceiptService(accountingService),
                bridgeSettlementService(accountingService),
                bridgeCreditDebitNoteService(accountingService),
                bridgeAccountingAuditService(accountingService),
                bridgeInventoryAccountingService(accountingService),
                accountingFacade,
                salesReturnService,
                accountingPeriodService,
                reconciliationService,
                statementService,
                taxService,
                temporalBalanceService,
                accountHierarchyService,
                agingReportService,
                companyDefaultAccountsService,
                accountingAuditTrailService,
                companyContextService,
                companyClock,
                bankReconciliationSessionService,
                auditService);
    }

    private static AccountingService requireAccountingService(AccountingService accountingService) {
        if (accountingService == null) {
            throw new IllegalStateException("AccountingService is required for compatibility constructor bridge");
        }
        return accountingService;
    }

    private static JournalEntryService bridgeJournalEntryService(AccountingService accountingService) {
        return new JournalEntryService(null, null) {
            @Override
            public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
                return requireAccountingService(accountingService).listJournalEntries(dealerId, supplierId, page, size);
            }

            @Override
            public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
                return requireAccountingService(accountingService).createManualJournalEntry(request, idempotencyKey);
            }

            @Override
            public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
                return requireAccountingService(accountingService).reverseJournalEntry(entryId, request);
            }

            @Override
            public List<JournalEntryDto> cascadeReverseRelatedEntries(Long primaryEntryId, JournalEntryReversalRequest request) {
                return requireAccountingService(accountingService).cascadeReverseRelatedEntries(primaryEntryId, request);
            }

            @Override
            public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
                return requireAccountingService(accountingService).listJournalEntriesByReferencePrefix(prefix);
            }
        };
    }

    private static DealerReceiptService bridgeDealerReceiptService(AccountingService accountingService) {
        return new DealerReceiptService(null) {
            @Override
            public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
                return requireAccountingService(accountingService).recordDealerReceipt(request);
            }

            @Override
            public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
                return requireAccountingService(accountingService).recordDealerReceiptSplit(request);
            }
        };
    }

    private static SettlementService bridgeSettlementService(AccountingService accountingService) {
        return new SettlementService(null) {
            @Override
            public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
                return requireAccountingService(accountingService).recordSupplierPayment(request);
            }

            @Override
            public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
                return requireAccountingService(accountingService).settleDealerInvoices(request);
            }

            @Override
            public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
                return requireAccountingService(accountingService).settleSupplierInvoices(request);
            }

            @Override
            public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
                return requireAccountingService(accountingService).autoSettleDealer(dealerId, request);
            }

            @Override
            public PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
                return requireAccountingService(accountingService).autoSettleSupplier(supplierId, request);
            }
        };
    }

    private static CreditDebitNoteService bridgeCreditDebitNoteService(AccountingService accountingService) {
        return new CreditDebitNoteService(null) {
            @Override
            public JournalEntryDto postCreditNote(CreditNoteRequest request) {
                return requireAccountingService(accountingService).postCreditNote(request);
            }

            @Override
            public JournalEntryDto postDebitNote(DebitNoteRequest request) {
                return requireAccountingService(accountingService).postDebitNote(request);
            }

            @Override
            public JournalEntryDto postAccrual(AccrualRequest request) {
                return requireAccountingService(accountingService).postAccrual(request);
            }

            @Override
            public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
                return requireAccountingService(accountingService).writeOffBadDebt(request);
            }
        };
    }

    private static AccountingAuditService bridgeAccountingAuditService(AccountingService accountingService) {
        return new AccountingAuditService(null) {
            @Override
            public AuditDigestResponse auditDigest(LocalDate from, LocalDate to) {
                return requireAccountingService(accountingService).auditDigest(from, to);
            }

            @Override
            public String auditDigestCsv(LocalDate from, LocalDate to) {
                return requireAccountingService(accountingService).auditDigestCsv(from, to);
            }
        };
    }

    private static InventoryAccountingService bridgeInventoryAccountingService(AccountingService accountingService) {
        return new InventoryAccountingService(null) {
            @Override
            public JournalEntryDto recordLandedCost(LandedCostRequest request) {
                return requireAccountingService(accountingService).recordLandedCost(request);
            }

            @Override
            public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
                return requireAccountingService(accountingService).revalueInventory(request);
            }

            @Override
            public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
                return requireAccountingService(accountingService).adjustWip(request);
            }
        };
    }

    /**
     * Keep accounting error payloads structured and explicit for UI diagnostics.
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleApplicationException(
            ApplicationException ex,
            HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("code", ex.getErrorCode().getCode());
        errorData.put("message", ex.getUserMessage());
        errorData.put("reason", ex.getUserMessage());
        errorData.put("path", request != null ? request.getRequestURI() : null);
        errorData.put("traceId", traceId);
        Map<String, Object> details = ex.getDetails();
        if (!details.isEmpty()) {
            errorData.put("details", details);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(ex.getUserMessage(), errorData));
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
        return ResponseEntity.ok(ApiResponse.success(journalEntryService.listJournalEntries(dealerId, supplierId, page, size)));
    }

    @GetMapping("/journals")
    @Timed(value = "erp.accounting.journals.list", description = "List journals with filters")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<List<JournalListItemDto>>> listJournals(
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sourceModule) {
        return ResponseEntity.ok(ApiResponse.success(
                accountingService.listJournals(fromDate, toDate, type, sourceModule)));
    }

    @PostMapping("/journals/manual")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> createManualJournal(@Valid @RequestBody ManualJournalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Manual journal entry posted", accountingService.createManualJournal(request)));
    }

    @PostMapping("/journals/{entryId}/reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> reverseJournalEntryByJournalPath(@PathVariable Long entryId,
                                                                                          @RequestBody(required = false) JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Journal entry corrected", accountingService.reverseJournalEntry(entryId, request)));
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
                request.fxRate(),
                null,
                null,
                null,
                request.attachmentReferences()
        );
        return ResponseEntity.ok(ApiResponse.success("Journal entry posted",
                accountingService.createManualJournalEntry(sanitized, idempotencyKey)));
    }

    @PostMapping("/journal-entries/{entryId}/reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> reverseJournalEntry(@PathVariable Long entryId,
                                                                            @RequestBody(required = false) JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Journal entry corrected", journalEntryService.reverseJournalEntry(entryId, request)));
    }
    
    @PostMapping("/journal-entries/{entryId}/cascade-reverse")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<java.util.List<JournalEntryDto>>> cascadeReverseJournalEntry(
            @PathVariable Long entryId,
            @RequestBody JournalEntryReversalRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Journal entries reversed with related entries", 
                journalEntryService.cascadeReverseRelatedEntries(entryId, request)));
    }

    @PostMapping("/receipts/dealer")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordDealerReceipt(
            @Valid @RequestBody DealerReceiptRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        DealerReceiptRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Receipt recorded", dealerReceiptService.recordDealerReceipt(resolved)));
    }

    @PostMapping("/receipts/dealer/hybrid")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordDealerHybridReceipt(
            @Valid @RequestBody DealerReceiptSplitRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        DealerReceiptSplitRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Receipt recorded", dealerReceiptService.recordDealerReceiptSplit(resolved)));
    }

    @PostMapping("/settlements/dealers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> settleDealer(
            @Valid @RequestBody DealerSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        DealerSettlementRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Settlement recorded", settlementService.settleDealerInvoices(resolved)));
    }

    @PostMapping("/dealers/{dealerId}/auto-settle")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> autoSettleDealer(
            @PathVariable Long dealerId,
            @Valid @RequestBody AutoSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        AutoSettlementRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success(
                "Auto-settlement recorded",
                settlementService.autoSettleDealer(dealerId, resolved)));
    }

    @PostMapping("/payroll/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordPayrollPayment(@Valid @RequestBody PayrollPaymentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll payment recorded", accountingFacade.recordPayrollPayment(request)));
    }

    @PostMapping("/suppliers/payments")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordSupplierPayment(
            @Valid @RequestBody SupplierPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        SupplierPaymentRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Supplier payment recorded", settlementService.recordSupplierPayment(resolved)));
    }

    @PostMapping("/settlements/suppliers")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> settleSupplier(
            @Valid @RequestBody SupplierSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        SupplierSettlementRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Settlement recorded", settlementService.settleSupplierInvoices(resolved)));
    }

    @PostMapping("/suppliers/{supplierId}/auto-settle")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PartnerSettlementResponse>> autoSettleSupplier(
            @PathVariable Long supplierId,
            @Valid @RequestBody AutoSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String legacyIdempotencyKey) {
        AutoSettlementRequest resolved = applyIdempotencyKey(request, idempotencyKey, legacyIdempotencyKey);
        return ResponseEntity.ok(ApiResponse.success(
                "Auto-settlement recorded",
                settlementService.autoSettleSupplier(supplierId, resolved)));
    }

    @PostMapping("/credit-notes")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postCreditNote(@Valid @RequestBody CreditNoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Credit note posted", creditDebitNoteService.postCreditNote(request)));
    }

    @PostMapping("/debit-notes")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postDebitNote(@Valid @RequestBody DebitNoteRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Debit note posted", creditDebitNoteService.postDebitNote(request)));
    }

    @PostMapping("/accruals")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> postAccrual(@Valid @RequestBody AccrualRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accrual posted", creditDebitNoteService.postAccrual(request)));
    }

    private DealerReceiptRequest applyIdempotencyKey(DealerReceiptRequest request,
                                                     String idempotencyKeyHeader,
                                                     String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                DealerReceiptRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new DealerReceiptRequest(
                        resolvedRequest.dealerId(),
                        resolvedRequest.cashAccountId(),
                        resolvedRequest.amount(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey,
                        resolvedRequest.allocations()
                ));
    }

    private DealerReceiptSplitRequest applyIdempotencyKey(DealerReceiptSplitRequest request,
                                                          String idempotencyKeyHeader,
                                                          String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                DealerReceiptSplitRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new DealerReceiptSplitRequest(
                        resolvedRequest.dealerId(),
                        resolvedRequest.incomingLines(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey
                ));
    }

    private DealerSettlementRequest applyIdempotencyKey(DealerSettlementRequest request,
                                                        String idempotencyKeyHeader,
                                                        String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                DealerSettlementRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new DealerSettlementRequest(
                        resolvedRequest.dealerId(),
                        resolvedRequest.cashAccountId(),
                        resolvedRequest.discountAccountId(),
                        resolvedRequest.writeOffAccountId(),
                        resolvedRequest.fxGainAccountId(),
                        resolvedRequest.fxLossAccountId(),
                        resolvedRequest.amount(),
                        resolvedRequest.unappliedAmountApplication(),
                        resolvedRequest.settlementDate(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey,
                        resolvedRequest.adminOverride(),
                        resolvedRequest.allocations(),
                        resolvedRequest.payments()
                ));
    }

    private AutoSettlementRequest applyIdempotencyKey(AutoSettlementRequest request,
                                                      String idempotencyKeyHeader,
                                                      String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                AutoSettlementRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new AutoSettlementRequest(
                        resolvedRequest.cashAccountId(),
                        resolvedRequest.amount(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey
                ));
    }

    private SupplierPaymentRequest applyIdempotencyKey(SupplierPaymentRequest request,
                                                       String idempotencyKeyHeader,
                                                       String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                SupplierPaymentRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new SupplierPaymentRequest(
                        resolvedRequest.supplierId(),
                        resolvedRequest.cashAccountId(),
                        resolvedRequest.amount(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey,
                        resolvedRequest.allocations()
                ));
    }

    private SupplierSettlementRequest applyIdempotencyKey(SupplierSettlementRequest request,
                                                          String idempotencyKeyHeader,
                                                          String legacyIdempotencyKeyHeader) {
        return applyHeaderOnlyIdempotencyKey(
                request,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader,
                SupplierSettlementRequest::idempotencyKey,
                (resolvedRequest, resolvedKey) -> new SupplierSettlementRequest(
                        resolvedRequest.supplierId(),
                        resolvedRequest.cashAccountId(),
                        resolvedRequest.discountAccountId(),
                        resolvedRequest.writeOffAccountId(),
                        resolvedRequest.fxGainAccountId(),
                        resolvedRequest.fxLossAccountId(),
                        resolvedRequest.amount(),
                        resolvedRequest.unappliedAmountApplication(),
                        resolvedRequest.settlementDate(),
                        resolvedRequest.referenceNumber(),
                        resolvedRequest.memo(),
                        resolvedKey,
                        resolvedRequest.adminOverride(),
                        resolvedRequest.allocations()
                ));
    }

    private <T> T applyHeaderOnlyIdempotencyKey(T request,
                                                String idempotencyKeyHeader,
                                                String legacyIdempotencyKeyHeader,
                                                Function<T, String> requestIdempotencyKeyExtractor,
                                                BiFunction<T, String, T> requestWithIdempotencyKey) {
        if (request == null) {
            return null;
        }
        String resolvedKey = resolveHeaderOnlyIdempotencyKey(
                requestIdempotencyKeyExtractor.apply(request),
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader);
        if (!StringUtils.hasText(resolvedKey)) {
            return request;
        }
        return requestWithIdempotencyKey.apply(request, resolvedKey);
    }

    private String resolveHeaderOnlyIdempotencyKey(String bodyIdempotencyKey,
                                                   String idempotencyKeyHeader,
                                                   String legacyIdempotencyKeyHeader) {
        String normalizedPrimaryHeader = trimToNull(idempotencyKeyHeader);
        String normalizedLegacyHeader = trimToNull(legacyIdempotencyKeyHeader);
        if (normalizedPrimaryHeader != null
                && normalizedLegacyHeader != null
                && !normalizedPrimaryHeader.equals(normalizedLegacyHeader)) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Idempotency key mismatch between Idempotency-Key and X-Idempotency-Key headers")
                    .withDetail("headerKey", normalizedPrimaryHeader)
                    .withDetail("legacyHeaderKey", normalizedLegacyHeader);
        }
        String resolvedKey = com.bigbrightpaints.erp.core.util.IdempotencyHeaderUtils.resolveBodyOrHeaderKey(
                bodyIdempotencyKey,
                idempotencyKeyHeader,
                legacyIdempotencyKeyHeader);
        if (!StringUtils.hasText(resolvedKey) || StringUtils.hasText(bodyIdempotencyKey)) {
            return null;
        }
        return resolvedKey;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    @GetMapping("/gst/return")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<GstReturnDto>> generateGstReturn(@RequestParam(required = false) String period) {
        YearMonth target = period != null && !period.isBlank() ? YearMonth.parse(period) : null;
        return ResponseEntity.ok(ApiResponse.success(taxService.generateGstReturn(target)));
    }

    @GetMapping("/gst/reconciliation")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<GstReconciliationDto>> getGstReconciliation(@RequestParam(required = false) String period) {
        YearMonth target = period != null && !period.isBlank() ? YearMonth.parse(period) : null;
        return ResponseEntity.ok(ApiResponse.success(taxService.generateGstReconciliation(target)));
    }

    @PostMapping("/bad-debts/write-off")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> writeOffBadDebt(@Valid @RequestBody BadDebtWriteOffRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Bad debt written off", creditDebitNoteService.writeOffBadDebt(request)));
    }

    @GetMapping("/sales/returns")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')")
    public ResponseEntity<ApiResponse<List<JournalEntryDto>>> listSalesReturns() {
        return ResponseEntity.ok(ApiResponse.success("Sales returns",
            journalEntryService.listJournalEntriesByReferencePrefix("CRN-")));
    }

    @PostMapping("/sales/returns/preview")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<SalesReturnPreviewDto>> previewSalesReturn(@Valid @RequestBody SalesReturnRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Sales return preview", salesReturnService.previewReturn(request)));
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

    @PostMapping("/periods")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> createOrUpdatePeriod(@Valid @RequestBody AccountingPeriodUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting period saved",
                accountingPeriodService.createOrUpdatePeriod(request)));
    }

    @PutMapping("/periods/{periodId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> updatePeriod(@PathVariable Long periodId,
                                                                         @Valid @RequestBody AccountingPeriodUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting period updated",
                accountingPeriodService.updatePeriod(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/close")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> closePeriod(@PathVariable Long periodId,
                                                                        @RequestBody(required = false) AccountingPeriodCloseRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting period closed", accountingPeriodService.closePeriod(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/request-close")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PeriodCloseRequestDto>> requestPeriodClose(@PathVariable Long periodId,
                                                                                  @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Period close request submitted",
                accountingPeriodService.requestPeriodClose(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/approve-close")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> approvePeriodClose(@PathVariable Long periodId,
                                                                                @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting period close approved",
                accountingPeriodService.approvePeriodClose(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/reject-close")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<PeriodCloseRequestDto>> rejectPeriodClose(@PathVariable Long periodId,
                                                                                 @RequestBody(required = false) PeriodCloseRequestActionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting period close rejected",
                accountingPeriodService.rejectPeriodClose(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/lock")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingPeriodDto>> lockPeriod(@PathVariable Long periodId,
                                                                       @RequestBody(required = false) AccountingPeriodLockRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Accounting period locked", accountingPeriodService.lockPeriod(periodId, request)));
    }

    @PostMapping("/periods/{periodId}/reopen")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
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

    @PostMapping("/reconciliation/bank")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<BankReconciliationSummaryDto>> reconcileBank(
            @Valid @RequestBody BankReconciliationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                bankReconciliationSessionService.reconcileLegacy(request)));
    }

    @PostMapping("/reconciliation/bank/sessions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<BankReconciliationSessionSummaryDto>> startBankReconciliationSession(
            @Valid @RequestBody BankReconciliationSessionCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Bank reconciliation session started",
                bankReconciliationSessionService.startSession(request)));
    }

    @PutMapping("/reconciliation/bank/sessions/{sessionId}/items")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>> updateBankReconciliationSessionItems(
            @PathVariable Long sessionId,
            @RequestBody BankReconciliationSessionItemsUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Bank reconciliation session updated",
                bankReconciliationSessionService.updateItems(sessionId, request)));
    }

    @PostMapping("/reconciliation/bank/sessions/{sessionId}/complete")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>> completeBankReconciliationSession(
            @PathVariable Long sessionId,
            @RequestBody(required = false) BankReconciliationSessionCompletionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Bank reconciliation session completed",
                bankReconciliationSessionService.completeSession(sessionId, request)));
    }

    @GetMapping("/reconciliation/bank/sessions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<PageResponse<BankReconciliationSessionSummaryDto>>> listBankReconciliationSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(
                bankReconciliationSessionService.listSessions(page, size)));
    }

    @GetMapping("/reconciliation/bank/sessions/{sessionId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<BankReconciliationSessionDetailDto>> getBankReconciliationSession(
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(ApiResponse.success(
                bankReconciliationSessionService.getSessionDetail(sessionId)));
    }

    @GetMapping("/reconciliation/subledger")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<ReconciliationService.SubledgerReconciliationReport>> reconcileSubledger() {
        return ResponseEntity.ok(ApiResponse.success(
                reconciliationService.reconcileSubledgerBalances()));
    }

    @GetMapping("/reconciliation/discrepancies")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<ReconciliationDiscrepancyListResponse>> listReconciliationDiscrepancies(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        ReconciliationDiscrepancyStatus statusFilter = parseDiscrepancyStatus(status);
        ReconciliationDiscrepancyType typeFilter = parseDiscrepancyType(type);
        return ResponseEntity.ok(ApiResponse.success(
                reconciliationService.listDiscrepancies(statusFilter, typeFilter)));
    }

    @PostMapping("/reconciliation/discrepancies/{discrepancyId}/resolve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<ReconciliationDiscrepancyDto>> resolveReconciliationDiscrepancy(
            @PathVariable Long discrepancyId,
            @Valid @RequestBody ReconciliationDiscrepancyResolveRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Reconciliation discrepancy resolved",
                reconciliationService.resolveDiscrepancy(discrepancyId, request)));
    }

    @GetMapping("/reconciliation/inter-company")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<ReconciliationService.InterCompanyReconciliationReport>> reconcileInterCompany(
            @RequestParam("companyA") Long companyA,
            @RequestParam("companyB") Long companyB) {
        return ResponseEntity.ok(ApiResponse.success(
                reconciliationService.interCompanyReconcile(companyA, companyB)));
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> dealerStatementPdf(@PathVariable Long dealerId,
                                                     @RequestParam(required = false) String from,
                                                     @RequestParam(required = false) String to) {
        byte[] pdf = statementService.dealerStatementPdf(dealerId,
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        logAccountingExport("ACCOUNTING_DEALER_STATEMENT", dealerId, "pdf");
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> supplierStatementPdf(@PathVariable Long supplierId,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        byte[] pdf = statementService.supplierStatementPdf(supplierId,
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        logAccountingExport("ACCOUNTING_SUPPLIER_STATEMENT", supplierId, "pdf");
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> dealerAgingPdf(@PathVariable Long dealerId,
                                                 @RequestParam(required = false) String asOf,
                                                 @RequestParam(required = false) String buckets) {
        byte[] pdf = statementService.dealerAgingPdf(dealerId,
                asOf != null ? java.time.LocalDate.parse(asOf) : null,
                buckets);
        logAccountingExport("ACCOUNTING_DEALER_AGING", dealerId, "pdf");
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
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> supplierAgingPdf(@PathVariable Long supplierId,
                                                   @RequestParam(required = false) String asOf,
                                                   @RequestParam(required = false) String buckets) {
        byte[] pdf = statementService.supplierAgingPdf(supplierId,
                asOf != null ? java.time.LocalDate.parse(asOf) : null,
                buckets);
        logAccountingExport("ACCOUNTING_SUPPLIER_AGING", supplierId, "pdf");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=supplier-aging.pdf")
                .body(pdf);
    }

    /* Inventory valuation and WIP */
    @PostMapping("/inventory/landed-cost")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> recordLandedCost(@Valid @RequestBody LandedCostRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Landed cost posted", inventoryAccountingService.recordLandedCost(request)));
    }

    @PostMapping("/inventory/revaluation")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> revalueInventory(@Valid @RequestBody InventoryRevaluationRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Inventory revaluation posted", inventoryAccountingService.revalueInventory(request)));
    }

    @PostMapping("/inventory/wip-adjustment")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<JournalEntryDto>> adjustWip(@Valid @RequestBody WipAdjustmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success("WIP adjustment posted", inventoryAccountingService.adjustWip(request)));
    }

    /* Audit digest */
    @GetMapping("/audit/digest")
    @Deprecated(forRemoval = false, since = "2026-02-11")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<AuditDigestResponse>> auditDigest(@RequestParam(required = false) String from,
                                                                        @RequestParam(required = false) String to) {
        return ResponseEntity.ok(ApiResponse.success(
                accountingAuditService.auditDigest(
                        from != null ? java.time.LocalDate.parse(from) : null,
                        to != null ? java.time.LocalDate.parse(to) : null)));
    }

    @GetMapping(value = "/audit/digest.csv", produces = "text/csv")
    @Deprecated(forRemoval = false, since = "2026-02-11")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> auditDigestCsv(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        String csv = accountingAuditService.auditDigestCsv(
                from != null ? java.time.LocalDate.parse(from) : null,
                to != null ? java.time.LocalDate.parse(to) : null);
        logAccountingExport("ACCOUNTING_AUDIT_DIGEST", null, "csv");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-digest.csv")
                .body(csv);
    }

    @GetMapping("/audit/transactions")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<com.bigbrightpaints.erp.shared.dto.PageResponse<AccountingTransactionAuditListItemDto>>> transactionAudit(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        LocalDate fromDate = from != null ? LocalDate.parse(from) : null;
        LocalDate toDate = to != null ? LocalDate.parse(to) : null;
        return ResponseEntity.ok(ApiResponse.success(
                accountingAuditTrailService.listTransactions(fromDate, toDate, module, status, reference, page, size)));
    }

    @GetMapping("/audit/transactions/{journalEntryId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<AccountingTransactionAuditDetailDto>> transactionAuditDetail(@PathVariable Long journalEntryId) {
        return ResponseEntity.ok(ApiResponse.success(
                accountingAuditTrailService.transactionDetail(journalEntryId)));
    }

    // ==================== TEMPORAL QUERIES (Snapshots + Journal Lines) ====================

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
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        String resolvedStart = StringUtils.hasText(startDate) ? startDate : from;
        String resolvedEnd = StringUtils.hasText(endDate) ? endDate : to;
        if (!StringUtils.hasText(resolvedStart) || !StringUtils.hasText(resolvedEnd)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Account activity requires startDate/endDate (or from/to) query parameters");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(resolvedStart);
            end = LocalDate.parse(resolvedEnd);
        } catch (DateTimeParseException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "Invalid account activity date format; expected ISO date yyyy-MM-dd")
                    .withDetail("startDate", resolvedStart)
                    .withDetail("endDate", resolvedEnd);
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Account activity report",
                temporalBalanceService.getAccountActivity(
                        accountId,
                        start,
                        end)));
    }

    @GetMapping("/date-context")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAccountingDateContext() {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        Instant now = companyClock.now(company);
        Map<String, Object> payload = new HashMap<>();
        payload.put("companyId", company != null ? company.getId() : null);
        payload.put("companyCode", company != null ? company.getCode() : null);
        payload.put("timezone", company != null ? company.getTimezone() : null);
        payload.put("today", today);
        payload.put("now", now);
        return ResponseEntity.ok(ApiResponse.success("Accounting date context", payload));
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

    private void logAccountingExport(String resourceType, Long resourceId, String format) {
        if (auditService == null) {
            return;
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("resourceType", resourceType);
        metadata.put("resourceId", resourceId != null ? resourceId.toString() : "");
        metadata.put("operation", "EXPORT");
        metadata.put("format", format);
        auditService.logSuccess(AuditEvent.DATA_EXPORT, metadata);
    }

}
