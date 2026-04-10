package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;

@Service
class SalesJournalFacadeOperations {

  private static final Logger log = LoggerFactory.getLogger(SalesJournalFacadeOperations.class);
  private static final BigDecimal BALANCE_TOLERANCE = BigDecimal.ZERO;

  private final CompanyContextService companyContextService;
  private final AccountingService accountingService;
  private final CompanyClock companyClock;
  private final CompanyScopedSalesLookupService salesLookupService;
  private final AccountingFacadeTaxSupport taxSupport;
  private final JournalReferenceResolver journalReferenceResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;
  private final CompanyScopedAccountingLookupService accountingLookupService;

  SalesJournalFacadeOperations(
      CompanyContextService companyContextService,
      AccountingService accountingService,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      AccountingFacadeTaxSupport taxSupport,
      JournalReferenceResolver journalReferenceResolver,
      JournalEntryRepository journalEntryRepository,
      JournalReferenceMappingRepository journalReferenceMappingRepository,
      CompanyScopedAccountingLookupService accountingLookupService) {
    this.companyContextService = companyContextService;
    this.accountingService = accountingService;
    this.companyClock = companyClock;
    this.salesLookupService = salesLookupService;
    this.taxSupport = taxSupport;
    this.journalReferenceResolver = journalReferenceResolver;
    this.journalEntryRepository = journalEntryRepository;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
    this.accountingLookupService = accountingLookupService;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(
      value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class},
      maxAttempts = 5,
      backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return postSalesJournal(
        dealerId,
        orderNumber,
        entryDate,
        memo,
        revenueLines,
        taxLines,
        null,
        null,
        totalAmount,
        referenceNumber);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(
      value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class},
      maxAttempts = 5,
      backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      Map<Long, BigDecimal> discountLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return postSalesJournal(
        dealerId,
        orderNumber,
        entryDate,
        memo,
        revenueLines,
        taxLines,
        discountLines,
        null,
        totalAmount,
        referenceNumber);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(
      value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class},
      maxAttempts = 5,
      backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  JournalEntryDto postSalesJournal(
      Long dealerId,
      String orderNumber,
      LocalDate entryDate,
      String memo,
      Map<Long, BigDecimal> revenueLines,
      Map<Long, BigDecimal> taxLines,
      Map<Long, BigDecimal> discountLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount,
      String referenceNumber) {
    Objects.requireNonNull(dealerId, "Dealer ID is required");
    Objects.requireNonNull(orderNumber, "Order number is required");
    Objects.requireNonNull(totalAmount, "Total amount is required");

    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer = salesLookupService.requireDealer(company, dealerId);

    if (dealer.getReceivableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Dealer missing receivable account")
          .withDetail("dealerId", dealerId)
          .withDetail("dealerName", dealer.getName());
    }

    String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
    String aliasReference = null;
    if (StringUtils.hasText(referenceNumber)) {
      aliasReference = referenceNumber.trim();
      if (aliasReference.equalsIgnoreCase(canonicalReference)) {
        aliasReference = null;
      }
    }

    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, canonicalReference);
    if (existing.isEmpty() && StringUtils.hasText(aliasReference)) {
      existing = journalReferenceResolver.findExistingEntry(company, aliasReference);
    }
    if (existing.isEmpty()) {
      boolean reservationLeader = reserveSalesJournalReference(company, canonicalReference);
      if (!reservationLeader) {
        existing = resolveReservedSalesJournalEntry(company, canonicalReference);
        if (existing.isEmpty()) {
          throw new ApplicationException(
                  ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                  "Sales journal reference is reserved but journal entry not found")
              .withDetail("referenceNumber", canonicalReference);
        }
      }
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : "Sales order " + orderNumber;
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            dealer.getReceivableAccount().getId(),
            resolvedMemo,
            totalAmount.abs(),
            BigDecimal.ZERO));

    if (discountLines != null) {
      discountLines.forEach(
          (accountId, amount) -> {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
              if (accountId == null) {
                throw new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_INPUT,
                        "Discount account is required for discount lines")
                    .withDetail("orderNumber", orderNumber);
              }
              lines.add(
                  new JournalEntryRequest.JournalLineRequest(
                      accountId, resolvedMemo, amount.abs(), BigDecimal.ZERO));
            }
          });
    }

    if (revenueLines != null) {
      revenueLines.forEach(
          (accountId, amount) -> {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
              lines.add(
                  new JournalEntryRequest.JournalLineRequest(
                      accountId, resolvedMemo, BigDecimal.ZERO, amount.abs()));
            }
          });
    }

    taxSupport.appendSalesTaxLines(lines, taxLines, gstBreakdown, resolvedMemo, orderNumber);

    BigDecimal totalDebits = AccountingFacadeJournalSupport.calculateTotalDebits(lines);
    BigDecimal totalCredits = AccountingFacadeJournalSupport.calculateTotalCredits(lines);
    if (totalDebits.subtract(totalCredits).abs().compareTo(BALANCE_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Sales journal does not balance")
          .withDetail("totalAmount", totalAmount)
          .withDetail("totalDebits", totalDebits)
          .withDetail("totalCredits", totalCredits);
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    String requestReference =
        existing.map(JournalEntry::getReferenceNumber).orElse(canonicalReference);
    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount.abs(),
            dealer.getReceivableAccount().getId(),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(
                lines, dealer.getReceivableAccount().getId()),
            resolvedMemo,
            "SALES",
            requestReference,
            gstBreakdown,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            dealer.getId(),
            null,
            Boolean.FALSE);

    if (existing.isPresent()) {
      JournalEntryDto replay = accountingService.createStandardJournal(request);
      JournalEntry mappedEntry = existing.get();
      if (replay != null && replay.id() != null) {
        mappedEntry = accountingLookupService.requireJournalEntry(company, replay.id());
      }
      ensureSalesJournalReferenceMapping(company, mappedEntry, canonicalReference, aliasReference);
      return replay;
    }

    log.info("Posting sales journal");

    JournalEntryDto created = accountingService.createStandardJournal(request);
    if (created != null && created.id() != null) {
      JournalEntry entry = accountingLookupService.requireJournalEntry(company, created.id());
      ensureSalesJournalReferenceMapping(company, entry, canonicalReference, aliasReference);
    }
    return created;
  }

  private void ensureSalesJournalReferenceMapping(
      Company company, JournalEntry entry, String canonicalReference, String aliasReference) {
    if (company == null || entry == null || !StringUtils.hasText(canonicalReference)) {
      return;
    }
    String canonical = canonicalReference.trim();
    upsertJournalReferenceMapping(company, canonical, canonical, entry);
    if (StringUtils.hasText(aliasReference) && !aliasReference.equalsIgnoreCase(canonical)) {
      upsertJournalReferenceMapping(company, aliasReference, canonical, entry);
    }
    String entryReference = entry.getReferenceNumber();
    if (StringUtils.hasText(entryReference) && !entryReference.equalsIgnoreCase(canonical)) {
      upsertJournalReferenceMapping(company, entryReference, canonical, entry);
    }
  }

  void upsertJournalReferenceMapping(
      Company company, String legacyReference, String canonicalReference, JournalEntry entry) {
    if (company == null
        || entry == null
        || !StringUtils.hasText(legacyReference)
        || !StringUtils.hasText(canonicalReference)) {
      return;
    }
    String normalizedLegacy = legacyReference.trim();
    Optional<JournalReferenceMapping> existing =
        journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, normalizedLegacy);
    if (existing.isPresent()) {
      JournalReferenceMapping mapping = existing.get();
      if (mapping.getEntityId() == null
          || !StringUtils.hasText(mapping.getCanonicalReference())
          || mapping.getCanonicalReference().equalsIgnoreCase(canonicalReference.trim())) {
        mapping.setCanonicalReference(canonicalReference.trim());
        mapping.setEntityType("JOURNAL_ENTRY");
        mapping.setEntityId(entry.getId());
        journalReferenceMappingRepository.save(mapping);
      }
      return;
    }
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference(normalizedLegacy);
    mapping.setCanonicalReference(canonicalReference.trim());
    mapping.setEntityType("JOURNAL_ENTRY");
    mapping.setEntityId(entry.getId());
    try {
      journalReferenceMappingRepository.save(mapping);
    } catch (DataIntegrityViolationException ex) {
      // Ignore concurrent insert attempts for the same legacy reference.
    }
  }

  boolean reserveSalesJournalReference(Company company, String canonicalReference) {
    if (company == null || company.getId() == null || !StringUtils.hasText(canonicalReference)) {
      return true;
    }
    String canonical = canonicalReference.trim();
    Optional<JournalReferenceMapping> existing =
        journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, canonical);
    if (existing.isPresent()) {
      return false;
    }
    int reserved =
        journalReferenceMappingRepository.reserveReferenceMapping(
            company.getId(), canonical, canonical, "SALES_JOURNAL", CompanyTime.now(company));
    if (reserved == 1) {
      return true;
    }
    if (journalReferenceMappingRepository
        .findByCompanyAndLegacyReferenceIgnoreCase(company, canonical)
        .isPresent()) {
      return false;
    }
    throw new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Sales journal reference already reserved but mapping not found")
        .withDetail("referenceNumber", canonical);
  }

  Optional<JournalEntry> resolveReservedSalesJournalEntry(
      Company company, String canonicalReference) {
    if (company == null || !StringUtils.hasText(canonicalReference)) {
      return Optional.empty();
    }
    String canonical = canonicalReference.trim();
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, canonical);
    if (existing.isPresent()) {
      return existing;
    }
    Optional<JournalReferenceMapping> mapping =
        journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, canonical);
    if (mapping.isEmpty()) {
      return Optional.empty();
    }
    if (mapping.get().getEntityId() != null) {
      Optional<JournalEntry> byId =
          journalEntryRepository.findByCompanyAndId(company, mapping.get().getEntityId());
      if (byId.isPresent()) {
        return byId;
      }
    }
    return StringUtils.hasText(mapping.get().getCanonicalReference())
        ? journalReferenceResolver.findExistingEntry(company, mapping.get().getCanonicalReference())
        : Optional.empty();
  }
}
