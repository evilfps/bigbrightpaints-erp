package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;

@Service
public class SettlementService {

  @SuppressWarnings("unused")
  private Environment environment;

  private final SettlementCoreSupport settlementCoreSupport;

  @Autowired
  public SettlementService(SettlementCoreSupport settlementCoreSupport) {
    this.settlementCoreSupport = settlementCoreSupport;
  }

  public SettlementService(
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
      JournalEntryService journalEntryService,
      DealerReceiptService dealerReceiptService) {
    this(
        new SettlementCoreSupport(
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
            journalEntryService,
            dealerReceiptService));
  }

  public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    return settlementCoreSupport.recordSupplierPayment(normalizeSupplierPaymentRequest(request));
  }

  JournalEntryDto recordSupplierPaymentInternal(SupplierPaymentRequest request) {
    return settlementCoreSupport.recordSupplierPaymentInternal(request);
  }

  public PartnerSettlementResponse settleDealerInvoices(PartnerSettlementRequest request) {
    return settlementCoreSupport.settleDealerInvoices(normalizeDealerSettlementRequest(request));
  }

  PartnerSettlementResponse settleDealerInvoicesInternal(PartnerSettlementRequest request) {
    return settlementCoreSupport.settleDealerInvoicesInternal(request);
  }

  public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
    return settlementCoreSupport.autoSettleDealer(
        dealerId, normalizeAutoSettlementRequest(dealerId, request));
  }

  PartnerSettlementResponse autoSettleDealerInternal(Long dealerId, AutoSettlementRequest request) {
    return settlementCoreSupport.autoSettleDealerInternal(dealerId, request);
  }

  public PartnerSettlementResponse settleSupplierInvoices(PartnerSettlementRequest request) {
    return settlementCoreSupport.settleSupplierInvoices(
        normalizeSupplierSettlementRequest(request));
  }

  PartnerSettlementResponse settleSupplierInvoicesInternal(PartnerSettlementRequest request) {
    return settlementCoreSupport.settleSupplierInvoicesInternal(request);
  }

  public PartnerSettlementResponse autoSettleSupplier(
      Long supplierId, AutoSettlementRequest request) {
    return settlementCoreSupport.autoSettleSupplier(
        supplierId, normalizeAutoSettlementRequest(supplierId, request));
  }

  PartnerSettlementResponse autoSettleSupplierInternal(
      Long supplierId, AutoSettlementRequest request) {
    return settlementCoreSupport.autoSettleSupplierInternal(supplierId, request);
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
        request.allocations());
  }

  private PartnerSettlementRequest normalizeDealerSettlementRequest(
      PartnerSettlementRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.partnerType(), "partnerType");
    if (request.partnerType() != PartnerType.DEALER) {
      throw ValidationUtils.invalidState("Dealer settlements require partnerType DEALER");
    }
    ValidationUtils.requireNotNull(request.partnerId(), "partnerId");
    return new PartnerSettlementRequest(
        request.partnerType(),
        request.partnerId(),
        request.cashAccountId(),
        request.discountAccountId(),
        request.writeOffAccountId(),
        request.fxGainAccountId(),
        request.fxLossAccountId(),
        positiveAmountOrNull(request.amount()),
        request.unappliedAmountApplication(),
        request.settlementDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()),
        request.allocations(),
        request.payments());
  }

  private PartnerSettlementRequest normalizeSupplierSettlementRequest(
      PartnerSettlementRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.partnerType(), "partnerType");
    if (request.partnerType() != PartnerType.SUPPLIER) {
      throw ValidationUtils.invalidState("Supplier settlements require partnerType SUPPLIER");
    }
    ValidationUtils.requireNotNull(request.partnerId(), "partnerId");
    return new PartnerSettlementRequest(
        request.partnerType(),
        request.partnerId(),
        request.cashAccountId(),
        request.discountAccountId(),
        request.writeOffAccountId(),
        request.fxGainAccountId(),
        request.fxLossAccountId(),
        positiveAmountOrNull(request.amount()),
        request.unappliedAmountApplication(),
        request.settlementDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()),
        request.allocations(),
        request.payments());
  }

  private AutoSettlementRequest normalizeAutoSettlementRequest(
      Long partnerId, AutoSettlementRequest request) {
    ValidationUtils.requireNotNull(partnerId, "partnerId");
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requirePositive(request.amount(), "amount");
    return new AutoSettlementRequest(
        request.cashAccountId(),
        request.amount().abs(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()));
  }

  private BigDecimal positiveAmountOrNull(BigDecimal value) {
    if (value == null) {
      return null;
    }
    ValidationUtils.requirePositive(value, "amount");
    return value.abs();
  }

  private String normalizeText(String value) {
    String normalized = IdempotencyUtils.normalizeToken(value);
    return normalized.isBlank() ? null : normalized;
  }
}
