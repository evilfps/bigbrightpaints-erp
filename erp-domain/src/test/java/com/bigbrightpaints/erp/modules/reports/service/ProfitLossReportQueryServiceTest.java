package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ProfitLossDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfitLossReportQueryServiceTest {

    @Mock
    private ReportQuerySupport reportQuerySupport;
    @Mock
    private JournalLineRepository journalLineRepository;

    @Test
    void generate_computesRevenueCogsAndCategorizedExpensesWithComparative() {
        ProfitLossReportQueryService service = new ProfitLossReportQueryService(reportQuerySupport, journalLineRepository);

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );
        ReportQuerySupport.FinancialQueryWindow comparative = ReportFixtures.window(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 2, 28)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                comparative.startDate(),
                comparative.endDate(),
                null,
                "PDF"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(new ReportQuerySupport.FinancialComparisonWindow(comparative));
        when(reportQuerySupport.metadata(primary)).thenReturn(new ReportMetadata(
                primary.asOfDate(),
                primary.startDate(),
                primary.endDate(),
                primary.source(),
                null,
                null,
                null,
                true,
                true,
                "PDF"
        ));
        when(reportQuerySupport.metadata(comparative)).thenReturn(new ReportMetadata(
                comparative.asOfDate(),
                comparative.startDate(),
                comparative.endDate(),
                comparative.source(),
                null,
                null,
                null,
                true,
                true,
                "PDF"
        ));

        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        row(AccountType.REVENUE, "0.00", "1000.00"),
                        row(AccountType.OTHER_INCOME, "0.00", "50.00"),
                        row(AccountType.COGS, "400.00", "0.00"),
                        row(AccountType.EXPENSE, "200.00", "0.00"),
                        row(AccountType.OTHER_EXPENSE, "40.00", "0.00")
                ));
        when(journalLineRepository.summarizeByAccountType(comparative.company(), comparative.startDate(), comparative.endDate()))
                .thenReturn(List.of(
                        row(AccountType.REVENUE, "0.00", "800.00"),
                        row(AccountType.COGS, "350.00", "0.00"),
                        row(AccountType.EXPENSE, "150.00", "0.00"),
                        row(AccountType.OTHER_EXPENSE, "20.00", "0.00")
                ));

        ProfitLossDto dto = service.generate(request);

        assertThat(dto.revenue()).isEqualByComparingTo("1050.00");
        assertThat(dto.costOfGoodsSold()).isEqualByComparingTo("400.00");
        assertThat(dto.grossProfit()).isEqualByComparingTo("650.00");
        assertThat(dto.operatingExpenses()).isEqualByComparingTo("240.00");
        assertThat(dto.netIncome()).isEqualByComparingTo("410.00");
        assertThat(dto.operatingExpenseCategories()).hasSize(2);
        assertThat(dto.operatingExpenseCategories().get(0).category()).isEqualTo("OPERATING");
        assertThat(dto.operatingExpenseCategories().get(0).amount()).isEqualByComparingTo("200.00");
        assertThat(dto.operatingExpenseCategories().get(1).category()).isEqualTo("OTHER");
        assertThat(dto.operatingExpenseCategories().get(1).amount()).isEqualByComparingTo("40.00");

        assertThat(dto.comparative()).isNotNull();
        assertThat(dto.comparative().revenue()).isEqualByComparingTo("800.00");
        assertThat(dto.comparative().costOfGoodsSold()).isEqualByComparingTo("350.00");
        assertThat(dto.comparative().grossProfit()).isEqualByComparingTo("450.00");
        assertThat(dto.comparative().operatingExpenses()).isEqualByComparingTo("170.00");
        assertThat(dto.comparative().netIncome()).isEqualByComparingTo("280.00");
    }

    private Object[] row(AccountType type, String debit, String credit) {
        return new Object[]{type, new BigDecimal(debit), new BigDecimal(credit)};
    }
}
