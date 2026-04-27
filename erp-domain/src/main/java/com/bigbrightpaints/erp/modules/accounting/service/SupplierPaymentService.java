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
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierPaymentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
class SupplierPaymentService {

  private static final Logger log = LoggerFactory.getLogger(SupplierPaymentService.class);

  private final CompanyContextService companyContextService;
  private final JournalEntryService journalEntryService;
  private final SupplierRepository supplierRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  private final AccountResolutionService accountResolutionService;
  private final SettlementReferenceService settlementReferenceService;
  private final JournalReplayService journalReplayService;
  private final SettlementReplayValidationService settlementReplayValidationService;
  private final SettlementTotalsValidationService settlementTotalsValidationService;
  private final SettlementOutcomeService settlementOutcomeService;
  private final AccountingDtoMapperService dtoMapperService;
  private final AccountingAuditService accountingAuditService;

  SupplierPaymentService(
      CompanyContextService companyContextService,
      JournalEntryService journalEntryService,
      SupplierRepository supplierRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      AccountResolutionService accountResolutionService,
      SettlementReferenceService settlementReferenceService,
      JournalReplayService journalReplayService,
      SettlementReplayValidationService settlementReplayValidationService,
      SettlementTotalsValidationService settlementTotalsValidationService,
      SettlementOutcomeService settlementOutcomeService,
      AccountingDtoMapperService dtoMapperService,
      AccountingAuditService accountingAuditService) {
    this.companyContextService = companyContextService;
    this.journalEntryService = journalEntryService;
    this.supplierRepository = supplierRepository;
    this.accountingLookupService = accountingLookupService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
    this.accountResolutionService = accountResolutionService;
    this.settlementReferenceService = settlementReferenceService;
    this.journalReplayService = journalReplayService;
    this.settlementReplayValidationService = settlementReplayValidationService;
    this.settlementTotalsValidationService = settlementTotalsValidationService;
    this.settlementOutcomeService = settlementOutcomeService;
    this.dtoMapperService = dtoMapperService;
    this.accountingAuditService = accountingAuditService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
    return recordSupplierPaymentInternal(request);
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
    Account payableAccount = accountResolutionService.requireSupplierPayable(supplier);
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    List<SettlementAllocationRequest> allocations = request.allocations();
    settlementTotalsValidationService.validatePaymentAllocations(
        allocations, amount, "supplier payment", false);
    Account cashAccount =
        accountResolutionService.requireCashAccountForSettlement(
            company, request.cashAccountId(), "supplier payment", false);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Payment to supplier " + supplier.getName();
    String idempotencyKey =
        settlementReferenceService.resolveReceiptIdempotencyKey(
            request.idempotencyKey(), request.referenceNumber(), "supplier payment");
    String reference =
        settlementReferenceService.resolveSupplierPaymentReference(
            company, supplier, request.referenceNumber(), idempotencyKey);
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, idempotencyKey, reference, "SUPPLIER_PAYMENT");

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          journalReplayService.awaitAllocations(company, idempotencyKey);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            journalReplayService.resolveReplayJournalEntry(
                idempotencyKey, existingEntry, existingAllocations);
        journalReplayService.linkReferenceMapping(
            company, idempotencyKey, entry, "SUPPLIER_PAYMENT");
        settlementReplayValidationService.validateSupplierPaymentIdempotency(
            idempotencyKey,
            supplier,
            cashAccount,
            payableAccount,
            amount,
            memo,
            entry,
            existingAllocations,
            allocations);
        return dtoMapperService.toJournalEntryDto(entry);
      }
      throw journalReplayService.missingReservedPartnerAllocation(
          "Supplier payment", idempotencyKey, PartnerType.SUPPLIER, supplier.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        journalReplayService.findAllocationsByIdempotencyKey(company, idempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntryFromExistingAllocations(
              company, reference, idempotencyKey, existingAllocations);
      journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "SUPPLIER_PAYMENT");
      settlementReplayValidationService.validateSupplierPaymentIdempotency(
          idempotencyKey,
          supplier,
          cashAccount,
          payableAccount,
          amount,
          memo,
          entry,
          existingAllocations,
          allocations);
      return dtoMapperService.toJournalEntryDto(entry);
    }

    supplier.requireTransactionalUsage("record supplier payments");
    cashAccount =
        accountResolutionService.requireCashAccountForSettlement(
            company, request.cashAccountId(), "supplier payment", true);
    JournalEntryDto entryDto =
        journalEntryService.createStandardJournal(
            new JournalCreationRequest(
                amount,
                payableAccount.getId(),
                cashAccount.getId(),
                memo,
                "SUPPLIER_PAYMENT",
                reference,
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        payableAccount.getId(), amount, BigDecimal.ZERO, memo),
                    new JournalCreationRequest.LineRequest(
                        cashAccount.getId(), BigDecimal.ZERO, amount, memo)),
                accountResolutionService.currentDate(company),
                null,
                supplier.getId(),
                Boolean.FALSE));
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryDto.id());
    journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "SUPPLIER_PAYMENT");
    existingAllocations =
        journalReplayService.findAllocationsByIdempotencyKey(company, idempotencyKey);
    if (!existingAllocations.isEmpty()) {
      settlementReplayValidationService.validateSupplierPaymentIdempotency(
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
          journalReplayService.sanitizeIdempotencyLogValue(idempotencyKey));
      throw ex;
    }
    if (!settlementRows.isEmpty()) {
      BigDecimal totalAllocated =
          settlementRows.stream()
              .map(PartnerSettlementAllocation::getAllocationAmount)
              .map(MoneyUtils::zeroIfNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      accountingAuditService.recordSettlementAllocatedEventSafe(
          entry,
          PartnerType.SUPPLIER,
          supplier.getId(),
          totalAllocated,
          settlementRows.size(),
          idempotencyKey);
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
        entry, supplier.getId(), amount, idempotencyKey);
    return entryDto;
  }
}
