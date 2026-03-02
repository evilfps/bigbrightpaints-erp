package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ReportService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final AccountingPeriodSnapshotRepository snapshotRepository;
    private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final ProductionLogRepository productionLogRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private final InventoryValuationService inventoryValuationService;
    private final TrialBalanceReportQueryService trialBalanceReportQueryService;
    private final ProfitLossReportQueryService profitLossReportQueryService;
    private final BalanceSheetReportQueryService balanceSheetReportQueryService;
    private final AgedDebtorsReportQueryService agedDebtorsReportQueryService;
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    public ReportService(CompanyContextService companyContextService,
                         AccountRepository accountRepository,
                         AccountingPeriodRepository accountingPeriodRepository,
                         AccountingPeriodSnapshotRepository snapshotRepository,
                         AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
                         DealerRepository dealerRepository,
                         DealerLedgerService dealerLedgerService,
                         DealerLedgerRepository dealerLedgerRepository,
                         JournalEntryRepository journalEntryRepository,
                         JournalLineRepository journalLineRepository,
                         ProductionLogRepository productionLogRepository,
                         CompanyEntityLookup companyEntityLookup,
                         CompanyClock companyClock,
                         InventoryValuationService inventoryValuationService,
                         TrialBalanceReportQueryService trialBalanceReportQueryService,
                         ProfitLossReportQueryService profitLossReportQueryService,
                         BalanceSheetReportQueryService balanceSheetReportQueryService,
                         AgedDebtorsReportQueryService agedDebtorsReportQueryService) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotLineRepository = snapshotLineRepository;
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.productionLogRepository = productionLogRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
        this.inventoryValuationService = inventoryValuationService;
        this.trialBalanceReportQueryService = trialBalanceReportQueryService;
        this.profitLossReportQueryService = profitLossReportQueryService;
        this.balanceSheetReportQueryService = balanceSheetReportQueryService;
        this.agedDebtorsReportQueryService = agedDebtorsReportQueryService;
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet() {
        return balanceSheet(defaultRequest());
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet(LocalDate asOfDate) {
        return balanceSheet(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet(FinancialReportQueryRequest request) {
        return balanceSheetReportQueryService.generate(request != null ? request : defaultRequest());
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss() {
        return profitLoss(defaultRequest());
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss(LocalDate asOfDate) {
        return profitLoss(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss(FinancialReportQueryRequest request) {
        return profitLossReportQueryService.generate(request != null ? request : defaultRequest());
    }

    @Transactional(readOnly = true)
    public CashFlowDto cashFlow() {
        ReportContext context = resolveReportContext(null);
        Company company = context.company();
        List<JournalEntry> entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        BigDecimal operating = BigDecimal.ZERO;
        BigDecimal investing = BigDecimal.ZERO;
        BigDecimal financing = BigDecimal.ZERO;
        for (JournalEntry entry : entries) {
            if (!"POSTED".equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            List<JournalLine> lines = entry.getLines();
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            for (JournalLine line : entry.getLines()) {
                if (!isCashAccount(line.getAccount())) {
                    continue;
                }
                BigDecimal delta = safe(line.getDebit()).subtract(safe(line.getCredit()));
                if (delta.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                Map<CashFlowSection, BigDecimal> allocations = resolveCashFlowAllocations(line, lines, delta);
                operating = operating.add(safe(allocations.get(CashFlowSection.OPERATING)));
                investing = investing.add(safe(allocations.get(CashFlowSection.INVESTING)));
                financing = financing.add(safe(allocations.get(CashFlowSection.FINANCING)));
            }
        }
        BigDecimal net = operating.add(investing).add(financing);
        return new CashFlowDto(operating, investing, financing, net, context.metadata());
    }

    @Transactional(readOnly = true)
    public InventoryValuationDto inventoryValuationAsOf(LocalDate asOfDate) {
        return inventoryValuation(asOfDate);
    }

    @Transactional(readOnly = true)
    public InventoryValuationDto inventoryValuation() {
        return inventoryValuation(null);
    }

    private InventoryValuationDto inventoryValuation(LocalDate asOfDate) {
        ReportContext context = resolveReportContext(asOfDate);
        if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
            AccountingPeriodSnapshot snapshot = context.snapshot();
            return new InventoryValuationDto(snapshot.getInventoryTotalValue(),
                    snapshot.getInventoryLowStock(),
                    context.metadata());
        }
        Company company = context.company();
        if (context.source() == ReportSource.AS_OF) {
            InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.snapshotAsOf(company, context.asOfDate());
            return new InventoryValuationDto(snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
        }
        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);
        return new InventoryValuationDto(snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
    }

    @Transactional(readOnly = true)
    public List<AccountStatementEntryDto> accountStatement() {
        Company company = companyContextService.requireCurrentCompany();
        var dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
        var balances = dealerLedgerService.currentBalances(dealers.stream().map(Dealer::getId).toList());
        if (balances == null) {
            throw new ApplicationException(
                    ErrorCode.SYSTEM_INTERNAL_ERROR,
                    "Dealer balance snapshot unavailable for account statement");
        }
        return dealers.stream()
                .map(dealer -> {
                    BigDecimal outstanding = balances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
                    DealerLedgerEntry latest = dealerLedgerRepository
                            .findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(company, dealer)
                            .orElse(null);
                    LocalDate entryDate = latest != null ? latest.getEntryDate() : companyClock.today(company);
                    String reference = latest != null && latest.getReferenceNumber() != null
                            ? latest.getReferenceNumber()
                            : "BALANCE";
                    BigDecimal debit = latest != null ? safe(latest.getDebit()) : outstanding;
                    BigDecimal credit = latest != null ? safe(latest.getCredit()) : BigDecimal.ZERO;
                    Long journalEntryId = latest != null && latest.getJournalEntry() != null
                            ? latest.getJournalEntry().getId()
                            : null;
                    return new AccountStatementEntryDto(
                            dealer.getName(),
                            entryDate,
                            reference,
                            debit,
                            credit,
                            outstanding,
                            journalEntryId);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgedDebtorDto> agedDebtors() {
        return agedDebtors(defaultRequest());
    }

    @Transactional(readOnly = true)
    public List<AgedDebtorDto> agedDebtors(FinancialReportQueryRequest request) {
        return agedDebtorsReportQueryService.generate(request != null ? request : defaultRequest());
    }

    @Transactional(readOnly = true)
    public ReconciliationSummaryDto inventoryReconciliation() {
        Company company = companyContextService.requireCurrentCompany();
        InventoryValuationService.InventorySnapshot totals = inventoryValuationService.currentSnapshot(company);
        BigDecimal ledgerBalance = resolveInventoryLedgerBalance(company);
        BigDecimal variance = totals.totalValue().subtract(ledgerBalance);
        return new ReconciliationSummaryDto(totals.totalValue(), ledgerBalance, variance);
    }

    @Transactional(readOnly = true)
    public List<BalanceWarningDto> balanceWarnings() {
        Company company = companyContextService.requireCurrentCompany();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        List<BalanceWarningDto> warnings = new ArrayList<>();
        for (Account account : accounts) {
            BigDecimal balance = safe(account.getBalance());
            AccountType type = account.getType();
            String reason = null;
            String severity = "INFO";
            if (type == AccountType.ASSET && balance.compareTo(BigDecimal.ZERO) < 0) {
                reason = "Asset account has a credit balance";
                severity = "HIGH";
            } else if (type == AccountType.LIABILITY && balance.compareTo(BigDecimal.ZERO) > 0) {
                reason = "Liability account has a debit balance";
                severity = "HIGH";
            } else if (type == AccountType.REVENUE && balance.compareTo(BigDecimal.ZERO) > 0) {
                reason = "Revenue account shows a debit balance";
                severity = "MEDIUM";
            } else if ((type == AccountType.EXPENSE || type == AccountType.COGS) && balance.compareTo(BigDecimal.ZERO) < 0) {
                reason = "Expense account shows a credit balance";
                severity = "MEDIUM";
            }
            if (reason != null) {
                warnings.add(new BalanceWarningDto(account.getId(), account.getCode(), account.getName(), balance, severity, reason));
            }
        }
        return warnings;
    }

    @Transactional(readOnly = true)
    public ReconciliationDashboardDto reconciliationDashboard(Long bankAccountId, BigDecimal statementBalance) {
        Company company = companyContextService.requireCurrentCompany();
        Account bankAccount = companyEntityLookup.requireAccount(company, bankAccountId);
        InventoryValuationService.InventorySnapshot totals = inventoryValuationService.currentSnapshot(company);
        BigDecimal ledgerInventoryBalance = resolveInventoryLedgerBalance(company);
        BigDecimal physicalInventoryValue = totals.totalValue();
        BigDecimal inventoryVariance = physicalInventoryValue.subtract(ledgerInventoryBalance);
        BigDecimal bankLedgerBalance = safe(bankAccount.getBalance());
        BigDecimal bankStatementBalance = statementBalance != null ? statementBalance : bankLedgerBalance;
        BigDecimal bankVariance = bankLedgerBalance.subtract(bankStatementBalance);
        boolean inventoryBalanced = inventoryVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;
        boolean bankBalanced = bankVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;
        return new ReconciliationDashboardDto(
                ledgerInventoryBalance,
                physicalInventoryValue,
                inventoryVariance,
                bankLedgerBalance,
                bankStatementBalance,
                bankVariance,
                inventoryBalanced,
                bankBalanced,
                balanceWarnings());
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance() {
        return trialBalance(defaultRequest());
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance(LocalDate asOfDate) {
        return trialBalance(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance(FinancialReportQueryRequest request) {
        return trialBalanceReportQueryService.generate(request != null ? request : defaultRequest());
    }

    private FinancialReportQueryRequest defaultRequest() {
        return new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private ReportContext resolveReportContext(LocalDate asOfDate) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
        AccountingPeriod period = accountingPeriodRepository
                .findByCompanyAndYearAndMonth(company, effectiveDate.getYear(), effectiveDate.getMonthValue())
                .orElse(null);
        AccountingPeriodSnapshot snapshot = null;
        ReportSource source;
        if (period != null && period.getStatus() == AccountingPeriodStatus.CLOSED) {
            snapshot = snapshotRepository.findByCompanyAndPeriod(company, period)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                            "Closed period snapshot is required for reports")
                            .withDetail("companyId", company.getId())
                            .withDetail("periodId", period.getId())
                            .withDetail("asOfDate", effectiveDate));
            source = ReportSource.SNAPSHOT;
        } else if (asOfDate != null) {
            source = ReportSource.AS_OF;
        } else {
            source = ReportSource.LIVE;
        }
        return new ReportContext(company, effectiveDate, period, snapshot, source);
    }

    private List<TrialBalanceLine> resolveTrialBalanceLines(ReportContext context) {
        if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
            return snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(context.snapshot()).stream()
                    .map(line -> new TrialBalanceLine(
                            line.getAccountId(),
                            line.getAccountCode(),
                            line.getAccountName(),
                            line.getAccountType(),
                            safe(line.getDebit()),
                            safe(line.getCredit())
                    ))
                    .toList();
        }
        Company company = context.company();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        if (context.source() == ReportSource.AS_OF) {
            Map<Long, BigDecimal> balances = summarizeBalances(company, context.asOfDate());
            return accounts.stream()
                    .map(account -> toTrialBalanceLine(account, balances.getOrDefault(account.getId(), BigDecimal.ZERO)))
                    .toList();
        }
        return accounts.stream()
                .map(account -> toTrialBalanceLine(account, safe(account.getBalance())))
                .toList();
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

    private TrialBalanceLine toTrialBalanceLine(Account account, BigDecimal balance) {
        BigDecimal safeBalance = safe(balance);
        AccountType type = account != null ? account.getType() : null;
        boolean debitNormal = type == null || type.isDebitNormalBalance();
        BigDecimal normalized = debitNormal ? safeBalance : safeBalance.negate();
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
        return new TrialBalanceLine(
                account != null ? account.getId() : null,
                account != null ? account.getCode() : null,
                account != null ? account.getName() : null,
                type,
                debit,
                credit);
    }

    private record TrialBalanceLine(
            Long accountId,
            String code,
            String name,
            AccountType type,
            BigDecimal debit,
            BigDecimal credit
    ) {
    }

    private record ReportContext(
            Company company,
            LocalDate asOfDate,
            AccountingPeriod period,
            AccountingPeriodSnapshot snapshot,
            ReportSource source
    ) {
        ReportMetadata metadata() {
            Long periodId = period != null ? period.getId() : null;
            String status = period != null && period.getStatus() != null ? period.getStatus().name() : null;
            Long snapshotId = snapshot != null ? snapshot.getId() : null;
            return new ReportMetadata(asOfDate, source, periodId, status, snapshotId);
        }
    }

    private boolean isInventoryAccount(Account account) {
        if (account == null || account.getName() == null) {
            return false;
        }
        return account.getName().toLowerCase(Locale.ROOT).contains("inventory");
    }

    private boolean isCashAccount(Account account) {
        if (account == null || account.getType() != AccountType.ASSET) {
            return false;
        }
        String label = (account.getCode() + " " + account.getName()).toLowerCase(Locale.ROOT);
        return label.contains("cash")
                || label.contains("bank")
                || label.contains("wallet")
                || label.contains("upi");
    }

    private Map<CashFlowSection, BigDecimal> resolveCashFlowAllocations(JournalLine cashLine,
                                                                         List<JournalLine> entryLines,
                                                                         BigDecimal cashDelta) {
        Map<CashFlowSection, BigDecimal> allocations = new EnumMap<>(CashFlowSection.class);
        if (cashDelta == null || cashDelta.compareTo(BigDecimal.ZERO) == 0) {
            return allocations;
        }
        Map<CashFlowSection, BigDecimal> weights = resolveCashFlowWeights(cashLine, entryLines, cashDelta);
        if (weights.isEmpty()) {
            allocations.put(CashFlowSection.OPERATING, cashDelta);
            return allocations;
        }
        BigDecimal totalWeight = weights.values().stream()
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            allocations.put(CashFlowSection.OPERATING, cashDelta);
            return allocations;
        }

        CashFlowSection dominantSection = dominantCashFlowSection(weights);
        BigDecimal allocated = BigDecimal.ZERO;
        for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
            CashFlowSection section = entry.getKey();
            if (section == dominantSection) {
                continue;
            }
            BigDecimal weight = safe(entry.getValue());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal sectionAmount = roundCurrency(cashDelta.multiply(weight)
                    .divide(totalWeight, 8, java.math.RoundingMode.HALF_UP));
            if (sectionAmount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            allocations.merge(section, sectionAmount, BigDecimal::add);
            allocated = allocated.add(sectionAmount);
        }

        BigDecimal dominantAmount = roundCurrency(cashDelta.subtract(allocated));
        if (dominantAmount.compareTo(BigDecimal.ZERO) != 0) {
            allocations.merge(dominantSection, dominantAmount, BigDecimal::add);
        }
        return allocations;
    }

    private Map<CashFlowSection, BigDecimal> resolveCashFlowWeights(JournalLine cashLine,
                                                                     List<JournalLine> entryLines,
                                                                     BigDecimal cashDelta) {
        Map<CashFlowSection, BigDecimal> weights = new EnumMap<>(CashFlowSection.class);
        if (cashLine == null || entryLines == null || entryLines.isEmpty() || cashDelta == null) {
            return weights;
        }
        boolean inflow = cashDelta.compareTo(BigDecimal.ZERO) > 0;
        List<JournalLine> candidates = entryLines.stream()
                .filter(line -> line != null && line != cashLine)
                .filter(line -> !isCashAccount(line.getAccount()))
                .filter(line -> inflow
                        ? safe(line.getCredit()).compareTo(BigDecimal.ZERO) > 0
                        : safe(line.getDebit()).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (candidates.isEmpty()) {
            candidates = entryLines.stream()
                    .filter(line -> line != null && line != cashLine)
                    .filter(line -> !isCashAccount(line.getAccount()))
                    .toList();
        }
        for (JournalLine candidate : candidates) {
            BigDecimal weight = inflow ? safe(candidate.getCredit()) : safe(candidate.getDebit());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                weight = safe(candidate.getDebit()).add(safe(candidate.getCredit()));
            }
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            CashFlowSection section = classifyCashFlowCounterparty(candidate.getAccount());
            weights.merge(section, weight.abs(), BigDecimal::add);
        }
        return weights;
    }

    private CashFlowSection dominantCashFlowSection(Map<CashFlowSection, BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            return CashFlowSection.OPERATING;
        }
        CashFlowSection resolved = CashFlowSection.OPERATING;
        BigDecimal maxWeight = BigDecimal.ZERO;
        for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
            BigDecimal weight = safe(entry.getValue());
            if (weight.compareTo(maxWeight) > 0) {
                maxWeight = weight;
                resolved = entry.getKey();
            }
        }
        return resolved;
    }

    private BigDecimal roundCurrency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private enum CashFlowSection {
        OPERATING,
        INVESTING,
        FINANCING
    }

    private CashFlowSection classifyCashFlowCounterparty(Account account) {
        if (account == null) {
            return CashFlowSection.OPERATING;
        }
        AccountType type = account.getType();
        String label = ((account.getCode() != null ? account.getCode() : "")
                + " "
                + (account.getName() != null ? account.getName() : ""))
                .toLowerCase(Locale.ROOT);
        if (type == AccountType.EQUITY) {
            return CashFlowSection.FINANCING;
        }
        if (type == AccountType.LIABILITY) {
            if (containsAny(label, "loan", "borrow", "debt", "note payable", "capital lease", "long-term")) {
                return CashFlowSection.FINANCING;
            }
            return CashFlowSection.OPERATING;
        }
        if (type == AccountType.ASSET) {
            if (containsAny(label, "fixed asset", "equipment", "machinery", "vehicle", "building", "plant", "investment")) {
                return CashFlowSection.INVESTING;
            }
            return CashFlowSection.OPERATING;
        }
        return CashFlowSection.OPERATING;
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal resolveInventoryLedgerBalance(Company company) {
        Long defaultInventoryAccountId = company.getDefaultInventoryAccountId();
        if (defaultInventoryAccountId != null) {
            Account account = companyEntityLookup.requireAccount(company, defaultInventoryAccountId);
            return safe(account.getBalance());
        }
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(this::isInventoryAccount)
                .map(Account::getBalance)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public List<WastageReportDto> wastageReport() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionLog> logs = productionLogRepository.findTop25ByCompanyOrderByProducedAtDesc(company);

        return logs.stream()
                .filter(log -> log.getWastageQuantity() != null &&
                               log.getWastageQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(log -> {
                    BigDecimal mixedQty = safe(log.getMixedQuantity());
                    BigDecimal wastageQty = safe(log.getWastageQuantity());
                    BigDecimal wastagePercentage = mixedQty.compareTo(BigDecimal.ZERO) > 0
                            ? wastageQty.divide(mixedQty, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    BigDecimal wastageValue = wastageQty.multiply(safe(log.getUnitCost()));

                    return new WastageReportDto(
                            log.getId(),
                            log.getProductionCode(),
                            log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
                            log.getBatchColour(),
                            log.getMixedQuantity(),
                            log.getTotalPackedQuantity(),
                            log.getWastageQuantity(),
                            wastagePercentage,
                            wastageValue,
                            log.getProducedAt()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CostBreakdownDto costBreakdown(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = companyEntityLookup.requireProductionLog(company, productionLogId);

        BigDecimal totalCost = safe(log.getMaterialCostTotal())
                .add(safe(log.getLaborCostTotal()))
                .add(safe(log.getOverheadCostTotal()));

        return new CostBreakdownDto(
                log.getId(),
                log.getProductionCode(),
                log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
                log.getBatchColour(),
                log.getMixedQuantity(),
                log.getMaterialCostTotal(),
                log.getLaborCostTotal(),
                log.getOverheadCostTotal(),
                totalCost,
                log.getUnitCost(),
                log.getProducedAt()
        );
    }

    @Transactional(readOnly = true)
    public MonthlyProductionCostDto monthlyProductionCosts(Integer year, Integer month) {
        Company company = companyContextService.requireCurrentCompany();

        java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
        java.time.LocalDate startDate = yearMonth.atDay(1);
        java.time.LocalDate endDate = yearMonth.atEndOfMonth().plusDays(1);

        ZoneId zone = companyClock.zoneId(company);
        java.time.Instant startInstant = startDate.atStartOfDay(zone).toInstant();
        java.time.Instant endInstant = endDate.atStartOfDay(zone).toInstant();

        List<ProductionLog> logs = productionLogRepository.findFullyPackedBatchesByMonth(
                company, startInstant, endInstant);

        if (logs.isEmpty()) {
            return new MonthlyProductionCostDto(
                    year, month, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            );
        }

        BigDecimal totalLiters = BigDecimal.ZERO;
        BigDecimal totalMaterialCost = BigDecimal.ZERO;
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        BigDecimal totalOverheadCost = BigDecimal.ZERO;
        BigDecimal totalWastage = BigDecimal.ZERO;

        for (ProductionLog log : logs) {
            totalLiters = totalLiters.add(safe(log.getMixedQuantity()));
            totalMaterialCost = totalMaterialCost.add(safe(log.getMaterialCostTotal()));
            totalLaborCost = totalLaborCost.add(safe(log.getLaborCostTotal()));
            totalOverheadCost = totalOverheadCost.add(safe(log.getOverheadCostTotal()));
            totalWastage = totalWastage.add(safe(log.getWastageQuantity()));
        }

        BigDecimal totalCost = totalMaterialCost.add(totalLaborCost).add(totalOverheadCost);
        BigDecimal avgCostPerLiter = totalLiters.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalLiters, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal wastagePercentage = totalLiters.compareTo(BigDecimal.ZERO) > 0
                ? totalWastage.divide(totalLiters, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return new MonthlyProductionCostDto(
                year,
                month,
                logs.size(),
                totalLiters,
                totalMaterialCost,
                totalLaborCost,
                totalOverheadCost,
                totalCost,
                avgCostPerLiter,
                totalWastage,
                wastagePercentage
        );
    }
}
