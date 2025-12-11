package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyAccountingSettingsService.TaxAccountConfiguration;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
@Service
public class AccountingFacade {

    private static final Logger log = LoggerFactory.getLogger(AccountingFacade.class);
    private static final BigDecimal BALANCE_TOLERANCE = BigDecimal.ZERO;
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000; // 5 minutes

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

    // Thread-safe account cache with TTL to reduce DB queries
    private final Map<String, CachedAccount> accountCache = new ConcurrentHashMap<>();

    private record CachedAccount(Account account, long cachedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MILLIS;
        }
    }

    public AccountingFacade(CompanyContextService companyContextService,
                            AccountRepository accountRepository,
                            AccountingService accountingService,
                            JournalEntryRepository journalEntryRepository,
                            ReferenceNumberService referenceNumberService,
                            DealerRepository dealerRepository,
                            SupplierRepository supplierRepository,
                            CompanyClock companyClock,
                            CompanyEntityLookup companyEntityLookup,
                            CompanyAccountingSettingsService companyAccountingSettingsService) {
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
     * @param referenceNumber  optional custom reference (null = auto-generated)
     * @return the created journal entry DTO
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postSalesJournal(Long dealerId,
                                            String orderNumber,
                                            LocalDate entryDate,
                                            String memo,
                                            Map<Long, BigDecimal> revenueLines,
                                            Map<Long, BigDecimal> taxLines,
                                            BigDecimal totalAmount,
                                            String referenceNumber) {
        Objects.requireNonNull(dealerId, "Dealer ID is required");
        Objects.requireNonNull(orderNumber, "Order number is required");
        Objects.requireNonNull(totalAmount, "Total amount is required");

        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = companyEntityLookup.requireDealer(company, dealerId);

        // Validate dealer has receivable account
        if (dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Dealer missing receivable account")
                    .withDetail("dealerId", dealerId)
                    .withDetail("dealerName", dealer.getName());
        }

        // Generate reference number
        String reference = StringUtils.hasText(referenceNumber)
                ? referenceNumber.trim()
                : referenceNumberService.salesOrderReference(company, orderNumber);

        // Check for duplicate
        if (journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).isPresent()) {
            log.info("Sales journal already exists for reference: {}", reference);
            return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                    .map(this::toSimpleDto)
                    .orElseThrow();
        }

        // Build journal lines
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String resolvedMemo = memo != null ? memo : "Sales order " + orderNumber;

        // Dr: Accounts Receivable
        lines.add(new JournalEntryRequest.JournalLineRequest(
                dealer.getReceivableAccount().getId(),
                resolvedMemo,
                totalAmount.abs(),
                BigDecimal.ZERO));

        // Cr: Revenue accounts
        if (revenueLines != null) {
            revenueLines.forEach((accountId, amount) -> {
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            resolvedMemo,
                            BigDecimal.ZERO,
                            amount.abs()));
                }
            });
        }

        // Cr: Tax accounts
        if (taxLines != null) {
            taxLines.forEach((accountId, amount) -> {
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            resolvedMemo,
                            BigDecimal.ZERO,
                            amount.abs()));
                }
            });
        }

        // Validate balance
        BigDecimal totalCredits = calculateTotalCredits(lines);
        if (totalAmount.subtract(totalCredits).abs().compareTo(BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Sales journal does not balance")
                    .withDetail("totalAmount", totalAmount)
                    .withDetail("totalCredits", totalCredits);
        }

        LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                postingDate,
                resolvedMemo,
                dealer.getId(),
                null,
                Boolean.FALSE,
                lines);

        log.info("Posting sales journal: reference={}, dealer={}, amount={}",
                reference, dealer.getName(), totalAmount);

        return accountingService.createJournalEntry(request);
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
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postPurchaseJournal(Long supplierId,
                                               String invoiceNumber,
                                               LocalDate invoiceDate,
                                               String memo,
                                               Map<Long, BigDecimal> inventoryLines,
                                               BigDecimal totalAmount) {
        return postPurchaseJournal(supplierId, invoiceNumber, invoiceDate, memo, inventoryLines, null, totalAmount, null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postPurchaseJournal(Long supplierId,
                                               String invoiceNumber,
                                               LocalDate invoiceDate,
                                               String memo,
                                               Map<Long, BigDecimal> inventoryLines,
                                               Map<Long, BigDecimal> taxLines,
                                               BigDecimal totalAmount,
                                               String referenceNumber) {
        Objects.requireNonNull(supplierId, "Supplier ID is required");
        Objects.requireNonNull(invoiceNumber, "Invoice number is required");
        Objects.requireNonNull(totalAmount, "Total amount is required");

        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = requireSupplier(company, supplierId);

        if (inventoryLines == null || inventoryLines.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Inventory lines are required for purchase journal");
        }
        inventoryLines.keySet()
                .forEach(accountId -> requireAccountById(company, accountId, "Inventory account"));

        // Validate supplier has payable account
        if (supplier.getPayableAccount() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Supplier missing payable account")
                    .withDetail("supplierId", supplierId)
                    .withDetail("supplierName", supplier.getName());
        }

        // Generate reference number
        String reference = StringUtils.hasText(referenceNumber)
                ? referenceNumber.trim()
                : referenceNumberService.purchaseReference(company, supplier, invoiceNumber);

        // Check for duplicate
        if (journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).isPresent()) {
            log.info("Purchase journal already exists for reference: {}", reference);
            return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
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
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            entry.getKey(),
                            resolvedMemo,
                            amount.abs(),
                            BigDecimal.ZERO));
                    inventoryTotal = inventoryTotal.add(amount.abs());
                }
            }
        }

        BigDecimal taxTotal = BigDecimal.ZERO;
        if (taxLines != null && !taxLines.isEmpty()) {
            TaxAccountConfiguration taxConfig = companyAccountingSettingsService.requireTaxAccounts();
            for (Map.Entry<Long, BigDecimal> entry : taxLines.entrySet()) {
                BigDecimal amount = entry.getValue();
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            entry.getKey() != null ? entry.getKey() : taxConfig.inputTaxAccountId(),
                            "Input tax for " + resolvedMemo,
                            amount.abs(),
                            BigDecimal.ZERO));
                    taxTotal = taxTotal.add(amount.abs());
                }
            }
        }

        if (inventoryTotal.add(taxTotal).compareTo(totalAmount.abs()) != 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Purchase totals do not balance inventory+tax to payable total")
                    .withDetail("inventoryTotal", inventoryTotal)
                    .withDetail("taxTotal", taxTotal)
                    .withDetail("totalAmount", totalAmount);
        }

        // Cr: Accounts Payable
        lines.add(new JournalEntryRequest.JournalLineRequest(
                supplier.getPayableAccount().getId(),
                resolvedMemo,
                BigDecimal.ZERO,
                totalAmount.abs()));

        LocalDate postingDate = invoiceDate != null ? invoiceDate : companyClock.today(company);

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                postingDate,
                resolvedMemo,
                null,
                supplier.getId(),
                Boolean.FALSE,
                lines);

        log.info("Posting purchase journal: reference={}, supplier={}, amount={}",
                reference, supplier.getName(), totalAmount);

        return accountingService.createJournalEntry(request);
    }

    /**
     * Post purchase return journal entry (Dr AP / Cr Inventory).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postPurchaseReturn(Long supplierId,
                                              String referenceNumber,
                                              LocalDate returnDate,
                                              String memo,
                                              Map<Long, BigDecimal> inventoryCredits,
                                              BigDecimal totalAmount) {
        Objects.requireNonNull(supplierId, "Supplier ID is required");
        Objects.requireNonNull(totalAmount, "Total amount is required");
        Objects.requireNonNull(inventoryCredits, "Inventory credits are required");

        if (inventoryCredits.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "At least one inventory line is required for purchase return");
        }

        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = requireSupplier(company, supplierId);

        String reference = StringUtils.hasText(referenceNumber)
                ? referenceNumber.trim()
                : referenceNumberService.purchaseReturnReference(company, supplier);

        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            log.info("Purchase return journal already exists for reference: {}", reference);
            return existing.map(this::toSimpleDto).orElseThrow();
        }

        LocalDate postingDate = returnDate != null ? returnDate : companyClock.today(company);
        String resolvedMemo = memo != null ? memo : "Purchase return for " + supplier.getName();
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();

        lines.add(new JournalEntryRequest.JournalLineRequest(
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
                lines.add(new JournalEntryRequest.JournalLineRequest(
                        accountId,
                        resolvedMemo,
                        BigDecimal.ZERO,
                        amount.abs()));
            }
        }

        if (totalAmount.subtract(totalCredits).abs().compareTo(BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Purchase return journal does not balance")
                    .withDetail("totalAmount", totalAmount)
                    .withDetail("totalCredits", totalCredits);
        }

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                postingDate,
                resolvedMemo,
                null,
                supplier.getId(),
                Boolean.FALSE,
                lines);

        log.info("Posting purchase return journal: reference={}, supplier={}, amount={}",
                reference, supplier.getName(), totalAmount);

        return accountingService.createJournalEntry(request);
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
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postMaterialConsumption(String productionCode,
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
            return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                    .map(this::toSimpleDto)
                    .orElseThrow();
        }

        // Build journal lines
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String memo = "Raw material consumption for " + productionCode;

        // Dr: WIP
        lines.add(new JournalEntryRequest.JournalLineRequest(
                wipAccountId,
                "WIP charge " + productionCode,
                totalCost,
                BigDecimal.ZERO));

        // Cr: Raw Material Inventory accounts
        if (materialLines != null) {
            materialLines.forEach((accountId, amount) -> {
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            "Raw material issue " + productionCode,
                            BigDecimal.ZERO,
                            amount.abs()));
                }
            });
        }

        LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                postingDate,
                memo,
                null,
                null,
                Boolean.FALSE,
                lines);

        log.info("Posting material consumption journal: reference={}, cost={}", reference, totalCost);

        return accountingService.createJournalEntry(request);
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
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postCostAllocation(String batchCode,
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

        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            log.info("Cost allocation journal already exists for reference: {}", reference);
            return existing.map(this::toSimpleDto).orElseThrow();
        }

        // Build journal lines
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String memo = notes != null ? notes : "Cost allocation for " + batchCode;

        // Dr: Finished Goods Inventory
        lines.add(new JournalEntryRequest.JournalLineRequest(
                finishedGoodsAcctId,
                "Allocated costs to finished goods",
                totalAmount,
                BigDecimal.ZERO));

        // Cr: Labor Expense
        if (laborAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    laborExpenseAcctId,
                    "Labor cost allocated to production",
                    BigDecimal.ZERO,
                    laborAmount));
        }

        // Cr: Overhead Expense
        if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    overheadExpenseAcctId,
                    "Overhead cost allocated to production",
                    BigDecimal.ZERO,
                    overheadAmount));
        }

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                companyClock.today(company),
                memo,
                null,
                null,
                Boolean.FALSE,
                lines);

        log.info("Posting cost allocation journal: reference={}, batch={}, amount={}",
                reference, batchCode, totalAmount);

        return accountingService.createJournalEntry(request);
    }

    /**
     * Post COGS journal entry (Dr COGS / Cr Inventory).
     *
     * @param referenceId      the reference ID (order ID, dispatch ID, etc.)
     * @param cogsAccountId    the COGS account ID
     * @param inventoryAcctId  the inventory account ID
     * @param cost             the cost amount
     * @param memo             optional memo
     * @return the created journal entry DTO
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postCOGS(String referenceId,
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
        String reference = "COGS-" + sanitize(referenceId);

        // Check for duplicate (allow variants with same logical reference)
        Optional<JournalEntry> existing = journalEntryRepository.findFirstByCompanyAndReferenceNumberStartingWith(company, reference);
        if (existing.isPresent()) {
            log.info("COGS journal already exists for reference: {}", reference);
            return toSimpleDto(existing.get());
        }

        // Build journal lines
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String resolvedMemo = memo != null ? memo : "COGS for " + referenceId;

        // Dr: COGS
        lines.add(new JournalEntryRequest.JournalLineRequest(
                cogsAccountId,
                resolvedMemo,
                cost,
                BigDecimal.ZERO));

        // Cr: Inventory
        lines.add(new JournalEntryRequest.JournalLineRequest(
                inventoryAcctId,
                resolvedMemo,
                BigDecimal.ZERO,
                cost));

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                companyClock.today(company),
                resolvedMemo,
                null,
                null,
                Boolean.FALSE,
                lines);

        log.info("Posting COGS journal: reference={}, cost={}", reference, cost);
        return accountingService.createJournalEntry(request);
    }

    public boolean hasCogsJournalFor(String referenceId) {
        Company company = companyContextService.requireCurrentCompany();
        String reference = "COGS-" + sanitize(referenceId);
        return journalEntryRepository.findFirstByCompanyAndReferenceNumberStartingWith(company, reference).isPresent();
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
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postSalesReturn(Long dealerId,
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
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Dealer missing receivable account")
                    .withDetail("dealerId", dealerId);
        }

        // Generate reference number
        String reference = "CRN-" + invoiceNumber;

        // Check for duplicate
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            log.info("Sales return journal already exists for reference: {}", reference);
            return toSimpleDto(existing.get());
        }

        // Build journal lines
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String reasonSuffix = StringUtils.hasText(reason) ? reason.trim() : "Return";
        String memo = reasonSuffix + " - " + invoiceNumber;

        // Dr: Revenue/Tax accounts (reverse the original entries)
        if (returnLines != null) {
            returnLines.forEach((accountId, amount) -> {
                if (amount != null && amount.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal debit = amount.compareTo(BigDecimal.ZERO) > 0 ? amount.abs() : BigDecimal.ZERO;
                    BigDecimal credit = amount.compareTo(BigDecimal.ZERO) < 0 ? amount.abs() : BigDecimal.ZERO;
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            memo,
                            debit,
                            credit));
                }
            });
        }

        // Cr: Accounts Receivable
        lines.add(new JournalEntryRequest.JournalLineRequest(
                dealer.getReceivableAccount().getId(),
                memo,
                BigDecimal.ZERO,
                totalAmount.abs()));

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                companyClock.today(company),
                memo,
                dealer.getId(),
                null,
                Boolean.FALSE,
                lines);

        log.info("Posting sales return journal: reference={}, dealer={}, amount={}",
                reference, dealer.getName(), totalAmount);

        return accountingService.createJournalEntry(request);
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
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postInventoryAdjustment(String adjustmentType,
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
        return postInventoryAdjustment(adjustmentType,
                referenceId,
                varianceAcctId,
                inventoryLines,
                increaseInventory,
                false,
                memo);
    }

    /**
     * Post inventory adjustment journal entry with multiple inventory lines.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postInventoryAdjustment(String adjustmentType,
                                                   String referenceId,
                                                   Long varianceAcctId,
                                                   Map<Long, BigDecimal> inventoryLines,
                                                   boolean increaseInventory,
                                                   boolean adminOverride,
                                                   String memo) {
        Objects.requireNonNull(adjustmentType, "Adjustment type is required");
        Objects.requireNonNull(referenceId, "Reference ID is required");
        Objects.requireNonNull(varianceAcctId, "Variance account ID is required");
        Objects.requireNonNull(inventoryLines, "Inventory lines are required");

        if (inventoryLines.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Inventory adjustment lines are required");
        }

        Company company = companyContextService.requireCurrentCompany();

        requireAccountById(company, varianceAcctId, "Variance account");
        inventoryLines.keySet().forEach(accountId -> requireAccountById(company, accountId, "Inventory account"));

        BigDecimal totalAmount = inventoryLines.values().stream()
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("Skipping inventory adjustment for {} - zero total amount", referenceId);
            return null;
        }

        String reference = StringUtils.hasText(referenceId)
                ? referenceId.trim()
                : referenceNumberService.inventoryAdjustmentReference(company, adjustmentType);
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            log.info("Inventory adjustment journal already exists for reference: {}", reference);
            return existing.map(this::toSimpleDto).orElseThrow();
        }

        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        String resolvedMemo = memo != null ? memo : adjustmentType + " adjustment";

        if (increaseInventory) {
            inventoryLines.forEach((accountId, amount) -> {
                BigDecimal absAmount = normalizeAmount(amount);
                if (absAmount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            resolvedMemo,
                            absAmount,
                            BigDecimal.ZERO));
                }
            });
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    varianceAcctId,
                    resolvedMemo,
                    BigDecimal.ZERO,
                    totalAmount));
        } else {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    varianceAcctId,
                    resolvedMemo,
                    totalAmount,
                    BigDecimal.ZERO));
            inventoryLines.forEach((accountId, amount) -> {
                BigDecimal absAmount = normalizeAmount(amount);
                if (absAmount.compareTo(BigDecimal.ZERO) > 0) {
                    lines.add(new JournalEntryRequest.JournalLineRequest(
                            accountId,
                            resolvedMemo,
                            BigDecimal.ZERO,
                            absAmount));
                }
            });
        }

        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                companyClock.today(company),
                resolvedMemo,
                null,
                null,
                adminOverride,
                lines);

        log.info("Posting inventory adjustment journal: reference={}, type={}, amount={}, increase={}",
                reference, adjustmentType, totalAmount, increaseInventory);

        return accountingService.createJournalEntry(request);
    }

    /**
     * Post a simple two-line journal entry (Dr/Credit with equal amount).
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public JournalEntryDto postSimpleJournal(String reference,
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

        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, resolvedReference);
        if (existing.isPresent()) {
            log.info("Manual journal already exists for reference: {}", resolvedReference);
            return existing.map(this::toSimpleDto).orElseThrow();
        }

        LocalDate postingDate = entryDate != null ? entryDate : companyClock.today(company);
        String resolvedMemo = memo != null ? memo : "Manual journal for " + resolvedReference;
        BigDecimal postingAmount = amount.abs();

        List<JournalEntryRequest.JournalLineRequest> lines = List.of(
                new JournalEntryRequest.JournalLineRequest(debitAccountId, resolvedMemo, postingAmount, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(creditAccountId, resolvedMemo, BigDecimal.ZERO, postingAmount)
        );

        JournalEntryRequest request = new JournalEntryRequest(
                resolvedReference,
                postingDate,
                resolvedMemo,
                null,
                null,
                adminOverride,
                lines);

        log.info("Posting manual journal: reference={}, amount={}", resolvedReference, postingAmount);
        return accountingService.createJournalEntry(request);
    }

    /**
     * Record payroll payment via AccountingService wrapper.
     */
    public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
        return accountingService.recordPayrollPayment(request);
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

    private Supplier requireSupplier(Company company, Long supplierId) {
        try {
            return companyEntityLookup.requireSupplier(company, supplierId);
        } catch (IllegalArgumentException ex) {
            throw new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                    "Supplier not found").withDetail("supplierId", supplierId);
        }
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
            throw new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                    accountType + " not found").withDetail("accountId", accountId);
        }
    }

    private BigDecimal calculateTotalCredits(List<JournalEntryRequest.JournalLineRequest> lines) {
        return lines.stream()
                .map(JournalEntryRequest.JournalLineRequest::credit)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private String sanitize(String value) {
        if (!StringUtils.hasText(value)) {
            return "GEN";
        }
        // Preserve hyphens for readability (BBP-2025-00001 stays readable)
        return value.replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
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
                null, null, null, null, null, null, null, null,
                List.of(),
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getPostedAt(),
                entry.getCreatedBy(),
                entry.getPostedBy(),
                entry.getLastModifiedBy()
        );
    }
}
