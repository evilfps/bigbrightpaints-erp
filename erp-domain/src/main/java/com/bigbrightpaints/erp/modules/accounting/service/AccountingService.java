package com.bigbrightpaints.erp.modules.accounting.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccrualRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BadDebtWriteOffRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.CreditNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DebitNoteRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class AccountingService {

  private final AccountCatalogService accountCatalogService;
  private final JournalEntryService journalEntryService;
  private final DealerReceiptService dealerReceiptService;
  private final SettlementService settlementService;
  private final CreditDebitNoteService creditDebitNoteService;
  private final InventoryAccountingService inventoryAccountingService;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
  private final PayrollAccountingService payrollAccountingService;
  private boolean strictAccountingEventTrail = true;
  private com.bigbrightpaints.erp.core.audit.AuditService auditService;

  @Autowired
  public AccountingService(
      AccountCatalogService accountCatalogService,
      JournalEntryService journalEntryService,
      DealerReceiptService dealerReceiptService,
      SettlementService settlementService,
      CreditDebitNoteService creditDebitNoteService,
      InventoryAccountingService inventoryAccountingService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      PayrollAccountingService payrollAccountingService) {
    this.accountCatalogService = accountCatalogService;
    this.journalEntryService = journalEntryService;
    this.dealerReceiptService = dealerReceiptService;
    this.settlementService = settlementService;
    this.creditDebitNoteService = creditDebitNoteService;
    this.inventoryAccountingService = inventoryAccountingService;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.payrollAccountingService = payrollAccountingService;
    this.auditService = null;
  }

  public AccountingService(
      AccountingCoreSupport accountingCoreSupport,
      JournalEntryService journalEntryService,
      DealerReceiptService dealerReceiptService,
      SettlementService settlementService,
      CreditDebitNoteService creditDebitNoteService,
      InventoryAccountingService inventoryAccountingService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      PayrollRunRepository payrollRunRepository,
      CompanyContextService companyContextService,
      com.bigbrightpaints.erp.core.util.CompanyClock companyClock,
      com.bigbrightpaints.erp.core.util.CompanyEntityLookup companyEntityLookup,
      AccountRepository accountRepository) {
    this(
        accountingCoreSupport,
        journalEntryService,
        dealerReceiptService,
        settlementService,
        creditDebitNoteService,
        inventoryAccountingService,
        accountingFacadeProvider,
        payrollRunRepository,
        companyContextService,
        companyClock,
        com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService.fromLegacy(
            companyEntityLookup),
        CompanyScopedAccountingLookupService.fromLegacy(companyEntityLookup),
        accountRepository);
  }

  public AccountingService(
      AccountingCoreSupport accountingCoreSupport,
      JournalEntryService journalEntryService,
      DealerReceiptService dealerReceiptService,
      SettlementService settlementService,
      CreditDebitNoteService creditDebitNoteService,
      InventoryAccountingService inventoryAccountingService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      PayrollRunRepository payrollRunRepository,
      CompanyContextService companyContextService,
      com.bigbrightpaints.erp.core.util.CompanyClock companyClock,
      com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService hrLookupService,
      CompanyScopedAccountingLookupService accountingLookupService,
      AccountRepository accountRepository) {
    this(
        new AccountCatalogService(accountingCoreSupport),
        journalEntryService,
        dealerReceiptService,
        settlementService,
        creditDebitNoteService,
        inventoryAccountingService,
        accountingFacadeProvider,
        new PayrollAccountingService(
            accountingCoreSupport,
            payrollRunRepository,
            companyContextService,
            companyClock,
            hrLookupService,
            accountingLookupService,
            accountRepository,
            journalEntryService));
  }

  public AccountingService(
      com.bigbrightpaints.erp.modules.company.service.CompanyContextService companyContextService,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository
          journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      PayrollRunRepository payrollRunRepository,
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
      DealerReceiptService dealerReceiptService,
      SettlementService settlementService,
      CreditDebitNoteService creditDebitNoteService,
      InventoryAccountingService inventoryAccountingService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider) {
    this(
        legacySupport(
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
            accountingEventStore),
        journalEntryService,
        dealerReceiptService,
        settlementService,
        creditDebitNoteService,
        inventoryAccountingService,
        accountingFacadeProvider,
        payrollRunRepository,
        companyContextService,
        companyClock,
        com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService.fromLegacy(
            companyEntityLookup),
        CompanyScopedAccountingLookupService.fromLegacy(companyEntityLookup),
        accountRepository);
    this.auditService = auditService;
  }

  private static AccountingCoreSupport legacySupport(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository
          journalEntryRepository,
      DealerLedgerService dealerLedgerService,
      SupplierLedgerService supplierLedgerService,
      PayrollRunRepository payrollRunRepository,
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
      com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore accountingEventStore) {
    return new AccountingCoreSupport(
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
  }

  @SuppressWarnings("unused")
  private void handleAccountingEventTrailFailure(
      String operation, String journalReference, Exception ex) {
    java.util.Map<String, String> metadata = new java.util.HashMap<>();
    metadata.put("eventTrailOperation", operation);
    String policy = strictAccountingEventTrail ? "STRICT" : "BEST_EFFORT";
    metadata.put("policy", policy);
    if (StringUtils.hasText(journalReference)) {
      metadata.put("journalReference", journalReference);
    }
    String failureCode = AccountingEventTrailAlertRoutingPolicy.ACCOUNTING_EVENT_TRAIL_FAILURE_CODE;
    String errorCategory = classifyEventTrailFailure(ex);
    com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema.applyRequiredFields(
        metadata,
        failureCode,
        errorCategory,
        AccountingEventTrailAlertRoutingPolicy.ROUTING_VERSION,
        AccountingEventTrailAlertRoutingPolicy.resolveRoute(failureCode, errorCategory, policy));
    metadata.put("errorType", ex.getClass().getSimpleName());
    if (auditService != null) {
      auditService.logFailure(
          com.bigbrightpaints.erp.core.audit.AuditEvent.INTEGRATION_FAILURE, metadata);
    }
    if (strictAccountingEventTrail) {
      throw new ApplicationException(
              ErrorCode.SYSTEM_DATABASE_ERROR, "Accounting event trail persistence failed", ex)
          .withDetail("eventTrailOperation", operation)
          .withDetail("journalReference", journalReference);
    }
  }

  private String classifyEventTrailFailure(Exception ex) {
    if (ex instanceof ApplicationException appEx) {
      return classifyApplicationEventTrailFailure(appEx.getErrorCode());
    }
    if (ex instanceof IllegalArgumentException) {
      return "VALIDATION";
    }
    if (ex instanceof org.springframework.dao.DataIntegrityViolationException) {
      return "DATA_INTEGRITY";
    }
    return "PERSISTENCE";
  }

  private String classifyApplicationEventTrailFailure(ErrorCode errorCode) {
    if (errorCode == null) {
      return "PERSISTENCE";
    }
    if (errorCode.name().startsWith("VALIDATION_")) {
      return "VALIDATION";
    }
    if (errorCode.name().startsWith("BUSINESS_CONFLICT")
        || errorCode.name().startsWith("DATA_INTEGRITY_")) {
      return "DATA_INTEGRITY";
    }
    return "PERSISTENCE";
  }

  public List<AccountDto> listAccounts() {
    return accountCatalogService.listAccounts();
  }

  public AccountDto createAccount(AccountRequest request) {
    return accountCatalogService.createAccount(request);
  }

  public List<JournalEntryDto> listJournalEntries(
      Long dealerId, Long supplierId, int page, int size) {
    return journalEntryService.listJournalEntries(dealerId, supplierId, page, size);
  }

  public List<JournalEntryDto> listJournalEntries(Long dealerId) {
    return journalEntryService.listJournalEntries(dealerId);
  }

  public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
    return journalEntryService.listJournalEntriesByReferencePrefix(prefix);
  }

  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryService.createJournalEntry(request);
  }

  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
  }

  public JournalEntryDto createManualJournal(ManualJournalRequest request) {
    return resolveAccountingFacade().createManualJournal(request);
  }

  public PageResponse<JournalListItemDto> listJournals(
      LocalDate fromDate,
      LocalDate toDate,
      String journalType,
      String sourceModule,
      int page,
      int size) {
    return journalEntryService.listJournals(
        fromDate, toDate, journalType, sourceModule, page, size);
  }

  public JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return payrollAccountingService.postPayrollRun(runNumber, runId, postingDate, memo, lines);
  }

  public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
    return journalEntryService.reverseJournalEntry(entryId, request);
  }

  JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    return journalEntryService.reverseClosingEntryForPeriodReopen(entry, period, reason);
  }

  public JournalEntryDto createManualJournalEntry(
      JournalEntryRequest request, String idempotencyKey) {
    return resolveAccountingFacade().createManualJournalEntry(request, idempotencyKey);
  }

  public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
    return dealerReceiptService.recordDealerReceipt(request);
  }

  public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
    return dealerReceiptService.recordDealerReceiptSplit(request);
  }

  public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    return settlementService.recordSupplierPayment(request);
  }

  public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    return payrollAccountingService.recordPayrollPayment(request);
  }

  public PartnerSettlementResponse settleDealerInvoices(PartnerSettlementRequest request) {
    return settlementService.settleDealerInvoices(request);
  }

  public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
    return settlementService.autoSettleDealer(dealerId, request);
  }

  public PartnerSettlementResponse settleSupplierInvoices(PartnerSettlementRequest request) {
    return settlementService.settleSupplierInvoices(request);
  }

  public PartnerSettlementResponse autoSettleSupplier(
      Long supplierId, AutoSettlementRequest request) {
    return settlementService.autoSettleSupplier(supplierId, request);
  }

  public JournalEntryDto postCreditNote(CreditNoteRequest request) {
    return creditDebitNoteService.postCreditNote(request);
  }

  public JournalEntryDto postDebitNote(DebitNoteRequest request) {
    return creditDebitNoteService.postDebitNote(request);
  }

  public JournalEntryDto postAccrual(AccrualRequest request) {
    return creditDebitNoteService.postAccrual(request);
  }

  public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
    return creditDebitNoteService.writeOffBadDebt(request);
  }

  public JournalEntryDto recordLandedCost(LandedCostRequest request) {
    return inventoryAccountingService.recordLandedCost(request);
  }

  public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
    return inventoryAccountingService.revalueInventory(request);
  }

  public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
    return inventoryAccountingService.adjustWip(request);
  }

  private AccountingFacade resolveAccountingFacade() {
    AccountingFacade facade =
        accountingFacadeProvider != null ? accountingFacadeProvider.getIfAvailable() : null;
    if (facade == null) {
      throw new IllegalStateException("AccountingFacade is required");
    }
    return facade;
  }
}
