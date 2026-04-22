package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
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
class DealerReceiptPostingService {

  private static final String DEALER_RECEIPT_ROUTE = "/api/v1/accounting/receipts/dealer";
  private static final String DEALER_RECEIPT_SPLIT_ROUTE =
      "/api/v1/accounting/receipts/dealer/hybrid";

  private final CompanyContextService companyContextService;
  private final DealerRepository dealerRepository;
  private final JournalEntryService journalEntryService;
  private final AccountResolutionService accountResolutionService;
  private final SettlementReferenceService settlementReferenceService;
  private final JournalReplayService journalReplayService;
  private final SettlementReplayValidationService settlementReplayValidationService;
  private final PartnerSettlementAllocationRepository settlementAllocationRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final InvoiceRepository invoiceRepository;
  private final InvoiceSettlementPolicy invoiceSettlementPolicy;
  private final DealerLedgerService dealerLedgerService;
  private final AccountingDtoMapperService dtoMapperService;
  private final AccountingAuditService accountingAuditService;
  private final SettlementTotalsValidationService settlementTotalsValidationService;
  private final PartnerPaymentEventService partnerPaymentEventService;

  @Autowired(required = false)
  private SalesOrderAutoCloseService salesOrderAutoCloseService;

  DealerReceiptPostingService(
      CompanyContextService companyContextService,
      DealerRepository dealerRepository,
      JournalEntryService journalEntryService,
      AccountResolutionService accountResolutionService,
      SettlementReferenceService settlementReferenceService,
      JournalReplayService journalReplayService,
      SettlementReplayValidationService settlementReplayValidationService,
      PartnerSettlementAllocationRepository settlementAllocationRepository,
      CompanyScopedAccountingLookupService accountingLookupService,
      InvoiceRepository invoiceRepository,
      InvoiceSettlementPolicy invoiceSettlementPolicy,
      DealerLedgerService dealerLedgerService,
      AccountingDtoMapperService dtoMapperService,
      AccountingAuditService accountingAuditService,
      SettlementTotalsValidationService settlementTotalsValidationService,
      PartnerPaymentEventService partnerPaymentEventService) {
    this.companyContextService = companyContextService;
    this.dealerRepository = dealerRepository;
    this.journalEntryService = journalEntryService;
    this.accountResolutionService = accountResolutionService;
    this.settlementReferenceService = settlementReferenceService;
    this.journalReplayService = journalReplayService;
    this.settlementReplayValidationService = settlementReplayValidationService;
    this.settlementAllocationRepository = settlementAllocationRepository;
    this.accountingLookupService = accountingLookupService;
    this.invoiceRepository = invoiceRepository;
    this.invoiceSettlementPolicy = invoiceSettlementPolicy;
    this.dealerLedgerService = dealerLedgerService;
    this.dtoMapperService = dtoMapperService;
    this.accountingAuditService = accountingAuditService;
    this.settlementTotalsValidationService = settlementTotalsValidationService;
    this.partnerPaymentEventService = partnerPaymentEventService;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, request.dealerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    Account receivableAccount = accountResolutionService.requireDealerReceivable(dealer);
    Account cashAccount =
        accountResolutionService.requireCashAccountForSettlement(
            company, request.cashAccountId(), "dealer receipt", false);
    BigDecimal amount = ValidationUtils.requirePositive(request.amount(), "amount");
    List<SettlementAllocationRequest> requestedAllocations = request.allocations();
    if (requestedAllocations == null) {
      requestedAllocations = List.of();
    }
    validateDealerReceiptAllocations(requestedAllocations, amount);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Receipt for dealer " + dealer.getName();
    List<SettlementAllocationRequest> allocationsForPosting =
        resolveDealerReceiptAllocationsForPosting(requestedAllocations, amount, memo);
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : settlementReferenceService.buildDealerReceiptReference(company, dealer, request);
    String idempotencyKey =
        settlementReferenceService.resolveReceiptIdempotencyKey(
            request.idempotencyKey(), reference, "dealer receipt");
    LocalDate paymentDate = accountResolutionService.currentDate(company);
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, idempotencyKey, reference, "DEALER_RECEIPT");

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntry(
              idempotencyKey, existingEntry, existingAllocations);
      if (entry != null) {
        PartnerPaymentEvent paymentEvent =
            partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
                company,
                dealer,
                PartnerPaymentFlow.DEALER_RECEIPT,
                amount,
                entry.getEntryDate(),
                reference,
                idempotencyKey,
                memo,
                DEALER_RECEIPT_ROUTE);
        partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
        journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "DEALER_RECEIPT");
        settlementReplayValidationService.validateDealerReceiptIdempotency(
            idempotencyKey,
            dealer,
            cashAccount,
            receivableAccount,
            amount,
            memo,
            entry,
            existingAllocations,
            resolveDealerReceiptReplayValidationAllocations(
                requestedAllocations, amount, memo, existingAllocations));
        return dtoMapperService.toJournalEntryDto(entry);
      }
      throw journalReplayService.missingReservedPartnerAllocation(
          "Dealer receipt", idempotencyKey, PartnerType.DEALER, dealer.getId());
    }

    List<PartnerSettlementAllocation> existingAllocations =
        journalReplayService.findAllocationsByIdempotencyKey(company, idempotencyKey);
    if (!existingAllocations.isEmpty()) {
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntryFromExistingAllocations(
              company, reference, idempotencyKey, existingAllocations);
      if (entry == null) {
        throw journalReplayService.missingReservedPartnerAllocation(
            "Dealer receipt", idempotencyKey, PartnerType.DEALER, dealer.getId());
      }
      PartnerPaymentEvent paymentEvent =
          partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
              company,
              dealer,
              PartnerPaymentFlow.DEALER_RECEIPT,
              amount,
              entry.getEntryDate(),
              reference,
              idempotencyKey,
              memo,
              DEALER_RECEIPT_ROUTE);
      partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
      journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "DEALER_RECEIPT");
      settlementReplayValidationService.validateDealerReceiptIdempotency(
          idempotencyKey,
          dealer,
          cashAccount,
          receivableAccount,
          amount,
          memo,
          entry,
          existingAllocations,
          resolveDealerReceiptReplayValidationAllocations(
              requestedAllocations, amount, memo, existingAllocations));
      return dtoMapperService.toJournalEntryDto(entry);
    }
    JournalEntry existingEntry =
        journalReplayService.findExistingEntry(company, reference, idempotencyKey);
    if (existingEntry != null) {
      existingAllocations = resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntry(
              idempotencyKey, existingEntry, existingAllocations);
      if (entry != null) {
        PartnerPaymentEvent paymentEvent =
            partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
                company,
                dealer,
                PartnerPaymentFlow.DEALER_RECEIPT,
                amount,
                entry.getEntryDate(),
                reference,
                idempotencyKey,
                memo,
                DEALER_RECEIPT_ROUTE);
        partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
        journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "DEALER_RECEIPT");
        settlementReplayValidationService.validateDealerReceiptIdempotency(
            idempotencyKey,
            dealer,
            cashAccount,
            receivableAccount,
            amount,
            memo,
            entry,
            existingAllocations,
            resolveDealerReceiptReplayValidationAllocations(
                requestedAllocations, amount, memo, existingAllocations));
        return dtoMapperService.toJournalEntryDto(entry);
      }
    }

    cashAccount =
        accountResolutionService.requireCashAccountForSettlement(
            company, request.cashAccountId(), "dealer receipt", true);
    PartnerPaymentEvent paymentEvent =
        partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
            company,
            dealer,
            PartnerPaymentFlow.DEALER_RECEIPT,
            amount,
            paymentDate,
            reference,
            idempotencyKey,
            memo,
            DEALER_RECEIPT_ROUTE);
    JournalEntryDto entryDto =
        createStandardJournal(
            new JournalCreationRequest(
                amount,
                null,
                null,
                memo,
                "DEALER_RECEIPT",
                reference,
                null,
                List.of(
                    new JournalCreationRequest.LineRequest(
                        cashAccount.getId(), amount, BigDecimal.ZERO, memo),
                    new JournalCreationRequest.LineRequest(
                        receivableAccount.getId(), BigDecimal.ZERO, amount, memo)),
                paymentDate,
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of()));
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryDto.id());
    partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
    journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "DEALER_RECEIPT");
    applyDealerAllocations(
        company, dealer, reference, entry, paymentEvent, idempotencyKey, allocationsForPosting);
    accountingAuditService.recordDealerReceiptPostedEventSafe(
        entry, dealer.getId(), amount, idempotencyKey);
    return entryDto;
  }

  @Retryable(
      value = DataIntegrityViolationException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50, maxDelay = 250, multiplier = 2.0))
  @Transactional
  JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer =
        dealerRepository
            .lockByCompanyAndId(company, request.dealerId())
            .orElseThrow(
                () ->
                    new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
    Account receivableAccount = accountResolutionService.requireDealerReceivable(dealer);
    if (request.incomingLines() == null || request.incomingLines().isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "At least one incoming line is required");
    }
    BigDecimal total = BigDecimal.ZERO;
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    for (DealerReceiptSplitRequest.IncomingLine line : request.incomingLines()) {
      Account incoming =
          accountResolutionService.requireCashAccountForSettlement(
              company, line.accountId(), "dealer split receipt", false);
      BigDecimal amount = ValidationUtils.requirePositive(line.amount(), "amount");
      total = total.add(amount);
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              incoming.getId(), "Dealer receipt", amount, BigDecimal.ZERO));
    }
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            receivableAccount.getId(), "Dealer receipt", BigDecimal.ZERO, total));
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Receipt for dealer " + dealer.getName();
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : settlementReferenceService.buildDealerReceiptReference(company, dealer, request);
    String idempotencyKey =
        settlementReferenceService.resolveReceiptIdempotencyKey(
            request.idempotencyKey(), reference, "dealer receipt");
    LocalDate paymentDate = accountResolutionService.currentDate(company);
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, idempotencyKey, reference, "DEALER_RECEIPT_SPLIT");
    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      JournalEntry entry =
          journalReplayService.resolveReplayJournalEntry(
              idempotencyKey, existingEntry, existingAllocations);
      if (entry != null) {
        PartnerPaymentEvent paymentEvent =
            partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
                company,
                dealer,
                PartnerPaymentFlow.DEALER_RECEIPT_SPLIT,
                total,
                entry.getEntryDate(),
                reference,
                idempotencyKey,
                memo,
                DEALER_RECEIPT_SPLIT_ROUTE);
        partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
        journalReplayService.linkReferenceMapping(
            company, idempotencyKey, entry, "DEALER_RECEIPT_SPLIT");
        settlementReplayValidationService.validateSplitReceiptIdempotency(
            idempotencyKey, dealer, memo, entry, lines);
        return dtoMapperService.toJournalEntryDto(entry);
      }
      throw journalReplayService.missingReservedPartnerAllocation(
          "Dealer receipt", idempotencyKey, PartnerType.DEALER, dealer.getId());
    }
    for (DealerReceiptSplitRequest.IncomingLine line : request.incomingLines()) {
      accountResolutionService.requireCashAccountForSettlement(
          company, line.accountId(), "dealer split receipt", true);
    }
    PartnerPaymentEvent paymentEvent =
        partnerPaymentEventService.resolveOrCreateDealerPaymentEvent(
            company,
            dealer,
            PartnerPaymentFlow.DEALER_RECEIPT_SPLIT,
            total,
            paymentDate,
            reference,
            idempotencyKey,
            memo,
            DEALER_RECEIPT_SPLIT_ROUTE);
    JournalEntryDto entryDto =
        createStandardJournal(
            new JournalCreationRequest(
                total,
                null,
                null,
                memo,
                "DEALER_RECEIPT_SPLIT",
                reference,
                null,
                lines.stream()
                    .map(
                        line ->
                            new JournalCreationRequest.LineRequest(
                                line.accountId(), line.debit(), line.credit(), line.description()))
                    .toList(),
                paymentDate,
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of()));
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryDto.id());
    partnerPaymentEventService.linkJournalEntry(paymentEvent, entry);
    journalReplayService.linkReferenceMapping(
        company, idempotencyKey, entry, "DEALER_RECEIPT_SPLIT");
    autoApplyDealerSplitAllocations(company, dealer, entry, paymentEvent, total, idempotencyKey);
    accountingAuditService.recordDealerReceiptPostedEventSafe(
        entry, dealer.getId(), total, idempotencyKey);
    return entryDto;
  }

  private JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return journalEntryService.createStandardJournal(request);
  }

  private List<PartnerSettlementAllocation> resolveAllocationsForReplay(
      Company company, String idempotencyKey, JournalEntry existingEntry) {
    if (existingEntry != null) {
      List<PartnerSettlementAllocation> byEntry =
          settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(
              company, existingEntry);
      if (!byEntry.isEmpty()) {
        return byEntry;
      }
    }
    return journalReplayService.awaitAllocations(company, idempotencyKey);
  }

  private List<SettlementAllocationRequest> resolveDealerReceiptAllocationsForPosting(
      List<SettlementAllocationRequest> requestedAllocations, BigDecimal amount, String memo) {
    if (requestedAllocations != null && !requestedAllocations.isEmpty()) {
      return requestedAllocations;
    }
    return List.of(buildDealerOnAccountAllocation(amount, memo));
  }

  private List<SettlementAllocationRequest> resolveDealerReceiptReplayValidationAllocations(
      List<SettlementAllocationRequest> requestedAllocations,
      BigDecimal amount,
      String memo,
      List<PartnerSettlementAllocation> existingAllocations) {
    if (requestedAllocations != null && !requestedAllocations.isEmpty()) {
      return requestedAllocations;
    }
    if (existingAllocations == null || existingAllocations.isEmpty()) {
      return List.of();
    }
    return List.of(buildDealerOnAccountAllocation(amount, memo));
  }

  private SettlementAllocationRequest buildDealerOnAccountAllocation(
      BigDecimal amount, String memo) {
    return new SettlementAllocationRequest(
        null,
        null,
        ValidationUtils.requirePositive(amount, "amount"),
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        SettlementAllocationApplication.ON_ACCOUNT,
        StringUtils.hasText(memo) ? memo.trim() : "Dealer receipt unapplied carry");
  }

  private String resolveAllocationCurrency(Company company, Invoice invoice) {
    if (invoice != null && StringUtils.hasText(invoice.getCurrency())) {
      return invoice.getCurrency().trim();
    }
    if (company != null && StringUtils.hasText(company.getBaseCurrency())) {
      return company.getBaseCurrency().trim();
    }
    return "INR";
  }

  private void applyDealerAllocations(
      Company company,
      Dealer dealer,
      String reference,
      JournalEntry entry,
      PartnerPaymentEvent paymentEvent,
      String idempotencyKey,
      List<SettlementAllocationRequest> allocations) {
    if (allocations == null || allocations.isEmpty()) {
      return;
    }
    LocalDate entryDate = entry.getEntryDate();
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
    List<Invoice> touchedInvoices = new ArrayList<>();
    Map<Long, BigDecimal> remainingByInvoice = new HashMap<>();
    for (SettlementAllocationRequest allocation : allocations) {
      if (allocation.purchaseId() != null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT, "Dealer receipts cannot allocate to purchases");
      }
      SettlementAllocationApplication applicationType =
          settlementTotalsValidationService.resolveSettlementApplicationType(allocation);
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      Invoice invoice = null;
      if (applicationType.isUnapplied()) {
        if (allocation.invoiceId() != null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Unapplied dealer receipt rows cannot reference an invoice");
        }
      } else {
        if (allocation.invoiceId() == null) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Invoice allocation is required for dealer receipts unless unapplied");
        }
        invoice =
            invoiceRepository
                .lockByCompanyAndId(company, allocation.invoiceId())
                .orElseThrow(
                    () ->
                        new ApplicationException(
                            ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
        if (invoice.getDealer() == null
            || !Objects.equals(invoice.getDealer().getId(), dealer.getId())) {
          throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
        }
        BigDecimal currentOutstanding =
            remainingByInvoice.getOrDefault(
                invoice.getId(), MoneyUtils.zeroIfNull(invoice.getOutstandingAmount()));
        if (applied.compareTo(currentOutstanding) > 0) {
          throw new ApplicationException(
                  ErrorCode.VALIDATION_INVALID_INPUT,
                  "Allocation exceeds invoice outstanding amount")
              .withDetail("invoiceId", invoice.getId())
              .withDetail("outstanding", currentOutstanding)
              .withDetail("applied", applied);
        }
        remainingByInvoice.put(
            invoice.getId(), currentOutstanding.subtract(applied).max(BigDecimal.ZERO));
      }
      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.DEALER);
      row.setDealer(dealer);
      row.setInvoice(invoice);
      row.setJournalEntry(entry);
      row.setPaymentEvent(paymentEvent);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(BigDecimal.ZERO);
      row.setWriteOffAmount(BigDecimal.ZERO);
      row.setFxDifferenceAmount(BigDecimal.ZERO);
      row.setIdempotencyKey(idempotencyKey);
      row.setCurrency(resolveAllocationCurrency(company, invoice));
      row.setMemo(
          settlementTotalsValidationService.encodeSettlementAllocationMemo(
              applicationType, allocation.memo()));
      settlementRows.add(row);
    }
    settlementAllocationRepository.saveAll(settlementRows);
    if (!settlementRows.isEmpty()) {
      BigDecimal totalAllocated =
          settlementRows.stream()
              .map(PartnerSettlementAllocation::getAllocationAmount)
              .map(MoneyUtils::zeroIfNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      accountingAuditService.recordSettlementAllocatedEventSafe(
          entry,
          PartnerType.DEALER,
          dealer.getId(),
          totalAllocated,
          settlementRows.size(),
          idempotencyKey);
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
      autoClosePaidOrders(company, touchedInvoices);
    }
  }

  private void autoApplyDealerSplitAllocations(
      Company company,
      Dealer dealer,
      JournalEntry entry,
      PartnerPaymentEvent paymentEvent,
      BigDecimal total,
      String idempotencyKey) {
    LocalDate entryDate = entry.getEntryDate();
    List<Invoice> openInvoices =
        invoiceRepository.lockOpenInvoicesForSettlement(company, dealer).stream()
            .filter(Objects::nonNull)
            .toList();
    BigDecimal remaining = total;
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
    List<Invoice> touchedInvoices = new ArrayList<>();
    for (Invoice invoice : openInvoices) {
      BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
      if (currentOutstanding.compareTo(BigDecimal.ZERO) <= 0
          || remaining.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal applied = remaining.min(currentOutstanding);
      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.DEALER);
      row.setDealer(dealer);
      row.setInvoice(invoice);
      row.setJournalEntry(entry);
      row.setPaymentEvent(paymentEvent);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(BigDecimal.ZERO);
      row.setWriteOffAmount(BigDecimal.ZERO);
      row.setFxDifferenceAmount(BigDecimal.ZERO);
      row.setIdempotencyKey(idempotencyKey);
      row.setCurrency(resolveAllocationCurrency(company, invoice));
      row.setMemo("Dealer split receipt");
      settlementRows.add(row);
      remaining = remaining.subtract(applied);
    }
    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      PartnerSettlementAllocation unapplied = new PartnerSettlementAllocation();
      unapplied.setCompany(company);
      unapplied.setPartnerType(PartnerType.DEALER);
      unapplied.setDealer(dealer);
      unapplied.setJournalEntry(entry);
      unapplied.setPaymentEvent(paymentEvent);
      unapplied.setSettlementDate(entryDate);
      unapplied.setAllocationAmount(remaining);
      unapplied.setDiscountAmount(BigDecimal.ZERO);
      unapplied.setWriteOffAmount(BigDecimal.ZERO);
      unapplied.setFxDifferenceAmount(BigDecimal.ZERO);
      unapplied.setIdempotencyKey(idempotencyKey);
      unapplied.setCurrency(resolveAllocationCurrency(company, null));
      unapplied.setMemo(
          settlementTotalsValidationService.encodeSettlementAllocationMemo(
              SettlementAllocationApplication.ON_ACCOUNT, "Dealer split receipt unapplied"));
      settlementRows.add(unapplied);
    }
    settlementAllocationRepository.saveAll(settlementRows);
    if (!settlementRows.isEmpty()) {
      BigDecimal totalAllocated =
          settlementRows.stream()
              .map(PartnerSettlementAllocation::getAllocationAmount)
              .map(MoneyUtils::zeroIfNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      accountingAuditService.recordSettlementAllocatedEventSafe(
          entry,
          PartnerType.DEALER,
          dealer.getId(),
          totalAllocated,
          settlementRows.size(),
          idempotencyKey);
    }
    for (PartnerSettlementAllocation row : settlementRows) {
      if (row.getInvoice() == null) {
        continue;
      }
      String settlementRef = entry.getReferenceNumber() + "-INV-" + row.getInvoice().getId();
      invoiceSettlementPolicy.applySettlement(
          row.getInvoice(), row.getAllocationAmount(), settlementRef);
      dealerLedgerService.syncInvoiceLedger(row.getInvoice(), entryDate);
      touchedInvoices.add(row.getInvoice());
    }
    if (!touchedInvoices.isEmpty()) {
      invoiceRepository.saveAll(touchedInvoices);
      autoClosePaidOrders(company, touchedInvoices);
    }
  }

  private void autoClosePaidOrders(Company company, List<Invoice> touchedInvoices) {
    if (salesOrderAutoCloseService == null) {
      return;
    }
    salesOrderAutoCloseService.autoCloseFullyPaidOrders(company, touchedInvoices);
  }

  private void validateDealerReceiptAllocations(
      List<SettlementAllocationRequest> allocations, BigDecimal amount) {
    if (allocations == null || allocations.isEmpty()) {
      return;
    }
    settlementTotalsValidationService.validateDealerSettlementAllocations(allocations);
    BigDecimal totalApplied = BigDecimal.ZERO;
    for (SettlementAllocationRequest allocation : allocations) {
      BigDecimal discount =
          settlementTotalsValidationService.normalizeNonNegative(
              allocation.discountAmount(), "discountAmount");
      BigDecimal writeOff =
          settlementTotalsValidationService.normalizeNonNegative(
              allocation.writeOffAmount(), "writeOffAmount");
      BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
      if (discount.compareTo(BigDecimal.ZERO) > 0
          || writeOff.compareTo(BigDecimal.ZERO) > 0
          || fxAdjustment.compareTo(BigDecimal.ZERO) != 0) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Discount/write-off/FX adjustments are not supported for dealer receipts");
      }
      totalApplied =
          totalApplied.add(
              ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount"));
    }
    if (totalApplied.subtract(amount).abs().compareTo(AccountingConstants.ALLOCATION_TOLERANCE)
        > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Dealer receipt allocations must add up to the receipt amount")
          .withDetail("receiptAmount", amount)
          .withDetail("allocationTotal", totalApplied);
    }
  }
}
