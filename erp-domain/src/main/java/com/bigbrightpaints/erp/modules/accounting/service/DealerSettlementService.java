package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentEvent;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerPaymentFlow;
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
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.service.SalesOrderAutoCloseService;

@Service
class DealerSettlementService {

  private static final String DEALER_SETTLEMENT_ROUTE = "/api/v1/accounting/settlements/dealers";
  private static final String DEALER_AUTO_SETTLEMENT_ROUTE =
      "/api/v1/accounting/dealers/{dealerId}/auto-settle";

  private final CompanyContextService companyContextService;
  private final JournalEntryService journalEntryService;
  private final DealerRepository dealerRepository;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final InvoiceSettlementPolicy invoiceSettlementPolicy;
  private final DealerLedgerService dealerLedgerService;
  private final InvoiceRepository invoiceRepository;
  private final AccountResolutionService accountResolutionService;
  private final SettlementReferenceService settlementReferenceService;
  private final JournalReplayService journalReplayService;
  private final SettlementReplayValidationService settlementReplayValidationService;
  private final SettlementAllocationResolutionService settlementAllocationResolutionService;
  private final SettlementTotalsValidationService settlementTotalsValidationService;
  private final SettlementJournalLineDraftService settlementJournalLineDraftService;
  private final SettlementOutcomeService settlementOutcomeService;
  private final AccountingAuditService accountingAuditService;
  private final PartnerPaymentEventService partnerPaymentEventService;

  @Autowired(required = false)
  private SalesOrderAutoCloseService salesOrderAutoCloseService;

  DealerSettlementService(
      CompanyContextService companyContextService,
      JournalEntryService journalEntryService,
      DealerRepository dealerRepository,
      ReferenceNumberService referenceNumberService,
      CompanyScopedAccountingLookupService accountingLookupService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      InvoiceSettlementPolicy invoiceSettlementPolicy,
      DealerLedgerService dealerLedgerService,
      InvoiceRepository invoiceRepository,
      AccountResolutionService accountResolutionService,
      SettlementReferenceService settlementReferenceService,
      JournalReplayService journalReplayService,
      SettlementReplayValidationService settlementReplayValidationService,
      SettlementAllocationResolutionService settlementAllocationResolutionService,
      SettlementTotalsValidationService settlementTotalsValidationService,
      SettlementJournalLineDraftService settlementJournalLineDraftService,
      SettlementOutcomeService settlementOutcomeService,
      AccountingAuditService accountingAuditService,
      PartnerPaymentEventService partnerPaymentEventService) {
    this.companyContextService = companyContextService;
    this.journalEntryService = journalEntryService;
    this.dealerRepository = dealerRepository;
    this.referenceNumberService = referenceNumberService;
    this.accountingLookupService = accountingLookupService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.invoiceSettlementPolicy = invoiceSettlementPolicy;
    this.dealerLedgerService = dealerLedgerService;
    this.invoiceRepository = invoiceRepository;
    this.accountResolutionService = accountResolutionService;
    this.settlementReferenceService = settlementReferenceService;
    this.journalReplayService = journalReplayService;
    this.settlementReplayValidationService = settlementReplayValidationService;
    this.settlementAllocationResolutionService = settlementAllocationResolutionService;
    this.settlementTotalsValidationService = settlementTotalsValidationService;
    this.settlementJournalLineDraftService = settlementJournalLineDraftService;
    this.settlementOutcomeService = settlementOutcomeService;
    this.accountingAuditService = accountingAuditService;
    this.partnerPaymentEventService = partnerPaymentEventService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  PartnerSettlementResponse settleDealerInvoices(PartnerSettlementRequest request) {
    return settleDealerInvoices(request, DEALER_SETTLEMENT_ROUTE);
  }

  private PartnerSettlementResponse settleDealerInvoices(
      PartnerSettlementRequest request, String sourceRoute) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, request.partnerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    Account receivableAccount = accountResolutionService.requireDealerReceivable(dealer);
    String replayLookupKey = settlementReferenceService.resolveExplicitSettlementReplayKey(request);
    List<SettlementAllocationRequest> allocations =
        settlementAllocationResolutionService.resolveDealerSettlementAllocations(
            company, dealer, request, replayLookupKey);
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
    String trimmedIdempotencyKey =
        settlementReferenceService.resolveDealerSettlementIdempotencyKey(requestForReplay);
    if (!StringUtils.hasText(trimmedIdempotencyKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Idempotency key is required for dealer settlements");
    }
    boolean replayCandidate =
        journalReplayService.hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      settlementTotalsValidationService.validateDealerSettlementAllocations(allocations);
    }
    SettlementTotals totals =
        settlementTotalsValidationService.computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement for dealer " + dealer.getName();
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
        settlementReferenceService.resolveDealerSettlementReference(
            company, dealer, request, trimmedIdempotencyKey);
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, trimmedIdempotencyKey, reference, "DEALER_SETTLEMENT");
    if (reservation.leader()
        && !StringUtils.hasText(request.referenceNumber())
        && journalReplayService.isReservedReference(reference)) {
      reference = referenceNumberService.dealerReceiptReference(company, dealer);
    }
    SettlementLineDraft lineDraft =
        settlementJournalLineDraftService.buildDealerSettlementLines(
            company, request, receivableAccount, totals, memo, false);

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          journalReplayService.awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            journalReplayService.resolveReplayJournalEntry(
                trimmedIdempotencyKey, existingEntry, existingAllocations);
        PartnerPaymentEvent paymentEvent =
            resolveDealerSettlementPaymentEvent(
                company,
                dealer,
                totals.totalApplied(),
                entry != null ? entry.getEntryDate() : requestedEffectiveSettlementDate,
                reference,
                trimmedIdempotencyKey,
                memo,
                sourceRoute);
        partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
        attachPaymentEventToSettlementRows(existingAllocations, paymentEvent);
        journalReplayService.linkReferenceMapping(
            company, trimmedIdempotencyKey, entry, "DEALER_SETTLEMENT");
        settlementReplayValidationService.validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            existingAllocations,
            allocations);
        settlementReplayValidationService.validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            lineDraft.lines());
        return settlementOutcomeService.buildDealerSettlementResponse(existingAllocations);
      }
      throw journalReplayService.missingReservedPartnerAllocation(
          "Dealer settlement", trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        journalReplayService.findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntryFromExistingAllocations(
              company, reference, trimmedIdempotencyKey, existingAllocations);
      PartnerPaymentEvent paymentEvent =
          resolveDealerSettlementPaymentEvent(
              company,
              dealer,
              totals.totalApplied(),
              entry != null ? entry.getEntryDate() : requestedEffectiveSettlementDate,
              reference,
              trimmedIdempotencyKey,
              memo,
              sourceRoute);
      partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
      attachPaymentEventToSettlementRows(existingAllocations, paymentEvent);
      journalReplayService.linkReferenceMapping(
          company, trimmedIdempotencyKey, entry, "DEALER_SETTLEMENT");
      settlementReplayValidationService.validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          existingAllocations,
          allocations);
      settlementReplayValidationService.validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          lineDraft.lines());
      return settlementOutcomeService.buildDealerSettlementResponse(existingAllocations);
    }

    lineDraft =
        settlementJournalLineDraftService.buildDealerSettlementLines(
            company, request, receivableAccount, totals, memo, true);
    LocalDate entryDate = requestedEffectiveSettlementDate;

    BigDecimal totalApplied = totals.totalApplied();
    BigDecimal totalDiscount = totals.totalDiscount();
    BigDecimal totalWriteOff = totals.totalWriteOff();
    BigDecimal totalFxGain = totals.totalFxGain();
    BigDecimal totalFxLoss = totals.totalFxLoss();
    BigDecimal cashAmount = lineDraft.cashAmount();
    PartnerPaymentEvent paymentEvent =
        resolveDealerSettlementPaymentEvent(
            company,
            dealer,
            totals.totalApplied(),
            entryDate,
            reference,
            trimmedIdempotencyKey,
            memo,
            sourceRoute);
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
        settlementOutcomeService.enforceSettlementCurrency(company, invoice);

        BigDecimal cleared = applied;
        BigDecimal currentOutstanding =
            remainingByInvoice.getOrDefault(
                invoice.getId(), MoneyUtils.zeroIfNull(invoice.getOutstandingAmount()));
        if (cleared.subtract(currentOutstanding).compareTo(AccountingConstants.ALLOCATION_TOLERANCE)
            > 0) {
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
      row.setPaymentEvent(paymentEvent);
      if (invoice != null && invoice.getCurrency() != null) {
        row.setCurrency(invoice.getCurrency());
      }
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
                "DEALER_SETTLEMENT",
                reference,
                null,
                toCreationLines(lineDraft.lines()),
                entryDate,
                dealer.getId(),
                null,
                request.adminOverride(),
                List.of()));

    JournalEntry journalEntry =
        accountingLookupService.requireJournalEntry(company, journalEntryDto.id());
    partnerPaymentEventService.linkJournalEntry(paymentEvent, journalEntry);
    journalReplayService.linkReferenceMapping(
        company, trimmedIdempotencyKey, journalEntry, "DEALER_SETTLEMENT");
    for (PartnerSettlementAllocation allocation : settlementRows) {
      allocation.setJournalEntry(journalEntry);
    }
    settlementAllocationRepository.saveAll(settlementRows);
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
      autoClosePaidOrders(company, touchedInvoices);
    }
    accountingAuditService.recordDealerReceiptPostedEventSafe(
        journalEntry, dealer.getId(), cashAmount, trimmedIdempotencyKey);
    accountingAuditService.recordSettlementAllocatedEventSafe(
        journalEntry,
        PartnerType.DEALER,
        dealer.getId(),
        totalApplied,
        settlementRows.size(),
        trimmedIdempotencyKey);

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        settlementOutcomeService.toSettlementAllocationSummaries(settlementRows);
    accountingAuditService.logSettlementAuditSuccess(
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

  private PartnerPaymentEvent resolveDealerSettlementPaymentEvent(
      Company company,
      Dealer dealer,
      BigDecimal paymentAmount,
      LocalDate paymentDate,
      String reference,
      String idempotencyKey,
      String memo,
      String sourceRoute) {
    return partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
        company,
        dealer,
        PartnerPaymentFlow.DEALER_SETTLEMENT,
        paymentAmount,
        paymentDate,
        reference,
        idempotencyKey,
        memo,
        sourceRoute);
  }

  private void attachPaymentEventToSettlementRows(
      List<PartnerSettlementAllocation> allocations, PartnerPaymentEvent paymentEvent) {
    if (paymentEvent == null || allocations == null || allocations.isEmpty()) {
      return;
    }
    boolean updated = false;
    for (PartnerSettlementAllocation allocation : allocations) {
      if (allocation.getPaymentEvent() == null) {
        allocation.setPaymentEvent(paymentEvent);
        updated = true;
      }
    }
    if (updated) {
      settlementAllocationRepository.saveAll(allocations);
    }
  }

  private void autoClosePaidOrders(Company company, List<Invoice> touchedInvoices) {
    if (salesOrderAutoCloseService == null) {
      return;
    }
    salesOrderAutoCloseService.autoCloseFullyPaidOrders(company, touchedInvoices);
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
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
        accountResolutionService.resolveAutoSettlementCashAccountId(
            company, request.cashAccountId(), "dealer auto-settlement");
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Auto-settlement for dealer " + dealer.getName();
    PartnerSettlementRequest settlementRequest =
        new PartnerSettlementRequest(
            PartnerType.DEALER,
            dealer.getId(),
            cashAccountId,
            null,
            null,
            null,
            null,
            amount,
            null,
            null,
            request.referenceNumber(),
            memo,
            request.idempotencyKey(),
            Boolean.FALSE,
            null);
    return settleDealerInvoices(settlementRequest, DEALER_AUTO_SETTLEMENT_ROUTE);
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
