package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.SupplierLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.DealerBalanceView;
import com.bigbrightpaints.erp.modules.accounting.dto.SupplierBalanceView;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.reports.service.InventoryValuationQueryService;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@Service
public class AccountingPeriodSnapshotService {

  private final AccountingPeriodSnapshotRepository snapshotRepository;
  private final AccountingPeriodTrialBalanceLineRepository lineRepository;
  private final AccountRepository accountRepository;
  private final JournalLineRepository journalLineRepository;
  private final DealerRepository dealerRepository;
  private final SupplierRepository supplierRepository;
  private final DealerLedgerRepository dealerLedgerRepository;
  private final SupplierLedgerRepository supplierLedgerRepository;
  private final InventoryValuationQueryService inventoryValuationService;

  public AccountingPeriodSnapshotService(
      AccountingPeriodSnapshotRepository snapshotRepository,
      AccountingPeriodTrialBalanceLineRepository lineRepository,
      AccountRepository accountRepository,
      JournalLineRepository journalLineRepository,
      DealerRepository dealerRepository,
      SupplierRepository supplierRepository,
      DealerLedgerRepository dealerLedgerRepository,
      SupplierLedgerRepository supplierLedgerRepository,
      InventoryValuationQueryService inventoryValuationService) {
    this.snapshotRepository = snapshotRepository;
    this.lineRepository = lineRepository;
    this.accountRepository = accountRepository;
    this.journalLineRepository = journalLineRepository;
    this.dealerRepository = dealerRepository;
    this.supplierRepository = supplierRepository;
    this.dealerLedgerRepository = dealerLedgerRepository;
    this.supplierLedgerRepository = supplierLedgerRepository;
    this.inventoryValuationService = inventoryValuationService;
  }

  @Transactional
  public AccountingPeriodSnapshot captureSnapshot(
      Company company, AccountingPeriod period, String username) {
    if (company == null || period == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "company and period are required");
    }
    Optional<AccountingPeriodSnapshot> existing =
        snapshotRepository.findByCompanyAndPeriod(company, period);
    if (existing.isPresent()) {
      return existing.get();
    }
    LocalDate asOfDate = period.getEndDate();
    Map<Long, BalanceAggregate> aggregates = loadAccountAggregates(company, asOfDate);
    List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
    List<AccountingPeriodTrialBalanceLine> lines = new ArrayList<>();
    BigDecimal totalDebit = BigDecimal.ZERO;
    BigDecimal totalCredit = BigDecimal.ZERO;
    for (Account account : accounts) {
      BalanceAggregate aggregate = aggregates.get(account.getId());
      BigDecimal debitSum = aggregate != null ? aggregate.debit() : BigDecimal.ZERO;
      BigDecimal creditSum = aggregate != null ? aggregate.credit() : BigDecimal.ZERO;
      BigDecimal balance = debitSum.subtract(creditSum);
      TrialBalanceAmounts amounts = toTrialBalanceAmounts(account, balance);
      totalDebit = totalDebit.add(amounts.debit());
      totalCredit = totalCredit.add(amounts.credit());
      AccountingPeriodTrialBalanceLine line = new AccountingPeriodTrialBalanceLine();
      line.setAccountId(account.getId());
      line.setAccountCode(account.getCode());
      line.setAccountName(account.getName());
      line.setAccountType(account.getType());
      line.setDebit(amounts.debit());
      line.setCredit(amounts.credit());
      lines.add(line);
    }

    InventoryValuationQueryService.InventorySnapshot inventorySnapshot =
        inventoryValuationService.snapshotAsOf(company, asOfDate);
    BigDecimal arTotal = sumDealerBalances(company, asOfDate);
    BigDecimal apTotal = sumSupplierBalances(company, asOfDate);

    AccountingPeriodSnapshot snapshot = new AccountingPeriodSnapshot();
    snapshot.setCompany(company);
    snapshot.setPeriod(period);
    snapshot.setAsOfDate(asOfDate);
    snapshot.setCreatedAt(CompanyTime.now(company));
    snapshot.setCreatedBy(username);
    snapshot.setTrialBalanceTotalDebit(round(totalDebit));
    snapshot.setTrialBalanceTotalCredit(round(totalCredit));
    snapshot.setInventoryTotalValue(round(inventorySnapshot.totalValue()));
    snapshot.setInventoryLowStock(inventorySnapshot.lowStockItems());
    snapshot.setArSubledgerTotal(round(arTotal));
    snapshot.setApSubledgerTotal(round(apTotal));

    AccountingPeriodSnapshot saved = snapshotRepository.save(snapshot);
    for (AccountingPeriodTrialBalanceLine line : lines) {
      line.setSnapshot(saved);
    }
    if (!lines.isEmpty()) {
      lineRepository.saveAll(lines);
    }
    return saved;
  }

  @Transactional
  public void deleteSnapshotForPeriod(Company company, AccountingPeriod period) {
    if (company == null || period == null) {
      return;
    }
    snapshotRepository
        .findByCompanyAndPeriod(company, period)
        .ifPresent(
            snapshot -> {
              lineRepository.deleteBySnapshot(snapshot);
              snapshotRepository.delete(snapshot);
            });
  }

  private Map<Long, BalanceAggregate> loadAccountAggregates(Company company, LocalDate asOfDate) {
    List<Object[]> rows = journalLineRepository.summarizeByAccountUpTo(company, asOfDate);
    Map<Long, BalanceAggregate> aggregates = new HashMap<>();
    for (Object[] row : rows) {
      if (row == null || row.length < 3 || row[0] == null) {
        continue;
      }
      Long accountId = (Long) row[0];
      BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
      aggregates.put(accountId, new BalanceAggregate(debit, credit));
    }
    return aggregates;
  }

  private BigDecimal sumDealerBalances(Company company, LocalDate asOfDate) {
    List<Long> dealerIds =
        dealerRepository.findByCompanyOrderByNameAsc(company).stream()
            .map(dealer -> dealer.getId())
            .toList();
    if (dealerIds.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return dealerLedgerRepository.aggregateBalancesUpTo(company, dealerIds, asOfDate).stream()
        .map(DealerBalanceView::balance)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal sumSupplierBalances(Company company, LocalDate asOfDate) {
    List<Long> supplierIds =
        supplierRepository.findByCompanyOrderByNameAsc(company).stream()
            .map(supplier -> supplier.getId())
            .toList();
    if (supplierIds.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return supplierLedgerRepository.aggregateBalancesUpTo(company, supplierIds, asOfDate).stream()
        .map(SupplierBalanceView::balance)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private TrialBalanceAmounts toTrialBalanceAmounts(Account account, BigDecimal balance) {
    AccountType type = account != null ? account.getType() : null;
    boolean debitNormal = type == null || type.isDebitNormalBalance();
    BigDecimal normalized = debitNormal ? balance : balance.negate();
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

  private BigDecimal round(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
  }

  private record BalanceAggregate(BigDecimal debit, BigDecimal credit) {}

  private record TrialBalanceAmounts(BigDecimal debit, BigDecimal credit) {}
}
