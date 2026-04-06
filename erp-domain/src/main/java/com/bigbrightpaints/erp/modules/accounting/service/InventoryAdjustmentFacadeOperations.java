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
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

@Service
class InventoryAdjustmentFacadeOperations {

  private static final Logger log =
      LoggerFactory.getLogger(InventoryAdjustmentFacadeOperations.class);

  private final CompanyContextService companyContextService;
  private final AccountingService accountingService;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyClock companyClock;
  private final JournalEntryRepository journalEntryRepository;
  private final AccountingFacadeAccountResolver accountResolver;

  InventoryAdjustmentFacadeOperations(
      CompanyContextService companyContextService,
      AccountingService accountingService,
      ReferenceNumberService referenceNumberService,
      CompanyClock companyClock,
      JournalEntryRepository journalEntryRepository,
      AccountingFacadeAccountResolver accountResolver) {
    this.companyContextService = companyContextService;
    this.accountingService = accountingService;
    this.referenceNumberService = referenceNumberService;
    this.companyClock = companyClock;
    this.journalEntryRepository = journalEntryRepository;
    this.accountResolver = accountResolver;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long inventoryAcctId,
      Long varianceAcctId,
      BigDecimal amount,
      String memo) {
    Objects.requireNonNull(adjustmentType, "Adjustment type is required");
    Objects.requireNonNull(referenceId, "Reference ID is required");
    Objects.requireNonNull(inventoryAcctId, "Inventory account ID is required");
    Objects.requireNonNull(varianceAcctId, "Variance account ID is required");
    Objects.requireNonNull(amount, "Amount is required");
    if (amount.compareTo(BigDecimal.ZERO) == 0) {
      log.warn("Skipping inventory adjustment for {} - zero amount", referenceId);
      return null;
    }
    return postInventoryAdjustment(
        adjustmentType,
        referenceId,
        varianceAcctId,
        Map.of(inventoryAcctId, amount.abs()),
        amount.compareTo(BigDecimal.ZERO) > 0,
        false,
        memo,
        null);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long varianceAcctId,
      Map<Long, BigDecimal> inventoryLines,
      boolean increaseInventory,
      boolean adminOverride,
      String memo) {
    return postInventoryAdjustment(
        adjustmentType,
        referenceId,
        varianceAcctId,
        inventoryLines,
        increaseInventory,
        adminOverride,
        memo,
        null);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  JournalEntryDto postInventoryAdjustment(
      String adjustmentType,
      String referenceId,
      Long varianceAcctId,
      Map<Long, BigDecimal> inventoryLines,
      boolean increaseInventory,
      boolean adminOverride,
      String memo,
      LocalDate entryDate) {
    Objects.requireNonNull(adjustmentType, "Adjustment type is required");
    Objects.requireNonNull(referenceId, "Reference ID is required");
    Objects.requireNonNull(varianceAcctId, "Variance account ID is required");
    Objects.requireNonNull(inventoryLines, "Inventory lines are required");
    if (inventoryLines.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Inventory adjustment lines are required");
    }

    Company company = companyContextService.requireCurrentCompany();
    accountResolver.requireAccountById(company, varianceAcctId, "Variance account");
    inventoryLines
        .keySet()
        .forEach(accountId -> accountResolver.requireAccountById(company, accountId, "Inventory account"));

    BigDecimal totalAmount =
        inventoryLines.values().stream()
            .filter(Objects::nonNull)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
      log.warn("Skipping inventory adjustment for {} - zero total amount", referenceId);
      return null;
    }

    String reference =
        StringUtils.hasText(referenceId)
            ? referenceId.trim()
            : referenceNumberService.inventoryAdjustmentReference(company, adjustmentType);
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Inventory adjustment journal already exists for reference: {}", reference);
      return AccountingFacadeJournalSupport.toSimpleDto(existing.orElseThrow());
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : adjustmentType + " adjustment";
    if (increaseInventory) {
      inventoryLines.forEach(
          (accountId, amount) -> {
            BigDecimal absAmount = AccountingFacadeJournalSupport.absoluteAmount(amount);
            if (absAmount.compareTo(BigDecimal.ZERO) > 0) {
              lines.add(
                  new JournalEntryRequest.JournalLineRequest(
                      accountId, resolvedMemo, absAmount, BigDecimal.ZERO));
            }
          });
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              varianceAcctId, resolvedMemo, BigDecimal.ZERO, totalAmount));
    } else {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              varianceAcctId, resolvedMemo, totalAmount, BigDecimal.ZERO));
      inventoryLines.forEach(
          (accountId, amount) -> {
            BigDecimal absAmount = AccountingFacadeJournalSupport.absoluteAmount(amount);
            if (absAmount.compareTo(BigDecimal.ZERO) > 0) {
              lines.add(
                  new JournalEntryRequest.JournalLineRequest(
                      accountId, resolvedMemo, BigDecimal.ZERO, absAmount));
            }
          });
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount,
            AccountingFacadeJournalSupport.resolvePrimaryDebitAccount(lines, varianceAcctId),
            AccountingFacadeJournalSupport.resolvePrimaryCreditAccount(lines, varianceAcctId),
            resolvedMemo,
            "INVENTORY_ADJUSTMENT",
            reference,
            null,
            AccountingFacadeJournalSupport.toStandardLines(lines),
            postingDate,
            null,
            null,
            adminOverride);

    log.info(
        "Posting inventory adjustment journal: reference={}, type={}, amount={}, increase={}",
        reference,
        adjustmentType,
        totalAmount,
        increaseInventory);
    return accountingService.createStandardJournal(request);
  }
}
