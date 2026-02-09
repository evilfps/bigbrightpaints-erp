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
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
    private final InvoiceRepository invoiceRepository;
    private final ProductionLogRepository productionLogRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private final InventoryValuationService inventoryValuationService;
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
                         InvoiceRepository invoiceRepository,
                         ProductionLogRepository productionLogRepository,
                         CompanyEntityLookup companyEntityLookup,
                         CompanyClock companyClock,
                         InventoryValuationService inventoryValuationService) {
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
        this.invoiceRepository = invoiceRepository;
        this.productionLogRepository = productionLogRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
        this.inventoryValuationService = inventoryValuationService;
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet() {
        return balanceSheet(null);
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet(LocalDate asOfDate) {
        ReportContext context = resolveReportContext(asOfDate);
        List<TrialBalanceLine> lines = resolveTrialBalanceLines(context);
        BigDecimal assets = aggregateAccountType(lines, AccountType.ASSET);
        BigDecimal liabilities = aggregateAccountType(lines, AccountType.LIABILITY);
        BigDecimal equity = assets.subtract(liabilities);
        return new BalanceSheetDto(assets, liabilities, equity, context.metadata());
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss() {
        return profitLoss(null);
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss(LocalDate asOfDate) {
        ReportContext context = resolveReportContext(asOfDate);
        List<TrialBalanceLine> lines = resolveTrialBalanceLines(context);
        BigDecimal revenue = aggregateAccountType(lines, AccountType.REVENUE)
                .add(aggregateAccountType(lines, AccountType.OTHER_INCOME));
        BigDecimal cogs = aggregateAccountType(lines, AccountType.COGS);
        BigDecimal grossProfit = revenue.subtract(cogs);
        BigDecimal expenses = aggregateAccountType(lines, AccountType.EXPENSE)
                .add(aggregateAccountType(lines, AccountType.OTHER_EXPENSE));
        BigDecimal netIncome = grossProfit.subtract(expenses);
        return new ProfitLossDto(revenue, cogs, grossProfit, expenses, netIncome, context.metadata());
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
                CashFlowSection section = resolveCashFlowSection(line, lines);
                switch (section) {
                    case INVESTING -> investing = investing.add(delta);
                    case FINANCING -> financing = financing.add(delta);
                    default -> operating = operating.add(delta);
                }
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
                    return new AccountStatementEntryDto(dealer.getName(), entryDate, reference, debit, credit, outstanding);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgedDebtorDto> agedDebtors() {
        Company company = companyContextService.requireCurrentCompany();
        List<Invoice> invoices = invoiceRepository.findByCompanyOrderByIssueDateDesc(company);
        LocalDate today = companyClock.today(company);
        Map<Dealer, AgedBucket> buckets = new java.util.LinkedHashMap<>();
        for (Invoice invoice : invoices) {
            Dealer dealer = invoice.getDealer();
            if (dealer == null) {
                continue;
            }
            String status = invoice.getStatus() != null ? invoice.getStatus().toUpperCase(Locale.ROOT) : null;
            if (status == null || "DRAFT".equals(status) || "VOID".equals(status) || "REVERSED".equals(status)) {
                continue;
            }
            BigDecimal outstanding = invoice.getOutstandingAmount();
            if (outstanding == null) {
                outstanding = Optional.ofNullable(invoice.getTotalAmount()).orElse(BigDecimal.ZERO);
            }
            if (outstanding.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            long daysPastDue = invoice.getDueDate() == null
                    ? 0
                    : ChronoUnit.DAYS.between(invoice.getDueDate(), today);
            AgedBucket bucket = buckets.computeIfAbsent(dealer, ignored -> new AgedBucket());
            if (daysPastDue <= 0) {
                bucket.current = bucket.current.add(outstanding);
            } else if (daysPastDue <= 30) {
                bucket.thirty = bucket.thirty.add(outstanding);
            } else if (daysPastDue <= 60) {
                bucket.sixty = bucket.sixty.add(outstanding);
            } else {
                bucket.ninety = bucket.ninety.add(outstanding);
            }
        }
        return buckets.entrySet().stream()
                .map(entry -> new AgedDebtorDto(entry.getKey().getName(),
                        entry.getValue().current,
                        entry.getValue().thirty,
                        entry.getValue().sixty,
                        entry.getValue().ninety))
                .toList();
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
        return trialBalance(null);
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance(LocalDate asOfDate) {
        ReportContext context = resolveReportContext(asOfDate);
        List<TrialBalanceLine> lines = resolveTrialBalanceLines(context);
        List<TrialBalanceDto.Row> rows = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (TrialBalanceLine line : lines) {
            TrialBalanceDto.Row row = new TrialBalanceDto.Row(
                    line.accountId(),
                    line.code(),
                    line.name(),
                    line.type(),
                    line.debit(),
                    line.credit()
            );
            rows.add(row);
            totalDebit = totalDebit.add(safe(line.debit()));
            totalCredit = totalCredit.add(safe(line.credit()));
        }
        boolean balanced = totalDebit.subtract(totalCredit).abs().compareTo(BALANCE_TOLERANCE) <= 0;
        return new TrialBalanceDto(rows, totalDebit, totalCredit, balanced, context.metadata());
    }

    private BigDecimal aggregateAccountType(List<TrialBalanceLine> lines, AccountType type) {
        return lines.stream()
                .filter(line -> line.type() == type)
                .map(this::naturalBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal naturalBalance(TrialBalanceLine line) {
        if (line == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal debit = safe(line.debit());
        BigDecimal credit = safe(line.credit());
        if (line.type() == null || line.type().isDebitNormalBalance()) {
            return debit.subtract(credit);
        }
        return credit.subtract(debit);
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

    private CashFlowSection resolveCashFlowSection(JournalLine cashLine, List<JournalLine> entryLines) {
        if (cashLine == null || entryLines == null || entryLines.isEmpty()) {
            return CashFlowSection.OPERATING;
        }
        BigDecimal cashDelta = safe(cashLine.getDebit()).subtract(safe(cashLine.getCredit()));
        if (cashDelta.compareTo(BigDecimal.ZERO) == 0) {
            return CashFlowSection.OPERATING;
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
        if (candidates.isEmpty()) {
            return CashFlowSection.OPERATING;
        }
        Map<CashFlowSection, BigDecimal> weights = new EnumMap<>(CashFlowSection.class);
        for (JournalLine candidate : candidates) {
            BigDecimal weight = inflow ? safe(candidate.getCredit()) : safe(candidate.getDebit());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                weight = safe(candidate.getDebit()).add(safe(candidate.getCredit()));
            }
            CashFlowSection section = classifyCashFlowCounterparty(candidate.getAccount());
            weights.merge(section, weight.abs(), BigDecimal::add);
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
    ) {}

    private enum CashFlowSection {
        OPERATING,
        INVESTING,
        FINANCING
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

    private static class AgedBucket {
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal thirty = BigDecimal.ZERO;
        private BigDecimal sixty = BigDecimal.ZERO;
        private BigDecimal ninety = BigDecimal.ZERO;
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
