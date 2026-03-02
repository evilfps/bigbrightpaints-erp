package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ProfitLossReportQueryService {

    private final ReportQuerySupport reportQuerySupport;
    private final JournalLineRepository journalLineRepository;

    public ProfitLossReportQueryService(ReportQuerySupport reportQuerySupport,
                                        JournalLineRepository journalLineRepository) {
        this.reportQuerySupport = reportQuerySupport;
        this.journalLineRepository = journalLineRepository;
    }

    public ProfitLossDto generate(FinancialReportQueryRequest request) {
        ReportQuerySupport.FinancialQueryWindow primaryWindow = reportQuerySupport.resolveWindow(request);
        ProfitLossSnapshot primary = summarize(primaryWindow);

        ProfitLossDto.Comparative comparative = null;
        ReportQuerySupport.FinancialComparisonWindow comparison = reportQuerySupport.resolveComparison(request);
        if (comparison != null) {
            ReportQuerySupport.FinancialQueryWindow comparativeWindow = comparison.window();
            ProfitLossSnapshot comparativeSnapshot = summarize(comparativeWindow);
            comparative = new ProfitLossDto.Comparative(
                    comparativeSnapshot.revenue(),
                    comparativeSnapshot.costOfGoodsSold(),
                    comparativeSnapshot.grossProfit(),
                    comparativeSnapshot.operatingExpenses(),
                    comparativeSnapshot.expenseCategories(),
                    comparativeSnapshot.netIncome(),
                    reportQuerySupport.metadata(comparativeWindow)
            );
        }

        return new ProfitLossDto(
                primary.revenue(),
                primary.costOfGoodsSold(),
                primary.grossProfit(),
                primary.operatingExpenses(),
                primary.expenseCategories(),
                primary.netIncome(),
                reportQuerySupport.metadata(primaryWindow),
                comparative
        );
    }

    private ProfitLossSnapshot summarize(ReportQuerySupport.FinancialQueryWindow window) {
        List<Object[]> summarized = journalLineRepository.summarizeByAccountType(
                window.company(),
                window.startDate(),
                window.endDate());

        Map<AccountType, BigDecimal> naturalBalances = new EnumMap<>(AccountType.class);
        for (Object[] row : summarized) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            AccountType type = (AccountType) row[0];
            BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            BigDecimal natural = toNatural(type, debit, credit);
            naturalBalances.merge(type, natural, BigDecimal::add);
        }

        BigDecimal revenue = safe(naturalBalances.get(AccountType.REVENUE))
                .add(safe(naturalBalances.get(AccountType.OTHER_INCOME)));
        BigDecimal cogs = safe(naturalBalances.get(AccountType.COGS));
        BigDecimal grossProfit = revenue.subtract(cogs);

        BigDecimal operatingExpenses = safe(naturalBalances.get(AccountType.EXPENSE))
                .add(safe(naturalBalances.get(AccountType.OTHER_EXPENSE)));
        List<ProfitLossDto.ExpenseCategory> expenseCategories = new ArrayList<>();
        expenseCategories.add(new ProfitLossDto.ExpenseCategory("OPERATING", safe(naturalBalances.get(AccountType.EXPENSE))));
        expenseCategories.add(new ProfitLossDto.ExpenseCategory("OTHER", safe(naturalBalances.get(AccountType.OTHER_EXPENSE))));

        BigDecimal netIncome = grossProfit.subtract(operatingExpenses);
        return new ProfitLossSnapshot(revenue, cogs, grossProfit, operatingExpenses, expenseCategories, netIncome);
    }

    private BigDecimal toNatural(AccountType type, BigDecimal debit, BigDecimal credit) {
        if (type == null || type.isDebitNormalBalance()) {
            return safe(debit).subtract(safe(credit));
        }
        return safe(credit).subtract(safe(debit));
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ProfitLossSnapshot(
            BigDecimal revenue,
            BigDecimal costOfGoodsSold,
            BigDecimal grossProfit,
            BigDecimal operatingExpenses,
            List<ProfitLossDto.ExpenseCategory> expenseCategories,
            BigDecimal netIncome
    ) {
    }
}
