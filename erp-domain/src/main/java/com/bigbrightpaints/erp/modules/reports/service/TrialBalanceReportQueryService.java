package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class TrialBalanceReportQueryService {

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    private final ReportQuerySupport reportQuerySupport;
    private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;

    public TrialBalanceReportQueryService(ReportQuerySupport reportQuerySupport,
                                          AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
                                          AccountRepository accountRepository,
                                          JournalLineRepository journalLineRepository) {
        this.reportQuerySupport = reportQuerySupport;
        this.snapshotLineRepository = snapshotLineRepository;
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
    }

    public TrialBalanceDto generate(FinancialReportQueryRequest request) {
        ReportQuerySupport.FinancialQueryWindow primaryWindow = reportQuerySupport.resolveWindow(request);
        TrialBalanceSnapshot primary = resolveSnapshot(primaryWindow);
        TrialBalanceDto.Comparative comparative = null;

        ReportQuerySupport.FinancialComparisonWindow comparison = reportQuerySupport.resolveComparison(request);
        if (comparison != null) {
            ReportQuerySupport.FinancialQueryWindow comparativeWindow = comparison.window();
            TrialBalanceSnapshot comparativeSnapshot = resolveSnapshot(comparativeWindow);
            comparative = new TrialBalanceDto.Comparative(
                    comparativeSnapshot.rows(),
                    comparativeSnapshot.totalDebit(),
                    comparativeSnapshot.totalCredit(),
                    comparativeSnapshot.balanced(),
                    reportQuerySupport.metadata(comparativeWindow)
            );
        }

        return new TrialBalanceDto(
                primary.rows(),
                primary.totalDebit(),
                primary.totalCredit(),
                primary.balanced(),
                reportQuerySupport.metadata(primaryWindow),
                comparative
        );
    }

    private TrialBalanceSnapshot resolveSnapshot(ReportQuerySupport.FinancialQueryWindow window) {
        List<TrialBalanceDto.Row> rows = usesClosedSnapshot(window)
                ? fromClosedSnapshot(window)
                : fromJournalSummary(window);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        for (TrialBalanceDto.Row row : rows) {
            totalDebit = totalDebit.add(safe(row.debit()));
            totalCredit = totalCredit.add(safe(row.credit()));
        }
        boolean balanced = totalDebit.subtract(totalCredit).abs().compareTo(BALANCE_TOLERANCE) <= 0;
        return new TrialBalanceSnapshot(rows, totalDebit, totalCredit, balanced);
    }

    private boolean usesClosedSnapshot(ReportQuerySupport.FinancialQueryWindow window) {
        if (window.source() != ReportSource.SNAPSHOT || window.snapshot() == null || window.period() == null) {
            return false;
        }
        return window.startDate().equals(window.period().getStartDate())
                && window.endDate().equals(window.period().getEndDate());
    }

    private List<TrialBalanceDto.Row> fromClosedSnapshot(ReportQuerySupport.FinancialQueryWindow window) {
        List<AccountingPeriodTrialBalanceLine> lines = snapshotLineRepository
                .findBySnapshotOrderByAccountCodeAsc(window.snapshot());
        return lines.stream()
                .map(line -> {
                    BigDecimal debit = safe(line.getDebit());
                    BigDecimal credit = safe(line.getCredit());
                    return new TrialBalanceDto.Row(
                            line.getAccountId(),
                            line.getAccountCode(),
                            line.getAccountName(),
                            line.getAccountType(),
                            debit,
                            credit,
                            debit.subtract(credit)
                    );
                })
                .toList();
    }

    private List<TrialBalanceDto.Row> fromJournalSummary(ReportQuerySupport.FinancialQueryWindow window) {
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(window.company());
        Map<Long, BigDecimal> debitByAccount = new HashMap<>();
        Map<Long, BigDecimal> creditByAccount = new HashMap<>();

        List<Object[]> rows;
        if (window.source() == ReportSource.AS_OF) {
            rows = journalLineRepository.summarizeByAccountUpTo(window.company(), window.asOfDate());
        } else if (window.startDate() != null && window.endDate() != null) {
            rows = journalLineRepository.summarizeByAccountWithin(window.company(), window.startDate(), window.endDate());
        } else {
            rows = journalLineRepository.summarizeByAccountUpTo(window.company(), window.asOfDate());
        }

        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            Long accountId = (Long) row[0];
            debitByAccount.put(accountId, safe((BigDecimal) row[1]));
            creditByAccount.put(accountId, safe((BigDecimal) row[2]));
        }

        List<TrialBalanceDto.Row> computedRows = new ArrayList<>();
        for (Account account : accounts) {
            BigDecimal debit = debitByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
            BigDecimal credit = creditByAccount.getOrDefault(account.getId(), BigDecimal.ZERO);
            BigDecimal net = computeNet(account.getType(), debit, credit);
            computedRows.add(new TrialBalanceDto.Row(
                    account.getId(),
                    account.getCode(),
                    account.getName(),
                    account.getType(),
                    debit,
                    credit,
                    net
            ));
        }
        return computedRows;
    }

    private BigDecimal computeNet(AccountType accountType, BigDecimal debit, BigDecimal credit) {
        if (accountType == null || accountType.isDebitNormalBalance()) {
            return safe(debit).subtract(safe(credit));
        }
        return safe(credit).subtract(safe(debit));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record TrialBalanceSnapshot(
            List<TrialBalanceDto.Row> rows,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            boolean balanced
    ) {
    }
}
