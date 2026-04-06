package com.bigbrightpaints.erp.modules.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.portal.dto.EnterpriseDashboardSnapshot;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;

@ExtendWith(MockitoExtension.class)
class EnterpriseDashboardServiceTest {

  private static final long MAX_DASHBOARD_WINDOW_DAYS = 366L;

  @Mock private CompanyContextService companyContextService;
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private SalesOrderRepository salesOrderRepository;
  @Mock private PartnerSettlementAllocationRepository settlementAllocationRepository;
  @Mock private JournalEntryRepository journalEntryRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private PackingRecordRepository packingRecordRepository;
  @Mock private PackagingSlipRepository packagingSlipRepository;
  @Mock private ReportService reportService;

  private EnterpriseDashboardService service;
  private Company company;

  @BeforeEach
  void setUp() {
    service =
        new EnterpriseDashboardService(
            companyContextService,
            invoiceRepository,
            salesOrderRepository,
            settlementAllocationRepository,
            journalEntryRepository,
            accountRepository,
            productionLogRepository,
            packingRecordRepository,
            packagingSlipRepository,
            reportService);

    company = new Company();
    company.setCode("TENANT");
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");

    when(companyContextService.requireCurrentCompany()).thenReturn(company);
    when(invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            eq(company), any(LocalDate.class), any(LocalDate.class)))
        .thenReturn(List.of());
    when(invoiceRepository.findByCompanyOrderByIssueDateDesc(company)).thenReturn(List.of());
    when(journalEntryRepository.findByCompanyAndEntryDateBetweenOrderByEntryDateAsc(
            eq(company), any(LocalDate.class), any(LocalDate.class)))
        .thenReturn(List.of());
    when(journalEntryRepository.findByCompanyAndEntryDateAfterOrderByEntryDateAsc(
            eq(company), any(LocalDate.class)))
        .thenReturn(List.of());
    when(accountRepository.findByCompanyOrderByCodeAsc(company)).thenReturn(List.of());
    when(reportService.inventoryValuation())
        .thenReturn(
            new InventoryValuationDto(
                BigDecimal.ZERO, 0L, "FIFO", List.of(), List.of(), List.of(), null));
    when(salesOrderRepository.findByCompanyOrderByCreatedAtDesc(company)).thenReturn(List.of());
    when(productionLogRepository.findByCompanyAndProducedAtBetween(eq(company), any(), any()))
        .thenReturn(List.of());
    when(packingRecordRepository.findByCompanyAndPackedDateBetween(
            eq(company), any(LocalDate.class), any(LocalDate.class)))
        .thenReturn(List.of());
    when(packagingSlipRepository.findByCompanyAndDispatchedAtBetween(eq(company), any(), any()))
        .thenReturn(List.of());
    when(settlementAllocationRepository.findByCompanyAndPartnerTypeAndSettlementDateBetween(
            eq(company), any(), any(LocalDate.class), any(LocalDate.class)))
        .thenReturn(List.of());
    when(packagingSlipRepository.countByCompanyAndStatusInAndCreatedAtBefore(
            eq(company), any(), any()))
        .thenReturn(0L);
  }

  @Test
  void snapshot_prevComparisonClampsHugeParseableWindowBeforeQueries() {
    EnterpriseDashboardSnapshot snapshot =
        service.snapshot(hugeParseableWindowSpec(), "prev", "UTC");

    assertThat(windowLength(snapshot.window())).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
    assertThat(snapshot.window().compareStart()).isNotNull();
    assertThat(snapshot.window().compareEnd())
        .isEqualTo(snapshot.window().currentWindowStart().minusDays(1));
    assertThat(snapshot.trends().revenue()).hasSize(53);

    ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
    verify(invoiceRepository)
        .findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            eq(company), startCaptor.capture(), endCaptor.capture());
    assertThat(startCaptor.getValue()).isEqualTo(snapshot.window().currentWindowStart());
    assertThat(endCaptor.getValue()).isEqualTo(snapshot.window().currentWindowEnd());
    assertThat(startCaptor.getValue()).isAfter(LocalDate.MIN);
  }

  @Test
  void snapshot_yoyComparisonClampsHugeParseableWindowBeforeQueries() {
    EnterpriseDashboardSnapshot snapshot =
        service.snapshot(hugeParseableWindowSpec(), "yoy", "UTC");

    assertThat(windowLength(snapshot.window())).isEqualTo(MAX_DASHBOARD_WINDOW_DAYS);
    assertThat(snapshot.window().compareStart())
        .isEqualTo(snapshot.window().currentWindowStart().minusYears(1));
    assertThat(snapshot.window().compareEnd())
        .isEqualTo(snapshot.window().currentWindowEnd().minusYears(1));
    assertThat(snapshot.trends().revenue()).hasSize(53);

    ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
    ArgumentCaptor<LocalDate> endCaptor = ArgumentCaptor.forClass(LocalDate.class);
    verify(invoiceRepository)
        .findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(
            eq(company), startCaptor.capture(), endCaptor.capture());
    assertThat(startCaptor.getValue()).isEqualTo(snapshot.window().currentWindowStart());
    assertThat(endCaptor.getValue()).isEqualTo(snapshot.window().currentWindowEnd());
    assertThat(startCaptor.getValue()).isAfter(LocalDate.MIN);
  }

  private static long windowLength(EnterpriseDashboardSnapshot.Window window) {
    return ChronoUnit.DAYS.between(window.currentWindowStart(), window.currentWindowEnd()) + 1;
  }

  private static String hugeParseableWindowSpec() {
    long days = ChronoUnit.DAYS.between(LocalDate.MIN, LocalDate.now(ZoneId.of("UTC"))) + 1;
    return days + "d";
  }
}
