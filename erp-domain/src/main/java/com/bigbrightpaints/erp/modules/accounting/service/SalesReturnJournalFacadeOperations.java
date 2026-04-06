package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
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

@Service
class SalesReturnJournalFacadeOperations {

  private static final Logger log =
      LoggerFactory.getLogger(SalesReturnJournalFacadeOperations.class);

  private final CompanyContextService companyContextService;
  private final AccountingService accountingService;
  private final CompanyClock companyClock;
  private final CompanyScopedSalesLookupService salesLookupService;
  private final JournalReferenceResolver journalReferenceResolver;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;

  SalesReturnJournalFacadeOperations(
      CompanyContextService companyContextService,
      AccountingService accountingService,
      CompanyClock companyClock,
      CompanyScopedSalesLookupService salesLookupService,
      JournalReferenceResolver journalReferenceResolver,
      JournalEntryRepository journalEntryRepository,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    this.companyContextService = companyContextService;
    this.accountingService = accountingService;
    this.companyClock = companyClock;
    this.salesLookupService = salesLookupService;
    this.journalReferenceResolver = journalReferenceResolver;
    this.journalEntryRepository = journalEntryRepository;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postSalesReturn(
      Long dealerId,
      String invoiceNumber,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    Objects.requireNonNull(dealerId, "Dealer ID is required");
    Objects.requireNonNull(invoiceNumber, "Invoice number is required");
    Objects.requireNonNull(totalAmount, "Total amount is required");

    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer = salesLookupService.requireDealer(company, dealerId);
    if (dealer.getReceivableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Dealer missing receivable account")
          .withDetail("dealerId", dealerId);
    }

    String baseReference = "CRN-" + AccountingFacadeJournalSupport.sanitize(invoiceNumber);
    String hashReference =
        buildSalesReturnHashReference(
            invoiceNumber, dealer.getId(), returnLines, totalAmount, reason);
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, hashReference);
    if (existing.isPresent()) {
      log.info(
          "Sales return journal already exists for reference: {}",
          existing.get().getReferenceNumber());
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    String reference = resolveSalesReturnReference(company, baseReference, hashReference);
    Optional<JournalEntry> existingByRef =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existingByRef.isPresent()) {
      log.info("Sales return journal already exists for reference: {}", reference);
      return AccountingFacadeJournalSupport.toSimpleDto(existingByRef.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String reasonSuffix = StringUtils.hasText(reason) ? reason.trim() : "Return";
    String memo = reasonSuffix + " - " + invoiceNumber;
    if (returnLines != null) {
      returnLines.forEach(
          (accountId, amount) -> {
            if (amount != null && amount.compareTo(BigDecimal.ZERO) != 0) {
              BigDecimal debit =
                  amount.compareTo(BigDecimal.ZERO) > 0 ? amount.abs() : BigDecimal.ZERO;
              BigDecimal credit =
                  amount.compareTo(BigDecimal.ZERO) < 0 ? amount.abs() : BigDecimal.ZERO;
              lines.add(new JournalEntryRequest.JournalLineRequest(accountId, memo, debit, credit));
            }
          });
    }
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            dealer.getReceivableAccount().getId(), memo, BigDecimal.ZERO, totalAmount.abs()));

    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount.abs(),
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, null),
            dealer.getReceivableAccount().getId(),
            memo,
            "SALES_RETURN",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            companyClock.today(company),
            dealer.getId(),
            null,
            Boolean.FALSE);

    log.info(
        "Posting sales return journal: reference={}, dealer={}, amount={}",
        reference,
        dealer.getName(),
        totalAmount);

    JournalEntryDto created = accountingService.createStandardJournal(request);
    JournalEntry persisted =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElse(null);
    if (persisted != null) {
      ensureSalesReturnReferenceMapping(company, hashReference, reference, persisted.getId());
    }
    return created;
  }

  private String buildSalesReturnHashReference(
      String invoiceNumber,
      Long dealerId,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    String base = "CRN-" + AccountingFacadeJournalSupport.sanitize(invoiceNumber);
    IdempotencySignatureBuilder fingerprint =
        IdempotencySignatureBuilder.create()
            .add(base)
            .add("dealer=" + (dealerId != null ? dealerId : "NA"))
            .add("total=" + IdempotencyUtils.normalizeDecimal(totalAmount))
            .add("reason=" + IdempotencyUtils.normalizeToken(reason));
    if (returnLines != null && !returnLines.isEmpty()) {
      returnLines.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry ->
                  fingerprint.add(
                      "acc="
                          + entry.getKey()
                          + ":"
                          + IdempotencyUtils.normalizeDecimal(entry.getValue())));
    }
    String hash = IdempotencyUtils.sha256Hex(fingerprint.buildPayload());
    return base + "-H" + hash.substring(0, 12);
  }

  private String resolveSalesReturnReference(
      Company company, String baseReference, String hashReference) {
    if (!StringUtils.hasText(baseReference)) {
      return "CRN-GEN";
    }
    Optional<JournalEntry> baseEntry =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, baseReference);
    if (baseEntry.isEmpty()) {
      return baseReference;
    }
    if (StringUtils.hasText(hashReference)) {
      return hashReference.trim();
    }
    String prefix = baseReference + "-R";
    List<JournalEntry> existingReturns =
        journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, prefix);
    int maxIndex = 0;
    for (JournalEntry entry : existingReturns) {
      String ref = entry.getReferenceNumber();
      if (ref == null || !ref.startsWith(prefix)) {
        continue;
      }
      String suffix = ref.substring(prefix.length());
      if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
        continue;
      }
      int index = Integer.parseInt(suffix);
      if (index > maxIndex) {
        maxIndex = index;
      }
    }
    return prefix + (maxIndex + 1);
  }

  private void ensureSalesReturnReferenceMapping(
      Company company, String legacyReference, String canonicalReference, Long entryId) {
    if (company == null
        || !StringUtils.hasText(legacyReference)
        || !StringUtils.hasText(canonicalReference)) {
      return;
    }
    if (journalReferenceMappingRepository
        .findByCompanyAndLegacyReferenceIgnoreCase(company, legacyReference.trim())
        .isPresent()) {
      return;
    }
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference(legacyReference.trim());
    mapping.setCanonicalReference(canonicalReference.trim());
    mapping.setEntityType("JOURNAL_ENTRY");
    mapping.setEntityId(entryId);
    journalReferenceMappingRepository.save(mapping);
  }
}
