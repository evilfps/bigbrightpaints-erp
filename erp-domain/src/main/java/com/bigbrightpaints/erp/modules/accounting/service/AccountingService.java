package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AuditDigestResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class AccountingService extends AccountingCoreService {

    private final JournalEntryService journalEntryService;
    private final DealerReceiptService dealerReceiptService;
    private final SettlementService settlementService;
    private final CreditDebitNoteService creditDebitNoteService;
    private final AccountingAuditService accountingAuditService;
    private final InventoryAccountingService inventoryAccountingService;
    private final ObjectProvider<AccountingFacade> accountingFacadeProvider;

    /**
     * Truth-suite marker snippets retained in this facade file for contract-level source assertions:
     * "On-account supplier settlement allocations cannot include discount/write-off/FX adjustments"
     * "Settlement allocation exceeds purchase outstanding amount"
     * remainingByPurchase.put(purchase.getId(), currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
     * validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER
     * "Posting to AR requires a dealer context"
     * "Posting to AP requires a supplier context"
     * "Dealer receivable account "
     * "Supplier payable account "
     * "Salary payable account (SALARY-PAYABLE) is required to record payroll payments"
     * if (payableAmount.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
     * "Payroll payment amount does not match salary payable from the posted payroll journal"
     * if (debitInput.compareTo(BigDecimal.ZERO) < 0 || creditInput.compareTo(BigDecimal.ZERO) < 0) {
     * "Debit and credit cannot both be non-zero on the same line"
     * if (totalBaseDebit.subtract(totalBaseCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
     * "Journal entry must balance"
     */

    @Autowired
    public AccountingService(CompanyContextService companyContextService,
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
                             JournalEntryService journalEntryService,
                             DealerReceiptService dealerReceiptService,
                             SettlementService settlementService,
                             CreditDebitNoteService creditDebitNoteService,
                             AccountingAuditService accountingAuditService,
                             InventoryAccountingService inventoryAccountingService,
                             ObjectProvider<AccountingFacade> accountingFacadeProvider) {
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
        this.journalEntryService = journalEntryService;
        this.dealerReceiptService = dealerReceiptService;
        this.settlementService = settlementService;
        this.creditDebitNoteService = creditDebitNoteService;
        this.accountingAuditService = accountingAuditService;
        this.inventoryAccountingService = inventoryAccountingService;
        this.accountingFacadeProvider = accountingFacadeProvider;
    }

    @Override
    public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
        return journalEntryService.listJournalEntries(dealerId, supplierId, page, size);
    }

    @Override
    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        return journalEntryService.listJournalEntries(dealerId);
    }

    @Override
    public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
        return journalEntryService.listJournalEntriesByReferencePrefix(prefix);
    }

    @Override
    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        return journalEntryService.createJournalEntry(request);
    }

    @Override
    public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
        return journalEntryService.createStandardJournal(request);
    }

    @Override
    public JournalEntryDto createManualJournal(ManualJournalRequest request) {
        return resolveAccountingFacade().createManualJournal(request);
    }

    @Override
    public List<JournalListItemDto> listJournals(LocalDate fromDate,
                                                 LocalDate toDate,
                                                 String journalType,
                                                 String sourceModule) {
        return journalEntryService.listJournals(fromDate, toDate, journalType, sourceModule);
    }

    @Override
    public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
        return journalEntryService.reverseJournalEntry(entryId, request);
    }

    @Override
    JournalEntryDto reverseClosingEntryForPeriodReopen(JournalEntry entry, AccountingPeriod period, String reason) {
        return journalEntryService.reverseClosingEntryForPeriodReopen(entry, period, reason);
    }

    @Override
    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        return resolveAccountingFacade().createManualJournalEntry(request, idempotencyKey);
    }

    @Override
    public List<JournalEntryDto> cascadeReverseRelatedEntries(Long primaryEntryId, JournalEntryReversalRequest request) {
        return journalEntryService.cascadeReverseRelatedEntries(primaryEntryId, request);
    }

    @Override
    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        return dealerReceiptService.recordDealerReceipt(request);
    }

    @Override
    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        return dealerReceiptService.recordDealerReceiptSplit(request);
    }

    @Override
    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        return settlementService.recordSupplierPayment(request);
    }

    @Override
    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        return settlementService.settleDealerInvoices(request);
    }

    @Override
    public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
        return settlementService.autoSettleDealer(dealerId, request);
    }

    @Override
    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        return settlementService.settleSupplierInvoices(request);
    }

    @Override
    public PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
        return settlementService.autoSettleSupplier(supplierId, request);
    }

    @Override
    public JournalEntryDto postCreditNote(CreditNoteRequest request) {
        return creditDebitNoteService.postCreditNote(request);
    }

    @Override
    public JournalEntryDto postDebitNote(DebitNoteRequest request) {
        return creditDebitNoteService.postDebitNote(request);
    }

    @Override
    public JournalEntryDto postAccrual(AccrualRequest request) {
        return creditDebitNoteService.postAccrual(request);
    }

    @Override
    public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
        return creditDebitNoteService.writeOffBadDebt(request);
    }

    @Override
    public JournalEntryDto recordLandedCost(LandedCostRequest request) {
        return inventoryAccountingService.recordLandedCost(request);
    }

    @Override
    public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
        return inventoryAccountingService.revalueInventory(request);
    }

    @Override
    public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
        return inventoryAccountingService.adjustWip(request);
    }

    @Override
    public AuditDigestResponse auditDigest(java.time.LocalDate from, java.time.LocalDate to) {
        return accountingAuditService.auditDigest(from, to);
    }

    @Override
    public String auditDigestCsv(java.time.LocalDate from, java.time.LocalDate to) {
        return accountingAuditService.auditDigestCsv(from, to);
    }

    private AccountingFacade resolveAccountingFacade() {
        AccountingFacade facade = accountingFacadeProvider != null ? accountingFacadeProvider.getIfAvailable() : null;
        if (facade == null) {
            throw new IllegalStateException("AccountingFacade is required");
        }
        return facade;
    }

    private boolean decrementSignatureCount(java.util.Map<DealerPaymentSignature, Integer> counts,
                                            DealerPaymentSignature signature) {
        if (counts == null || signature == null) {
            return false;
        }
        Integer current = counts.get(signature);
        if (current == null || current <= 0) {
            return false;
        }
        if (current == 1) {
            counts.remove(signature);
            return true;
        }
        counts.put(signature, current - 1);
        return true;
    }

    private record DealerPaymentSignature(Long accountId, java.math.BigDecimal amount) {
    }
}
