package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class SettlementService extends AccountingCoreEngine {

    private final AccountingIdempotencyService accountingIdempotencyService;

    @Autowired
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
        SupplierPaymentRequest normalized = normalizeSupplierPaymentRequest(request);
        return accountingIdempotencyService.recordSupplierPayment(normalized);
    }

    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        DealerSettlementRequest normalized = normalizeDealerSettlementRequest(request);
        return accountingIdempotencyService.settleDealerInvoices(normalized);
    }

    public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
        AutoSettlementRequest normalized = normalizeAutoSettlementRequest("DEALER", dealerId, request);
        return accountingIdempotencyService.autoSettleDealer(dealerId, normalized);
    }

    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        SupplierSettlementRequest normalized = normalizeSupplierSettlementRequest(request);
        return accountingIdempotencyService.settleSupplierInvoices(normalized);
    }

    public PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
        AutoSettlementRequest normalized = normalizeAutoSettlementRequest("SUPPLIER", supplierId, request);
        return accountingIdempotencyService.autoSettleSupplier(supplierId, normalized);
    }

    private SupplierPaymentRequest normalizeSupplierPaymentRequest(SupplierPaymentRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requireNotNull(request.supplierId(), "supplierId");
        ValidationUtils.requireNotNull(request.cashAccountId(), "cashAccountId");
        ValidationUtils.requirePositive(request.amount(), "amount");
        return new SupplierPaymentRequest(
                request.supplierId(),
                request.cashAccountId(),
                request.amount().abs(),
                normalizeText(request.referenceNumber()),
                normalizeText(request.memo()),
                normalizeText(request.idempotencyKey()),
                request.allocations()
        );
    }

    private DealerSettlementRequest normalizeDealerSettlementRequest(DealerSettlementRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
        return new DealerSettlementRequest(
                request.dealerId(),
                request.cashAccountId(),
                request.discountAccountId(),
                request.writeOffAccountId(),
                request.fxGainAccountId(),
                request.fxLossAccountId(),
                request.settlementDate(),
                normalizeText(request.referenceNumber()),
                normalizeText(request.memo()),
                normalizeText(request.idempotencyKey()),
                Boolean.TRUE.equals(request.adminOverride()),
                request.allocations(),
                request.payments()
        );
    }

    private SupplierSettlementRequest normalizeSupplierSettlementRequest(SupplierSettlementRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requireNotNull(request.supplierId(), "supplierId");
        ValidationUtils.requireNotNull(request.cashAccountId(), "cashAccountId");
        return new SupplierSettlementRequest(
                request.supplierId(),
                request.cashAccountId(),
                request.discountAccountId(),
                request.writeOffAccountId(),
                request.fxGainAccountId(),
                request.fxLossAccountId(),
                request.settlementDate(),
                normalizeText(request.referenceNumber()),
                normalizeText(request.memo()),
                normalizeText(request.idempotencyKey()),
                Boolean.TRUE.equals(request.adminOverride()),
                request.allocations()
        );
    }

    private AutoSettlementRequest normalizeAutoSettlementRequest(String partnerType,
                                                                 Long partnerId,
                                                                 AutoSettlementRequest request) {
        ValidationUtils.requireNotNull(partnerId, "partnerId");
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requirePositive(request.amount(), "amount");
        String idempotencyKey = normalizeText(request.idempotencyKey());
        String reference = normalizeText(request.referenceNumber());
        String seed = partnerType + "|" + partnerId + "|" + request.amount().stripTrailingZeros().toPlainString() + "|" +
                (reference == null ? "" : reference);
        String deterministicSuffix = IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
        if (idempotencyKey == null) {
            idempotencyKey = partnerType.toLowerCase(Locale.ROOT) + "-auto-" + deterministicSuffix;
        }
        if (reference == null) {
            String prefix = "DEALER".equals(partnerType) ? "SET" : "SUP-SET";
            reference = prefix + "-" + deterministicSuffix.substring(0, 12);
        }
        return new AutoSettlementRequest(
                request.cashAccountId(),
                request.amount().abs(),
                reference,
                normalizeText(request.memo()),
                idempotencyKey
        );
    }

    private String normalizeText(String value) {
        String normalized = IdempotencyUtils.normalizeToken(value);
        return normalized.isBlank() ? null : normalized;
    }
}
