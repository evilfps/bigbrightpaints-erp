package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.reports.dto.ReportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportQuerySupportTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private AccountingPeriodRepository accountingPeriodRepository;
    @Mock
    private AccountingPeriodSnapshotRepository snapshotRepository;
    @Mock
    private CompanyClock companyClock;

    @Test
    void resolveWindow_closedPeriodUsesSnapshotAndPeriodRange() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        Company company = company(501L);
        AccountingPeriod period = new AccountingPeriod();
        ReflectionTestUtils.setField(period, "id", 900L);
        period.setCompany(company);
        period.setYear(2026);
        period.setMonth(3);
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 31));
        period.setStatus(AccountingPeriodStatus.CLOSED);

        AccountingPeriodSnapshot snapshot = new AccountingPeriodSnapshot();
        ReflectionTestUtils.setField(snapshot, "id", 42L);
        snapshot.setCompany(company);
        snapshot.setPeriod(period);

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                900L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PDF"
        );

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 20));
        when(accountingPeriodRepository.findByCompanyAndId(company, 900L)).thenReturn(Optional.of(period));
        when(snapshotRepository.findByCompanyAndPeriod(company, period)).thenReturn(Optional.of(snapshot));

        ReportQuerySupport.FinancialQueryWindow window = support.resolveWindow(request);

        assertThat(window.source()).isEqualTo(ReportSource.SNAPSHOT);
        assertThat(window.snapshot()).isEqualTo(snapshot);
        assertThat(window.startDate()).isEqualTo(period.getStartDate());
        assertThat(window.endDate()).isEqualTo(period.getEndDate());
        assertThat(window.exportOptions().requestedFormat()).isEqualTo("PDF");
    }

    @Test
    void resolveWindow_rejectsCompanyMismatch() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        Company company = company(700L);
        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                701L,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> support.resolveWindow(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Requested company does not match authenticated company context");
    }

    @Test
    void resolveWindow_withoutPeriodOrRangeDefaultsToMonthToDateWindow() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        Company company = company(888L);
        LocalDate today = LocalDate.of(2026, 3, 18);
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
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

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(today);
        when(accountingPeriodRepository.findByCompanyAndYearAndMonth(company, 2026, 3)).thenReturn(Optional.empty());

        ReportQuerySupport.FinancialQueryWindow window = support.resolveWindow(request);

        assertThat(window.startDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(window.endDate()).isEqualTo(today);
        assertThat(window.source()).isEqualTo(ReportSource.LIVE);
    }

    @Test
    void resolveWindow_rejectsUnsupportedExportFormat() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        Company company = company(777L);
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "XLSX"
        );

        when(companyContextService.requireCurrentCompany()).thenReturn(company);

        assertThatThrownBy(() -> support.resolveWindow(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Unsupported exportFormat");
    }

    @Test
    void resolveComparison_requiresComparativeDatePair() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2026, 2, 1),
                null,
                null,
                null
        );

        assertThatThrownBy(() -> support.resolveComparison(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Both comparativeStartDate and comparativeEndDate must be provided together");
    }

    @Test
    void resolveComparison_withComparativePeriodDelegatesToResolveWindow() {
        ReportQuerySupport support = new ReportQuerySupport(
                companyContextService,
                accountingPeriodRepository,
                snapshotRepository,
                companyClock
        );

        Company company = company(990L);
        AccountingPeriod comparativePeriod = new AccountingPeriod();
        ReflectionTestUtils.setField(comparativePeriod, "id", 321L);
        comparativePeriod.setCompany(company);
        comparativePeriod.setYear(2026);
        comparativePeriod.setMonth(2);
        comparativePeriod.setStartDate(LocalDate.of(2026, 2, 1));
        comparativePeriod.setEndDate(LocalDate.of(2026, 2, 28));
        comparativePeriod.setStatus(AccountingPeriodStatus.OPEN);

        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                null,
                null,
                null,
                null,
                321L,
                "CSV"
        );

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 20));
        when(accountingPeriodRepository.findByCompanyAndId(company, 321L)).thenReturn(Optional.of(comparativePeriod));

        ReportQuerySupport.FinancialComparisonWindow comparison = support.resolveComparison(request);

        assertThat(comparison).isNotNull();
        assertThat(comparison.window().period()).isEqualTo(comparativePeriod);
        assertThat(comparison.window().startDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(comparison.window().endDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(comparison.window().exportOptions().requestedFormat()).isEqualTo("CSV");
    }

    private Company company(Long id) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", id);
        company.setCode("RPT-" + id);
        company.setTimezone("UTC");
        return company;
    }
}
