package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.ExportHints;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class AgedDebtorsReportQueryService {

    private final ReportQuerySupport reportQuerySupport;
    private final DealerLedgerRepository dealerLedgerRepository;

    public AgedDebtorsReportQueryService(ReportQuerySupport reportQuerySupport,
                                         DealerLedgerRepository dealerLedgerRepository) {
        this.reportQuerySupport = reportQuerySupport;
        this.dealerLedgerRepository = dealerLedgerRepository;
    }

    public List<AgedDebtorDto> generate(FinancialReportQueryRequest request) {
        ReportQuerySupport.FinancialQueryWindow window = reportQuerySupport.resolveWindow(request);
        LocalDate asOfDate = window.asOfDate();
        ReportMetadata metadata = reportQuerySupport.metadata(window);
        ExportHints exportHints = new ExportHints(
                metadata.pdfReady(),
                metadata.csvReady(),
                metadata.requestedExportFormat()
        );
        boolean filterByDateRange = hasExplicitDateWindow(request);

        List<DealerLedgerEntry> unpaidEntries = dealerLedgerRepository.findAllUnpaidAsOf(window.company(), asOfDate);
        Map<Long, Bucket> bucketsByDealer = new LinkedHashMap<>();

        for (DealerLedgerEntry entry : unpaidEntries) {
            if (filterByDateRange && !withinWindow(entry.getEntryDate(), window.startDate(), window.endDate())) {
                continue;
            }
            Dealer dealer = entry.getDealer();
            if (dealer == null || dealer.getId() == null) {
                continue;
            }
            BigDecimal outstanding = safe(entry.getOutstandingAmount());
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            Bucket bucket = bucketsByDealer.computeIfAbsent(dealer.getId(), ignored -> new Bucket(dealer));
            long daysPastDue = calculateDaysPastDue(entry.getDueDate(), asOfDate);
            bucket.allocate(daysPastDue, outstanding);
        }

        return bucketsByDealer.values().stream()
                .map(bucket -> bucket.toDto(metadata, exportHints))
                .toList();
    }

    private boolean hasExplicitDateWindow(FinancialReportQueryRequest request) {
        if (request == null) {
            return false;
        }
        return request.periodId() != null || request.startDate() != null || request.endDate() != null;
    }

    private boolean withinWindow(LocalDate date, LocalDate startDate, LocalDate endDate) {
        if (date == null || startDate == null || endDate == null) {
            return false;
        }
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private long calculateDaysPastDue(LocalDate dueDate, LocalDate asOfDate) {
        if (dueDate == null || !asOfDate.isAfter(dueDate)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(dueDate, asOfDate);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static class Bucket {
        private final Dealer dealer;
        private BigDecimal current = BigDecimal.ZERO;
        private BigDecimal oneToThirty = BigDecimal.ZERO;
        private BigDecimal thirtyOneToSixty = BigDecimal.ZERO;
        private BigDecimal sixtyOneToNinety = BigDecimal.ZERO;
        private BigDecimal ninetyPlus = BigDecimal.ZERO;

        private Bucket(Dealer dealer) {
            this.dealer = dealer;
        }

        private void allocate(long daysPastDue, BigDecimal amount) {
            if (daysPastDue <= 0) {
                current = current.add(amount);
            } else if (daysPastDue <= 30) {
                oneToThirty = oneToThirty.add(amount);
            } else if (daysPastDue <= 60) {
                thirtyOneToSixty = thirtyOneToSixty.add(amount);
            } else if (daysPastDue <= 90) {
                sixtyOneToNinety = sixtyOneToNinety.add(amount);
            } else {
                ninetyPlus = ninetyPlus.add(amount);
            }
        }

        private AgedDebtorDto toDto(ReportMetadata metadata, ExportHints exportHints) {
            BigDecimal total = current
                    .add(oneToThirty)
                    .add(thirtyOneToSixty)
                    .add(sixtyOneToNinety)
                    .add(ninetyPlus);
            return new AgedDebtorDto(
                    dealer.getId(),
                    dealer.getCode(),
                    dealer.getName(),
                    current,
                    oneToThirty,
                    thirtyOneToSixty,
                    sixtyOneToNinety,
                    ninetyPlus,
                    total,
                    metadata,
                    exportHints
            );
        }
    }
}
