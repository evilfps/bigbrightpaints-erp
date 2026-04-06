package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;

@Service
public class DealerReceiptService {

  @SuppressWarnings("unused")
  private Environment environment;

  private final AccountingCoreSupport accountingCoreSupport;

  @Autowired
  public DealerReceiptService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  public DealerReceiptService(
      com.bigbrightpaints.erp.modules.company.service.CompanyContextService companyContextService,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository
          journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository payrollRunRepository,
      com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository payrollRunLineRepository,
      AccountingPeriodService accountingPeriodService,
      ReferenceNumberService referenceNumberService,
      org.springframework.context.ApplicationEventPublisher eventPublisher,
      com.bigbrightpaints.erp.core.util.CompanyClock companyClock,
      com.bigbrightpaints.erp.core.util.CompanyEntityLookup companyEntityLookup,
      com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository
          settlementAllocationRepository,
      com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository
          rawMaterialPurchaseRepository,
      com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository invoiceRepository,
      com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository
          rawMaterialMovementRepository,
      com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository
          rawMaterialBatchRepository,
      com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository
          finishedGoodBatchRepository,
      com.bigbrightpaints.erp.modules.sales.domain.DealerRepository dealerRepository,
      com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository supplierRepository,
      com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy
          invoiceSettlementPolicy,
      JournalReferenceResolver journalReferenceResolver,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository
          journalReferenceMappingRepository,
      jakarta.persistence.EntityManager entityManager,
      com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService,
      com.bigbrightpaints.erp.core.audit.AuditService auditService,
      com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore,
      JournalEntryService journalEntryService) {
    this(
        new DelegatingAccountingCoreSupport(
            companyContextService,
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
            accountingEventStore,
            journalEntryService));
  }

  public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
    return accountingCoreSupport.recordDealerReceipt(normalizeDealerReceiptRequest(request));
  }

  JournalEntryDto recordDealerReceiptNormalized(DealerReceiptRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    return accountingCoreSupport.recordDealerReceipt(request);
  }

  public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
    return accountingCoreSupport.recordDealerReceiptSplit(
        normalizeDealerReceiptSplitRequest(request));
  }

  public List<JournalEntryDto> listDealerReceipts(Long dealerId, int page, int size) {
    ValidationUtils.requireNotNull(dealerId, "dealerId");
    return accountingCoreSupport.listJournalEntries(
        dealerId, null, Math.max(page, 0), Math.max(1, Math.min(size, 200)));
  }

  JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return accountingCoreSupport.createJournalEntry(request);
  }

  private DealerReceiptRequest normalizeDealerReceiptRequest(DealerReceiptRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
    ValidationUtils.requireNotNull(request.cashAccountId(), "cashAccountId");
    ValidationUtils.requirePositive(request.amount(), "amount");
    String reference = normalizeText(request.referenceNumber());
    String idempotencyKey = normalizeText(request.idempotencyKey());
    if (idempotencyKey == null) {
      String seed =
          "DEALER_RECEIPT|"
              + request.dealerId()
              + "|"
              + request.amount().stripTrailingZeros().toPlainString()
              + "|"
              + (reference == null ? "" : reference);
      idempotencyKey =
          "dealer-receipt-" + IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
    }
    return new DealerReceiptRequest(
        request.dealerId(),
        request.cashAccountId(),
        request.amount().abs(),
        reference,
        normalizeText(request.memo()),
        idempotencyKey,
        request.allocations());
  }

  private DealerReceiptSplitRequest normalizeDealerReceiptSplitRequest(
      DealerReceiptSplitRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
    ValidationUtils.requireNotNull(request.incomingLines(), "incomingLines");
    List<DealerReceiptSplitRequest.IncomingLine> normalizedIncoming =
        request.incomingLines().stream().map(this::normalizeIncomingLine).toList();
    BigDecimal totalIncoming =
        normalizedIncoming.stream()
            .map(DealerReceiptSplitRequest.IncomingLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    ValidationUtils.requirePositive(totalIncoming, "incoming total");
    String reference = normalizeText(request.referenceNumber());
    String idempotencyKey = normalizeText(request.idempotencyKey());
    if (idempotencyKey == null) {
      String seed =
          "DEALER_RECEIPT_SPLIT|"
              + request.dealerId()
              + "|"
              + totalIncoming.stripTrailingZeros().toPlainString()
              + "|"
              + normalizedIncoming.size()
              + "|"
              + (reference == null ? "" : reference);
      idempotencyKey =
          "dealer-receipt-split-" + IdempotencyUtils.sha256Hex(seed, 24).toUpperCase(Locale.ROOT);
    }
    return new DealerReceiptSplitRequest(
        request.dealerId(),
        normalizedIncoming,
        reference,
        normalizeText(request.memo()),
        idempotencyKey);
  }

  private DealerReceiptSplitRequest.IncomingLine normalizeIncomingLine(
      DealerReceiptSplitRequest.IncomingLine line) {
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
