package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class BalanceSheetReportQueryService {

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");
    private static final String CURRENT_EARNINGS_CODE = "CURRENT-EARNINGS";
    private static final String CURRENT_EARNINGS_NAME = "Current Earnings";

    private final ReportQuerySupport reportQuerySupport;
    private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;

    public BalanceSheetReportQueryService(ReportQuerySupport reportQuerySupport,
                                          AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
                                          AccountRepository accountRepository,
                                          JournalLineRepository journalLineRepository) {
        this.reportQuerySupport = reportQuerySupport;
        this.snapshotLineRepository = snapshotLineRepository;
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
    }

    public BalanceSheetDto generate(FinancialReportQueryRequest request) {
        ReportQuerySupport.FinancialQueryWindow primaryWindow = reportQuerySupport.resolveWindow(request);
        BalanceSheetSnapshot primary = summarize(primaryWindow);

        BalanceSheetDto.Comparative comparative = null;
        ReportQuerySupport.FinancialComparisonWindow comparison = reportQuerySupport.resolveComparison(request);
        if (comparison != null) {
            ReportQuerySupport.FinancialQueryWindow comparativeWindow = comparison.window();
            BalanceSheetSnapshot comparativeSnapshot = summarize(comparativeWindow);
            comparative = new BalanceSheetDto.Comparative(
                    comparativeSnapshot.totalAssets(),
                    comparativeSnapshot.totalLiabilities(),
                    comparativeSnapshot.totalEquity(),
                    comparativeSnapshot.balanced(),
                    reportQuerySupport.metadata(comparativeWindow),
                    comparativeSnapshot.currentAssets(),
                    comparativeSnapshot.fixedAssets(),
                    comparativeSnapshot.currentLiabilities(),
                    comparativeSnapshot.longTermLiabilities(),
                    comparativeSnapshot.equityLines()
            );
        }

        return new BalanceSheetDto(
                primary.totalAssets(),
                primary.totalLiabilities(),
                primary.totalEquity(),
                primary.balanced(),
                reportQuerySupport.metadata(primaryWindow),
                primary.currentAssets(),
                primary.fixedAssets(),
                primary.currentLiabilities(),
                primary.longTermLiabilities(),
                primary.equityLines(),
                comparative
        );
    }

    private BalanceSheetSnapshot summarize(ReportQuerySupport.FinancialQueryWindow window) {
        List<BalanceLine> lines = usesClosedSnapshot(window)
                ? fromClosedSnapshot(window)
                : fromJournalSummary(window);

        List<BalanceSheetDto.SectionLine> currentAssets = new ArrayList<>();
        List<BalanceSheetDto.SectionLine> fixedAssets = new ArrayList<>();
        List<BalanceSheetDto.SectionLine> currentLiabilities = new ArrayList<>();
        List<BalanceSheetDto.SectionLine> longTermLiabilities = new ArrayList<>();
        List<BalanceSheetDto.SectionLine> equityLines = new ArrayList<>();

        BigDecimal totalAssets = BigDecimal.ZERO;
        BigDecimal totalLiabilities = BigDecimal.ZERO;
        BigDecimal totalEquity = BigDecimal.ZERO;

        for (BalanceLine line : lines) {
            BalanceSheetDto.SectionLine dto = new BalanceSheetDto.SectionLine(
                    line.accountId(),
                    line.code(),
                    line.name(),
                    line.amount());
            if (line.type() == AccountType.ASSET) {
                totalAssets = totalAssets.add(line.amount());
                if (isCurrentAsset(line.code(), line.name())) {
                    currentAssets.add(dto);
                } else {
                    fixedAssets.add(dto);
                }
            } else if (line.type() == AccountType.LIABILITY) {
                totalLiabilities = totalLiabilities.add(line.amount());
                if (isLongTermLiability(line.code(), line.name())) {
                    longTermLiabilities.add(dto);
                } else {
                    currentLiabilities.add(dto);
                }
            } else if (line.type() == AccountType.EQUITY) {
                totalEquity = totalEquity.add(line.amount());
                equityLines.add(dto);
            }
        }

        if (!usesClosedSnapshot(window)) {
            BigDecimal currentEarnings = currentPeriodEarnings(window);
            if (currentEarnings.compareTo(BigDecimal.ZERO) != 0) {
                totalEquity = totalEquity.add(currentEarnings);
                equityLines.add(new BalanceSheetDto.SectionLine(
                        null,
                        CURRENT_EARNINGS_CODE,
                        CURRENT_EARNINGS_NAME,
                        currentEarnings));
            }
        }

        boolean balanced = totalAssets.subtract(totalLiabilities.add(totalEquity))
                .abs().compareTo(BALANCE_TOLERANCE) <= 0;

        return new BalanceSheetSnapshot(
                totalAssets,
                totalLiabilities,
                totalEquity,
                balanced,
                currentAssets,
                fixedAssets,
                currentLiabilities,
                longTermLiabilities,
                equityLines
        );
    }

    private boolean usesClosedSnapshot(ReportQuerySupport.FinancialQueryWindow window) {
        if (window.source() != ReportSource.SNAPSHOT || window.snapshot() == null || window.period() == null) {
            return false;
        }
        return window.startDate().equals(window.period().getStartDate())
                && window.endDate().equals(window.period().getEndDate());
    }

    private List<BalanceLine> fromClosedSnapshot(ReportQuerySupport.FinancialQueryWindow window) {
        List<AccountingPeriodTrialBalanceLine> lines = snapshotLineRepository
                .findBySnapshotOrderByAccountCodeAsc(window.snapshot());
        return lines.stream()
                .filter(line -> isBalanceSheetType(line.getAccountType()))
                .map(line -> new BalanceLine(
                        line.getAccountId(),
                        line.getAccountCode(),
                        line.getAccountName(),
                        line.getAccountType(),
                        toNatural(line.getAccountType(), safe(line.getDebit()), safe(line.getCredit()))
                ))
                .toList();
    }

    private List<BalanceLine> fromJournalSummary(ReportQuerySupport.FinancialQueryWindow window) {
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(window.company());
        Map<Long, BigDecimal> debitByAccount = new HashMap<>();
        Map<Long, BigDecimal> creditByAccount = new HashMap<>();
        mergeSummaryRows(
                debitByAccount,
                creditByAccount,
                journalLineRepository.summarizeByAccountWithin(
                        window.company(),
                        window.startDate(),
                        window.endDate()),
                false);
        mergeSummaryRows(
                debitByAccount,
                creditByAccount,
                journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                        window.company(),
                        window.startDate(),
                        window.endDate()),
                true);
        List<BalanceLine> lines = new ArrayList<>();
        for (Account account : accounts) {
            if (!isBalanceSheetType(account.getType())) {
                continue;
            }
            BigDecimal debit = debitByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
            BigDecimal credit = creditByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
            lines.add(new BalanceLine(
                    account.getId(),
                    account.getCode(),
                    account.getName(),
                    account.getType(),
                    toNatural(account.getType(), debit, credit)
            ));
        }
        return lines;
    }

    private void mergeSummaryRows(Map<Long, BigDecimal> debitByAccount,
                                  Map<Long, BigDecimal> creditByAccount,
                                  List<Object[]> rows,
                                  boolean subtract) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        BigDecimal multiplier = subtract ? BigDecimal.valueOf(-1) : BigDecimal.ONE;
        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            Long accountId = (Long) row[0];
            debitByAccount.merge(accountId, safe((BigDecimal) row[1]).multiply(multiplier), BigDecimal::add);
            creditByAccount.merge(accountId, safe((BigDecimal) row[2]).multiply(multiplier), BigDecimal::add);
        }
    }

    private BigDecimal currentPeriodEarnings(ReportQuerySupport.FinancialQueryWindow window) {
        List<Object[]> summarized = journalLineRepository.summarizeByAccountType(
                window.company(),
                window.startDate(),
                window.endDate());

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        for (Object[] row : summarized) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            AccountType type = (AccountType) row[0];
            BigDecimal natural = toNatural(type, safe((BigDecimal) row[1]), safe((BigDecimal) row[2]));
            if (type == AccountType.REVENUE || type == AccountType.OTHER_INCOME) {
                revenue = revenue.add(natural);
            } else if (type == AccountType.EXPENSE || type == AccountType.OTHER_EXPENSE || type == AccountType.COGS) {
                expenses = expenses.add(natural);
            }
        }
        return revenue.subtract(expenses);
    }

    private boolean isBalanceSheetType(AccountType type) {
        return type == AccountType.ASSET || type == AccountType.LIABILITY || type == AccountType.EQUITY;
    }

    private BigDecimal toNatural(AccountType type, BigDecimal debit, BigDecimal credit) {
        if (type == null || type.isDebitNormalBalance()) {
            return safe(debit).subtract(safe(credit));
        }
        return safe(credit).subtract(safe(debit));
    }

    private boolean isCurrentAsset(String code, String name) {
        String normalized = normalize(code, name);
        return !normalized.contains("FIXED")
                && !normalized.contains("NON-CURRENT")
                && !normalized.contains("LONG-TERM")
                && !normalized.contains("INTANGIBLE")
                && !normalized.contains("DEPRECIATION")
                && !normalized.contains("INVESTMENT");
    }

    private boolean isLongTermLiability(String code, String name) {
        String normalized = normalize(code, name);
        return normalized.contains("LONG-TERM")
                || normalized.contains("NON-CURRENT")
                || normalized.contains("BORROWING")
                || normalized.contains("LOAN")
                || normalized.contains("PROVISION");
    }

    private String normalize(String code, String name) {
        String safeCode = code == null ? "" : code;
        String safeName = name == null ? "" : name;
        return (safeCode + " " + safeName).toUpperCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record BalanceLine(
            Long accountId,
            String code,
            String name,
            AccountType type,
            BigDecimal amount
    ) {
    }

    private record BalanceSheetSnapshot(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity,
            boolean balanced,
            List<BalanceSheetDto.SectionLine> currentAssets,
            List<BalanceSheetDto.SectionLine> fixedAssets,
            List<BalanceSheetDto.SectionLine> currentLiabilities,
            List<BalanceSheetDto.SectionLine> longTermLiabilities,
            List<BalanceSheetDto.SectionLine> equityLines
    ) {
    }
}
