package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * When true, disables date validation for benchmark mode.
     * This allows posting entries with any date regardless of past/future constraints.
     */
    @Value("${erp.benchmark.skip-date-validation:false}")
    private boolean skipDateValidation;

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
                             AuditService auditService) {
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
            dealerReceivableAccount = requireDealerReceivable(dealer);
            entry.setDealer(dealer);
        }
        if (request.supplierId() != null) {
            supplier = requireSupplier(company, request.supplierId());
            supplierPayableAccount = requireSupplierPayable(supplier);
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
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Supplier payable account " + account.getCode() + " requires a supplier context");
                }
                if (supplierOwners.stream().noneMatch(owner -> owner.getId().equals(supplierContext.getId()))) {
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
            auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
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
                auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
                return toDto(existingEntry);
            }
            throw ex;
        }
        if (!accountDeltas.isEmpty()) {
            // Sort accounts by ID to prevent deadlocks - consistent lock ordering
            List<Map.Entry<Account, BigDecimal>> sortedDeltas = accountDeltas.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getKey().getId()))
                    .toList();
            for (Map.Entry<Account, BigDecimal> delta : sortedDeltas) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
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
        auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_POSTED, auditMetadata);
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
            JournalEntryDto reversalDto = createJournalEntry(payload);
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
            auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
            return toDto(reversalEntry);
        }
        JournalEntryDto reversalDto = createJournalEntry(payload);
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
        auditService.logSuccess(AuditEvent.JOURNAL_ENTRY_REVERSED, auditMetadata);
        return reversalDto;
    }

    @Transactional
    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
        Account receivableAccount = requireDealerReceivable(dealer);
        Account cashAccount = requireAccount(company, request.cashAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
        List<SettlementAllocationRequest> allocations = request.allocations();
        validatePaymentAllocations(allocations, amount, "dealer receipt", true);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Receipt for dealer " + dealer.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.dealerReceiptReference(company, dealer);
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
        String idempotencyKey = entry.getReferenceNumber();
        List<PartnerSettlementAllocation> existing = settlementAllocationRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (!existing.isEmpty()) {
            return entryDto;
        }
        LocalDate entryDate = entry.getEntryDate();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<Invoice> touchedInvoices = new ArrayList<>();

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
            BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
            if (applied.compareTo(currentOutstanding) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Allocation exceeds invoice outstanding amount")
                        .withDetail("invoiceId", invoice.getId())
                        .withDetail("outstanding", currentOutstanding)
                        .withDetail("applied", applied);
            }
            String settlementRef = idempotencyKey + "-INV-" + invoice.getId();
            invoiceSettlementPolicy.applySettlement(invoice, applied, settlementRef);
            dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
            touchedInvoices.add(invoice);

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
        if (!touchedInvoices.isEmpty()) {
            invoiceRepository.saveAll(touchedInvoices);
        }
        settlementAllocationRepository.saveAll(settlementRows);
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
            Account incoming = requireAccount(company, line.accountId());
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
                : referenceNumberService.dealerReceiptReference(company, dealer);

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
        String idempotencyKey = entry.getReferenceNumber();
        List<PartnerSettlementAllocation> existing = settlementAllocationRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (!existing.isEmpty()) {
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
            String settlementRef = idempotencyKey + "-INV-" + invoice.getId();
            invoiceSettlementPolicy.applySettlement(invoice, applied, settlementRef);
            dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
            touchedInvoices.add(invoice);

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
        if (!touchedInvoices.isEmpty()) {
            invoiceRepository.saveAll(touchedInvoices);
        }
        settlementAllocationRepository.saveAll(settlementRows);
        return entryDto;
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = companyEntityLookup.requirePayrollRun(company, request.payrollRunId());
        
        // Idempotency check: if already paid, return existing journal entry
        if ("PAID".equals(run.getStatus())) {
            if (run.getJournalEntry() != null) {
                log.info("Payroll run {} already paid with journal {}, returning existing", 
                    run.getId(), run.getJournalEntry().getReferenceNumber());
                return toDto(run.getJournalEntry());
            }
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, 
                "Payroll run already marked PAID but journal entry reference is missing");
        }
        
        // Block if in processing state (concurrent request guard)
        if ("PROCESSING".equals(run.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, 
                "Payroll run is currently being processed, please wait");
        }
        
        Account cashAccount = requireAccount(company, request.cashAccountId());
        Account expenseAccount = requireAccount(company, request.expenseAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
        BigDecimal recordedTotal = run.getTotalAmount() == null ? BigDecimal.ZERO : run.getTotalAmount();
        if (recordedTotal.compareTo(BigDecimal.ZERO) > 0 &&
                recordedTotal.subtract(amount).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Payroll payment amount does not match recorded run total");
        }
        if (recordedTotal.compareTo(BigDecimal.ZERO) == 0) {
            run.setTotalAmount(amount);
        }
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payroll payment for " + run.getRunDate();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.payrollPaymentReference(company);
        JournalEntryRequest payload = new JournalEntryRequest(
                reference,
                currentDate(company),
                memo,
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(expenseAccount.getId(), memo, amount, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, amount)
                )
        );
        // Set PROCESSING before journal to guard against orphans
        run.setStatus("PROCESSING");
        payrollRunRepository.save(run);

        JournalEntryDto entry = createJournalEntry(payload);
        JournalEntry payrollEntry = companyEntityLookup.requireJournalEntry(company, entry.id());
        run.setStatus("PAID");
        run.setJournalEntryId(payrollEntry.getId());
        run.setJournalEntry(payrollEntry);
        payrollRunRepository.save(run);
        return entry;
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
        Account cash = requireAccount(company, request.cashAccountId());
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

        // Create PayrollRun
        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunDate(request.runDate());
        run.setNotes(request.memo());
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

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payroll batch for " + request.runDate();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.payrollPaymentReference(company);

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
        Account cashAccount = requireAccount(company, request.cashAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
        List<SettlementAllocationRequest> allocations = request.allocations();
        validatePaymentAllocations(allocations, amount, "supplier payment", false);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Payment to supplier " + supplier.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.supplierPaymentReference(company, supplier);
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
        String idempotencyKey = entry.getReferenceNumber();
        List<PartnerSettlementAllocation> existing = settlementAllocationRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (!existing.isEmpty()) {
            return entryDto;
        }
        LocalDate entryDate = entry.getEntryDate();
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.purchaseId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase allocation is required for supplier settlements");
            }
            if (allocation.invoiceId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier settlements cannot allocate to invoices");
            }
            BigDecimal applied = requirePositive(allocation.appliedAmount(), "appliedAmount");
            RawMaterialPurchase purchase = rawMaterialPurchaseRepository.lockByCompanyAndId(company, allocation.purchaseId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material purchase not found"));
            if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
            }
            BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
            if (applied.compareTo(currentOutstanding) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Allocation exceeds purchase outstanding amount")
                        .withDetail("purchaseId", purchase.getId())
                        .withDetail("outstanding", currentOutstanding)
                        .withDetail("applied", applied);
            }
            BigDecimal newOutstanding = currentOutstanding.subtract(applied).max(BigDecimal.ZERO);
            purchase.setOutstandingAmount(newOutstanding);
            updatePurchaseStatus(purchase);
            touchedPurchases.add(purchase);

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
        if (!touchedPurchases.isEmpty()) {
            rawMaterialPurchaseRepository.saveAll(touchedPurchases);
        }
        settlementAllocationRepository.saveAll(settlementRows);
        return entryDto;
    }

    @Transactional
    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String trimmedIdempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().trim()
                : (StringUtils.hasText(request.referenceNumber()) ? request.referenceNumber().trim() : UUID.randomUUID().toString());
        Dealer dealer = dealerRepository.lockByCompanyAndId(company, request.dealerId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Dealer not found"));
        Account receivableAccount = requireDealerReceivable(dealer);
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        if (StringUtils.hasText(trimmedIdempotencyKey)) {
            List<PartnerSettlementAllocation> existing = settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, trimmedIdempotencyKey);
            if (!existing.isEmpty()) {
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.DEALER, dealer.getId(), existing, allocations);
                JournalEntry entry = existing.get(0).getJournalEntry();
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
                for (PartnerSettlementAllocation row : existing) {
                    if (row.getInvoice() != null) {
                        dealerLedgerService.syncInvoiceLedger(row.getInvoice(), row.getSettlementDate());
                    }
                }
                return new PartnerSettlementResponse(
                        toDto(entry),
                        applied,
                        null,
                        discountSum,
                        writeOffSum,
                        fxGainSum,
                        fxLossSum,
                        existing.stream()
                                .map(row -> new PartnerSettlementResponse.Allocation(
                                        row.getInvoice() != null ? row.getInvoice().getId() : null,
                                        row.getPurchase() != null ? row.getPurchase().getId() : null,
                                        row.getAllocationAmount(),
                                        row.getDiscountAmount(),
                                        row.getWriteOffAmount(),
                                        row.getFxDifferenceAmount(),
                                        row.getMemo()
                                ))
                                .toList()
                );
            }
        }
        LocalDate entryDate = request.settlementDate() != null ? request.settlementDate() : currentDate(company);

        BigDecimal totalApplied = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        BigDecimal totalFxGain = BigDecimal.ZERO;
        BigDecimal totalFxLoss = BigDecimal.ZERO;
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();
        List<Invoice> touchedInvoices = new ArrayList<>();

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

            totalApplied = totalApplied.add(applied);
            totalDiscount = totalDiscount.add(discount);
            totalWriteOff = totalWriteOff.add(writeOff);
            if (fxAdjustment.compareTo(BigDecimal.ZERO) > 0) {
                totalFxGain = totalFxGain.add(fxAdjustment);
            } else if (fxAdjustment.compareTo(BigDecimal.ZERO) < 0) {
                totalFxLoss = totalFxLoss.add(fxAdjustment.abs());
            }

            Invoice invoice = null;
            if (allocation.invoiceId() != null) {
                invoice = invoiceRepository.lockByCompanyAndId(company, allocation.invoiceId())
                        .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice not found"));
                if (invoice.getDealer() == null || !invoice.getDealer().getId().equals(dealer.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
                }

                String settlementCurrency = company.getBaseCurrency();
                if (StringUtils.hasText(settlementCurrency) && invoice.getCurrency() != null && !invoice.getCurrency().equalsIgnoreCase(settlementCurrency)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            String.format("Cannot settle invoice %s in %s with settlement currency %s", invoice.getInvoiceNumber(), invoice.getCurrency(), settlementCurrency));
                }

                // Open-item tracking: applied amount represents gross invoice reduction.
                BigDecimal cleared = applied;
                BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
                if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Settlement allocation exceeds invoice outstanding amount")
                            .withDetail("invoiceId", invoice.getId())
                            .withDetail("outstandingAmount", currentOutstanding)
                            .withDetail("appliedAmount", cleared);
                }
                // Use centralized policy for settlement - handles status transitions
                String settlementRef = trimmedIdempotencyKey + "-INV-" + invoice.getId();
                invoiceSettlementPolicy.applySettlement(invoice, cleared, settlementRef);
                dealerLedgerService.syncInvoiceLedger(invoice, entryDate);
                touchedInvoices.add(invoice);
            }

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
            if (invoice != null && invoice.getCurrency() != null) {
                row.setCurrency(invoice.getCurrency());
            }
            row.setMemo(allocation.memo());
            settlementRows.add(row);
        }

        // FIX #7: Removed duplicate validation block
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0 && request.discountAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Discount account is required when a discount is applied");
        }
        if (totalWriteOff.compareTo(BigDecimal.ZERO) > 0 && request.writeOffAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Write-off account is required when a write-off is applied");
        }
        if (totalFxGain.compareTo(BigDecimal.ZERO) > 0 && request.fxGainAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX gain account is required when FX gain is provided");
        }
        if (totalFxLoss.compareTo(BigDecimal.ZERO) > 0 && request.fxLossAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX loss account is required when FX loss is provided");
        }

        Account discountAccount = totalDiscount.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.discountAccountId())
                : null;
        Account writeOffAccount = totalWriteOff.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.writeOffAccountId())
                : null;
        Account fxGainAccount = totalFxGain.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxGainAccountId())
                : null;
        Account fxLossAccount = totalFxLoss.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxLossAccountId())
                : null;

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Settlement for dealer " + dealer.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.dealerReceiptReference(company, dealer);

        // Cash is what actually moves: applied minus concessions and FX loss impact, plus FX gain impact on credits
        BigDecimal cashAmount = totalApplied
                .add(totalFxGain)      // gain reduces net debits needed because it is a credit line
                .subtract(totalFxLoss) // loss adds a debit line, so reduce cash accordingly
                .subtract(totalDiscount)
                .subtract(totalWriteOff);
        if (cashAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Calculated cash amount cannot be negative. Adjust discount/write-off/FX values.");
        }

        // Resolve payments: prefer explicit payments list; fall back to single legacy cashAccountId
        List<SettlementPaymentRequest> paymentRequests = request.payments() != null && !request.payments().isEmpty()
                ? request.payments()
                : null;
        List<JournalEntryRequest.JournalLineRequest> paymentLines = new ArrayList<>();
        if (paymentRequests == null) {
            // Legacy single-tender path
            if (cashAmount.compareTo(BigDecimal.ZERO) > 0 && request.cashAccountId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "cashAccountId is required when cash is moving");
            }
            if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
                Account cashAccount = requireAccount(company, request.cashAccountId());
                paymentLines.add(new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, cashAmount, BigDecimal.ZERO));
            }
        } else {
            BigDecimal paymentTotal = BigDecimal.ZERO;
            for (SettlementPaymentRequest payment : paymentRequests) {
                BigDecimal amount = requirePositive(payment.amount(), "payment amount");
                Account account = requireAccount(company, payment.accountId());
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
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    discountAccount.getId(),
                    "Settlement discount",
                    totalDiscount,
                    BigDecimal.ZERO));
        }
        if (totalWriteOff.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    writeOffAccount.getId(),
                    "Settlement write-off",
                    totalWriteOff,
                    BigDecimal.ZERO));
        }
        if (totalFxLoss.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxLossAccount.getId(),
                    "FX loss on settlement",
                    totalFxLoss,
                    BigDecimal.ZERO));
        }
        lines.add(new JournalEntryRequest.JournalLineRequest(receivableAccount.getId(), memo, BigDecimal.ZERO, totalApplied));
        if (totalFxGain.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxGainAccount.getId(),
                    "FX gain on settlement",
                    BigDecimal.ZERO,
                    totalFxGain));
        }

        JournalEntryDto journalEntryDto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                dealer.getId(),
                null,
                request.adminOverride(),
                lines
        ));

        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
        for (PartnerSettlementAllocation allocation : settlementRows) {
            allocation.setJournalEntry(journalEntry);
        }
        if (!touchedPurchases.isEmpty()) {
            rawMaterialPurchaseRepository.saveAll(touchedPurchases);
        }
        settlementAllocationRepository.saveAll(settlementRows);
        if (!touchedInvoices.isEmpty()) {
            invoiceRepository.saveAll(touchedInvoices);
        }

        List<PartnerSettlementResponse.Allocation> allocationSummaries = settlementRows.stream()
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

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("partnerType", PartnerType.DEALER.name());
        if (dealer.getId() != null) {
            auditMetadata.put("partnerId", dealer.getId().toString());
        }
        if (journalEntryDto != null && journalEntryDto.id() != null) {
            auditMetadata.put("journalEntryId", journalEntryDto.id().toString());
        }
        if (entryDate != null) {
            auditMetadata.put("settlementDate", entryDate.toString());
        }
        if (trimmedIdempotencyKey != null) {
            auditMetadata.put("idempotencyKey", trimmedIdempotencyKey);
        }
        auditMetadata.put("allocationCount", Integer.toString(settlementRows.size()));
        auditMetadata.put("totalApplied", totalApplied.toPlainString());
        auditMetadata.put("cashAmount", cashAmount.toPlainString());
        auditMetadata.put("totalDiscount", totalDiscount.toPlainString());
        auditMetadata.put("totalWriteOff", totalWriteOff.toPlainString());
        auditMetadata.put("totalFxGain", totalFxGain.toPlainString());
        auditMetadata.put("totalFxLoss", totalFxLoss.toPlainString());
        auditService.logSuccess(AuditEvent.SETTLEMENT_RECORDED, auditMetadata);

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
        String trimmedIdempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().trim()
                : (StringUtils.hasText(request.referenceNumber()) ? request.referenceNumber().trim() : UUID.randomUUID().toString());
        Supplier supplier = supplierRepository.lockByCompanyAndId(company, request.supplierId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Supplier not found"));
        Account payableAccount = requireSupplierPayable(supplier);
        Account cashAccount = requireAccount(company, request.cashAccountId());
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        if (StringUtils.hasText(trimmedIdempotencyKey)) {
            List<PartnerSettlementAllocation> existing = settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, trimmedIdempotencyKey);
            if (!existing.isEmpty()) {
                validateSettlementIdempotencyKey(trimmedIdempotencyKey, PartnerType.SUPPLIER, supplier.getId(), existing, allocations);
                JournalEntry entry = existing.get(0).getJournalEntry();
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
                return new PartnerSettlementResponse(
                        toDto(entry),
                        applied,
                        null,
                        discountSum,
                        writeOffSum,
                        fxGainSum,
                        fxLossSum,
                        existing.stream()
                                .map(row -> new PartnerSettlementResponse.Allocation(
                                        row.getInvoice() != null ? row.getInvoice().getId() : null,
                                        row.getPurchase() != null ? row.getPurchase().getId() : null,
                                        row.getAllocationAmount(),
                                        row.getDiscountAmount(),
                                        row.getWriteOffAmount(),
                                        row.getFxDifferenceAmount(),
                                        row.getMemo()
                                ))
                                .toList()
                );
            }
        }
        LocalDate entryDate = request.settlementDate() != null ? request.settlementDate() : currentDate(company);

        BigDecimal totalApplied = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalWriteOff = BigDecimal.ZERO;
        BigDecimal totalFxGain = BigDecimal.ZERO;
        BigDecimal totalFxLoss = BigDecimal.ZERO;
        List<PartnerSettlementAllocation> settlementRows = new ArrayList<>();
        List<RawMaterialPurchase> touchedPurchases = new ArrayList<>();

        for (SettlementAllocationRequest allocation : allocations) {
            if (allocation.purchaseId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Purchase allocation is required for supplier settlements");
            }
            if (allocation.invoiceId() != null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier settlements cannot allocate to invoices");
            }
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

            RawMaterialPurchase purchase = null;
            if (allocation.purchaseId() != null) {
                purchase = rawMaterialPurchaseRepository.lockByCompanyAndId(company, allocation.purchaseId())
                        .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Raw material purchase not found"));
                if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
                }
                // Open-item: applied amount represents gross purchase reduction.
                BigDecimal cleared = applied;
                BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
                if (cleared.subtract(currentOutstanding).compareTo(ALLOCATION_TOLERANCE) > 0) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Settlement allocation exceeds purchase outstanding amount")
                            .withDetail("purchaseId", purchase.getId())
                            .withDetail("outstandingAmount", currentOutstanding)
                            .withDetail("appliedAmount", cleared);
                }
                purchase.setOutstandingAmount(currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
                updatePurchaseStatus(purchase);
                touchedPurchases.add(purchase);
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

        // Validate required accounts before loading them
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0 && request.discountAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Discount account is required when a discount is applied");
        }
        if (totalWriteOff.compareTo(BigDecimal.ZERO) > 0 && request.writeOffAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Write-off account is required when a write-off is applied");
        }
        if (totalFxGain.compareTo(BigDecimal.ZERO) > 0 && request.fxGainAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX gain account is required when FX gain is provided");
        }
        if (totalFxLoss.compareTo(BigDecimal.ZERO) > 0 && request.fxLossAccountId() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "FX loss account is required when FX loss is provided");
        }

        Account discountAccount = totalDiscount.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.discountAccountId())
                : null;
        Account writeOffAccount = totalWriteOff.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.writeOffAccountId())
                : null;
        Account fxGainAccount = totalFxGain.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxGainAccountId())
                : null;
        Account fxLossAccount = totalFxLoss.compareTo(BigDecimal.ZERO) > 0
                ? requireAccount(company, request.fxLossAccountId())
                : null;

        // Cash is what actually moves: applied minus concessions, adjusted for FX gain/loss lines
        BigDecimal cashAmount = totalApplied
                .add(totalFxLoss)      // loss increases cash paid
                .subtract(totalFxGain) // gain reduces cash paid
                .subtract(totalDiscount)
                .subtract(totalWriteOff);
        if (cashAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Calculated cash amount cannot be negative. Adjust discount/write-off/FX values.");
        }

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Settlement to supplier " + supplier.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.supplierPaymentReference(company, supplier);

        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        lines.add(new JournalEntryRequest.JournalLineRequest(payableAccount.getId(), memo, totalApplied, BigDecimal.ZERO));
        if (totalFxLoss.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxLossAccount.getId(),
                    "FX loss on settlement",
                    totalFxLoss,
                    BigDecimal.ZERO));
        }
        if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, BigDecimal.ZERO, cashAmount));
        }
        if (totalDiscount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    discountAccount.getId(),
                    "Settlement discount received",
                    BigDecimal.ZERO,
                    totalDiscount));
        }
        if (totalWriteOff.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    writeOffAccount.getId(),
                    "Settlement write-off",
                    BigDecimal.ZERO,
                    totalWriteOff));
        }
        if (totalFxGain.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    fxGainAccount.getId(),
                    "FX gain on settlement",
                    BigDecimal.ZERO,
                    totalFxGain));
        }

        JournalEntryDto journalEntryDto = createJournalEntry(new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                supplier.getId(),
                request.adminOverride(),
                lines
        ));

        JournalEntry journalEntry = companyEntityLookup.requireJournalEntry(company, journalEntryDto.id());
        for (PartnerSettlementAllocation allocation : settlementRows) {
            allocation.setJournalEntry(journalEntry);
        }
        if (!touchedPurchases.isEmpty()) {
            rawMaterialPurchaseRepository.saveAll(touchedPurchases);
        }
        settlementAllocationRepository.saveAll(settlementRows);

        List<PartnerSettlementResponse.Allocation> allocationSummaries = settlementRows.stream()
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

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("partnerType", PartnerType.SUPPLIER.name());
        if (supplier.getId() != null) {
            auditMetadata.put("partnerId", supplier.getId().toString());
        }
        if (journalEntryDto != null && journalEntryDto.id() != null) {
            auditMetadata.put("journalEntryId", journalEntryDto.id().toString());
        }
        if (entryDate != null) {
            auditMetadata.put("settlementDate", entryDate.toString());
        }
        if (trimmedIdempotencyKey != null) {
            auditMetadata.put("idempotencyKey", trimmedIdempotencyKey);
        }
        auditMetadata.put("allocationCount", Integer.toString(settlementRows.size()));
        auditMetadata.put("totalApplied", totalApplied.toPlainString());
        auditMetadata.put("cashAmount", cashAmount.toPlainString());
        auditMetadata.put("totalDiscount", totalDiscount.toPlainString());
        auditMetadata.put("totalWriteOff", totalWriteOff.toPlainString());
        auditMetadata.put("totalFxGain", totalFxGain.toPlainString());
        auditMetadata.put("totalFxLoss", totalFxLoss.toPlainString());
        auditService.logSuccess(AuditEvent.SETTLEMENT_RECORDED, auditMetadata);

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
                if (allocation.purchaseId() == null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Purchase allocation is required for supplier payments");
                }
                if (allocation.invoiceId() != null) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            "Supplier payments cannot allocate to invoices");
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
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        if (StringUtils.hasText(key)) {
            if (journalEntryRepository.findByCompanyAndReferenceNumber(company, key).isPresent()) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Idempotency key conflicts with an existing system reference")
                        .withDetail("referenceNumber", key);
            }
            Optional<JournalEntry> existing = journalReferenceResolver.findExistingEntry(company, key);
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
                    "JOURNAL_ENTRY"
            );
            if (reserved == 0) {
                Optional<JournalEntry> already = journalReferenceResolver.findExistingEntry(company, key);
                if (already.isPresent()) {
                    return toDto(already.get());
                }
                throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                        "Manual journal idempotency key already reserved but entry not found")
                        .withDetail("referenceNumber", key);
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
            JournalReferenceMapping mapping = journalReferenceMappingRepository
                    .findByCompanyAndLegacyReferenceIgnoreCase(company, key)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                            "Manual journal idempotency reservation missing")
                            .withDetail("referenceNumber", key));
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

    private boolean hasEntryDateOverrideAuthority() {
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
                    log.warn("Could not cascade reverse entry {}: {}", related.getReferenceNumber(), e.getMessage());
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
                        continue;
                    }
                    JournalEntryDto relatedReversal = reverseJournalEntry(relatedId, request);
                    reversedEntries.add(relatedReversal);
                    processedIds.add(relatedId);
                } catch (ApplicationException e) {
                    log.warn("Could not reverse related entry {}: {}", relatedId, e.getMessage());
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

    private void ensureDuplicateMatchesExisting(JournalEntry existing,
                                                JournalEntry candidate,
                                                List<JournalLine> candidateLines) {
        List<String> mismatches = new ArrayList<>();
        if (!Objects.equals(existing.getEntryDate(), candidate.getEntryDate())) {
            mismatches.add("entryDate");
        }
        if (!Objects.equals(existing.getDealer() != null ? existing.getDealer().getId() : null,
                candidate.getDealer() != null ? candidate.getDealer().getId() : null)) {
            mismatches.add("dealerId");
        }
        if (!Objects.equals(existing.getSupplier() != null ? existing.getSupplier().getId() : null,
                candidate.getSupplier() != null ? candidate.getSupplier().getId() : null)) {
            mismatches.add("supplierId");
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
            throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Journal entry reference already exists with different details")
                    .withDetail("reference", existing.getReferenceNumber())
                    .withDetail("mismatches", mismatches);
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
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            BigDecimal existingAmount = calculateEntryTotal(existing.get());
            BigDecimal totalCredited = totalNoteAmount(company, source, "CREDIT_NOTE");
            applyCreditNoteToInvoice(invoice, existingAmount, totalCredited, reference, entryDate);
            return toDto(existing.get());
        }
        BigDecimal totalAmount = MoneyUtils.zeroIfNull(invoice.getTotalAmount());
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Credit note amount must be positive");
        }
        BigDecimal creditedSoFar = totalNoteAmount(company, source, "CREDIT_NOTE");
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
        BigDecimal postedAmount = calculateEntryTotal(saved);
        BigDecimal totalCredited = creditedSoFar.add(postedAmount);
        applyCreditNoteToInvoice(invoice, postedAmount, totalCredited, reference, entryDate);
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
        boolean partnerMismatch = existing.stream().anyMatch(row -> {
            if (partnerType == PartnerType.DEALER) {
                return row.getDealer() == null || !Objects.equals(row.getDealer().getId(), partnerId);
            }
            if (partnerType == PartnerType.SUPPLIER) {
                return row.getSupplier() == null || !Objects.equals(row.getSupplier().getId(), partnerId);
            }
            return true;
        });
        if (partnerMismatch) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, "Idempotency key already used for another partner")
                    .withDetail("idempotencyKey", idempotencyKey);
        }

        Map<String, Integer> existingSignatures = allocationSignatureCountsFromRows(existing);
        Map<String, Integer> requestSignatures = allocationSignatureCountsFromRequests(allocations);
        if (!existingSignatures.equals(requestSignatures)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT, "Idempotency key already used for a different settlement payload")
                    .withDetail("idempotencyKey", idempotencyKey);
        }
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
