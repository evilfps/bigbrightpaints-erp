package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

  private final AccountingCoreSupport accountingCoreSupport;
  private final CompanyContextService companyContextService;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
  private final SupplierRepository supplierRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  SupplierSettlementService(
      AccountingCoreSupport accountingCoreSupport,
      CompanyContextService companyContextService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      SupplierRepository supplierRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this.accountingCoreSupport = accountingCoreSupport;
    this.companyContextService = companyContextService;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.supplierRepository = supplierRepository;
    this.accountingLookupService = accountingLookupService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
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
            .lockByCompanyAndId(company, request.supplierId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    Account payableAccount = accountingCoreSupport.requireSupplierPayable(supplier);
    String trimmedIdempotencyKey =
        accountingCoreSupport.resolveSupplierSettlementIdempotencyKey(request);
    List<SettlementAllocationRequest> allocations =
        accountingCoreSupport.resolveSupplierSettlementAllocations(
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
                allocations,
                request.payments());
    trimmedIdempotencyKey =
        accountingCoreSupport.resolveSupplierSettlementIdempotencyKey(requestForReplay);
    boolean replayCandidate =
        accountingCoreSupport.hasExistingIdempotencyMapping(company, trimmedIdempotencyKey)
            || accountingCoreSupport.hasExistingSettlementAllocations(
                company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      accountingCoreSupport.validateSupplierSettlementAllocations(allocations);
    }
    AccountingCoreSupport.SettlementTotals totals =
        accountingCoreSupport.computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement to supplier " + supplier.getName();
    LocalDate requestedEffectiveSettlementDate =
        request.settlementDate() != null
            ? request.settlementDate()
            : accountingCoreSupport.currentDate(company);
    boolean settlementOverrideRequested = accountingCoreSupport.settlementOverrideRequested(totals);
    if (settlementOverrideRequested) {
      accountingCoreSupport.requireAdminExceptionReason(
          "Settlement override", request.adminOverride(), request.memo());
    }
    String reference =
        accountingCoreSupport.resolveSupplierSettlementReference(
            company, supplier, request, trimmedIdempotencyKey);
    AccountingCoreSupport.IdempotencyReservation reservation =
        accountingCoreSupport.reserveReferenceMapping(
            company,
            trimmedIdempotencyKey,
            reference,
            AccountingCoreSupport.ENTITY_TYPE_SUPPLIER_SETTLEMENT);

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          accountingCoreSupport.awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          accountingCoreSupport.awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        AccountingCoreSupport.SettlementLineDraft replayLineDraft =
            accountingCoreSupport.buildSupplierSettlementLines(
                company, request, payableAccount, totals, memo, false);
        JournalEntry entry =
            accountingCoreSupport.resolveReplayJournalEntry(
                trimmedIdempotencyKey, existingEntry, existingAllocations);
        accountingCoreSupport.linkReferenceMapping(
            company,
            trimmedIdempotencyKey,
            entry,
            AccountingCoreSupport.ENTITY_TYPE_SUPPLIER_SETTLEMENT);
        accountingCoreSupport.validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            existingAllocations,
            allocations);
        accountingCoreSupport.validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.SUPPLIER,
            supplier.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            replayLineDraft.lines());
        return accountingCoreSupport.buildSupplierSettlementResponse(existingAllocations);
      }
      throw accountingCoreSupport.missingReservedPartnerAllocation(
          "Supplier settlement", trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        accountingCoreSupport.findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          accountingCoreSupport.resolveReplayJournalEntryFromExistingAllocations(
              company, reference, trimmedIdempotencyKey, existingAllocations);
      accountingCoreSupport.linkReferenceMapping(
          company,
          trimmedIdempotencyKey,
          entry,
          AccountingCoreSupport.ENTITY_TYPE_SUPPLIER_SETTLEMENT);
      accountingCoreSupport.validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          existingAllocations,
          allocations);
      accountingCoreSupport.validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.SUPPLIER,
          supplier.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          accountingCoreSupport
              .buildSupplierSettlementLines(company, request, payableAccount, totals, memo, false)
              .lines());
      return accountingCoreSupport.buildSupplierSettlementResponse(existingAllocations);
    }

    supplier.requireTransactionalUsage("settle supplier invoices");
    AccountingCoreSupport.SettlementLineDraft lineDraft =
        accountingCoreSupport.buildSupplierSettlementLines(
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
          accountingCoreSupport.normalizeNonNegative(allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff =
          accountingCoreSupport.normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      SettlementAllocationApplication applicationType =
          accountingCoreSupport.resolveSettlementApplicationType(allocation);

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
        accountingCoreSupport.enforceSupplierSettlementPostingParity(
            company, supplier.getId(), purchase, trimmedIdempotencyKey);
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
          accountingCoreSupport.encodeSettlementAllocationMemo(applicationType, allocation.memo()));
      settlementRows.add(row);
    }

    JournalEntryDto journalEntryDto =
        resolveAccountingFacade()
            .createStandardJournal(
                new JournalCreationRequest(
                    totalJournalAmount(lineDraft.lines()),
                    null,
                    null,
                    memo,
                    AccountingCoreSupport.ENTITY_TYPE_SUPPLIER_SETTLEMENT,
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
    accountingCoreSupport.linkReferenceMapping(
        company,
        trimmedIdempotencyKey,
        journalEntry,
        AccountingCoreSupport.ENTITY_TYPE_SUPPLIER_SETTLEMENT);
    for (PartnerSettlementAllocation allocation : settlementRows) {
      allocation.setJournalEntry(journalEntry);
    }
    try {
      settlementAllocationRepository.saveAll(settlementRows);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Concurrent supplier settlement allocation conflict for idempotency key hash={} detected;"
              + " retrying in fresh transaction",
          accountingCoreSupport.sanitizeIdempotencyLogValue(trimmedIdempotencyKey));
      throw ex;
    }
    for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
      RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
      if (purchase == null) {
        continue;
      }
      purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
      accountingCoreSupport.updatePurchaseStatus(purchase);
      touchedPurchases.add(purchase);
    }
    if (!touchedPurchases.isEmpty()) {
      rawMaterialPurchaseRepository.saveAll(touchedPurchases);
    }

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        accountingCoreSupport.toSettlementAllocationSummaries(settlementRows);
    accountingCoreSupport.logSettlementAuditSuccess(
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
        settlementOverrideRequested ? accountingCoreSupport.resolveCurrentUsername() : null);

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
        accountingCoreSupport
            .supplierRepository
            .lockByCompanyAndId(company, supplierId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    Long cashAccountId =
        accountingCoreSupport.resolveAutoSettlementCashAccountId(
            company, request.cashAccountId(), "supplier auto-settlement");
    List<SettlementAllocationRequest> allocations =
        accountingCoreSupport.buildSupplierAutoSettlementAllocations(company, supplier, amount);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Auto-settlement for supplier " + supplier.getName();
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : accountingCoreSupport.buildSupplierAutoSettlementReference(
                company, supplier, cashAccountId, amount, allocations);
    String idempotencyKey =
        StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey().trim() : reference;
    SupplierPaymentRequest paymentRequest =
        new SupplierPaymentRequest(
            supplier.getId(), cashAccountId, amount, reference, memo, idempotencyKey, allocations);
    JournalEntryDto journalEntry = resolveAccountingFacade().recordSupplierPayment(paymentRequest);
    return accountingCoreSupport.buildAutoSettlementResponse(company, journalEntry);
  }

  private AccountingFacade resolveAccountingFacade() {
    AccountingFacade facade =
        accountingFacadeProvider != null ? accountingFacadeProvider.getIfAvailable() : null;
    if (facade == null) {
      throw new IllegalStateException("AccountingFacade is required");
    }
    return facade;
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
