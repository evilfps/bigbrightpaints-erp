package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
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

@Service
class DealerSettlementService {

  private final AccountingCoreSupport accountingCoreSupport;
  private final CompanyContextService companyContextService;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
  private final DealerRepository dealerRepository;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final InvoiceSettlementPolicy invoiceSettlementPolicy;
  private final DealerLedgerService dealerLedgerService;
  private final InvoiceRepository invoiceRepository;

  DealerSettlementService(
      AccountingCoreSupport accountingCoreSupport,
      CompanyContextService companyContextService,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
      DealerRepository dealerRepository,
      ReferenceNumberService referenceNumberService,
      CompanyScopedAccountingLookupService accountingLookupService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      InvoiceSettlementPolicy invoiceSettlementPolicy,
      DealerLedgerService dealerLedgerService,
      InvoiceRepository invoiceRepository) {
    this.accountingCoreSupport = accountingCoreSupport;
    this.companyContextService = companyContextService;
    this.accountingFacadeProvider = accountingFacadeProvider;
    this.dealerRepository = dealerRepository;
    this.referenceNumberService = referenceNumberService;
    this.accountingLookupService = accountingLookupService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.invoiceSettlementPolicy = invoiceSettlementPolicy;
    this.dealerLedgerService = dealerLedgerService;
    this.invoiceRepository = invoiceRepository;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  PartnerSettlementResponse settleDealerInvoices(PartnerSettlementRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, request.dealerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    Account receivableAccount = accountingCoreSupport.requireDealerReceivable(dealer);
    String trimmedIdempotencyKey =
        accountingCoreSupport.resolveDealerSettlementIdempotencyKey(company, request);
    List<SettlementAllocationRequest> allocations =
        accountingCoreSupport.resolveDealerSettlementAllocations(
            company, dealer, request, trimmedIdempotencyKey);
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
        accountingCoreSupport.resolveDealerSettlementIdempotencyKey(company, requestForReplay);
    if (!StringUtils.hasText(trimmedIdempotencyKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Idempotency key is required for dealer settlements");
    }
    boolean replayCandidate =
        accountingCoreSupport.hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
    if (!replayCandidate) {
      accountingCoreSupport.validateDealerSettlementAllocations(allocations);
    }
    AccountingCoreSupport.SettlementTotals totals =
        accountingCoreSupport.computeSettlementTotals(allocations);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Settlement for dealer " + dealer.getName();
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
        accountingCoreSupport.resolveDealerSettlementReference(
            company, dealer, request, trimmedIdempotencyKey);
    AccountingCoreSupport.IdempotencyReservation reservation =
        accountingCoreSupport.reserveReferenceMapping(
            company,
            trimmedIdempotencyKey,
            reference,
            AccountingCoreSupport.ENTITY_TYPE_DEALER_SETTLEMENT);
    if (reservation.leader()
        && !StringUtils.hasText(request.referenceNumber())
        && accountingCoreSupport.isReservedReference(reference)) {
      reference = referenceNumberService.dealerReceiptReference(company, dealer);
    }
    AccountingCoreSupport.SettlementLineDraft lineDraft =
        accountingCoreSupport.buildDealerSettlementLines(
            company, request, receivableAccount, totals, memo, false);

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          accountingCoreSupport.awaitJournalEntry(company, reference, trimmedIdempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          accountingCoreSupport.awaitAllocations(company, trimmedIdempotencyKey);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            accountingCoreSupport.resolveReplayJournalEntry(
                trimmedIdempotencyKey, existingEntry, existingAllocations);
        accountingCoreSupport.linkReferenceMapping(
            company,
            trimmedIdempotencyKey,
            entry,
            AccountingCoreSupport.ENTITY_TYPE_DEALER_SETTLEMENT);
        accountingCoreSupport.validateSettlementIdempotencyKey(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            existingAllocations,
            allocations);
        accountingCoreSupport.validatePartnerSettlementJournalLines(
            trimmedIdempotencyKey,
            PartnerType.DEALER,
            dealer.getId(),
            requestedEffectiveSettlementDate,
            memo,
            entry,
            lineDraft.lines());
        return accountingCoreSupport.buildDealerSettlementResponse(existingAllocations);
      }
      throw accountingCoreSupport.missingReservedPartnerAllocation(
          "Dealer settlement", trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId());
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
          AccountingCoreSupport.ENTITY_TYPE_DEALER_SETTLEMENT);
      accountingCoreSupport.validateSettlementIdempotencyKey(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          existingAllocations,
          allocations);
      accountingCoreSupport.validatePartnerSettlementJournalLines(
          trimmedIdempotencyKey,
          PartnerType.DEALER,
          dealer.getId(),
          requestedEffectiveSettlementDate,
          memo,
          entry,
          lineDraft.lines());
      return accountingCoreSupport.buildDealerSettlementResponse(existingAllocations);
    }

    lineDraft =
        accountingCoreSupport.buildDealerSettlementLines(
            company, request, receivableAccount, totals, memo, true);
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
        accountingCoreSupport.enforceSettlementCurrency(company, invoice);

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
      if (invoice != null && invoice.getCurrency() != null) {
        row.setCurrency(invoice.getCurrency());
      }
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
                    AccountingCoreSupport.ENTITY_TYPE_DEALER_SETTLEMENT,
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
    accountingCoreSupport.linkReferenceMapping(
        company,
        trimmedIdempotencyKey,
        journalEntry,
        AccountingCoreSupport.ENTITY_TYPE_DEALER_SETTLEMENT);
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
    }

    List<PartnerSettlementResponse.Allocation> allocationSummaries =
        accountingCoreSupport.toSettlementAllocationSummaries(settlementRows);
    accountingCoreSupport.logSettlementAuditSuccess(
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
  PartnerSettlementResponse autoSettleDealer(Long dealerId, AutoSettlementRequest request) {
    if (request == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Auto-settlement request is required");
    }
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        accountingCoreSupport
            .dealerRepository
            .lockByCompanyAndId(company, dealerId)
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    Long cashAccountId =
        accountingCoreSupport.resolveAutoSettlementCashAccountId(
            company, request.cashAccountId(), "dealer auto-settlement");
    List<SettlementAllocationRequest> allocations =
        accountingCoreSupport.buildDealerAutoSettlementAllocations(company, dealer, amount);
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
    JournalEntryDto journalEntry = resolveAccountingFacade().recordDealerReceipt(receiptRequest);
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
