package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.*;
import com.bigbrightpaints.erp.modules.accounting.event.AccountCacheInvalidatedEvent;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import jakarta.transaction.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AccountingService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final SupplierRepository supplierRepository;
    private final SupplierLedgerService supplierLedgerService;
    private final PayrollRunRepository payrollRunRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final ReferenceNumberService referenceNumberService;
    private final ApplicationEventPublisher eventPublisher;

    public AccountingService(CompanyContextService companyContextService,
                             AccountRepository accountRepository,
                             JournalEntryRepository journalEntryRepository,
                             DealerRepository dealerRepository,
                             DealerLedgerService dealerLedgerService,
                             SupplierRepository supplierRepository,
                             SupplierLedgerService supplierLedgerService,
                             PayrollRunRepository payrollRunRepository,
                             AccountingPeriodService accountingPeriodService,
                             ReferenceNumberService referenceNumberService,
                             ApplicationEventPublisher eventPublisher) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.supplierRepository = supplierRepository;
        this.supplierLedgerService = supplierLedgerService;
        this.payrollRunRepository = payrollRunRepository;
        this.accountingPeriodService = accountingPeriodService;
        this.referenceNumberService = referenceNumberService;
        this.eventPublisher = eventPublisher;
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
        BigDecimal totalDebit = request.lines().stream()
                .map(JournalEntryRequest.JournalLineRequest::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = request.lines().stream()
                .map(JournalEntryRequest.JournalLineRequest::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException("Journal entry must balance");
        }
        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
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
                throw new IllegalStateException("Dealer " + dealer.getName() + " is missing a receivable account");
            }
            entry.setDealer(dealer);
            dealerReceivableAccount = dealer.getReceivableAccount();
        }
        if (request.supplierId() != null) {
            supplier = requireSupplier(company, request.supplierId());
            if (supplier.getPayableAccount() == null) {
                throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
            }
            entry.setSupplier(supplier);
            supplierPayableAccount = supplier.getPayableAccount();
        }
        Map<Account, BigDecimal> accountDeltas = new HashMap<>();
        BigDecimal dealerLedgerDebitTotal = BigDecimal.ZERO;
        BigDecimal dealerLedgerCreditTotal = BigDecimal.ZERO;
        BigDecimal supplierLedgerDebitTotal = BigDecimal.ZERO;
        BigDecimal supplierLedgerCreditTotal = BigDecimal.ZERO;
        for (JournalEntryRequest.JournalLineRequest lineRequest : request.lines()) {
            Account account = accountRepository.lockByCompanyAndId(company, lineRequest.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Account not found"));
            JournalLine line = new JournalLine();
            line.setJournalEntry(entry);
            line.setAccount(account);
            line.setDescription(lineRequest.description());
            BigDecimal debit = lineRequest.debit() == null ? BigDecimal.ZERO : lineRequest.debit();
            BigDecimal credit = lineRequest.credit() == null ? BigDecimal.ZERO : lineRequest.credit();
            line.setDebit(debit);
            line.setCredit(credit);
            entry.getLines().add(line);
            accountDeltas.merge(account, debit.subtract(credit), BigDecimal::add);

            if (dealerReceivableAccount != null && Objects.equals(account.getId(), dealerReceivableAccount.getId())) {
                dealerLedgerDebitTotal = dealerLedgerDebitTotal.add(debit);
                dealerLedgerCreditTotal = dealerLedgerCreditTotal.add(credit);
            }
            if (supplierPayableAccount != null && Objects.equals(account.getId(), supplierPayableAccount.getId())) {
                supplierLedgerDebitTotal = supplierLedgerDebitTotal.add(debit);
                supplierLedgerCreditTotal = supplierLedgerCreditTotal.add(credit);
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
            for (Map.Entry<Account, BigDecimal> delta : accountDeltas.entrySet()) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
                account.setBalance(current.add(delta.getValue()));
            }
            accountRepository.saveAll(accountDeltas.keySet());
            publishAccountCacheInvalidated(company.getId());
        }
        if (saved.getDealer() != null && dealerReceivableAccount != null) {
            if (dealerLedgerDebitTotal.compareTo(BigDecimal.ZERO) != 0
                    || dealerLedgerCreditTotal.compareTo(BigDecimal.ZERO) != 0) {
                dealerLedgerService.recordLedgerEntry(
                        saved.getDealer(),
                        new DealerLedgerService.LocalLedgerContext(
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
                        new SupplierLedgerService.LocalLedgerContext(
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
        JournalEntry entry = journalEntryRepository.findByCompanyAndId(company, entryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found"));
        if ("VOIDED".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException("Entry is already voided");
        }
        if ("REVERSED".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException("Entry has already been reversed");
        }
        LocalDate reversalDate = request != null && request.reversalDate() != null
                ? request.reversalDate()
                : currentDate(company);
        boolean overrideRequested = request != null && Boolean.TRUE.equals(request.adminOverride());
        boolean overrideAuthorized = overrideRequested && hasEntryDateOverrideAuthority();
        validateEntryDate(company, reversalDate, overrideRequested, overrideAuthorized);
        AccountingPeriod postingPeriod = accountingPeriodService.requireOpenPeriod(company, reversalDate, overrideAuthorized);
        if (entry.getAccountingPeriod() != null &&
                entry.getAccountingPeriod().getStatus() == AccountingPeriodStatus.CLOSED &&
                !overrideAuthorized) {
            throw new IllegalStateException("Entry belongs to a closed period. Administrator override is required.");
        }
        String sanitizedReason = request != null && StringUtils.hasText(request.reason())
                ? request.reason().trim()
                : "Adjustment";
        if (request != null && request.voidOnly()) {
            entry.setStatus("VOIDED");
            entry.setCorrectionType(JournalCorrectionType.VOID);
            entry.setCorrectionReason(sanitizedReason);
            entry.setVoidReason(sanitizedReason);
            entry.setVoidedAt(Instant.now());
            entry.setLastModifiedBy(resolveCurrentUsername());
            return toDto(journalEntryRepository.save(entry));
        }
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
        JournalEntry reversalEntry = journalEntryRepository.findByCompanyAndId(company, reversalDto.id())
                .orElseThrow(() -> new IllegalStateException("Failed to load reversal journal entry"));
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
            throw new IllegalStateException("Dealer " + dealer.getName() + " is missing a receivable account");
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
        PayrollRun run = payrollRunRepository.findByCompanyAndId(company, request.payrollRunId())
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found"));
        Account cashAccount = requireAccount(company, request.cashAccountId());
        Account expenseAccount = requireAccount(company, request.expenseAccountId());
        BigDecimal amount = requirePositive(request.amount(), "amount");
        BigDecimal recordedTotal = run.getTotalAmount() == null ? BigDecimal.ZERO : run.getTotalAmount();
        if (recordedTotal.compareTo(BigDecimal.ZERO) > 0 &&
                recordedTotal.subtract(amount).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException("Payroll payment amount does not match recorded run total");
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
        journalEntryRepository.findByCompanyAndId(company, entry.id())
                .ifPresent(run::setJournalEntry);
        payrollRunRepository.save(run);
        return entry;
    }

    @Transactional
    public JournalEntryDto recordSupplierPayment(SupplierPaymentRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Supplier supplier = requireSupplier(company, request.supplierId());
        Account payableAccount = supplier.getPayableAccount();
        if (payableAccount == null) {
            throw new IllegalStateException("Supplier " + supplier.getName() + " is missing a payable account");
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
        return dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found"));
    }

    private Supplier requireSupplier(Company company, Long supplierId) {
        return supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found"));
    }

    private Account requireAccount(Company company, Long accountId) {
        return accountRepository.findByCompanyAndId(company, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Value for " + field + " must be greater than zero");
        }
        return value;
    }

    private void validateEntryDate(Company company, LocalDate entryDate, boolean overrideRequested, boolean overrideAuthorized) {
        if (entryDate == null) {
            throw new IllegalArgumentException("Entry date is required");
        }
        LocalDate today = currentDate(company);
        LocalDate oldestAllowed = today.minusDays(30);
        boolean future = entryDate.isAfter(today);
        boolean tooOld = entryDate.isBefore(oldestAllowed);
        if ((!overrideAuthorized) && (future || tooOld)) {
            if (overrideRequested && !overrideAuthorized) {
                String reason = future ? "future period" : "a closed period";
                throw new IllegalArgumentException("Administrator approval is required to post into " + reason);
            }
            if (future) {
                throw new IllegalArgumentException("Entry date cannot be in the future");
            }
            throw new IllegalArgumentException("Entry date cannot be more than 30 days old");
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
        String timezone = company.getTimezone() == null ? "UTC" : company.getTimezone();
        return LocalDate.now(ZoneId.of(timezone));
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

}
