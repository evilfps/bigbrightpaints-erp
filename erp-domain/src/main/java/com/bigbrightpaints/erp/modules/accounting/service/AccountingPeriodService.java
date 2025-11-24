package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodCloseRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodLockRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodReopenRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.MonthEndChecklistUpdateRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.math.RoundingMode;

@Service
public class AccountingPeriodService {

    private final AccountingPeriodRepository accountingPeriodRepository;
    private final CompanyContextService companyContextService;
    private final JournalEntryRepository journalEntryRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final JournalLineRepository journalLineRepository;
    private final AccountRepository accountRepository;
    private final CompanyClock companyClock;

    public AccountingPeriodService(AccountingPeriodRepository accountingPeriodRepository,
                                   CompanyContextService companyContextService,
                                   JournalEntryRepository journalEntryRepository,
                                   CompanyEntityLookup companyEntityLookup,
                                   JournalLineRepository journalLineRepository,
                                   AccountRepository accountRepository,
                                   CompanyClock companyClock) {
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.companyContextService = companyContextService;
        this.journalEntryRepository = journalEntryRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.journalLineRepository = journalLineRepository;
        this.accountRepository = accountRepository;
        this.companyClock = companyClock;
    }

    public List<AccountingPeriodDto> listPeriods() {
        Company company = companyContextService.requireCurrentCompany();
        ensureSurroundingPeriods(company);
        return accountingPeriodRepository.findByCompanyOrderByYearDescMonthDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    public AccountingPeriodDto getPeriod(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        return toDto(period);
    }

    @Transactional
    public AccountingPeriodDto closePeriod(Long periodId, AccountingPeriodCloseRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        if (period.getStatus() == AccountingPeriodStatus.CLOSED || period.getStatus() == AccountingPeriodStatus.LOCKED) {
            return toDto(period);
        }
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        if (!force) {
            assertChecklistComplete(company, period);
        }
        String note = request != null && StringUtils.hasText(request.note()) ? request.note().trim() : null;
        if (note != null) {
            period.setChecklistNotes(note);
        }
        BigDecimal netIncome = computeNetIncome(company, period);
        Long closingJournalId = null;
        if (netIncome.compareTo(BigDecimal.ZERO) != 0) {
            JournalEntry closingJe = postClosingJournal(company, period, netIncome, note);
            closingJournalId = closingJe.getId();
        }
        Instant now = Instant.now();
        String user = resolveCurrentUsername();
        period.setStatus(AccountingPeriodStatus.CLOSED);
        period.setClosedAt(now);
        period.setClosedBy(user);
        period.setLockedAt(now);
        period.setLockedBy(user);
        period.setLockReason(note);
        period.setClosingJournalEntryId(closingJournalId);
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        ensurePeriod(company, period.getEndDate().plusDays(1));
        return toDto(saved);
    }

    @Transactional
    public AccountingPeriodDto confirmBankReconciliation(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        period.setBankReconciled(true);
        period.setBankReconciledAt(Instant.now());
        period.setBankReconciledBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto confirmInventoryCount(Long periodId, LocalDate referenceDate, String note) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId, referenceDate);
        period.setInventoryCounted(true);
        period.setInventoryCountedAt(Instant.now());
        period.setInventoryCountedBy(resolveCurrentUsername());
        if (StringUtils.hasText(note)) {
            period.setChecklistNotes(note.trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto lockPeriod(Long periodId, AccountingPeriodLockRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        if (period.getStatus() == AccountingPeriodStatus.LOCKED || period.getStatus() == AccountingPeriodStatus.CLOSED) {
            return toDto(period);
        }
        period.setStatus(AccountingPeriodStatus.LOCKED);
        period.setLockedAt(Instant.now());
        period.setLockedBy(resolveCurrentUsername());
        if (request != null && StringUtils.hasText(request.reason())) {
            period.setLockReason(request.reason().trim());
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    @Transactional
    public AccountingPeriodDto reopenPeriod(Long periodId, AccountingPeriodReopenRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = companyEntityLookup.requireAccountingPeriod(company, periodId);
        if (period.getStatus() == AccountingPeriodStatus.OPEN) {
            return toDto(period);
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Reopen reason is required");
        }
        Instant now = Instant.now();
        period.setStatus(AccountingPeriodStatus.OPEN);
        period.setReopenedAt(now);
        period.setReopenedBy(resolveCurrentUsername());
        period.setReopenReason(request != null ? request.reason() : null);
        // Auto-reverse closing journal if present
        if (period.getClosingJournalEntryId() != null) {
            journalEntryRepository.findById(period.getClosingJournalEntryId()).ifPresent(closing -> {
                createReversalFor(closing, period, now);
            });
            period.setClosingJournalEntryId(null);
        }
        return toDto(accountingPeriodRepository.save(period));
    }

    public MonthEndChecklistDto getMonthEndChecklist(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        return buildChecklist(company, period);
    }

    @Transactional
    public MonthEndChecklistDto updateMonthEndChecklist(Long periodId, MonthEndChecklistUpdateRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolvePeriod(company, periodId);
        if (request != null) {
            if (request.bankReconciled() != null) {
                period.setBankReconciled(request.bankReconciled());
            }
            if (request.inventoryCounted() != null) {
                period.setInventoryCounted(request.inventoryCounted());
            }
            if (StringUtils.hasText(request.note())) {
                period.setChecklistNotes(request.note().trim());
            }
        }
        AccountingPeriod saved = accountingPeriodRepository.save(period);
        return buildChecklist(company, saved);
    }

    public AccountingPeriod requireOpenPeriod(Company company, LocalDate referenceDate) {
        AccountingPeriod period = ensurePeriod(company, referenceDate);
        if (period.getStatus() != AccountingPeriodStatus.OPEN) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Accounting period " + period.getLabel() + " is locked/closed");
        }
        return period;
    }

    private BigDecimal computeNetIncome(Company company, AccountingPeriod period) {
        List<Object[]> aggregates = journalLineRepository.summarizeByAccountType(
                company, period.getStartDate(), period.getEndDate());
        BigDecimal net = BigDecimal.ZERO;
        for (Object[] row : aggregates) {
            if (row == null || row.length < 3) {
                continue;
            }
            AccountType type = (AccountType) row[0];
            BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            switch (type) {
                case REVENUE -> net = net.add(credit.subtract(debit));
                case EXPENSE, COGS -> net = net.subtract(debit.subtract(credit));
                default -> {
                }
            }
        }
        return net.setScale(2, RoundingMode.HALF_UP);
    }

    private JournalEntry postClosingJournal(Company company,
                                            AccountingPeriod period,
                                            BigDecimal netIncome,
                                            String note) {
        String reference = "PERIOD-CLOSE-" + period.getYear() + String.format("%02d", period.getMonth());
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference)
                .orElseGet(() -> createSystemJournal(company, period, reference, note, netIncome));
    }

    private JournalEntry createSystemJournal(Company company,
                                             AccountingPeriod period,
                                             String reference,
                                             String note,
                                             BigDecimal netIncome) {
        Account retained = ensureEquityAccount(company, "RETAINED_EARNINGS", "Retained Earnings");
        Account periodResult = ensureEquityAccount(company, "PERIOD_RESULT", "Period Result");
        BigDecimal amount = netIncome.abs();
        List<JournalLineSpec> specs = List.of(
                netIncome.compareTo(BigDecimal.ZERO) > 0
                        ? new JournalLineSpec(periodResult, amount, BigDecimal.ZERO, "Close P&L to retained earnings")
                        : new JournalLineSpec(retained, amount, BigDecimal.ZERO, "Close P&L loss to retained earnings"),
                netIncome.compareTo(BigDecimal.ZERO) > 0
                        ? new JournalLineSpec(retained, BigDecimal.ZERO, amount, "Transfer profit to retained earnings")
                        : new JournalLineSpec(periodResult, BigDecimal.ZERO, amount, "Transfer loss to period result")
        );
        JournalEntry entry = new JournalEntry();
        entry.setCompany(company);
        entry.setReferenceNumber(reference);
        entry.setEntryDate(period.getEndDate());
        entry.setMemo(note != null ? note : "Period close " + period.getLabel());
        entry.setStatus("POSTED");
        entry.setAccountingPeriod(period);
        Instant now = Instant.now();
        String username = resolveCurrentUsername();
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setPostedAt(now);
        entry.setCreatedBy(username);
        entry.setLastModifiedBy(username);
        entry.setPostedBy(username);
        Map<Account, BigDecimal> deltas = new java.util.HashMap<>();
        for (JournalLineSpec spec : specs) {
            JournalLine line = new JournalLine();
            line.setJournalEntry(entry);
            line.setAccount(spec.account());
            line.setDescription(spec.description());
            line.setDebit(spec.debit());
            line.setCredit(spec.credit());
            entry.getLines().add(line);
            deltas.merge(spec.account(), spec.debit().subtract(spec.credit()), BigDecimal::add);
        }
        JournalEntry saved = journalEntryRepository.save(entry);
        if (!deltas.isEmpty()) {
            for (Map.Entry<Account, BigDecimal> delta : deltas.entrySet()) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
                account.setBalance(current.add(delta.getValue()));
            }
            accountRepository.saveAll(deltas.keySet());
        }
        return saved;
    }

    private Account ensureEquityAccount(Company company, String code, String name) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account acct = new Account();
                    acct.setCompany(company);
                    acct.setCode(code);
                    acct.setName(name);
                    acct.setType(AccountType.EQUITY);
                    return accountRepository.save(acct);
                });
    }

    private record JournalLineSpec(Account account, BigDecimal debit, BigDecimal credit, String description) {}

    private void createReversalFor(JournalEntry source, AccountingPeriod period, Instant timestamp) {
        JournalEntry reversal = new JournalEntry();
        reversal.setCompany(source.getCompany());
        reversal.setReferenceNumber(source.getReferenceNumber() + "-REOPEN");
        reversal.setEntryDate(period.getEndDate());
        reversal.setMemo("Reversal of closing entry " + source.getReferenceNumber());
        reversal.setStatus("POSTED");
        reversal.setAccountingPeriod(period);
        String user = resolveCurrentUsername();
        reversal.setCreatedAt(timestamp);
        reversal.setUpdatedAt(timestamp);
        reversal.setPostedAt(timestamp);
        reversal.setCreatedBy(user);
        reversal.setLastModifiedBy(user);
        reversal.setPostedBy(user);
        Map<Account, BigDecimal> deltas = new java.util.HashMap<>();
        for (JournalLine line : source.getLines()) {
            JournalLine rev = new JournalLine();
            rev.setJournalEntry(reversal);
            rev.setAccount(line.getAccount());
            rev.setDescription("Reopen reversal");
            rev.setDebit(line.getCredit());
            rev.setCredit(line.getDebit());
            reversal.getLines().add(rev);
            deltas.merge(line.getAccount(), rev.getDebit().subtract(rev.getCredit()), BigDecimal::add);
        }
        JournalEntry saved = journalEntryRepository.save(reversal);
        if (!deltas.isEmpty()) {
            for (Map.Entry<Account, BigDecimal> delta : deltas.entrySet()) {
                Account account = delta.getKey();
                BigDecimal current = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
                account.setBalance(current.add(delta.getValue()));
            }
            accountRepository.saveAll(deltas.keySet());
        }
        source.setReversalEntry(saved);
        source.setStatus("REVERSED");
        journalEntryRepository.save(source);
    }

    @Transactional
    public AccountingPeriod ensurePeriod(Company company, LocalDate referenceDate) {
        LocalDate baseDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        LocalDate safeDate = baseDate.withDayOfMonth(1);
        int year = safeDate.getYear();
        int month = safeDate.getMonthValue();
        return accountingPeriodRepository.findByCompanyAndYearAndMonth(company, year, month)
                .orElseGet(() -> {
                    AccountingPeriod period = new AccountingPeriod();
                    period.setCompany(company);
                    period.setYear(year);
                    period.setMonth(month);
                    period.setStartDate(safeDate);
                    period.setEndDate(safeDate.plusMonths(1).minusDays(1));
                    period.setStatus(AccountingPeriodStatus.OPEN);
                    return accountingPeriodRepository.save(period);
                });
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId, LocalDate referenceDate) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        LocalDate effectiveDate = referenceDate == null ? resolveCurrentDate(company) : referenceDate;
        return ensurePeriod(company, effectiveDate);
    }

    private void ensureSurroundingPeriods(Company company) {
        LocalDate today = resolveCurrentDate(company);
        ensurePeriod(company, today);
        ensurePeriod(company, today.minusMonths(1));
        ensurePeriod(company, today.plusMonths(1));
    }

    private void assertChecklistComplete(Company company, AccountingPeriod period) {
        if (!period.isBankReconciled()) {
            throw new IllegalStateException("Bank reconciliation has not been confirmed for this period");
        }
        if (!period.isInventoryCounted()) {
            throw new IllegalStateException("Inventory count has not been confirmed for this period");
        }
        long drafts = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        if (drafts > 0) {
            throw new IllegalStateException("There are " + drafts + " draft entries in this period");
        }
    }

    private AccountingPeriodDto toDto(AccountingPeriod period) {
        return new AccountingPeriodDto(
                period.getId(),
                period.getYear(),
                period.getMonth(),
                period.getStartDate(),
                period.getEndDate(),
                period.getLabel(),
                period.getStatus().name(),
                period.isBankReconciled(),
                period.getBankReconciledAt(),
                period.getBankReconciledBy(),
                period.isInventoryCounted(),
                period.getInventoryCountedAt(),
                period.getInventoryCountedBy(),
                period.getClosedAt(),
                period.getClosedBy(),
                period.getChecklistNotes(),
                period.getLockedAt(),
                period.getLockedBy(),
                period.getLockReason(),
                period.getReopenedAt(),
                period.getReopenedBy(),
                period.getReopenReason(),
                period.getClosingJournalEntryId(),
                period.getChecklistNotes()
        );
    }

    private LocalDate resolveCurrentDate(Company company) {
        return companyClock.today(company);
    }

    private AccountingPeriod resolvePeriod(Company company, Long periodId) {
        if (periodId != null) {
            return companyEntityLookup.requireAccountingPeriod(company, periodId);
        }
        return accountingPeriodRepository.findFirstByCompanyAndStatusOrderByStartDateDesc(company, AccountingPeriodStatus.OPEN)
                .orElseGet(() -> ensurePeriod(company, resolveCurrentDate(company)));
    }

    private MonthEndChecklistDto buildChecklist(Company company, AccountingPeriod period) {
        long draftEntries = journalEntryRepository.countByCompanyAndEntryDateBetweenAndStatusIn(
                company,
                period.getStartDate(),
                period.getEndDate(),
                List.of("DRAFT", "PENDING"));
        boolean draftsCleared = draftEntries == 0;
        List<MonthEndChecklistItemDto> items = List.of(
                new MonthEndChecklistItemDto(
                        "bankReconciled",
                        "Bank accounts reconciled",
                        period.isBankReconciled(),
                        period.isBankReconciled() ? "Confirmed" : "Pending review"),
                new MonthEndChecklistItemDto(
                        "inventoryCounted",
                        "Inventory counted",
                        period.isInventoryCounted(),
                        period.isInventoryCounted() ? "Counts logged" : "Awaiting stock count"),
                new MonthEndChecklistItemDto(
                        "draftEntries",
                        "Draft entries cleared",
                        draftsCleared,
                        draftsCleared ? "All entries posted" : draftEntries + " draft entries remaining")
        );
        boolean ready = period.isBankReconciled() && period.isInventoryCounted() && draftsCleared;
        return new MonthEndChecklistDto(toDto(period), items, ready);
    }

    private String resolveCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }
}
