package com.bigbrightpaints.erp.modules.reports.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.service.AgingReportService;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.ExportHints;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;

@Service
@Transactional(readOnly = true)
public class AgedDebtorsReportQueryService {

  private final ReportQuerySupport reportQuerySupport;
  private final AgingReportService agingReportService;

  public AgedDebtorsReportQueryService(
      ReportQuerySupport reportQuerySupport, AgingReportService agingReportService) {
    this.reportQuerySupport = reportQuerySupport;
    this.agingReportService = agingReportService;
  }

  public List<AgedDebtorDto> generate(FinancialReportQueryRequest request) {
    ReportQuerySupport.FinancialQueryWindow window = reportQuerySupport.resolveWindow(request);
    LocalDate asOfDate = window.asOfDate();
    ReportMetadata metadata = reportQuerySupport.metadata(window);
    ExportHints exportHints =
        new ExportHints(metadata.pdfReady(), metadata.csvReady(), metadata.requestedExportFormat());
    boolean filterByDateRange = hasExplicitDateWindow(request);

    List<AgingReportService.DealerAgingDetail> dealerDetails =
        filterByDateRange
            ? agingReportService.getDealerAgingRows(asOfDate, window.startDate(), window.endDate())
            : agingReportService.getDealerAgingRows(asOfDate);

    return dealerDetails.stream()
        .filter(detail -> detail != null)
        .map(detail -> toDto(detail, metadata, exportHints))
        .toList();
  }

  private boolean hasExplicitDateWindow(FinancialReportQueryRequest request) {
    if (request == null) {
      return false;
    }
    return request.periodId() != null || request.startDate() != null || request.endDate() != null;
  }

  private AgedDebtorDto toDto(
      AgingReportService.DealerAgingDetail detail,
      ReportMetadata metadata,
      ExportHints exportHints) {
    AgingReportService.AgingBuckets buckets =
        detail.buckets() == null ? new AgingReportService.AgingBuckets() : detail.buckets();
    return new AgedDebtorDto(
        detail.dealerId(),
        detail.dealerCode(),
        detail.dealerName(),
        safe(buckets.current()),
        safe(buckets.days1to30()),
        safe(buckets.days31to60()),
        safe(buckets.days61to90()),
        safe(buckets.over90()),
        safe(detail.totalOutstanding()),
        metadata,
        exportHints);
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
