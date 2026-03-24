package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class TrialBalanceReportQueryServiceTest {

    @Mock
    private ReportQuerySupport reportQuerySupport;
    @Mock
    private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalLineRepository journalLineRepository;

    @Test
    void generate_forDateRangeAggregatesDebitsCreditsAndBalanceCheck() {
        TrialBalanceReportQueryService service = new TrialBalanceReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow window = ReportFixtures.window(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                window.startDate(),
                window.endDate(),
                null,
                null,
                null,
                null,
                null,
                "PDF"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(window)).thenReturn(new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "PDF"
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account sales = account(2L, "REV-SALES", "Sales", AccountType.REVENUE);
        when(accountRepository.findByCompanyOrderByCodeAsc(window.company())).thenReturn(List.of(cash, sales));
        when(journalLineRepository.summarizeByAccountWithin(window.company(), window.startDate(), window.endDate())).thenReturn(List.of(
                row(1L, "150.00", "10.00"),
                row(2L, "0.00", "140.00")
        ));

        TrialBalanceDto report = service.generate(request);

        assertThat(report.totalDebit()).isEqualByComparingTo("150.00");
        assertThat(report.totalCredit()).isEqualByComparingTo("150.00");
        assertThat(report.balanced()).isTrue();
        assertThat(report.rows()).hasSize(2);
        assertThat(report.metadata().startDate()).isEqualTo(window.startDate());
        assertThat(report.metadata().endDate()).isEqualTo(window.endDate());
        assertThat(report.metadata().requestedExportFormat()).isEqualTo("PDF");
        assertThat(report.metadata().pdfReady()).isTrue();
        assertThat(report.metadata().csvReady()).isTrue();
    }

    @Test
    void generate_withComparativeRangeReturnsSideBySideComparativeSnapshot() {
        TrialBalanceReportQueryService service = new TrialBalanceReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

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
                "CSV"
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
                "CSV"
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
                "CSV"
        ));

        Account cash = account(11L, "CASH", "Cash", AccountType.ASSET);
        Account sales = account(12L, "REV-SALES", "Sales", AccountType.REVENUE);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, sales));
        when(accountRepository.findByCompanyOrderByCodeAsc(comparative.company())).thenReturn(List.of(cash, sales));

        when(journalLineRepository.summarizeByAccountWithin(primary.company(), primary.startDate(), primary.endDate())).thenReturn(List.of(
                row(11L, "200.00", "20.00"),
                row(12L, "0.00", "180.00")
        ));
        when(journalLineRepository.summarizeByAccountWithin(comparative.company(), comparative.startDate(), comparative.endDate())).thenReturn(List.of(
                row(11L, "120.00", "20.00"),
                row(12L, "0.00", "100.00")
        ));

        TrialBalanceDto report = service.generate(request);

        assertThat(report.comparative()).isNotNull();
        assertThat(report.totalDebit()).isEqualByComparingTo("200.00");
        assertThat(report.totalCredit()).isEqualByComparingTo("200.00");
        assertThat(report.comparative().totalDebit()).isEqualByComparingTo("120.00");
        assertThat(report.comparative().totalCredit()).isEqualByComparingTo("120.00");
        assertThat(report.metadata().requestedExportFormat()).isEqualTo("CSV");
        assertThat(report.comparative().metadata().startDate()).isEqualTo(comparative.startDate());
        assertThat(report.comparative().metadata().endDate()).isEqualTo(comparative.endDate());
    }

    @Test
    void generate_asOfUsesUpToSummaryAndSkipsMalformedRows() {
        TrialBalanceReportQueryService service = new TrialBalanceReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var company = new com.bigbrightpaints.erp.modules.company.domain.Company();
        ReflectionTestUtils.setField(company, "id", 7001L);
        company.setCode("RPT");
        company.setTimezone("UTC");
        LocalDate asOfDate = LocalDate.of(2026, 3, 15);
        ReportQuerySupport.FinancialQueryWindow window = new ReportQuerySupport.FinancialQueryWindow(
                company,
                null,
                null,
                asOfDate,
                null,
                null,
                ReportSource.AS_OF,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                asOfDate,
                null,
                null,
                null,
                null,
                "CSV"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(window)).thenReturn(new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "CSV"
        ));

        Account cash = account(21L, "CASH", "Cash", AccountType.ASSET);
        Account payables = account(22L, "PAYABLE", "Payables", AccountType.LIABILITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(window.company())).thenReturn(List.of(cash, payables));
        when(journalLineRepository.summarizeByAccountUpTo(window.company(), window.asOfDate())).thenReturn(java.util.Arrays.asList(
                null,
                new Object[]{21L, new BigDecimal("100.00"), new BigDecimal("5.00")},
                new Object[]{22L, BigDecimal.ZERO, new BigDecimal("95.00")},
                new Object[]{22L, BigDecimal.ONE},
                new Object[]{null, BigDecimal.ONE, BigDecimal.ONE}
        ));

        TrialBalanceDto report = service.generate(request);

        assertThat(report.totalDebit()).isEqualByComparingTo("100.00");
        assertThat(report.totalCredit()).isEqualByComparingTo("100.00");
        assertThat(report.balanced()).isTrue();
        assertThat(report.rows()).hasSize(2);
        assertThat(report.rows().getFirst().net()).isEqualByComparingTo("95.00");
        assertThat(report.rows().get(1).net()).isEqualByComparingTo("95.00");
    }


    @Test
    void generate_withoutDateRangeFallsBackToAsOfSummaryForNonAsOfSource() {
        TrialBalanceReportQueryService service = new TrialBalanceReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var company = new com.bigbrightpaints.erp.modules.company.domain.Company();
        ReflectionTestUtils.setField(company, "id", 7002L);
        company.setCode("RPT-FALLBACK");
        company.setTimezone("UTC");
        LocalDate asOfDate = LocalDate.of(2026, 4, 1);
        ReportQuerySupport.FinancialQueryWindow window = new ReportQuerySupport.FinancialQueryWindow(
                company,
                null,
                null,
                asOfDate,
                null,
                null,
                ReportSource.LIVE,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                asOfDate,
                null,
                null,
                null,
                null,
                "CSV"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(window)).thenReturn(new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "CSV"
        ));

        Account cash = account(31L, "CASH", "Cash", AccountType.ASSET);
        when(accountRepository.findByCompanyOrderByCodeAsc(window.company())).thenReturn(List.of(cash));
        when(journalLineRepository.summarizeByAccountUpTo(window.company(), asOfDate))
                .thenReturn(List.<Object[]>of(row(31L, "75.00", "5.00")));

        TrialBalanceDto report = service.generate(request);

        assertThat(report.totalDebit()).isEqualByComparingTo("75.00");
        assertThat(report.totalCredit()).isEqualByComparingTo("5.00");
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().getFirst().net()).isEqualByComparingTo("70.00");
    }

    @Test
    void generate_withPartialDateRangeFallsBackToAsOfSummary() {
        TrialBalanceReportQueryService service = new TrialBalanceReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var company = new com.bigbrightpaints.erp.modules.company.domain.Company();
        ReflectionTestUtils.setField(company, "id", 7003L);
        company.setCode("RPT-PARTIAL");
        company.setTimezone("UTC");
        LocalDate asOfDate = LocalDate.of(2026, 4, 2);
        ReportQuerySupport.FinancialQueryWindow window = new ReportQuerySupport.FinancialQueryWindow(
                company,
                LocalDate.of(2026, 4, 1),
                null,
                asOfDate,
                null,
                null,
                ReportSource.LIVE,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                window.startDate(),
                window.endDate(),
                asOfDate,
                null,
                null,
                null,
                null,
                "CSV"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(window)).thenReturn(new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "CSV"
        ));

        Account payables = account(32L, "PAYABLE", "Payables", AccountType.LIABILITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(window.company())).thenReturn(List.of(payables));
        when(journalLineRepository.summarizeByAccountUpTo(window.company(), asOfDate))
                .thenReturn(List.<Object[]>of(row(32L, "10.00", "40.00")));

        TrialBalanceDto report = service.generate(request);

        assertThat(report.totalDebit()).isEqualByComparingTo("10.00");
        assertThat(report.totalCredit()).isEqualByComparingTo("40.00");
        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().getFirst().net()).isEqualByComparingTo("30.00");
    }
    private Account account(Long id, String code, String name, AccountType type) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        return account;
    }

    private Object[] row(Long accountId, String debit, String credit) {
        return new Object[]{accountId, new BigDecimal(debit), new BigDecimal(credit)};
    }
}
