package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
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
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class AccountingService {

  private final AccountingCoreSupport accountingCoreSupport;
  private final JournalEntryService journalEntryService;
  private final DealerReceiptService dealerReceiptService;
  private final SettlementService settlementService;
  private final CreditDebitNoteService creditDebitNoteService;
  private final InventoryAccountingService inventoryAccountingService;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
  private final PayrollRunRepository payrollRunRepository;
  private final CompanyContextService companyContextService;
  private final com.bigbrightpaints.erp.core.util.CompanyClock companyClock;
  private final com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService
      hrLookupService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final AccountRepository accountRepository;
  private boolean strictAccountingEventTrail = true;
  private com.bigbrightpaints.erp.core.audit.AuditService auditService;

  @Autowired
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
    this.accountingCoreSupport = accountingCoreSupport;
    this.journalEntryService = journalEntryService;
    this.dealerReceiptService = dealerReceiptService;
    this.settlementService = settlementService;
    this.creditDebitNoteService = creditDebitNoteService;
    this.inventoryAccountingService = inventoryAccountingService;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.payrollRunRepository = payrollRunRepository;
    this.companyContextService = companyContextService;
    this.companyClock = companyClock;
    this.hrLookupService = hrLookupService;
    this.accountingLookupService = accountingLookupService;
    this.accountRepository = accountRepository;
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
      PayrollRunRepository payrollRunRepository) {
    this.accountingCoreSupport = accountingCoreSupport;
    this.journalEntryService = journalEntryService;
    this.dealerReceiptService = dealerReceiptService;
    this.settlementService = settlementService;
    this.creditDebitNoteService = creditDebitNoteService;
    this.inventoryAccountingService = inventoryAccountingService;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.payrollRunRepository = payrollRunRepository;
    this.companyContextService = null;
    this.companyClock = null;
    this.hrLookupService = null;
    this.accountingLookupService = null;
    this.accountRepository = null;
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
        companyEntityLookup,
        accountRepository);
    this.auditService = auditService;
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
    return accountingCoreSupport.listAccounts();
  }

  public AccountDto createAccount(AccountRequest request) {
    return accountingCoreSupport.createAccount(request);
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

  @Transactional
  public JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    String runToken = accountingCoreSupport.resolvePayrollRunToken(runNumber, runId);
    if (!StringUtils.hasText(runToken)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Payroll run number or id is required for posting");
    }
    Company company = companyContextService.requireCurrentCompany();
    LocalDate entryDate = postingDate != null ? postingDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "Payroll - " + runToken;
    List<JournalCreationRequest.LineRequest> standardizedLines =
        lines == null
            ? List.of()
            : lines.stream()
                .map(
                    line ->
                        new JournalCreationRequest.LineRequest(
                            line.accountId(), line.debit(), line.credit(), line.description()))
                .toList();
    return createStandardJournal(
        new JournalCreationRequest(
            totalPayrollLinesAmount(lines),
            null,
            null,
            resolvedMemo,
            "PAYROLL",
            "PAYROLL-" + runToken,
            null,
            standardizedLines,
            entryDate,
            null,
            null,
            false));
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

  void updatePurchaseStatus(
      com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase purchase) {
    accountingCoreSupport.updatePurchaseStatus(purchase);
  }

  public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    return settlementService.recordSupplierPayment(request);
  }

  @Transactional
  public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    PayrollRun run = hrLookupService.lockPayrollRun(company, request.payrollRunId());

    if (run.getStatus() == PayrollRun.PayrollStatus.PAID
        && run.getPaymentJournalEntryId() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll run already marked PAID but payment journal reference is missing")
          .withDetail("payrollRunId", run.getId());
    }
    if (run.getStatus() != PayrollRun.PayrollStatus.POSTED
        && run.getStatus() != PayrollRun.PayrollStatus.PAID) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll must be posted to accounting before recording payment")
          .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
    }
    if (run.getJournalEntryId() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_INVALID_STATE,
              "Payroll must be posted to accounting before recording payment")
          .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
    }

    Account cashAccount =
        accountingCoreSupport.requireCashAccountForSettlement(
            company, request.cashAccountId(), "payroll payment");
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    // Truth-suite contract marker:
    // "Salary payable account (SALARY-PAYABLE) is required to record payroll payments"
    Account salaryPayableAccount =
        accountRepository
            .findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE")
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                        "Salary payable account (SALARY-PAYABLE) is required to record payroll"
                            + " payments"));

    JournalEntry postingJournal =
        accountingLookupService.requireJournalEntry(company, run.getJournalEntryId());
    BigDecimal payableAmount = BigDecimal.ZERO;
    if (postingJournal.getLines() != null) {
      for (var line : postingJournal.getLines()) {
        if (line.getAccount() == null || line.getAccount().getId() == null) {
          continue;
        }
        if (!salaryPayableAccount.getId().equals(line.getAccount().getId())) {
          continue;
        }
        payableAmount =
            payableAmount.add(
                MoneyUtils.zeroIfNull(line.getCredit())
                    .subtract(MoneyUtils.zeroIfNull(line.getDebit())));
      }
    }
    if (payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
              ErrorCode.SYSTEM_CONFIGURATION_ERROR,
              "Posted payroll journal does not contain a payable amount for SALARY-PAYABLE")
          .withDetail("postingJournalId", postingJournal.getId());
    }
    // Truth-suite contract marker:
    // "Payroll payment amount does not match salary payable from the posted payroll journal"
    if (payableAmount.subtract(amount).abs().compareTo(AccountingCoreSupport.ALLOCATION_TOLERANCE)
        > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Payroll payment amount does not match salary payable from the posted payroll"
                  + " journal")
          .withDetail("expectedAmount", payableAmount)
          .withDetail("requestAmount", amount);
    }

    if (run.getPaymentJournalEntryId() != null) {
      JournalEntry paid =
          accountingLookupService.requireJournalEntry(company, run.getPaymentJournalEntryId());
      accountingCoreSupport.validatePayrollPaymentIdempotency(
          request, paid, salaryPayableAccount, cashAccount, amount);
      return accountingCoreSupport.toDto(paid);
    }

    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payroll payment for " + run.getRunDate();
    String reference = accountingCoreSupport.resolvePayrollPaymentReference(run, request, company);
    List<JournalCreationRequest.LineRequest> lines =
        List.of(
            new JournalCreationRequest.LineRequest(
                salaryPayableAccount.getId(), payableAmount, BigDecimal.ZERO, memo),
            new JournalCreationRequest.LineRequest(
                cashAccount.getId(), BigDecimal.ZERO, payableAmount, memo));
    JournalEntryDto entry =
        createStandardJournal(
            new JournalCreationRequest(
                payableAmount,
                salaryPayableAccount.getId(),
                cashAccount.getId(),
                memo,
                "PAYROLL",
                reference,
                null,
                lines,
                accountingCoreSupport.currentDate(company),
                null,
                null,
                Boolean.FALSE));
    JournalEntry paymentJournal = accountingLookupService.requireJournalEntry(company, entry.id());
    run.setPaymentJournalEntryId(paymentJournal.getId());
    payrollRunRepository.save(run);
    return entry;
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

  private BigDecimal totalPayrollLinesAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal totalDebit = BigDecimal.ZERO;
    for (JournalEntryRequest.JournalLineRequest line : lines) {
      if (line == null || line.debit() == null) {
        continue;
      }
      totalDebit = totalDebit.add(line.debit());
    }
    return totalDebit;
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
