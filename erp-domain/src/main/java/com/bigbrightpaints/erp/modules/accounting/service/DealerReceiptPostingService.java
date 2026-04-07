package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerReceiptSplitRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SettlementAllocationRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
class DealerReceiptPostingService {

  private final CompanyContextService companyContextService;
  private final DealerRepository dealerRepository;
  private final ObjectProvider<AccountingFacade> accountingFacadeProvider;
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

  DealerReceiptPostingService(
      CompanyContextService companyContextService,
      DealerRepository dealerRepository,
      ObjectProvider<AccountingFacade> accountingFacadeProvider,
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
      AccountingAuditService accountingAuditService) {
    this.companyContextService = companyContextService;
    this.dealerRepository = dealerRepository;
    this.accountingFacadeProvider = accountingFacadeProvider;
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
    List<SettlementAllocationRequest> allocations = request.allocations();
    if (allocations == null) {
      allocations = List.of();
    }
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
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, idempotencyKey, reference, "DEALER_RECEIPT");

    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            journalReplayService.resolveReplayJournalEntry(
                idempotencyKey, existingEntry, existingAllocations);
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
            allocations);
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
          allocations);
      return dtoMapperService.toJournalEntryDto(entry);
    }
    JournalEntry existingEntry =
        journalReplayService.findExistingEntry(company, reference, idempotencyKey);
    if (existingEntry != null) {
      existingAllocations = resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      if (!existingAllocations.isEmpty()) {
        journalReplayService.linkReferenceMapping(
            company, idempotencyKey, existingEntry, "DEALER_RECEIPT");
        settlementReplayValidationService.validateDealerReceiptIdempotency(
            idempotencyKey,
            dealer,
            cashAccount,
            receivableAccount,
            amount,
            memo,
            existingEntry,
            existingAllocations,
            allocations);
        return dtoMapperService.toJournalEntryDto(existingEntry);
      }
    }

    cashAccount =
        accountResolutionService.requireCashAccountForSettlement(
            company, request.cashAccountId(), "dealer receipt", true);
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
                accountResolutionService.currentDate(company),
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of()));
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryDto.id());
    journalReplayService.linkReferenceMapping(company, idempotencyKey, entry, "DEALER_RECEIPT");
    applyDealerAllocations(company, dealer, reference, entry, idempotencyKey, allocations);
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
    JournalReplayService.IdempotencyReservation reservation =
        journalReplayService.reserveReferenceMapping(
            company, idempotencyKey, reference, "DEALER_RECEIPT_SPLIT");
    if (!reservation.leader()) {
      JournalEntry existingEntry =
          journalReplayService.awaitJournalEntry(company, reference, idempotencyKey);
      List<PartnerSettlementAllocation> existingAllocations =
          resolveAllocationsForReplay(company, idempotencyKey, existingEntry);
      if (!existingAllocations.isEmpty()) {
        JournalEntry entry =
            journalReplayService.resolveReplayJournalEntry(
                idempotencyKey, existingEntry, existingAllocations);
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
                accountResolutionService.currentDate(company),
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of()));
    JournalEntry entry = accountingLookupService.requireJournalEntry(company, entryDto.id());
    journalReplayService.linkReferenceMapping(
        company, idempotencyKey, entry, "DEALER_RECEIPT_SPLIT");
    autoApplyDealerSplitAllocations(company, dealer, entry, total, idempotencyKey);
    accountingAuditService.recordDealerReceiptPostedEventSafe(
        entry, dealer.getId(), total, idempotencyKey);
    return entryDto;
  }

  private JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    AccountingFacade facade = accountingFacadeProvider.getIfAvailable();
    if (facade == null) {
      throw new IllegalStateException("AccountingFacade is required");
    }
    return facade.createStandardJournal(request);
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

  private void applyDealerAllocations(
      Company company,
      Dealer dealer,
      String reference,
      JournalEntry entry,
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
      if (allocation.invoiceId() == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Invoice allocation is required for dealer settlements");
      }
      BigDecimal applied =
          ValidationUtils.requirePositive(allocation.appliedAmount(), "appliedAmount");
      Invoice invoice =
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
                ErrorCode.VALIDATION_INVALID_INPUT, "Allocation exceeds invoice outstanding amount")
            .withDetail("invoiceId", invoice.getId())
            .withDetail("outstanding", currentOutstanding)
            .withDetail("applied", applied);
      }
      remainingByInvoice.put(
          invoice.getId(), currentOutstanding.subtract(applied).max(BigDecimal.ZERO));
      PartnerSettlementAllocation row = new PartnerSettlementAllocation();
      row.setCompany(company);
      row.setPartnerType(PartnerType.DEALER);
      row.setDealer(dealer);
      row.setInvoice(invoice);
      row.setJournalEntry(entry);
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(BigDecimal.ZERO);
      row.setWriteOffAmount(BigDecimal.ZERO);
      row.setFxDifferenceAmount(BigDecimal.ZERO);
      row.setIdempotencyKey(idempotencyKey);
      if (invoice.getCurrency() != null) {
        row.setCurrency(invoice.getCurrency());
      }
      row.setMemo(allocation.memo());
      settlementRows.add(row);
    }
    settlementAllocationRepository.saveAll(settlementRows);
    for (PartnerSettlementAllocation row : settlementRows) {
      if (row.getInvoice() == null) {
        continue;
      }
      String settlementRef = reference + "-INV-" + row.getInvoice().getId();
      invoiceSettlementPolicy.applySettlement(
          row.getInvoice(), row.getAllocationAmount(), settlementRef);
      dealerLedgerService.syncInvoiceLedger(row.getInvoice(), entryDate);
      touchedInvoices.add(row.getInvoice());
    }
    if (!touchedInvoices.isEmpty()) {
      invoiceRepository.saveAll(touchedInvoices);
    }
  }

  private void autoApplyDealerSplitAllocations(
      Company company, Dealer dealer, JournalEntry entry, BigDecimal total, String idempotencyKey) {
    LocalDate entryDate = entry.getEntryDate();
    List<Invoice> openInvoices =
        invoiceRepository.lockOpenInvoicesForSettlement(company, dealer).stream()
            .filter(Objects::nonNull)
            .toList();
    BigDecimal remaining = total;
    List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
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
      row.setSettlementDate(entryDate);
      row.setAllocationAmount(applied);
      row.setDiscountAmount(BigDecimal.ZERO);
      row.setWriteOffAmount(BigDecimal.ZERO);
      row.setFxDifferenceAmount(BigDecimal.ZERO);
      row.setIdempotencyKey(idempotencyKey);
      row.setMemo("Dealer split receipt");
      settlementRows.add(row);
      remaining = remaining.subtract(applied);
    }
    settlementAllocationRepository.saveAll(settlementRows);
    for (PartnerSettlementAllocation row : settlementRows) {
      String settlementRef = entry.getReferenceNumber() + "-INV-" + row.getInvoice().getId();
      invoiceSettlementPolicy.applySettlement(
          row.getInvoice(), row.getAllocationAmount(), settlementRef);
      dealerLedgerService.syncInvoiceLedger(row.getInvoice(), entryDate);
    }
  }
}
