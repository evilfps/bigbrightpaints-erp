package com.bigbrightpaints.erp.modules.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingBucketDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AgingSummaryResponse;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.support.ReflectionFieldAccess;

@ExtendWith(MockitoExtension.class)
class AgingReportServiceTest {

  @Mock private DealerLedgerRepository dealerLedgerRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyClock companyClock;
  @Mock private StatementService statementService;

  private AgingReportService agingReportService;
  private Company company;

  @BeforeEach
  void setUp() {
    agingReportService =
        new AgingReportService(
            dealerLedgerRepository,
            dealerRepository,
            companyContextService,
            companyClock,
            statementService);
    company = new Company();
    ReflectionFieldAccess.setField(company, "id", 99L);
    when(companyContextService.requireCurrentCompany()).thenReturn(company);
  }

  @Test
  void getAgedReceivablesReport_usesAsOfScopedQuery() {
    LocalDate asOf = LocalDate.of(2026, 2, 1);
    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 501L);
    dealer.setCode("DLR-501");
    dealer.setName("Dealer 501");
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(statementService.dealerAging(dealer, asOf, "0-0,1-30,31-60,61-90,91"))
        .thenReturn(
            new AgingSummaryResponse(
                dealer.getId(),
                dealer.getName(),
                new BigDecimal("100.00"),
                List.of(new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("100.00")))));

    AgingReportService.AgedReceivablesReport report =
        agingReportService.getAgedReceivablesReport(asOf);

    assertThat(report.asOfDate()).isEqualTo(asOf);
    assertThat(report.grandTotal()).isEqualByComparingTo("100.00");
    verify(dealerRepository).findByCompanyOrderByNameAsc(company);
    verify(statementService).dealerAging(dealer, asOf, "0-0,1-30,31-60,61-90,91");
    verify(dealerLedgerRepository, never()).findAllUnpaid(company);
    verify(dealerLedgerRepository, never()).findAllUnpaidAsOf(company, asOf);
  }

  @Test
  void getAgedReceivablesReport_defaultsAsOfToCompanyToday() {
    LocalDate today = LocalDate.of(2026, 2, 12);
    when(companyClock.today(company)).thenReturn(today);
    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of());

    AgingReportService.AgedReceivablesReport report = agingReportService.getAgedReceivablesReport();

    assertThat(report.asOfDate()).isEqualTo(today);
    assertThat(report.grandTotal()).isEqualByComparingTo("0.00");
    verify(dealerRepository).findByCompanyOrderByNameAsc(company);
    verify(dealerLedgerRepository, never()).findAllUnpaid(company);
    verify(dealerLedgerRepository, never()).findAllUnpaidAsOf(company, today);
  }

  @Test
  void getDealerAgingRows_withDateWindowUsesWindowedCanonicalAging() {
    LocalDate asOf = LocalDate.of(2026, 3, 31);
    LocalDate start = LocalDate.of(2026, 3, 1);
    LocalDate end = LocalDate.of(2026, 3, 31);

    Dealer dealer = new Dealer();
    ReflectionFieldAccess.setField(dealer, "id", 502L);
    dealer.setCode("DLR-502");
    dealer.setName("Dealer 502");

    when(dealerRepository.findByCompanyOrderByNameAsc(company)).thenReturn(List.of(dealer));
    when(statementService.dealerAgingWithinEntryWindow(
            dealer, asOf, "0-0,1-30,31-60,61-90,91", start, end))
        .thenReturn(
            new AgingSummaryResponse(
                dealer.getId(),
                dealer.getName(),
                new BigDecimal("120.00"),
                List.of(new AgingBucketDto("1-30 days", 1, 30, new BigDecimal("120.00")))));

    List<AgingReportService.DealerAgingDetail> rows =
        agingReportService.getDealerAgingRows(asOf, start, end);

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().dealerId()).isEqualTo(502L);
    assertThat(rows.getFirst().totalOutstanding()).isEqualByComparingTo("120.00");
    verify(statementService)
        .dealerAgingWithinEntryWindow(dealer, asOf, "0-0,1-30,31-60,61-90,91", start, end);
    verify(statementService, never()).dealerAging(dealer, asOf, "0-0,1-30,31-60,61-90,91");
  }
}
