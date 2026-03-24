package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.reports.dto.BalanceSheetDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class BalanceSheetReportQueryServiceTest {

    @Mock
    private ReportQuerySupport reportQuerySupport;
    @Mock
    private AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalLineRepository journalLineRepository;

    @Test
    void generate_groupsCurrentAndLongTermSectionsAndValidatesEquation() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
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

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                "CSV"
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account machinery = account(2L, "FIXED-ASSETS", "Fixed Assets", AccountType.ASSET);
        Account payable = account(3L, "AP", "Accounts Payable", AccountType.LIABILITY);
        Account loan = account(4L, "LONG-TERM-BORROWINGS", "Long-Term Borrowings", AccountType.LIABILITY);
        Account equity = account(5L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, machinery, payable, loan, equity));

        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        row(1L, "1200.00", "0.00"),
                        row(2L, "800.00", "0.00"),
                        row(3L, "0.00", "300.00"),
                        row(4L, "0.00", "500.00"),
                        row(5L, "0.00", "1200.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("2000.00");
        assertThat(dto.totalLiabilities()).isEqualByComparingTo("800.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("1200.00");
        assertThat(dto.balanced()).isTrue();

        assertThat(dto.currentAssets()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("CASH");
        assertThat(dto.fixedAssets()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("FIXED-ASSETS");
        assertThat(dto.currentLiabilities()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("AP");
        assertThat(dto.longTermLiabilities()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("LONG-TERM-BORROWINGS");
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS");
    }

    @Test
    void generate_rollsCurrentPeriodEarningsIntoLiveEquity() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
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

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account payable = account(2L, "LOAN", "Loan", AccountType.LIABILITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, payable));

        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        row(1L, "90.00", "0.00"),
                        row(2L, "0.00", "30.00")
                ));
        when(journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(null);
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        typeRow(AccountType.REVENUE, "0.00", "100.00"),
                        typeRow(AccountType.EXPENSE, "40.00", "0.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("90.00");
        assertThat(dto.totalLiabilities()).isEqualByComparingTo("30.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("60.00");
        assertThat(dto.balanced()).isTrue();
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("CURRENT-EARNINGS");
    }

    @Test
    void generate_skipsCurrentEarningsForClosedSnapshot() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var snapshot = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot();
        var period = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod();
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 31));
        ReportQuerySupport.FinancialQueryWindow snapshotWindow = new ReportQuerySupport.FinancialQueryWindow(
                ReportFixtures.window(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31)).company(),
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31),
                period,
                snapshot,
                com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                44L,
                snapshotWindow.startDate(),
                snapshotWindow.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(snapshotWindow);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(snapshotWindow)).thenReturn(new ReportMetadata(
                snapshotWindow.asOfDate(),
                snapshotWindow.startDate(),
                snapshotWindow.endDate(),
                snapshotWindow.source(),
                null,
                null,
                null,
                true,
                true,
                null
        ));

        var line = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLine();
        line.setAccountId(5L);
        line.setAccountCode("RETAINED-EARNINGS");
        line.setAccountName("Retained Earnings");
        line.setAccountType(AccountType.EQUITY);
        line.setDebit(BigDecimal.ZERO);
        line.setCredit(new BigDecimal("60.00"));
        when(snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(snapshot)).thenReturn(List.of(line));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalEquity()).isEqualByComparingTo("60.00");
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS");
        org.mockito.Mockito.verifyNoInteractions(journalLineRepository);
    }

    @Test
    void generate_excludesPeriodCloseJournalsFromLiveRangeSummary() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var snapshot = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot();
        var period = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod();
        period.setYear(2026);
        period.setMonth(3);
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 31));
        period.setStatus(com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus.CLOSED);

        ReportQuerySupport.FinancialQueryWindow primary = new ReportQuerySupport.FinancialQueryWindow(
                ReportFixtures.window(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31)).company(),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31),
                period,
                snapshot,
                com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                new ReportQuerySupport.ExportOptions(true, true, null)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                44L,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
        when(reportQuerySupport.metadata(primary)).thenReturn(new ReportMetadata(
                primary.asOfDate(),
                primary.startDate(),
                primary.endDate(),
                primary.source(),
                44L,
                period.getStatus().name(),
                null,
                true,
                true,
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account payable = account(2L, "AP", "Accounts Payable", AccountType.LIABILITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, payable));
        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(List.of(
                        row(1L, "100.00", "0.00"),
                        row(2L, "0.00", "40.00"),
                        row(3L, "0.00", "60.00")
                ));
        when(journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(List.<Object[]>of(row(3L, "0.00", "60.00")));
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        typeRow(AccountType.REVENUE, "0.00", "100.00"),
                        typeRow(AccountType.EXPENSE, "40.00", "0.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("100.00");
        assertThat(dto.totalLiabilities()).isEqualByComparingTo("40.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("60.00");
        assertThat(dto.balanced()).isTrue();
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("CURRENT-EARNINGS");
        verify(journalLineRepository).summarizeByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate());
        verify(journalLineRepository).summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate());
    }

    @Test
    void generate_excludesPeriodCloseJournalsForMultiMonthLiveRange() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account retained = account(2L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, retained));
        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(java.util.List.<Object[]>of(
                        row(1L, "120.00", "0.00"),
                        row(2L, "0.00", "120.00")
                ));
        when(journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(java.util.List.<Object[]>of(
                        row(2L, "0.00", "120.00")
                ));
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(java.util.List.<Object[]>of(
                        typeRow(AccountType.REVENUE, "0.00", "150.00"),
                        typeRow(AccountType.EXPENSE, "30.00", "0.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("120.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("120.00");
        assertThat(dto.balanced()).isTrue();
        assertThat(dto.equityLines()).hasSize(2);
        assertThat(dto.equityLines().get(0).accountCode()).isEqualTo("RETAINED-EARNINGS");
        assertThat(dto.equityLines().get(0).amount()).isEqualByComparingTo("0.00");
        assertThat(dto.equityLines().get(1).accountCode()).isEqualTo("CURRENT-EARNINGS");
        assertThat(dto.equityLines().get(1).amount()).isEqualByComparingTo("120.00");
        verify(journalLineRepository).summarizeByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate());
        verify(journalLineRepository).summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate());
    }

    @Test
    void generate_preservesNonSystemPeriodClosePrefixedJournalsInLiveRangeSummary() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account retained = account(2L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, retained));
        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(java.util.List.<Object[]>of(
                        row(1L, "100.00", "0.00"),
                        row(2L, "0.00", "140.00")
                ));
        when(journalLineRepository.summarizePostedPeriodCloseSystemJournalsByAccountWithin(
                primary.company(),
                primary.startDate(),
                primary.endDate()))
                .thenReturn(java.util.Arrays.asList(
                        null,
                        new Object[]{2L, BigDecimal.ZERO},
                        new Object[]{null, BigDecimal.ZERO, BigDecimal.ZERO},
                        row(2L, "0.00", "40.00")
                ));
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of());

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("100.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("100.00");
        assertThat(dto.balanced()).isTrue();
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS");
    }

    @Test
    void generate_ignoresMalformedCurrentEarningsRowsAndSupportsIncomeAndExpenseFamilies() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 4, 30)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account equity = account(2L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company())).thenReturn(List.of(cash, equity));
        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(java.util.Arrays.asList(
                        null,
                        new Object[]{1L, new BigDecimal("999.99")},
                        row(1L, "70.00", "0.00"),
                        row(2L, "0.00", "40.00")
                ));
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(java.util.Arrays.asList(
                        null,
                        new Object[]{AccountType.REVENUE, BigDecimal.ONE},
                        new Object[]{null, BigDecimal.ONE, BigDecimal.ZERO},
                        new Object[]{AccountType.OTHER_INCOME, BigDecimal.ZERO, new BigDecimal("20.00")},
                        new Object[]{AccountType.COGS, new BigDecimal("10.00"), BigDecimal.ZERO},
                        new Object[]{AccountType.OTHER_EXPENSE, new BigDecimal("5.00"), BigDecimal.ZERO},
                        new Object[]{AccountType.LIABILITY, BigDecimal.ZERO, BigDecimal.ZERO}
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("70.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("45.00");
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS", "CURRENT-EARNINGS");
        assertThat(dto.equityLines().getLast().amount()).isEqualByComparingTo("5.00");
    }

    @Test
    void generate_omitsCurrentEarningsLineWhenLiveWindowNetIsZero() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        ReportQuerySupport.FinancialQueryWindow primary = ReportFixtures.window(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 5, 31)
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                primary.startDate(),
                primary.endDate(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(reportQuerySupport.resolveWindow(request)).thenReturn(primary);
        when(reportQuerySupport.resolveComparison(request)).thenReturn(null);
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
                null
        ));

        Account cash = account(1L, "CASH", "Cash", AccountType.ASSET);
        Account payable = account(2L, "AP", "Accounts Payable", AccountType.LIABILITY);
        Account equity = account(3L, "RETAINED-EARNINGS", "Retained Earnings", AccountType.EQUITY);
        Account revenue = account(4L, "SALES", "Sales", AccountType.REVENUE);
        when(accountRepository.findByCompanyOrderByCodeAsc(primary.company()))
                .thenReturn(List.of(cash, payable, equity, revenue));
        when(journalLineRepository.summarizeByAccountWithin(
                primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        row(1L, "40.00", "0.00"),
                        row(2L, "0.00", "20.00"),
                        row(3L, "0.00", "20.00"),
                        row(4L, "0.00", "999.00")
                ));
        when(journalLineRepository.summarizeByAccountType(primary.company(), primary.startDate(), primary.endDate()))
                .thenReturn(List.of(
                        typeRow(AccountType.REVENUE, "0.00", "10.00"),
                        typeRow(AccountType.EXPENSE, "10.00", "0.00")
                ));

        BalanceSheetDto dto = service.generate(request);

        assertThat(dto.totalAssets()).isEqualByComparingTo("40.00");
        assertThat(dto.totalLiabilities()).isEqualByComparingTo("20.00");
        assertThat(dto.totalEquity()).isEqualByComparingTo("20.00");
        assertThat(dto.balanced()).isTrue();
        assertThat(dto.equityLines()).extracting(BalanceSheetDto.SectionLine::accountCode)
                .containsExactly("RETAINED-EARNINGS");
    }

    @Test
    void helper_branches_cover_snapshot_and_period_close_shortCircuits() {
        BalanceSheetReportQueryService service = new BalanceSheetReportQueryService(
                reportQuerySupport,
                snapshotLineRepository,
                accountRepository,
                journalLineRepository
        );

        var base = ReportFixtures.window(
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 6, 30)
        );
        var snapshot = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot();
        var period = new com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod();
        period.setYear(2026);
        period.setMonth(6);
        period.setStartDate(base.startDate());
        period.setEndDate(base.endDate());
        period.setStatus(com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus.CLOSED);

        var fullSnapshotWindow = new ReportQuerySupport.FinancialQueryWindow(
                base.company(),
                base.startDate(),
                base.endDate(),
                base.asOfDate(),
                period,
                snapshot,
                com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                base.exportOptions()
        );

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot", base)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot",
                new ReportQuerySupport.FinancialQueryWindow(
                        base.company(),
                        base.startDate(),
                        base.endDate(),
                        base.asOfDate(),
                        period,
                        null,
                        com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                        base.exportOptions()
                ))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot",
                new ReportQuerySupport.FinancialQueryWindow(
                        base.company(),
                        base.startDate(),
                        base.endDate(),
                        base.asOfDate(),
                        null,
                        snapshot,
                        com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                        base.exportOptions()
                ))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot",
                new ReportQuerySupport.FinancialQueryWindow(
                        base.company(),
                        base.startDate().minusDays(1),
                        base.endDate(),
                        base.asOfDate(),
                        period,
                        snapshot,
                        com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                        base.exportOptions()
                ))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot",
                new ReportQuerySupport.FinancialQueryWindow(
                        base.company(),
                        base.startDate(),
                        base.endDate().minusDays(1),
                        base.asOfDate(),
                        period,
                        snapshot,
                        com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                        base.exportOptions()
                ))).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "usesClosedSnapshot",
                new ReportQuerySupport.FinancialQueryWindow(
                        base.company(),
                        base.startDate(),
                        base.endDate(),
                        base.asOfDate(),
                        period,
                        snapshot,
                        com.bigbrightpaints.erp.modules.reports.dto.ReportSource.SNAPSHOT,
                        base.exportOptions()
                ))).isTrue();

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "isBalanceSheetType", AccountType.ASSET)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(service, "isBalanceSheetType", AccountType.REVENUE)).isFalse();
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

    private Object[] typeRow(AccountType type, String debit, String credit) {
        return new Object[]{type, new BigDecimal(debit), new BigDecimal(credit)};
    }
}
