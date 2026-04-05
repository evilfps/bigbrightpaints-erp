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
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
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
public class CreditDebitNoteService extends AccountingCoreEngineCore {

  private final JournalEntryService journalEntryService;

  @Autowired
  public CreditDebitNoteService(
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
  public JournalEntryDto postCreditNote(CreditNoteRequest request) {
    CreditNoteRequest normalized = normalizeCreditNoteRequest(request);
    return super.postCreditNote(normalized);
  }

  @Override
  public JournalEntryDto postDebitNote(DebitNoteRequest request) {
    DebitNoteRequest normalized = normalizeDebitNoteRequest(request);
    return super.postDebitNote(normalized);
  }

  @Override
  public JournalEntryDto postAccrual(AccrualRequest request) {
    AccrualRequest normalized = normalizeAccrualRequest(request);
    return super.postAccrual(normalized);
  }

  @Override
  public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
    BadDebtWriteOffRequest normalized = normalizeBadDebtRequest(request);
    return super.writeOffBadDebt(normalized);
  }

  @Override
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryService.createJournalEntry(request);
  }

  @Override
  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
  }

  private CreditNoteRequest normalizeCreditNoteRequest(CreditNoteRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.invoiceId(), "invoiceId");
    return new CreditNoteRequest(
        request.invoiceId(),
        request.amount() == null
            ? null
            : ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private DebitNoteRequest normalizeDebitNoteRequest(DebitNoteRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.purchaseId(), "purchaseId");
    return new DebitNoteRequest(
        request.purchaseId(),
        request.amount() == null
            ? null
            : ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private AccrualRequest normalizeAccrualRequest(AccrualRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.debitAccountId(), "debitAccountId");
    ValidationUtils.requireNotNull(request.creditAccountId(), "creditAccountId");
    return new AccrualRequest(
        request.debitAccountId(),
        request.creditAccountId(),
        ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        request.autoReverseDate(),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private BadDebtWriteOffRequest normalizeBadDebtRequest(BadDebtWriteOffRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.invoiceId(), "invoiceId");
    ValidationUtils.requireNotNull(request.expenseAccountId(), "expenseAccountId");
    return new BadDebtWriteOffRequest(
        request.invoiceId(),
        request.expenseAccountId(),
        ValidationUtils.requirePositive(request.amount(), "amount").abs(),
        request.entryDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()));
  }

  private String normalizeText(String value) {
    String normalized = IdempotencyUtils.normalizeToken(value);
    return normalized.isBlank() ? null : normalized;
  }
}
