package com.bigbrightpaints.erp.modules.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;

@ExtendWith(MockitoExtension.class)
class AgedDebtorsReportQueryServiceTest {

  @Mock private ReportQuerySupport reportQuerySupport;
  @Mock private AgingReportService agingReportService;

  @Test
  void generate_groupsOutstandingByAllAgingBucketsAndIncludesMetadataHints() {
    AgedDebtorsReportQueryService service =
        new AgedDebtorsReportQueryService(reportQuerySupport, agingReportService);

    ReportQuerySupport.FinancialQueryWindow window =
        ReportFixtures.window(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31));
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(null, null, null, null, null, null, null, null, "PDF");

    when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
    when(reportQuerySupport.metadata(window))
        .thenReturn(
            new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "PDF"));
    when(agingReportService.getDealerAgingRows(window.asOfDate()))
        .thenReturn(
            List.of(
                dealerAgingDetail(
                    81L, "DLR-81", "Dealer 81", "100.00", "200.00", "300.00", "400.00", "500.00")));

    List<AgedDebtorDto> result = service.generate(request);

    assertThat(result).hasSize(1);
    AgedDebtorDto dto = result.getFirst();
    assertThat(dto.dealerId()).isEqualTo(81L);
    assertThat(dto.dealerCode()).isEqualTo("DLR-81");
    assertThat(dto.dealerName()).isEqualTo("Dealer 81");
    assertThat(dto.current()).isEqualByComparingTo("100.00");
    assertThat(dto.oneToThirtyDays()).isEqualByComparingTo("200.00");
    assertThat(dto.thirtyOneToSixtyDays()).isEqualByComparingTo("300.00");
    assertThat(dto.sixtyOneToNinetyDays()).isEqualByComparingTo("400.00");
    assertThat(dto.ninetyPlusDays()).isEqualByComparingTo("500.00");
    assertThat(dto.totalOutstanding()).isEqualByComparingTo("1500.00");
    assertThat(dto.metadata()).isNotNull();
    assertThat(dto.metadata().requestedExportFormat()).isEqualTo("PDF");
    assertThat(dto.exportHints().pdfReady()).isTrue();
    assertThat(dto.exportHints().csvReady()).isTrue();
    verify(agingReportService).getDealerAgingRows(window.asOfDate());
    verify(agingReportService, never())
        .getDealerAgingRows(window.asOfDate(), window.startDate(), window.endDate());
  }

  @Test
  void generate_withExplicitDateRangeFiltersEntriesOutsideWindow() {
    AgedDebtorsReportQueryService service =
        new AgedDebtorsReportQueryService(reportQuerySupport, agingReportService);

    ReportQuerySupport.FinancialQueryWindow window =
        ReportFixtures.window(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 31));
    FinancialReportQueryRequest request =
        new FinancialReportQueryRequest(
            null, window.startDate(), window.endDate(), null, null, null, null, null, "CSV");

    when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
    when(reportQuerySupport.metadata(window))
        .thenReturn(
            new ReportMetadata(
                window.asOfDate(),
                window.startDate(),
                window.endDate(),
                window.source(),
                null,
                null,
                null,
                true,
                true,
                "CSV"));
    when(agingReportService.getDealerAgingRows(
            window.asOfDate(), window.startDate(), window.endDate()))
        .thenReturn(
            List.of(
                dealerAgingDetail(
                    82L, "DLR-82", "Dealer 82", "0.00", "120.00", "0.00", "0.00", "0.00")));

    List<AgedDebtorDto> result = service.generate(request);

    assertThat(result).hasSize(1);
    AgedDebtorDto dto = result.getFirst();
    assertThat(dto.current()).isEqualByComparingTo("0.00");
    assertThat(dto.oneToThirtyDays()).isEqualByComparingTo("120.00");
    assertThat(dto.thirtyOneToSixtyDays()).isEqualByComparingTo("0.00");
    assertThat(dto.sixtyOneToNinetyDays()).isEqualByComparingTo("0.00");
    assertThat(dto.ninetyPlusDays()).isEqualByComparingTo("0.00");
    assertThat(dto.totalOutstanding()).isEqualByComparingTo("120.00");
    verify(agingReportService)
        .getDealerAgingRows(window.asOfDate(), window.startDate(), window.endDate());
    verify(agingReportService, never()).getDealerAgingRows(window.asOfDate());
  }

  private AgingReportService.DealerAgingDetail dealerAgingDetail(
      Long dealerId,
      String dealerCode,
      String dealerName,
      String current,
      String days1to30,
      String days31to60,
      String days61to90,
      String over90) {
    AgingReportService.AgingBuckets buckets =
        new AgingReportService.AgingBuckets(
            new BigDecimal(current),
            new BigDecimal(days1to30),
            new BigDecimal(days31to60),
            new BigDecimal(days61to90),
            new BigDecimal(over90));
    return new AgingReportService.DealerAgingDetail(
        dealerId, dealerCode, dealerName, buckets, buckets.total());
  }
}
