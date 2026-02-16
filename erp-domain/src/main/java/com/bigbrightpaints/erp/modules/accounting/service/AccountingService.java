package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audit.IntegrationFailureMetadataSchema;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventStore;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLine;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunLineRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceSettlementPolicy;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountingService {

    private static final Logger log = LoggerFactory.getLogger(AccountingService.class);

    // Exact zero tolerance enforced for double-entry accounting integrity.
    // All amounts must be properly rounded before posting to ensure perfect balance.
    private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = BigDecimal.ZERO;
    private static final BigDecimal FX_RATE_MIN = new BigDecimal("0.0001");
    private static final BigDecimal FX_RATE_MAX = new BigDecimal("100000");
    private static final BigDecimal FX_ROUNDING_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal ALLOCATION_TOLERANCE = new BigDecimal("0.01");
    private static final Duration IDEMPOTENCY_WAIT_TIMEOUT = Duration.ofSeconds(8);
    private static final long IDEMPOTENCY_WAIT_SLEEP_MS = 50L;
    private static final ThreadLocal<Boolean> SYSTEM_ENTRY_DATE_OVERRIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final int ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH = 100;
    private static final int ACCOUNTING_EVENT_ACCOUNT_CODE_MAX_LENGTH = 50;
    private static final int ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH = 500;
    private static final String ENTITY_TYPE_DEALER_RECEIPT = "DEALER_RECEIPT";
    private static final String ENTITY_TYPE_DEALER_RECEIPT_SPLIT = "DEALER_RECEIPT_SPLIT";
    private static final String ENTITY_TYPE_DEALER_SETTLEMENT = "DEALER_SETTLEMENT";
    private static final String ENTITY_TYPE_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";
    private static final String ENTITY_TYPE_SUPPLIER_SETTLEMENT = "SUPPLIER_SETTLEMENT";
    private static final String ENTITY_TYPE_CREDIT_NOTE = "CREDIT_NOTE";
    private static final String SETTLEMENT_DISCOUNT_LINE_DESCRIPTION = "settlement discount";
    private static final String SETTLEMENT_WRITE_OFF_LINE_DESCRIPTION = "settlement write-off";
    private static final String SETTLEMENT_FX_LOSS_LINE_DESCRIPTION = "fx loss on settlement";

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DealerLedgerService dealerLedgerService;
    private final SupplierLedgerService supplierLedgerService;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollRunLineRepository payrollRunLineRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final ReferenceNumberService referenceNumberService;
    private final ApplicationEventPublisher eventPublisher;
    private final CompanyClock companyClock;
    private final CompanyEntityLookup companyEntityLookup;
    private final PartnerSettlementAllocationRepository settlementAllocationRepository;
    private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    private final InvoiceRepository invoiceRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final DealerRepository dealerRepository;
    private final SupplierRepository supplierRepository;
    private final InvoiceSettlementPolicy invoiceSettlementPolicy;
    private final JournalReferenceResolver journalReferenceResolver;
    private final JournalReferenceMappingRepository journalReferenceMappingRepository;
    private final EntityManager entityManager;
    private final SystemSettingsService systemSettingsService;
    private final AuditService auditService;
    private final AccountingEventStore accountingEventStore;

    /**
     * When true, disables date validation for benchmark mode.
     * This allows posting entries with any date regardless of past/future constraints.
     */
    @Value("${erp.benchmark.skip-date-validation:false}")
    private boolean skipDateValidation;

    /**
     * When true, journal posting/reversal fails if event-trail persistence fails.
     * This is the staging/predeploy default to prevent silent audit-trail drops.
     */
    @Value("${erp.accounting.event-trail.strict:true}")
    private boolean strictAccountingEventTrail = true;

    public AccountingService(CompanyContextService companyContextService,
                             AccountRepository accountRepository,
                             JournalEntryRepository journalEntryRepository,
                             DealerLedgerService dealerLedgerService,
                             SupplierLedgerService supplierLedgerService,
                             PayrollRunRepository payrollRunRepository,
                             PayrollRunLineRepository payrollRunLineRepository,
                             AccountingPeriodService accountingPeriodService,
                             ReferenceNumberService referenceNumberService,
                             ApplicationEventPublisher eventPublisher,
                             CompanyClock companyClock,
                             CompanyEntityLookup companyEntityLookup,
                             PartnerSettlementAllocationRepository settlementAllocationRepository,
                             RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                             InvoiceRepository invoiceRepository,
                             RawMaterialMovementRepository rawMaterialMovementRepository,
                             RawMaterialBatchRepository rawMaterialBatchRepository,
                             FinishedGoodBatchRepository finishedGoodBatchRepository,
                             DealerRepository dealerRepository,
                             SupplierRepository supplierRepository,
                             InvoiceSettlementPolicy invoiceSettlementPolicy,
                             JournalReferenceResolver journalReferenceResolver,
                             JournalReferenceMappingRepository journalReferenceMappingRepository,
                             EntityManager entityManager,
                             SystemSettingsService systemSettingsService,
                             AuditService auditService,
                             AccountingEventStore accountingEventStore) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.supplierLedgerService = supplierLedgerService;
        this.payrollRunRepository = payrollRunRepository;
        this.payrollRunLineRepository = payrollRunLineRepository;
        this.accountingPeriodService = accountingPeriodService;
        this.referenceNumberService = referenceNumberService;
        this.eventPublisher = eventPublisher;
        this.companyClock = companyClock;
        this.companyEntityLookup = companyEntityLookup;
        this.settlementAllocationRepository = settlementAllocationRepository;
        this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
        this.invoiceRepository = invoiceRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.dealerRepository = dealerRepository;
        this.supplierRepository = supplierRepository;
        this.invoiceSettlementPolicy = invoiceSettlementPolicy;
        this.journalReferenceResolver = journalReferenceResolver;
        this.journalReferenceMappingRepository = journalReferenceMappingRepository;
        this.entityManager = entityManager;
        this.systemSettingsService = systemSettingsService;
        this.auditService = auditService;
        this.accountingEventStore = accountingEventStore;
    }

    /* Accounts */
    public List<AccountDto> listAccounts() {
        Company company = companyContextService.requireCurrentCompany();
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public AccountDto createAccount(AccountRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Account account = new Account();
        account.setCompany(company);
        account.setCode(request.code());
        account.setName(request.name());
        account.setType(request.type());
        
        // Handle parent-child hierarchy
        if (request.parentId() != null) {
            Account parent = accountRepository.findByCompanyAndId(company, request.parentId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, 
                            "Parent account not found"));
            if (parent.getType() != request.type()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Child account must have same type as parent");
            }
            account.setParent(parent);
        }
        
        Account saved = accountRepository.save(account);
        publishAccountCacheInvalidated(company.getId());
        return toDto(saved);
    }

    /* Journal Entries */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<JournalEntryDto> listJournalEntries(Long dealerId, Long supplierId, int page, int size) {
        if (dealerId != null && supplierId != null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Only one of dealerId or supplierId can be provided");
        }
        Company company = companyContextService.requireCurrentCompany();
        int safeSize = Math.max(1, Math.min(size, 200));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);
        List<JournalEntry> entries;
        if (dealerId != null) {
            Dealer dealer = requireDealer(company, dealerId);
            entries = journalEntryRepository.findByCompanyAndDealerOrderByEntryDateDescIdDesc(company, dealer, pageable).getContent();
        } else if (supplierId != null) {
            Supplier supplier = requireSupplier(company, supplierId);
            entries = journalEntryRepository.findByCompanyAndSupplierOrderByEntryDateDescIdDesc(company, supplier, pageable).getContent();
        } else {
            entries = journalEntryRepository.findByCompanyOrderByEntryDateDescIdDesc(company, pageable).getContent();
        }
        return entries.stream().map(this::toDto).toList();
    }

    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        return listJournalEntries(dealerId, null, 0, 100);
    }

    public List<JournalEntryDto> listJournalEntriesByReferencePrefix(String prefix) {
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntry> entries = journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(company, prefix);
        return entries.stream().map(this::toDto).toList();
    }

    @Transactional
    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        Map<String, String> auditMetadata = new HashMap<>();
        if (request != null && request.referenceNumber() != null) {
            auditMetadata.put("requestedReference", request.referenceNumber());
        }
        try {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Journal entry request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntryRequest.JournalLineRequest> lines = request.lines();
        if (company.getId() != null) {
            auditMetadata.put("companyId", company.getId().toString());
        }
        if (lines == null || lines.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one journal line is required");
        }
        String currency = resolveCurrency(request.currency(), company);
        BigDecimal fxRate = resolveFxRate(currency, company, request.fxRate());
        String baseCurrency = company.getBaseCurrency() != null && !company.getBaseCurrency().isBlank()
                ? company.getBaseCurrency().trim().toUpperCase()
                : "INR";
        boolean foreignCurrency = !currency.equalsIgnoreCase(baseCurrency);
        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
        entry.setCurrency(currency);
        entry.setFxRate(fxRate);
        entry.setReferenceNumber(resolveJournalReference(company, request.referenceNumber()));
        auditMetadata.put("referenceNumber", entry.getReferenceNumber());

        Optional<JournalEntry> duplicate = journalEntryRepository.findByCompanyAndReferenceNumber(company, entry.getReferenceNumber());

        LocalDate entryDate = request.entryDate();
        if (entryDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date is required");
        }
        boolean overrideRequested = Boolean.TRUE.equals(request.adminOverride());
        boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
        if (duplicate.isEmpty()) {
            validateEntryDate(company, entryDate, overrideRequested, overrideAuthorized);
            AccountingPeriod postingPeriod = systemSettingsService.isPeriodLockEnforced()
                    ? accountingPeriodService.requireOpenPeriod(company, entryDate)
                    : accountingPeriodService.ensurePeriod(company, entryDate);
            if (postingPeriod == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Accounting period is required for journal posting");
            }
            entry.setAccountingPeriod(postingPeriod);
        }
        entry.setEntryDate(entryDate);
        entry.setMemo(request.memo());
        entry.setStatus("POSTED");
        Dealer dealer = null;
        Account dealerReceivableAccount = null;
        Supplier supplier = null;
        Account supplierPayableAccount = null;
        if (request.dealerId() != null) {
            dealer = requireDealer(company, request.dealerId());
            dealerReceivableAccount = dealer.getReceivableAccount();
            entry.setDealer(dealer);
        }
        if (request.supplierId() != null) {
            supplier = requireSupplier(company, request.supplierId());
            supplierPayableAccount = supplier.getPayableAccount();
            entry.setSupplier(supplier);
        }
        Map<Account, BigDecimal> accountDeltas = new HashMap<>();
        BigDecimal dealerLedgerDebitTotal = BigDecimal.ZERO;
        BigDecimal dealerLedgerCreditTotal = BigDecimal.ZERO;
        BigDecimal supplierLedgerDebitTotal = BigDecimal.ZERO;
        BigDecimal supplierLedgerCreditTotal = BigDecimal.ZERO;
        int dealerArLines = 0;
        int supplierApLines = 0;
        List<Long> sortedAccountIds = lines.stream()
                .map(JournalEntryRequest.JournalLineRequest::accountId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        Map<Long, Account> lockedAccounts = new HashMap<>();
        for (Long accountId : sortedAccountIds) {
            Account account = accountRepository.lockByCompanyAndId(company, accountId)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found"));
            lockedAccounts.put(accountId, account);
        }
        Dealer dealerContext = dealer;
        Supplier supplierContext = supplier;
        boolean hasReceivableAccount = false;
        boolean hasPayableAccount = false;
        for (Account account : lockedAccounts.values()) {
            if (isReceivableAccount(account)) {
                hasReceivableAccount = true;
            }
            if (isPayableAccount(account)) {
                hasPayableAccount = true;
            }
            List<Dealer> dealerOwners = dealerRepository.findAllByCompanyAndReceivableAccount(company, account);
            if (!dealerOwners.isEmpty()) {
                if (dealerContext == null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Dealer receivable account " + account.getCode() + " requires a dealer context");
                }
                if (dealerOwners.stream().noneMatch(owner -> owner.getId().equals(dealerContext.getId()))) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Dealer receivable account " + account.getCode() + " requires matching dealer context");
                }
            }
            List<Supplier> supplierOwners = supplierRepository.findAllByCompanyAndPayableAccount(company, account);
            if (!supplierOwners.isEmpty()) {
                if (supplierContext == null) {
                    if (supplierOwners.size() == 1) {
                        supplierContext = supplierOwners.get(0);
                        supplier = supplierContext;
                        supplierPayableAccount = supplierContext.getPayableAccount();
                        entry.setSupplier(supplierContext);
                    } else {
                        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                                "Supplier payable account " + account.getCode() + " requires a supplier context");
                    }
                }
                Long supplierContextId = supplierContext.getId();
                if (supplierOwners.stream().noneMatch(owner -> owner.getId().equals(supplierContextId))) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Supplier payable account " + account.getCode() + " requires matching supplier context");
                }
            }
        }
        if (hasReceivableAccount && hasPayableAccount) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Journal entry cannot combine AR and AP accounts; split into separate entries");
        }
        if (hasReceivableAccount && dealerContext == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Posting to AR requires a dealer context");
        }
        if (hasPayableAccount && supplierContext == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Posting to AP requires a supplier context");
        }
        if (dealerContext != null && hasReceivableAccount && dealerReceivableAccount == null) {
            dealerReceivableAccount = requireDealerReceivable(dealerContext);
        }
        if (supplierContext != null && hasPayableAccount && supplierPayableAccount == null) {
            supplierPayableAccount = requireSupplierPayable(supplierContext);
        }
        BigDecimal totalBaseDebit = BigDecimal.ZERO;
        BigDecimal totalBaseCredit = BigDecimal.ZERO;
        BigDecimal totalForeignDebit = BigDecimal.ZERO;
        BigDecimal totalForeignCredit = BigDecimal.ZERO;
        List<JournalLine> postedLines = new ArrayList<>();
        for (JournalEntryRequest.JournalLineRequest lineRequest : lines) {
            if (lineRequest.accountId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Account is required for every journal line");
            }
            Account account = lockedAccounts.get(lineRequest.accountId());
            if (account == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Account not found");
            }
            JournalLine line = new JournalLine();
            line.setJournalEntry(entry);
            line.setAccount(account);
            line.setDescription(lineRequest.description());

            BigDecimal debitInput = lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit();
            BigDecimal creditInput = lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit();
            if (debitInput.compareTo(BigDecimal.ZERO) < 0 || creditInput.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit/Credit cannot be negative");
            }
            if (debitInput.compareTo(BigDecimal.ZERO) > 0 && creditInput.compareTo(BigDecimal.ZERO) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit and credit cannot both be non-zero on the same line");
            }

            if (foreignCurrency) {
                totalForeignDebit = totalForeignDebit.add(debitInput);
                totalForeignCredit = totalForeignCredit.add(creditInput);
            }

            BigDecimal baseDebit = toBaseCurrency(debitInput, fxRate);
            BigDecimal baseCredit = toBaseCurrency(creditInput, fxRate);
            line.setDebit(baseDebit);
            line.setCredit(baseCredit);
            entry.getLines().add(line);
            postedLines.add(line);
            accountDeltas.merge(account, baseDebit.subtract(baseCredit), BigDecimal::add);
            totalBaseDebit = totalBaseDebit.add(baseDebit);
            totalBaseCredit = totalBaseCredit.add(baseCredit);

            if (dealerReceivableAccount != null && Objects.equals(account.getId(), dealerReceivableAccount.getId())) {
                dealerArLines++;
            }
            if (supplierPayableAccount != null && Objects.equals(account.getId(), supplierPayableAccount.getId())) {
                supplierApLines++;
            }
        }
        BigDecimal roundingDelta = totalBaseDebit.subtract(totalBaseCredit);
        if (roundingDelta.compareTo(BigDecimal.ZERO) != 0) {
            if (roundingDelta.abs().compareTo(FX_ROUNDING_TOLERANCE) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Journal entry must balance" )
                        .withDetail("delta", roundingDelta)
                        .withDetail("currency", currency)
                        .withDetail("fxRate", fxRate);
            }
            // Adjust a single line to absorb minor FX/base rounding variance.
            if (roundingDelta.signum() > 0) {
                JournalLine target = postedLines.stream()
                        .filter(l -> l.getCredit().compareTo(BigDecimal.ZERO) > 0)
                        .max(Comparator.comparing(JournalLine::getCredit))
                        .orElse(null);
                if (target != null) {
                    target.setCredit(target.getCredit().add(roundingDelta));
                    accountDeltas.merge(target.getAccount(), roundingDelta.negate(), BigDecimal::add);
                    totalBaseCredit = totalBaseCredit.add(roundingDelta);
                }
            } else {
                BigDecimal adjust = roundingDelta.abs();
                JournalLine target = postedLines.stream()
                        .filter(l -> l.getDebit().compareTo(BigDecimal.ZERO) > 0)
                        .max(Comparator.comparing(JournalLine::getDebit))
                        .orElse(null);
                if (target != null) {
                    target.setDebit(target.getDebit().add(adjust));
                    accountDeltas.merge(target.getAccount(), adjust, BigDecimal::add);
                    totalBaseDebit = totalBaseDebit.add(adjust);
                }
            }
        }
        if (totalBaseDebit.subtract(totalBaseCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance");
        }

        if (foreignCurrency && totalForeignDebit.compareTo(BigDecimal.ZERO) > 0) {
            entry.setForeignAmountTotal(totalForeignDebit.setScale(2, RoundingMode.HALF_UP));
        }

        if (duplicate.isPresent()) {
            JournalEntry existingEntry = duplicate.get();
            if (existingEntry.getId() != null) {
                auditMetadata.put("journalEntryId", existingEntry.getId().toString());
            }
            ensureDuplicateMatchesExisting(existingEntry, entry, postedLines);
            log.info("Idempotent return: journal entry '{}' already exists, returning existing entry",
                    entry.getReferenceNumber());
            auditMetadata.put("idempotent", "true");
            logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
            return toDto(existingEntry);
        }

        // Only enforce AR/AP validation when AR/AP lines are present (allow partner context with zero AR/AP lines for COGS/inventory entries)
        if (dealer != null && dealerReceivableAccount != null && dealerArLines > 1 && !overrideAuthorized) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Dealer journal entry has multiple receivable lines; admin override required");
        }
        if (supplier != null && supplierPayableAccount != null && supplierApLines > 1 && !overrideAuthorized) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Supplier journal entry has multiple payable lines; admin override required");
        }
        Instant now = CompanyTime.now(company);
        String username = resolveCurrentUsername();
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setPostedAt(now);
        entry.setCreatedBy(username);
        entry.setLastModifiedBy(username);
        entry.setPostedBy(username);
        JournalEntry saved;
        try {
            saved = journalEntryRepository.save(entry);
        } catch (DataIntegrityViolationException ex) {
            Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, entry.getReferenceNumber());
            if (existing.isPresent()) {
                JournalEntry existingEntry = existing.get();
                if (existingEntry.getId() != null) {
                    auditMetadata.put("journalEntryId", existingEntry.getId().toString());
                }
                ensureDuplicateMatchesExisting(existingEntry, entry, postedLines);
                log.info("Idempotent return: journal entry '{}' already exists, returning existing entry",
                        entry.getReferenceNumber());
                auditMetadata.put("idempotent", "true");
                logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
                return toDto(existingEntry);
            }
            throw ex;
        }
        boolean postedEventTrailRecorded = true;
        if (!accountDeltas.isEmpty()) {
            // Sort accounts by ID to prevent deadlocks - consistent lock ordering
            List<Map.Entry<Account, BigDecimal>> sortedDeltas = accountDeltas.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getKey().getId()))
                    .toList();
            Map<Long, BigDecimal> balancesBefore = new HashMap<>();
            for (Map.Entry<Account, BigDecimal> delta : sortedDeltas) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
                if (account.getId() != null) {
                    balancesBefore.putIfAbsent(account.getId(), current);
                }
                BigDecimal updated = current.add(delta.getValue());
                account.validateBalanceUpdate(updated);
                int rows = accountRepository.updateBalanceAtomic(company, account.getId(), delta.getValue());
                if (rows != 1) {
                    throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE, "Account balance update failed for " + account.getCode());
                }
            }
            // Detach accounts from persistence context so they'll be re-fetched fresh with updated balances
            for (Account account : accountDeltas.keySet()) {
                entityManager.detach(account);
            }
            publishAccountCacheInvalidated(company.getId());
            postedEventTrailRecorded = recordJournalEntryPostedEventSafe(saved, balancesBefore);
        }
        if (saved.getDealer() != null && dealerReceivableAccount != null) {
            for (JournalLine l : saved.getLines()) {
                if (l.getAccount() != null && Objects.equals(l.getAccount().getId(), dealerReceivableAccount.getId())) {
                    dealerLedgerDebitTotal = dealerLedgerDebitTotal.add(l.getDebit());
                    dealerLedgerCreditTotal = dealerLedgerCreditTotal.add(l.getCredit());
                }
            }
            if (dealerLedgerDebitTotal.compareTo(BigDecimal.ZERO) != 0
                    || dealerLedgerCreditTotal.compareTo(BigDecimal.ZERO) != 0) {
                dealerLedgerService.recordLedgerEntry(
                        saved.getDealer(),
                        new AbstractPartnerLedgerService.LedgerContext(
                                saved.getEntryDate(),
                                saved.getReferenceNumber(),
                                saved.getMemo(),
                                dealerLedgerDebitTotal,
                                dealerLedgerCreditTotal,
                                saved));
            }
        }
        if (saved.getSupplier() != null && supplierPayableAccount != null) {
            for (JournalLine l : saved.getLines()) {
                if (l.getAccount() != null && Objects.equals(l.getAccount().getId(), supplierPayableAccount.getId())) {
                    supplierLedgerDebitTotal = supplierLedgerDebitTotal.add(l.getDebit());
                    supplierLedgerCreditTotal = supplierLedgerCreditTotal.add(l.getCredit());
                }
            }
            if (supplierLedgerDebitTotal.compareTo(BigDecimal.ZERO) != 0
                    || supplierLedgerCreditTotal.compareTo(BigDecimal.ZERO) != 0) {
                supplierLedgerService.recordLedgerEntry(
                        saved.getSupplier(),
                        new AbstractPartnerLedgerService.LedgerContext(
                                saved.getEntryDate(),
                                saved.getReferenceNumber(),
                                saved.getMemo(),
                                supplierLedgerDebitTotal,
                                supplierLedgerCreditTotal,
                                saved));
            }
        }
        if (saved.getId() != null) {
            auditMetadata.put("journalEntryId", saved.getId().toString());
        }
        auditMetadata.put("status", saved.getStatus());
        if (postedEventTrailRecorded) {
            logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
        }
        return toDto(saved);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                auditMetadata.put("error", e.getMessage());
            }
            auditService.logFailure(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
            throw e;
        }
    }

    @Transactional
    public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryId);
        return reverseJournalEntryInternal(company, entry, request, false);
    }

    @Transactional
    JournalEntryDto reverseClosingEntryForPeriodReopen(JournalEntry entry, AccountingPeriod period, String reason) {
        if (entry == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Closing journal entry is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        LocalDate reversalDate = entry.getEntryDate() != null
                ? entry.getEntryDate()
                : (period != null ? period.getEndDate() : currentDate(company));
        LocalDate today = currentDate(company);
        if (reversalDate != null && reversalDate.isAfter(today)) {
            reversalDate = today;
        }
        String memo = "Reopen reversal of " + entry.getReferenceNumber();
        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                reversalDate,
                false,
                StringUtils.hasText(reason) ? reason.trim() : "Period reopen",
                memo,
                Boolean.TRUE
        );
        return reverseJournalEntryInternal(company, entry, request, true);
    }

    private JournalEntryDto reverseJournalEntryInternal(Company company,
                                                       JournalEntry entry,
                                                       JournalEntryReversalRequest request,
                                                       boolean allowClosedPeriodOverride) {
        // Validate entry state
        if ("VOIDED".equalsIgnoreCase(entry.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Entry is already voided");
        }
        if ("REVERSED".equalsIgnoreCase(entry.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Entry has already been reversed");
        }

        // Determine reversal date and authorization
        LocalDate reversalDate = request != null && request.reversalDate() != null
                ? request.reversalDate()
                : currentDate(company);
        boolean overrideRequested = request != null && Boolean.TRUE.equals(request.adminOverride());
        boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
        if (allowClosedPeriodOverride) {
            overrideAuthorized = true;
        }
        validateEntryDate(company, reversalDate, overrideRequested, overrideAuthorized);

        // PERIOD LOCK CHECK: Strictly enforce period status
        AccountingPeriod postingPeriod = systemSettingsService.isPeriodLockEnforced()
                ? accountingPeriodService.requireOpenPeriod(company, reversalDate)
                : accountingPeriodService.ensurePeriod(company, reversalDate);
        AccountingPeriod originalPeriod = entry.getAccountingPeriod();
        if (originalPeriod != null) {
            if (originalPeriod.getStatus() == AccountingPeriodStatus.LOCKED) {
                throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                        "Cannot reverse entry from LOCKED period " + originalPeriod.getYear() + "-" +
                                originalPeriod.getMonth() + ". Period must be unlocked first.");
            }
            if (originalPeriod.getStatus() == AccountingPeriodStatus.CLOSED && !overrideAuthorized) {
                throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                        "Entry belongs to CLOSED period. Administrator override with audit approval required.");
            }
        }

        // Build audit trail reason
        String sanitizedReason = buildAuditReason(request, entry);
        String memo = request != null && StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Reversal of " + entry.getReferenceNumber();
        // Calculate reversal amounts (supports partial reversals)
        BigDecimal reversalFactor = request != null && request.isPartialReversal()
                ? request.getEffectivePercentage().divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)
                : BigDecimal.ONE;
        boolean isPartial = request != null && request.isPartialReversal();
        String partialNote = isPartial ? " (" + request.getEffectivePercentage() + "% partial)" : "";

        List<JournalEntryRequest.JournalLineRequest> reversedLines = entry.getLines().stream()
                .map(line -> new JournalEntryRequest.JournalLineRequest(
                        line.getAccount().getId(),
                        "Reversal" + partialNote + ": " + (line.getDescription() == null ? entry.getMemo() : line.getDescription()),
                        line.getCredit().multiply(reversalFactor).setScale(2, RoundingMode.HALF_UP),
                        line.getDebit().multiply(reversalFactor).setScale(2, RoundingMode.HALF_UP)))
                .toList();
        JournalEntryRequest payload = new JournalEntryRequest(
                referenceNumberService.reversalReference(entry.getReferenceNumber()),
                reversalDate,
                memo,
                entry.getDealer() != null ? entry.getDealer().getId() : null,
                entry.getSupplier() != null ? entry.getSupplier().getId() : null,
                request != null ? request.adminOverride() : null,
                reversedLines
        );
        if (request != null && request.voidOnly()) {
            Instant now = CompanyTime.now(company);
            JournalEntryDto reversalDto = createJournalEntryForReversal(payload, allowClosedPeriodOverride);
            JournalEntry reversalEntry = companyEntityLookup.requireJournalEntry(company, reversalDto.id());
            reversalEntry.setReversalOf(entry);
            reversalEntry.setAccountingPeriod(postingPeriod);
            reversalEntry.setCorrectionType(JournalCorrectionType.VOID);
            reversalEntry.setCorrectionReason(sanitizedReason);
            reversalEntry.setLastModifiedBy(resolveCurrentUsername());
            journalEntryRepository.save(reversalEntry);

            entry.setStatus("VOIDED");
            entry.setCorrectionType(JournalCorrectionType.VOID);
            entry.setCorrectionReason(sanitizedReason);
            entry.setVoidReason(sanitizedReason);
            entry.setVoidedAt(now);
            entry.setReversalEntry(reversalEntry);
            entry.setLastModifiedBy(resolveCurrentUsername());
            journalEntryRepository.save(entry);
            Map<String, String> auditMetadata = new HashMap<>();
            auditMetadata.put("originalJournalEntryId", entry.getId().toString());
            auditMetadata.put("reversalEntryId", reversalEntry.getId().toString());
            auditMetadata.put("reversalType", JournalCorrectionType.VOID.name());
            auditMetadata.put("reversalDate", reversalDate.toString());
            auditMetadata.put("partial", Boolean.toString(isPartial));
            auditMetadata.put("reversalFactor", reversalFactor.toPlainString());
            auditMetadata.put("adminOverrideRequested", Boolean.toString(overrideRequested));
            auditMetadata.put("adminOverrideAuthorized", Boolean.toString(overrideAuthorized));
            if (entry.getReferenceNumber() != null) {
                auditMetadata.put("referenceNumber", entry.getReferenceNumber());
            }
            if (sanitizedReason != null) {
                auditMetadata.put("reason", sanitizedReason);
            }
            recordJournalEntryReversedEventSafe(entry, reversalEntry, sanitizedReason);
            logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
            return toDto(reversalEntry);
        }
        JournalEntryDto reversalDto = createJournalEntryForReversal(payload, allowClosedPeriodOverride);
        JournalEntry reversalEntry = companyEntityLookup.requireJournalEntry(company, reversalDto.id());
        reversalEntry.setReversalOf(entry);
        reversalEntry.setAccountingPeriod(postingPeriod);
        reversalEntry.setCorrectionType(JournalCorrectionType.REVERSAL);
        reversalEntry.setCorrectionReason(sanitizedReason);
        reversalEntry.setLastModifiedBy(resolveCurrentUsername());
        journalEntryRepository.save(reversalEntry);
        entry.setStatus("REVERSED");
        entry.setCorrectionType(JournalCorrectionType.REVERSAL);
        entry.setCorrectionReason(sanitizedReason);
        entry.setVoidReason(null);
        entry.setVoidedAt(null);
        entry.setReversalEntry(reversalEntry);
        entry.setLastModifiedBy(resolveCurrentUsername());
        journalEntryRepository.save(entry);
        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("originalJournalEntryId", entry.getId().toString());
        auditMetadata.put("reversalEntryId", reversalEntry.getId().toString());
        auditMetadata.put("reversalType", JournalCorrectionType.REVERSAL.name());
        auditMetadata.put("reversalDate", reversalDate.toString());
        auditMetadata.put("partial", Boolean.toString(isPartial));
        auditMetadata.put("reversalFactor", reversalFactor.toPlainString());
        auditMetadata.put("adminOverrideRequested", Boolean.toString(overrideRequested));
        auditMetadata.put("adminOverrideAuthorized", Boolean.toString(overrideAuthorized));
        if (entry.getReferenceNumber() != null) {
            auditMetadata.put("referenceNumber", entry.getReferenceNumber());
        }
        if (sanitizedReason != null) {
            auditMetadata.put("reason", sanitizedReason);
        }
        recordJournalEntryReversedEventSafe(entry, reversalEntry, sanitizedReason);
        logAuditSuccessAfterCommit(AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
        return reversalDto;
    }

    @Transactional
    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
        Account receivableAccount = requireDealerReceivable(dealer);
        Account cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "dealer receipt", false);
        BigDecimal amount = requirePositive(request.amount(), "amount");
        List<SettlementAllocationRequest> allocations = request.allocations();
        validatePaymentAllocations(allocations, amount, "dealer receipt", true);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Receipt for dealer " + dealer.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : buildDealerReceiptReference(company, dealer, request);
        String idempotencyKey = resolveReceiptIdempotencyKey(request.idempotencyKey(), reference, "dealer receipt");
        IdempotencyReservation reservation = reserveReferenceMapping(company, idempotencyKey, reference, ENTITY_TYPE_DEALER_RECEIPT);

        if (!reservation.leader()) {
            JournalEntry existingEntry = awaitJournalEntry(company, reference, idempotencyKey);
            List<PartnerSettlementAllocation> existingAllocations =
                    resolveAllocationsForIdempotentReceiptReplay(company, idempotencyKey, existingEntry);
            if (!existingAllocations.isEmpty()) {
                JournalEntry entry = resolveReplayJournalEntry(idempotencyKey, existingEntry, existingAllocations);
                linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT);
                validateDealerReceiptIdempotency(idempotencyKey, dealer, cashAccount, receivableAccount, amount, memo, entry,
                        existingAllocations, allocations);
                return toDto(entry);
            }
            throw missingReservedPartnerAllocation(
                    "Dealer receipt",
                    idempotencyKey,
                    PartnerType.DEALER,
                    dealer.getId());
        }

        List<PartnerSettlementAllocation> existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            JournalEntry entry = resolveReplayJournalEntryFromExistingAllocations(
                    company,
                    reference,
                    idempotencyKey,
                    existingAllocations);
            linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT);
            validateDealerReceiptIdempotency(idempotencyKey, dealer, cashAccount, receivableAccount, amount, memo, entry,
                    existingAllocations, allocations);
            return toDto(entry);
        }
        cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "dealer receipt", true);
        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                currentDate(company),
                memo,
                dealer.getId(),
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(receivableAccount.getId(), memo, BigDecimal.ZERO, amount)
                )
        );
        JournalEntryDto entryDto = createJournalEntry(payload);
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryDto.id());
        linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT);
        existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            validateDealerReceiptIdempotency(idempotencyKey, dealer, cashAccount, receivableAccount, amount, memo, entry,
                    existingAllocations, allocations);
            return entryDto;
        }
        List<PartnerSettlementAllocation> existingEntryAllocations = settlementAllocationRepository
                .findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry);
        if (!existingEntryAllocations.isEmpty()) {
            validateDealerReceiptIdempotency(idempotencyKey, dealer, cashAccount, receivableAccount, amount, memo, entry,
                    existingEntryAllocations, allocations);
            return entryDto;
        }
        LocalDate entryDate = entry.getEntryDate();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<Invoice> touchedInvoices = new ArrayList<>();
        Map<Long, BigDecimal> remainingByInvoice = new HashMap<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Invoice allocation is required for dealer settlements");
            }
            if (allocation.purchaseId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dealer settlements cannot allocate to purchases");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            Invoice invoice = invoiceRepository.lockByCompanyAndId(company, allocation.invoiceId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
            if (invoice.getDealer() == null || !invoice.getDealer().getId().equals(dealer.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
            }
            enforceSettlementCurrency(company, invoice);
            BigDecimal currentOutstanding = remainingByInvoice.getOrDefault(
                    invoice.getId(),
                    MoneyUtils.zeroIfNull(invoice.getOutstandingAmount()));
            if (applied.compareTo(currentOutstanding) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Allocation exceeds invoice outstanding amount")
                        .withDetail("invoiceId", invoice.getId())
                        .withDetail("outstanding", currentOutstanding)
                        .withDetail("applied", applied);
            }
            remainingByInvoice.put(invoice.getId(), currentOutstanding.subtract(applied).max(BigDecimal.ZERO));

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
        try {
            settlementAllocationRepository.saveAll(settlementRows);
        } catch (DataIntegrityViolationException ex) {
            List<PartnerSettlementAllocation> concurrent = findAllocationsByIdempotencyKey(company, idempotencyKey);
            if (!concurrent.isEmpty()) {
                JournalEntry existingEntry = resolveReplayJournalEntryFromExistingAllocations(
                        company,
                        reference,
                        idempotencyKey,
                        concurrent);
                linkReferenceMapping(company, idempotencyKey, existingEntry, ENTITY_TYPE_DEALER_RECEIPT);
                validateDealerReceiptIdempotency(idempotencyKey, dealer, cashAccount, receivableAccount, amount, memo, existingEntry,
                        concurrent, allocations);
                return toDto(existingEntry);
            }
            throw ex;
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
        }
        return entryDto;
    }

    @Transactional
    public JournalEntryDto recordDealerReceiptSplit(DealerReceiptSplitRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
        Account receivableAccount = requireDealerReceivable(dealer);
        if (request.incomingLines() == null || request.incomingLines().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one incoming line is required");
        }
        BigDecimal total = BigDecimal.ZERO;
        List<JournalEntryRequest.JournalLineRequest> lines = new java.util.ArrayList<>();
        for (DealerReceiptSplitRequest.IncomingLine line : request.incomingLines()) {
            Account incoming = requireCashAccountForSettlement(company, line.accountId(), "dealer split receipt", false);
            BigDecimal amt = requirePositive(line.amount(), "amount");
            total = total.add(amt);
            lines.add(new JournalEntryRequest.JournalLineRequest(incoming.getId(), "Dealer receipt", amt, BigDecimal.ZERO));
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Total receipt amount must be greater than zero");
        }
        lines.add(new JournalEntryRequest.JournalLineRequest(receivableAccount.getId(), "Dealer receipt", BigDecimal.ZERO, total));

        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Receipt for dealer " + dealer.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : buildDealerReceiptReference(company, dealer, request);
        String idempotencyKey = resolveReceiptIdempotencyKey(request.idempotencyKey(), reference, "dealer receipt");
        IdempotencyReservation reservation = reserveReferenceMapping(company, idempotencyKey, reference, ENTITY_TYPE_DEALER_RECEIPT_SPLIT);
        if (!reservation.leader()) {
            JournalEntry existingEntry = awaitJournalEntry(company, reference, idempotencyKey);
            List<PartnerSettlementAllocation> existingAllocations =
                    resolveAllocationsForIdempotentReceiptReplay(company, idempotencyKey, existingEntry);
            if (!existingAllocations.isEmpty()) {
                JournalEntry entry = resolveReplayJournalEntry(idempotencyKey, existingEntry, existingAllocations);
                linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT_SPLIT);
                validateSplitReceiptIdempotency(idempotencyKey, dealer, memo, entry, lines);
                return toDto(entry);
            }
            throw missingReservedPartnerAllocation(
                    "Dealer receipt",
                    idempotencyKey,
                    PartnerType.DEALER,
                    dealer.getId());
        }
        List<PartnerSettlementAllocation> existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            JournalEntry entry = resolveReplayJournalEntryFromExistingAllocations(
                    company,
                    reference,
                    idempotencyKey,
                    existingAllocations);
            linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT_SPLIT);
            validateSplitReceiptIdempotency(idempotencyKey, dealer, memo, entry, lines);
            return toDto(entry);
        }

        for (DealerReceiptSplitRequest.IncomingLine line : request.incomingLines()) {
            requireCashAccountForSettlement(company, line.accountId(), "dealer split receipt", true);
        }
        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                currentDate(company),
                memo,
                dealer.getId(),
                null,
                Boolean.FALSE,
                lines
        );
        JournalEntryDto entryDto = createJournalEntry(payload);
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryDto.id());
        linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_DEALER_RECEIPT_SPLIT);
        existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            validateSplitReceiptIdempotency(idempotencyKey, dealer, memo, entry, lines);
            return entryDto;
        }
        List<PartnerSettlementAllocation> existingEntryAllocations = settlementAllocationRepository
                .findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, entry);
        if (!existingEntryAllocations.isEmpty()) {
            validateSplitReceiptIdempotency(idempotencyKey, dealer, memo, entry, lines);
            return entryDto;
        }
        LocalDate entryDate = entry.getEntryDate();
        List<Invoice> openInvoices = invoiceRepository.lockOpenInvoicesForSettlement(company, dealer);
        if (openInvoices.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "No open invoices available to allocate the receipt");
        }
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (Invoice invoice : openInvoices) {
            BigDecimal outstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
            if (outstanding.compareTo(BigDecimal.ZERO) > 0) {
                totalOutstanding = totalOutstanding.add(outstanding);
            }
        }
        if (totalOutstanding.add(ALLOCATION_TOLERANCE).compareTo(total) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Receipt amount exceeds total outstanding balance")
                    .withDetail("outstandingTotal", totalOutstanding)
                    .withDetail("receiptAmount", total);
        }
        BigDecimal remaining = total;
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<Invoice> touchedInvoices = new ArrayList<>();
        for (Invoice invoice : openInvoices) {
            BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
            if (currentOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal applied = remaining.min(currentOutstanding);
            if (applied.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            enforceSettlementCurrency(company, invoice);

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
            row.setMemo(request.memo());
            settlementRows.add(row);
            remaining = remaining.subtract(applied);
        }
        if (remaining.abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Receipt amount could not be fully allocated")
                    .withDetail("remaining", remaining);
        }
        try {
            settlementAllocationRepository.saveAll(settlementRows);
        } catch (DataIntegrityViolationException ex) {
            List<PartnerSettlementAllocation> concurrent = findAllocationsByIdempotencyKey(company, idempotencyKey);
            if (!concurrent.isEmpty()) {
                JournalEntry existingEntry = resolveReplayJournalEntryFromExistingAllocations(
                        company,
                        reference,
                        idempotencyKey,
                        concurrent);
                linkReferenceMapping(company, idempotencyKey, existingEntry, ENTITY_TYPE_DEALER_RECEIPT_SPLIT);
                validateSplitReceiptIdempotency(idempotencyKey, dealer, memo, existingEntry, lines);
                return toDto(existingEntry);
            }
            throw ex;
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
        }
        return entryDto;
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = companyEntityLookup.lockPayrollRun(company, request.payrollRunId());

        if (run.getStatus() == PayrollRun.PayrollStatus.PAID && run.getPaymentJournalEntryId() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll run already marked PAID but payment journal reference is missing")
                    .withDetail("payrollRunId", run.getId());
        }

        if (run.getStatus() != PayrollRun.PayrollStatus.POSTED && run.getStatus() != PayrollRun.PayrollStatus.PAID) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll must be posted to accounting before recording payment")
                    .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
        }
        if (run.getJournalEntryId() == null) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Payroll must be posted to accounting before recording payment")
                    .withDetail("requiredStatus", PayrollRun.PayrollStatus.POSTED.name());
        }

        Account cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "payroll payment");
        BigDecimal amount = requirePositive(request.amount(), "amount");

        Account salaryPayableAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "SALARY-PAYABLE")
                .orElseThrow(() -> new ApplicationException(ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                        "Salary payable account (SALARY-PAYABLE) is required to record payroll payments"));

        JournalEntry postingJournal = companyEntityLookup.requireJournalEntry(company, run.getJournalEntryId());
        BigDecimal payableAmount = BigDecimal.ZERO;
        if (postingJournal.getLines() != null) {
            for (var line : postingJournal.getLines()) {
                if (line.getAccount() == null || line.getAccount().getId() == null) {
                    continue;
                }
                if (!salaryPayableAccount.getId().equals(line.getAccount().getId())) {
                    continue;
                }
                BigDecimal credit = MoneyUtils.zeroIfNull(line.getCredit());
                BigDecimal debit = MoneyUtils.zeroIfNull(line.getDebit());
                payableAmount = payableAmount.add(credit.subtract(debit));
            }
        }
        if (payableAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                    "Posted payroll journal does not contain a payable amount for SALARY-PAYABLE")
                    .withDetail("postingJournalId", postingJournal.getId());
        }
        if (payableAmount.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Payroll payment amount does not match salary payable from the posted payroll journal")
                    .withDetail("expectedAmount", payableAmount)
                    .withDetail("requestAmount", amount);
        }

        if (run.getPaymentJournalEntryId() != null) {
            JournalEntry paid = companyEntityLookup.requireJournalEntry(company, run.getPaymentJournalEntryId());
            validatePayrollPaymentIdempotency(request, paid, salaryPayableAccount, cashAccount, amount);
            log.info("Payroll run {} already has payment journal {}, returning existing", run.getId(), paid.getReferenceNumber());
            return toDto(paid);
        }

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payroll payment for " + run.getRunDate();
        String reference = resolvePayrollPaymentReference(run, request, company);

        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                currentDate(company),
                memo,
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(salaryPayableAccount.getId(), memo, payableAmount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, payableAmount)
                )
        );
        JournalEntryDto entry = createJournalEntry(payload);
        JournalEntry paymentJournal = companyEntityLookup.requireJournalEntry(company, entry.id());

        run.setPaymentJournalEntryId(paymentJournal.getId());
        payrollRunRepository.save(run);
        return entry;
    }

    private String resolvePayrollPaymentReference(PayrollRun run, PayrollPaymentRequest request, Company company) {
        if (StringUtils.hasText(request.referenceNumber())) {
            return request.referenceNumber().trim();
        }
        String runToken = StringUtils.hasText(run.getRunNumber())
                ? run.getRunNumber().trim()
                : (run.getId() != null ? "LEGACY-" + run.getId() : null);
        if (!StringUtils.hasText(runToken)) {
            return referenceNumberService.payrollPaymentReference(company);
        }
        return "PAYROLL-PAY-" + runToken;
    }

    private void validatePayrollPaymentIdempotency(PayrollPaymentRequest request,
                                                   JournalEntry existing,
                                                   Account salaryPayableAccount,
                                                   Account cashAccount,
                                                   BigDecimal amount) {
        List<String> mismatches = new ArrayList<>();
        if (StringUtils.hasText(request.referenceNumber())
                && existing.getReferenceNumber() != null
                && !request.referenceNumber().trim().equalsIgnoreCase(existing.getReferenceNumber())) {
            mismatches.add("referenceNumber");
        }
        BigDecimal payableDebit = BigDecimal.ZERO;
        BigDecimal cashCredit = BigDecimal.ZERO;
        if (existing.getLines() != null) {
            for (JournalLine line : existing.getLines()) {
                if (line.getAccount() == null || line.getAccount().getId() == null) {
                    continue;
                }
                if (salaryPayableAccount.getId().equals(line.getAccount().getId())) {
                    payableDebit = payableDebit.add(MoneyUtils.zeroIfNull(line.getDebit()));
                }
                if (cashAccount.getId().equals(line.getAccount().getId())) {
                    cashCredit = cashCredit.add(MoneyUtils.zeroIfNull(line.getCredit()));
                }
            }
        }
        if (payableDebit.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            mismatches.add("salaryPayableDebit");
        }
        if (cashCredit.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            mismatches.add("cashCredit");
        }
        if (!mismatches.isEmpty()) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Payroll payment already recorded with different details")
                    .withDetail("payrollRunId", request.payrollRunId())
                    .withDetail("mismatches", mismatches);
        }
    }

    /**
     * Process payroll batch payment with proper accounting entries including liabilities.
     * 
     * Creates journal entries:
     * 1. Payroll Expense Entry:
     *    Dr. Payroll Expense (gross wages)
     *    Cr. Cash (net pay)
     *    Cr. Tax Payable (employee tax withholding)
     *    Cr. PF/Pension Payable (employee contribution)
     * 
     * 2. Employer Contribution Entry (if employer rates provided):
     *    Dr. Employer Tax Expense
     *    Cr. Tax Payable (employer portion)
     *    Dr. Employer PF Expense  
     *    Cr. PF Payable (employer portion)
     */
    @Transactional
    public PayrollBatchPaymentResponse processPayrollBatchPayment(PayrollBatchPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one payroll line is required");
        }
        
        // Required accounts
        Account cash = requireCashAccountForSettlement(company, request.cashAccountId(), "payroll batch payment");
        Account expense = requireAccount(company, request.expenseAccountId());
        
        // Optional liability accounts
        Account taxPayable = request.taxPayableAccountId() != null 
                ? requireAccount(company, request.taxPayableAccountId()) : null;
        Account pfPayable = request.pfPayableAccountId() != null 
                ? requireAccount(company, request.pfPayableAccountId()) : null;
        
        // Optional employer expense accounts
        Account employerTaxExpense = request.employerTaxExpenseAccountId() != null 
                ? requireAccount(company, request.employerTaxExpenseAccountId()) : null;
        Account employerPfExpense = request.employerPfExpenseAccountId() != null 
                ? requireAccount(company, request.employerPfExpenseAccountId()) : null;
        
        // Default rates
        BigDecimal defaultTaxRate = request.defaultTaxRate() != null ? request.defaultTaxRate() : BigDecimal.ZERO;
        BigDecimal defaultPfRate = request.defaultPfRate() != null ? request.defaultPfRate() : BigDecimal.ZERO;
        BigDecimal employerTaxRate = request.employerTaxRate() != null ? request.employerTaxRate() : BigDecimal.ZERO;
        BigDecimal employerPfRate = request.employerPfRate() != null ? request.employerPfRate() : BigDecimal.ZERO;

        List<PayrollBatchPaymentRequest.PayrollLine> lines = request.lines();
        List<PayrollBatchPaymentResponse.LineTotal> lineTotals = new ArrayList<>();
        
        // Totals
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalTaxWithholding = BigDecimal.ZERO;
        BigDecimal totalPfWithholding = BigDecimal.ZERO;
        BigDecimal totalAdvances = BigDecimal.ZERO;
        BigDecimal totalNetPay = BigDecimal.ZERO;
        
        for (PayrollBatchPaymentRequest.PayrollLine line : lines) {
            if (!StringUtils.hasText(line.name())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Line name is required");
            }
            int days = line.days() == null ? 0 : line.days();
            if (days <= 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Days must be greater than zero for " + line.name());
            }
            BigDecimal wage = line.dailyWage() == null ? BigDecimal.ZERO : line.dailyWage();
            if (wage.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Daily wage must be greater than zero for " + line.name());
            }
            BigDecimal advances = line.advances() == null ? BigDecimal.ZERO : line.advances();
            if (advances.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Advances cannot be negative for " + line.name());
            }
            
            // Calculate gross pay
            BigDecimal grossPay = wage.multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);
            
            // Calculate tax withholding (use line-specific if provided, else use rate)
            BigDecimal taxWithholding = line.taxWithholding() != null 
                    ? line.taxWithholding() 
                    : grossPay.multiply(defaultTaxRate).setScale(2, RoundingMode.HALF_UP);
            
            // Calculate PF withholding (use line-specific if provided, else use rate)
            BigDecimal pfWithholding = line.pfWithholding() != null 
                    ? line.pfWithholding() 
                    : grossPay.multiply(defaultPfRate).setScale(2, RoundingMode.HALF_UP);
            
            // Calculate net pay: Gross - Tax - PF - Advances
            BigDecimal netPay = grossPay
                    .subtract(taxWithholding)
                    .subtract(pfWithholding)
                    .subtract(advances)
                    .setScale(2, RoundingMode.HALF_UP);
            
            if (netPay.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, 
                        "Net pay cannot be negative for " + line.name() + ". Deductions exceed gross pay.");
            }
            
            // Accumulate totals
            totalGross = totalGross.add(grossPay);
            totalTaxWithholding = totalTaxWithholding.add(taxWithholding);
            totalPfWithholding = totalPfWithholding.add(pfWithholding);
            totalAdvances = totalAdvances.add(advances);
            totalNetPay = totalNetPay.add(netPay);
            
            lineTotals.add(new PayrollBatchPaymentResponse.LineTotal(
                    line.name(),
                    days,
                    wage.setScale(2, RoundingMode.HALF_UP),
                    grossPay,
                    taxWithholding,
                    pfWithholding,
                    advances,
                    netPay,
                    line.notes()
            ));
        }
        
        if (totalGross.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Total gross payroll amount must be greater than zero");
        }

        // Calculate employer contributions
        BigDecimal employerTaxAmount = totalGross.multiply(employerTaxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal employerPfAmount = totalGross.multiply(employerPfRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalEmployerCost = totalGross.add(employerTaxAmount).add(employerPfAmount);

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payroll batch for " + request.runDate();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.payrollPaymentReference(company);

        // Create PayrollRun
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunType(PayrollRun.RunType.MONTHLY);
        run.setPeriodStart(request.runDate());
        run.setPeriodEnd(request.runDate());
        run.setRunDate(request.runDate());
        run.setRunNumber(reference);
        run.setNotes(memo);
        run.setTotalAmount(totalGross); // Store gross amount
        run.setStatus("DRAFT");
        run.setProcessedBy(resolveCurrentUsername());
        PayrollRun savedRun = payrollRunRepository.save(run);

        // Create PayrollRunLines
        List<PayrollRunLine> persistedLines = new ArrayList<>();
        for (PayrollBatchPaymentResponse.LineTotal line : lineTotals) {
            PayrollRunLine entity = new PayrollRunLine();
            entity.setPayrollRun(savedRun);
            entity.setName(line.name());
            entity.setDaysWorked(line.days());
            entity.setDailyWage(line.dailyWage());
            entity.setAdvances(line.advances());
            entity.setLineTotal(line.netPay()); // Store net pay
            entity.setNotes(line.notes());
            persistedLines.add(entity);
        }
        payrollRunLineRepository.saveAll(persistedLines);

        // Build journal entry lines for main payroll entry
        // The journal must balance, so we calculate total credits first:
        // - Cash (net pay) is always credited
        // - Tax Payable (only if account provided AND withholding > 0)
        // - PF Payable (only if account provided AND withholding > 0)
        // Expense debit = sum of all credits (ensures balance)
        
        List<JournalEntryRequest.JournalLineRequest> payrollLines = new ArrayList<>();
        BigDecimal totalCredits = BigDecimal.ZERO;
        
        // Credit: Cash (net pay)
        if (totalNetPay.compareTo(BigDecimal.ZERO) > 0) {
            payrollLines.add(new JournalEntryRequest.JournalLineRequest(
                    cash.getId(), "Net payroll disbursement", BigDecimal.ZERO, totalNetPay));
            totalCredits = totalCredits.add(totalNetPay);
        }
        
        // Credit: Tax Payable (employee tax withholding) - only if account provided
        if (taxPayable != null && totalTaxWithholding.compareTo(BigDecimal.ZERO) > 0) {
            payrollLines.add(new JournalEntryRequest.JournalLineRequest(
                    taxPayable.getId(), "Employee tax withholding (TDS)", BigDecimal.ZERO, totalTaxWithholding));
            totalCredits = totalCredits.add(totalTaxWithholding);
        }
        
        // Credit: PF Payable (employee PF contribution) - only if account provided
        if (pfPayable != null && totalPfWithholding.compareTo(BigDecimal.ZERO) > 0) {
            payrollLines.add(new JournalEntryRequest.JournalLineRequest(
                    pfPayable.getId(), "Employee PF contribution", BigDecimal.ZERO, totalPfWithholding));
            totalCredits = totalCredits.add(totalPfWithholding);
        }
        
        // Debit: Payroll Expense = total credits (ensures journal balances)
        // Note: When no tax/PF accounts provided, expense = net pay
        // When all accounts provided, expense = gross (tax + PF + net)
        payrollLines.add(0, new JournalEntryRequest.JournalLineRequest(
                expense.getId(), "Payroll expense", totalCredits, BigDecimal.ZERO));

        // Create main payroll journal entry
        JournalEntryDto payrollJe = createJournalEntry(new JournalEntryRequest(
                reference,
                request.runDate(),
                memo,
                null,
                null,
                Boolean.FALSE,
                payrollLines
        ));
        JournalEntry payrollEntry = companyEntityLookup.requireJournalEntry(company, payrollJe.id());
        
        // Create employer contribution journal entry (if rates provided)
        Long employerContribJournalId = null;
        if ((employerTaxAmount.compareTo(BigDecimal.ZERO) > 0 && employerTaxExpense != null && taxPayable != null) ||
            (employerPfAmount.compareTo(BigDecimal.ZERO) > 0 && employerPfExpense != null && pfPayable != null)) {
            
            List<JournalEntryRequest.JournalLineRequest> employerLines = new ArrayList<>();
            
            // Dr. Employer Tax Expense, Cr. Tax Payable
            if (employerTaxAmount.compareTo(BigDecimal.ZERO) > 0 && employerTaxExpense != null && taxPayable != null) {
                employerLines.add(new JournalEntryRequest.JournalLineRequest(
                        employerTaxExpense.getId(), "Employer tax contribution", employerTaxAmount, BigDecimal.ZERO));
                employerLines.add(new JournalEntryRequest.JournalLineRequest(
                        taxPayable.getId(), "Employer tax payable", BigDecimal.ZERO, employerTaxAmount));
            }
            
            // Dr. Employer PF Expense, Cr. PF Payable
            if (employerPfAmount.compareTo(BigDecimal.ZERO) > 0 && employerPfExpense != null && pfPayable != null) {
                employerLines.add(new JournalEntryRequest.JournalLineRequest(
                        employerPfExpense.getId(), "Employer PF contribution", employerPfAmount, BigDecimal.ZERO));
                employerLines.add(new JournalEntryRequest.JournalLineRequest(
                        pfPayable.getId(), "Employer PF payable", BigDecimal.ZERO, employerPfAmount));
            }
            
            if (!employerLines.isEmpty()) {
                String employerRef = reference + "-EMP";
                JournalEntryDto employerJe = createJournalEntry(new JournalEntryRequest(
                        employerRef,
                        request.runDate(),
                        "Employer contributions for " + memo,
                        null,
                        null,
                        Boolean.FALSE,
                        employerLines
                ));
                employerContribJournalId = employerJe.id();
            }
        }
        
        // Update PayrollRun status
        savedRun.setStatus("PAID");
        savedRun.setJournalEntryId(payrollEntry.getId());
        savedRun.setJournalEntry(payrollEntry);
        payrollRunRepository.save(savedRun);

        return new PayrollBatchPaymentResponse(
                savedRun.getId(),
                savedRun.getRunDate(),
                totalGross.setScale(2, RoundingMode.HALF_UP),
                totalTaxWithholding.setScale(2, RoundingMode.HALF_UP),
                totalPfWithholding.setScale(2, RoundingMode.HALF_UP),
                totalAdvances.setScale(2, RoundingMode.HALF_UP),
                totalNetPay.setScale(2, RoundingMode.HALF_UP),
                employerTaxAmount.setScale(2, RoundingMode.HALF_UP),
                employerPfAmount.setScale(2, RoundingMode.HALF_UP),
                totalEmployerCost.setScale(2, RoundingMode.HALF_UP),
                payrollEntry.getId(),
                employerContribJournalId,
                lineTotals
        );
    }

    @Transactional
    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = supplierRepository.lockByCompanyAndId(company, request.supplierId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
        Account payableAccount = requireSupplierPayable(supplier);
        Account cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "supplier payment", false);
        BigDecimal amount = requirePositive(request.amount(), "amount");
        List<SettlementAllocationRequest> allocations = request.allocations();
        validatePaymentAllocations(allocations, amount, "supplier payment", false);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payment to supplier " + supplier.getName();
        String idempotencyKey = resolveReceiptIdempotencyKey(request.idempotencyKey(), request.referenceNumber(), "supplier payment");
        String reference = resolveSupplierPaymentReference(company, supplier, request.referenceNumber(), idempotencyKey);
        IdempotencyReservation reservation = reserveReferenceMapping(company, idempotencyKey, reference, ENTITY_TYPE_SUPPLIER_PAYMENT);

        if (!reservation.leader()) {
            JournalEntry existingEntry = awaitJournalEntry(company, reference, idempotencyKey);
            List<PartnerSettlementAllocation> existingAllocations = awaitAllocations(company, idempotencyKey);
            if (!existingAllocations.isEmpty()) {
                JournalEntry entry = resolveReplayJournalEntry(idempotencyKey, existingEntry, existingAllocations);
                linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
                validateSupplierPaymentIdempotency(idempotencyKey, supplier, cashAccount, payableAccount, amount, memo,
                        entry, existingAllocations, allocations);
                return toDto(entry);
            }
            throw missingReservedPartnerAllocation(
                    "Supplier payment",
                    idempotencyKey,
                    PartnerType.SUPPLIER,
                    supplier.getId());
        }

        List<PartnerSettlementAllocation> existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            JournalEntry entry = resolveReplayJournalEntryFromExistingAllocations(
                    company,
                    reference,
                    idempotencyKey,
                    existingAllocations);
            linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
            validateSupplierPaymentIdempotency(idempotencyKey, supplier, cashAccount, payableAccount, amount, memo,
                    entry, existingAllocations, allocations);
            return toDto(entry);
        }

        cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "supplier payment", true);
        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                currentDate(company),
                memo,
                null,
                supplier.getId(),
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(payableAccount.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, amount)
                )
        );
        JournalEntryDto entryDto = createJournalEntry(payload);
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryDto.id());
        linkReferenceMapping(company, idempotencyKey, entry, ENTITY_TYPE_SUPPLIER_PAYMENT);
        existingAllocations = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existingAllocations.isEmpty()) {
            validateSupplierPaymentIdempotency(idempotencyKey, supplier, cashAccount, payableAccount, amount, memo,
                    entry, existingAllocations, allocations);
            return entryDto;
        }

        LocalDate entryDate = entry.getEntryDate();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();
        Map<Long, BigDecimal> remainingByPurchase = new HashMap<>();
        Map<Long, RawMaterialPurchase> purchaseById = new HashMap<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier payments cannot allocate to invoices");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            RawMaterialPurchase purchase = null;
            if (allocation.purchaseId() != null) {
                purchase = rawMaterialPurchaseRepository.lockByCompanyAndId(company, allocation.purchaseId())
                        .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material purchase not found"));
                if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
                }
                BigDecimal currentOutstanding = remainingByPurchase.getOrDefault(
                        purchase.getId(),
                        MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
                if (applied.compareTo(currentOutstanding) > 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Allocation exceeds purchase outstanding amount")
                            .withDetail("purchaseId", purchase.getId())
                            .withDetail("outstanding", currentOutstanding)
                            .withDetail("applied", applied);
                }
                remainingByPurchase.put(purchase.getId(), currentOutstanding.subtract(applied).max(BigDecimal.ZERO));
                purchaseById.put(purchase.getId(), purchase);
            }

            PartnerSettlementAllocation row = new PartnerSettlementAllocation();
            row.setCompany(company);
            row.setPartnerType(PartnerType.SUPPLIER);
            row.setSupplier(supplier);
            row.setPurchase(purchase);
            row.setJournalEntry(entry);
            row.setSettlementDate(entryDate);
            row.setAllocationAmount(applied);
            row.setDiscountAmount(BigDecimal.ZERO);
            row.setWriteOffAmount(BigDecimal.ZERO);
            row.setFxDifferenceAmount(BigDecimal.ZERO);
            row.setIdempotencyKey(idempotencyKey);
            row.setMemo(allocation.memo());
            settlementRows.add(row);
        }
        try {
            settlementAllocationRepository.saveAll(settlementRows);
        } catch (DataIntegrityViolationException ex) {
            List<PartnerSettlementAllocation> concurrent = findAllocationsByIdempotencyKey(company, idempotencyKey);
            if (!concurrent.isEmpty()) {
                JournalEntry existingEntry = resolveReplayJournalEntryFromExistingAllocations(
                        company,
                        reference,
                        idempotencyKey,
                        concurrent);
                linkReferenceMapping(company, idempotencyKey, existingEntry, ENTITY_TYPE_SUPPLIER_PAYMENT);
                validateSupplierPaymentIdempotency(idempotencyKey, supplier, cashAccount, payableAccount, amount, memo,
                        existingEntry, concurrent, allocations);
                return toDto(existingEntry);
            }
            throw ex;
        }
        for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
            RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
            if (purchase == null) {
                continue;
            }
            purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
            updatePurchaseStatus(purchase);
            touchedPurchases.add(purchase);
        }
        if (!touchedPurchases.isEmpty()) {
            rawMaterialPurchaseRepository.saveAll(touchedPurchases);
        }
        return entryDto;
    }

    @Transactional
    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String trimmedIdempotencyKey = resolveDealerSettlementIdempotencyKey(company, request);
        if (!StringUtils.hasText(trimmedIdempotencyKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for dealer settlements");
        }
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
        Account receivableAccount = requireDealerReceivable(dealer);
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        boolean replayCandidate = hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
        if (!replayCandidate) {
            validateDealerSettlementAllocations(allocations);
        }
        SettlementTotals totals = computeSettlementTotals(allocations);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Settlement for dealer " + dealer.getName();
        String reference = resolveDealerSettlementReference(company, dealer, request, trimmedIdempotencyKey);
        IdempotencyReservation reservation = reserveReferenceMapping(company, trimmedIdempotencyKey, reference, ENTITY_TYPE_DEALER_SETTLEMENT);
        if (reservation.leader()
                && !StringUtils.hasText(request.referenceNumber())
                && isReservedReference(reference)) {
            reference = referenceNumberService.dealerReceiptReference(company, dealer);
        }
        SettlementLineDraft lineDraft = buildDealerSettlementLines(
                company,
                request,
                receivableAccount,
                totals,
                memo,
                false);

        if (!reservation.leader()) {
            JournalEntry existingEntry = awaitJournalEntry(company, reference, trimmedIdempotencyKey);
            List<PartnerSettlementAllocation> existingAllocations = awaitAllocations(company, trimmedIdempotencyKey);
            if (!existingAllocations.isEmpty()) {
                JournalEntry entry = resolveReplayJournalEntry(trimmedIdempotencyKey, existingEntry, existingAllocations);
                linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_DEALER_SETTLEMENT);
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId(), existingAllocations, allocations);
                validatePartnerSettlementJournalLines(
                        trimmedIdempotencyKey,
                        PartnerType.DEALER,
                        dealer.getId(),
                        memo,
                        entry,
                        lineDraft.lines());
                return buildDealerSettlementResponse(existingAllocations);
            }
            throw missingReservedPartnerAllocation(
                    "Dealer settlement",
                    trimmedIdempotencyKey,
                    PartnerType.DEALER,
                    dealer.getId());
        }

        List<PartnerSettlementAllocation> existingAllocations = findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
        if (!existingAllocations.isEmpty()) {
            JournalEntry entry = resolveReplayJournalEntryFromExistingAllocations(
                    company,
                    reference,
                    trimmedIdempotencyKey,
                    existingAllocations);
            linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_DEALER_SETTLEMENT);
            validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId(), existingAllocations, allocations);
            validatePartnerSettlementJournalLines(
                    trimmedIdempotencyKey,
                    PartnerType.DEALER,
                    dealer.getId(),
                    memo,
                    entry,
                    lineDraft.lines());
            return buildDealerSettlementResponse(existingAllocations);
        }

        lineDraft = buildDealerSettlementLines(
                company,
                request,
                receivableAccount,
                totals,
                memo,
                true);
        LocalDate entryDate = request.settlementDate() != null ? request.settlementDate() : currentDate(company);

        BigDecimal totalApplied = totals.totalApplied();
        BigDecimal totalDiscount = totals.totalDiscount();
        BigDecimal totalWriteOff = totals.totalWriteOff();
        BigDecimal totalFxGain = totals.totalFxGain();
        BigDecimal totalFxLoss = totals.totalFxLoss();
        BigDecimal cashAmount = lineDraft.cashAmount();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<Invoice> touchedInvoices = new ArrayList<>();
        Map<Long, BigDecimal> remainingByInvoice = new HashMap<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Invoice allocation is required for dealer settlements");
            }
            if (allocation.purchaseId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dealer settlements cannot allocate to purchases");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());

            Invoice invoice = invoiceRepository.lockByCompanyAndId(company, allocation.invoiceId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
            if (invoice.getDealer() == null || !invoice.getDealer().getId().equals(dealer.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
            }
            enforceSettlementCurrency(company, invoice);

            // Open-item tracking: applied amount represents gross invoice reduction.
            BigDecimal cleared = applied;
            BigDecimal currentOutstanding = remainingByInvoice.getOrDefault(
                    invoice.getId(),
                    MoneyUtils.zeroIfNull(invoice.getOutstandingAmount()));
            if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Settlement allocation exceeds invoice outstanding amount")
                        .withDetail("invoiceId", invoice.getId())
                        .withDetail("outstandingAmount", currentOutstanding)
                        .withDetail("appliedAmount", cleared);
            }
            remainingByInvoice.put(invoice.getId(), currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));

            PartnerSettlementAllocation row = new PartnerSettlementAllocation();
            row.setCompany(company);
            row.setPartnerType(PartnerType.DEALER);
            row.setDealer(dealer);
            row.setInvoice(invoice);
            row.setSettlementDate(entryDate);
            row.setAllocationAmount(applied);
            row.setDiscountAmount(discount);
            row.setWriteOffAmount(writeOff);
            row.setFxDifferenceAmount(fxAdjustment);
            row.setIdempotencyKey(trimmedIdempotencyKey);
            if (invoice.getCurrency() != null) {
                row.setCurrency(invoice.getCurrency());
            }
            row.setMemo(allocation.memo());
            settlementRows.add(row);
        }

        JournalEntryDto journalEntryDto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                dealer.getId(),
                null,
                request.adminOverride(),
                lineDraft.lines()
        ));

        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
        linkReferenceMapping(company, trimmedIdempotencyKey, journalEntry, ENTITY_TYPE_DEALER_SETTLEMENT);
        for (PartnerSettlementAllocation allocation : settlementRows) {
            allocation.setJournalEntry(journalEntry);
        }
        try {
            settlementAllocationRepository.saveAll(settlementRows);
        } catch (DataIntegrityViolationException ex) {
            List<PartnerSettlementAllocation> concurrent = findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
            if (!concurrent.isEmpty()) {
                JournalEntry existingEntry = resolveReplayJournalEntryFromExistingAllocations(
                        company,
                        reference,
                        trimmedIdempotencyKey,
                        concurrent);
                linkReferenceMapping(company, trimmedIdempotencyKey, existingEntry, ENTITY_TYPE_DEALER_SETTLEMENT);
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId(), concurrent, allocations);
                validatePartnerSettlementJournalLines(
                        trimmedIdempotencyKey,
                        PartnerType.DEALER,
                        dealer.getId(),
                        memo,
                        existingEntry,
                        lineDraft.lines());
                return buildDealerSettlementResponse(concurrent);
            }
            throw ex;
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
        }

        List<PartnerSettlementResponse.Allocation> allocationSummaries = toSettlementAllocationSummaries(settlementRows);
        logSettlementAuditSuccess(
                PartnerType.DEALER,
                dealer.getId(),
                journalEntryDto,
                entryDate,
                trimmedIdempotencyKey,
                settlementRows.size(),
                totalApplied,
                cashAmount,
                totalDiscount,
                totalWriteOff,
                totalFxGain,
                totalFxLoss);

        return new PartnerSettlementResponse(
                journalEntryDto,
                totalApplied,
                cashAmount,
                totalDiscount,
                totalWriteOff,
                totalFxGain,
                totalFxLoss,
                allocationSummaries
        );
    }

    @Transactional
    public PartnerSettlementResponse settleSupplierInvoices(SupplierSettlementRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String trimmedIdempotencyKey = resolveReceiptIdempotencyKey(request.idempotencyKey(), request.referenceNumber(), "supplier settlement");
        Supplier supplier = supplierRepository.lockByCompanyAndId(company, request.supplierId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
        Account payableAccount = requireSupplierPayable(supplier);
        Account cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "supplier settlement", false);
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        boolean replayCandidate = hasExistingIdempotencyMapping(company, trimmedIdempotencyKey)
                || hasExistingSettlementAllocations(company, trimmedIdempotencyKey);
        if (!replayCandidate) {
            validateSupplierSettlementAllocations(allocations);
        }
        SettlementTotals totals = computeSettlementTotals(allocations);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Settlement to supplier " + supplier.getName();
        String reference = resolveSupplierSettlementReference(company, supplier, request, trimmedIdempotencyKey);
        IdempotencyReservation reservation = reserveReferenceMapping(company, trimmedIdempotencyKey, reference, ENTITY_TYPE_SUPPLIER_SETTLEMENT);

        if (!reservation.leader()) {
            JournalEntry existingEntry = awaitJournalEntry(company, reference, trimmedIdempotencyKey);
            List<PartnerSettlementAllocation> existingAllocations = awaitAllocations(company, trimmedIdempotencyKey);
            if (!existingAllocations.isEmpty()) {
                SettlementLineDraft replayLineDraft =
                        buildSupplierSettlementLines(company, request, payableAccount, cashAccount, totals, memo);
                JournalEntry entry = resolveReplayJournalEntry(trimmedIdempotencyKey, existingEntry, existingAllocations);
                linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId(), existingAllocations, allocations);
                validatePartnerSettlementJournalLines(
                        trimmedIdempotencyKey,
                        PartnerType.SUPPLIER,
                        supplier.getId(),
                        memo,
                        entry,
                        replayLineDraft.lines());
                return buildSupplierSettlementResponse(existingAllocations);
            }
            throw missingReservedPartnerAllocation(
                    "Supplier settlement",
                    trimmedIdempotencyKey,
                    PartnerType.SUPPLIER,
                    supplier.getId());
        }

        List<PartnerSettlementAllocation> existingAllocations = findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
        if (!existingAllocations.isEmpty()) {
            JournalEntry entry = resolveReplayJournalEntryFromExistingAllocations(
                    company,
                    reference,
                    trimmedIdempotencyKey,
                    existingAllocations);
            SettlementLineDraft replayLineDraft =
                    buildSupplierSettlementLines(company, request, payableAccount, cashAccount, totals, memo);
            linkReferenceMapping(company, trimmedIdempotencyKey, entry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
            validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId(), existingAllocations, allocations);
            validatePartnerSettlementJournalLines(
                    trimmedIdempotencyKey,
                    PartnerType.SUPPLIER,
                    supplier.getId(),
                    memo,
                    entry,
                    replayLineDraft.lines());
            return buildSupplierSettlementResponse(existingAllocations);
        }

        cashAccount = requireCashAccountForSettlement(company, request.cashAccountId(), "supplier settlement", true);
        SettlementLineDraft lineDraft = buildSupplierSettlementLines(company, request, payableAccount, cashAccount, totals, memo);

        LocalDate entryDate = request.settlementDate() != null ? request.settlementDate() : currentDate(company);

        BigDecimal totalApplied = totals.totalApplied();
        BigDecimal totalDiscount = totals.totalDiscount();
        BigDecimal totalWriteOff = totals.totalWriteOff();
        BigDecimal totalFxGain = totals.totalFxGain();
        BigDecimal totalFxLoss = totals.totalFxLoss();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();
        Map<Long, BigDecimal> remainingByPurchase = new HashMap<>();
        Map<Long, RawMaterialPurchase> purchaseById = new HashMap<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier settlements cannot allocate to invoices");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());

            if (allocation.purchaseId() == null
                    && (discount.compareTo(BigDecimal.ZERO) > 0
                    || writeOff.compareTo(BigDecimal.ZERO) > 0
                    || fxAdjustment.compareTo(BigDecimal.ZERO) != 0)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "On-account supplier settlement allocations cannot include discount/write-off/FX adjustments");
            }

            RawMaterialPurchase purchase = null;
            if (allocation.purchaseId() != null) {
                purchase = rawMaterialPurchaseRepository.lockByCompanyAndId(company, allocation.purchaseId())
                        .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material purchase not found"));
                if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
                }
                // Open-item: applied amount represents gross purchase reduction.
                BigDecimal cleared = applied;
                BigDecimal currentOutstanding = remainingByPurchase.getOrDefault(
                        purchase.getId(),
                        MoneyUtils.zeroIfNull(purchase.getOutstandingAmount()));
                if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Settlement allocation exceeds purchase outstanding amount")
                            .withDetail("purchaseId", purchase.getId())
                            .withDetail("outstandingAmount", currentOutstanding)
                            .withDetail("appliedAmount", cleared);
                }
                remainingByPurchase.put(purchase.getId(), currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
                purchaseById.put(purchase.getId(), purchase);
            }

            PartnerSettlementAllocation row = new PartnerSettlementAllocation();
            row.setCompany(company);
            row.setPartnerType(PartnerType.SUPPLIER);
            row.setSupplier(supplier);
            row.setPurchase(purchase);
            row.setSettlementDate(entryDate);
            row.setAllocationAmount(applied);
            row.setDiscountAmount(discount);
            row.setWriteOffAmount(writeOff);
            row.setFxDifferenceAmount(fxAdjustment);
            row.setIdempotencyKey(trimmedIdempotencyKey);
            row.setMemo(allocation.memo());
            settlementRows.add(row);
        }

        JournalEntryDto journalEntryDto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                supplier.getId(),
                request.adminOverride(),
                lineDraft.lines()
        ));

        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
        linkReferenceMapping(company, trimmedIdempotencyKey, journalEntry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
        for (PartnerSettlementAllocation allocation : settlementRows) {
            allocation.setJournalEntry(journalEntry);
        }
        try {
            settlementAllocationRepository.saveAll(settlementRows);
        } catch (DataIntegrityViolationException ex) {
            List<PartnerSettlementAllocation> concurrent = findAllocationsByIdempotencyKey(company, trimmedIdempotencyKey);
            if (!concurrent.isEmpty()) {
                JournalEntry existingEntry = resolveReplayJournalEntryFromExistingAllocations(
                        company,
                        reference,
                        trimmedIdempotencyKey,
                        concurrent);
                linkReferenceMapping(company, trimmedIdempotencyKey, existingEntry, ENTITY_TYPE_SUPPLIER_SETTLEMENT);
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId(), concurrent, allocations);
                validatePartnerSettlementJournalLines(
                        trimmedIdempotencyKey,
                        PartnerType.SUPPLIER,
                        supplier.getId(),
                        memo,
                        existingEntry,
                        lineDraft.lines());
                return buildSupplierSettlementResponse(concurrent);
            }
            throw ex;
        }
        for (Map.Entry<Long, BigDecimal> entryState : remainingByPurchase.entrySet()) {
            RawMaterialPurchase purchase = purchaseById.get(entryState.getKey());
            if (purchase == null) {
                continue;
            }
            purchase.setOutstandingAmount(entryState.getValue().max(BigDecimal.ZERO));
            updatePurchaseStatus(purchase);
            touchedPurchases.add(purchase);
        }
        if (!touchedPurchases.isEmpty()) {
            rawMaterialPurchaseRepository.saveAll(touchedPurchases);
        }

        List<PartnerSettlementResponse.Allocation> allocationSummaries = toSettlementAllocationSummaries(settlementRows);
        logSettlementAuditSuccess(
                PartnerType.SUPPLIER,
                supplier.getId(),
                journalEntryDto,
                entryDate,
                trimmedIdempotencyKey,
                settlementRows.size(),
                totalApplied,
                lineDraft.cashAmount(),
                totalDiscount,
                totalWriteOff,
                totalFxGain,
                totalFxLoss);

        return new PartnerSettlementResponse(
                journalEntryDto,
                totalApplied,
                lineDraft.cashAmount(),
                totalDiscount,
                totalWriteOff,
                totalFxGain,
                totalFxLoss,
                allocationSummaries
        );
    }

    /**
     * Calculates the net ledger posting amount for a specific account within a journal entry.
     * <p>
     * This method iterates through all journal lines and sums amounts for lines that reference
     * the given account. In practice, each journal entry should have exactly ONE line for a
     * dealer's AR account or supplier's AP account, but this method correctly handles edge cases.
     * <p>
     * Example for sales journal:
     * <pre>
     * Dr AR 1000, Cr Revenue 1000
     * → Returns LedgerPosting(debit=1000, credit=0) for AR account
     * </pre>
     * <p>
     * Example for sales return:
     * <pre>
     * Dr Revenue 1000, Cr AR 1000
     * → Returns LedgerPosting(debit=0, credit=1000) for AR account
     * </pre>
     *
     * @param entry the journal entry to analyze
     * @param ledgerAccount the account to match (e.g., dealer's AR account or supplier's AP account)
     * @param debitIncreasesBalance true for asset accounts (AR), false for liability accounts (AP)
     * @return the net debit/credit effect on the ledger, or zero amounts if no matches found
     */
    private AccountDto toDto(Account account) {
        return new AccountDto(account.getId(), account.getPublicId(), account.getCode(), account.getName(), account.getType(), account.getBalance());
    }

    private JournalEntryDto toDto(JournalEntry entry) {
        List<JournalLineDto> lines = entry.getLines().stream()
                .map(line -> new JournalLineDto(
                        line.getAccount().getId(),
                        line.getAccount().getCode(),
                        line.getDescription(),
                        line.getDebit(),
                        line.getCredit()))
                .toList();
        Dealer dealer = entry.getDealer();
        Supplier supplier = entry.getSupplier();
        String dealerName = dealer != null ? dealer.getName() : null;
        String supplierName = supplier != null ? supplier.getName() : null;
        AccountingPeriod period = entry.getAccountingPeriod();
        Long periodId = period != null ? period.getId() : null;
        String periodLabel = period != null ? period.getLabel() : null;
        String periodStatus = period != null ? period.getStatus().name() : null;
        JournalEntry reversalOf = entry.getReversalOf();
        JournalEntry reversalEntry = entry.getReversalEntry();
        String correctionType = entry.getCorrectionType() != null ? entry.getCorrectionType().name() : null;
        return new JournalEntryDto(entry.getId(), entry.getPublicId(), entry.getReferenceNumber(),
                entry.getEntryDate(), entry.getMemo(), entry.getStatus(),
                dealer != null ? dealer.getId() : null, dealerName,
                supplier != null ? supplier.getId() : null, supplierName,
                periodId, periodLabel, periodStatus,
                reversalOf != null ? reversalOf.getId() : null,
                reversalEntry != null ? reversalEntry.getId() : null,
                correctionType,
                entry.getCorrectionReason(),
                entry.getVoidReason(),
                lines,
                entry.getCreatedAt(),
                entry.getUpdatedAt(),
                entry.getPostedAt(),
                entry.getCreatedBy(),
                entry.getPostedBy(),
                entry.getLastModifiedBy());
    }

    private Dealer requireDealer(Company company, Long dealerId) {
        return companyEntityLookup.requireDealer(company, dealerId);
    }

    private Supplier requireSupplier(Company company, Long supplierId) {
        return companyEntityLookup.requireSupplier(company, supplierId);
    }

    private Account requireAccount(Company company, Long accountId) {
        return companyEntityLookup.requireAccount(company, accountId);
    }

    private Account requireCashAccountForSettlement(Company company, Long accountId, String operation) {
        return requireCashAccountForSettlement(company, accountId, operation, true);
    }

    private Account requireCashAccountForSettlement(Company company, Long accountId, String operation, boolean requireActive) {
        Account account = requireAccount(company, accountId);
        if (requireActive && !account.isActive()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cash/bank account for " + operation + " must be active")
                    .withDetail("operation", operation)
                    .withDetail("accountId", account.getId())
                    .withDetail("accountCode", account.getCode())
                    .withDetail("active", false);
        }
        if (account.getType() != null && account.getType() != AccountType.ASSET) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cash/bank account for " + operation + " must be an ASSET account")
                    .withDetail("operation", operation)
                    .withDetail("accountId", account.getId())
                    .withDetail("accountCode", account.getCode())
                    .withDetail("accountType", account.getType().name());
        }
        if (isReceivableAccount(account) || isPayableAccount(account)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Cash/bank account for " + operation + " cannot be AR/AP control account")
                    .withDetail("operation", operation)
                    .withDetail("accountId", account.getId())
                    .withDetail("accountCode", account.getCode())
                    .withDetail("accountName", account.getName());
        }
        return account;
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Value for " + field + " must be greater than zero");
        }
        return value;
    }

    private BigDecimal normalizeNonNegative(BigDecimal value, String field) {
        BigDecimal normalized = MoneyUtils.zeroIfNull(value);
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Value for " + field + " cannot be negative");
        }
        return normalized;
    }

    private void validatePaymentAllocations(List<SettlementAllocationRequest> allocations,
                                            BigDecimal amount,
                                            String label,
                                            boolean dealer) {
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Allocations are required for " + label + "; use settlement endpoints or include allocations");
        }
        BigDecimal totalApplied = BigDecimal.ZERO;
        for (SettlementAllocationRequest allocation : allocations) {
            if (dealer) {
                if (allocation.invoiceId() == null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Invoice allocation is required for dealer receipts");
                }
                if (allocation.purchaseId() != null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Dealer receipts cannot allocate to purchases");
                }
            } else {
                if (allocation.invoiceId() != null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Supplier payments cannot allocate to invoices");
                }
                if (allocation.purchaseId() == null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Purchase allocation is required for supplier payments; use /api/v1/accounting/settlements/suppliers for on-account credits");
                }
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
            if (discount.compareTo(BigDecimal.ZERO) > 0
                    || writeOff.compareTo(BigDecimal.ZERO) > 0
                    || fxAdjustment.compareTo(BigDecimal.ZERO) != 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Discount/write-off/FX adjustments are not supported for " + label + " allocations");
            }
            totalApplied = totalApplied.add(applied);
        }
        if (totalApplied.subtract(amount).abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Allocation total must equal payment amount")
                    .withDetail("allocationTotal", totalApplied)
                    .withDetail("paymentAmount", amount);
        }
    }

    @Transactional
    public JournalEntryDto createManualJournalEntry(JournalEntryRequest request, String idempotencyKey) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Journal entry request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        String rawKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        String key = StringUtils.hasText(rawKey) ? normalizeIdempotencyMappingKey(rawKey) : null;
        if (StringUtils.hasText(rawKey)) {
            if (journalEntryRepository.findByCompanyAndReferenceNumber(company, rawKey).isPresent()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Idempotency key conflicts with an existing system reference")
                        .withDetail("referenceNumber", rawKey);
            }
            Optional<JournalEntry> existing = journalReferenceResolver.findExistingEntry(company, rawKey);
            if (existing.isPresent()) {
                return toDto(existing.get());
            }
            // Reserve the idempotency key FIRST (reserve-first pattern) to make manual journal creation
            // concurrency-safe. The INSERT ... ON CONFLICT DO NOTHING is atomic and avoids a
            // check-then-insert race while ensuring no journal is created before the key is reserved.
            int reserved = journalReferenceMappingRepository.reserveManualReference(
                    company.getId(),
                    key,
                    reservedManualReference(key),
                    "JOURNAL_ENTRY",
                    CompanyTime.now(company)
            );
            if (reserved == 0) {
                JournalEntry already = awaitJournalEntry(company, rawKey, key);
                if (already != null) {
                    return toDto(already);
                }
                throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                        "Manual journal idempotency key already reserved but entry not found")
                        .withDetail("referenceNumber", rawKey);
            }
        }
        JournalEntryDto created = createJournalEntry(new JournalEntryRequest(
                null,
                request.entryDate(),
                request.memo(),
                request.dealerId(),
                request.supplierId(),
                request.adminOverride(),
                request.lines(),
                request.currency(),
                request.fxRate()
        ));
        if (StringUtils.hasText(key) && created != null && StringUtils.hasText(created.referenceNumber())) {
            JournalReferenceMapping mapping = findLatestLegacyReferenceMapping(company, key)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                            "Manual journal idempotency reservation missing")
                            .withDetail("referenceNumber", rawKey));
            mapping.setCanonicalReference(created.referenceNumber());
            mapping.setEntityId(created.id());
            journalReferenceMappingRepository.save(mapping);
        }
        return created;
    }

    private String reservedManualReference(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return "RESERVED";
        }
        String hash = org.springframework.util.DigestUtils.md5DigestAsHex(
                idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "RESERVED-" + hash;
    }

    private boolean isReservedReference(String reference) {
        if (!StringUtils.hasText(reference)) {
            return false;
        }
        return reference.trim().toUpperCase(Locale.ROOT).startsWith("RESERVED-");
    }

    private String resolveReceiptIdempotencyKey(String provided, String reference, String label) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        if (StringUtils.hasText(reference)) {
            return reference.trim();
        }
        throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                "Idempotency key or reference number is required for " + label);
    }

    private String resolveDealerSettlementIdempotencyKey(Company company, DealerSettlementRequest request) {
        if (request == null) {
            return "";
        }
        if (StringUtils.hasText(request.idempotencyKey())) {
            return request.idempotencyKey().trim();
        }
        if (StringUtils.hasText(request.referenceNumber())) {
            return request.referenceNumber().trim();
        }

        String canonicalKey = buildDealerSettlementIdempotencyKey(request);
        Optional<String> replayKeyCandidate = findMatchingDealerSettlementReplayKey(company, request);
        if (replayKeyCandidate.isPresent() && !replayKeyCandidate.get().equalsIgnoreCase(canonicalKey)) {
            return replayKeyCandidate.get();
        }

        String legacyKey = buildLegacyDealerSettlementIdempotencyKey(request);
        if (StringUtils.hasText(legacyKey) && !legacyKey.equalsIgnoreCase(canonicalKey)) {
            boolean hasLegacyAllocations = hasExistingSettlementAllocations(company, legacyKey);
            if (hasLegacyAllocations && dealerSettlementReplayKeyMatchesRequest(company, request, legacyKey)) {
                return legacyKey;
            }
            if (!hasLegacyAllocations && hasExistingIdempotencyMapping(company, legacyKey)) {
                return legacyKey;
            }
        }
        return canonicalKey;
    }

    private Optional<String> findMatchingDealerSettlementReplayKey(Company company, DealerSettlementRequest request) {
        if (company == null || request == null || request.allocations() == null || request.allocations().isEmpty()) {
            return Optional.empty();
        }

        Map<String, Integer> requestSignatures = allocationSignatureCountsFromRequests(request.allocations());
        if (requestSignatures.isEmpty()) {
            return Optional.empty();
        }
        Map<DealerPaymentSignature, Integer> requestPaymentSignatures = dealerPaymentSignatureCountsFromRequest(request);
        Set<Long> requestPaymentAccountIds = requestPaymentSignatures.keySet().stream()
                .map(DealerPaymentSignature::accountId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, Long> requestedAdjustmentAccountIds = requestedAdjustmentAccountIds(request);

        Long dealerId = request.dealerId();
        Set<Long> invoiceIds = request.allocations().stream()
                .map(SettlementAllocationRequest::invoiceId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (invoiceIds.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();
        for (Long invoiceId : invoiceIds) {
            Optional<Invoice> invoiceCandidate = invoiceRepository.findByCompanyAndId(company, invoiceId);
            if (invoiceCandidate == null || invoiceCandidate.isEmpty()) {
                continue;
            }
            List<PartnerSettlementAllocation> rows = settlementAllocationRepository
                    .findByCompanyAndInvoiceOrderByCreatedAtDesc(company, invoiceCandidate.get());
            for (PartnerSettlementAllocation row : rows) {
                if (StringUtils.hasText(row.getIdempotencyKey())) {
                    candidateKeys.add(row.getIdempotencyKey().trim());
                }
            }
        }

        for (String candidateKey : candidateKeys) {
            List<PartnerSettlementAllocation> existing = findAllocationsByIdempotencyKey(company, candidateKey);
            if (existing.isEmpty()) {
                continue;
            }
            boolean dealerMismatch = existing.stream().anyMatch(row ->
                    row.getDealer() == null || !Objects.equals(row.getDealer().getId(), dealerId));
            if (dealerMismatch) {
                continue;
            }
            if (!allocationSignatureCountsFromRows(existing).equals(requestSignatures)) {
                continue;
            }
            if (!dealerPaymentSignatureCountsFromExistingRows(
                    existing,
                    requestPaymentAccountIds,
                    requestPaymentSignatures,
                    requestedAdjustmentAccountIds)
                    .equals(requestPaymentSignatures)) {
                continue;
            }
            return Optional.of(candidateKey);
        }
        return Optional.empty();
    }

    private boolean dealerSettlementReplayKeyMatchesRequest(Company company,
                                                            DealerSettlementRequest request,
                                                            String candidateKey) {
        if (company == null || request == null || !StringUtils.hasText(candidateKey)) {
            return false;
        }
        List<PartnerSettlementAllocation> existing = findAllocationsByIdempotencyKey(company, candidateKey);
        if (existing.isEmpty()) {
            return false;
        }
        Map<String, Integer> requestSignatures = allocationSignatureCountsFromRequests(
                request.allocations() != null ? request.allocations() : List.of());
        if (requestSignatures.isEmpty()) {
            return false;
        }
        Long dealerId = request.dealerId();
        boolean dealerMismatch = existing.stream().anyMatch(row ->
                row.getDealer() == null || !Objects.equals(row.getDealer().getId(), dealerId));
        if (dealerMismatch) {
            return false;
        }
        if (!allocationSignatureCountsFromRows(existing).equals(requestSignatures)) {
            return false;
        }
        Map<DealerPaymentSignature, Integer> requestPaymentSignatures = dealerPaymentSignatureCountsFromRequest(request);
        Set<Long> requestPaymentAccountIds = requestPaymentSignatures.keySet().stream()
                .map(DealerPaymentSignature::accountId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, Long> requestedAdjustmentAccountIds = requestedAdjustmentAccountIds(request);
        return dealerPaymentSignatureCountsFromExistingRows(
                existing,
                requestPaymentAccountIds,
                requestPaymentSignatures,
                requestedAdjustmentAccountIds)
                .equals(requestPaymentSignatures);
    }

    private String normalizeIdempotencyMappingKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return "";
        }
        return idempotencyKey.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<JournalReferenceMapping> findLatestLegacyReferenceMapping(Company company, String idempotencyKey) {
        if (company == null || !StringUtils.hasText(idempotencyKey)) {
            return Optional.empty();
        }
        List<JournalReferenceMapping> mappings = journalReferenceMappingRepository
                .findAllByCompanyAndLegacyReferenceIgnoreCase(company, idempotencyKey);
        if (mappings == null || mappings.isEmpty()) {
            return Optional.empty();
        }
        if (mappings.size() > 1) {
            log.warn("Multiple journal_reference_mappings rows for company={} idempotencyKey='{}'; selecting deterministic mapping",
                    company.getId(), idempotencyKey);
        }
        Comparator<JournalReferenceMapping> ranking = Comparator
                .comparing((JournalReferenceMapping mapping) -> mapping.getEntityId() != null)
                .thenComparing(JournalReferenceMapping::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(JournalReferenceMapping::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        return mappings.stream().max(ranking);
    }

    private String resolveDealerSettlementReference(Company company,
                                                    Dealer dealer,
                                                    DealerSettlementRequest request,
                                                    String idempotencyKey) {
        if (request != null && StringUtils.hasText(request.referenceNumber())) {
            return request.referenceNumber().trim();
        }
        if (company != null && StringUtils.hasText(idempotencyKey)) {
            String key = normalizeIdempotencyMappingKey(idempotencyKey);
            Optional<JournalReferenceMapping> mapping = findLatestLegacyReferenceMapping(company, key);
            if (mapping.isPresent() && StringUtils.hasText(mapping.get().getCanonicalReference())) {
                return mapping.get().getCanonicalReference().trim();
            }
            return reservedManualReference(key);
        }
        return referenceNumberService.dealerReceiptReference(company, dealer);
    }

    private String resolveSupplierPaymentReference(Company company,
                                                   Supplier supplier,
                                                   String providedReference,
                                                   String idempotencyKey) {
        if (StringUtils.hasText(providedReference)) {
            return providedReference.trim();
        }
        if (company != null && StringUtils.hasText(idempotencyKey)) {
            String key = normalizeIdempotencyMappingKey(idempotencyKey);
            Optional<JournalReferenceMapping> mapping = findLatestLegacyReferenceMapping(company, key);
            if (mapping.isPresent() && StringUtils.hasText(mapping.get().getCanonicalReference())) {
                return mapping.get().getCanonicalReference().trim();
            }
        }
        return referenceNumberService.supplierPaymentReference(company, supplier);
    }

    private String resolveSupplierSettlementReference(Company company,
                                                      Supplier supplier,
                                                      SupplierSettlementRequest request,
                                                      String idempotencyKey) {
        if (request != null && StringUtils.hasText(request.referenceNumber())) {
            return request.referenceNumber().trim();
        }
        if (company != null && StringUtils.hasText(idempotencyKey)) {
            String key = normalizeIdempotencyMappingKey(idempotencyKey);
            Optional<JournalReferenceMapping> mapping = findLatestLegacyReferenceMapping(company, key);
            if (mapping.isPresent() && StringUtils.hasText(mapping.get().getCanonicalReference())) {
                return mapping.get().getCanonicalReference().trim();
            }
        }
        return referenceNumberService.supplierPaymentReference(company, supplier);
    }

    private boolean hasExistingIdempotencyMapping(Company company, String idempotencyKey) {
        if (company == null || !StringUtils.hasText(idempotencyKey)) {
            return false;
        }
        String key = normalizeIdempotencyMappingKey(idempotencyKey);
        return findLatestLegacyReferenceMapping(company, key).isPresent();
    }

    private boolean hasExistingSettlementAllocations(Company company, String idempotencyKey) {
        if (company == null || !StringUtils.hasText(idempotencyKey)) {
            return false;
        }
        return !findAllocationsByIdempotencyKey(company, idempotencyKey).isEmpty();
    }

    private IdempotencyReservation reserveReferenceMapping(Company company,
                                                           String idempotencyKey,
                                                           String canonicalReference,
                                                           String entityType) {
        if (company == null || !StringUtils.hasText(idempotencyKey) || !StringUtils.hasText(canonicalReference)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key and reference number are required to reserve journal mapping");
        }
        String key = normalizeIdempotencyMappingKey(idempotencyKey);
        String canonical = canonicalReference.trim();
        Optional<JournalReferenceMapping> existing = findLatestLegacyReferenceMapping(company, key);
        if (existing.isPresent()) {
            JournalReferenceMapping mapping = existing.get();
            if (StringUtils.hasText(mapping.getCanonicalReference())
                    && !mapping.getCanonicalReference().equalsIgnoreCase(canonical)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used for another reference")
                        .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, key)
                        .withDetail("referenceNumber", mapping.getCanonicalReference());
            }
            return new IdempotencyReservation(false, canonical);
        }
        int reserved = journalReferenceMappingRepository.reserveReferenceMapping(
                company.getId(),
                key,
                canonical,
                entityType,
                CompanyTime.now(company)
        );
        if (reserved == 1) {
            return new IdempotencyReservation(true, canonical);
        }
        JournalReferenceMapping mapping = findLatestLegacyReferenceMapping(company, key)
                .orElseThrow(() -> new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                        "Idempotency key already reserved but mapping not found")
                        .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, key));
        if (StringUtils.hasText(mapping.getCanonicalReference())
                && !mapping.getCanonicalReference().equalsIgnoreCase(canonical)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for another reference")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, key)
                    .withDetail("referenceNumber", mapping.getCanonicalReference());
        }
        return new IdempotencyReservation(false, canonical);
    }

    private void linkReferenceMapping(Company company, String idempotencyKey, JournalEntry entry, String entityType) {
        if (company == null || !StringUtils.hasText(idempotencyKey) || entry == null) {
            return;
        }
        String key = normalizeIdempotencyMappingKey(idempotencyKey);
        Optional<JournalReferenceMapping> mappingCandidate = findLatestLegacyReferenceMapping(company, key);
        if (mappingCandidate.isEmpty()) {
            JournalReferenceMapping created = new JournalReferenceMapping();
            created.setCompany(company);
            created.setLegacyReference(key);
            created.setCanonicalReference(entry.getReferenceNumber());
            created.setEntityId(entry.getId());
            if (StringUtils.hasText(entityType)) {
                created.setEntityType(entityType);
            }
            journalReferenceMappingRepository.save(created);
            return;
        }
        JournalReferenceMapping mapping = mappingCandidate.get();
        boolean canonicalMismatch = StringUtils.hasText(mapping.getCanonicalReference())
                && !mapping.getCanonicalReference().equalsIgnoreCase(entry.getReferenceNumber());
        boolean canRepairUnlinkedMapping = mapping.getEntityId() == null;
        if (StringUtils.hasText(mapping.getCanonicalReference())
                && canonicalMismatch
                && !isReservedReference(mapping.getCanonicalReference())
                && !canRepairUnlinkedMapping) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key maps to a different journal reference")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, key)
                    .withDetail("referenceNumber", mapping.getCanonicalReference());
        }
        mapping.setCanonicalReference(entry.getReferenceNumber());
        mapping.setEntityId(entry.getId());
        if (StringUtils.hasText(entityType)) {
            mapping.setEntityType(entityType);
        }
        journalReferenceMappingRepository.save(mapping);
    }

    private JournalEntry awaitJournalEntry(Company company, String reference, String idempotencyKey) {
        JournalEntry existing = findExistingEntry(company, reference, idempotencyKey);
        if (existing != null) {
            return existing;
        }
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            sleepBriefly();
            existing = findExistingEntry(company, reference, idempotencyKey);
            if (existing != null) {
                return existing;
            }
        }
        return null;
    }

    private JournalEntry findExistingEntry(Company company, String reference, String idempotencyKey) {
        if (company == null) {
            return null;
        }
        if (StringUtils.hasText(reference)) {
            Optional<JournalEntry> byReference = journalReferenceResolver.findExistingEntry(company, reference);
            if (byReference.isPresent()) {
                return byReference.get();
            }
        }
        if (StringUtils.hasText(idempotencyKey)) {
            Optional<JournalEntry> byKey = journalReferenceResolver.findExistingEntry(company, idempotencyKey);
            return byKey.orElse(null);
        }
        return null;
    }

    private List<PartnerSettlementAllocation> awaitAllocations(Company company, String idempotencyKey) {
        if (company == null || !StringUtils.hasText(idempotencyKey)) {
            return List.of();
        }
        List<PartnerSettlementAllocation> existing = findAllocationsByIdempotencyKey(company, idempotencyKey);
        if (!existing.isEmpty()) {
            return existing;
        }
        long deadline = System.nanoTime() + IDEMPOTENCY_WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            sleepBriefly();
            existing = findAllocationsByIdempotencyKey(company, idempotencyKey);
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        return existing;
    }

    private List<PartnerSettlementAllocation> findAllocationsByIdempotencyKey(Company company, String idempotencyKey) {
        if (company == null || !StringUtils.hasText(idempotencyKey)) {
            return List.of();
        }
        String key = idempotencyKey.trim();
        List<PartnerSettlementAllocation> matches = settlementAllocationRepository
                .findByCompanyAndIdempotencyKeyIgnoreCaseOrderByCreatedAtAscIdAsc(company, key);
        if (matches != null && !matches.isEmpty()) {
            return matches;
        }
        List<PartnerSettlementAllocation> exact = settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, key);
        return exact != null ? exact : List.of();
    }

    private List<PartnerSettlementAllocation> resolveAllocationsForIdempotentReceiptReplay(Company company,
                                                                                            String idempotencyKey,
                                                                                            JournalEntry existingEntry) {
        if (existingEntry != null) {
            List<PartnerSettlementAllocation> existingEntryAllocations = settlementAllocationRepository
                    .findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, existingEntry);
            if (!existingEntryAllocations.isEmpty()) {
                return existingEntryAllocations;
            }
        }
        List<PartnerSettlementAllocation> existingAllocations = awaitAllocations(company, idempotencyKey);
        if (!existingAllocations.isEmpty() || existingEntry == null) {
            return existingAllocations;
        }
        return settlementAllocationRepository.findByCompanyAndJournalEntryOrderByCreatedAtAsc(company, existingEntry);
    }

    private JournalEntry resolveReplayJournalEntry(String idempotencyKey,
                                                   JournalEntry mappingEntry,
                                                   List<PartnerSettlementAllocation> allocations) {
        JournalEntry allocationEntry = null;
        if (allocations != null && !allocations.isEmpty()) {
            allocationEntry = allocations.getFirst().getJournalEntry();
        }
        if (mappingEntry != null
                && allocationEntry != null
                && mappingEntry.getId() != null
                && allocationEntry.getId() != null
                && !Objects.equals(mappingEntry.getId(), allocationEntry.getId())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency mapping points to a different journal than settled allocations")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                    .withDetail("mappingJournalEntryId", mappingEntry.getId())
                    .withDetail("allocationJournalEntryId", allocationEntry.getId());
        }
        return allocationEntry != null ? allocationEntry : mappingEntry;
    }

    private JournalEntry resolveReplayJournalEntryFromExistingAllocations(Company company,
                                                                          String reference,
                                                                          String idempotencyKey,
                                                                          List<PartnerSettlementAllocation> allocations) {
        JournalEntry mappingEntry = findExistingEntry(company, reference, idempotencyKey);
        return resolveReplayJournalEntry(idempotencyKey, mappingEntry, allocations);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(IDEMPOTENCY_WAIT_SLEEP_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void validateDealerReceiptIdempotency(String idempotencyKey,
                                                  Dealer dealer,
                                                  Account cashAccount,
                                                  Account receivableAccount,
                                                  BigDecimal amount,
                                                  String memo,
                                                  JournalEntry entry,
                                                  List<PartnerSettlementAllocation> existingAllocations,
                                                  List<SettlementAllocationRequest> allocations) {
        validateSettlementIdempotencyKey(idempotencyKey, PartnerType.DEALER, dealer.getId(), existingAllocations, allocations);
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, amount, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(receivableAccount.getId(), memo, BigDecimal.ZERO, amount)
        );
        validateReceiptJournalLines(idempotencyKey, dealer, memo, entry, expectedLines);
    }

    private void validateSplitReceiptIdempotency(String idempotencyKey,
                                                 Dealer dealer,
                                                 String memo,
                                                 JournalEntry entry,
                                                 List<JournalEntryRequest.JournalLineRequest> expectedLines) {
        validateReceiptJournalLines(idempotencyKey, dealer, memo, entry, expectedLines);
    }

    private void validateReceiptJournalLines(String idempotencyKey,
                                             Dealer dealer,
                                             String memo,
                                             JournalEntry entry,
                                             List<JournalEntryRequest.JournalLineRequest> expectedLines) {
        validatePartnerJournalReplay(
                idempotencyKey,
                PartnerType.DEALER,
                dealer != null ? dealer.getId() : null,
                memo,
                entry,
                expectedLines,
                "Idempotency key already used for a different receipt payload");
    }

    private void validateSupplierPaymentIdempotency(String idempotencyKey,
                                                    Supplier supplier,
                                                    Account cashAccount,
                                                    Account payableAccount,
                                                    BigDecimal amount,
                                                    String memo,
                                                    JournalEntry entry,
                                                    List<PartnerSettlementAllocation> existingAllocations,
                                                    List<SettlementAllocationRequest> allocations) {
        validateSettlementIdempotencyKey(idempotencyKey, PartnerType.SUPPLIER, supplier.getId(), existingAllocations, allocations);
        List<JournalEntryRequest.JournalLineRequest> expectedLines = List.of(
                new JournalEntryRequest.JournalLineRequest(payableAccount.getId(), memo, amount, BigDecimal.ZERO),
                new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, amount)
        );
        validatePartnerJournalReplay(
                idempotencyKey,
                PartnerType.SUPPLIER,
                supplier != null ? supplier.getId() : null,
                memo,
                entry,
                expectedLines,
                "Idempotency key already used for a different supplier payment payload");
    }

    private void validatePartnerSettlementJournalLines(String idempotencyKey,
                                                       PartnerType partnerType,
                                                       Long partnerId,
                                                       String memo,
                                                       JournalEntry entry,
                                                       List<JournalEntryRequest.JournalLineRequest> expectedLines) {
        validatePartnerJournalReplay(
                idempotencyKey,
                partnerType,
                partnerId,
                memo,
                entry,
                expectedLines,
                "Idempotency key already used for a different settlement payload");
    }

    private ApplicationException missingReservedPartnerAllocation(String subject,
                                                                  String idempotencyKey,
                                                                  PartnerType partnerType,
                                                                  Long partnerId) {
        ApplicationException exception = new ApplicationException(
                ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                subject + " idempotency key is reserved but allocation not found")
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        if (partnerType != null) {
            exception.withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType.name());
        }
        if (partnerId != null) {
            exception.withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
        }
        return exception;
    }

    private void validatePartnerJournalReplay(String idempotencyKey,
                                              PartnerType partnerType,
                                              Long partnerId,
                                              String memo,
                                              JournalEntry entry,
                                              List<JournalEntryRequest.JournalLineRequest> expectedLines,
                                              String payloadMismatchMessage) {
        if (entry == null) {
            throw replayConflictWithPartnerContext(
                    "Idempotency key already used but journal entry is missing",
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }
        if (isJournalEntryPartnerMismatch(entry, partnerType, partnerId)) {
            throw replayConflictWithPartnerContext(
                    partnerMismatchMessage(partnerType),
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }
        if (StringUtils.hasText(memo) && !Objects.equals(entry.getMemo(), memo)) {
            throw replayConflictWithPartnerContext(
                    "Idempotency key already used with a different memo",
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }
        Map<JournalLineSignature, Integer> existingLines = lineSignatureCounts(entry.getLines());
        Map<JournalLineSignature, Integer> expected = lineSignatureCountsFromRequests(expectedLines);
        if (!existingLines.equals(expected)) {
            throw replayConflictWithPartnerContext(
                    payloadMismatchMessage,
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }
    }

    private ApplicationException replayConflictWithPartnerContext(String message,
                                                                  String idempotencyKey,
                                                                  PartnerType partnerType,
                                                                  Long partnerId) {
        String normalizedIdempotencyKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : idempotencyKey;
        String partnerTypeDetail = partnerType != null ? partnerType.name() : "null";
        ApplicationException exception = new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, message)
                .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, normalizedIdempotencyKey)
                .withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerTypeDetail);
        if (partnerId != null) {
            exception.withDetail(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId);
        }
        return exception;
    }

    private boolean isJournalEntryPartnerMismatch(JournalEntry entry,
                                                  PartnerType partnerType,
                                                  Long partnerId) {
        if (partnerType == PartnerType.DEALER) {
            return entry.getDealer() == null || !Objects.equals(entry.getDealer().getId(), partnerId);
        }
        if (partnerType == PartnerType.SUPPLIER) {
            return entry.getSupplier() == null || !Objects.equals(entry.getSupplier().getId(), partnerId);
        }
        return true;
    }

    private String partnerMismatchMessage(PartnerType partnerType) {
        return "Idempotency key already used for another " + partnerMismatchSubject(partnerType);
    }

    private String partnerMismatchSubject(PartnerType partnerType) {
        if (partnerType == PartnerType.DEALER) {
            return "dealer";
        }
        if (partnerType == PartnerType.SUPPLIER) {
            return "supplier";
        }
        return "partner type";
    }

    private void validateCreditNoteIdempotency(String idempotencyKey,
                                               Invoice invoice,
                                               JournalEntry source,
                                               JournalEntry entry,
                                               BigDecimal requestedAmount,
                                               BigDecimal totalAmount) {
        if (entry == null) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used but credit note journal is missing")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        if (invoice != null && invoice.getDealer() != null && entry.getDealer() != null
                && !Objects.equals(entry.getDealer().getId(), invoice.getDealer().getId())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for another dealer")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        if (source != null && entry.getReversalOf() != null
                && !Objects.equals(entry.getReversalOf().getId(), source.getId())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for another invoice reversal")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        if (source != null && entry.getReversalOf() == null && invoice != null
                && !invoice.getPaymentReferences().contains(entry.getReferenceNumber())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for another invoice")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        BigDecimal existingAmount = calculateCreditNoteAmount(entry, invoice, source);
        BigDecimal expectedAmount = requestedAmount != null ? roundCurrency(requestedAmount) : existingAmount;
        if (existingAmount.compareTo(expectedAmount) != 0) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used with a different credit amount")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey)
                    .withDetail("existingAmount", existingAmount)
                    .withDetail("requestedAmount", expectedAmount);
        }
        if (source != null && totalAmount != null && totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = expectedAmount.divide(totalAmount, 6, RoundingMode.HALF_UP);
            List<JournalEntryRequest.JournalLineRequest> expectedLines =
                    buildScaledReversalLines(source, ratio, "Credit note reversal - ");
            Map<JournalLineSignature, Integer> existingLines = lineSignatureCounts(entry.getLines());
            Map<JournalLineSignature, Integer> expected = lineSignatureCountsFromRequests(expectedLines);
            if (!existingLines.equals(expected)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used for a different credit note payload")
                        .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
            }
        }
    }

    private void validateDealerSettlementAllocations(List<SettlementAllocationRequest> allocations) {
        if (allocations == null) {
            return;
        }
        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Invoice allocation is required for dealer settlements");
            }
            if (allocation.purchaseId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dealer settlements cannot allocate to purchases");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
            validateDealerAllocationCashContribution(allocation.invoiceId(), applied, discount, writeOff, fxAdjustment);
        }
    }

    private void validateSupplierSettlementAllocations(List<SettlementAllocationRequest> allocations) {
        if (allocations == null) {
            return;
        }
        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.invoiceId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier settlements cannot allocate to invoices");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());
            if (allocation.purchaseId() == null
                    && (discount.compareTo(BigDecimal.ZERO) > 0
                    || writeOff.compareTo(BigDecimal.ZERO) > 0
                    || fxAdjustment.compareTo(BigDecimal.ZERO) != 0)) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "On-account supplier settlement allocations cannot include discount/write-off/FX adjustments");
            }
            validateSupplierAllocationCashContribution(allocation.purchaseId(), applied, discount, writeOff, fxAdjustment);
        }
    }

    private SettlementTotals computeSettlementTotals(List<SettlementAllocationRequest> allocations) {
        BigDecimal totalApplied = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        BigDecimal totalFxGain = BigDecimal.ZERO;
        BigDecimal totalFxLoss = BigDecimal.ZERO;
        if (allocations == null) {
            return new SettlementTotals(totalApplied, totalDiscount, totalWriteOff, totalFxGain, totalFxLoss);
        }
        for (SettlementAllocationRequest allocation : allocations) {
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            BigDecimal discount = normalizeNonNegative(allocation.discountAmount(), "discountAmount");
            BigDecimal writeOff = normalizeNonNegative(allocation.writeOffAmount(), "writeOffAmount");
            BigDecimal fxAdjustment = MoneyUtils.zeroIfNull(allocation.fxAdjustment());

            totalApplied = totalApplied.add(applied);
            totalDiscount = totalDiscount.add(discount);
            totalWriteOff = totalWriteOff.add(writeOff);
            if (fxAdjustment.compareTo(BigDecimal.ZERO) > 0) {
                totalFxGain = totalFxGain.add(fxAdjustment);
            } else if (fxAdjustment.compareTo(BigDecimal.ZERO) < 0) {
                totalFxLoss = totalFxLoss.add(fxAdjustment.abs());
            }
        }
        return new SettlementTotals(totalApplied, totalDiscount, totalWriteOff, totalFxGain, totalFxLoss);
    }

    private void validateDealerAllocationCashContribution(Long invoiceId,
                                                          BigDecimal applied,
                                                          BigDecimal discount,
                                                          BigDecimal writeOff,
                                                          BigDecimal fxAdjustment) {
        BigDecimal fxGain = fxAdjustment.compareTo(BigDecimal.ZERO) > 0 ? fxAdjustment : BigDecimal.ZERO;
        BigDecimal fxLoss = fxAdjustment.compareTo(BigDecimal.ZERO) < 0 ? fxAdjustment.abs() : BigDecimal.ZERO;
        BigDecimal netCashContribution = applied
                .add(fxGain)
                .subtract(fxLoss)
                .subtract(discount)
                .subtract(writeOff);
        validateAllocationCashContribution("dealer", "invoiceId", invoiceId, netCashContribution, applied, discount, writeOff, fxAdjustment);
    }

    private void validateSupplierAllocationCashContribution(Long purchaseId,
                                                            BigDecimal applied,
                                                            BigDecimal discount,
                                                            BigDecimal writeOff,
                                                            BigDecimal fxAdjustment) {
        BigDecimal fxGain = fxAdjustment.compareTo(BigDecimal.ZERO) > 0 ? fxAdjustment : BigDecimal.ZERO;
        BigDecimal fxLoss = fxAdjustment.compareTo(BigDecimal.ZERO) < 0 ? fxAdjustment.abs() : BigDecimal.ZERO;
        BigDecimal netCashContribution = applied
                .add(fxLoss)
                .subtract(fxGain)
                .subtract(discount)
                .subtract(writeOff);
        validateAllocationCashContribution("supplier", "purchaseId", purchaseId, netCashContribution, applied, discount, writeOff, fxAdjustment);
    }

    private void validateAllocationCashContribution(String partnerLabel,
                                                    String referenceField,
                                                    Long referenceId,
                                                    BigDecimal contribution,
                                                    BigDecimal applied,
                                                    BigDecimal discount,
                                                    BigDecimal writeOff,
                                                    BigDecimal fxAdjustment) {
        if (contribution.compareTo(BigDecimal.ZERO) < 0
                && contribution.abs().compareTo(ALLOCATION_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Settlement allocation has negative net cash contribution for " + partnerLabel + " settlement")
                    .withDetail(referenceField, referenceId)
                    .withDetail("appliedAmount", applied)
                    .withDetail("discountAmount", discount)
                    .withDetail("writeOffAmount", writeOff)
                    .withDetail("fxAdjustment", fxAdjustment)
                    .withDetail("netCashContribution", contribution);
        }
    }

    private SettlementLineDraft buildDealerSettlementLines(Company company,
                                                           DealerSettlementRequest request,
                                                           Account receivableAccount,
                                                           SettlementTotals totals,
                                                           String memo,
                                                           boolean requireActiveCashAccounts) {
        if (totals == null) {
            totals = new SettlementTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0 && request.discountAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Discount account is required when a discount is applied");
        }
        if (totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0 && request.writeOffAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Write-off account is required when a write-off is applied");
        }
        if (totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0 && request.fxGainAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX gain account is required when FX gain is provided");
        }
        if (totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0 && request.fxLossAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX loss account is required when FX loss is provided");
        }

        Account discountAccount = totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.discountAccountId())
                : null;
        Account writeOffAccount = totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.writeOffAccountId())
                : null;
        Account fxGainAccount = totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxGainAccountId())
                : null;
        Account fxLossAccount = totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxLossAccountId())
                : null;

        BigDecimal cashAmount = totals.totalApplied()
                .add(totals.totalFxGain())
                .subtract(totals.totalFxLoss())
                .subtract(totals.totalDiscount())
                .subtract(totals.totalWriteOff());
        if (cashAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Calculated cash amount cannot be negative. Adjust discount/write-off/FX values.");
        }

        List<SettlementPaymentRequest> paymentRequests = request.payments() != null && !request.payments().isEmpty()
                ? request.payments()
                : null;
        List<JournalEntryRequest.JournalLineRequest> paymentLines = new ArrayList<>();
        if (paymentRequests == null) {
            if (cashAmount.compareTo(BigDecimal.ZERO) > 0 && request.cashAccountId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "cashAccountId is required when cash is moving");
            }
            if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
                Account cashAccount = requireCashAccountForSettlement(
                        company,
                        request.cashAccountId(),
                        "dealer settlement",
                        requireActiveCashAccounts);
                paymentLines.add(new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, cashAmount, BigDecimal.ZERO));
            }
        } else {
            BigDecimal paymentTotal = BigDecimal.ZERO;
            for (SettlementPaymentRequest payment : paymentRequests) {
                BigDecimal amount = requirePositive(payment.amount(), "payment amount");
                Account account = requireCashAccountForSettlement(
                        company,
                        payment.accountId(),
                        "dealer settlement payment line",
                        requireActiveCashAccounts);
                paymentLines.add(new JournalEntryRequest.JournalLineRequest(account.getId(), memo, amount, BigDecimal.ZERO));
                paymentTotal = paymentTotal.add(amount);
            }
            if (cashAmount.compareTo(paymentTotal) != 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        String.format("Payment total (%s) must equal net cash required (%s)", paymentTotal, cashAmount));
            }
        }

        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        lines.addAll(paymentLines);
        if (totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    discountAccount.getId(),
                    "Settlement discount",
                    totals.totalDiscount(),
                    BigDecimal.ZERO));
        }
        if (totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    writeOffAccount.getId(),
                    "Settlement write-off",
                    totals.totalWriteOff(),
                    BigDecimal.ZERO));
        }
        if (totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxLossAccount.getId(),
                    "FX loss on settlement",
                    totals.totalFxLoss(),
                    BigDecimal.ZERO));
        }
        lines.add(new JournalEntryRequest.JournalLineRequest(receivableAccount.getId(), memo, BigDecimal.ZERO, totals.totalApplied()));
        if (totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxGainAccount.getId(),
                    "FX gain on settlement",
                    BigDecimal.ZERO,
                    totals.totalFxGain()));
        }
        return new SettlementLineDraft(lines, cashAmount);
    }

    private SettlementLineDraft buildSupplierSettlementLines(Company company,
                                                            SupplierSettlementRequest request,
                                                            Account payableAccount,
                                                            Account cashAccount,
                                                            SettlementTotals totals,
                                                            String memo) {
        if (totals == null) {
            totals = new SettlementTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0 && request.discountAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Discount account is required when a discount is applied");
        }
        if (totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0 && request.writeOffAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Write-off account is required when a write-off is applied");
        }
        if (totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0 && request.fxGainAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX gain account is required when FX gain is provided");
        }
        if (totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0 && request.fxLossAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX loss account is required when FX loss is provided");
        }

        Account discountAccount = totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.discountAccountId())
                : null;
        Account writeOffAccount = totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.writeOffAccountId())
                : null;
        Account fxGainAccount = totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxGainAccountId())
                : null;
        Account fxLossAccount = totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxLossAccountId())
                : null;

        // Cash is what actually moves: applied minus concessions, adjusted for FX gain/loss lines
        BigDecimal cashAmount = totals.totalApplied()
                .add(totals.totalFxLoss())      // loss increases cash paid
                .subtract(totals.totalFxGain()) // gain reduces cash paid
                .subtract(totals.totalDiscount())
                .subtract(totals.totalWriteOff());
        if (cashAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Calculated cash amount cannot be negative. Adjust discount/write-off/FX values.");
        }

        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalEntryRequest.JournalLineRequest(payableAccount.getId(), memo, totals.totalApplied(), BigDecimal.ZERO));
        if (totals.totalFxLoss().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxLossAccount.getId(),
                    "FX loss on settlement",
                    totals.totalFxLoss(),
                    BigDecimal.ZERO));
        }
        if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, cashAmount));
        }
        if (totals.totalDiscount().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    discountAccount.getId(),
                    "Settlement discount received",
                    BigDecimal.ZERO,
                    totals.totalDiscount()));
        }
        if (totals.totalWriteOff().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    writeOffAccount.getId(),
                    "Settlement write-off",
                    BigDecimal.ZERO,
                    totals.totalWriteOff()));
        }
        if (totals.totalFxGain().compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxGainAccount.getId(),
                    "FX gain on settlement",
                    BigDecimal.ZERO,
                    totals.totalFxGain()));
        }

        return new SettlementLineDraft(lines, cashAmount);
    }

    private PartnerSettlementResponse buildDealerSettlementResponse(List<PartnerSettlementAllocation> existing) {
        if (existing == null || existing.isEmpty()) {
            throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                    "Settlement allocations missing for idempotent response");
        }
        JournalEntry entry = existing.getFirst().getJournalEntry();
        BigDecimal applied = existing.stream().map(PartnerSettlementAllocation::getAllocationAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountSum = existing.stream().map(PartnerSettlementAllocation::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal writeOffSum = existing.stream().map(PartnerSettlementAllocation::getWriteOffAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fxGainSum = existing.stream()
                .map(PartnerSettlementAllocation::getFxDifferenceAmount)
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fxLossSum = existing.stream()
                .map(PartnerSettlementAllocation::getFxDifferenceAmount)
                .filter(v -> v.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashAmount = applied
                .add(fxGainSum)
                .subtract(fxLossSum)
                .subtract(discountSum)
                .subtract(writeOffSum);
        for (PartnerSettlementAllocation row : existing) {
            if (row.getInvoice() != null) {
                dealerLedgerService.syncInvoiceLedger(row.getInvoice(), row.getSettlementDate());
            }
        }
        return new PartnerSettlementResponse(
                toDto(entry),
                applied,
                cashAmount,
                discountSum,
                writeOffSum,
                fxGainSum,
                fxLossSum,
                toSettlementAllocationSummaries(existing)
        );
    }

    private PartnerSettlementResponse buildSupplierSettlementResponse(List<PartnerSettlementAllocation> existing) {
        if (existing == null || existing.isEmpty()) {
            throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                    "Settlement allocations missing for idempotent response");
        }
        JournalEntry entry = existing.getFirst().getJournalEntry();
        BigDecimal applied = existing.stream().map(PartnerSettlementAllocation::getAllocationAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountSum = existing.stream().map(PartnerSettlementAllocation::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal writeOffSum = existing.stream().map(PartnerSettlementAllocation::getWriteOffAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fxGainSum = existing.stream()
                .map(PartnerSettlementAllocation::getFxDifferenceAmount)
                .filter(v -> v.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fxLossSum = existing.stream()
                .map(PartnerSettlementAllocation::getFxDifferenceAmount)
                .filter(v -> v.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashAmount = applied
                .add(fxLossSum)
                .subtract(fxGainSum)
                .subtract(discountSum)
                .subtract(writeOffSum);

        return new PartnerSettlementResponse(
                toDto(entry),
                applied,
                cashAmount,
                discountSum,
                writeOffSum,
                fxGainSum,
                fxLossSum,
                toSettlementAllocationSummaries(existing)
        );
    }

    private List<PartnerSettlementResponse.Allocation> toSettlementAllocationSummaries(
            List<PartnerSettlementAllocation> allocations) {
        return allocations.stream()
                .map(row -> new PartnerSettlementResponse.Allocation(
                        row.getInvoice() != null ? row.getInvoice().getId() : null,
                        row.getPurchase() != null ? row.getPurchase().getId() : null,
                        row.getAllocationAmount(),
                        row.getDiscountAmount(),
                        row.getWriteOffAmount(),
                        row.getFxDifferenceAmount(),
                        row.getMemo()
                ))
                .toList();
    }

    private void logSettlementAuditSuccess(PartnerType partnerType,
                                           Long partnerId,
                                           JournalEntryDto journalEntryDto,
                                           LocalDate settlementDate,
                                           String idempotencyKey,
                                           int allocationCount,
                                           BigDecimal totalApplied,
                                           BigDecimal cashAmount,
                                           BigDecimal totalDiscount,
                                           BigDecimal totalWriteOff,
                                           BigDecimal totalFxGain,
                                           BigDecimal totalFxLoss) {
        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put(IntegrationFailureMetadataSchema.KEY_PARTNER_TYPE, partnerType.name());
        if (partnerId != null) {
            auditMetadata.put(IntegrationFailureMetadataSchema.KEY_PARTNER_ID, partnerId.toString());
        }
        if (journalEntryDto != null && journalEntryDto.id() != null) {
            auditMetadata.put(IntegrationFailureMetadataSchema.KEY_JOURNAL_ENTRY_ID, journalEntryDto.id().toString());
        }
        if (settlementDate != null) {
            auditMetadata.put(IntegrationFailureMetadataSchema.KEY_SETTLEMENT_DATE, settlementDate.toString());
        }
        if (idempotencyKey != null) {
            auditMetadata.put(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        auditMetadata.put(IntegrationFailureMetadataSchema.KEY_ALLOCATION_COUNT, Integer.toString(allocationCount));
        auditMetadata.put("totalApplied", totalApplied.toPlainString());
        auditMetadata.put("cashAmount", cashAmount.toPlainString());
        auditMetadata.put("totalDiscount", totalDiscount.toPlainString());
        auditMetadata.put("totalWriteOff", totalWriteOff.toPlainString());
        auditMetadata.put("totalFxGain", totalFxGain.toPlainString());
        auditMetadata.put("totalFxLoss", totalFxLoss.toPlainString());
        logAuditSuccessAfterCommit(AuditEvent.SETTLEMENT_RECORDED, auditMetadata);
    }

    private Map<JournalLineSignature, Integer> lineSignatureCountsFromRequests(
            List<JournalEntryRequest.JournalLineRequest> lines) {
        Map<JournalLineSignature, Integer> counts = new HashMap<>();
        if (lines == null) {
            return counts;
        }
        for (JournalEntryRequest.JournalLineRequest line : lines) {
            if (line.accountId() == null) {
                continue;
            }
            JournalLineSignature signature = new JournalLineSignature(
                    line.accountId(),
                    normalizeAmount(line.debit()),
                    normalizeAmount(line.credit()));
            counts.merge(signature, 1, Integer::sum);
        }
        return counts;
    }

    private record IdempotencyReservation(boolean leader, String canonicalReference) {
    }

    private record SettlementTotals(BigDecimal totalApplied,
                                    BigDecimal totalDiscount,
                                    BigDecimal totalWriteOff,
                                    BigDecimal totalFxGain,
                                    BigDecimal totalFxLoss) {
    }

    private record SettlementLineDraft(List<JournalEntryRequest.JournalLineRequest> lines,
                                       BigDecimal cashAmount) {
    }

    private void enforceSettlementCurrency(Company company, Invoice invoice) {
        if (company == null || invoice == null) {
            return;
        }
        String settlementCurrency = company.getBaseCurrency();
        if (StringUtils.hasText(settlementCurrency)
                && invoice.getCurrency() != null
                && !invoice.getCurrency().equalsIgnoreCase(settlementCurrency)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    String.format("Cannot settle invoice %s in %s with settlement currency %s",
                            invoice.getInvoiceNumber(), invoice.getCurrency(), settlementCurrency));
        }
    }

    private void validateEntryDate(Company company, LocalDate entryDate, boolean overrideRequested, boolean overrideAuthorized) {
        if (entryDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date is required");
        }
        // Skip date validation in benchmark mode
        if (skipDateValidation) {
            return;
        }
        LocalDate today = currentDate(company);
        LocalDate oldestAllowed = today.minusDays(30);
        boolean future = entryDate.isAfter(today);
        boolean tooOld = entryDate.isBefore(oldestAllowed);
        if ((!overrideAuthorized) && (future || tooOld)) {
            if (overrideRequested && !overrideAuthorized) {
                String reason = future ? "future period" : "a closed period";
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Administrator approval is required to post into " + reason);
            }
            if (future) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date cannot be in the future");
            }
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, 
                    "Entry date cannot be more than 30 days old; posting to locked/closed periods requires admin override");
        }
    }

    private JournalEntryDto createJournalEntryForReversal(JournalEntryRequest payload, boolean allowClosedPeriodOverride) {
        if (!allowClosedPeriodOverride) {
            return createJournalEntry(payload);
        }
        Boolean previous = SYSTEM_ENTRY_DATE_OVERRIDE.get();
        SYSTEM_ENTRY_DATE_OVERRIDE.set(Boolean.TRUE);
        try {
            return createJournalEntry(payload);
        } finally {
            if (Boolean.TRUE.equals(previous)) {
                SYSTEM_ENTRY_DATE_OVERRIDE.set(Boolean.TRUE);
            } else {
                SYSTEM_ENTRY_DATE_OVERRIDE.remove();
            }
        }
    }

    private boolean hasEntryDateOverrideAuthority() {
        if (Boolean.TRUE.equals(SYSTEM_ENTRY_DATE_OVERRIDE.get())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private LocalDate currentDate(Company company) {
        return companyClock.today(company);
    }

    private void logAuditSuccessAfterCommit(AuditEvent event, Map<String, String> metadata) {
        if (event == null || !shouldEmitAuditServiceSuccessEvent(event)) {
            return;
        }
        Map<String, String> capturedMetadata = metadata != null ? new HashMap<>(metadata) : null;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    auditService.logSuccess(event, capturedMetadata);
                }
            });
            return;
        }
        auditService.logSuccess(event, capturedMetadata);
    }

    private boolean shouldEmitAuditServiceSuccessEvent(AuditEvent event) {
        return event != AuditEvent.JOURNAL_ENTRY_POSTED
                && event != AuditEvent.JOURNAL_ENTRY_REVERSED
                && event != AuditEvent.SETTLEMENT_RECORDED;
    }

    /**
     * Build comprehensive audit reason for reversal entries
     */
    private String buildAuditReason(JournalEntryReversalRequest request, JournalEntry originalEntry) {
        if (request == null) {
            return "Adjustment";
        }
        StringBuilder reason = new StringBuilder();
        
        // Add reason code if provided
        if (request.reasonCode() != null) {
            reason.append("[").append(request.reasonCode().name()).append("] ");
        }
        
        // Add custom reason text
        if (StringUtils.hasText(request.reason())) {
            reason.append(request.reason().trim());
        } else {
            reason.append("Reversal of ").append(originalEntry.getReferenceNumber());
        }
        
        // Add partial reversal info
        if (request.isPartialReversal()) {
            reason.append(" (").append(request.getEffectivePercentage()).append("% partial reversal)");
        }
        
        // Add approval info for audit trail
        if (StringUtils.hasText(request.approvedBy())) {
            reason.append(" | Approved by: ").append(request.approvedBy());
        }
        
        // Add supporting document reference
        if (StringUtils.hasText(request.supportingDocumentRef())) {
            reason.append(" | Doc: ").append(request.supportingDocumentRef());
        }
        
        return reason.toString();
    }
    
    /**
     * Cascade reverse related journal entries (COGS, tax, payments linked to the original)
     * 
     * Related entries are found by:
     * 1. Reference number pattern: INV-001 finds INV-001-COGS, INV-001-TAX (same base ref)
     * 2. Explicitly listed entry IDs in request.relatedEntryIds()
     */
    @Transactional
    public List<JournalEntryDto> cascadeReverseRelatedEntries(Long primaryEntryId, JournalEntryReversalRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Reversal request is required for cascade reversal");
        }
        Company company = companyContextService.requireCurrentCompany();
        JournalEntry primaryEntry = companyEntityLookup.requireJournalEntry(company, primaryEntryId);
        List<JournalEntryDto> reversedEntries = new java.util.ArrayList<>();
        java.util.Set<Long> processedIds = new java.util.HashSet<>();
        
        // First reverse the primary entry
        JournalEntryDto primaryReversal = reverseJournalEntry(primaryEntryId, request);
        reversedEntries.add(primaryReversal);
        processedIds.add(primaryEntryId);
        if (primaryReversal != null && primaryReversal.id() != null) {
            processedIds.add(primaryReversal.id());
        }
        
        // Find related entries by EXACT reference prefix (not just first segment)
        // e.g., "INV-001" finds "INV-001-COGS", "INV-001-TAX" but NOT "INV-002"
        String baseRef = primaryEntry.getReferenceNumber();
        List<JournalEntry> relatedEntries = journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(
                company, baseRef + "-"); // Append hyphen to ensure exact prefix match
        
        String cascadeReason = StringUtils.hasText(request.reason()) 
                ? "Cascade reversal: " + request.reason() 
                : "Cascade reversal of " + baseRef;
        
        for (JournalEntry related : relatedEntries) {
            if (processedIds.contains(related.getId())) {
                continue; // Skip already processed
            }
            if (related.getReversalOf() != null) {
                processedIds.add(related.getId());
                continue; // Skip reversal entries to avoid reversing reversals
            }
            if (!"REVERSED".equalsIgnoreCase(related.getStatus()) &&
                !"VOIDED".equalsIgnoreCase(related.getStatus())) {
                try {
                    JournalEntryDto relatedReversal = reverseJournalEntry(related.getId(), 
                            new JournalEntryReversalRequest(
                                    request.reversalDate(),
                                    request.voidOnly(),
                                    cascadeReason,
                                    "Cascade from " + baseRef,
                                    request.adminOverride(),
                                    request.reversalPercentage(),
                                    false, null,
                                    request.reasonCode(),
                                    request.approvedBy(),
                                    request.supportingDocumentRef()
                            ));
                    reversedEntries.add(relatedReversal);
                    processedIds.add(related.getId());
                } catch (ApplicationException e) {
                    throw e.withDetail("cascadePrimaryEntryId", primaryEntryId)
                            .withDetail("cascadePrimaryReference", baseRef)
                            .withDetail("cascadeRelatedEntryId", related.getId())
                            .withDetail("cascadeRelatedReference", related.getReferenceNumber());
                }
            }
        }
        
        // Also reverse explicitly listed related entries (avoid duplicates)
        if (request.relatedEntryIds() != null) {
            for (Long relatedId : request.relatedEntryIds()) {
                if (processedIds.contains(relatedId)) {
                    continue; // Skip already processed
                }
                try {
                    // Validate the entry exists and is reversible
                    JournalEntry relatedEntry = companyEntityLookup.requireJournalEntry(company, relatedId);
                    if ("REVERSED".equalsIgnoreCase(relatedEntry.getStatus()) ||
                        "VOIDED".equalsIgnoreCase(relatedEntry.getStatus())) {
                        log.info("Skipping already reversed/voided entry {}", relatedId);
                        processedIds.add(relatedId);
                        continue;
                    }
                    JournalEntryDto relatedReversal = reverseJournalEntry(relatedId, request);
                    reversedEntries.add(relatedReversal);
                    processedIds.add(relatedId);
                } catch (ApplicationException e) {
                    throw e.withDetail("cascadePrimaryEntryId", primaryEntryId)
                            .withDetail("cascadePrimaryReference", baseRef)
                            .withDetail("cascadeRelatedEntryId", relatedId);
                }
            }
        }
        
        log.info("Cascade reversal complete: {} entries reversed for primary entry {}", 
                reversedEntries.size(), baseRef);
        return reversedEntries;
    }

    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }

    private void publishAccountCacheInvalidated(Long companyId) {
        if (companyId == null) {
            return;
        }
        eventPublisher.publishEvent(new AccountCacheInvalidatedEvent(companyId));
    }

    private boolean recordJournalEntryPostedEventSafe(JournalEntry journalEntry, Map<Long, BigDecimal> balancesBefore) {
        if (journalEntry == null) {
            return true;
        }
        validatePostedEventPayloadCompatibility(journalEntry);
        try {
            Map<Long, BigDecimal> snapshot = balancesBefore != null ? new HashMap<>(balancesBefore) : Map.of();
            accountingEventStore.recordJournalEntryPosted(journalEntry, snapshot);
            return true;
        } catch (Exception ex) {
            handleAccountingEventTrailFailure("JOURNAL_ENTRY_POSTED", journalEntry.getReferenceNumber(), ex);
            return false;
        }
    }

    private void recordJournalEntryReversedEventSafe(JournalEntry original, JournalEntry reversal, String reason) {
        if (original == null || reversal == null) {
            return;
        }
        validateReversalEventPayloadCompatibility(original, reason);
        try {
            accountingEventStore.recordJournalEntryReversed(original, reversal, reason);
        } catch (Exception ex) {
            handleAccountingEventTrailFailure("JOURNAL_ENTRY_REVERSED", original.getReferenceNumber(), ex);
        }
    }

    private void handleAccountingEventTrailFailure(String operation, String journalReference, Exception ex) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("eventTrailOperation", operation);
        String policy = strictAccountingEventTrail ? "STRICT" : "BEST_EFFORT";
        metadata.put("policy", policy);
        if (StringUtils.hasText(journalReference)) {
            metadata.put("journalReference", journalReference);
        }
        String failureCode = AccountingEventTrailAlertRoutingPolicy.ACCOUNTING_EVENT_TRAIL_FAILURE_CODE;
        String errorCategory = classifyEventTrailFailure(ex);
        IntegrationFailureMetadataSchema.applyRequiredFields(
                metadata,
                failureCode,
                errorCategory,
                AccountingEventTrailAlertRoutingPolicy.ROUTING_VERSION,
                AccountingEventTrailAlertRoutingPolicy.resolveRoute(failureCode, errorCategory, policy));
        metadata.put("errorType", ex.getClass().getSimpleName());
        try {
            auditService.logFailure(AuditEvent.INTEGRATION_FAILURE, metadata);
        } catch (Exception auditEx) {
            log.warn("Failed to write integration-failure audit marker for operation {} and journal {}",
                    operation,
                    journalReference,
                    auditEx);
        }

        if (strictAccountingEventTrail) {
            throw new ApplicationException(
                    ErrorCode.SYSTEM_DATABASE_ERROR,
                    "Accounting event trail persistence failed",
                    ex)
                    .withDetail("eventTrailOperation", operation)
                    .withDetail("journalReference", journalReference);
        }

        log.warn("Accounting event trail persistence failed for operation {} and journal {} (best-effort policy)",
                operation,
                journalReference,
                ex);
    }

    private void validatePostedEventPayloadCompatibility(JournalEntry journalEntry) {
        ensureEventFieldWithinLimit(
                "journalReference",
                journalEntry.getReferenceNumber(),
                ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH,
                "JOURNAL_ENTRY_POSTED");
        ensureEventFieldWithinLimit(
                "journalMemo",
                journalEntry.getMemo(),
                ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
                "JOURNAL_ENTRY_POSTED");
        if (journalEntry.getLines() == null || journalEntry.getLines().isEmpty()) {
            return;
        }
        for (JournalLine line : journalEntry.getLines()) {
            if (line == null) {
                continue;
            }
            Account account = line.getAccount();
            if (account != null) {
                ensureEventFieldWithinLimit(
                        "accountCode",
                        account.getCode(),
                        ACCOUNTING_EVENT_ACCOUNT_CODE_MAX_LENGTH,
                        "JOURNAL_ENTRY_POSTED");
            }
            ensureEventFieldWithinLimit(
                    "journalLineDescription",
                    line.getDescription(),
                    ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
                    "JOURNAL_ENTRY_POSTED");
        }
    }

    private void validateReversalEventPayloadCompatibility(JournalEntry original, String reason) {
        ensureEventFieldWithinLimit(
                "journalReference",
                original.getReferenceNumber(),
                ACCOUNTING_EVENT_JOURNAL_REFERENCE_MAX_LENGTH,
                "JOURNAL_ENTRY_REVERSED");
        ensureEventFieldWithinLimit(
                "reversalReason",
                reason,
                ACCOUNTING_EVENT_DESCRIPTION_MAX_LENGTH,
                "JOURNAL_ENTRY_REVERSED");
    }

    private void ensureEventFieldWithinLimit(String field,
                                             String value,
                                             int maxLength,
                                             String operation) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return;
        }
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Accounting event-trail field exceeds allowed length")
                .withDetail("eventTrailOperation", operation)
                .withDetail("field", field)
                .withDetail("maxLength", maxLength)
                .withDetail("actualLength", normalized.length());
    }

    private String classifyEventTrailFailure(Exception ex) {
        if (ex instanceof ApplicationException appEx) {
            return classifyApplicationEventTrailFailure(appEx.getErrorCode());
        }
        if (ex instanceof IllegalArgumentException) {
            return "VALIDATION";
        }
        if (ex instanceof DataIntegrityViolationException) {
            return "DATA_INTEGRITY";
        }
        return "PERSISTENCE";
    }

    private String classifyApplicationEventTrailFailure(ErrorCode errorCode) {
        if (errorCode == null) {
            return "PERSISTENCE";
        }
        if (isValidationError(errorCode)) {
            return "VALIDATION";
        }
        if (isDataIntegrityError(errorCode)) {
            return "DATA_INTEGRITY";
        }
        return "PERSISTENCE";
    }

    private boolean isValidationError(ErrorCode errorCode) {
        return errorCode.name().startsWith("VALIDATION_");
    }

    private boolean isDataIntegrityError(ErrorCode errorCode) {
        return switch (errorCode) {
            case CONCURRENCY_CONFLICT, CONCURRENCY_LOCK_TIMEOUT, INTERNAL_CONCURRENCY_FAILURE, DUPLICATE_ENTITY -> true;
            default -> false;
        };
    }

    private void ensureDuplicateMatchesExisting(JournalEntry existing,
                                                JournalEntry candidate,
                                                List<JournalLine> candidateLines) {
        List<String> mismatches = new ArrayList<>();
        List<String> partnerMismatchTypes = new ArrayList<>();
        if (!Objects.equals(existing.getEntryDate(), candidate.getEntryDate())) {
            mismatches.add("entryDate");
        }
        if (!Objects.equals(existing.getDealer() != null ? existing.getDealer().getId() : null,
                candidate.getDealer() != null ? candidate.getDealer().getId() : null)) {
            mismatches.add(partnerFieldLabel(PartnerType.DEALER));
            partnerMismatchTypes.add(PartnerType.DEALER.name());
        }
        if (!Objects.equals(existing.getSupplier() != null ? existing.getSupplier().getId() : null,
                candidate.getSupplier() != null ? candidate.getSupplier().getId() : null)) {
            mismatches.add(partnerFieldLabel(PartnerType.SUPPLIER));
            partnerMismatchTypes.add(PartnerType.SUPPLIER.name());
        }
        if (!sameCurrency(existing.getCurrency(), candidate.getCurrency())) {
            mismatches.add("currency");
        }
        if (!sameFxRate(existing.getFxRate(), candidate.getFxRate())) {
            mismatches.add("fxRate");
        }
        if (StringUtils.hasText(candidate.getMemo()) && !Objects.equals(existing.getMemo(), candidate.getMemo())) {
            mismatches.add("memo");
        }
        if (!lineSignatureCounts(existing.getLines()).equals(lineSignatureCounts(candidateLines))) {
            mismatches.add("lines");
        }
        if (!mismatches.isEmpty()) {
            ApplicationException exception = new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Journal entry reference already exists with different details")
                    .withDetail("reference", existing.getReferenceNumber())
                    .withDetail("mismatches", mismatches);
            if (!partnerMismatchTypes.isEmpty()) {
                exception.withDetail("partnerMismatchTypes", partnerMismatchTypes);
            }
            throw exception;
        }
    }

    private Map<JournalLineSignature, Integer> lineSignatureCounts(List<JournalLine> lines) {
        Map<JournalLineSignature, Integer> counts = new HashMap<>();
        if (lines == null) {
            return counts;
        }
        for (JournalLine line : lines) {
            if (line.getAccount() == null || line.getAccount().getId() == null) {
                continue;
            }
            JournalLineSignature signature = new JournalLineSignature(
                    line.getAccount().getId(),
                    normalizeAmount(line.getDebit()),
                    normalizeAmount(line.getCredit()));
            counts.merge(signature, 1, Integer::sum);
        }
        return counts;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean sameCurrency(String left, String right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private boolean sameFxRate(BigDecimal left, BigDecimal right) {
        BigDecimal normalizedLeft = left == null ? BigDecimal.ONE : left;
        BigDecimal normalizedRight = right == null ? BigDecimal.ONE : right;
        return normalizedLeft.compareTo(normalizedRight) == 0;
    }

    private record JournalLineSignature(Long accountId, BigDecimal debit, BigDecimal credit) {
    }

    private record DealerPaymentSignature(Long accountId, BigDecimal amount) {
    }

    private record ExistingDealerPaymentLine(DealerPaymentSignature signature, String normalizedDescription) {
    }

    private record SettlementAdjustmentSignature(String normalizedDescription, BigDecimal amount) {
    }

    private Account requireDealerReceivable(Dealer dealer) {
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer " + (dealer != null ? dealer.getName() : "unknown") + " is missing a receivable account");
        }
        return dealer.getReceivableAccount();
    }

    private Account requireSupplierPayable(Supplier supplier) {
        if (supplier == null || supplier.getPayableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Supplier " + (supplier != null ? supplier.getName() : "unknown") + " is missing a payable account");
        }
        return supplier.getPayableAccount();
    }

    private boolean isReceivableAccount(Account account) {
        if (account == null || account.getType() != AccountType.ASSET) {
            return false;
        }
        String code = normalizeToken(account.getCode());
        String name = normalizeToken(account.getName());
        return isTokenMatch(code, "AR") || name.contains("ACCOUNTS RECEIVABLE");
    }

    private boolean isPayableAccount(Account account) {
        if (account == null || account.getType() != AccountType.LIABILITY) {
            return false;
        }
        String code = normalizeToken(account.getCode());
        String name = normalizeToken(account.getName());
        return isTokenMatch(code, "AP") || name.contains("ACCOUNTS PAYABLE");
    }

    private void requireGenericSubledgerOverride(String label, JournalEntryRequest request) {
        if (!Boolean.TRUE.equals(request.adminOverride())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Posting to " + label + " requires dealer/supplier context or admin override");
        }
        String memo = request.memo();
        String normalized = normalizeToken(memo);
        String token = "GENERIC " + label + ":";
        if (!normalized.contains(token)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Generic " + label + " postings require memo to include '" + token + " <reason>'");
        }
    }

    private void applyCreditNoteToInvoice(Invoice invoice,
                                          BigDecimal creditAmount,
                                          BigDecimal totalCredited,
                                          String reference,
                                          LocalDate entryDate) {
        if (invoice == null) {
            return;
        }
        if (!StringUtils.hasText(reference)) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Credit note reference is required");
        }
        BigDecimal amount = MoneyUtils.zeroIfNull(creditAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (invoice.getPaymentReferences().contains(reference)) {
            dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
            return;
        }
        if ("VOID".equalsIgnoreCase(invoice.getStatus()) || "REVERSED".equalsIgnoreCase(invoice.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Invoice " + invoice.getInvoiceNumber() + " is void; cannot apply credit note");
        }
        BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
        BigDecimal newOutstanding = currentOutstanding.subtract(amount);
        invoice.setOutstandingAmount(newOutstanding);
        invoice.getPaymentReferences().add(reference);
        invoiceSettlementPolicy.updateStatusFromOutstanding(invoice, newOutstanding);
        BigDecimal totalAmount = MoneyUtils.zeroIfNull(invoice.getTotalAmount());
        BigDecimal credited = totalCredited != null ? totalCredited : BigDecimal.ZERO;
        if (credited.compareTo(totalAmount) >= 0 && newOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setStatus(InvoiceSettlementPolicy.InvoiceStatus.VOID.name());
        }
        dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
    }

    private void applyDebitNoteToPurchase(RawMaterialPurchase purchase,
                                          BigDecimal debitAmount,
                                          BigDecimal totalDebited) {
        if (purchase == null) {
            return;
        }
        BigDecimal amount = MoneyUtils.zeroIfNull(debitAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
        BigDecimal newOutstanding = currentOutstanding.subtract(amount);
        purchase.setOutstandingAmount(newOutstanding);
        BigDecimal totalAmount = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
        BigDecimal debited = totalDebited != null ? totalDebited : BigDecimal.ZERO;
        if (totalAmount.compareTo(BigDecimal.ZERO) > 0 && debited.compareTo(totalAmount) >= 0) {
            purchase.setStatus("VOID");
        } else {
            updatePurchaseStatus(purchase);
        }
    }

    void updatePurchaseStatus(RawMaterialPurchase purchase) {
        if (purchase == null) {
            return;
        }
        String status = purchase.getStatus();
        if (status != null && ("VOID".equalsIgnoreCase(status) || "REVERSED".equalsIgnoreCase(status))) {
            return;
        }
        BigDecimal total = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
        BigDecimal outstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            purchase.setStatus("PAID");
        } else if (total.compareTo(BigDecimal.ZERO) > 0 && outstanding.compareTo(total) < 0) {
            purchase.setStatus("PARTIAL");
        } else {
            purchase.setStatus("POSTED");
        }
    }

    private boolean isTokenMatch(String value, String token) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.equals(token) || value.startsWith(token + "-") || value.endsWith("-" + token) || value.contains("-" + token + "-");
    }

    private String normalizeToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String buildDealerReceiptReference(Company company, Dealer dealer, DealerReceiptRequest request) {
        String dealerToken = sanitizeToken(dealer != null ? dealer.getCode() : null);
        if (request == null) {
            return referenceNumberService.nextJournalReference(company);
        }
        StringBuilder fingerprint = new StringBuilder();
        appendPartnerFingerprint(fingerprint, PartnerType.DEALER, dealer != null ? dealer.getId() : null);
        fingerprint.append("|cashAccountId=").append(request.cashAccountId() != null ? request.cashAccountId() : "null")
                .append("|amount=").append(normalizeDecimal(request.amount()));
        List<SettlementAllocationRequest> allocations = request.allocations() != null
                ? request.allocations().stream()
                .sorted(Comparator.comparing(SettlementAllocationRequest::invoiceId, Comparator.nullsLast(Long::compareTo)))
                .toList()
                : List.of();
        for (SettlementAllocationRequest allocation : allocations) {
            fingerprint.append("|inv=").append(allocation.invoiceId() != null ? allocation.invoiceId() : "null")
                    .append(":").append(normalizeDecimal(allocation.appliedAmount()));
        }
        String hash = sha256Hex(fingerprint.toString(), 12);
        return "RCPT-%s-%s".formatted(dealerToken, hash);
    }

    private String buildDealerReceiptReference(Company company, Dealer dealer, DealerReceiptSplitRequest request) {
        String dealerToken = sanitizeToken(dealer != null ? dealer.getCode() : null);
        if (request == null) {
            return referenceNumberService.nextJournalReference(company);
        }
        StringBuilder fingerprint = new StringBuilder();
        appendPartnerFingerprint(fingerprint, PartnerType.DEALER, dealer != null ? dealer.getId() : null);
        List<DealerReceiptSplitRequest.IncomingLine> lines = request.incomingLines() != null
                ? request.incomingLines().stream()
                .sorted(Comparator.comparing(DealerReceiptSplitRequest.IncomingLine::accountId, Comparator.nullsLast(Long::compareTo)))
                .toList()
                : List.of();
        for (DealerReceiptSplitRequest.IncomingLine line : lines) {
            fingerprint.append("|acc=").append(line.accountId() != null ? line.accountId() : "null")
                    .append(":").append(normalizeDecimal(line.amount()));
        }
        String hash = sha256Hex(fingerprint.toString(), 12);
        return "RCPT-%s-%s".formatted(dealerToken, hash);
    }

    private String buildDealerSettlementIdempotencyKey(DealerSettlementRequest request) {
        Comparator<SettlementPaymentRequest> paymentComparator = Comparator
                .comparing(SettlementPaymentRequest::accountId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(SettlementPaymentRequest::amount, Comparator.nullsLast(BigDecimal::compareTo));
        return buildDealerSettlementIdempotencyKey(request, paymentComparator, true);
    }

    private String buildLegacyDealerSettlementIdempotencyKey(DealerSettlementRequest request) {
        Comparator<SettlementPaymentRequest> paymentComparator = Comparator
                .comparing(SettlementPaymentRequest::accountId, Comparator.nullsLast(Long::compareTo));
        return buildDealerSettlementIdempotencyKey(request, paymentComparator, false);
    }

    private String buildDealerSettlementIdempotencyKey(DealerSettlementRequest request,
                                                       Comparator<SettlementPaymentRequest> paymentComparator,
                                                       boolean includeImplicitCashFallback) {
        List<SettlementPaymentRequest> orderedPayments =
                orderedDealerSettlementPaymentsForFingerprint(request, paymentComparator, includeImplicitCashFallback);
        return buildDealerSettlementIdempotencyKey(request, orderedPayments);
    }

    private List<SettlementPaymentRequest> orderedDealerSettlementPaymentsForFingerprint(
            DealerSettlementRequest request,
            Comparator<SettlementPaymentRequest> paymentComparator,
            boolean includeImplicitCashFallback) {
        if (request == null) {
            return List.of();
        }
        if (request.payments() != null && !request.payments().isEmpty()) {
            return request.payments().stream()
                    .sorted(paymentComparator)
                    .toList();
        }
        if (!includeImplicitCashFallback || request.cashAccountId() == null) {
            return List.of();
        }
        SettlementTotals totals = computeSettlementTotals(request.allocations());
        BigDecimal cashAmount = totals.totalApplied()
                .add(totals.totalFxGain())
                .subtract(totals.totalFxLoss())
                .subtract(totals.totalDiscount())
                .subtract(totals.totalWriteOff());
        if (cashAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        // Include implicit single-tender cash mode in canonical fingerprint to bind account mapping.
        return List.of(new SettlementPaymentRequest(request.cashAccountId(), cashAmount, "AUTO"));
    }

    private String buildDealerSettlementIdempotencyKey(DealerSettlementRequest request,
                                                       List<SettlementPaymentRequest> orderedPayments) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        StringBuilder fingerprint = new StringBuilder();
        appendPartnerFingerprint(fingerprint, PartnerType.DEALER, request.dealerId());
        List<SettlementAllocationRequest> allocations = request.allocations() != null
                ? request.allocations().stream()
                .sorted(Comparator.comparing(SettlementAllocationRequest::invoiceId, Comparator.nullsLast(Long::compareTo)))
                .toList()
                : List.of();
        for (SettlementAllocationRequest allocation : allocations) {
            fingerprint.append("|inv=").append(allocation.invoiceId() != null ? allocation.invoiceId() : "null")
                    .append(":").append(normalizeDecimal(allocation.appliedAmount()))
                    .append(":disc=").append(normalizeDecimal(allocation.discountAmount()))
                    .append(":woff=").append(normalizeDecimal(allocation.writeOffAmount()))
                    .append(":fx=").append(normalizeDecimal(allocation.fxAdjustment()));
        }
        for (SettlementPaymentRequest payment : orderedPayments) {
            fingerprint.append("|pay=").append(payment.accountId() != null ? payment.accountId() : "null")
                    .append(":").append(normalizeDecimal(payment.amount()));
        }
        String hash = sha256Hex(fingerprint.toString(), 12);
        return "DEALER-SETTLEMENT-" + hash;
    }

    private String buildSupplierSettlementIdempotencyKey(SupplierSettlementRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        StringBuilder fingerprint = new StringBuilder();
        appendPartnerFingerprint(fingerprint, PartnerType.SUPPLIER, request.supplierId());
        fingerprint.append("|cashAccountId=").append(request.cashAccountId() != null ? request.cashAccountId() : "null");
        List<SettlementAllocationRequest> allocations = request.allocations() != null
                ? request.allocations().stream()
                .sorted(Comparator.comparing(SettlementAllocationRequest::purchaseId, Comparator.nullsLast(Long::compareTo)))
                .toList()
                : List.of();
        for (SettlementAllocationRequest allocation : allocations) {
            fingerprint.append("|pur=").append(allocation.purchaseId() != null ? allocation.purchaseId() : "null")
                    .append(":").append(normalizeDecimal(allocation.appliedAmount()))
                    .append(":disc=").append(normalizeDecimal(allocation.discountAmount()))
                    .append(":woff=").append(normalizeDecimal(allocation.writeOffAmount()))
                    .append(":fx=").append(normalizeDecimal(allocation.fxAdjustment()));
        }
        String hash = sha256Hex(fingerprint.toString(), 12);
        return "SUPPLIER-SETTLEMENT-" + hash;
    }

    private String normalizeDecimal(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private void appendPartnerFingerprint(StringBuilder fingerprint, PartnerType partnerType, Long partnerId) {
        fingerprint.append(partnerFieldLabel(partnerType))
                .append("=")
                .append(partnerId != null ? partnerId : "null");
    }

    private String partnerFieldLabel(PartnerType partnerType) {
        if (partnerType == PartnerType.DEALER) {
            return "dealerId";
        }
        if (partnerType == PartnerType.SUPPLIER) {
            return "supplierId";
        }
        return "partnerId";
    }

    private String sanitizeToken(String value) {
        String normalized = normalizeToken(value).replaceAll("[^A-Z0-9]", "");
        if (normalized.isBlank()) {
            return "TOKEN";
        }
        return normalized.length() > 16 ? normalized.substring(0, 16) : normalized;
    }

    private String sha256Hex(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String fullHex = java.util.HexFormat.of().formatHex(hash);
            return fullHex.substring(0, Math.min(length, fullHex.length()));
        } catch (Exception ex) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String resolveJournalReference(Company company, String provided) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        return referenceNumberService.nextJournalReference(company);
    }

    private String resolveCurrency(String requested, Company company) {
        String base = company != null && StringUtils.hasText(company.getBaseCurrency())
                ? company.getBaseCurrency().trim().toUpperCase()
                : "INR";
        if (!StringUtils.hasText(requested)) {
            return base;
        }
        return requested.trim().toUpperCase();
    }

    private BigDecimal resolveFxRate(String currency, Company company, BigDecimal requestedRate) {
        String base = company != null && StringUtils.hasText(company.getBaseCurrency())
                ? company.getBaseCurrency().trim().toUpperCase()
                : "INR";
        if (!StringUtils.hasText(currency) || currency.equalsIgnoreCase(base)) {
            return BigDecimal.ONE;
        }
        if (requestedRate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "FX rate is required for currency " + currency);
        }
        BigDecimal rate = requestedRate;
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "FX rate must be positive");
        }
        if (rate.compareTo(FX_RATE_MIN) < 0 || rate.compareTo(FX_RATE_MAX) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "FX rate out of bounds")
                    .withDetail("min", FX_RATE_MIN)
                    .withDetail("max", FX_RATE_MAX)
                    .withDetail("requested", rate);
        }
        return rate.setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal toBaseCurrency(BigDecimal amount, BigDecimal fxRate) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = fxRate == null ? BigDecimal.ONE : fxRate;
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal roundCurrency(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateEntryTotal(JournalEntry entry) {
        if (entry == null || entry.getLines() == null || entry.getLines().isEmpty()) {
            return BigDecimal.ZERO;
        }
        return entry.getLines().stream()
                .map(line -> MoneyUtils.zeroIfNull(line.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateCreditNoteAmount(JournalEntry entry, Invoice invoice, JournalEntry source) {
        if (entry == null || entry.getLines() == null || entry.getLines().isEmpty()) {
            return BigDecimal.ZERO;
        }
        Long receivableAccountId = resolveReceivableAccountId(invoice, source);
        if (receivableAccountId == null) {
            return calculateEntryTotal(entry);
        }
        BigDecimal receivableCredit = entry.getLines().stream()
                .filter(line -> line.getAccount() != null
                        && Objects.equals(line.getAccount().getId(), receivableAccountId))
                .map(line -> MoneyUtils.zeroIfNull(line.getCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (receivableCredit.compareTo(BigDecimal.ZERO) > 0) {
            return receivableCredit;
        }
        return calculateEntryTotal(entry);
    }

    private BigDecimal totalCreditNoteAmount(Company company, JournalEntry source, Invoice invoice) {
        if (company == null || source == null) {
            return BigDecimal.ZERO;
        }
        List<JournalEntry> notes = journalEntryRepository
                .findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(company, source, "CREDIT_NOTE");
        return notes.stream()
                .map(note -> calculateCreditNoteAmount(note, invoice, source))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Long resolveReceivableAccountId(Invoice invoice, JournalEntry source) {
        if (invoice != null
                && invoice.getDealer() != null
                && invoice.getDealer().getReceivableAccount() != null
                && invoice.getDealer().getReceivableAccount().getId() != null) {
            return invoice.getDealer().getReceivableAccount().getId();
        }
        if (source != null
                && source.getDealer() != null
                && source.getDealer().getReceivableAccount() != null
                && source.getDealer().getReceivableAccount().getId() != null) {
            return source.getDealer().getReceivableAccount().getId();
        }
        return null;
    }

    private BigDecimal totalNoteAmount(Company company, JournalEntry source, String reason) {
        if (company == null || source == null || !StringUtils.hasText(reason)) {
            return BigDecimal.ZERO;
        }
        List<JournalEntry> notes = journalEntryRepository
                .findByCompanyAndReversalOfAndCorrectionReasonIgnoreCase(company, source, reason);
        return notes.stream()
                .map(this::calculateEntryTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<JournalEntryRequest.JournalLineRequest> buildScaledReversalLines(JournalEntry source,
                                                                                 BigDecimal ratio,
                                                                                 String prefix) {
        BigDecimal factor = ratio != null ? ratio : BigDecimal.ONE;
        String resolvedPrefix = prefix != null ? prefix : "";
        return source.getLines().stream()
                .map(line -> {
                    BigDecimal scaledDebit = roundCurrency(MoneyUtils.zeroIfNull(line.getDebit()).multiply(factor));
                    BigDecimal scaledCredit = roundCurrency(MoneyUtils.zeroIfNull(line.getCredit()).multiply(factor));
                    return new JournalEntryRequest.JournalLineRequest(
                            line.getAccount().getId(),
                            resolvedPrefix + line.getDescription(),
                            scaledCredit,
                            scaledDebit
                    );
                })
                .toList();
    }

    /* Credit/Debit Notes */
    @Transactional
    public JournalEntryDto postCreditNote(CreditNoteRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.lockByCompanyAndId(company, request.invoiceId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
        JournalEntry source = invoice.getJournalEntry();
        if (source == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Invoice " + invoice.getInvoiceNumber() + " has no posted journal to reverse");
        }
        String referenceNumber = StringUtils.hasText(request.referenceNumber()) ? request.referenceNumber().trim() : null;
        String idempotencyKey = resolveReceiptIdempotencyKey(request.idempotencyKey(), referenceNumber, "credit note");
        String reference = StringUtils.hasText(referenceNumber) ? referenceNumber : idempotencyKey;
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        JournalEntry existingEntry = findExistingEntry(company, reference, idempotencyKey);
        if (existingEntry != null) {
            BigDecimal existingAmount = calculateCreditNoteAmount(existingEntry, invoice, source);
            BigDecimal totalCredited = totalCreditNoteAmount(company, source, invoice);
            validateCreditNoteIdempotency(idempotencyKey, invoice, source, existingEntry, request.amount(), invoice.getTotalAmount());
            applyCreditNoteToInvoice(invoice, existingAmount, totalCredited, existingEntry.getReferenceNumber(), entryDate);
            return toDto(existingEntry);
        }
        BigDecimal totalAmount = MoneyUtils.zeroIfNull(invoice.getTotalAmount());
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Credit note amount must be positive");
        }
        BigDecimal creditedSoFar = totalCreditNoteAmount(company, source, invoice);
        BigDecimal remaining = totalAmount.subtract(creditedSoFar);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Invoice " + invoice.getInvoiceNumber() + " is already fully credited");
        }
        BigDecimal requestedAmount = request.amount() != null ? request.amount() : remaining;
        BigDecimal creditAmount = MoneyUtils.zeroIfNull(requestedAmount);
        if (creditAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Credit note amount must be positive");
        }
        if (creditAmount.compareTo(remaining) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Credit note exceeds remaining invoice amount")
                    .withDetail("remaining", remaining)
                    .withDetail("requested", creditAmount);
        }
        IdempotencyReservation reservation = reserveReferenceMapping(company, idempotencyKey, reference, ENTITY_TYPE_CREDIT_NOTE);
        if (!reservation.leader()) {
            JournalEntry awaited = awaitJournalEntry(company, reference, idempotencyKey);
            if (awaited != null) {
                BigDecimal existingAmount = calculateCreditNoteAmount(awaited, invoice, source);
                BigDecimal totalCredited = totalCreditNoteAmount(company, source, invoice);
                validateCreditNoteIdempotency(idempotencyKey, invoice, source, awaited, request.amount(), invoice.getTotalAmount());
                applyCreditNoteToInvoice(invoice, existingAmount, totalCredited, awaited.getReferenceNumber(), entryDate);
                return toDto(awaited);
            }
            throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                    "Credit note idempotency key is reserved but journal entry not found")
                    .withDetail(IntegrationFailureMetadataSchema.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Credit note for invoice " + invoice.getInvoiceNumber();
        BigDecimal ratio = creditAmount.divide(totalAmount, 6, RoundingMode.HALF_UP);
        List<JournalEntryRequest.JournalLineRequest> lines =
                buildScaledReversalLines(source, ratio, "Credit note reversal - ");

        JournalEntryDto dto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                invoice.getDealer() != null ? invoice.getDealer().getId() : null,
                null,
                request.adminOverride(),
                lines
        ));
        JournalEntry saved = companyEntityLookup.requireJournalEntry(company, dto.id());
        saved.setReversalOf(source);
        saved.setCorrectionType(JournalCorrectionType.REVERSAL);
        saved.setCorrectionReason("CREDIT_NOTE");
        journalEntryRepository.save(saved);
        linkReferenceMapping(company, idempotencyKey, saved, ENTITY_TYPE_CREDIT_NOTE);
        BigDecimal postedAmount = calculateCreditNoteAmount(saved, invoice, source);
        BigDecimal totalCredited = creditedSoFar.add(postedAmount);
        applyCreditNoteToInvoice(invoice, postedAmount, totalCredited, saved.getReferenceNumber(), entryDate);
        return toDto(saved);
    }

    @Transactional
    public JournalEntryDto postDebitNote(DebitNoteRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = rawMaterialPurchaseRepository.lockByCompanyAndId(company, request.purchaseId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material purchase not found"));
        JournalEntry source = purchase.getJournalEntry();
        if (source == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Purchase " + purchase.getInvoiceNumber() + " has no posted journal to reverse");
        }
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            BigDecimal existingAmount = calculateEntryTotal(existing.get());
            BigDecimal totalDebited = totalNoteAmount(company, source, "DEBIT_NOTE");
            applyDebitNoteToPurchase(purchase, existingAmount, totalDebited);
            return toDto(existing.get());
        }
        if ("VOID".equalsIgnoreCase(purchase.getStatus()) || "REVERSED".equalsIgnoreCase(purchase.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Purchase " + purchase.getInvoiceNumber() + " is void; cannot apply debit note");
        }
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        BigDecimal totalAmount = MoneyUtils.zeroIfNull(purchase.getTotalAmount());
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit note amount must be positive");
        }
        BigDecimal debitedSoFar = totalNoteAmount(company, source, "DEBIT_NOTE");
        BigDecimal remaining = totalAmount.subtract(debitedSoFar);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Purchase " + purchase.getInvoiceNumber() + " is already fully credited");
        }
        BigDecimal requestedAmount = request.amount() != null ? request.amount() : remaining;
        BigDecimal debitAmount = MoneyUtils.zeroIfNull(requestedAmount);
        if (debitAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit note amount must be positive");
        }
        if (debitAmount.compareTo(remaining) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Debit note exceeds remaining purchase amount")
                    .withDetail("remaining", remaining)
                    .withDetail("requested", debitAmount);
        }
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Debit note for purchase " + purchase.getInvoiceNumber();
        BigDecimal ratio = debitAmount.divide(totalAmount, 6, RoundingMode.HALF_UP);
        List<JournalEntryRequest.JournalLineRequest> lines =
                buildScaledReversalLines(source, ratio, "Debit note reversal - ");

        JournalEntryDto dto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                purchase.getSupplier() != null ? purchase.getSupplier().getId() : null,
                request.adminOverride(),
                lines
        ));
        JournalEntry saved = companyEntityLookup.requireJournalEntry(company, dto.id());
        saved.setReversalOf(source);
        saved.setCorrectionType(JournalCorrectionType.REVERSAL);
        saved.setCorrectionReason("DEBIT_NOTE");
        journalEntryRepository.save(saved);
        BigDecimal postedAmount = calculateEntryTotal(saved);
        BigDecimal totalDebited = debitedSoFar.add(postedAmount);
        applyDebitNoteToPurchase(purchase, postedAmount, totalDebited);
        return toDto(saved);
    }

    /* Accruals / Provisions */
    @Transactional
    public JournalEntryDto postAccrual(AccrualRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Account debit = requireAccount(company, request.debitAccountId());
        Account credit = requireAccount(company, request.creditAccountId());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Accrual/Provision";
        BigDecimal amount = request.amount();

        JournalEntryDto accrual = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(
                        new JournalEntryRequest.JournalLineRequest(debit.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(credit.getId(), memo, BigDecimal.ZERO, amount)
                )
        ));

        if (request.autoReverseDate() != null) {
            JournalEntryDto reversal = createJournalEntry(new JournalEntryRequest(
                    reference + "-REV",
                    request.autoReverseDate(),
                    "Reversal of " + reference,
                    null,
                    null,
                    request.adminOverride(),
                    List.of(
                            new JournalEntryRequest.JournalLineRequest(credit.getId(), "Auto-reverse " + memo, amount, BigDecimal.ZERO),
                            new JournalEntryRequest.JournalLineRequest(debit.getId(), "Auto-reverse " + memo, BigDecimal.ZERO, amount)
                    )
            ));
            JournalEntry accrualJe = companyEntityLookup.requireJournalEntry(company, accrual.id());
            JournalEntry reversalJe = companyEntityLookup.requireJournalEntry(company, reversal.id());
            reversalJe.setReversalOf(accrualJe);
            reversalJe.setCorrectionType(JournalCorrectionType.REVERSAL);
            reversalJe.setCorrectionReason("AUTO_REVERSAL");
            journalEntryRepository.save(reversalJe);
        }
        return accrual;
    }

    /* Bad debt write-off */
    @Transactional
    public JournalEntryDto writeOffBadDebt(BadDebtWriteOffRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = invoiceRepository.lockByCompanyAndId(company, request.invoiceId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
        Dealer dealer = invoice.getDealer();
        Account ar = requireDealerReceivable(dealer);
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            JournalEntry existingEntry = existing.get();
            BigDecimal postedAmount = calculateEntryTotal(existingEntry);
            applyBadDebtSettlement(invoice, postedAmount, reference, existingEntry.getEntryDate());
            return toDto(existingEntry);
        }
        Account expense = requireAccount(company, request.expenseAccountId());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Bad debt write-off for invoice " + invoice.getInvoiceNumber();
        BigDecimal outstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
        BigDecimal amount = request.amount() != null ? request.amount() : outstanding;
        amount = requirePositive(amount, "amount");
        if (amount.compareTo(outstanding) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Bad debt write-off exceeds invoice outstanding amount")
                    .withDetail("outstanding", outstanding)
                    .withDetail("requested", amount);
        }

        JournalEntryDto je = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                dealer.getId(),
                null,
                request.adminOverride(),
                List.of(
                        new JournalEntryRequest.JournalLineRequest(expense.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(ar.getId(), memo, BigDecimal.ZERO, amount)
                )
        ));
        JournalEntry saved = companyEntityLookup.requireJournalEntry(company, je.id());
        BigDecimal postedAmount = calculateEntryTotal(saved);
        applyBadDebtSettlement(invoice, postedAmount, reference, entryDate);
        return je;
    }

    private void applyBadDebtSettlement(Invoice invoice, BigDecimal amount, String reference, LocalDate entryDate) {
        if (invoice == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String settlementRef = reference + "-BADDEBT";
        invoiceSettlementPolicy.applySettlement(invoice, amount, settlementRef);
        dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
        invoiceRepository.save(invoice);
    }

    /* Landed cost allocation */
    @Transactional
    public JournalEntryDto recordLandedCost(LandedCostRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = companyEntityLookup.requireRawMaterialPurchase(company, request.rawMaterialPurchaseId());
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Account inventoryAccount = requireAccount(company, request.inventoryAccountId());
        Account offsetAccount = requireAccount(company, request.offsetAccountId());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim()
                : "Landed cost for purchase " + purchase.getInvoiceNumber();
        BigDecimal amount = request.amount();
        JournalEntryDto je = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null, // landed cost is internal; keep supplier ledger untouched
                request.adminOverride(),
                List.of(
                        new JournalEntryRequest.JournalLineRequest(inventoryAccount.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(offsetAccount.getId(), memo, BigDecimal.ZERO, amount)
                )
        ));
        adjustLandedCostValuation(purchase, amount);
        return je;
    }

    /* Inventory revaluation */
    @Transactional
    public JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Account inventoryAccount = requireAccount(company, request.inventoryAccountId());
        Account revalAccount = requireAccount(company, request.revaluationAccountId());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Inventory revaluation";
        BigDecimal delta = request.deltaAmount();
        BigDecimal debit = delta.compareTo(BigDecimal.ZERO) >= 0 ? delta : BigDecimal.ZERO;
        BigDecimal credit = delta.compareTo(BigDecimal.ZERO) < 0 ? delta.abs() : BigDecimal.ZERO;

        JournalEntryDto je = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(
                        new JournalEntryRequest.JournalLineRequest(inventoryAccount.getId(), memo, debit, credit),
                        new JournalEntryRequest.JournalLineRequest(revalAccount.getId(), memo, credit, debit)
                )
        ));
        // Find batches for proportional revaluation
        List<FinishedGoodBatch> revalBatches = finishedGoodBatchRepository
                .findByCompanyAndValuationAccountId(company, inventoryAccount.getId());
        if (!revalBatches.isEmpty()) {
            revalueFinishedBatches(revalBatches, delta);
        }
        return je;
    }

    /* WIP adjustments */
    @Transactional
    public JournalEntryDto adjustWip(WipAdjustmentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        companyEntityLookup.requireProductionLog(company, request.productionLogId());
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Account wip = requireAccount(company, request.wipAccountId());
        Account inventory = requireAccount(company, request.inventoryAccountId());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "WIP adjustment";
        BigDecimal amount = request.amount();
        JournalEntryRequest.JournalLineRequest line1;
        JournalEntryRequest.JournalLineRequest line2;
        if (request.direction() == WipAdjustmentRequest.Direction.ISSUE) {
            line1 = new JournalEntryRequest.JournalLineRequest(wip.getId(), memo, amount, BigDecimal.ZERO);
            line2 = new JournalEntryRequest.JournalLineRequest(inventory.getId(), memo, BigDecimal.ZERO, amount);
        } else {
            line1 = new JournalEntryRequest.JournalLineRequest(inventory.getId(), memo, amount, BigDecimal.ZERO);
            line2 = new JournalEntryRequest.JournalLineRequest(wip.getId(), memo, BigDecimal.ZERO, amount);
        }
        return createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(line1, line2)
        ));
    }

    public AuditDigestResponse auditDigest(LocalDate from, LocalDate to) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate start = from != null ? from : currentDate(company);
        LocalDate end = to != null ? to : start;
        List<JournalEntry> entries = journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, start, end);
        List<String> digest = entries.stream()
                .map(this::buildDigestLine)
                .toList();
        String label = start.equals(end) ? start.toString() : start + " to " + end;
        return new AuditDigestResponse(label, digest);
    }

    public String auditDigestCsv(LocalDate from, LocalDate to) {
        AuditDigestResponse digest = auditDigest(from, to);
        StringBuilder sb = new StringBuilder();
        sb.append("period,reference,date,memo,entity,account,debit,credit").append("\n");
        Company company = companyContextService.requireCurrentCompany();
        LocalDate start = from != null ? from : currentDate(company);
        LocalDate end = to != null ? to : start;
        List<JournalEntry> entries = journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
                company, start, end);
        for (JournalEntry entry : entries) {
            String entity = entry.getDealer() != null ? "Dealer:" + entry.getDealer().getName()
                    : entry.getSupplier() != null ? "Supplier:" + entry.getSupplier().getName()
                    : "";
            for (JournalLine line : entry.getLines()) {
                sb.append(digest.periodLabel()).append(",")
                        .append(entry.getReferenceNumber()).append(",")
                        .append(entry.getEntryDate()).append(",")
                        .append(entry.getMemo() != null ? entry.getMemo().replace(",", " ") : "").append(",")
                        .append(entity).append(",")
                        .append(line.getAccount().getCode()).append(",")
                        .append(line.getDebit()).append(",")
                        .append(line.getCredit()).append("\n");
            }
        }
        return sb.toString();
    }

    private String buildDigestLine(JournalEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getEntryDate()).append(" ").append(entry.getReferenceNumber()).append(" ");
        if (entry.getMemo() != null) {
            sb.append(entry.getMemo());
        }
        if (entry.getDealer() != null) {
            sb.append(" [Dealer: ").append(entry.getDealer().getName()).append("]");
        }
        if (entry.getSupplier() != null) {
            sb.append(" [Supplier: ").append(entry.getSupplier().getName()).append("]");
        }
        sb.append(" | ");
        for (JournalLine line : entry.getLines()) {
            sb.append(line.getAccount().getCode())
                    .append(" Dr ").append(line.getDebit())
                    .append(" Cr ").append(line.getCredit())
                    .append("; ");
        }
        return sb.toString().trim();
    }

    private void validateSettlementIdempotencyKey(String idempotencyKey,
                                                  PartnerType partnerType,
                                                  Long partnerId,
                                                  List<PartnerSettlementAllocation> existing,
                                                  List<SettlementAllocationRequest> allocations) {
        boolean partnerMismatch = existing.stream()
                .anyMatch(row -> isSettlementAllocationPartnerMismatch(row, partnerType, partnerId));
        if (partnerMismatch) {
            throw replayConflictWithPartnerContext(
                    partnerMismatchMessage(partnerType),
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }

        Map<String, Integer> existingSignatures = allocationSignatureCountsFromRows(existing);
        Map<String, Integer> requestSignatures = allocationSignatureCountsFromRequests(allocations);
        if (!existingSignatures.equals(requestSignatures)) {
            throw replayConflictWithPartnerContext(
                    "Idempotency key already used for a different settlement payload",
                    idempotencyKey,
                    partnerType,
                    partnerId);
        }
    }

    private boolean isSettlementAllocationPartnerMismatch(PartnerSettlementAllocation row,
                                                          PartnerType partnerType,
                                                          Long partnerId) {
        if (partnerType == PartnerType.DEALER) {
            return row.getDealer() == null || !Objects.equals(row.getDealer().getId(), partnerId);
        }
        if (partnerType == PartnerType.SUPPLIER) {
            return row.getSupplier() == null || !Objects.equals(row.getSupplier().getId(), partnerId);
        }
        return true;
    }

    private Map<String, Integer> allocationSignatureCountsFromRows(List<PartnerSettlementAllocation> allocations) {
        Map<String, Integer> counts = new HashMap<>();
        for (PartnerSettlementAllocation allocation : allocations) {
            String signature = allocationSignature(
                    allocation.getInvoice() != null ? allocation.getInvoice().getId() : null,
                    allocation.getPurchase() != null ? allocation.getPurchase().getId() : null,
                    allocation.getAllocationAmount(),
                    allocation.getDiscountAmount(),
                    allocation.getWriteOffAmount(),
                    allocation.getFxDifferenceAmount(),
                    allocation.getMemo()
            );
            counts.merge(signature, 1, Integer::sum);
        }
        return counts;
    }

    private Map<String, Integer> allocationSignatureCountsFromRequests(List<SettlementAllocationRequest> allocations) {
        Map<String, Integer> counts = new HashMap<>();
        for (SettlementAllocationRequest allocation : allocations) {
            String signature = allocationSignature(
                    allocation.invoiceId(),
                    allocation.purchaseId(),
                    allocation.appliedAmount(),
                    allocation.discountAmount(),
                    allocation.writeOffAmount(),
                    allocation.fxAdjustment(),
                    allocation.memo()
            );
            counts.merge(signature, 1, Integer::sum);
        }
        return counts;
    }

    private Map<DealerPaymentSignature, Integer> dealerPaymentSignatureCountsFromRequest(DealerSettlementRequest request) {
        Map<DealerPaymentSignature, Integer> counts = new HashMap<>();
        if (request == null) {
            return counts;
        }
        if (request.payments() != null && !request.payments().isEmpty()) {
            for (SettlementPaymentRequest payment : request.payments()) {
                if (payment == null || payment.accountId() == null) {
                    continue;
                }
                BigDecimal amount = normalizeAmount(payment.amount());
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                counts.merge(new DealerPaymentSignature(payment.accountId(), amount), 1, Integer::sum);
            }
            return counts;
        }

        SettlementTotals totals = computeSettlementTotals(request.allocations());
        BigDecimal cashAmount = totals.totalApplied()
                .add(totals.totalFxGain())
                .subtract(totals.totalFxLoss())
                .subtract(totals.totalDiscount())
                .subtract(totals.totalWriteOff());
        if (cashAmount.compareTo(BigDecimal.ZERO) <= 0 || request.cashAccountId() == null) {
            return counts;
        }
        counts.merge(
                new DealerPaymentSignature(request.cashAccountId(), normalizeAmount(cashAmount)),
                1,
                Integer::sum
        );
        return counts;
    }

    private Map<DealerPaymentSignature, Integer> dealerPaymentSignatureCountsFromExistingRows(
            List<PartnerSettlementAllocation> allocations,
            Set<Long> paymentAccountIds,
            Map<DealerPaymentSignature, Integer> requestPaymentSignatures,
            Map<String, Long> requestedAdjustmentAccountIds) {
        Map<DealerPaymentSignature, Integer> counts = new HashMap<>();
        List<ExistingDealerPaymentLine> candidateLines = new ArrayList<>();
        if (allocations == null || allocations.isEmpty() || paymentAccountIds == null || paymentAccountIds.isEmpty()) {
            return counts;
        }
        JournalEntry entry = allocations.stream()
                .map(PartnerSettlementAllocation::getJournalEntry)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (entry == null || entry.getLines() == null) {
            return counts;
        }
        for (JournalLine line : entry.getLines()) {
            if (line == null || line.getAccount() == null || line.getAccount().getId() == null) {
                continue;
            }
            Long accountId = line.getAccount().getId();
            if (!paymentAccountIds.contains(accountId)) {
                continue;
            }
            BigDecimal debit = normalizeAmount(line.getDebit());
            if (debit.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Account account = line.getAccount();
            if (account.getType() != AccountType.ASSET) {
                continue;
            }
            if (isReceivableAccount(account) || isPayableAccount(account)) {
                continue;
            }
            DealerPaymentSignature signature = new DealerPaymentSignature(accountId, debit);
            counts.merge(signature, 1, Integer::sum);
            candidateLines.add(new ExistingDealerPaymentLine(signature, normalizeLineDescription(line.getDescription())));
        }
        if (requestPaymentSignatures == null || requestPaymentSignatures.isEmpty()) {
            return counts;
        }
        List<SettlementAdjustmentSignature> adjustmentSignatures = buildSettlementAdjustmentSignaturesFromRows(allocations);
        if (adjustmentSignatures.isEmpty()) {
            return counts;
        }
        List<BigDecimal> adjustmentRemovalTargets = new ArrayList<>();
        List<List<Integer>> candidateIndexesByAdjustment = new ArrayList<>();
        for (SettlementAdjustmentSignature adjustmentSignature : adjustmentSignatures) {
            Long expectedAdjustmentAccountId = requestedAdjustmentAccountIds != null
                    ? requestedAdjustmentAccountIds.get(adjustmentSignature.normalizedDescription())
                    : null;
            BigDecimal nonPaymentAmount = adjustmentDebitAmountOnNonPaymentAccounts(
                    entry,
                    paymentAccountIds,
                    adjustmentSignature.normalizedDescription(),
                    expectedAdjustmentAccountId);
            BigDecimal remaining = adjustmentSignature.amount().subtract(nonPaymentAmount);
            adjustmentRemovalTargets.add(normalizeAmount(remaining.compareTo(BigDecimal.ZERO) > 0 ? remaining : BigDecimal.ZERO));
            List<Integer> candidateIndexes = new ArrayList<>();
            for (int i = 0; i < candidateLines.size(); i++) {
                ExistingDealerPaymentLine line = candidateLines.get(i);
                if (!line.normalizedDescription().equals(adjustmentSignature.normalizedDescription())) {
                    continue;
                }
                if (expectedAdjustmentAccountId != null
                        && !Objects.equals(line.signature().accountId(), expectedAdjustmentAccountId)) {
                    continue;
                }
                candidateIndexes.add(i);
            }
            candidateIndexesByAdjustment.add(candidateIndexes);
        }
        Map<DealerPaymentSignature, Integer> workingCounts = new HashMap<>(counts);
        if (canMatchRequestSignaturesWithOptionalAdjustmentExclusions(
                0,
                adjustmentSignatures,
                adjustmentRemovalTargets,
                candidateIndexesByAdjustment,
                candidateLines,
                new HashSet<>(),
                workingCounts,
                requestPaymentSignatures)) {
            return new HashMap<>(requestPaymentSignatures);
        }
        boolean hasPositiveRemovalTarget = adjustmentRemovalTargets.stream()
                .anyMatch(amount -> amount.compareTo(BigDecimal.ZERO) > 0);
        if (hasPositiveRemovalTarget && counts.equals(requestPaymentSignatures)) {
            return new HashMap<>();
        }
        return counts;
    }

    private BigDecimal adjustmentDebitAmountOnNonPaymentAccounts(JournalEntry entry,
                                                                 Set<Long> paymentAccountIds,
                                                                 String normalizedDescription,
                                                                 Long expectedAdjustmentAccountId) {
        if (entry == null || entry.getLines() == null || !StringUtils.hasText(normalizedDescription)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JournalLine line : entry.getLines()) {
            if (line == null || line.getAccount() == null || line.getAccount().getId() == null) {
                continue;
            }
            if (paymentAccountIds != null && paymentAccountIds.contains(line.getAccount().getId())) {
                continue;
            }
            Account account = line.getAccount();
            if (expectedAdjustmentAccountId != null) {
                if (!Objects.equals(account.getId(), expectedAdjustmentAccountId)) {
                    continue;
                }
            } else if (account.getType() == AccountType.ASSET
                    && !isReceivableAccount(account)
                    && !isPayableAccount(account)) {
                // Treat non-request cash/bank-like debits as payment noise, not adjustment coverage.
                continue;
            }
            if (!normalizeLineDescription(line.getDescription()).equals(normalizedDescription)) {
                continue;
            }
            BigDecimal debit = normalizeAmount(line.getDebit());
            if (debit.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            total = total.add(debit);
        }
        return normalizeAmount(total);
    }

    private List<SettlementAdjustmentSignature> buildSettlementAdjustmentSignaturesFromRows(
            List<PartnerSettlementAllocation> allocations) {
        if (allocations == null || allocations.isEmpty()) {
            return List.of();
        }
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        BigDecimal totalFxLoss = BigDecimal.ZERO;
        for (PartnerSettlementAllocation allocation : allocations) {
            if (allocation == null) {
                continue;
            }
            totalDiscount = totalDiscount.add(normalizeAmount(allocation.getDiscountAmount()));
            totalWriteOff = totalWriteOff.add(normalizeAmount(allocation.getWriteOffAmount()));
            BigDecimal fxDifference = normalizeAmount(allocation.getFxDifferenceAmount());
            if (fxDifference.compareTo(BigDecimal.ZERO) < 0) {
                totalFxLoss = totalFxLoss.add(fxDifference.abs());
            }
        }
        List<SettlementAdjustmentSignature> signatures = new ArrayList<>();
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            signatures.add(new SettlementAdjustmentSignature(
                    SETTLEMENT_DISCOUNT_LINE_DESCRIPTION,
                    normalizeAmount(totalDiscount)));
        }
        if (totalWriteOff.compareTo(BigDecimal.ZERO) > 0) {
            signatures.add(new SettlementAdjustmentSignature(
                    SETTLEMENT_WRITE_OFF_LINE_DESCRIPTION,
                    normalizeAmount(totalWriteOff)));
        }
        if (totalFxLoss.compareTo(BigDecimal.ZERO) > 0) {
            signatures.add(new SettlementAdjustmentSignature(
                    SETTLEMENT_FX_LOSS_LINE_DESCRIPTION,
                    normalizeAmount(totalFxLoss)));
        }
        return signatures;
    }

    private Map<String, Long> requestedAdjustmentAccountIds(DealerSettlementRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, Long> ids = new HashMap<>();
        if (request.discountAccountId() != null) {
            ids.put(SETTLEMENT_DISCOUNT_LINE_DESCRIPTION, request.discountAccountId());
        }
        if (request.writeOffAccountId() != null) {
            ids.put(SETTLEMENT_WRITE_OFF_LINE_DESCRIPTION, request.writeOffAccountId());
        }
        if (request.fxLossAccountId() != null) {
            ids.put(SETTLEMENT_FX_LOSS_LINE_DESCRIPTION, request.fxLossAccountId());
        }
        return ids;
    }

    private boolean canMatchRequestSignaturesWithOptionalAdjustmentExclusions(
            int adjustmentIndex,
            List<SettlementAdjustmentSignature> adjustmentSignatures,
            List<BigDecimal> adjustmentRemovalTargets,
            List<List<Integer>> candidateIndexesByAdjustment,
            List<ExistingDealerPaymentLine> candidateLines,
            Set<Integer> removedIndexes,
            Map<DealerPaymentSignature, Integer> workingCounts,
            Map<DealerPaymentSignature, Integer> requestPaymentSignatures) {
        if (adjustmentIndex >= adjustmentSignatures.size()) {
            return workingCounts.equals(requestPaymentSignatures);
        }
        BigDecimal requiredRemoval = adjustmentRemovalTargets.get(adjustmentIndex);
        if (requiredRemoval.compareTo(BigDecimal.ZERO) == 0) {
            return canMatchRequestSignaturesWithOptionalAdjustmentExclusions(
                    adjustmentIndex + 1,
                    adjustmentSignatures,
                    adjustmentRemovalTargets,
                    candidateIndexesByAdjustment,
                    candidateLines,
                    removedIndexes,
                    workingCounts,
                    requestPaymentSignatures);
        }
        return tryMatchAdjustmentRemovalCombination(
                adjustmentIndex,
                candidateIndexesByAdjustment.get(adjustmentIndex),
                0,
                requiredRemoval,
                adjustmentSignatures,
                adjustmentRemovalTargets,
                candidateIndexesByAdjustment,
                candidateLines,
                removedIndexes,
                workingCounts,
                requestPaymentSignatures);
    }

    private boolean tryMatchAdjustmentRemovalCombination(
            int adjustmentIndex,
            List<Integer> candidateIndexes,
            int candidateOffset,
            BigDecimal remainingAmount,
            List<SettlementAdjustmentSignature> adjustmentSignatures,
            List<BigDecimal> adjustmentRemovalTargets,
            List<List<Integer>> candidateIndexesByAdjustment,
            List<ExistingDealerPaymentLine> candidateLines,
            Set<Integer> removedIndexes,
            Map<DealerPaymentSignature, Integer> workingCounts,
            Map<DealerPaymentSignature, Integer> requestPaymentSignatures) {
        if (remainingAmount.compareTo(BigDecimal.ZERO) == 0) {
            return canMatchRequestSignaturesWithOptionalAdjustmentExclusions(
                    adjustmentIndex + 1,
                    adjustmentSignatures,
                    adjustmentRemovalTargets,
                    candidateIndexesByAdjustment,
                    candidateLines,
                    removedIndexes,
                    workingCounts,
                    requestPaymentSignatures);
        }
        if (remainingAmount.compareTo(BigDecimal.ZERO) < 0 || candidateOffset >= candidateIndexes.size()) {
            return false;
        }
        for (int i = candidateOffset; i < candidateIndexes.size(); i++) {
            Integer lineIndex = candidateIndexes.get(i);
            if (lineIndex == null || removedIndexes.contains(lineIndex)) {
                continue;
            }
            ExistingDealerPaymentLine line = candidateLines.get(lineIndex);
            BigDecimal lineAmount = line.signature().amount();
            if (lineAmount.compareTo(remainingAmount) > 0) {
                continue;
            }
            if (!decrementSignatureCount(workingCounts, line.signature())) {
                continue;
            }
            removedIndexes.add(lineIndex);
            if (tryMatchAdjustmentRemovalCombination(
                    adjustmentIndex,
                    candidateIndexes,
                    i + 1,
                    remainingAmount.subtract(lineAmount),
                    adjustmentSignatures,
                    adjustmentRemovalTargets,
                    candidateIndexesByAdjustment,
                    candidateLines,
                    removedIndexes,
                    workingCounts,
                    requestPaymentSignatures)) {
                return true;
            }
            removedIndexes.remove(lineIndex);
            workingCounts.merge(line.signature(), 1, Integer::sum);
        }
        return false;
    }

    private boolean decrementSignatureCount(Map<DealerPaymentSignature, Integer> counts,
                                            DealerPaymentSignature signature) {
        Integer current = counts.get(signature);
        if (current == null || current <= 0) {
            return false;
        }
        if (current == 1) {
            counts.remove(signature);
            return true;
        }
        counts.put(signature, current - 1);
        return true;
    }

    private String normalizeLineDescription(String description) {
        if (!StringUtils.hasText(description)) {
            return "";
        }
        return description.trim().toLowerCase(Locale.ROOT);
    }

    private String allocationSignature(Long invoiceId,
                                       Long purchaseId,
                                       BigDecimal appliedAmount,
                                       BigDecimal discountAmount,
                                       BigDecimal writeOffAmount,
                                       BigDecimal fxAdjustment,
                                       String memo) {
        return "inv=" + (invoiceId != null ? invoiceId : "null")
                + "|pur=" + (purchaseId != null ? purchaseId : "null")
                + "|applied=" + normalizeSignatureAmount(appliedAmount)
                + "|discount=" + normalizeSignatureAmount(discountAmount)
                + "|writeOff=" + normalizeSignatureAmount(writeOffAmount)
                + "|fx=" + normalizeSignatureAmount(fxAdjustment)
                + "|memo=" + normalizeMemo(memo);
    }

    private String normalizeSignatureAmount(BigDecimal value) {
        return MoneyUtils.zeroIfNull(value).stripTrailingZeros().toPlainString();
    }

    private String normalizeMemo(String memo) {
        return StringUtils.hasText(memo) ? memo.trim() : "";
    }

    private void adjustLandedCostValuation(RawMaterialPurchase purchase, BigDecimal landedAmount) {
        if (landedAmount == null || landedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        List<com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine> lines = purchase.getLines();
        BigDecimal totalValue = lines.stream()
                .map(com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        for (com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine line : lines) {
            BigDecimal weight = line.getLineTotal().divide(totalValue, 8, RoundingMode.HALF_UP);
            BigDecimal allocation = landedAmount.multiply(weight).setScale(4, RoundingMode.HALF_UP);
            BigDecimal qty = MoneyUtils.zeroIfNull(line.getQuantity());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal deltaPerUnit = allocation.divide(qty, 6, RoundingMode.HALF_UP);
            line.setCostPerUnit(line.getCostPerUnit().add(deltaPerUnit));
            line.setLineTotal(line.getLineTotal().add(allocation));
            if (line.getRawMaterialBatch() != null) {
                var batch = line.getRawMaterialBatch();
                batch.setCostPerUnit(batch.getCostPerUnit().add(deltaPerUnit));
                rawMaterialBatchRepository.save(batch);
                rawMaterialMovementRepository.findByRawMaterialBatch(batch).stream()
                        .filter(mv -> "RECEIPT".equalsIgnoreCase(mv.getMovementType()))
                        .forEach(mv -> {
                            mv.setUnitCost(mv.getUnitCost().add(deltaPerUnit));
                            rawMaterialMovementRepository.save(mv);
                        });
            }
        }
        rawMaterialPurchaseRepository.save(purchase);
    }

    private void adjustValuationLayers(Account inventoryAccount, BigDecimal delta) {
        if (delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        // Prefer finished goods for this inventory account; fall back to raw materials if none
        List<FinishedGoodBatch> fgBatches = finishedGoodBatchRepository.findByFinishedGood_ValuationAccountId(inventoryAccount.getId());
        if (fgBatches.isEmpty()) {
            fgBatches = finishedGoodBatchRepository.findAll().stream()
                    .filter(b -> b.getFinishedGood() != null
                            && Objects.equals(b.getFinishedGood().getValuationAccountId(), inventoryAccount.getId()))
                    .toList();
        }
        if (!fgBatches.isEmpty()) {
            revalueFinishedBatches(fgBatches, delta);
            return;
        }
        List<RawMaterialBatch> rmBatches = rawMaterialBatchRepository.findByRawMaterial_InventoryAccountId(inventoryAccount.getId());
        if (!rmBatches.isEmpty()) {
            revalueBatches(rmBatches, delta);
            return;
        }
        List<FinishedGoodBatch> anyBatches = finishedGoodBatchRepository.findAll();
        if (!anyBatches.isEmpty()) {
            revalueFinishedBatches(anyBatches, delta);
        }
    }

    private boolean revalueBatches(List<RawMaterialBatch> batches, BigDecimal delta) {
        BigDecimal totalQty = batches.stream()
                .map(RawMaterialBatch::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal deltaPerUnit = delta.divide(totalQty, 6, RoundingMode.HALF_UP);
        for (RawMaterialBatch batch : batches) {
            batch.setCostPerUnit(batch.getCostPerUnit().add(deltaPerUnit));
            rawMaterialBatchRepository.save(batch);
            rawMaterialMovementRepository.findByRawMaterialBatch(batch).stream()
                    .filter(mv -> "RECEIPT".equalsIgnoreCase(mv.getMovementType()))
                    .forEach(mv -> {
                        mv.setUnitCost(mv.getUnitCost().add(deltaPerUnit));
                        rawMaterialMovementRepository.save(mv);
                    });
        }
        return true;
    }

    private void revalueFinishedBatches(List<FinishedGoodBatch> batches, BigDecimal delta) {
        BigDecimal totalQty = batches.stream()
                .map(batch -> batch.getQuantityTotal() == null ? BigDecimal.ZERO : batch.getQuantityTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal deltaPerUnit = delta.divide(totalQty, 6, RoundingMode.HALF_UP);
        for (FinishedGoodBatch batch : batches) {
            BigDecimal qty = batch.getQuantityTotal() == null ? BigDecimal.ZERO : batch.getQuantityTotal();
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            batch.setUnitCost(batch.getUnitCost().add(deltaPerUnit));
            finishedGoodBatchRepository.save(batch);
        }
    }

}
