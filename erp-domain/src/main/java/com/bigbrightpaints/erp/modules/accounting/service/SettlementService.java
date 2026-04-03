package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

import jakarta.persistence.EntityManager;

@Service
public class SettlementService extends AccountingCoreEngineCore {

  private final JournalEntryService journalEntryService;
  private final DealerReceiptService dealerReceiptService;

  @Autowired
  public SettlementService(
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
      JournalEntryService journalEntryService,
      DealerReceiptService dealerReceiptService) {
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
    this.dealerReceiptService = dealerReceiptService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    SupplierPaymentRequest normalized = normalizeSupplierPaymentRequest(request);
    return recordSupplierPaymentInternal(normalized);
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
    DealerSettlementRequest normalized = normalizeDealerSettlementRequest(request);
    return settleDealerInvoicesInternal(normalized);
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  public PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
    AutoSettlementRequest normalized = normalizeAutoSettlementRequest("DEALER", dealerId, request);
    return autoSettleDealerInternal(dealerId, normalized);
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
    SupplierSettlementRequest normalized = normalizeSupplierSettlementRequest(request);
    return settleSupplierInvoicesInternal(normalized);
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  public PartnerSettlementResponse autoSettleSupplier(
      Long supplierId, AutoSettlementRequest request) {
    AutoSettlementRequest normalized =
        normalizeAutoSettlementRequest("SUPPLIER", supplierId, request);
    return autoSettleSupplierInternal(supplierId, normalized);
  }

  JournalEntryDto recordSupplierPaymentInternal(SupplierPaymentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierRepository
            .lockByCompanyAndId(company, request.supplierId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    Account payableAccount = requireSupplierPayable(supplier);
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    List<SettlementAllocationRequest> allocations = request.allocations();
    validatePaymentAllocations(allocations, amount, "supplier payment", false);
    Account cashAccount =
        requireCashAccountForSettlement(company, request.cashAccountId(), "supplier payment", false);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payment to supplier " + supplier.getName();
    String idempotencyKey =
        resolveReceiptIdempotencyKey(
            request.idempotencyKey(), request.referenceNumber(), "supplier payment");
    String reference =
        resolveSupplierPaymentReference(company, supplier, request.referenceNumber(), idempotencyKey);
    IdempotencyReservation reservation =
        reserveReferenceMapping(company, idempotencyKey, reference, ENTITY_TYPE_SUPPLIER_PAYMENT);

    if (!reservation.leader()) {
      JournalEntry existingEntry = awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations = awaitAllocations(company, idempotencyKey);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            resolveReplayJournalEntry(idempotencyKey, existingEntry, existingAllocations);
        linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
        validateSupplierPaymentIdempotency(
            idempotencyKey,
            supplier,
            cashAccount,
            payableAccount,
            amount,
            memo,
            entry,
            existingAllocations,
            allocations);
        return toDto(entry);
      }
      throw missingReservedPartnerAllocation(
          "Supplier payment", idempotencyKey, PartnerType.SUPPLIER, supplier.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        findAllocationsByIdempotencyKey(company, idempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          resolveReplayJournalEntryFromExistingAllocations(
              company, reference, idempotencyKey, existingAllocations);
      linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
      validateSupplierPaymentIdempotency(
          idempotencyKey,
          supplier,
          cashAccount,
          payableAccount,
          amount,
          memo,
          entry,
          existingAllocations,
          allocations);
      return toDto(entry);
    }

    supplier.requireTransactionalUsage("record supplier payments");
    cashAccount =
        requireCashAccountForSettlement(company, request.cashAccountId(), "supplier payment", true);
    JournalEntryRequest payload =
        new JournalEntryRequest(
            reference,
            currentDate(company),
            memo,
            null,
            supplier.getId(),
            Boolean.FALSE,
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    payableAccount.getId(), memo, amount, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    cashAccount.getId(), memo, BigDecimal.ZERO, amount)));
    JournalEntryDto entryDto = createJournalEntry(payload);
    JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryDto.id());
    linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
    existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
    if (!existingAllocations.isEmpty()) {
      validateSupplierPaymentIdempotency(
          idempotencyKey,
          supplier,
          cashAccount,
          payableAccount,
          amount,
          memo,
          entry,
          existingAllocations,
          allocations);
      return entryDto;
    }

    LocalDate entryDate = entry.getEntryDate();
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
    List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();
    Map<Long, BigDecimal> remainingByPurchase = new HashMap<>();
    Map<Long, RawMaterialPurchase> purchaseById = new HashMap<>();

    for (SettlementAllocationRequest allocation : allocations) {
      if (allocation.invoiceId() != null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Supplier payments cannot allocate to invoices");
      }
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      RawMaterialPurchase purchase = null;
      if (allocation.purchaseId() != null) {
        purchase =
            rawMaterialPurchaseRepository
                .lockByCompanyAndId(company, allocation.purchaseId())
                .orElseThrow(
                    () ->
                        new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Raw material purchase not found"));
        if (purchase.getSupplier() == null
            || !purchase.getSupplier().getId().equals(supplier.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
        }
        BigDecimal currentOutstanding =
            remainingByPurchase.getOrDefault(
                purchase.getId(), MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
        if (applied.compareTo(currentOutstanding) > 0) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT,
                  "Allocation exceeds purchase outstanding amount")
              .withDetail("purchaseId", purchase.getId())
              .withDetail("outstanding", currentOutstanding)
              .withDetail("applied", applied);
        }
        remainingByPurchase.put(
            purchase.getId(), currentOutstanding.subtract(applied).max(BigDecimal.ZERO));
        purchaseById.put(purchase.getId(), purchase);
      }

      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.SUPPLIER);
      row.setSupplier(supplier);
      row.setPurchase(purchase);
      row.setJournalEntry(entry);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(BigDecimal.ZERO);
      row.setWriteOffAmount(BigDecimal.ZERO);
      row.setFxDifferenceAmount(BigDecimal.ZERO);
      row.setIdempotencyKey(idempotencyKey);
      row.setMemo(allocation.memo());
      settlementRows.add(row);
    }
    try {
      settlementAllocationRepository.saveAll(settlementRows);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Concurrent supplier payment allocation conflict for idempotency key hash={} detected;"
              + " retrying in fresh transaction",
          sanitizeIdempotencyLogValue(idempotencyKey));
      throw ex;
    }
    for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
      RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
      if (purchase == null) {
        continue;
      }
      purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
      updatePurchaseStatus(purchase);
      touchedPurchases.add(purchase);
    }
    if (!touchedPurchases.isEmpty()) {
      rawMaterialPurchaseRepository.saveAll(touchedPurchases);
    }
    return entryDto;
  }

  PartnerSettlementResponse autoSettleDealerInternal(Long dealerId, AutoSettlementRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Auto-settlement request is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    Long cashAccountId =
        resolveAutoSettlementCashAccountId(company, request.cashAccountId(), "dealer auto-settlement");
    List<SettlementAllocationRequest> allocations =
        buildDealerAutoSettlementAllocations(company, dealer, amount);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Auto-settlement for dealer " + dealer.getName();
    DealerReceiptRequest receiptRequest =
        new DealerReceiptRequest(
            dealer.getId(),
            cashAccountId,
            amount,
            request.referenceNumber(),
            memo,
            request.idempotencyKey(),
            allocations);
    JournalEntryDto journalEntry =
        dealerReceiptService.recordDealerReceiptNormalized(receiptRequest);
    return buildAutoSettlementResponse(company, journalEntry);
  }

  PartnerSettlementResponse autoSettleSupplierInternal(
      Long supplierId, AutoSettlementRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Auto-settlement request is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierRepository
            .lockByCompanyAndId(company, supplierId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    Long cashAccountId =
        resolveAutoSettlementCashAccountId(
            company, request.cashAccountId(), "supplier auto-settlement");
    List<SettlementAllocationRequest> allocations =
        buildSupplierAutoSettlementAllocations(company, supplier, amount);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Auto-settlement for supplier " + supplier.getName();
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : buildSupplierAutoSettlementReference(company, supplier, cashAccountId, amount, allocations);
    String idempotencyKey =
        StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : reference;
    SupplierPaymentRequest paymentRequest =
        new SupplierPaymentRequest(
            supplier.getId(), cashAccountId, amount, reference, memo, idempotencyKey, allocations);
    JournalEntryDto journalEntry = recordSupplierPayment(paymentRequest);
    return buildAutoSettlementResponse(company, journalEntry);
  }

  PartnerSettlementResponse settleDealerInvoicesInternal(DealerSettlementRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, request.dealerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    Account receivableAccount = requireDealerReceivable(dealer);
    String trimmedIdempotencyKey = resolveDealerSettlementIdempotencyKey(company, request);
    List<SettlementAllocationRequest> allocations =
        resolveDealerSettlementAllocations(company, dealer, request, trimmedIdempotencyKey);
    DealerSettlementRequest requestForReplay =
        request.allocations() == allocations
            ? request
            : new DealerSettlementRequest(
                request.dealerId(),
                request.cashAccountId(),
                request.discountAccountId(),
                request.writeOffAccountId(),
                request.fxGainAccountId(),
                request.fxLossAccountId(),
                request.amount(),
                request.unappliedAmountApplication(),
                request.settlementDate(),
                request.referenceNumber(),
                request.memo(),
                request.idempotencyKey(),
                request.adminOverride(),
                allocations,
                request.payments());
    trimmedIdempotencyKey = resolveDealerSettlementIdempotencyKey(company, requestForReplay);
    if (!StringUtils.hasText(trimmedIdempotencyKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Idempotency key is required for dealer settlements");
    }
    boolean replayCandidate = hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      validateDealerSettlementAllocations(allocations);
    }
    SettlementTotals totals = computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement for dealer " + dealer.getName();
    LocalDate requestedEffectiveSettlementDate =
        request.settlementDate() != null ? request.settlementDate() : currentDate(company);
    boolean settlementOverrideRequested = settlementOverrideRequested(totals);
    if (settlementOverrideRequested) {
      requireAdminExceptionReason("Settlement override", request.adminOverride(), request.memo());
    }
    String reference =
        resolveDealerSettlementReference(company, dealer, request, trimmedIdempotencyKey);
    IdempotencyReservation reservation =
        reserveReferenceMapping(company, trimmedIdempotencyKey, reference, ENTITY_TYPE_DEALER_SETTLEMENT);
    if (reservation.leader()
        && !StringUtils.hasText(request.referenceNumber())
        && isReservedReference(reference)) {
      reference = referenceNumberService.dealerReceiptReference(company, dealer);
    }
    SettlementLineDraft lineDraft =
        buildDealerSettlementLines(company, request, receivableAccount, totals, memo, false);

    if (!reservation.leader()) {
      JournalEntry existingEntry = awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            resolveReplayJournalEntry(trimmedIdempotencyKey, existingEntry, existingAllocations);
        linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_DEALER_SETTLEMENT);
        validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            existingAllocations,
            allocations);
        validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            lineDraft.lines());
        return buildDealerSettlementResponse(existingAllocations);
      }
      throw missingReservedPartnerAllocation(
          "Dealer settlement", trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          resolveReplayJournalEntryFromExistingAllocations(
              company, reference, trimmedIdempotencyKey, existingAllocations);
      linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_DEALER_SETTLEMENT);
      validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          existingAllocations,
          allocations);
      validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          lineDraft.lines());
      return buildDealerSettlementResponse(existingAllocations);
    }

    lineDraft = buildDealerSettlementLines(company, request, receivableAccount, totals, memo, true);
    LocalDate entryDate = requestedEffectiveSettlementDate;

    BigDecimal totalApplied = totals.totalApplied();
    BigDecimal totalDiscount = totals.totalDiscount();
    BigDecimal totalWriteOff = totals.totalWriteOff();
    BigDecimal totalFxGain = totals.totalFxGain();
    BigDecimal totalFxLoss = totals.totalFxLoss();
    BigDecimal cashAmount = lineDraft.cashAmount();
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
    List<Invoice> touchedInvoices = new ArrayList<>();
    Map<Long, BigDecimal> remainingByInvoice = new HashMap<>();

    for (SettlementAllocationRequest allocation : allocations) {
      if (allocation.purchaseId() != null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Dealer settlements cannot allocate to purchases");
      }
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      SettlementAllocationApplication applicationType = resolveSettlementApplicationType(allocation);

      if (applicationType.isUnapplied()
          && (discount.compareTo(BigDecimal.ZERO) > 0
              || writeOff.compareTo(BigDecimal.ZERO) > 0
              || fxAdjustment.compareTo(BigDecimal.ZERO) != 0)) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "On-account dealer settlement allocations cannot include discount/write-off/FX"
                + " adjustments");
      }

      Invoice invoice = null;
      if (!applicationType.isUnapplied()) {
        invoice =
            invoiceRepository
                .lockByCompanyAndId(company, allocation.invoiceId())
                .orElseThrow(
                    () ->
                        new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
        if (invoice.getDealer() == null || !invoice.getDealer().getId().equals(dealer.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
        }
        enforceSettlementCurrency(company, invoice);

        BigDecimal cleared = applied;
        BigDecimal currentOutstanding =
            remainingByInvoice.getOrDefault(
                invoice.getId(), MoneyUtils.zeroIfNull(invoice.getOutstandingAmount()));
        if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT,
                  "Settlement allocation exceeds invoice outstanding amount")
              .withDetail("invoiceId", invoice.getId())
              .withDetail("outstandingAmount", currentOutstanding)
              .withDetail("appliedAmount", cleared);
        }
        remainingByInvoice.put(
            invoice.getId(), currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
      }

      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.DEALER);
      row.setDealer(dealer);
      row.setInvoice(invoice);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(discount);
      row.setWriteOffAmount(writeOff);
      row.setFxDifferenceAmount(fxAdjustment);
      row.setIdempotencyKey(trimmedIdempotencyKey);
      if (invoice != null && invoice.getCurrency() != null) {
        row.setCurrency(invoice.getCurrency());
      }
      row.setMemo(encodeSettlementAllocationMemo(applicationType, allocation.memo()));
      settlementRows.add(row);
    }

    JournalEntryDto journalEntryDto =
        createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                dealer.getId(),
                null,
                request.adminOverride(),
                lineDraft.lines(),
                null,
                null,
                ENTITY_TYPE_DEALER_SETTLEMENT,
                reference,
                null,
                List.of()));

    JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
    linkReferenceMapping(company, trimmedIdempotencyKey, journalEntry, ENTITY_TYPE_DEALER_SETTLEMENT);
    for (PartnerSettlementAllocation allocation : settlementRows) {
      allocation.setJournalEntry(journalEntry);
    }
    try {
      settlementAllocationRepository.saveAll(settlementRows);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Concurrent dealer settlement allocation conflict for idempotency key hash={} detected;"
              + " retrying in fresh transaction",
          sanitizeIdempotencyLogValue(trimmedIdempotencyKey));
      throw ex;
    }
    for (PartnerSettlementAllocation row : settlementRows) {
      Invoice invoice = row.getInvoice();
      if (invoice == null) {
        continue;
      }
      String settlementRef = reference + "-INV-" + invoice.getId();
      invoiceSettlementPolicy.applySettlement(invoice, row.getAllocationAmount(), settlementRef);
      dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
      touchedInvoices.add(invoice);
    }
    if (!touchedInvoices.isEmpty()) {
      invoiceRepository.saveAll(touchedInvoices);
    }

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        toSettlementAllocationSummaries(settlementRows);
    logSettlementAuditSuccess(
        PartnerType.DEALER,
        dealer.getId(),
        journalEntryDto,
        entryDate,
        trimmedIdempotencyKey,
        settlementRows.size(),
        totalApplied,
        cashAmount,
        totalDiscount,
        totalWriteOff,
        totalFxGain,
        totalFxLoss,
        settlementOverrideRequested,
        settlementOverrideRequested ? memo : null,
        settlementOverrideRequested ? resolveCurrentUsername() : null);

    return new PartnerSettlementResponse(
        journalEntryDto,
        totalApplied,
        cashAmount,
        totalDiscount,
        totalWriteOff,
        totalFxGain,
        totalFxLoss,
        allocationSummaries);
  }

  PartnerSettlementResponse settleSupplierInvoicesInternal(SupplierSettlementRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierRepository
            .lockByCompanyAndId(company, request.supplierId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    Account payableAccount = requireSupplierPayable(supplier);
    String trimmedIdempotencyKey = resolveSupplierSettlementIdempotencyKey(request);
    List<SettlementAllocationRequest> allocations =
        resolveSupplierSettlementAllocations(company, supplier, request, trimmedIdempotencyKey);
    SupplierSettlementRequest requestForReplay =
        request.allocations() == allocations
            ? request
            : new SupplierSettlementRequest(
                request.supplierId(),
                request.cashAccountId(),
                request.discountAccountId(),
                request.writeOffAccountId(),
                request.fxGainAccountId(),
                request.fxLossAccountId(),
                request.amount(),
                request.unappliedAmountApplication(),
                request.settlementDate(),
                request.referenceNumber(),
                request.memo(),
                request.idempotencyKey(),
                request.adminOverride(),
                allocations);
    trimmedIdempotencyKey = resolveSupplierSettlementIdempotencyKey(requestForReplay);
    boolean replayCandidate =
        hasExistingIdempotencyMapping(company, trimmedIdempotencyKey)
            || hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      validateSupplierSettlementAllocations(allocations);
    }
    SettlementTotals totals = computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement to supplier " + supplier.getName();
    LocalDate requestedEffectiveSettlementDate =
        request.settlementDate() != null ? request.settlementDate() : currentDate(company);
    boolean settlementOverrideRequested = settlementOverrideRequested(totals);
    if (settlementOverrideRequested) {
      requireAdminExceptionReason("Settlement override", request.adminOverride(), request.memo());
    }
    String reference =
        resolveSupplierSettlementReference(company, supplier, request, trimmedIdempotencyKey);
    IdempotencyReservation reservation =
        reserveReferenceMapping(
            company, trimmedIdempotencyKey, reference, ENTITY_TYPE_SUPPLIER_SETTLEMENT);

    if (!reservation.leader()) {
      JournalEntry existingEntry = awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        SettlementLineDraft replayLineDraft =
            buildSupplierSettlementLines(company, request, payableAccount, totals, memo, false);
        JournalEntry entry =
            resolveReplayJournalEntry(trimmedIdempotencyKey, existingEntry, existingAllocations);
        linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
        validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            existingAllocations,
            allocations);
        validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            replayLineDraft.lines());
        return buildSupplierSettlementResponse(existingAllocations);
      }
      throw missingReservedPartnerAllocation(
          "Supplier settlement", trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          resolveReplayJournalEntryFromExistingAllocations(
              company, reference, trimmedIdempotencyKey, existingAllocations);
      linkReferenceMapping(
          company, trimmedIdempotencyKey, entry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
      validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          existingAllocations,
          allocations);
      validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          buildSupplierSettlementLines(company, request, payableAccount, totals, memo, false).lines());
      return buildSupplierSettlementResponse(existingAllocations);
    }

    supplier.requireTransactionalUsage("settle supplier invoices");
    SettlementLineDraft lineDraft =
        buildSupplierSettlementLines(company, request, payableAccount, totals, memo, true);
    LocalDate entryDate = requestedEffectiveSettlementDate;
    BigDecimal totalApplied = totals.totalApplied();
    BigDecimal totalDiscount = totals.totalDiscount();
    BigDecimal totalWriteOff = totals.totalWriteOff();
    BigDecimal totalFxGain = totals.totalFxGain();
    BigDecimal totalFxLoss = totals.totalFxLoss();
    BigDecimal cashAmount = lineDraft.cashAmount();
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
    List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();
    Map<Long, BigDecimal> remainingByPurchase = new HashMap<>();
    Map<Long, RawMaterialPurchase> purchaseById = new HashMap<>();

    for (SettlementAllocationRequest allocation : allocations) {
      if (allocation.invoiceId() != null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Supplier settlements cannot allocate to invoices");
      }
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      SettlementAllocationApplication applicationType = resolveSettlementApplicationType(allocation);

      if (applicationType.isUnapplied()
          && (discount.compareTo(BigDecimal.ZERO) > 0
              || writeOff.compareTo(BigDecimal.ZERO) > 0
              || fxAdjustment.compareTo(BigDecimal.ZERO) != 0)) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "On-account supplier settlement allocations cannot include discount/write-off/FX"
                + " adjustments");
      }

      RawMaterialPurchase purchase = null;
      if (!applicationType.isUnapplied()) {
        purchase =
            rawMaterialPurchaseRepository
                .lockByCompanyAndId(company, allocation.purchaseId())
                .orElseThrow(
                    () ->
                        new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Raw material purchase not found"));
        if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
        }
        enforceSupplierSettlementPostingParity(
            company, supplier.getId(), purchase, trimmedIdempotencyKey);
        BigDecimal cleared = applied;
        BigDecimal currentOutstanding =
            remainingByPurchase.getOrDefault(
                purchase.getId(), MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
        if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT,
                  "Settlement allocation exceeds purchase outstanding amount")
              .withDetail("purchaseId", purchase.getId())
              .withDetail("outstandingAmount", currentOutstanding)
              .withDetail("appliedAmount", cleared);
        }
        remainingByPurchase.put(
            purchase.getId(), currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
        purchaseById.put(purchase.getId(), purchase);
      }

      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.SUPPLIER);
      row.setSupplier(supplier);
      row.setPurchase(purchase);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(discount);
      row.setWriteOffAmount(writeOff);
      row.setFxDifferenceAmount(fxAdjustment);
      row.setIdempotencyKey(trimmedIdempotencyKey);
      row.setMemo(encodeSettlementAllocationMemo(applicationType, allocation.memo()));
      settlementRows.add(row);
    }

    JournalEntryDto journalEntryDto =
        createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                supplier.getId(),
                request.adminOverride(),
                lineDraft.lines(),
                null,
                null,
                ENTITY_TYPE_SUPPLIER_SETTLEMENT,
                reference,
                null,
                List.of()));
    JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
    linkReferenceMapping(
        company, trimmedIdempotencyKey, journalEntry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
    for (PartnerSettlementAllocation allocation : settlementRows) {
      allocation.setJournalEntry(journalEntry);
    }
    try {
      settlementAllocationRepository.saveAll(settlementRows);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Concurrent supplier settlement allocation conflict for idempotency key hash={} detected;"
              + " retrying in fresh transaction",
          sanitizeIdempotencyLogValue(trimmedIdempotencyKey));
      throw ex;
    }
    for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
      RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
      if (purchase == null) {
        continue;
      }
      purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
      updatePurchaseStatus(purchase);
      touchedPurchases.add(purchase);
    }
    if (!touchedPurchases.isEmpty()) {
      rawMaterialPurchaseRepository.saveAll(touchedPurchases);
    }

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        toSettlementAllocationSummaries(settlementRows);
    logSettlementAuditSuccess(
        PartnerType.SUPPLIER,
        supplier.getId(),
        journalEntryDto,
        entryDate,
        trimmedIdempotencyKey,
        settlementRows.size(),
        totalApplied,
        cashAmount,
        totalDiscount,
        totalWriteOff,
        totalFxGain,
        totalFxLoss,
        settlementOverrideRequested,
        settlementOverrideRequested ? memo : null,
        settlementOverrideRequested ? resolveCurrentUsername() : null);

    return new PartnerSettlementResponse(
        journalEntryDto,
        totalApplied,
        cashAmount,
        totalDiscount,
        totalWriteOff,
        totalFxGain,
        totalFxLoss,
        allocationSummaries);
  }

  @Override
  public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
    return journalEntryService.createJournalEntry(request);
  }

  @Override
  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
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

  private DealerSettlementRequest normalizeDealerSettlementRequest(
      DealerSettlementRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.dealerId(), "dealerId");
    return new DealerSettlementRequest(
        request.dealerId(),
        request.cashAccountId(),
        request.discountAccountId(),
        request.writeOffAccountId(),
        request.fxGainAccountId(),
        request.fxLossAccountId(),
        positiveAmountOrNull(request.amount()),
        normalizeUnappliedApplication(request.unappliedAmountApplication()),
        request.settlementDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()),
        request.allocations(),
        request.payments());
  }

  private SupplierSettlementRequest normalizeSupplierSettlementRequest(
      SupplierSettlementRequest request) {
    ValidationUtils.requireNotNull(request, "request");
    ValidationUtils.requireNotNull(request.supplierId(), "supplierId");
    return new SupplierSettlementRequest(
        request.supplierId(),
        request.cashAccountId(),
        request.discountAccountId(),
        request.writeOffAccountId(),
        request.fxGainAccountId(),
        request.fxLossAccountId(),
        positiveAmountOrNull(request.amount()),
        normalizeUnappliedApplication(request.unappliedAmountApplication()),
        request.settlementDate(),
        normalizeText(request.referenceNumber()),
        normalizeText(request.memo()),
        normalizeText(request.idempotencyKey()),
        Boolean.TRUE.equals(request.adminOverride()),
        request.allocations());
  }

  private AutoSettlementRequest normalizeAutoSettlementRequest(
      String partnerType, Long partnerId, AutoSettlementRequest request) {
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

  private String normalizeText(String value) {
    String normalized = IdempotencyUtils.normalizeToken(value);
    return normalized.isBlank() ? null : normalized;
  }

  private BigDecimal positiveAmountOrNull(BigDecimal value) {
    if (value == null) {
      return null;
    }
    ValidationUtils.requirePositive(value, "amount");
    return value.abs();
  }

  private SettlementAllocationApplication normalizeUnappliedApplication(
      SettlementAllocationApplication value) {
    return value;
  }
}
