package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.ReportMetadata;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
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
class AgedDebtorsReportQueryServiceTest {

    @Mock
    private ReportQuerySupport reportQuerySupport;
    @Mock
    private DealerLedgerRepository dealerLedgerRepository;

    @Test
    void generate_groupsOutstandingByAllAgingBucketsAndIncludesMetadataHints() {
        AgedDebtorsReportQueryService service = new AgedDebtorsReportQueryService(reportQuerySupport, dealerLedgerRepository);

        ReportQuerySupport.FinancialQueryWindow window = ReportFixtures.window(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 3, 31)
        );
        FinancialReportQueryRequest request = new FinancialReportQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PDF"
        );

        Company company = window.company();
        Dealer dealer = dealer(81L, "DLR-81", "Dealer 81");

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
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

        when(dealerLedgerRepository.findAllUnpaidAsOf(company, window.asOfDate())).thenReturn(List.of(
                ledger(dealer, "100.00", window.asOfDate().plusDays(5), window.startDate()),
                ledger(dealer, "200.00", window.asOfDate().minusDays(12), window.startDate()),
                ledger(dealer, "300.00", window.asOfDate().minusDays(45), window.startDate()),
                ledger(dealer, "400.00", window.asOfDate().minusDays(75), window.startDate()),
                ledger(dealer, "500.00", window.asOfDate().minusDays(120), window.startDate())
        ));

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
    }

    @Test
    void generate_withExplicitDateRangeFiltersEntriesOutsideWindow() {
        AgedDebtorsReportQueryService service = new AgedDebtorsReportQueryService(reportQuerySupport, dealerLedgerRepository);

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
                "CSV"
        );

        Company company = window.company();
        Dealer dealer = dealer(82L, "DLR-82", "Dealer 82");

        when(reportQuerySupport.resolveWindow(request)).thenReturn(window);
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

        when(dealerLedgerRepository.findAllUnpaidAsOf(company, window.asOfDate())).thenReturn(List.of(
                ledger(dealer, "50.00", window.asOfDate().minusDays(20), LocalDate.of(2026, 3, 5)),
                ledger(dealer, "70.00", window.asOfDate().minusDays(5), LocalDate.of(2026, 3, 18)),
                ledger(dealer, "300.00", window.asOfDate().minusDays(45), LocalDate.of(2026, 2, 20))
        ));

        List<AgedDebtorDto> result = service.generate(request);

        assertThat(result).hasSize(1);
        AgedDebtorDto dto = result.getFirst();
        assertThat(dto.current()).isEqualByComparingTo("0.00");
        assertThat(dto.oneToThirtyDays()).isEqualByComparingTo("120.00");
        assertThat(dto.thirtyOneToSixtyDays()).isEqualByComparingTo("0.00");
        assertThat(dto.sixtyOneToNinetyDays()).isEqualByComparingTo("0.00");
        assertThat(dto.ninetyPlusDays()).isEqualByComparingTo("0.00");
        assertThat(dto.totalOutstanding()).isEqualByComparingTo("120.00");
    }

    private Dealer dealer(Long id, String code, String name) {
        Dealer dealer = new Dealer();
        ReflectionTestUtils.setField(dealer, "id", id);
        dealer.setCode(code);
        dealer.setName(name);
        return dealer;
    }

    private DealerLedgerEntry ledger(Dealer dealer, String outstanding, LocalDate dueDate, LocalDate entryDate) {
        DealerLedgerEntry entry = new DealerLedgerEntry();
        entry.setDealer(dealer);
        entry.setEntryDate(entryDate);
        entry.setDueDate(dueDate);
        entry.setDebit(new BigDecimal(outstanding));
        entry.setCredit(BigDecimal.ZERO);
        entry.setAmountPaid(BigDecimal.ZERO);
        entry.setInvoiceNumber("INV-" + dueDate);
        entry.setPaymentStatus("UNPAID");
        return entry;
    }
}
