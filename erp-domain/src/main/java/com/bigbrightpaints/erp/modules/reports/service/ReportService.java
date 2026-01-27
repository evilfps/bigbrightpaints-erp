package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
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
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ReportService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProductionLogRepository productionLogRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    public ReportService(CompanyContextService companyContextService,
                         AccountRepository accountRepository,
                         RawMaterialRepository rawMaterialRepository,
                         RawMaterialBatchRepository rawMaterialBatchRepository,
                         FinishedGoodRepository finishedGoodRepository,
                         FinishedGoodBatchRepository finishedGoodBatchRepository,
                         DealerRepository dealerRepository,
                         DealerLedgerService dealerLedgerService,
                         DealerLedgerRepository dealerLedgerRepository,
                         JournalEntryRepository journalEntryRepository,
                         InvoiceRepository invoiceRepository,
                         ProductionLogRepository productionLogRepository,
                         CompanyEntityLookup companyEntityLookup,
                         CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.invoiceRepository = invoiceRepository;
        this.productionLogRepository = productionLogRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet() {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal assets = aggregateAccountType(company, AccountType.ASSET);
        BigDecimal liabilities = aggregateAccountType(company, AccountType.LIABILITY);
        BigDecimal equity = assets.subtract(liabilities);
        return new BalanceSheetDto(assets, liabilities, equity);
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss() {
        Company company = companyContextService.requireCurrentCompany();
        BigDecimal revenue = aggregateAccountType(company, AccountType.REVENUE);
        BigDecimal cogs = aggregateAccountType(company, AccountType.COGS);
        BigDecimal grossProfit = revenue.subtract(cogs);
        BigDecimal expenses = aggregateAccountType(company, AccountType.EXPENSE);
        BigDecimal netIncome = grossProfit.subtract(expenses);
        return new ProfitLossDto(revenue, cogs, grossProfit, expenses, netIncome);
    }

    @Transactional(readOnly = true)
    public CashFlowDto cashFlow() {
        Company company = companyContextService.requireCurrentCompany();
        List<JournalEntry> entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        BigDecimal operating = BigDecimal.ZERO;
        BigDecimal investing = BigDecimal.ZERO;
        BigDecimal financing = BigDecimal.ZERO;
        for (JournalEntry entry : entries) {
            if (!"POSTED".equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            for (JournalLine line : entry.getLines()) {
                BigDecimal delta = safe(line.getDebit()).subtract(safe(line.getCredit()));
                CashCategory category = classify(line.getAccount());
                switch (category) {
                    case OPERATING -> operating = operating.add(delta);
                    case INVESTING -> investing = investing.add(delta);
                    case FINANCING -> financing = financing.add(delta);
                }
            }
        }
        BigDecimal net = operating.add(investing).add(financing);
        return new CashFlowDto(operating, investing, financing, net);
    }

    @Transactional(readOnly = true)
    public InventoryValuationDto inventoryValuation() {
        Company company = companyContextService.requireCurrentCompany();
        InventoryTotals totals = computeInventoryTotals(company);
        return new InventoryValuationDto(totals.totalValue(), totals.lowStock());
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
        InventoryTotals totals = computeInventoryTotals(company);
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
        InventoryTotals totals = computeInventoryTotals(company);
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
        Company company = companyContextService.requireCurrentCompany();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        List<TrialBalanceDto.Row> rows = new ArrayList<>();
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (Account account : accounts) {
            TrialBalanceDto.Row row = toTrialBalanceRow(account);
            rows.add(row);
            totalDebit = totalDebit.add(safe(row.debit()));
            totalCredit = totalCredit.add(safe(row.credit()));
        }
        boolean balanced = totalDebit.subtract(totalCredit).abs().compareTo(BALANCE_TOLERANCE) <= 0;
        return new TrialBalanceDto(rows, totalDebit, totalCredit, balanced);
    }

    private BigDecimal aggregateAccountType(Company company, AccountType type) {
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(acc -> acc.getType() == type)
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isInventoryAccount(Account account) {
        if (account == null || account.getName() == null) {
            return false;
        }
        return account.getName().toLowerCase(Locale.ROOT).contains("inventory");
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

    private InventoryTotals computeInventoryTotals(Company company) {
        BigDecimal totalValue = BigDecimal.ZERO;
        long lowStock = 0;
        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        for (RawMaterial material : materials) {
            totalValue = totalValue.add(valueFromRawMaterial(material));
            if (material.getCurrentStock().compareTo(material.getReorderLevel()) < 0) {
                lowStock++;
            }
        }
        List<FinishedGood> finishedGoods = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company);
        for (FinishedGood finishedGood : finishedGoods) {
            totalValue = totalValue.add(valueFromFinishedGood(finishedGood));
            if (finishedGood.getReservedStock() != null
                    && finishedGood.getCurrentStock().compareTo(finishedGood.getReservedStock()) < 0) {
                lowStock++;
            }
        }
        return new InventoryTotals(totalValue, lowStock);
    }

    private BigDecimal valueFromRawMaterial(RawMaterial material) {
        BigDecimal remaining = Optional.ofNullable(material.getCurrentStock()).orElse(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (isWeightedAverage(material.getCostingMethod())) {
            BigDecimal avgCost = rawMaterialBatchRepository.calculateWeightedAverageCost(material);
            if (avgCost == null) {
                return BigDecimal.ZERO;
            }
            return remaining.multiply(avgCost);
        }
        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(material).stream()
                .sorted((a, b) -> a.getReceivedAt().compareTo(b.getReceivedAt()))
                .toList();
        return consumeValuation(remaining, batches.stream()
                .map(batch -> new CostSlice(batch.getQuantity(), batch.getCostPerUnit()))
                .toList());
    }

    private BigDecimal valueFromFinishedGood(FinishedGood finishedGood) {
        BigDecimal remaining = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (isWeightedAverage(finishedGood.getCostingMethod())) {
            BigDecimal avgCost = finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood);
            if (avgCost == null) {
                return BigDecimal.ZERO;
            }
            return remaining.multiply(avgCost);
        }
        List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood);
        return consumeValuation(remaining, batches.stream()
                .map(batch -> new CostSlice(batch.getQuantityTotal(), batch.getUnitCost()))
                .toList());
    }

    private BigDecimal consumeValuation(BigDecimal required, List<CostSlice> slices) {
        BigDecimal remaining = required;
        BigDecimal total = BigDecimal.ZERO;
        for (CostSlice slice : slices) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal qty = Optional.ofNullable(slice.quantity()).orElse(BigDecimal.ZERO);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal used = qty.min(remaining);
            total = total.add(used.multiply(Optional.ofNullable(slice.cost()).orElse(BigDecimal.ZERO)));
            remaining = remaining.subtract(used);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal lastCost = slices.isEmpty()
                    ? BigDecimal.ZERO
                    : Optional.ofNullable(slices.get(slices.size() - 1).cost()).orElse(BigDecimal.ZERO);
            total = total.add(remaining.multiply(lastCost));
        }
        return total;
    }

    private boolean isWeightedAverage(String method) {
        if (method == null) {
            return false;
        }
        String normalized = method.trim().toUpperCase();
        return "WAC".equals(normalized) || "WEIGHTED_AVERAGE".equals(normalized) || "WEIGHTED-AVERAGE".equals(normalized);
    }

    private CashCategory classify(Account account) {
        AccountType type = account != null ? account.getType() : null;
        if (type == null) {
            return CashCategory.OPERATING;
        }
        if (type == AccountType.LIABILITY || type == AccountType.EQUITY) {
            return CashCategory.FINANCING;
        }
        return CashCategory.OPERATING;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private TrialBalanceDto.Row toTrialBalanceRow(Account account) {
        BigDecimal balance = safe(account.getBalance());
        AccountType type = account.getType();
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
        return new TrialBalanceDto.Row(
                account.getId(),
                account.getCode(),
                account.getName(),
                type,
                debit,
                credit
        );
    }

    private enum CashCategory {
        OPERATING, INVESTING, FINANCING
    }

    private static class AgedBucket {
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal thirty = BigDecimal.ZERO;
        private BigDecimal sixty = BigDecimal.ZERO;
        private BigDecimal ninety = BigDecimal.ZERO;
    }

    private record InventoryTotals(BigDecimal totalValue, long lowStock) {}

    private record CostSlice(BigDecimal quantity, BigDecimal cost) {}

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

        java.time.Instant startInstant = startDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        java.time.Instant endInstant = endDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();

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
