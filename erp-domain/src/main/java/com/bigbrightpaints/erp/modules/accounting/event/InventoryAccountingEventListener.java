package com.bigbrightpaints.erp.modules.accounting.event;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryMovementEvent;
import com.bigbrightpaints.erp.modules.inventory.event.InventoryValuationChangedEvent;

/**
 * Listens to inventory domain events and creates corresponding GL entries.
 * Ensures tight integration between Inventory and Accounting modules.
 *
 * This eliminates the need for manual/hardcoded GL postings in inventory operations.
 */
@Component
@ConditionalOnProperty(
    prefix = "erp.inventory.accounting",
    name = "events.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InventoryAccountingEventListener {

  private static final Logger log = LoggerFactory.getLogger(InventoryAccountingEventListener.class);

  private final AccountingService accountingService;
  private final AccountRepository accountRepository;
  private final CompanyRepository companyRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final CompanyClock companyClock;

  public InventoryAccountingEventListener(
      AccountingService accountingService,
      AccountRepository accountRepository,
      CompanyRepository companyRepository,
      JournalEntryRepository journalEntryRepository,
      CompanyClock companyClock) {
    this.accountingService = accountingService;
    this.accountRepository = accountRepository;
    this.companyRepository = companyRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.companyClock = companyClock;
  }

  /**
   * Handle inventory valuation changes (revaluations, cost method changes, etc.)
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void onInventoryValuationChanged(InventoryValuationChangedEvent event) {
    log.info(
        "Processing inventory valuation change: {} {} - {} from {} to {}",
        event.inventoryType(),
        event.itemCode(),
        event.reason(),
        event.oldValue(),
        event.newValue());

    BigDecimal valueChange = event.getValueChange();
    if (valueChange.compareTo(BigDecimal.ZERO) == 0) {
      log.debug("No value change, skipping journal entry");
      return;
    }

    Company company = null;
    try {
      company =
          companyRepository
              .findById(event.companyId())
              .orElseThrow(
                  () -> new IllegalStateException("Company not found: " + event.companyId()));

      // Set company context for the accounting service
      CompanyContextHolder.setCompanyCode(company.getCode());

      // Build idempotent reference from source event
      String refNumber = buildIdempotentRevalReference(event);

      // Check if journal already exists (idempotency check)
      if (journalEntryRepository.findByCompanyAndReferenceNumber(company, refNumber).isPresent()) {
        log.info("Journal entry {} already exists, skipping duplicate", refNumber);
        return;
      }

      // Get revaluation account (expense for decreases, income for increases)
      Account revaluationAccount = getRevaluationAccount(company, event.reason());
      Account inventoryAccount =
          accountRepository
              .findByCompanyAndId(company, event.inventoryAccountId())
              .orElseThrow(
                  () -> new IllegalStateException("Inventory account not found for company"));

      String memo = buildRevaluationMemo(event);

      List<JournalEntryRequest.JournalLineRequest> lines;
      if (event.isIncrease()) {
        // Increase: Debit Inventory, Credit Revaluation Gain
        lines =
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    inventoryAccount.getId(),
                    "Inventory revaluation - " + event.itemName(),
                    valueChange.abs(),
                    BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    revaluationAccount.getId(),
                    "Revaluation gain - " + event.reason(),
                    BigDecimal.ZERO,
                    valueChange.abs()));
      } else {
        // Decrease: Debit Revaluation Loss, Credit Inventory
        lines =
            List.of(
                new JournalEntryRequest.JournalLineRequest(
                    revaluationAccount.getId(),
                    "Revaluation loss - " + event.reason(),
                    valueChange.abs(),
                    BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(
                    inventoryAccount.getId(),
                    "Inventory revaluation - " + event.itemName(),
                    BigDecimal.ZERO,
                    valueChange.abs()));
      }

      LocalDate entryDate = companyClock.dateForInstant(company, event.timestamp());
      JournalEntryRequest request =
          new JournalEntryRequest(refNumber, entryDate, memo, null, null, false, lines);

      accountingService.createJournalEntry(request);
      log.info("Created revaluation journal entry: {}", refNumber);

    } catch (Exception e) {
      log.error(
          "Failed to create revaluation journal entry for {}: {}",
          event.itemCode(),
          e.getMessage(),
          e);
    } finally {
      // Always clear company context to prevent thread pollution
      CompanyContextHolder.clear();
    }
  }

  /**
   * Handle inventory movements (receipts, issues, transfers)
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void onInventoryMovement(InventoryMovementEvent event) {
    if (event.totalCost().compareTo(BigDecimal.ZERO) == 0) {
      log.debug("Zero cost movement, skipping journal entry");
      return;
    }

    if (isCanonicalWorkflowMovement(event)) {
      log.debug(
          "Canonical workflow movement {}, skipping listener auto-posting",
          event.relatedEntityType());
      return;
    }

    // Skip if accounts not specified (let caller handle GL posting)
    if (event.sourceAccountId() == null || event.destinationAccountId() == null) {
      log.debug("Source/destination accounts not specified, skipping auto-posting");
      return;
    }

    log.info(
        "Processing inventory movement: {} {} - {} units @ {}",
        event.movementType(),
        event.itemCode(),
        event.quantity(),
        event.unitCost());

    try {
      Company company =
          companyRepository
              .findById(event.companyId())
              .orElseThrow(() -> new IllegalStateException("Company not found"));

      CompanyContextHolder.setCompanyCode(company.getCode());

      // Build idempotent reference from source event
      String refNumber = buildIdempotentMovementReference(event);

      // Check if journal already exists (idempotency check)
      if (journalEntryRepository.findByCompanyAndReferenceNumber(company, refNumber).isPresent()) {
        log.info("Journal entry {} already exists, skipping duplicate", refNumber);
        return;
      }

      String memo =
          String.format(
              "%s: %s x %s @ %s",
              event.movementType(), event.itemCode(), event.quantity(), event.unitCost());

      // Debit destination, Credit source
      List<JournalEntryRequest.JournalLineRequest> lines =
          List.of(
              new JournalEntryRequest.JournalLineRequest(
                  event.destinationAccountId(), memo, event.totalCost(), BigDecimal.ZERO),
              new JournalEntryRequest.JournalLineRequest(
                  event.sourceAccountId(), memo, BigDecimal.ZERO, event.totalCost()));

      JournalEntryRequest request =
          new JournalEntryRequest(
              refNumber,
              event.movementDate(),
              event.memo() != null ? event.memo() : memo,
              null,
              null,
              false,
              lines);

      accountingService.createJournalEntry(request);
      log.info("Created movement journal entry: {}", refNumber);

    } catch (Exception e) {
      log.error(
          "Failed to create movement journal entry for {}: {}",
          event.itemCode(),
          e.getMessage(),
          e);
    } finally {
      // Always clear company context to prevent thread pollution
      CompanyContextHolder.clear();
    }
  }

  private Account getRevaluationAccount(
      Company company, InventoryValuationChangedEvent.ValuationChangeReason reason) {
    // Try to find a specific revaluation account, fall back to generic expense
    String accountCode =
        switch (reason) {
          case SCRAP_WRITEOFF -> "INV-WRITEOFF";
          case MARKET_REVALUATION -> "INV-REVAL";
          case PHYSICAL_COUNT_ADJUSTMENT -> "INV-ADJUSTMENT";
          default -> "INV-REVAL";
        };

    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, accountCode)
        .or(() -> accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE"))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No revaluation or expense account found for company " + company.getCode()));
  }

  private String buildRevaluationMemo(InventoryValuationChangedEvent event) {
    return String.format(
        "Inventory revaluation [%s]: %s - %s to %s (Qty: %s, Unit cost: %s → %s)",
        event.reason(),
        event.itemCode(),
        event.oldValue(),
        event.newValue(),
        event.quantity(),
        event.oldUnitCost(),
        event.newUnitCost());
  }

  /**
   * Build idempotent reference for revaluation events.
   * Uses source reference if available, otherwise builds deterministic SHA-256 hash from event data.
   */
  private String buildIdempotentRevalReference(InventoryValuationChangedEvent event) {
    if (event.referenceNumber() != null && !event.referenceNumber().isBlank()) {
      return "REVAL-" + event.referenceNumber();
    }
    // Fallback: SHA-256 hash of all event fields (collision-resistant)
    String eventFingerprint =
        String.format(
            "%d|%s|%s|%s|%s|%s|%s|%s|%s",
            event.itemId(),
            event.itemCode(),
            event.reason(),
            event.oldValue(),
            event.newValue(),
            event.quantity(),
            event.oldUnitCost(),
            event.newUnitCost(),
            event.timestamp() != null ? event.timestamp().toEpochMilli() : "");
    String hash = IdempotencyUtils.sha256Hex(eventFingerprint, 16); // 16 hex chars = 64 bits
    return String.format("REVAL-%s-%s-%s", event.itemCode(), event.reason(), hash);
  }

  /**
   * Build idempotent reference for movement events.
   * Uses source reference if available, otherwise builds deterministic SHA-256 hash from event data.
   */
  private String buildIdempotentMovementReference(InventoryMovementEvent event) {
    String referenceNumber = event.referenceNumber();
    String referencePrefix =
        (referenceNumber == null || referenceNumber.isBlank())
            ? String.format("INV-%s-%s", event.movementType(), event.itemCode())
            : referenceNumber.trim();
    if (event.movementId() != null) {
      return String.format("%s-MOV-%d", referencePrefix, event.movementId());
    }
    String eventFingerprint =
        String.format(
            "%s|%d|%s|%s|%d|%s|%s|%s|%s|%s|%d|%d|%s|%d|%s",
            referencePrefix,
            event.companyId(),
            event.movementType(),
            event.inventoryType(),
            event.itemId() != null ? event.itemId() : 0,
            event.itemCode(),
            event.quantity(),
            event.unitCost(),
            event.totalCost(),
            event.movementDate(),
            event.sourceAccountId() != null ? event.sourceAccountId() : 0,
            event.destinationAccountId() != null ? event.destinationAccountId() : 0,
            event.relatedEntityType() != null ? event.relatedEntityType() : "",
            event.relatedEntityId() != null ? event.relatedEntityId() : 0,
            event.itemName() != null ? event.itemName() : "");
    String hash = IdempotencyUtils.sha256Hex(eventFingerprint, 16); // 16 hex chars = 64 bits
    return String.format("%s-%s", referencePrefix, hash);
  }

  private boolean isCanonicalWorkflowMovement(InventoryMovementEvent event) {
    String relatedEntityType = event.relatedEntityType();
    if (relatedEntityType == null || relatedEntityType.isBlank()) {
      return false;
    }
    return InventoryReference.GOODS_RECEIPT.equalsIgnoreCase(relatedEntityType)
        || InventoryReference.SALES_ORDER.equalsIgnoreCase(relatedEntityType)
        || "PACKAGING_SLIP".equalsIgnoreCase(relatedEntityType);
  }
}
