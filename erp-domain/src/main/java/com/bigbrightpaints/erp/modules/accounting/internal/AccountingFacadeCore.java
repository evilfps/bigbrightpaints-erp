package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService.TaxAccountConfiguration;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;

/**
 * Centralized facade for all accounting journal entry operations.
 * <p>
 * This service provides domain-specific methods for posting journal entries
 * from different business operations (sales, purchases, production, etc.).
 * It eliminates code duplication and ensures consistent accounting practices.
 * <p>
 * Key features:
 * - Account validation and caching
 * - Reference number generation
 * - Idempotency checks
 * - Transaction isolation
 * - Retry on optimistic locking failures
 * - Comprehensive error handling
 *
 * @author ERP Development Team
 * @since 1.0
 */
class AccountingFacadeCore {

  private static final Logger log = LoggerFactory.getLogger(AccountingFacadeCore.class);
  private static final BigDecimal BALANCE_TOLERANCE = BigDecimal.ZERO;
  private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000; // 5 minutes

  public static final String MANUAL_REFERENCE_PREFIX = "MANUAL-";
  private static final Set<String> RESERVED_REFERENCE_PREFIXES =
      Set.of(
          "JRN-",
          "INV-",
          "SALE-",
          "COGS-",
          "RMP-",
          "PRN-",
          "PAYROLL-",
          "RM-",
          "ADJ-",
          "OPEN-STOCK-",
          "CAL-",
          "INVJ-",
          "RCPT-",
          "SUP-",
          "COST-ALLOC-",
          "CRN-",
          "DBN-",
          "DISPATCH-");

  public static boolean isReservedReferenceNamespace(String referenceNumber) {
    if (!StringUtils.hasText(referenceNumber)) {
      return false;
    }
    String normalized = referenceNumber.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith(MANUAL_REFERENCE_PREFIX)) {
      return false;
    }
    if (normalized.contains("-INV-")) {
      return true;
    }
    for (String prefix : RESERVED_REFERENCE_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private final CompanyContextService companyContextService;
  private final AccountRepository accountRepository;
  private final AccountingService accountingService;
  private final JournalEntryRepository journalEntryRepository;
  private final ReferenceNumberService referenceNumberService;
  private final DealerRepository dealerRepository;
  private final SupplierRepository supplierRepository;
  private final CompanyClock companyClock;
  private final CompanyEntityLookup companyEntityLookup;
  private final CompanyAccountingSettingsService companyAccountingSettingsService;
  private final JournalReferenceResolver journalReferenceResolver;
  private final JournalReferenceMappingRepository journalReferenceMappingRepository;

  // Thread-safe account cache with TTL to reduce DB queries
  private final Map<String, CachedAccount> accountCache = new ConcurrentHashMap<>();

  private record CachedAccount(Account account, long cachedAt) {
    boolean isExpired() {
      return System.currentTimeMillis() - cachedAt > CACHE_TTL_MILLIS;
    }
  }

  public AccountingFacadeCore(
      CompanyContextService companyContextService,
      AccountRepository accountRepository,
      AccountingService accountingService,
      JournalEntryRepository journalEntryRepository,
      ReferenceNumberService referenceNumberService,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      CompanyClock companyClock,
      CompanyEntityLookup companyEntityLookup,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      JournalReferenceResolver journalReferenceResolver,
      JournalReferenceMappingRepository journalReferenceMappingRepository) {
    this.companyContextService = companyContextService;
    this.accountRepository = accountRepository;
    this.accountingService = accountingService;
    this.journalEntryRepository = journalEntryRepository;
    this.referenceNumberService = referenceNumberService;
    this.dealerRepository = dealerRepository;
    this.supplierRepository = supplierRepository;
    this.companyClock = companyClock;
    this.companyEntityLookup = companyEntityLookup;
    this.companyAccountingSettingsService = companyAccountingSettingsService;
    this.journalReferenceResolver = journalReferenceResolver;
    this.journalReferenceMappingRepository = journalReferenceMappingRepository;
  }

  /**
   * Post sales journal entry (Dr AR / Cr Revenue + Tax).
   *
   * @param dealerId         the dealer ID
   * @param orderNumber      the sales order number
   * @param entryDate        the journal entry date (null = current date)
   * @param memo             the journal memo
   * @param revenueLines     map of revenue account ID to amount
   * @param taxLines         map of tax account ID to amount
   * @param totalAmount      the total receivable amount
   * @param referenceNumber  optional alias reference (mapped to canonical INV-<orderNumber>)
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 5, backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  public JournalEntryDto postSalesJournal(
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

  /**
   * Post sales journal entry with optional discount lines (Dr AR + Discount / Cr Revenue + Tax).
   *
   * @param dealerId         the dealer ID
   * @param orderNumber      the sales order number
   * @param entryDate        the journal entry date (null = current date)
   * @param memo             the journal memo
   * @param revenueLines     map of revenue account ID to amount
   * @param taxLines         map of tax account ID to amount
   * @param discountLines    map of discount account ID to amount (debit)
   * @param totalAmount      the total receivable amount
   * @param referenceNumber  optional alias reference (mapped to canonical INV-<orderNumber>)
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 5, backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  public JournalEntryDto postSalesJournal(
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
    Dealer dealer = companyEntityLookup.requireDealer(company, dealerId);

    // Validate dealer has receivable account
    if (dealer.getReceivableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Dealer missing receivable account")
          .withDetail("dealerId", dealerId)
          .withDetail("dealerName", dealer.getName());
    }

    String canonicalReference = SalesOrderReference.invoiceReference(orderNumber);
    String aliasReference = StringUtils.hasText(referenceNumber) ? referenceNumber.trim() : null;
    if (StringUtils.hasText(aliasReference)
        && aliasReference.equalsIgnoreCase(canonicalReference)) {
      aliasReference = null;
    }
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, canonicalReference);
    if (existing.isEmpty() && StringUtils.hasText(aliasReference)) {
      existing = journalReferenceResolver.findExistingEntry(company, aliasReference);
    }
    if (existing.isEmpty()) {
      boolean reservationLeader = reserveSalesJournalReference(company, canonicalReference);
      if (!reservationLeader) { existing = resolveReservedSalesJournalEntry(company, canonicalReference); if (existing.isEmpty()) { throw new ApplicationException(
              ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
              "Sales journal reference is reserved but journal entry not found").withDetail("referenceNumber", canonicalReference); } }
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : "Sales order " + orderNumber;

    // Dr: Accounts Receivable
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            dealer.getReceivableAccount().getId(),
            resolvedMemo,
            totalAmount.abs(),
            BigDecimal.ZERO));

    // Dr: Discount accounts (contra revenue)
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

    // Cr: Revenue accounts
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

    appendSalesTaxLines(company, lines, taxLines, gstBreakdown, resolvedMemo, orderNumber);

    // Validate balance
    BigDecimal totalDebits = calculateTotalDebits(lines);
    BigDecimal totalCredits = calculateTotalCredits(lines);
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
            resolvePrimaryCreditAccount(lines, dealer.getReceivableAccount().getId()),
            resolvedMemo,
            "SALES",
            requestReference,
            gstBreakdown,
            toStandardLines(lines),
            postingDate,
            dealer.getId(),
            null,
            Boolean.FALSE);

    if (existing.isPresent()) {
      JournalEntryDto replay = createStandardJournal(request);
      JournalEntry mappedEntry = existing.get();
      if (replay != null && replay.id() != null) {
        mappedEntry = companyEntityLookup.requireJournalEntry(company, replay.id());
      }
      ensureSalesJournalReferenceMapping(company, mappedEntry, canonicalReference, aliasReference);
      return replay;
    }

    log.info(
        "Posting sales journal: reference={}, dealer={}, amount={}",
        requestReference,
        dealer.getName(),
        totalAmount);

    JournalEntryDto created = createStandardJournal(request);
    if (created != null && created.id() != null) {
      JournalEntry entry = companyEntityLookup.requireJournalEntry(company, created.id());
      ensureSalesJournalReferenceMapping(company, entry, canonicalReference, aliasReference);
    }
    return created;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  @Retryable(value = {OptimisticLockingFailureException.class, CannotAcquireLockException.class}, maxAttempts = 5, backoff = @Backoff(delay = 50, maxDelay = 400, multiplier = 2.0))
  public JournalEntryDto postSalesJournal(
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

  /**
   * Post purchase journal entry (Dr Inventory / Cr Payable).
   *
   * @param supplierId       the supplier ID
   * @param invoiceNumber    the purchase invoice number
   * @param invoiceDate      the invoice date
   * @param memo             the journal memo
   * @param inventoryLines   map of inventory account ID to amount
   * @param totalAmount      the total payable amount
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPurchaseJournal(
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
  public JournalEntryDto postPurchaseJournal(
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
      return toSimpleDto(existingByBase.get());
    }

    supplier.requireTransactionalUsage("post purchase journals");

    if (inventoryLines == null || inventoryLines.isEmpty()) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Inventory lines are required for purchase journal");
    }
    inventoryLines
        .keySet()
        .forEach(accountId -> requireAccountById(company, accountId, "Inventory account"));

    // Validate supplier has payable account
    if (supplier.getPayableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Supplier missing payable account")
          .withDetail("supplierId", supplierId)
          .withDetail("supplierName", supplier.getName());
    }

    // Generate reference number
    String reference =
        StringUtils.hasText(referenceNumber)
            ? referenceNumber.trim()
            : referenceNumberService.purchaseReference(company, supplier, invoiceNumber);

    // Check for duplicate
    if (journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).isPresent()) {
      log.info("Purchase journal already exists for reference: {}", reference);
      return journalEntryRepository
          .findByCompanyAndReferenceNumber(company, reference)
          .map(this::toSimpleDto)
          .orElseThrow();
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : "Purchase invoice " + invoiceNumber;

    BigDecimal inventoryTotal = BigDecimal.ZERO;
    if (inventoryLines != null) {
      for (Map.Entry<Long, BigDecimal> entry : inventoryLines.entrySet()) {
        BigDecimal amount = entry.getValue();
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
          lines.add(
              new JournalEntryRequest.JournalLineRequest(
                  entry.getKey(), resolvedMemo, amount.abs(), BigDecimal.ZERO));
          inventoryTotal = inventoryTotal.add(amount.abs());
        }
      }
    }

    BigDecimal taxTotal =
        appendPurchaseTaxLines(company, lines, taxLines, gstBreakdown, resolvedMemo);

    if (inventoryTotal.add(taxTotal).compareTo(totalAmount.abs()) != 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Purchase totals do not balance inventory+tax to payable total")
          .withDetail("inventoryTotal", inventoryTotal)
          .withDetail("taxTotal", taxTotal)
          .withDetail("totalAmount", totalAmount);
    }

    // Cr: Accounts Payable
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
            resolvePrimaryDebitAccount(lines, supplier.getPayableAccount().getId()),
            supplier.getPayableAccount().getId(),
            resolvedMemo,
            "PURCHASING",
            reference,
            resolvePurchaseBreakdown(inventoryTotal, taxTotal, gstBreakdown),
            toStandardLines(lines),
            postingDate,
            null,
            supplier.getId(),
            Boolean.FALSE);

    log.info(
        "Posting purchase journal: reference={}, supplier={}, amount={}",
        reference,
        supplier.getName(),
        totalAmount);

    JournalEntryDto entry = createStandardJournal(request);
    JournalEntry saved = companyEntityLookup.requireJournalEntry(company, entry.id());
    ensurePurchaseReferenceMapping(company, baseReference, saved);
    return entry;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPurchaseJournal(
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

  /**
   * Post purchase return journal entry (Dr AP / Cr Inventory).
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPurchaseReturn(
      Long supplierId,
      String referenceNumber,
      LocalDate returnDate,
      String memo,
      Map<Long, BigDecimal> inventoryCredits,
      BigDecimal totalAmount) {
    return postPurchaseReturn(
        supplierId, referenceNumber, returnDate, memo, inventoryCredits, null, null, totalAmount);
  }

  /**
   * Post purchase return journal entry (Dr AP / Cr Inventory [+ Cr Input Tax]).
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPurchaseReturn(
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

    String reference =
        StringUtils.hasText(referenceNumber)
            ? referenceNumber.trim()
            : referenceNumberService.purchaseReturnReference(company, supplier);

    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Purchase return journal already exists for reference: {}", reference);
      return existing.map(this::toSimpleDto).orElseThrow();
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
            appendPurchaseReturnTaxLines(company, lines, taxCredits, gstBreakdown, resolvedMemo));

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
            resolvePrimaryCreditAccount(lines, supplier.getPayableAccount().getId()),
            resolvedMemo,
            "PURCHASING_RETURN",
            reference,
            gstBreakdown,
            toStandardLines(lines),
            postingDate,
            null,
            supplier.getId(),
            Boolean.FALSE);

    log.info(
        "Posting purchase return journal: reference={}, supplier={}, amount={}",
        reference,
        supplier.getName(),
        totalAmount);

    return createStandardJournal(request);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPurchaseReturn(
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

  /**
   * Post material consumption journal entry (Dr WIP / Cr Raw Material Inventory).
   *
   * @param productionCode   the production log code
   * @param entryDate        the journal entry date
   * @param wipAccountId     the WIP account ID
   * @param materialLines    map of material inventory account ID to amount
   * @param totalCost        the total material cost
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  @Deprecated(since = "2026-02", forRemoval = false)
  public JournalEntryDto postMaterialConsumption(
      String productionCode,
      LocalDate entryDate,
      Long wipAccountId,
      Map<Long, BigDecimal> materialLines,
      BigDecimal totalCost) {
    Objects.requireNonNull(productionCode, "Production code is required");
    Objects.requireNonNull(wipAccountId, "WIP account ID is required");
    Objects.requireNonNull(totalCost, "Total cost is required");

    Company company = companyContextService.requireCurrentCompany();

    if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Skipping material consumption journal for {} - zero cost", productionCode);
      return null;
    }

    // Validate WIP account exists
    requireAccountById(company, wipAccountId, "WIP account");

    // Generate reference number
    String reference = productionCode + "-RM";

    // Check for duplicate
    if (journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).isPresent()) {
      log.info("Material consumption journal already exists for reference: {}", reference);
      return journalEntryRepository
          .findByCompanyAndReferenceNumber(company, reference)
          .map(this::toSimpleDto)
          .orElseThrow();
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = "Raw material consumption for " + productionCode;

    // Dr: WIP
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            wipAccountId, "WIP charge " + productionCode, totalCost, BigDecimal.ZERO));

    // Cr: Raw Material Inventory accounts
    if (materialLines != null) {
      materialLines.forEach(
          (accountId, amount) -> {
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
              lines.add(
                  new JournalEntryRequest.JournalLineRequest(
                      accountId,
                      "Raw material issue " + productionCode,
                      BigDecimal.ZERO,
                      amount.abs()));
            }
          });
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);

    JournalCreationRequest request =
        new JournalCreationRequest(
            totalCost,
            resolvePrimaryDebitAccount(lines, wipAccountId),
            resolvePrimaryCreditAccount(lines, wipAccountId),
            memo,
            "FACTORY_PRODUCTION",
            reference,
            null,
            toStandardLines(lines),
            postingDate,
            null,
            null,
            Boolean.FALSE);

    log.info("Posting material consumption journal: reference={}, cost={}", reference, totalCost);

    return createStandardJournal(request);
  }

  /**
   * Post labor/overhead applied journal entry (Dr WIP / Cr Labor Applied, Cr Overhead Applied).
   *
   * @param productionCode      the production log code
   * @param entryDate           the journal entry date
   * @param wipAccountId        the WIP account ID
   * @param laborAppliedAccountId    the labor applied account ID
   * @param overheadAppliedAccountId the overhead applied account ID
   * @param laborCost           the labor cost to apply
   * @param overheadCost        the overhead cost to apply
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  @Deprecated(since = "2026-02", forRemoval = false)
  public JournalEntryDto postLaborOverheadApplied(
      String productionCode,
      LocalDate entryDate,
      Long wipAccountId,
      Long laborAppliedAccountId,
      Long overheadAppliedAccountId,
      BigDecimal laborCost,
      BigDecimal overheadCost) {
    Objects.requireNonNull(productionCode, "Production code is required");
    Objects.requireNonNull(wipAccountId, "WIP account ID is required");

    BigDecimal laborAmount = laborCost != null ? laborCost : BigDecimal.ZERO;
    BigDecimal overheadAmount = overheadCost != null ? overheadCost : BigDecimal.ZERO;
    BigDecimal totalAmount = laborAmount.add(overheadAmount);

    if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Skipping labor/overhead applied journal for {} - zero amount", productionCode);
      return null;
    }

    if (laborAmount.compareTo(BigDecimal.ZERO) > 0 && laborAppliedAccountId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Labor applied account ID is required");
    }
    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0 && overheadAppliedAccountId == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Overhead applied account ID is required");
    }

    Company company = companyContextService.requireCurrentCompany();

    requireAccountById(company, wipAccountId, "WIP account");
    if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
      requireAccountById(company, laborAppliedAccountId, "Labor applied account");
    }
    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
      requireAccountById(company, overheadAppliedAccountId, "Overhead applied account");
    }

    String reference = productionCode + "-LABOH";
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Labor/overhead applied journal already exists for reference: {}", reference);
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = "Labor/overhead applied for " + productionCode;

    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            wipAccountId, "WIP labor/overhead " + productionCode, totalAmount, BigDecimal.ZERO));

    if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              laborAppliedAccountId,
              "Labor applied " + productionCode,
              BigDecimal.ZERO,
              laborAmount.abs()));
    }

    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              overheadAppliedAccountId,
              "Overhead applied " + productionCode,
              BigDecimal.ZERO,
              overheadAmount.abs()));
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);

    JournalCreationRequest request =
        new JournalCreationRequest(
            totalAmount,
            resolvePrimaryDebitAccount(lines, wipAccountId),
            resolvePrimaryCreditAccount(lines, wipAccountId),
            memo,
            "FACTORY_PRODUCTION",
            reference,
            null,
            toStandardLines(lines),
            postingDate,
            null,
            null,
            Boolean.FALSE);

    log.info(
        "Posting labor/overhead applied journal: reference={}, amount={}", reference, totalAmount);

    return createStandardJournal(request);
  }

  /**
   * Post packing/bulk-pack journal entries through the canonical facade boundary.
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postPackingJournal(
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
      log.info("Packing journal already exists for reference: {}", resolvedReference);
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "Packing journal " + resolvedReference;
    JournalCreationRequest request =
        new JournalCreationRequest(
            totalLineAmount(lines),
            resolvePrimaryDebitAccount(lines, null),
            resolvePrimaryCreditAccount(lines, null),
            resolvedMemo,
            "FACTORY_PACKING",
            resolvedReference,
            null,
            toStandardLines(lines),
            postingDate,
            null,
            null,
            Boolean.FALSE);

    log.info("Posting packing journal: reference={}, lines={}", resolvedReference, lines.size());
    return createStandardJournal(request);
  }

  /**
   * Post cost allocation journal entry (Dr Finished Goods / Cr Labor/Overhead Expense).
   *
   * @param batchCode          the batch code for reference
   * @param finishedGoodsAcctId the finished goods inventory account ID
   * @param laborExpenseAcctId  the labor expense account ID
   * @param overheadExpenseAcctId the overhead expense account ID
   * @param laborCost          the labor cost to allocate
   * @param overheadCost       the overhead cost to allocate
   * @param notes              optional notes
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postCostAllocation(
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
      log.warn("Skipping cost allocation journal for {} - zero amount", batchCode);
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();

    // Validate accounts exist
    requireAccountById(company, finishedGoodsAcctId, "Finished goods account");
    requireAccountById(company, laborExpenseAcctId, "Labor expense account");
    requireAccountById(company, overheadExpenseAcctId, "Overhead expense account");

    // Generate deterministic reference for idempotency
    String reference = "CAL-" + sanitize(batchCode);

    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Cost allocation journal already exists for reference: {}", reference);
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = notes != null ? notes : "Cost allocation for " + batchCode;

    // Dr: Finished Goods Inventory
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            finishedGoodsAcctId,
            "Allocated costs to finished goods",
            totalAmount,
            BigDecimal.ZERO));

    // Cr: Labor Expense
    if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              laborExpenseAcctId,
              "Labor cost allocated to production",
              BigDecimal.ZERO,
              laborAmount));
    }

    // Cr: Overhead Expense
    if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              overheadExpenseAcctId,
              "Overhead cost allocated to production",
              BigDecimal.ZERO,
              overheadAmount));
    }

    JournalEntryRequest request =
        new JournalEntryRequest(
            reference, companyClock.today(company), memo, null, null, Boolean.FALSE, lines);

    log.info(
        "Posting cost allocation journal: reference={}, batch={}, amount={}",
        reference,
        batchCode,
        totalAmount);

    return accountingService.createJournalEntry(request);
  }

  /**
   * Post COGS journal entry (Dr COGS / Cr Inventory).
   *
   * @param referenceId      the reference ID (order ID, dispatch ID, etc.)
   * @param dealerId         optional dealer ID for linkage
   * @param cogsAccountId    the COGS account ID
   * @param inventoryAcctId  the inventory account ID
   * @param cost             the cost amount
   * @param memo             optional memo
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postCOGS(
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
      log.warn("Skipping COGS journal for {} - zero cost", referenceId);
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();

    // Validate accounts exist
    requireAccountById(company, cogsAccountId, "COGS account");
    requireAccountById(company, inventoryAcctId, "Inventory account");

    // Generate reference number
    String reference = resolveCogsReference(referenceId);

    // Check for duplicate (allow variants with same logical reference)
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, reference);
    if (existing.isPresent()) {
      log.info("COGS journal already exists for reference: {}", reference);
      return toSimpleDto(existing.get());
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : "COGS for " + referenceId;

    // Dr: COGS
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            cogsAccountId, resolvedMemo, cost, BigDecimal.ZERO));

    // Cr: Inventory
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            inventoryAcctId, resolvedMemo, BigDecimal.ZERO, cost));

    JournalEntryRequest request =
        new JournalEntryRequest(
            reference,
            companyClock.today(company),
            resolvedMemo,
            dealerId,
            null,
            Boolean.FALSE,
            lines);

    log.info("Posting COGS journal: reference={}, cost={}", reference, cost);
    return accountingService.createJournalEntry(request);
  }

  /**
   * Post variance allocation journal entry (Dr/Cr Finished Goods / Cr/Dr Labor + Overhead Expense).
   *
   * @param batchCode              the batch code for reference
   * @param periodKey              period key (e.g., 202501) for idempotency
   * @param entryDate              entry date for the journal
   * @param finishedGoodsAcctId    finished goods inventory account ID
   * @param laborExpenseAcctId     labor expense account ID
   * @param overheadExpenseAcctId  overhead expense account ID
   * @param laborVariance          labor variance amount (actual - applied)
   * @param overheadVariance       overhead variance amount (actual - applied)
   * @param notes                  optional notes
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postCostVarianceAllocation(
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

    boolean hasLabor = laborAmount.compareTo(BigDecimal.ZERO) != 0;
    boolean hasOverhead = overheadAmount.compareTo(BigDecimal.ZERO) != 0;
    if (!hasLabor && !hasOverhead) {
      log.info("Skipping variance allocation journal for {} - zero variance", batchCode);
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();

    requireAccountById(company, finishedGoodsAcctId, "Finished goods account");
    requireAccountById(company, laborExpenseAcctId, "Labor expense account");
    requireAccountById(company, overheadExpenseAcctId, "Overhead expense account");

    String reference = "CVAR-" + sanitize(batchCode) + "-" + periodKey;
    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existing.isPresent()) {
      log.info("Cost variance journal already exists for reference: {}", reference);
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String memo = notes != null ? notes : "Cost variance allocation for " + batchCode;

    if (totalAmount.compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal absTotal = totalAmount.abs();
      if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                finishedGoodsAcctId, "Underapplied labor/overhead", absTotal, BigDecimal.ZERO));
      } else {
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                finishedGoodsAcctId, "Overapplied labor/overhead", BigDecimal.ZERO, absTotal));
      }
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

    JournalEntryRequest request =
        new JournalEntryRequest(reference, postingDate, memo, null, null, Boolean.FALSE, lines);

    log.info(
        "Posting cost variance journal: reference={}, batch={}, amount={}",
        reference,
        batchCode,
        totalAmount);

    return accountingService.createJournalEntry(request);
  }

  @Transactional(readOnly = true)
  public Optional<String> findExistingCostVarianceReference(String batchCode, String periodKey) {
    Objects.requireNonNull(batchCode, "Batch code is required");
    Objects.requireNonNull(periodKey, "Period key is required");
    Company company = companyContextService.requireCurrentCompany();
    String reference = "CVAR-" + sanitize(batchCode) + "-" + periodKey;
    return journalEntryRepository
        .findByCompanyAndReferenceNumber(company, reference)
        .map(JournalEntry::getReferenceNumber);
  }

  public JournalEntryDto postCOGS(
      String referenceId, Long cogsAccountId, Long inventoryAcctId, BigDecimal cost, String memo) {
    return postCOGS(referenceId, null, cogsAccountId, inventoryAcctId, cost, memo);
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postCogsJournal(
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
      log.info("COGS journal already exists for reference: {}", reference);
      return toSimpleDto(existing.get());
    }

    LocalDate effectiveDate = entryDate != null ? entryDate : companyClock.today(company);
    String resolvedMemo = StringUtils.hasText(memo) ? memo : "COGS for " + referenceId;
    JournalCreationRequest request =
        new JournalCreationRequest(
            totalLineAmount(lines),
            resolvePrimaryDebitAccount(lines, null),
            resolvePrimaryCreditAccount(lines, null),
            resolvedMemo,
            "SALES_DISPATCH",
            reference,
            null,
            toStandardLines(lines),
            effectiveDate,
            dealerId,
            null,
            Boolean.FALSE);
    log.info("Posting consolidated COGS journal: reference={}, lines={}", reference, lines.size());
    return createStandardJournal(request);
  }

  public boolean hasCogsJournalFor(String referenceId) {
    Company company = companyContextService.requireCurrentCompany();
    String reference = resolveCogsReference(referenceId);
    return journalReferenceResolver.exists(company, reference);
  }

  /**
   * Post sales return journal entry (Dr Revenue+Tax / Cr AR).
   *
   * @param dealerId         the dealer ID
   * @param invoiceNumber    the invoice number
   * @param returnLines      map of account ID to return amount
   * @param totalAmount      the total return amount
   * @param reason           the return reason
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postSalesReturn(
      Long dealerId,
      String invoiceNumber,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    Objects.requireNonNull(dealerId, "Dealer ID is required");
    Objects.requireNonNull(invoiceNumber, "Invoice number is required");
    Objects.requireNonNull(totalAmount, "Total amount is required");

    Company company = companyContextService.requireCurrentCompany();
    Dealer dealer = companyEntityLookup.requireDealer(company, dealerId);

    if (dealer.getReceivableAccount() == null) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION, "Dealer missing receivable account")
          .withDetail("dealerId", dealerId);
    }

    String baseReference = "CRN-" + sanitize(invoiceNumber);
    String hashReference =
        buildSalesReturnHashReference(
            invoiceNumber, dealer.getId(), returnLines, totalAmount, reason);
    Optional<JournalEntry> existing =
        journalReferenceResolver.findExistingEntry(company, hashReference);
    if (existing.isPresent()) {
      log.info(
          "Sales return journal already exists for reference: {}",
          existing.get().getReferenceNumber());
      return toSimpleDto(existing.get());
    }

    String reference = resolveSalesReturnReference(company, baseReference, hashReference);
    Optional<JournalEntry> existingByRef =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
    if (existingByRef.isPresent()) {
      log.info("Sales return journal already exists for reference: {}", reference);
      return toSimpleDto(existingByRef.get());
    }

    // Build journal lines
    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String reasonSuffix = StringUtils.hasText(reason) ? reason.trim() : "Return";
    String memo = reasonSuffix + " - " + invoiceNumber;

    // Dr: Revenue/Tax accounts (reverse the original entries)
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

    // Cr: Accounts Receivable
    lines.add(
        new JournalEntryRequest.JournalLineRequest(
            dealer.getReceivableAccount().getId(), memo, BigDecimal.ZERO, totalAmount.abs()));

    JournalEntryRequest request =
        new JournalEntryRequest(
            reference,
            companyClock.today(company),
            memo,
            dealer.getId(),
            null,
            Boolean.FALSE,
            lines);

    log.info(
        "Posting sales return journal: reference={}, dealer={}, amount={}",
        reference,
        dealer.getName(),
        totalAmount);

    JournalEntryDto created = accountingService.createJournalEntry(request);
    JournalEntry persisted =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElse(null);
    if (persisted != null) {
      ensureSalesReturnReferenceMapping(company, hashReference, reference, persisted.getId());
    }
    return created;
  }

  /**
   * Post inventory adjustment journal entry.
   *
   * @param adjustmentType   the adjustment type (SHORTAGE, OVERAGE, DAMAGE, etc.)
   * @param referenceId      the reference ID
   * @param inventoryAcctId  the inventory account ID
   * @param varianceAcctId   the variance account ID
   * @param amount           the adjustment amount (positive for increase, negative for decrease)
   * @param memo             the adjustment memo
   * @return the created journal entry DTO
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postInventoryAdjustment(
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

    Map<Long, BigDecimal> inventoryLines = Map.of(inventoryAcctId, amount.abs());
    boolean increaseInventory = amount.compareTo(BigDecimal.ZERO) > 0;
    return postInventoryAdjustment(
        adjustmentType,
        referenceId,
        varianceAcctId,
        inventoryLines,
        increaseInventory,
        false,
        memo,
        null);
  }

  /**
   * Post inventory adjustment journal entry with multiple inventory lines.
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postInventoryAdjustment(
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

  /**
   * Post inventory adjustment journal entry with multiple inventory lines and explicit business date.
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public JournalEntryDto postInventoryAdjustment(
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

    requireAccountById(company, varianceAcctId, "Variance account");
    inventoryLines
        .keySet()
        .forEach(accountId -> requireAccountById(company, accountId, "Inventory account"));

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
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
    String resolvedMemo = memo != null ? memo : adjustmentType + " adjustment";

    if (increaseInventory) {
      inventoryLines.forEach(
          (accountId, amount) -> {
            BigDecimal absAmount = absoluteAmount(amount);
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
            BigDecimal absAmount = absoluteAmount(amount);
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
            resolvePrimaryDebitAccount(lines, varianceAcctId),
            resolvePrimaryCreditAccount(lines, varianceAcctId),
            resolvedMemo,
            "INVENTORY_ADJUSTMENT",
            reference,
            null,
            toStandardLines(lines),
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

    return createStandardJournal(request);
  }

  /**
   * Post a simple two-line journal entry (Dr/Credit with equal amount).
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  @Retryable(
      value = OptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  @Deprecated(since = "2026-02", forRemoval = false)
  public JournalEntryDto postSimpleJournal(
      String reference,
      LocalDate entryDate,
      String memo,
      Long debitAccountId,
      Long creditAccountId,
      BigDecimal amount,
      boolean adminOverride) {
    Objects.requireNonNull(reference, "Reference is required");
    Objects.requireNonNull(debitAccountId, "Debit account is required");
    Objects.requireNonNull(creditAccountId, "Credit account is required");
    Objects.requireNonNull(amount, "Amount is required");

    if (amount.compareTo(BigDecimal.ZERO) <= 0) {
      log.warn("Skipping manual journal {} - zero amount", reference);
      return null;
    }

    Company company = companyContextService.requireCurrentCompany();
    requireAccountById(company, debitAccountId, "Debit account");
    requireAccountById(company, creditAccountId, "Credit account");

    String resolvedReference = reference.trim();
    if (!StringUtils.hasText(resolvedReference)) {
      resolvedReference = referenceNumberService.nextJournalReference(company);
    }

    Optional<JournalEntry> existing =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, resolvedReference);
    if (existing.isPresent()) {
      log.info("Manual journal already exists for reference: {}", resolvedReference);
      return existing.map(this::toSimpleDto).orElseThrow();
    }

    LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
    String resolvedMemo = memo != null ? memo : "Manual journal for " + resolvedReference;
    BigDecimal postingAmount = amount.abs();

    List<JournalEntryRequest.JournalLineRequest> lines =
        List.of(
            new JournalEntryRequest.JournalLineRequest(
                debitAccountId, resolvedMemo, postingAmount, BigDecimal.ZERO),
            new JournalEntryRequest.JournalLineRequest(
                creditAccountId, resolvedMemo, BigDecimal.ZERO, postingAmount));

    JournalCreationRequest request =
        new JournalCreationRequest(
            postingAmount,
            debitAccountId,
            creditAccountId,
            resolvedMemo,
            "ACCOUNTING_MANUAL",
            resolvedReference,
            null,
            toStandardLines(lines),
            postingDate,
            null,
            null,
            adminOverride);

    log.info("Posting manual journal: reference={}, amount={}", resolvedReference, postingAmount);
    return createStandardJournal(request);
  }

  public JournalEntryDto createStandardJournal(JournalCreationRequest request) {
    return accountingService.createStandardJournal(request);
  }

  /**
   * Post payroll run journal via AccountingService wrapper.
   */
  public JournalEntryDto postPayrollRun(
      String runNumber,
      Long runId,
      LocalDate postingDate,
      String memo,
      List<JournalEntryRequest.JournalLineRequest> lines) {
    return accountingService.postPayrollRun(runNumber, runId, postingDate, memo, lines);
  }

  /**
   * Record payroll payment via AccountingService wrapper.
   */
  public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
    return accountingService.recordPayrollPayment(request);
  }

  /**
   * Reverse period-close journal via canonical accounting boundary.
   */
  public JournalEntryDto reverseClosingEntryForPeriodReopen(
      JournalEntry entry, AccountingPeriod period, String reason) {
    return accountingService.reverseClosingEntryForPeriodReopen(entry, period, reason);
  }

  /**
   * Clear account cache for a specific company.
   * Call this when account structure changes.
   */
  public void clearAccountCache() {
    clearAccountCache(null);
  }

  public void clearAccountCache(Long companyId) {
    if (companyId == null) {
      accountCache.clear();
      log.info("Account cache cleared");
      return;
    }
    String prefix = companyId + ":";
    accountCache.keySet().removeIf(key -> key.startsWith(prefix));
    log.info("Account cache cleared for company {}", companyId);
  }

  @EventListener
  public void handleAccountCacheInvalidated(AccountCacheInvalidatedEvent event) {
    clearAccountCache(event.companyId());
  }

  // ===== Helper Methods =====

  private List<JournalCreationRequest.LineRequest> toStandardLines(
      List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null) {
      return List.of();
    }
    return lines.stream()
        .map(
            line ->
                new JournalCreationRequest.LineRequest(
                    line.accountId(), line.debit(), line.credit(), line.description()))
        .toList();
  }

  private BigDecimal totalLineAmount(List<JournalEntryRequest.JournalLineRequest> lines) {
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal debitTotal =
        lines.stream()
            .map(line -> line.debit() == null ? BigDecimal.ZERO : line.debit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal creditTotal =
        lines.stream()
            .map(line -> line.credit() == null ? BigDecimal.ZERO : line.credit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return (debitTotal.compareTo(BigDecimal.ZERO) > 0 ? debitTotal : creditTotal).abs();
  }

  private Long resolvePrimaryDebitAccount(
      List<JournalEntryRequest.JournalLineRequest> lines, Long fallback) {
    if (lines == null) {
      return fallback;
    }
    return lines.stream()
        .filter(line -> line.debit() != null && line.debit().compareTo(BigDecimal.ZERO) > 0)
        .map(JournalEntryRequest.JournalLineRequest::accountId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(fallback);
  }

  private Long resolvePrimaryCreditAccount(
      List<JournalEntryRequest.JournalLineRequest> lines, Long fallback) {
    if (lines == null) {
      return fallback;
    }
    return lines.stream()
        .filter(line -> line.credit() != null && line.credit().compareTo(BigDecimal.ZERO) > 0)
        .map(JournalEntryRequest.JournalLineRequest::accountId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(fallback);
  }

  private void appendSalesTaxLines(
      Company company,
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo,
      String orderNumber) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(company, taxLines, false);
      appendComponentCreditLines(
          lines, taxAccountId, resolvedMemo, orderNumber, gstBreakdown, "output");
      return;
    }
    if (taxLines == null) {
      return;
    }
    taxLines.forEach(
        (accountId, amount) -> {
          if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(
                new JournalEntryRequest.JournalLineRequest(
                    accountId, resolvedMemo, BigDecimal.ZERO, amount.abs()));
          }
        });
  }

  private BigDecimal appendPurchaseTaxLines(
      Company company,
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxLines,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(company, taxLines, true);
      return appendComponentDebitLines(lines, taxAccountId, resolvedMemo, gstBreakdown, "input");
    }
    BigDecimal taxTotal = BigDecimal.ZERO;
    if (taxLines == null || taxLines.isEmpty()) {
      return taxTotal;
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    for (Map.Entry<Long, BigDecimal> entry : taxLines.entrySet()) {
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                entry.getKey() != null ? entry.getKey() : taxConfig.inputTaxAccountId(),
                "Input tax for " + resolvedMemo,
                amount.abs(),
                BigDecimal.ZERO));
        taxTotal = taxTotal.add(amount.abs());
      }
    }
    return taxTotal;
  }

  private BigDecimal appendPurchaseReturnTaxLines(
      Company company,
      List<JournalEntryRequest.JournalLineRequest> lines,
      Map<Long, BigDecimal> taxCredits,
      JournalCreationRequest.GstBreakdown gstBreakdown,
      String resolvedMemo) {
    if (breakdownHasTax(gstBreakdown)) {
      Long taxAccountId = resolveTaxAccountId(company, taxCredits, true);
      return appendComponentCreditLines(
          lines, taxAccountId, resolvedMemo, null, gstBreakdown, "reverse input");
    }
    BigDecimal taxTotal = BigDecimal.ZERO;
    if (taxCredits == null || taxCredits.isEmpty()) {
      return taxTotal;
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    for (Map.Entry<Long, BigDecimal> entry : taxCredits.entrySet()) {
      BigDecimal amount = entry.getValue();
      if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
        Long accountId = entry.getKey() != null ? entry.getKey() : taxConfig.inputTaxAccountId();
        taxTotal = taxTotal.add(amount.abs());
        lines.add(
            new JournalEntryRequest.JournalLineRequest(
                accountId, "Reverse input tax for " + resolvedMemo, BigDecimal.ZERO, amount.abs()));
      }
    }
    return taxTotal;
  }

  private BigDecimal appendComponentDebitLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Long taxAccountId,
      String resolvedMemo,
      JournalCreationRequest.GstBreakdown breakdown,
      String labelPrefix) {
    BigDecimal total = BigDecimal.ZERO;
    if (breakdown.cgst() != null && breakdown.cgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.cgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "CGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    if (breakdown.sgst() != null && breakdown.sgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.sgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "SGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    if (breakdown.igst() != null && breakdown.igst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.igst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "IGST " + labelPrefix + " tax for " + resolvedMemo,
              amount,
              BigDecimal.ZERO));
      total = total.add(amount);
    }
    return total;
  }

  private BigDecimal appendComponentCreditLines(
      List<JournalEntryRequest.JournalLineRequest> lines,
      Long taxAccountId,
      String resolvedMemo,
      String orderNumber,
      JournalCreationRequest.GstBreakdown breakdown,
      String labelPrefix) {
    BigDecimal total = BigDecimal.ZERO;
    String context = StringUtils.hasText(orderNumber) ? orderNumber : resolvedMemo;
    if (breakdown.cgst() != null && breakdown.cgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.cgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "CGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    if (breakdown.sgst() != null && breakdown.sgst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.sgst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "SGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    if (breakdown.igst() != null && breakdown.igst().compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal amount = breakdown.igst().abs();
      lines.add(
          new JournalEntryRequest.JournalLineRequest(
              taxAccountId,
              "IGST " + labelPrefix + " tax for " + context,
              BigDecimal.ZERO,
              amount));
      total = total.add(amount);
    }
    return total;
  }

  private Long resolveTaxAccountId(
      Company company, Map<Long, BigDecimal> taxLines, boolean inputTax) {
    if (taxLines != null) {
      Optional<Long> fromMap = taxLines.keySet().stream().filter(Objects::nonNull).findFirst();
      if (fromMap.isPresent()) {
        return fromMap.get();
      }
    }
    TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
    Long accountId = inputTax ? taxConfig.inputTaxAccountId() : taxConfig.outputTaxAccountId();
    if (accountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          (inputTax ? "Input" : "Output") + " tax account is not configured");
    }
    return accountId;
  }

  private JournalCreationRequest.GstBreakdown resolvePurchaseBreakdown(
      BigDecimal taxableAmount, BigDecimal taxTotal, JournalCreationRequest.GstBreakdown provided) {
    if (provided != null) {
      return provided;
    }
    if (taxTotal == null || taxTotal.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return new JournalCreationRequest.GstBreakdown(
        taxableAmount, BigDecimal.ZERO, BigDecimal.ZERO, taxTotal);
  }

  private boolean breakdownHasTax(JournalCreationRequest.GstBreakdown breakdown) {
    if (breakdown == null) {
      return false;
    }
    BigDecimal cgst = breakdown.cgst() == null ? BigDecimal.ZERO : breakdown.cgst();
    BigDecimal sgst = breakdown.sgst() == null ? BigDecimal.ZERO : breakdown.sgst();
    BigDecimal igst = breakdown.igst() == null ? BigDecimal.ZERO : breakdown.igst();
    return cgst.compareTo(BigDecimal.ZERO) > 0
        || sgst.compareTo(BigDecimal.ZERO) > 0
        || igst.compareTo(BigDecimal.ZERO) > 0;
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

  private void upsertJournalReferenceMapping(
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
      // Ignore concurrent insert attempts for the same legacy reference
    }
  }

  private boolean reserveSalesJournalReference(Company company, String canonicalReference) {
    if (company == null || company.getId() == null || !StringUtils.hasText(canonicalReference)) {
      return true;
    }
    String canonical = canonicalReference.trim();
    Optional<JournalReferenceMapping> existing =
        journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, canonical);
    if (existing.isPresent()) { return false; }
    int reserved =
        journalReferenceMappingRepository.reserveReferenceMapping(
            company.getId(), canonical, canonical, "SALES_JOURNAL", CompanyTime.now(company));
    if (reserved == 1) { return true; }
    if (journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(company, canonical).isPresent()) { return false; }
    throw new ApplicationException(
            ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
            "Sales journal reference already reserved but mapping not found")
        .withDetail("referenceNumber", canonical);
  }

  private Optional<JournalEntry> resolveReservedSalesJournalEntry(
      Company company, String canonicalReference) {
    if (company == null || !StringUtils.hasText(canonicalReference)) { return Optional.empty(); }
    String canonical = canonicalReference.trim();
    Optional<JournalEntry> existing = journalReferenceResolver.findExistingEntry(company, canonical);
    if (existing.isPresent()) { return existing; }
    Optional<JournalReferenceMapping> mapping =
        journalReferenceMappingRepository.findByCompanyAndLegacyReferenceIgnoreCase(
            company, canonical);
    if (mapping.isEmpty()) { return Optional.empty(); }
    if (mapping.get().getEntityId() != null) {
      Optional<JournalEntry> byId =
          journalEntryRepository.findByCompanyAndId(company, mapping.get().getEntityId());
      if (byId.isPresent()) { return byId; }
    }
    return StringUtils.hasText(mapping.get().getCanonicalReference())
        ? journalReferenceResolver.findExistingEntry(company, mapping.get().getCanonicalReference())
        : Optional.empty();
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
            () -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND, "Supplier not found")
                .withDetail("supplierId", supplierId));
  }

  private Account requireAccountById(Company company, Long accountId, String accountType) {
    String cacheKey = company.getId() + ":" + accountId;
    CachedAccount cached = accountCache.get(cacheKey);
    if (cached != null && !cached.isExpired()) {
      return cached.account();
    }
    Account account = fetchAccount(company, accountId, accountType);
    accountCache.put(cacheKey, new CachedAccount(account, System.currentTimeMillis()));
    return account;
  }

  private Account fetchAccount(Company company, Long accountId, String accountType) {
    try {
      return companyEntityLookup.requireAccount(company, accountId);
    } catch (IllegalArgumentException ex) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_ENTITY_NOT_FOUND, accountType + " not found")
          .withDetail("accountId", accountId);
    }
  }

  private BigDecimal calculateTotalCredits(List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(JournalEntryRequest.JournalLineRequest::credit)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal calculateTotalDebits(List<JournalEntryRequest.JournalLineRequest> lines) {
    return lines.stream()
        .map(JournalEntryRequest.JournalLineRequest::debit)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal absoluteAmount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.abs();
  }

  private String sanitize(String value) {
    if (!StringUtils.hasText(value)) {
      return "GEN";
    }
    // Preserve hyphens for readability (BBP-2025-00001 stays readable)
    return value.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
  }

  private String resolveCogsReference(String referenceId) {
    String normalized = sanitize(referenceId);
    if (normalized.startsWith("COGS-")) {
      normalized = normalized.substring("COGS-".length());
    }
    return SalesOrderReference.cogsReference(normalized);
  }

  private String buildSalesReturnHashReference(
      String invoiceNumber,
      Long dealerId,
      Map<Long, BigDecimal> returnLines,
      BigDecimal totalAmount,
      String reason) {
    String base = "CRN-" + sanitize(invoiceNumber);
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
      if (suffix.isEmpty()) {
        continue;
      }
      try {
        int index = Integer.parseInt(suffix);
        if (index > maxIndex) {
          maxIndex = index;
        }
      } catch (NumberFormatException ignored) {
        // skip non-numeric suffixes
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

  private JournalEntryDto toSimpleDto(JournalEntry entry) {
    // Simplified DTO conversion for idempotency checks
    return new JournalEntryDto(
        entry.getId(),
        entry.getPublicId(),
        entry.getReferenceNumber(),
        entry.getEntryDate(),
        entry.getMemo(),
        entry.getStatus(),
        entry.getDealer() != null ? entry.getDealer().getId() : null,
        entry.getDealer() != null ? entry.getDealer().getName() : null,
        entry.getSupplier() != null ? entry.getSupplier().getId() : null,
        entry.getSupplier() != null ? entry.getSupplier().getName() : null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getPostedAt(),
        entry.getCreatedBy(),
        entry.getPostedBy(),
        entry.getLastModifiedBy());
  }
}
