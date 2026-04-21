package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocation;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerType;
import com.bigbrightpaints.erp.modules.accounting.dto.AutoSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PartnerSettlementResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationApplication;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
class SupplierSettlementService {

  private static final Logger log = LoggerFactory.getLogger(SupplierSettlementService.class);

  private final CompanyContextService companyContextService;
  private final JournalEntryService journalEntryService;
  private final SupplierPaymentService supplierPaymentService;
  private final SupplierRepository supplierRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  private final AccountResolutionService accountResolutionService;
  private final SettlementReferenceService settlementReferenceService;
  private final JournalReplayService journalReplayService;
  private final SettlementReplayValidationService settlementReplayValidationService;
  private final SettlementAllocationResolutionService settlementAllocationResolutionService;
  private final SettlementTotalsValidationService settlementTotalsValidationService;
  private final SettlementJournalLineDraftService settlementJournalLineDraftService;
  private final SettlementOutcomeService settlementOutcomeService;
  private final AccountingAuditService accountingAuditService;

  SupplierSettlementService(
      CompanyContextService companyContextService,
      JournalEntryService journalEntryService,
      SupplierPaymentService supplierPaymentService,
      SupplierRepository supplierRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      AccountResolutionService accountResolutionService,
      SettlementReferenceService settlementReferenceService,
      JournalReplayService journalReplayService,
      SettlementReplayValidationService settlementReplayValidationService,
      SettlementAllocationResolutionService settlementAllocationResolutionService,
      SettlementTotalsValidationService settlementTotalsValidationService,
      SettlementJournalLineDraftService settlementJournalLineDraftService,
      SettlementOutcomeService settlementOutcomeService,
      AccountingAuditService accountingAuditService) {
    this.companyContextService = companyContextService;
    this.journalEntryService = journalEntryService;
    this.supplierPaymentService = supplierPaymentService;
    this.supplierRepository = supplierRepository;
    this.accountingLookupService = accountingLookupService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
    this.accountResolutionService = accountResolutionService;
    this.settlementReferenceService = settlementReferenceService;
    this.journalReplayService = journalReplayService;
    this.settlementReplayValidationService = settlementReplayValidationService;
    this.settlementAllocationResolutionService = settlementAllocationResolutionService;
    this.settlementTotalsValidationService = settlementTotalsValidationService;
    this.settlementJournalLineDraftService = settlementJournalLineDraftService;
    this.settlementOutcomeService = settlementOutcomeService;
    this.accountingAuditService = accountingAuditService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  PartnerSettlementResponse settleSupplierInvoices(PartnerSettlementRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier =
        supplierRepository
            .lockByCompanyAndId(company, request.partnerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    Account payableAccount = accountResolutionService.requireSupplierPayable(supplier);
    String trimmedIdempotencyKey =
        settlementReferenceService.resolveSupplierSettlementIdempotencyKey(request);
    List<SettlementAllocationRequest> allocations =
        settlementAllocationResolutionService.resolveSupplierSettlementAllocations(
            company, supplier, request, trimmedIdempotencyKey);
    PartnerSettlementRequest requestForReplay =
        request.allocations() == allocations
            ? request
            : new PartnerSettlementRequest(
                request.partnerType(),
                request.partnerId(),
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
    trimmedIdempotencyKey =
        settlementReferenceService.resolveSupplierSettlementIdempotencyKey(requestForReplay);
    boolean replayCandidate =
        journalReplayService.hasExistingIdempotencyMapping(company, trimmedIdempotencyKey)
            || journalReplayService.hasExistingSettlementAllocations(
                company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      settlementTotalsValidationService.validateSupplierSettlementAllocations(allocations);
    }
    SettlementTotals totals =
        settlementTotalsValidationService.computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement to supplier " + supplier.getName();
    LocalDate requestedEffectiveSettlementDate =
        request.settlementDate() != null
            ? request.settlementDate()
            : accountResolutionService.currentDate(company);
    boolean settlementOverrideRequested =
        settlementTotalsValidationService.settlementOverrideRequested(totals);
    if (settlementOverrideRequested) {
      requireAdminExceptionReason("Settlement override", request.adminOverride(), request.memo());
    }
    String reference =
        settlementReferenceService.resolveSupplierSettlementReference(
            company, supplier, request, trimmedIdempotencyKey);
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, trimmedIdempotencyKey, reference, "SUPPLIER_SETTLEMENT");

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          journalReplayService.awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        SettlementLineDraft replayLineDraft =
            settlementJournalLineDraftService.buildSupplierSettlementLines(
                company, request, payableAccount, totals, memo, false);
        JournalEntry entry =
            journalReplayService.resolveReplayJournalEntry(
                trimmedIdempotencyKey, existingEntry, existingAllocations);
        journalReplayService.linkReferenceMapping(
            company, trimmedIdempotencyKey, entry, "SUPPLIER_SETTLEMENT");
        settlementReplayValidationService.validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            existingAllocations,
            allocations);
        settlementReplayValidationService.validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            replayLineDraft.lines());
        return settlementOutcomeService.buildSupplierSettlementResponse(existingAllocations);
      }
      throw journalReplayService.missingReservedPartnerAllocation(
          "Supplier settlement", trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        journalReplayService.findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntryFromExistingAllocations(
              company, reference, trimmedIdempotencyKey, existingAllocations);
      journalReplayService.linkReferenceMapping(
          company, trimmedIdempotencyKey, entry, "SUPPLIER_SETTLEMENT");
      settlementReplayValidationService.validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          existingAllocations,
          allocations);
      settlementReplayValidationService.validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          settlementJournalLineDraftService
              .buildSupplierSettlementLines(company, request, payableAccount, totals, memo, false)
              .lines());
      return settlementOutcomeService.buildSupplierSettlementResponse(existingAllocations);
    }

    supplier.requireTransactionalUsage("settle supplier invoices");
    SettlementLineDraft lineDraft =
        settlementJournalLineDraftService.buildSupplierSettlementLines(
            company, request, payableAccount, totals, memo, true);
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
      BigDecimal discount =
          settlementTotalsValidationService.normalizeNonNegative(
              allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff =
          settlementTotalsValidationService.normalizeNonNegative(
              allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      SettlementAllocationApplication applicationType =
          settlementTotalsValidationService.resolveSettlementApplicationType(allocation);

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
        if (purchase.getSupplier() == null
            || !purchase.getSupplier().getId().equals(supplier.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
        }
        settlementOutcomeService.enforceSupplierSettlementPostingParity(
            supplier.getId(), purchase, trimmedIdempotencyKey);
        BigDecimal cleared = applied;
        BigDecimal currentOutstanding =
            remainingByPurchase.getOrDefault(
                purchase.getId(), MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
        if (cleared.subtract(currentOutstanding).compareTo(AccountingConstants.ALLOCATION_TOLERANCE)
            > 0) {
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
      row.setMemo(
          settlementTotalsValidationService.encodeSettlementAllocationMemo(
              applicationType, allocation.memo()));
      settlementRows.add(row);
    }

    JournalEntryDto journalEntryDto =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                totalJournalAmount(lineDraft.lines()),
                null,
                null,
                memo,
                "SUPPLIER_SETTLEMENT",
                reference,
                null,
                toCreationLines(lineDraft.lines()),
                entryDate,
                null,
                supplier.getId(),
                request.adminOverride(),
                List.of()));
    JournalEntry journalEntry =
        accountingLookupService.requireJournalEntry(company, journalEntryDto.id());
    journalReplayService.linkReferenceMapping(
        company, trimmedIdempotencyKey, journalEntry, "SUPPLIER_SETTLEMENT");
    for (PartnerSettlementAllocation allocation : settlementRows) {
      allocation.setJournalEntry(journalEntry);
    }
    try {
      settlementAllocationRepository.saveAll(settlementRows);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Concurrent supplier settlement allocation conflict for idempotency key hash={} detected;"
              + " retrying in fresh transaction",
          journalReplayService.sanitizeIdempotencyLogValue(trimmedIdempotencyKey));
      throw ex;
    }
    for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
      RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
      if (purchase == null) {
        continue;
      }
      purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
      settlementOutcomeService.updatePurchaseStatus(purchase);
      touchedPurchases.add(purchase);
    }
    if (!touchedPurchases.isEmpty()) {
      rawMaterialPurchaseRepository.saveAll(touchedPurchases);
    }
    accountingAuditService.recordSupplierPaymentPostedEventSafe(
        journalEntry, supplier.getId(), cashAmount, trimmedIdempotencyKey);
    accountingAuditService.recordSettlementAllocatedEventSafe(
        journalEntry,
        PartnerType.SUPPLIER,
        supplier.getId(),
        totalApplied,
        settlementRows.size(),
        trimmedIdempotencyKey);

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        settlementOutcomeService.toSettlementAllocationSummaries(settlementRows);
    accountingAuditService.logSettlementAuditSuccess(
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
        settlementOverrideRequested ? accountingAuditService.resolveCurrentUsername() : null);

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

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  PartnerSettlementResponse autoSettleSupplier(Long supplierId, AutoSettlementRequest request) {
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
        accountResolutionService.resolveAutoSettlementCashAccountId(
            company, request.cashAccountId(), "supplier auto-settlement");
    List<SettlementAllocationRequest> allocations =
        settlementAllocationResolutionService.buildSupplierAutoSettlementAllocations(
            company, supplier, amount);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Auto-settlement for supplier " + supplier.getName();
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : settlementReferenceService.buildSupplierAutoSettlementReference(
                supplier, cashAccountId, amount, allocations);
    String idempotencyKey =
        StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : reference;
    SupplierPaymentRequest paymentRequest =
        new SupplierPaymentRequest(
            supplier.getId(), cashAccountId, amount, reference, memo, idempotencyKey, allocations);
    JournalEntryDto journalEntry = supplierPaymentService.recordSupplierPayment(paymentRequest);
    return settlementOutcomeService.buildAutoSettlementResponse(company, journalEntry);
  }

  private String requireAdminExceptionReason(
      String operation, Boolean adminOverride, String reason) {
    if (!Boolean.TRUE.equals(adminOverride)) {
      throw new ApplicationException(
          ErrorCode.AUTH_INSUFFICIENT_PERMISSIONS,
          operation + " requires an explicit admin override for this document");
    }
    if (StringUtils.hasText(reason)) {
      return reason.trim();
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, operation + " reason is required")
        .withDetail("field", "memo");
  }

  private List<JournalCreationRequest.LineRequest> toCreationLines(
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(
            line ->
                new JournalCreationRequest.LineRequest(
                    line.accountId(), line.debit(), line.credit(), line.description()))
        .toList();
  }

  private BigDecimal totalJournalAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(line -> line.debit() == null ? BigDecimal.ZERO : line.debit())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
