package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;

@Service
class FactoryJournalFacadeOperations {

  private static final Logger log = LoggerFactory.getLogger(FactoryJournalFacadeOperations.class);

  private final CompanyContextService companyContextService;
  private final AccountingService accountingService;
  private final JournalEntryRepository journalEntryRepository;
  private final CompanyClock companyClock;
  private final JournalReferenceResolver journalReferenceResolver;
  private final AccountingFacadeAccountResolver accountResolver;

  FactoryJournalFacadeOperations(
      CompanyContextService companyContextService,
      AccountingService accountingService,
      JournalEntryRepository journalEntryRepository,
      CompanyClock companyClock,
      JournalReferenceResolver journalReferenceResolver,
      AccountingFacadeAccountResolver accountResolver) {
    this.companyContextService = companyContextService;
    this.accountingService = accountingService;
    this.journalEntryRepository = journalEntryRepository;
    this.companyClock = companyClock;
    this.journalReferenceResolver = journalReferenceResolver;
    this.accountResolver = accountResolver;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postPackingJournal(
      String reference,
      LocalDate entryDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    if (!StringUtils.hasText(reference) || lines == null || lines.isEmpty()) {
      return null;
    }
    Company company = companyContextService.requireCurrentCompany();
    String resolvedReference = reference.trim();
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, resolvedReference);
    if (existing.isPresent()) {
      log.info("Packing journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "Packing journal " + resolvedReference;
    JournalCreationRequest request =
        new JournalCreationRequest(
            AccountingFacadeJournalSupport.totalLineAmount(lines),
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, null),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(lines, null),
            resolvedMemo,
            "FACTORY_PACKING",
            resolvedReference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            null,
            null,
            Boolean.FALSE);

    log.info("Posting packing journal");
    return accountingService.createStandardJournal(request);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postCostAllocation(
      String batchCode,
      Long finishedGoodsAcctId,
      Long laborExpenseAcctId,
      Long overheadExpenseAcctId,
      BigDecimal laborCost,
      BigDecimal overheadCost,
      String notes) {
    Objects.requireNonNull(batchCode, "Batch code is required");
    Objects.requireNonNull(finishedGoodsAcctId, "Finished goods account ID is required");
    Objects.requireNonNull(laborExpenseAcctId, "Labor expense account ID is required");
    Objects.requireNonNull(overheadExpenseAcctId, "Overhead expense account ID is required");

    BigDecimal laborAmount = laborCost != null ? laborCost : BigDecimal.ZERO;
    BigDecimal overheadAmount = overheadCost != null ? overheadCost : BigDecimal.ZERO;
    BigDecimal totalAmount = laborAmount.add(overheadAmount);
    if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Skipping cost allocation journal because total amount is zero");
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();
    accountResolver.requireAccountById(company, finishedGoodsAcctId, "Finished goods account");
    accountResolver.requireAccountById(company, laborExpenseAcctId, "Labor expense account");
    accountResolver.requireAccountById(company, overheadExpenseAcctId, "Overhead expense account");

    String reference = "CAL-" + AccountingFacadeJournalSupport.sanitize(batchCode);
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Cost allocation journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = notes != null ? notes : "Cost allocation for " + batchCode;
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            finishedGoodsAcctId,
            "Allocated costs to finished goods",
            totalAmount,
            BigDecimal.ZERO));
    if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              laborExpenseAcctId,
              "Labor cost allocated to production",
              BigDecimal.ZERO,
              laborAmount));
    }
    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              overheadExpenseAcctId,
              "Overhead cost allocated to production",
              BigDecimal.ZERO,
              overheadAmount));
    }

    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount,
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, finishedGoodsAcctId),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(lines, finishedGoodsAcctId),
            memo,
            "FACTORY_COST_ALLOCATION",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            companyClock.today(company),
            null,
            null,
            Boolean.FALSE);

    log.info("Posting cost allocation journal");
    return accountingService.createStandardJournal(request);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postCOGS(
      String referenceId,
      Long dealerId,
      Long cogsAccountId,
      Long inventoryAcctId,
      BigDecimal cost,
      String memo) {
    Objects.requireNonNull(referenceId, "Reference ID is required");
    Objects.requireNonNull(cogsAccountId, "COGS account ID is required");
    Objects.requireNonNull(inventoryAcctId, "Inventory account ID is required");
    Objects.requireNonNull(cost, "Cost is required");

    if (cost.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Skipping COGS journal because cost is zero");
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();
    accountResolver.requireAccountById(company, cogsAccountId, "COGS account");
    accountResolver.requireAccountById(company, inventoryAcctId, "Inventory account");

    String reference = resolveCogsReference(referenceId);
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, reference);
    if (existing.isPresent()) {
      log.info("COGS journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines =
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                cogsAccountId,
                memo != null ? memo : "COGS for " + referenceId,
                cost,
                BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                inventoryAcctId,
                memo != null ? memo : "COGS for " + referenceId,
                BigDecimal.ZERO,
                cost));

    JournalCreationRequest request =
        new JournalCreationRequest(
            cost.abs(),
            cogsAccountId,
            inventoryAcctId,
            memo != null ? memo : "COGS for " + referenceId,
            "SALES_DISPATCH",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            companyClock.today(company),
            dealerId,
            null,
            Boolean.FALSE);

    log.info("Posting COGS journal");
    return accountingService.createStandardJournal(request);
  }

  JournalEntryDto postCOGS(
      String referenceId, Long cogsAccountId, Long inventoryAcctId, BigDecimal cost, String memo) {
    return postCOGS(referenceId, null, cogsAccountId, inventoryAcctId, cost, memo);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postCogsJournal(
      String referenceId,
      Long dealerId,
      LocalDate entryDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    if (!StringUtils.hasText(referenceId) || lines == null || lines.isEmpty()) {
      return null;
    }
    Company company = companyContextService.requireCurrentCompany();
    String reference = resolveCogsReference(referenceId);
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, reference);
    if (existing.isPresent()) {
      log.info("COGS journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    LocalDate effectiveDate = entryDate != null ? entryDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "COGS for " + referenceId;
    JournalCreationRequest request =
        new JournalCreationRequest(
            AccountingFacadeJournalSupport.totalLineAmount(lines),
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, null),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(lines, null),
            resolvedMemo,
            "SALES_DISPATCH",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            effectiveDate,
            dealerId,
            null,
            Boolean.FALSE);

    log.info("Posting consolidated COGS journal");
    return accountingService.createStandardJournal(request);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postCostVarianceAllocation(
      String batchCode,
      String periodKey,
      LocalDate entryDate,
      Long finishedGoodsAcctId,
      Long laborExpenseAcctId,
      Long overheadExpenseAcctId,
      BigDecimal laborVariance,
      BigDecimal overheadVariance,
      String notes) {
    Objects.requireNonNull(batchCode, "Batch code is required");
    Objects.requireNonNull(periodKey, "Period key is required");
    Objects.requireNonNull(finishedGoodsAcctId, "Finished goods account ID is required");
    Objects.requireNonNull(laborExpenseAcctId, "Labor expense account ID is required");
    Objects.requireNonNull(overheadExpenseAcctId, "Overhead expense account ID is required");

    BigDecimal laborAmount = laborVariance != null ? laborVariance : BigDecimal.ZERO;
    BigDecimal overheadAmount = overheadVariance != null ? overheadVariance : BigDecimal.ZERO;
    BigDecimal totalAmount = laborAmount.add(overheadAmount);
    if (laborAmount.compareTo(BigDecimal.ZERO) == 0
        && overheadAmount.compareTo(BigDecimal.ZERO) == 0) {
      log.info("Skipping variance allocation journal because total variance is zero");
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();
    accountResolver.requireAccountById(company, finishedGoodsAcctId, "Finished goods account");
    accountResolver.requireAccountById(company, laborExpenseAcctId, "Labor expense account");
    accountResolver.requireAccountById(company, overheadExpenseAcctId, "Overhead expense account");

    String reference =
        "CVAR-" + AccountingFacadeJournalSupport.sanitize(batchCode) + "-" + periodKey;
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Cost variance journal already exists");
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = notes != null ? notes : "Cost variance allocation for " + batchCode;
    if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              finishedGoodsAcctId,
              "Underapplied labor/overhead",
              totalAmount.abs(),
              BigDecimal.ZERO));
    } else {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              finishedGoodsAcctId,
              "Overapplied labor/overhead",
              BigDecimal.ZERO,
              totalAmount.abs()));
    }
    if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              laborExpenseAcctId, "Labor variance allocated", BigDecimal.ZERO, laborAmount.abs()));
    } else if (laborAmount.compareTo(BigDecimal.ZERO) < 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              laborExpenseAcctId, "Labor variance allocated", laborAmount.abs(), BigDecimal.ZERO));
    }
    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              overheadExpenseAcctId,
              "Overhead variance allocated",
              BigDecimal.ZERO,
              overheadAmount.abs()));
    } else if (overheadAmount.compareTo(BigDecimal.ZERO) < 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              overheadExpenseAcctId,
              "Overhead variance allocated",
              overheadAmount.abs(),
              BigDecimal.ZERO));
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    JournalCreationRequest request =
        new JournalCreationRequest(
            AccountingFacadeJournalSupport.totalLineAmount(lines),
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, finishedGoodsAcctId),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(lines, finishedGoodsAcctId),
            memo,
            "FACTORY_COST_VARIANCE",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            null,
            null,
            Boolean.FALSE);

    log.info("Posting cost variance journal");
    return accountingService.createStandardJournal(request);
  }

  @Transactional(readOnly = true)
  Optional<String> findExistingCostVarianceReference(String batchCode, String periodKey) {
    Objects.requireNonNull(batchCode, "Batch code is required");
    Objects.requireNonNull(periodKey, "Period key is required");
    Company company = companyContextService.requireCurrentCompany();
    String reference =
        "CVAR-" + AccountingFacadeJournalSupport.sanitize(batchCode) + "-" + periodKey;
    return journalEntryRepository
        .findByCompanyAndReferenceNumber(company, reference)
        .map(JournalEntry::getReferenceNumber);
  }

  boolean hasCogsJournalFor(String referenceId) {
    Company company = companyContextService.requireCurrentCompany();
    return journalReferenceResolver.exists(company, resolveCogsReference(referenceId));
  }

  private String resolveCogsReference(String referenceId) {
    String normalized = AccountingFacadeJournalSupport.sanitize(referenceId);
    if (normalized.startsWith("COGS-")) {
      normalized = normalized.substring("COGS-".length());
    }
    return SalesOrderReference.cogsReference(normalized);
  }
}
