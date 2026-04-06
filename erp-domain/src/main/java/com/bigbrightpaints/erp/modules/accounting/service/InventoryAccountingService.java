package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;

@Service
public class InventoryAccountingService {

  @SuppressWarnings("unused")
  private Environment environment;

  private final AccountingCoreSupport accountingCoreSupport;

  @Autowired
  public InventoryAccountingService(AccountingCoreSupport accountingCoreSupport) {
    this.accountingCoreSupport = accountingCoreSupport;
  }

  public InventoryAccountingService(
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

  public JournalEntryDto recordLandedCost(LandedCostRequest request) {
    return accountingCoreSupport.recordLandedCost(normalizeLandedCostRequest(request));
  }

  public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
    return accountingCoreSupport.revalueInventory(normalizeInventoryRevaluationRequest(request));
  }

  public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
    return accountingCoreSupport.adjustWip(normalizeWipAdjustmentRequest(request));
  }

  JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return accountingCoreSupport.createJournalEntry(request);
  }

  private LandedCostRequest normalizeLandedCostRequest(LandedCostRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.rawMaterialPurchaseId(), "rawMaterialPurchaseId");
    ValidationUtils.requireNotNull(request.inventoryAccountId(), "inventoryAccountId");
    ValidationUtils.requireNotNull(request.offsetAccountId(), "offsetAccountId");
    return new LandedCostRequest(
        request.rawMaterialPurchaseId(),
        ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.inventoryAccountId(),
        request.offsetAccountId(),
        request.entryDate(),
        normalizeText(request.memo()),
        normalizeText(request.referenceNumber()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private InventoryRevaluationRequest normalizeInventoryRevaluationRequest(
      InventoryRevaluationRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.inventoryAccountId(), "inventoryAccountId");
    ValidationUtils.requireNotNull(request.revaluationAccountId(), "revaluationAccountId");
    ValidationUtils.requireNotNull(request.deltaAmount(), "deltaAmount");
    ValidationUtils.requirePositive(request.deltaAmount().abs(), "deltaAmount");
    return new InventoryRevaluationRequest(
        request.inventoryAccountId(),
        request.revaluationAccountId(),
        request.deltaAmount(),
        normalizeText(request.memo()),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private WipAdjustmentRequest normalizeWipAdjustmentRequest(WipAdjustmentRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.productionLogId(), "productionLogId");
    ValidationUtils.requireNotNull(request.wipAccountId(), "wipAccountId");
    ValidationUtils.requireNotNull(request.inventoryAccountId(), "inventoryAccountId");
    ValidationUtils.requireNotNull(request.direction(), "direction");
    return new WipAdjustmentRequest(
        request.productionLogId(),
        ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.wipAccountId(),
        request.inventoryAccountId(),
        request.direction(),
        normalizeText(request.memo()),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private String normalizeText(String value) {
    String normalized = IdempotencyUtils.normalizeToken(value);
    return normalized.isBlank() ? null : normalized;
  }
}
