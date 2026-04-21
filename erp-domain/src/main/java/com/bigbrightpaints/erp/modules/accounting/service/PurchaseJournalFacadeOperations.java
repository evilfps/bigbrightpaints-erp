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
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;

@Service
class PurchaseJournalFacadeOperations {

  private static final Logger log = LoggerFactory.getLogger(PurchaseJournalFacadeOperations.class);
  private static final BigDecimal BALANCE_TOLERANCE = BigDecimal.ZERO;

  private final CompanyContextService companyContextService;
  private final AccountingService accountingService;
  private final JournalEntryRepository journalEntryRepository;
  private final ReferenceNumberService referenceNumberService;
  private final SupplierRepository supplierRepository;
  private final CompanyClock companyClock;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final JournalReferenceResolver journalReferenceResolver;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;
  private final AccountingFacadeTaxSupport taxSupport;

  PurchaseJournalFacadeOperations(
      CompanyContextService companyContextService,
      AccountingService accountingService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyScopedAccountingLookupService accountingLookupService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository,
      AccountingFacadeTaxSupport taxSupport) {
    this.companyContextService = companyContextService;
    this.accountingService = accountingService;
    this.journalEntryRepository = journalEntryRepository;
    this.referenceNumberService = referenceNumberService;
    this.supplierRepository = supplierRepository;
    this.companyClock = companyClock;
    this.accountingLookupService = accountingLookupService;
    this.journalReferenceResolver = journalReferenceResolver;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
    this.taxSupport = taxSupport;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      BigDecimal totalAmount) {
    return postPurchaseJournal(
        supplierId,
        invoiceNumber,
        invoiceDate,
        memo,
        inventoryLines,
        null,
        null,
        totalAmount,
        null);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      Map<Long, BigDecimal> taxLines,
      BigDecimal totalAmount,
      String referenceNumber) {
    return postPurchaseJournal(
        supplierId,
        invoiceNumber,
        invoiceDate,
        memo,
        inventoryLines,
        taxLines,
        null,
        totalAmount,
        referenceNumber);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseJournal(
      Long supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String memo,
      Map<Long, BigDecimal> inventoryLines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount,
      String referenceNumber) {
    Objects.requireNonNull(supplierId, "Supplier ID is required");
    Objects.requireNonNull(invoiceNumber, "Invoice number is required");
    Objects.requireNonNull(totalAmount, "Total amount is required");

    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, supplierId);

    String baseReference =
        referenceNumberService.purchaseReferenceKey(company, supplier, invoiceNumber);
    Optional<JournalEntry> existingByBase =
        journalReferenceResolver.findExistingEntry(company, baseReference);
    if (existingByBase.isPresent()) {
      return AccountingFacadeJournalSupport.toSimpleDto(existingByBase.get());
    }

    supplier.requireTransactionalUsage("post purchase journals");

    if (inventoryLines == null || inventoryLines.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Inventory lines are required for purchase journal");
    }

    if (supplier.getPayableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Supplier missing payable account")
          .withDetail("supplierId", supplierId)
          .withDetail("supplierName", supplier.getName());
    }

    String reference = normalizeOptionalReference(referenceNumber);
    if (reference == null) {
      reference = referenceNumberService.purchaseReference(company, supplier, invoiceNumber);
    }
    if (!StringUtils.hasText(reference)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase journal reference is required");
    }
    reference = reference.trim();

    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Purchase journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : "Purchase invoice " + invoiceNumber;

    BigDecimal inventoryTotal = BigDecimal.ZERO;
    for (Map.Entry<Long, BigDecimal> entry : inventoryLines.entrySet()) {
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                entry.getKey(), resolvedMemo, amount.abs(), BigDecimal.ZERO));
        inventoryTotal = inventoryTotal.add(amount.abs());
      }
    }

    BigDecimal taxTotal =
        taxSupport.appendPurchaseTaxLines(lines, taxLines, gstBreakdown, resolvedMemo);
    if (inventoryTotal.add(taxTotal).compareTo(totalAmount.abs()) != 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Purchase totals do not balance inventory+tax to payable total")
          .withDetail("inventoryTotal", inventoryTotal)
          .withDetail("taxTotal", taxTotal)
          .withDetail("totalAmount", totalAmount);
    }

    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            supplier.getPayableAccount().getId(),
            resolvedMemo,
            BigDecimal.ZERO,
            totalAmount.abs()));

    LocalDate postingDate = invoiceDate != null ? invoiceDate : companyClock.today(company);
    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount.abs(),
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(
                lines, supplier.getPayableAccount().getId()),
            supplier.getPayableAccount().getId(),
            resolvedMemo,
            "PURCHASING",
            reference,
            taxSupport.resolvePurchaseBreakdown(inventoryTotal, taxTotal, gstBreakdown),
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            null,
            supplier.getId(),
            Boolean.FALSE);

    log.info("Posting purchase journal");

    JournalEntryDto entry = accountingService.createStandardJournal(request);
    JournalEntry saved = accountingLookupService.requireJournalEntry(company, entry.id());
    ensurePurchaseReferenceMapping(company, baseReference, saved);
    return entry;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      BigDecimal totalAmount) {
    return postPurchaseReturn(
        supplierId, referenceNumber, returnDate, memo, inventoryCredits, null, null, totalAmount);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      Map<Long, BigDecimal> taxCredits,
      BigDecimal totalAmount) {
    return postPurchaseReturn(
        supplierId,
        referenceNumber,
        returnDate,
        memo,
        inventoryCredits,
        taxCredits,
        null,
        totalAmount);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      Map<Long, BigDecimal> taxCredits,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      BigDecimal totalAmount) {
    Objects.requireNonNull(supplierId, "Supplier ID is required");
    Objects.requireNonNull(totalAmount, "Total amount is required");
    Objects.requireNonNull(inventoryCredits, "Inventory credits are required");

    if (inventoryCredits.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "At least one inventory line is required for purchase return");
    }

    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = requireSupplier(company, supplierId);
    String reference = normalizeOptionalReference(referenceNumber);
    if (reference == null) {
      reference = referenceNumberService.purchaseReturnReference(company, supplier);
    }
    if (!StringUtils.hasText(reference)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase return reference is required");
    }
    reference = reference.trim();

    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      JournalEntry existingEntry = existing.orElseThrow();
      log.info("Purchase return journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existingEntry);
    }

    LocalDate postingDate = returnDate != null ? returnDate : companyClock.today(company);
    String resolvedMemo = memo != null ? memo : "Purchase return for " + supplier.getName();
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();

    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            supplier.getPayableAccount().getId(),
            resolvedMemo,
            totalAmount.abs(),
            BigDecimal.ZERO));

    BigDecimal totalCredits = BigDecimal.ZERO;
    for (Map.Entry<Long, BigDecimal> entry : inventoryCredits.entrySet()) {
      Long accountId = entry.getKey();
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        totalCredits = totalCredits.add(amount.abs());
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                accountId, resolvedMemo, BigDecimal.ZERO, amount.abs()));
      }
    }

    totalCredits =
        totalCredits.add(
            taxSupport.appendPurchaseReturnTaxLines(lines, taxCredits, gstBreakdown, resolvedMemo));

    if (totalAmount.subtract(totalCredits).abs().compareTo(BALANCE_TOLERANCE) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT, "Purchase return journal does not balance")
          .withDetail("totalAmount", totalAmount)
          .withDetail("totalCredits", totalCredits);
    }

    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount.abs(),
            supplier.getPayableAccount().getId(),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(
                lines, supplier.getPayableAccount().getId()),
            resolvedMemo,
            "PURCHASING_RETURN",
            reference,
            gstBreakdown,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            null,
            supplier.getId(),
            Boolean.FALSE);

    log.info("Posting purchase return journal");

    return accountingService.createStandardJournal(request);
  }

  private void ensurePurchaseReferenceMapping(
      Company company, String baseReference, JournalEntry entry) {
    if (company == null || entry == null || !StringUtils.hasText(baseReference)) {
      return;
    }
    String canonicalReference = entry.getReferenceNumber();
    if (!StringUtils.hasText(canonicalReference)) {
      return;
    }
    if (journalReferenceMappingRepository
        .findByCompanyAndLegacyReferenceIgnoreCase(company, baseReference)
        .isPresent()) {
      return;
    }
    JournalReferenceMapping mapping = new JournalReferenceMapping();
    mapping.setCompany(company);
    mapping.setLegacyReference(baseReference.trim());
    mapping.setCanonicalReference(canonicalReference.trim());
    mapping.setEntityType("PURCHASE_JOURNAL");
    mapping.setEntityId(entry.getId());
    journalReferenceMappingRepository.save(mapping);
  }

  private Supplier requireSupplier(Company company, Long supplierId) {
    return supplierRepository
        .findByCompanyAndIdWithPayableAccount(company, supplierId)
        .orElseThrow(
            () ->
                new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Supplier not found")
                    .withDetail("supplierId", supplierId));
  }

  private String normalizeOptionalReference(String referenceNumber) {
    if (!StringUtils.hasText(referenceNumber)) {
      return null;
    }
    return referenceNumber.trim();
  }
}
