package com.bigbrightpaints.erp.modules.accounting.service;

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
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountingService {

    private static final BigDecimal JOURNAL_BALANCE_TOLERANCE = new BigDecimal("0.0001");

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
                             SupplierRepository supplierRepository) {
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
        this.payrollRunLineRepository = payrollRunLineRepository;
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
        Account saved = accountRepository.save(account);
        publishAccountCacheInvalidated(company.getId());
        return toDto(saved);
    }

    /* Journal Entries */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<JournalEntryDto> listJournalEntries(Long dealerId) {
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntry> entries;
        if (dealerId != null) {
            Dealer dealer = requireDealer(company, dealerId);
            entries = journalEntryRepository.findByCompanyAndDealerOrderByEntryDateDesc(company, dealer);
        } else {
            entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        }
        return entries.stream().map(this::toDto).toList();
    }

    @Transactional
    public JournalEntryDto createJournalEntry(JournalEntryRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntryRequest.JournalLineRequest> lines = request.lines();
        if (lines == null || lines.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one journal line is required");
        }
        String currency = resolveCurrency(request.currency(), company);
        BigDecimal fxRate = resolveFxRate(currency, company, request.fxRate());
        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
        entry.setCurrency(currency);
        entry.setFxRate(fxRate);
        entry.setReferenceNumber(resolveJournalReference(company, request.referenceNumber()));
        LocalDate entryDate = request.entryDate();
        boolean overrideRequested = Boolean.TRUE.equals(request.adminOverride());
        boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
        validateEntryDate(company, entryDate, overrideRequested, overrideAuthorized);
        AccountingPeriod postingPeriod = accountingPeriodService.requireOpenPeriod(company, entryDate, overrideAuthorized);

        entry.setEntryDate(entryDate);
        entry.setAccountingPeriod(postingPeriod);
        entry.setMemo(request.memo());
        entry.setStatus("POSTED");
        Dealer dealer = null;
        Account dealerReceivableAccount = null;
        Supplier supplier = null;
        Account supplierPayableAccount = null;
        if (request.dealerId() != null) {
            dealer = requireDealer(company, request.dealerId());
            if (dealer.getReceivableAccount() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Dealer " + dealer.getName() + " is missing a receivable account");
            }
            entry.setDealer(dealer);
            dealerReceivableAccount = dealer.getReceivableAccount();
        }
        if (request.supplierId() != null) {
            supplier = requireSupplier(company, request.supplierId());
            if (supplier.getPayableAccount() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Supplier " + supplier.getName() + " is missing a payable account");
            }
            entry.setSupplier(supplier);
            supplierPayableAccount = supplier.getPayableAccount();
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
        for (Account account : lockedAccounts.values()) {
            dealerRepository.findByCompanyAndReceivableAccount(company, account).ifPresent(owner -> {
                if (dealerContext == null || !owner.getId().equals(dealerContext.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Dealer receivable account " + account.getCode() + " requires matching dealer context");
                }
            });
            supplierRepository.findByCompanyAndPayableAccount(company, account).ifPresent(owner -> {
                if (supplierContext == null || !owner.getId().equals(supplierContext.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                            "Supplier payable account " + account.getCode() + " requires matching supplier context");
                }
            });
        }
        BigDecimal totalBaseDebit = BigDecimal.ZERO;
        BigDecimal totalBaseCredit = BigDecimal.ZERO;
        BigDecimal foreignTotal = BigDecimal.ZERO;
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
            BigDecimal debit = lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit();
            BigDecimal credit = lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit();
            if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit/Credit cannot be negative");
            }
            if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Debit and credit cannot both be non-zero on the same line");
            }
            BigDecimal debitBase = toBaseCurrency(debit, fxRate);
            BigDecimal creditBase = toBaseCurrency(credit, fxRate);
            line.setDebit(debitBase);
            line.setCredit(creditBase);
            entry.getLines().add(line);
            accountDeltas.merge(account, debitBase.subtract(creditBase), BigDecimal::add);
            totalBaseDebit = totalBaseDebit.add(debitBase);
            totalBaseCredit = totalBaseCredit.add(creditBase);
            // CRITICAL FIX #4: Track foreign currency amount properly, not sum of debit+credit
            // For foreign currency entries, track the net amount (debit - credit), not the sum
            if (!currency.equalsIgnoreCase(company.getBaseCurrency())) {
                foreignTotal = foreignTotal.add(debit.subtract(credit));
            }

            if (dealerReceivableAccount != null && Objects.equals(account.getId(), dealerReceivableAccount.getId())) {
                dealerLedgerDebitTotal = dealerLedgerDebitTotal.add(debitBase);
                dealerLedgerCreditTotal = dealerLedgerCreditTotal.add(creditBase);
                dealerArLines++;
            }
            if (supplierPayableAccount != null && Objects.equals(account.getId(), supplierPayableAccount.getId())) {
                supplierLedgerDebitTotal = supplierLedgerDebitTotal.add(debitBase);
                supplierLedgerCreditTotal = supplierLedgerCreditTotal.add(creditBase);
                supplierApLines++;
            }
        }
        if (totalBaseDebit.subtract(totalBaseCredit).abs().compareTo(JOURNAL_BALANCE_TOLERANCE) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Journal entry must balance");
        }
        if (!currency.equalsIgnoreCase(company.getBaseCurrency())) {
            entry.setForeignAmountTotal(foreignTotal.setScale(2, RoundingMode.HALF_UP).doubleValue());
        }
        if (dealer != null && dealerReceivableAccount != null) {
            if (dealerArLines == 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dealer journal entry requires exactly one receivable line for dealer " + dealer.getName());
            }
            if (dealerArLines > 1 && !overrideAuthorized) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Dealer journal entry has multiple receivable lines; admin override required");
            }
        }
        if (supplier != null && supplierPayableAccount != null) {
            if (supplierApLines == 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier journal entry requires exactly one payable line for supplier " + supplier.getName());
            }
            if (supplierApLines > 1 && !overrideAuthorized) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Supplier journal entry has multiple payable lines; admin override required");
            }
        }
        Instant now = Instant.now();
        String username = resolveCurrentUsername();
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setPostedAt(now);
        entry.setCreatedBy(username);
        entry.setLastModifiedBy(username);
        entry.setPostedBy(username);
        JournalEntry saved = journalEntryRepository.save(entry);
        if (!accountDeltas.isEmpty()) {
            Map<Account, BigDecimal> newBalances = new HashMap<>();
            for (Map.Entry<Account, BigDecimal> delta : accountDeltas.entrySet()) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
                BigDecimal updated = current.add(delta.getValue());
                account.validateBalanceUpdate(updated);
                newBalances.put(account, updated);
            }
            for (Map.Entry<Account, BigDecimal> updated : newBalances.entrySet()) {
                updated.getKey().setBalance(updated.getValue());
            }
            accountRepository.saveAll(newBalances.keySet());
            publishAccountCacheInvalidated(company.getId());
        }
        if (saved.getDealer() != null && dealerReceivableAccount != null) {
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
        return toDto(saved);
    }

    @Transactional
    public JournalEntryDto reverseJournalEntry(Long entryId, JournalEntryReversalRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        JournalEntry entry = companyEntityLookup.requireJournalEntry(company, entryId);
        if ("VOIDED".equalsIgnoreCase(entry.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Entry is already voided");
        }
        if ("REVERSED".equalsIgnoreCase(entry.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE, "Entry has already been reversed");
        }
        LocalDate reversalDate = request != null && request.reversalDate() != null
                ? request.reversalDate()
                : currentDate(company);
        boolean overrideRequested = request != null && Boolean.TRUE.equals(request.adminOverride());
        boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
        validateEntryDate(company, reversalDate, overrideRequested, overrideAuthorized);
        AccountingPeriod postingPeriod = accountingPeriodService.requireOpenPeriod(company, reversalDate, overrideAuthorized);
        if (entry.getAccountingPeriod() != null &&
                entry.getAccountingPeriod().getStatus() != AccountingPeriodStatus.OPEN &&
                !overrideAuthorized) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Entry belongs to a locked/closed period. Administrator override is required.");
        }
        String sanitizedReason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Adjustment";
        String memo = request != null && StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Reversal of " + entry.getReferenceNumber();
        List<JournalEntryRequest.JournalLineRequest> reversedLines = entry.getLines().stream()
                .map(line -> new JournalEntryRequest.JournalLineRequest(
                        line.getAccount().getId(),
                        "Reversal: " + (line.getDescription() == null ? entry.getMemo() : line.getDescription()),
                        line.getCredit(),
                        line.getDebit()))
                .toList();
        if (request != null && request.voidOnly()) {
            entry.setStatus("VOIDED");
            entry.setCorrectionType(JournalCorrectionType.VOID);
            entry.setCorrectionReason(sanitizedReason);
            entry.setVoidReason(sanitizedReason);
            entry.setVoidedAt(Instant.now());
            entry.setLastModifiedBy(resolveCurrentUsername());
            journalEntryRepository.save(entry);
            return toDto(entry);
        }
        JournalEntryRequest payload = new JournalEntryRequest(
                referenceNumberService.reversalReference(entry.getReferenceNumber()),
                reversalDate,
                memo,
                entry.getDealer() != null ? entry.getDealer().getId() : null,
                entry.getSupplier() != null ? entry.getSupplier().getId() : null,
                request != null ? request.adminOverride() : null,
                reversedLines
        );
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
        return reversalDto;
    }

    @Transactional
    public JournalEntryDto recordDealerReceipt(DealerReceiptRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Dealer dealer = requireDealer(company, request.dealerId());
        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer " + dealer.getName() + " is missing a receivable account");
        }
        Account cashAccount = requireAccount(company, request.cashAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
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
        return createJournalEntry(payload);
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(PayrollPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        PayrollRun run = companyEntityLookup.requirePayrollRun(company, request.payrollRunId());
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
        JournalEntryDto entry = createJournalEntry(payload);
        run.setStatus("PAID");
        JournalEntry payrollEntry = companyEntityLookup.requireJournalEntry(company, entry.id());
        run.setJournalEntry(payrollEntry);
        payrollRunRepository.save(run);
        return entry;
    }

    @Transactional
    public PayrollBatchPaymentResponse processPayrollBatchPayment(PayrollBatchPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one payroll line is required");
        }
        Account cash = requireAccount(company, request.cashAccountId());
        Account expense = requireAccount(company, request.expenseAccountId());

        List<PayrollBatchPaymentRequest.PayrollLine> lines = request.lines();
        List<PayrollBatchPaymentResponse.LineTotal> lineTotals = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
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
            BigDecimal lineTotal = wage.multiply(BigDecimal.valueOf(days)).subtract(advances);
            if (lineTotal.compareTo(BigDecimal.ZERO) < 0) {
                lineTotal = BigDecimal.ZERO;
            }
            lineTotal = lineTotal.setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineTotal);
            lineTotals.add(new PayrollBatchPaymentResponse.LineTotal(
                    line.name(),
                    days,
                    wage.setScale(2, RoundingMode.HALF_UP),
                    advances.setScale(2, RoundingMode.HALF_UP),
                    lineTotal,
                    line.notes()
            ));
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Total payroll amount must be greater than zero");
        }

        PayrollRun run = new PayrollRun();
        run.setCompany(company);
        run.setRunDate(request.runDate());
        run.setNotes(request.memo());
        run.setTotalAmount(total);
        run.setStatus("PAID");
        run.setProcessedBy(resolveCurrentUsername());
        PayrollRun savedRun = payrollRunRepository.save(run);

        List<PayrollRunLine> persistedLines = new ArrayList<>();
        for (PayrollBatchPaymentResponse.LineTotal line : lineTotals) {
            PayrollRunLine entity = new PayrollRunLine();
            entity.setPayrollRun(savedRun);
            entity.setName(line.name());
            entity.setDaysWorked(line.days());
            entity.setDailyWage(line.dailyWage());
            entity.setAdvances(line.advances());
            entity.setLineTotal(line.lineTotal());
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

        JournalEntryDto je = createJournalEntry(new JournalEntryRequest(
                reference,
                request.runDate(),
                memo,
                null,
                null,
                Boolean.FALSE,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(expense.getId(), memo, total, BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(cash.getId(), memo, BigDecimal.ZERO, total)
                )
        ));
        JournalEntry payrollEntry = companyEntityLookup.requireJournalEntry(company, je.id());
        savedRun.setJournalEntry(payrollEntry);
        payrollRunRepository.save(savedRun);

        return new PayrollBatchPaymentResponse(
                savedRun.getId(),
                savedRun.getRunDate(),
                total.setScale(2, RoundingMode.HALF_UP),
                payrollEntry.getId(),
                lineTotals
        );
    }

    @Transactional
    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = requireSupplier(company, request.supplierId());
        Account payableAccount = supplier.getPayableAccount();
        if (payableAccount == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Supplier " + supplier.getName() + " is missing a payable account");
        }
        Account cashAccount = requireAccount(company, request.cashAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
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
        return createJournalEntry(payload);
    }

    @Transactional
    public PartnerSettlementResponse settleDealerInvoices(DealerSettlementRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String trimmedIdempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().trim()
                : (StringUtils.hasText(request.referenceNumber()) ? request.referenceNumber().trim() : UUID.randomUUID().toString());
        Dealer dealer = requireDealer(company, request.dealerId());
        Account receivableAccount = dealer.getReceivableAccount();
        if (receivableAccount == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Dealer " + dealer.getName() + " is missing a receivable account");
        }
        Account cashAccount = requireAccount(company, request.cashAccountId());
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        if (StringUtils.hasText(trimmedIdempotencyKey)) {
            List<PartnerSettlementAllocation> existing = settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, trimmedIdempotencyKey);
            if (!existing.isEmpty()) {
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
        List<Invoice> touchedInvoices = new ArrayList<>();

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

            Invoice invoice = null;
            if (allocation.invoiceId() != null) {
                invoice = companyEntityLookup.requireInvoice(company, allocation.invoiceId());
                if (invoice.getDealer() == null || !invoice.getDealer().getId().equals(dealer.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice does not belong to the dealer");
                }

                String settlementCurrency = company.getBaseCurrency();
                if (StringUtils.hasText(settlementCurrency) && invoice.getCurrency() != null && !invoice.getCurrency().equalsIgnoreCase(settlementCurrency)) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                            String.format("Cannot settle invoice %s in %s with settlement currency %s", invoice.getInvoiceNumber(), invoice.getCurrency(), settlementCurrency));
                }

                // open-item tracking: reduce outstanding by cleared portion (applied + discount + write-off + fx adj)
                BigDecimal cleared = applied.add(discount).add(writeOff).add(fxAdjustment);
                if (cleared.compareTo(BigDecimal.ZERO) < 0) {
                    cleared = BigDecimal.ZERO;
                }
                BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(invoice.getOutstandingAmount());
                BigDecimal updatedOutstanding = currentOutstanding.subtract(cleared);
                if (updatedOutstanding.compareTo(BigDecimal.ZERO) < 0) {
                    updatedOutstanding = BigDecimal.ZERO;
                }
                invoice.setOutstandingAmount(updatedOutstanding);
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

        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Settlement for dealer " + dealer.getName();
        String reference = StringUtils.hasText(request.referenceNumber())
                ? request.referenceNumber().trim()
                : referenceNumberService.dealerReceiptReference(company, dealer);

        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        if (cashAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(cashAccount.getId(), memo, cashAmount, BigDecimal.ZERO));
        }
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
        Supplier supplier = requireSupplier(company, request.supplierId());
        Account payableAccount = supplier.getPayableAccount();
        if (payableAccount == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Supplier " + supplier.getName() + " is missing a payable account");
        }
        Account cashAccount = requireAccount(company, request.cashAccountId());
        List<SettlementAllocationRequest> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one allocation is required");
        }
        if (StringUtils.hasText(trimmedIdempotencyKey)) {
            List<PartnerSettlementAllocation> existing = settlementAllocationRepository.findByCompanyAndIdempotencyKey(company, trimmedIdempotencyKey);
            if (!existing.isEmpty()) {
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
                purchase = companyEntityLookup.requireRawMaterialPurchase(company, allocation.purchaseId());
                if (purchase.getSupplier() == null || !purchase.getSupplier().getId().equals(supplier.getId())) {
                    throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Purchase does not belong to the supplier");
                }
                // open-item: reduce outstanding by cleared amount
                BigDecimal cleared = applied.add(discount).add(writeOff).add(fxAdjustment);
                if (cleared.compareTo(BigDecimal.ZERO) < 0) {
                    cleared = BigDecimal.ZERO;
                }
                BigDecimal currentOutstanding = MoneyUtils.zeroIfNull(purchase.getOutstandingAmount());
                purchase.setOutstandingAmount(currentOutstanding.subtract(cleared).max(BigDecimal.ZERO));
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
                .add(totalFxGain)      // gain is a credit line, reduce cash debit to balance
                .subtract(totalFxLoss) // loss is a debit line, reduce cash needed accordingly
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

    private void validateEntryDate(Company company, LocalDate entryDate, boolean overrideRequested, boolean overrideAuthorized) {
        if (entryDate == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date is required");
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
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Entry date cannot be more than 30 days old");
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

    private String resolveJournalReference(Company company, String provided) {
        if (StringUtils.hasText(provided)) {
            return provided.trim();
        }
        return referenceNumberService.nextJournalReference(company);
    }

    private String resolveCurrency(String requested, Company company) {
        if (StringUtils.hasText(requested)) {
            return requested.trim().toUpperCase();
        }
        return company.getBaseCurrency() != null ? company.getBaseCurrency() : "INR";
    }

    private BigDecimal resolveFxRate(String currency, Company company, BigDecimal requestedRate) {
        String base = company.getBaseCurrency() != null ? company.getBaseCurrency() : "INR";
        if (currency.equalsIgnoreCase(base)) {
            return BigDecimal.ONE;
        }
        if (requestedRate == null || requestedRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "fxRate is required when currency differs from base currency");
        }
        return requestedRate;
    }

    private BigDecimal toBaseCurrency(BigDecimal amount, BigDecimal fxRate) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(fxRate).setScale(2, RoundingMode.HALF_UP);
    }

    /* Credit/Debit Notes */
    @Transactional
    public JournalEntryDto postCreditNote(CreditNoteRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Invoice invoice = companyEntityLookup.requireInvoice(company, request.invoiceId());
        JournalEntry source = invoice.getJournalEntry();
        if (source == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Invoice " + invoice.getInvoiceNumber() + " has no posted journal to reverse");
        }
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Credit note for invoice " + invoice.getInvoiceNumber();

        List<JournalEntryRequest.JournalLineRequest> lines = source.getLines().stream()
                .map(line -> new JournalEntryRequest.JournalLineRequest(
                        line.getAccount().getId(),
                        "Credit note reversal - " + line.getDescription(),
                        line.getCredit(),
                        line.getDebit()))
                .toList();

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
        return toDto(saved);
    }

    @Transactional
    public JournalEntryDto postDebitNote(DebitNoteRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        RawMaterialPurchase purchase = companyEntityLookup.requireRawMaterialPurchase(company, request.purchaseId());
        JournalEntry source = purchase.getJournalEntry();
        if (source == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Purchase " + purchase.getInvoiceNumber() + " has no posted journal to reverse");
        }
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo())
                ? request.memo().trim()
                : "Debit note for purchase " + purchase.getInvoiceNumber();

        List<JournalEntryRequest.JournalLineRequest> lines = source.getLines().stream()
                .map(line -> new JournalEntryRequest.JournalLineRequest(
                        line.getAccount().getId(),
                        "Debit note reversal - " + line.getDescription(),
                        line.getCredit(),
                        line.getDebit()))
                .toList();

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
        Invoice invoice = companyEntityLookup.requireInvoice(company, request.invoiceId());
        Dealer dealer = invoice.getDealer();
        if (dealer == null || dealer.getReceivableAccount() == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE, "Invoice dealer missing receivable account");
        }
        String reference = resolveJournalReference(company,
                StringUtils.hasText(request.idempotencyKey()) ? request.idempotencyKey() : request.referenceNumber());
        Optional<JournalEntry> existing = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference);
        if (existing.isPresent()) {
            return toDto(existing.get());
        }
        Account expense = requireAccount(company, request.expenseAccountId());
        Account ar = dealer.getReceivableAccount();
        LocalDate entryDate = request.entryDate() != null ? request.entryDate() : currentDate(company);
        String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "Bad debt write-off for invoice " + invoice.getInvoiceNumber();
        BigDecimal amount = request.amount();

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
        return je;
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
        adjustValuationLayers(inventoryAccount, delta);
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
        // Try raw materials first
        List<RawMaterialBatch> rmBatches = rawMaterialBatchRepository.findByRawMaterial_InventoryAccountId(inventoryAccount.getId());
        if (!rmBatches.isEmpty()) {
            revalueBatches(rmBatches, delta);
            return;
        }
        // Then finished goods
        List<FinishedGoodBatch> fgBatches = finishedGoodBatchRepository.findByFinishedGood_ValuationAccountId(inventoryAccount.getId());
        if (!fgBatches.isEmpty()) {
            revalueFinishedBatches(fgBatches, delta);
        }
    }

    private void revalueBatches(List<RawMaterialBatch> batches, BigDecimal delta) {
        BigDecimal totalQty = batches.stream()
                .map(RawMaterialBatch::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
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
    }

    private void revalueFinishedBatches(List<FinishedGoodBatch> batches, BigDecimal delta) {
        BigDecimal totalQty = batches.stream()
                .map(FinishedGoodBatch::getQuantityAvailable)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal deltaPerUnit = delta.divide(totalQty, 6, RoundingMode.HALF_UP);
        for (FinishedGoodBatch batch : batches) {
            batch.setUnitCost(batch.getUnitCost().add(deltaPerUnit));
            finishedGoodBatchRepository.save(batch);
        }
    }

}
