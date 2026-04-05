package com.bigbrightpaints.erp.modules.accounting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

import jakarta.persistence.EntityManager;

@Service
public class InventoryAccountingService extends AccountingCoreEngineCore {

  private final JournalEntryService journalEntryService;

  @Autowired
  public InventoryAccountingService(
      CompanyContextService companyContextService,
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
      JournalEntryService journalEntryService) {
    super(
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
        accountingEventStore);
    this.journalEntryService = journalEntryService;
  }

  @Override
  public JournalEntryDto recordLandedCost(LandedCostRequest request) {
    LandedCostRequest normalized = normalizeLandedCostRequest(request);
    return super.recordLandedCost(normalized);
  }

  @Override
  public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
    InventoryRevaluationRequest normalized = normalizeInventoryRevaluationRequest(request);
    return super.revalueInventory(normalized);
  }

  @Override
  public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
    WipAdjustmentRequest normalized = normalizeWipAdjustmentRequest(request);
    return super.adjustWip(normalized);
  }

  @Override
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryService.createJournalEntry(request);
  }

  @Override
  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
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
