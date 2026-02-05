package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEvent;
import com.bigbrightpaints.erp.modules.accounting.event.AccountingEventRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for temporal balance queries. Closed periods rely on snapshots; open periods
 * can fall back to event data for exploratory views.
 */
@Service
@Transactional(readOnly = true)
public class TemporalBalanceService {

    private final AccountingEventRepository eventRepository;
    private final AccountRepository accountRepository;
    private final CompanyContextService companyContextService;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final AccountingPeriodSnapshotRepository snapshotRepository;
    private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;

    public TemporalBalanceService(AccountingEventRepository eventRepository,
                                  AccountRepository accountRepository,
                                  CompanyContextService companyContextService,
                                  AccountingPeriodRepository accountingPeriodRepository,
                                  AccountingPeriodSnapshotRepository snapshotRepository,
                                  AccountingPeriodTrialBalanceLineRepository snapshotLineRepository) {
        this.eventRepository = eventRepository;
        this.accountRepository = accountRepository;
        this.companyContextService = companyContextService;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotLineRepository = snapshotLineRepository;
    }

    /**
     * Get account balance as of a specific date (end of day)
     */
    public BigDecimal getBalanceAsOfDate(Long accountId, LocalDate asOfDate) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
        if (snapshot != null) {
            return snapshotLineRepository.findBySnapshotAndAccountId(snapshot, accountId)
                    .map(line -> safe(line.getDebit()).subtract(safe(line.getCredit())))
                    .orElse(BigDecimal.ZERO);
        }
        return eventRepository.findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
                        company, accountId, asOfDate)
                .map(AccountingEvent::getBalanceAfter)
                .orElseGet(() -> getCurrentBalance(company, accountId));
    }

    /**
     * Get account balance as of a specific timestamp
     */
    public BigDecimal getBalanceAsOfTimestamp(Long accountId, Instant asOf) {
        Company company = companyContextService.requireCurrentCompany();
        return eventRepository.findFirstByCompanyAndAccountIdAndEventTimestampLessThanEqualOrderByEventTimestampDescSequenceNumberDesc(
                        company, accountId, asOf)
                .map(AccountingEvent::getBalanceAfter)
                .orElseGet(() -> getCurrentBalance(company, accountId));
    }

    /**
     * Get balances for multiple accounts as of a date
     */
    public Map<Long, BigDecimal> getBalancesAsOfDate(List<Long> accountIds, LocalDate asOfDate) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
        if (snapshot != null) {
            Map<Long, BigDecimal> balances = new HashMap<>();
            List<AccountingPeriodTrialBalanceLine> lines = snapshotLineRepository
                    .findBySnapshotOrderByAccountCodeAsc(snapshot);
            Map<Long, BigDecimal> snapshotBalances = lines.stream()
                    .filter(line -> line.getAccountId() != null)
                    .collect(Collectors.toMap(
                            AccountingPeriodTrialBalanceLine::getAccountId,
                            line -> safe(line.getDebit()).subtract(safe(line.getCredit())),
                            BigDecimal::add));
            for (Long accountId : accountIds) {
                if (accountId == null) {
                    continue;
                }
                balances.put(accountId, snapshotBalances.getOrDefault(accountId, BigDecimal.ZERO));
            }
            return balances;
        }
        Map<Long, BigDecimal> balances = new HashMap<>();
        
        for (Long accountId : accountIds) {
            BigDecimal balance = eventRepository.findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
                            company, accountId, asOfDate)
                    .map(AccountingEvent::getBalanceAfter)
                    .orElseGet(() -> getCurrentBalance(company, accountId));
            balances.put(accountId, balance);
        }
        
        return balances;
    }

    /**
     * Get trial balance as of a specific date
     */
    public TrialBalanceSnapshot getTrialBalanceAsOf(LocalDate asOfDate) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
        if (snapshot != null) {
            List<AccountingPeriodTrialBalanceLine> lines = snapshotLineRepository
                    .findBySnapshotOrderByAccountCodeAsc(snapshot);
            List<TrialBalanceEntry> entries = lines.stream()
                    .map(line -> new TrialBalanceEntry(
                            line.getAccountId(),
                            line.getAccountCode(),
                            line.getAccountName(),
                            line.getAccountType() != null ? line.getAccountType().name() : null,
                            safe(line.getDebit()),
                            safe(line.getCredit())
                    ))
                    .collect(Collectors.toList());
            return new TrialBalanceSnapshot(
                    snapshot.getAsOfDate(),
                    entries,
                    safe(snapshot.getTrialBalanceTotalDebit()),
                    safe(snapshot.getTrialBalanceTotalCredit()));
        }
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        
        List<TrialBalanceEntry> entries = new ArrayList<>();
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        
        for (Account account : accounts) {
            BigDecimal balance = eventRepository.findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
                            company, account.getId(), asOfDate)
                    .map(AccountingEvent::getBalanceAfter)
                    .orElse(BigDecimal.ZERO);
            
            if (balance.compareTo(BigDecimal.ZERO) != 0) {
                boolean isDebit = account.getType().isDebitNormalBalance() 
                        ? balance.compareTo(BigDecimal.ZERO) > 0
                        : balance.compareTo(BigDecimal.ZERO) < 0;
                
                BigDecimal debit = isDebit ? balance.abs() : BigDecimal.ZERO;
                BigDecimal credit = isDebit ? BigDecimal.ZERO : balance.abs();
                
                entries.add(new TrialBalanceEntry(
                        account.getId(),
                        account.getCode(),
                        account.getName(),
                        account.getType().name(),
                        debit,
                        credit
                ));
                
                totalDebits = totalDebits.add(debit);
                totalCredits = totalCredits.add(credit);
            }
        }
        
        return new TrialBalanceSnapshot(asOfDate, entries, totalDebits, totalCredits);
    }

    /**
     * Get account activity (movements) for a date range
     */
    public AccountActivityReport getAccountActivity(Long accountId, LocalDate startDate, LocalDate endDate) {
        Company company = companyContextService.requireCurrentCompany();
        Account account = accountRepository.findByCompanyAndId(company, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        
        // Get opening balance (balance as of day before start)
        BigDecimal openingBalance = eventRepository.findFirstByCompanyAndAccountIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescEventTimestampDescSequenceNumberDesc(
                company, accountId, startDate.minusDays(1))
                .map(AccountingEvent::getBalanceAfter)
                .orElse(BigDecimal.ZERO);
        
        // Get all movements in the period
        List<AccountingEvent> movements = eventRepository
                .findByCompanyAndAccountIdAndEffectiveDateBetweenOrderByEventTimestampAsc(
                        company, accountId, startDate, endDate);
        
        List<AccountMovement> movementList = movements.stream()
                .map(e -> new AccountMovement(
                        e.getEffectiveDate(),
                        e.getEventTimestamp(),
                        e.getJournalReference(),
                        e.getDescription(),
                        e.getDebitAmount() != null ? e.getDebitAmount() : BigDecimal.ZERO,
                        e.getCreditAmount() != null ? e.getCreditAmount() : BigDecimal.ZERO,
                        e.getBalanceAfter()
                ))
                .collect(Collectors.toList());
        
        BigDecimal closingBalance = movements.isEmpty() 
                ? openingBalance 
                : movements.get(movements.size() - 1).getBalanceAfter();
        
        BigDecimal totalDebits = movements.stream()
                .map(e -> e.getDebitAmount() != null ? e.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalCredits = movements.stream()
                .map(e -> e.getCreditAmount() != null ? e.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return new AccountActivityReport(
                account.getCode(),
                account.getName(),
                startDate,
                endDate,
                openingBalance,
                closingBalance,
                totalDebits,
                totalCredits,
                movementList
        );
    }

    /**
     * Compare balances between two dates
     */
    public BalanceComparison compareBalances(Long accountId, LocalDate date1, LocalDate date2) {
        BigDecimal balance1 = getBalanceAsOfDate(accountId, date1);
        BigDecimal balance2 = getBalanceAsOfDate(accountId, date2);
        BigDecimal change = balance2.subtract(balance1);
        
        return new BalanceComparison(accountId, date1, balance1, date2, balance2, change);
    }

    private BigDecimal getCurrentBalance(Company company, Long accountId) {
        return accountRepository.findByCompanyAndId(company, accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    private AccountingPeriodSnapshot resolveClosedSnapshot(Company company, LocalDate asOfDate) {
        if (company == null || asOfDate == null) {
            return null;
        }
        Optional<AccountingPeriod> period = accountingPeriodRepository
                .findByCompanyAndYearAndMonth(company, asOfDate.getYear(), asOfDate.getMonthValue());
        if (period.isEmpty() || period.get().getStatus() != AccountingPeriodStatus.CLOSED) {
            return null;
        }
        return snapshotRepository.findByCompanyAndPeriod(company, period.get()).orElse(null);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // DTOs for temporal queries
    public record TrialBalanceSnapshot(
            LocalDate asOfDate,
            List<TrialBalanceEntry> entries,
            BigDecimal totalDebits,
            BigDecimal totalCredits
    ) {}

    public record TrialBalanceEntry(
            Long accountId,
            String accountCode,
            String accountName,
            String accountType,
            BigDecimal debit,
            BigDecimal credit
    ) {}

    public record AccountActivityReport(
            String accountCode,
            String accountName,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalDebits,
            BigDecimal totalCredits,
            List<AccountMovement> movements
    ) {}

    public record AccountMovement(
            LocalDate date,
            Instant timestamp,
            String reference,
            String description,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal runningBalance
    ) {}

    public record BalanceComparison(
            Long accountId,
            LocalDate date1,
            BigDecimal balance1,
            LocalDate date2,
            BigDecimal balance2,
            BigDecimal change
    ) {}
}
