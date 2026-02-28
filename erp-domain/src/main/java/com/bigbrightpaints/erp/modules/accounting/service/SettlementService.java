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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
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
public class SettlementService extends AccountingCoreEngine {

    private final AccountingIdempotencyService accountingIdempotencyService;

    public SettlementService(CompanyContextService companyContextService,
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
    }

    // Compatibility constructor used by controller bridge delegates.
    public SettlementService(AccountingIdempotencyService accountingIdempotencyService) {
        super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        this.accountingIdempotencyService = accountingIdempotencyService;
    }

    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        return accountingIdempotencyService.recordSupplierPayment(request);
    }

    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        return accountingIdempotencyService.settleDealerInvoices(request);
    }

    public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
        return accountingIdempotencyService.autoSettleDealer(dealerId, request);
    }

    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        return accountingIdempotencyService.settleSupplierInvoices(request);
    }

    public PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
        return accountingIdempotencyService.autoSettleSupplier(supplierId, request);
    }
}
