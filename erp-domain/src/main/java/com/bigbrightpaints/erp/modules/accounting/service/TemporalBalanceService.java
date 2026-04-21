package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;

/**
 * Service for temporal balance queries. Closed periods rely on snapshots; open periods
 * use journal lines for deterministic as-of views.
 */
@Service
@Transactional(readOnly = true)
public class TemporalBalanceService {

  private final AccountRepository accountRepository;
  private final CompanyContextService companyContextService;
  private final AccountingPeriodRepository accountingPeriodRepository;
  private final AccountingPeriodSnapshotRepository snapshotRepository;
  private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
  private final JournalLineRepository journalLineRepository;
  private final CompanyClock companyClock;

  public TemporalBalanceService(
      AccountRepository accountRepository,
      CompanyContextService companyContextService,
      AccountingPeriodRepository accountingPeriodRepository,
      AccountingPeriodSnapshotRepository snapshotRepository,
      AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
      JournalLineRepository journalLineRepository,
      CompanyClock companyClock) {
    this.accountRepository = accountRepository;
    this.companyContextService = companyContextService;
    this.accountingPeriodRepository = accountingPeriodRepository;
    this.snapshotRepository = snapshotRepository;
    this.snapshotLineRepository = snapshotLineRepository;
    this.journalLineRepository = journalLineRepository;
    this.companyClock = companyClock;
  }

  /**
   * Get account balance as of a specific date (end of day)
   */
  public BigDecimal getBalanceAsOfDate(Long accountId, LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    Account account = requireCompanyAccount(company, accountId);
    AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
    if (snapshot != null) {
      return snapshotLineRepository
          .findBySnapshotAndAccountId(snapshot, accountId)
          .map(
              line -> normalizeNetBalance(line.getDebit(), line.getCredit(), line.getAccountType()))
          .orElse(BigDecimal.ZERO);
    }
    BigDecimal rawBalance =
        safe(journalLineRepository.netBalanceUpTo(company, accountId, asOfDate));
    return normalizeNetBalance(rawBalance, account.getType());
  }

  /**
   * Get account balance as of a specific timestamp
   */
  public BigDecimal getBalanceAsOfTimestamp(Long accountId, Instant asOf) {
    Company company = companyContextService.requireCurrentCompany();
    LocalDate asOfDate = companyClock.dateForInstant(company, asOf);
    return getBalanceAsOfDate(accountId, asOfDate);
  }

  /**
   * Get balances for multiple accounts as of a date
   */
  public Map<Long, BigDecimal> getBalancesAsOfDate(List<Long> accountIds, LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
    if (snapshot != null) {
      Map<Long, BigDecimal> balances = new HashMap<>();
      List<AccountingPeriodTrialBalanceLine> lines =
          snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(snapshot);
      Map<Long, BigDecimal> snapshotBalances =
          lines.stream()
              .filter(line -> line.getAccountId() != null)
              .collect(
                  Collectors.toMap(
                      AccountingPeriodTrialBalanceLine::getAccountId,
                      line ->
                          normalizeNetBalance(
                              line.getDebit(), line.getCredit(), line.getAccountType()),
                      BigDecimal::add));
      for (Long accountId : accountIds) {
        if (accountId == null) {
          continue;
        }
        balances.put(accountId, snapshotBalances.getOrDefault(accountId, BigDecimal.ZERO));
      }
      return balances;
    }
    Map<Long, BigDecimal> balances = summarizeBalances(company, asOfDate);
    Map<Long, AccountType> accountTypeById =
        accountRepository.findByCompanyOrderByCodeAsc(company).stream()
            .collect(Collectors.toMap(Account::getId, Account::getType));
    Map<Long, BigDecimal> filtered = new HashMap<>();
    for (Long accountId : accountIds) {
      if (accountId == null) {
        continue;
      }
      filtered.put(
          accountId,
          normalizeNetBalance(
              balances.getOrDefault(accountId, BigDecimal.ZERO), accountTypeById.get(accountId)));
    }
    return filtered;
  }

  /**
   * Get trial balance as of a specific date
   */
  public TrialBalanceSnapshot getTrialBalanceAsOf(LocalDate asOfDate) {
    Company company = companyContextService.requireCurrentCompany();
    AccountingPeriodSnapshot snapshot = resolveClosedSnapshot(company, asOfDate);
    if (snapshot != null) {
      List<AccountingPeriodTrialBalanceLine> lines =
          snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(snapshot);
      List<TrialBalanceEntry> entries =
          lines.stream()
              .map(
                  line ->
                      new TrialBalanceEntry(
                          line.getAccountId(),
                          line.getAccountCode(),
                          line.getAccountName(),
                          line.getAccountType() != null ? line.getAccountType().name() : null,
                          safe(line.getDebit()),
                          safe(line.getCredit())))
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
    Map<Long, BigDecimal> balances = summarizeBalances(company, asOfDate);
    for (Account account : accounts) {
      BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
      if (balance.compareTo(BigDecimal.ZERO) != 0) {
        TrialBalanceAmounts amounts = toTrialBalanceAmounts(account, balance);
        entries.add(
            new TrialBalanceEntry(
                account.getId(),
                account.getCode(),
                account.getName(),
                account.getType() != null ? account.getType().name() : null,
                amounts.debit(),
                amounts.credit()));

        totalDebits = totalDebits.add(amounts.debit());
        totalCredits = totalCredits.add(amounts.credit());
      }
    }

    return new TrialBalanceSnapshot(asOfDate, entries, totalDebits, totalCredits);
  }

  /**
   * Get account activity (movements) for a date range
   */
  public AccountActivityReport getAccountActivity(
      Long accountId, LocalDate startDate, LocalDate endDate) {
    Company company = companyContextService.requireCurrentCompany();
    Account account = requireCompanyAccount(company, accountId);
    AccountType accountType = account.getType();

    // Get opening balance (balance as of day before start)
    BigDecimal openingBalance =
        normalizeNetBalance(
            journalLineRepository.netBalanceUpTo(company, accountId, startDate.minusDays(1)),
            accountType);

    // Get all movements in the period
    List<JournalLine> movements =
        journalLineRepository.findLinesForAccountBetween(company, accountId, startDate, endDate);

    List<AccountMovement> movementList = new ArrayList<>();
    BigDecimal runningBalance = openingBalance;
    BigDecimal totalDebits = BigDecimal.ZERO;
    BigDecimal totalCredits = BigDecimal.ZERO;
    for (JournalLine line : movements) {
      JournalEntry entry = line.getJournalEntry();
      BigDecimal debit = safe(line.getDebit());
      BigDecimal credit = safe(line.getCredit());
      BigDecimal delta = normalizeNetBalance(debit.subtract(credit), accountType);
      runningBalance = runningBalance.add(delta);
      totalDebits = totalDebits.add(debit);
      totalCredits = totalCredits.add(credit);
      movementList.add(
          new AccountMovement(
              entry != null ? entry.getEntryDate() : null,
              resolveEntryTimestamp(entry),
              entry != null ? entry.getReferenceNumber() : null,
              resolveEntryDescription(entry, line),
              debit,
              credit,
              runningBalance));
    }

    BigDecimal closingBalance = movements.isEmpty() ? openingBalance : runningBalance;
    BigDecimal netMovement = normalizeNetBalance(totalDebits.subtract(totalCredits), accountType);

    return new AccountActivityReport(
        account.getCode(),
        account.getName(),
        startDate,
        endDate,
        openingBalance,
        closingBalance,
        totalDebits,
        totalCredits,
        netMovement,
        movementList);
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

  private Account requireCompanyAccount(Company company, Long accountId) {
    return accountRepository
        .findByCompanyAndId(company, accountId)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Account not found"));
  }

  private AccountingPeriodSnapshot resolveClosedSnapshot(Company company, LocalDate asOfDate) {
    if (company == null || asOfDate == null) {
      return null;
    }
    Optional<AccountingPeriod> period =
        accountingPeriodRepository.findByCompanyAndYearAndMonth(
            company, asOfDate.getYear(), asOfDate.getMonthValue());
    if (period.isEmpty() || period.get().getStatus() != AccountingPeriodStatus.CLOSED) {
      return null;
    }
    return snapshotRepository
        .findByCompanyAndPeriod(company, period.get())
        .orElseThrow(
            () ->
                new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Closed period snapshot is required for temporal queries")
                    .withDetail("companyId", company.getId())
                    .withDetail("periodId", period.get().getId())
                    .withDetail("asOfDate", asOfDate));
  }

  private Map<Long, BigDecimal> summarizeBalances(Company company, LocalDate asOfDate) {
    Map<Long, BigDecimal> balances = new HashMap<>();
    List<Object[]> rows = journalLineRepository.summarizeByAccountUpTo(company, asOfDate);
    for (Object[] row : rows) {
      if (row == null || row.length < 3 || row[0] == null) {
        continue;
      }
      Long accountId = (Long) row[0];
      BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
      balances.put(accountId, debit.subtract(credit));
    }
    return balances;
  }

  private TrialBalanceAmounts toTrialBalanceAmounts(Account account, BigDecimal balance) {
    AccountType type = account != null ? account.getType() : null;
    boolean debitNormal = type == null || type.isDebitNormalBalance();
    BigDecimal normalized = debitNormal ? safe(balance) : safe(balance).negate();
    BigDecimal debit;
    BigDecimal credit;
    if (normalized.compareTo(BigDecimal.ZERO) >= 0) {
      debit = debitNormal ? normalized : BigDecimal.ZERO;
      credit = debitNormal ? BigDecimal.ZERO : normalized;
    } else {
      BigDecimal amount = normalized.abs();
      debit = debitNormal ? BigDecimal.ZERO : amount;
      credit = debitNormal ? amount : BigDecimal.ZERO;
    }
    return new TrialBalanceAmounts(debit, credit);
  }

  private BigDecimal normalizeNetBalance(
      BigDecimal debit, BigDecimal credit, AccountType accountType) {
    return normalizeNetBalance(safe(debit).subtract(safe(credit)), accountType);
  }

  private BigDecimal normalizeNetBalance(BigDecimal netBalance, AccountType accountType) {
    BigDecimal normalized = safe(netBalance);
    if (accountType != null && !accountType.isDebitNormalBalance()) {
      return normalized.negate();
    }
    return normalized;
  }

  private Instant resolveEntryTimestamp(JournalEntry entry) {
    if (entry == null) {
      return null;
    }
    if (entry.getPostedAt() != null) {
      return entry.getPostedAt();
    }
    return entry.getCreatedAt();
  }

  private String resolveEntryDescription(JournalEntry entry, JournalLine line) {
    if (line != null && line.getDescription() != null && !line.getDescription().isBlank()) {
      return line.getDescription();
    }
    if (entry != null && entry.getMemo() != null && !entry.getMemo().isBlank()) {
      return entry.getMemo();
    }
    return null;
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  // DTOs for temporal queries
  public record TrialBalanceSnapshot(
      LocalDate asOfDate,
      List<TrialBalanceEntry> entries,
      BigDecimal totalDebits,
      BigDecimal totalCredits) {}

  public record TrialBalanceEntry(
      Long accountId,
      String accountCode,
      String accountName,
      String accountType,
      BigDecimal debit,
      BigDecimal credit) {}

  public record AccountActivityReport(
      String accountCode,
      String accountName,
      LocalDate startDate,
      LocalDate endDate,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      BigDecimal totalDebits,
      BigDecimal totalCredits,
      BigDecimal netMovement,
      List<AccountMovement> movements) {}

  public record AccountMovement(
      LocalDate date,
      Instant timestamp,
      String reference,
      String description,
      BigDecimal debit,
      BigDecimal credit,
      BigDecimal runningBalance) {}

  public record BalanceComparison(
      Long accountId,
      LocalDate date1,
      BigDecimal balance1,
      LocalDate date2,
      BigDecimal balance2,
      BigDecimal change) {}

  private record TrialBalanceAmounts(BigDecimal debit, BigDecimal credit) {}
}
