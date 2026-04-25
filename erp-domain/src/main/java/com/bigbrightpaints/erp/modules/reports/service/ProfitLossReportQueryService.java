package com.bigbrightpaints.erp.modules.reports.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;

@Service
@Transactional(readOnly = true)
public class ProfitLossReportQueryService {

  private final ReportQuerySupport reportQuerySupport;
  private final JournalLineRepository journalLineRepository;

  public ProfitLossReportQueryService(
      ReportQuerySupport reportQuerySupport, JournalLineRepository journalLineRepository) {
    this.reportQuerySupport = reportQuerySupport;
    this.journalLineRepository = journalLineRepository;
  }

  public ProfitLossDto generate(FinancialReportQueryRequest request) {
    ReportQuerySupport.FinancialQueryWindow primaryWindow =
        reportQuerySupport.resolveWindow(request);
    ProfitLossSnapshot primary = summarize(primaryWindow);
    ReportMetadata primaryMetadata = liveMetadata(primaryWindow);

    ProfitLossDto.Comparative comparative = null;
    ReportQuerySupport.FinancialComparisonWindow comparison =
        reportQuerySupport.resolveComparison(request);
    if (comparison != null) {
      ReportQuerySupport.FinancialQueryWindow comparativeWindow = comparison.window();
      ProfitLossSnapshot comparativeSnapshot = summarize(comparativeWindow);
      comparative =
          new ProfitLossDto.Comparative(
              comparativeSnapshot.revenue(),
              comparativeSnapshot.costOfGoodsSold(),
              comparativeSnapshot.grossProfit(),
              comparativeSnapshot.operatingExpenses(),
              comparativeSnapshot.expenseCategories(),
              comparativeSnapshot.netIncome(),
              liveMetadata(comparativeWindow));
    }

    return new ProfitLossDto(
        primary.revenue(),
        primary.costOfGoodsSold(),
        primary.grossProfit(),
        primary.operatingExpenses(),
        primary.expenseCategories(),
        primary.netIncome(),
        primaryMetadata,
        comparative);
  }

  private ProfitLossSnapshot summarize(ReportQuerySupport.FinancialQueryWindow window) {
    List<Object[]> summarized =
        journalLineRepository.summarizeByAccountType(
            window.company(), window.startDate(), window.endDate());
    List<Object[]> periodCloseRows =
        journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountTypeWithin(
            window.company(), window.startDate(), window.endDate());

    Map<AccountType, BigDecimal> naturalBalances = new EnumMap<>(AccountType.class);
    mergeNaturalBalances(naturalBalances, summarized, BigDecimal.ONE);
    mergeNaturalBalances(naturalBalances, periodCloseRows, BigDecimal.valueOf(-1));

    BigDecimal revenue =
        safe(naturalBalances.get(AccountType.REVENUE))
            .add(safe(naturalBalances.get(AccountType.OTHER_INCOME)));
    BigDecimal cogs = safe(naturalBalances.get(AccountType.COGS));
    BigDecimal grossProfit = revenue.subtract(cogs);

    BigDecimal operatingExpenses =
        safe(naturalBalances.get(AccountType.EXPENSE))
            .add(safe(naturalBalances.get(AccountType.OTHER_EXPENSE)));
    List<ProfitLossDto.ExpenseCategory> expenseCategories = new ArrayList<>();
    expenseCategories.add(
        new ProfitLossDto.ExpenseCategory(
            "OPERATING", safe(naturalBalances.get(AccountType.EXPENSE))));
    expenseCategories.add(
        new ProfitLossDto.ExpenseCategory(
            "OTHER", safe(naturalBalances.get(AccountType.OTHER_EXPENSE))));

    BigDecimal netIncome = grossProfit.subtract(operatingExpenses);
    return new ProfitLossSnapshot(
        revenue, cogs, grossProfit, operatingExpenses, expenseCategories, netIncome);
  }

  private void mergeNaturalBalances(
      Map<AccountType, BigDecimal> naturalBalances, List<Object[]> rows, BigDecimal multiplier) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    for (Object[] row : rows) {
      if (row == null || row.length < 3 || row[0] == null) {
        continue;
      }
      AccountType type = (AccountType) row[0];
      BigDecimal debit = safe((BigDecimal) row[1]).multiply(multiplier);
      BigDecimal credit = safe((BigDecimal) row[2]).multiply(multiplier);
      BigDecimal natural = toNatural(type, debit, credit);
      naturalBalances.merge(type, natural, BigDecimal::add);
    }
  }

  private ReportMetadata liveMetadata(ReportQuerySupport.FinancialQueryWindow window) {
    ReportMetadata base = reportQuerySupport.metadata(window);
    ReportSource source =
        base.source() == ReportSource.AS_OF ? ReportSource.AS_OF : ReportSource.LIVE;
    return new ReportMetadata(
        base.asOfDate(),
        base.startDate(),
        base.endDate(),
        source,
        base.accountingPeriodId(),
        base.accountingPeriodStatus(),
        null,
        base.pdfReady(),
        base.csvReady(),
        base.requestedExportFormat());
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
      BigDecimal netIncome) {}
}
