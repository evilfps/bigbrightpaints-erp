package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalLineDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalListItemDto;
import com.bigbrightpaints.erp.modules.accounting.dto.ManualJournalRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnPreviewDto;
import com.bigbrightpaints.erp.modules.accounting.dto.SalesReturnRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalEntryService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AccountingControllerJournalEndpointsTest {

    @Test
    void createManualJournal_delegatesToAccountingService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        ManualJournalRequest request = new ManualJournalRequest(
                LocalDate.of(2026, 2, 28),
                "Manual adjustment",
                "manual-100",
                false,
                List.of(
                        new ManualJournalRequest.LineRequest(11L, new BigDecimal("50.00"), "Debit", ManualJournalRequest.EntryType.DEBIT),
                        new ManualJournalRequest.LineRequest(22L, new BigDecimal("50.00"), "Credit", ManualJournalRequest.EntryType.CREDIT)
                )
        );
        JournalEntryDto expected = new JournalEntryDto(
                100L,
                null,
                "JRN-100",
                LocalDate.of(2026, 2, 28),
                "Manual adjustment",
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingService.createManualJournal(request)).thenReturn(expected);

        ApiResponse<JournalEntryDto> body = controller.createManualJournal(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isEqualTo(expected);
    }

    @Test
    void listJournals_appliesFilterArguments() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        List<JournalListItemDto> expected = List.of(
                new JournalListItemDto(
                        10L,
                        "INV-10",
                        LocalDate.of(2026, 2, 27),
                        "Dispatch",
                        "POSTED",
                        "AUTOMATED",
                        "SALES",
                        "INV-10",
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00")
                )
        );
        when(accountingService.listJournals(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                "AUTOMATED",
                "SALES"
        )).thenReturn(expected);

        ApiResponse<List<JournalListItemDto>> body = controller.listJournals(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                "AUTOMATED",
                "SALES"
        ).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).containsExactlyElementsOf(expected);
    }

    @Test
    void reverseJournalEntryByJournalPath_delegatesToService() {
        AccountingService accountingService = mock(AccountingService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), null);
        JournalEntryReversalRequest request = new JournalEntryReversalRequest(
                LocalDate.of(2026, 2, 28),
                false,
                "Correction",
                "Reversal",
                false
        );
        JournalEntryDto expected = new JournalEntryDto(
                200L,
                null,
                "REV-200",
                LocalDate.of(2026, 2, 28),
                "Reversal",
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.<JournalLineDto>of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(accountingService.reverseJournalEntry(200L, request)).thenReturn(expected);

        ApiResponse<JournalEntryDto> body = controller.reverseJournalEntryByJournalPath(200L, request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(expected);
    }

    @Test
    void listSalesReturns_usesSalesReturnReferencePrefix() {
        AccountingService accountingService = mock(AccountingService.class);
        JournalEntryService journalEntryService = mock(JournalEntryService.class);
        AccountingController controller = newController(accountingService, journalEntryService, null);

        controller.listSalesReturns();

        verify(journalEntryService).listJournalEntriesByReferencePrefix("CRN-");
    }

    @Test
    void previewSalesReturn_delegatesToSalesReturnService() {
        AccountingService accountingService = mock(AccountingService.class);
        SalesReturnService salesReturnService = mock(SalesReturnService.class);
        AccountingController controller = newController(accountingService, mock(JournalEntryService.class), salesReturnService);
        SalesReturnRequest request = new SalesReturnRequest(
                10L,
                "Damaged",
                List.of(new SalesReturnRequest.ReturnLine(20L, new BigDecimal("1.00")))
        );
        SalesReturnPreviewDto preview = new SalesReturnPreviewDto(
                10L,
                "INV-10",
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                List.of()
        );
        when(salesReturnService.previewReturn(request)).thenReturn(preview);

        ApiResponse<SalesReturnPreviewDto> body = controller.previewSalesReturn(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data()).isEqualTo(preview);
    }

    private AccountingController newController(AccountingService accountingService,
                                               JournalEntryService journalEntryService,
                                               SalesReturnService salesReturnService) {
        return new AccountingController(
                accountingService,
                journalEntryService,
                null,
                null,
                null,
                null,
                null,
                null,
                salesReturnService,
                null,
                null,
                null,
                null,
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
    }
}
