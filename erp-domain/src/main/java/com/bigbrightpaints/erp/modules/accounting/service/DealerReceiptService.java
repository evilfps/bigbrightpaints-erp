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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class DealerReceiptService extends AccountingCoreEngine {

    private final AccountingIdempotencyService accountingIdempotencyService;

    @Autowired
    public DealerReceiptService(CompanyContextService companyContextService,
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

    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        DealerReceiptRequest normalized = normalizeDealerReceiptRequest(request);
        return accountingIdempotencyService.recordDealerReceipt(normalized);
    }

    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        DealerReceiptSplitRequest normalized = normalizeDealerReceiptSplitRequest(request);
        return accountingIdempotencyService.recordDealerReceiptSplit(normalized);
    }

    public List<JournalEntryDto> listDealerReceipts(Long dealerId, int page, int size) {
        ValidationUtils.requireNotNull(dealerId, "dealerId");
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, 200));
        return super.listJournalEntries(dealerId, null, safePage, safeSize);
    }

    private DealerReceiptRequest normalizeDealerReceiptRequest(DealerReceiptRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
        ValidationUtils.requireNotNull(request.cashAccountId(), "cashAccountId");
        ValidationUtils.requirePositive(request.amount(), "amount");
        String reference = normalizeText(request.referenceNumber());
        String idempotencyKey = normalizeText(request.idempotencyKey());
        if (idempotencyKey == null) {
            String seed = "DEALER_RECEIPT|" + request.dealerId() + "|" + request.amount().stripTrailingZeros().toPlainString()
                    + "|" + (reference == null ? "" : reference);
            idempotencyKey = "dealer-receipt-" + IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
        }
        return new DealerReceiptRequest(
                request.dealerId(),
                request.cashAccountId(),
                request.amount().abs(),
                reference,
                normalizeText(request.memo()),
                idempotencyKey,
                request.allocations()
        );
    }

    private DealerReceiptSplitRequest normalizeDealerReceiptSplitRequest(DealerReceiptSplitRequest request) {
        ValidationUtils.requireNotNull(request, "request");
        ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
        ValidationUtils.requireNotNull(request.incomingLines(), "incomingLines");
        List<DealerReceiptSplitRequest.IncomingLine> normalizedIncoming = request.incomingLines().stream()
                .map(this::normalizeIncomingLine)
                .toList();
        BigDecimal totalIncoming = normalizedIncoming.stream()
                .map(DealerReceiptSplitRequest.IncomingLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        ValidationUtils.requirePositive(totalIncoming, "incoming total");
        String reference = normalizeText(request.referenceNumber());
        String idempotencyKey = normalizeText(request.idempotencyKey());
        if (idempotencyKey == null) {
            String seed = "DEALER_RECEIPT_SPLIT|" + request.dealerId() + "|" + totalIncoming.stripTrailingZeros().toPlainString()
                    + "|" + normalizedIncoming.size() + "|" + (reference == null ? "" : reference);
            idempotencyKey = "dealer-receipt-split-" + IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
        }
        return new DealerReceiptSplitRequest(
                request.dealerId(),
                normalizedIncoming,
                reference,
                normalizeText(request.memo()),
                idempotencyKey
        );
    }

    private DealerReceiptSplitRequest.IncomingLine normalizeIncomingLine(DealerReceiptSplitRequest.IncomingLine line) {
        ValidationUtils.requireNotNull(line, "incomingLine");
        ValidationUtils.requireNotNull(line.accountId(), "incomingLine.accountId");
        BigDecimal amount = ValidationUtils.requirePositive(line.amount(), "incomingLine.amount");
        return new DealerReceiptSplitRequest.IncomingLine(line.accountId(), amount.abs());
    }

    private String normalizeText(String value) {
        String normalized = IdempotencyUtils.normalizeToken(value);
        return normalized.isBlank() ? null : normalized;
    }
}
