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
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
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

  private AgingReportService agingReportService;
  private Company company;

  @BeforeEach
  void setUp() {
    agingReportService =
        new AgingReportService(
            dealerLedgerRepository, dealerRepository, companyContextService, companyClock);
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

    DealerLedgerEntry entry = new DealerLedgerEntry();
    entry.setDealer(dealer);
    entry.setEntryDate(asOf.minusDays(10));
    entry.setDueDate(asOf.minusDays(1));
    entry.setInvoiceNumber("INV-501");
    entry.setPaymentStatus("UNPAID");
    entry.setDebit(new BigDecimal("100.00"));
    entry.setCredit(BigDecimal.ZERO);
    entry.setAmountPaid(BigDecimal.ZERO);

    when(dealerLedgerRepository.findAllUnpaidAsOf(company, asOf)).thenReturn(List.of(entry));

    AgingReportService.AgedReceivablesReport report =
        agingReportService.getAgedReceivablesReport(asOf);

    assertThat(report.asOfDate()).isEqualTo(asOf);
    assertThat(report.grandTotal()).isEqualByComparingTo("100.00");
    verify(dealerLedgerRepository).findAllUnpaidAsOf(company, asOf);
    verify(dealerLedgerRepository, never()).findAllUnpaid(company);
  }

  @Test
  void getAgedReceivablesReport_defaultsAsOfToCompanyToday() {
    LocalDate today = LocalDate.of(2026, 2, 12);
    when(companyClock.today(company)).thenReturn(today);
    when(dealerLedgerRepository.findAllUnpaidAsOf(company, today)).thenReturn(List.of());

    AgingReportService.AgedReceivablesReport report = agingReportService.getAgedReceivablesReport();

    assertThat(report.asOfDate()).isEqualTo(today);
    assertThat(report.grandTotal()).isEqualByComparingTo("0.00");
    verify(dealerLedgerRepository).findAllUnpaidAsOf(company, today);
    verify(dealerLedgerRepository, never()).findAllUnpaid(company);
  }
}
