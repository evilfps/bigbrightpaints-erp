package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class JournalEntryService {

  @SuppressWarnings("unused")
  private Environment environment;

  private final JournalQueryService journalQueryService;
  private final JournalPostingService journalPostingService;
  private final JournalReversalService journalReversalService;
  private final PeriodValidationService periodValidationService;
  private final AccountingCoreSupport accountingCoreSupport;

  private record LegacyBundle(
      JournalQueryService journalQueryService,
      JournalPostingService journalPostingService,
      JournalReversalService journalReversalService,
      PeriodValidationService periodValidationService,
      AccountingCoreSupport accountingCoreSupport) {}

  private static LegacyBundle legacyBundle(
      com.bigbrightpaints.erp.modules.company.service.CompanyContextService companyContextService,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository journalEntryRepository,
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
      com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy invoiceSettlementPolicy,
      JournalReferenceResolver journalReferenceResolver,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository
          journalReferenceMappingRepository,
      jakarta.persistence.EntityManager entityManager,
      com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService,
      com.bigbrightpaints.erp.core.audit.AuditService auditService,
      com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore) {
    AccountingCoreSupport accountingCoreSupport =
        new AccountingCoreSupport(
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
    AccountResolutionService accountResolutionService =
        new AccountResolutionService(companyEntityLookup, accountRepository, companyClock);
    AccountingDtoMapperService accountingDtoMapperService =
        new AccountingDtoMapperService(journalReferenceMappingRepository);
    return new LegacyBundle(
        new JournalQueryService(
            companyContextService,
            journalEntryRepository,
            accountingDtoMapperService,
            accountResolutionService),
        new JournalPostingService(accountingCoreSupport),
        new JournalReversalService(accountingCoreSupport),
        new PeriodValidationService(accountingCoreSupport),
        accountingCoreSupport);
  }

  private JournalEntryService(LegacyBundle legacyBundle) {
    this(
        legacyBundle.journalQueryService(),
        legacyBundle.journalPostingService(),
        legacyBundle.journalReversalService(),
        legacyBundle.periodValidationService(),
        legacyBundle.accountingCoreSupport());
  }

  @Autowired
  public JournalEntryService(
      JournalQueryService journalQueryService,
      JournalPostingService journalPostingService,
      JournalReversalService journalReversalService,
      PeriodValidationService periodValidationService,
      AccountingCoreSupport accountingCoreSupport) {
    this.journalQueryService = journalQueryService;
    this.journalPostingService = journalPostingService;
    this.journalReversalService = journalReversalService;
    this.periodValidationService = periodValidationService;
    this.accountingCoreSupport = accountingCoreSupport;
  }

  public JournalEntryService(
      com.bigbrightpaints.erp.modules.company.service.CompanyContextService companyContextService,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository journalEntryRepository,
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
      com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy invoiceSettlementPolicy,
      JournalReferenceResolver journalReferenceResolver,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository
          journalReferenceMappingRepository,
      jakarta.persistence.EntityManager entityManager,
      com.bigbrightpaints.erp.core.config.SystemSettingsService systemSettingsService,
      com.bigbrightpaints.erp.core.audit.AuditService auditService,
      com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore) {
    this(
        legacyBundle(
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
            accountingEventStore));
  }

  public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
    return journalQueryService.listJournalEntries(dealerId, supplierId, page, size);
  }

  public List<JournalEntryDto> listJournalEntries(Long dealerId) {
    return journalQueryService.listJournalEntries(dealerId);
  }

  public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
    return journalQueryService.listJournalEntriesByReferencePrefix(prefix);
  }

  @Transactional
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalPostingService.createJournalEntry(request);
  }

  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalPostingService.createStandardJournal(request);
  }

  public PageResponse<JournalListItemDto> listJournals(
      LocalDate fromDate,
      LocalDate toDate,
      String journalType,
      String sourceModule,
      int page,
      int size) {
    return journalQueryService.listJournals(fromDate, toDate, journalType, sourceModule, page, size);
  }

  @Transactional
  public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "Journal entry request is required");
    }
    var company = accountingCoreSupport.companyContextService.requireCurrentCompany();
    String rawKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
    String key = StringUtils.hasText(rawKey) ? accountingCoreSupport.normalizeIdempotencyMappingKey(rawKey) : null;
    if (StringUtils.hasText(rawKey)) {
      Optional<JournalEntry> existingByReference =
          accountingCoreSupport.journalEntryRepository.findByCompanyAndReferenceNumber(company, rawKey);
      if (existingByReference.isPresent()) {
        return accountingCoreSupport.toDto(existingByReference.get());
      }
      Optional<JournalEntry> existingByResolver =
          accountingCoreSupport.journalReferenceResolver.findExistingEntry(company, rawKey);
      if (existingByResolver.isPresent()) {
        return accountingCoreSupport.toDto(existingByResolver.get());
      }
      int reserved =
          accountingCoreSupport.journalReferenceMappingRepository.reserveManualReference(
              company.getId(),
              key,
              accountingCoreSupport.reservedManualReference(key),
              "JOURNAL_ENTRY",
              CompanyTime.now(company));
      if (reserved == 0) {
        JournalEntry already = accountingCoreSupport.awaitJournalEntry(company, rawKey, key);
        if (already != null) {
          return accountingCoreSupport.toDto(already);
        }
        throw new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                "Manual journal idempotency key already reserved but entry not found")
            .withDetail("referenceNumber", rawKey);
      }
    }
    JournalEntryDto created;
    try {
      created =
          createJournalEntry(
              new JournalEntryRequest(
                  null,
                  request.entryDate(),
                  request.memo(),
                  request.dealerId(),
                  request.supplierId(),
                  request.adminOverride(),
                  request.lines(),
                  request.currency(),
                  request.fxRate(),
                  request.sourceModule(),
                  request.sourceReference(),
                  StringUtils.hasText(request.journalType())
                      ? request.journalType()
                      : JournalEntryType.MANUAL.name(),
                  request.attachmentReferences()));
    } catch (RuntimeException ex) {
      if (!StringUtils.hasText(rawKey) || !accountingCoreSupport.isRetryableManualConcurrencyFailure(ex)) {
        throw ex;
      }
      JournalEntry already = accountingCoreSupport.awaitJournalEntry(company, rawKey, key);
      if (already != null) {
        return accountingCoreSupport.toDto(already);
      }
      throw ex;
    }
    if (StringUtils.hasText(key)
        && created != null
        && StringUtils.hasText(created.referenceNumber())) {
      JournalReferenceMapping mapping =
          accountingCoreSupport.findLatestLegacyReferenceMapping(company, key)
              .orElseThrow(
                  () ->
                      new ApplicationException(
                              ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                              "Manual journal idempotency reservation missing")
                          .withDetail("referenceNumber", rawKey));
      mapping.setCanonicalReference(created.referenceNumber());
      mapping.setEntityId(created.id());
      accountingCoreSupport.journalReferenceMappingRepository.save(mapping);
    }
    return created;
  }

  public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
    return journalReversalService.reverseJournalEntry(entryId, request);
  }

  public List<JournalEntryDto> cascadeReverseRelatedEntries(
      Long primaryEntryId, JournalEntryReversalRequest request) {
    return journalReversalService.cascadeReverseRelatedEntries(primaryEntryId, request);
  }

  JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    return accountingCoreSupport.reverseClosingEntryForPeriodReopen(entry, period, reason);
  }

  private JournalEntryDto createJournalEntryForReversal(
      JournalEntryRequest payload, boolean allowClosedPeriodOverride) {
    if (!allowClosedPeriodOverride) {
      return createJournalEntry(payload);
    }
    return periodValidationService.runWithSystemEntryDateOverride(() -> createJournalEntry(payload));
  }
}
