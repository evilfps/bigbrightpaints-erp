package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.time.LocalDate;

@Service
public class JournalEntryService extends AccountingCoreEngine {

    private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = BigDecimal.ZERO;

    private final AccountingIdempotencyService accountingIdempotencyService;
    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;

    @Autowired
    public JournalEntryService(CompanyContextService companyContextService,
                               AccountRepository accountRepository,
                               JournalEntryRepository journalEntryRepository,
                               DealerLedgerService dealerLedgerService,
                               SupplierLedgerService supplierLedgerService,
                               PayrollRunRepository payrollRunRepository,
                               PayrollRunLineRepository payrollRunLineRepository,
                               AccountingPeriodService accountingPeriodService,
                               ReferenceNumberService referenceNumberService,
                               ApplicationEventPublisher eventPublisher,
                               CompanyClock companyClock,
                               CompanyEntityLookup companyEntityLookup,
                               PartnerSettlementAllocationRepository settlementAllocationRepository,
                               RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                               InvoiceRepository invoiceRepository,
                               RawMaterialMovementRepository rawMaterialMovementRepository,
                               RawMaterialBatchRepository rawMaterialBatchRepository,
                               FinishedGoodBatchRepository finishedGoodBatchRepository,
                               DealerRepository dealerRepository,
                               SupplierRepository supplierRepository,
                               InvoiceSettlementPolicy invoiceSettlementPolicy,
                               JournalReferenceResolver journalReferenceResolver,
                               JournalReferenceMappingRepository journalReferenceMappingRepository,
                               EntityManager entityManager,
                               SystemSettingsService systemSettingsService,
                               AuditService auditService,
                               AccountingEventStore accountingEventStore,
                               AccountingIdempotencyService accountingIdempotencyService) {
        super(companyContextService,
                accountRepository,
                journalEntryRepository,
                dealerLedgerService,
                supplierLedgerService,
                payrollRunRepository,
                payrollRunLineRepository,
                accountingPeriodService,
                referenceNumberService,
                eventPublisher,
                companyClock,
                companyEntityLookup,
                settlementAllocationRepository,
                rawMaterialPurchaseRepository,
                invoiceRepository,
                rawMaterialMovementRepository,
                rawMaterialBatchRepository,
                finishedGoodBatchRepository,
                dealerRepository,
                supplierRepository,
                invoiceSettlementPolicy,
                journalReferenceResolver,
                journalReferenceMappingRepository,
                entityManager,
                systemSettingsService,
                auditService,
                accountingEventStore);
        this.accountingIdempotencyService = accountingIdempotencyService;
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
    }

    public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
        return super.listJournalEntries(dealerId, supplierId, page, size);
    }

    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        return super.listJournalEntries(dealerId);
    }

    public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
        return super.listJournalEntriesByReferencePrefix(prefix);
    }

    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        return super.createJournalEntry(request);
    }

    public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Journal creation request is required");
        }
        ValidationUtils.requirePositive(request.amount(), "amount");
        if (!StringUtils.hasText(request.narration())) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Narration is required for journal creation");
        }
        if (!StringUtils.hasText(request.sourceModule())) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Source module is required for journal creation");
        }
        if (!StringUtils.hasText(request.sourceReference())) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Source reference is required for journal creation");
        }

        List<JournalEntryRequest.JournalLineRequest> resolvedLines = request.resolvedLines();
        if (resolvedLines == null || resolvedLines.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "At least one journal line is required");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (JournalEntryRequest.JournalLineRequest line : resolvedLines) {
            if (line.accountId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                        "Account is required for every journal line");
            }
            BigDecimal debit = line.debit() == null ? BigDecimal.ZERO : line.debit();
            BigDecimal credit = line.credit() == null ? BigDecimal.ZERO : line.credit();
            if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Journal line amounts cannot be negative");
            }
            if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Debit and credit cannot both be non-zero on the same line");
            }
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }
        if (totalDebit.compareTo(BigDecimal.ZERO) <= 0 || totalCredit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Journal lines must include at least one debit and one credit");
        }
        if (totalDebit.subtract(totalCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Journal entry must balance")
                    .withDetail("totalDebit", totalDebit)
                    .withDetail("totalCredit", totalCredit);
        }

        LocalDate entryDate = resolveManualEntryDate(request.entryDate());
        String narration = request.narration().trim();
        String sourceModule = request.sourceModule().trim();
        String sourceReference = request.sourceReference().trim();
        boolean manualSource = "MANUAL".equalsIgnoreCase(sourceModule);
        JournalEntryRequest journalRequest = new JournalEntryRequest(
                manualSource ? null : sourceReference,
                entryDate,
                narration,
                request.dealerId(),
                request.supplierId(),
                Boolean.TRUE.equals(request.adminOverride()),
                resolvedLines,
                null,
                null,
                sourceModule,
                sourceReference,
                manualSource ? JournalEntryType.MANUAL.name() : JournalEntryType.AUTOMATED.name(),
                request.attachmentReferences()
        );
        if (manualSource) {
            return super.createManualJournalEntry(journalRequest, sourceReference);
        }
        return createJournalEntry(journalRequest);
    }

    private LocalDate resolveManualEntryDate(LocalDate requestedEntryDate) {
        if (requestedEntryDate != null) {
            return requestedEntryDate;
        }
        return companyClock.today(companyContextService.requireCurrentCompany());
    }

    public List<JournalListItemDto> listJournals(LocalDate fromDate,
                                                 LocalDate toDate,
                                                 String journalType,
                                                 String sourceModule) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_DATE,
                    "fromDate cannot be after toDate")
                    .withDetail("fromDate", fromDate)
                    .withDetail("toDate", toDate);
        }
        return super.listJournals(fromDate, toDate, journalType, sourceModule);
    }

    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        return accountingIdempotencyService.createManualJournalEntry(request, idempotencyKey);
    }

    public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
        return super.reverseJournalEntry(entryId, request);
    }

    JournalEntryDto reverseClosingEntryForPeriodReopen(JournalEntry entry, AccountingPeriod period, String reason) {
        return super.reverseClosingEntryForPeriodReopen(entry, period, reason);
    }

    public List<JournalEntryDto> cascadeReverseRelatedEntries(Long primaryEntryId, JournalEntryReversalRequest request) {
        return super.cascadeReverseRelatedEntries(primaryEntryId, request);
    }
}
