package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AccountingIdempotencyService extends AccountingCoreEngine {

    public AccountingIdempotencyService(CompanyContextService companyContextService,
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
                                        AccountingEventStore accountingEventStore) {
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
    }

    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        return super.createManualJournalEntry(request, idempotencyKey);
    }

    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        return super.recordDealerReceipt(request);
    }

    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        return super.recordDealerReceiptSplit(request);
    }

    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        return super.recordSupplierPayment(request);
    }

    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        return super.settleDealerInvoices(request);
    }

    public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
        return super.autoSettleDealer(dealerId, request);
    }

    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        return super.settleSupplierInvoices(request);
    }

    public PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
        return super.autoSettleSupplier(supplierId, request);
    }
}
