package com.bigbrightpaints.erp.modules.reports.controller;

import com.bigbrightpaints.erp.modules.reports.dto.AccountStatementEntryDto;
import com.bigbrightpaints.erp.modules.reports.dto.TrialBalanceDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerContractTest {

    @Test
    void trialBalance_withDateRangeAndComparativeParametersDelegatesToQueryRequestBuilder() {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = new ReportController(reportService);
        TrialBalanceDto expected = new TrialBalanceDto(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, true, null, null);
        when(reportService.trialBalance(any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class))).thenReturn(expected);

        ResponseEntity<ApiResponse<TrialBalanceDto>> response = controller.trialBalance(
                null,
                100L,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28),
                99L,
                "CSV"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(expected);
        verify(reportService).trialBalance(any(com.bigbrightpaints.erp.modules.reports.service.FinancialReportQueryRequest.class));
    }

    @Test
    void agedDebtorsV2_usesNewReportsPathAndDelegatesToReportServiceWithQueryRequest() {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = new ReportController(reportService);
        when(reportService.agedDebtors(any())).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto>>> response = controller.agedDebtorsV2(
                77L,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                "PDF"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        verify(reportService).agedDebtors(any());
    }

    @Test
    void accountStatement_serializesJournalEntryIdInApiResponse() throws Exception {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = new ReportController(reportService);
        when(reportService.accountStatement()).thenReturn(List.of(
                new AccountStatementEntryDto(
                        "Dealer Trace",
                        LocalDate.of(2026, 2, 12),
                        "INV-9001",
                        new BigDecimal("300.00"),
                        new BigDecimal("25.00"),
                        new BigDecimal("275.00"),
                        9001L
                )
        ));

        ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> response = controller.accountStatement();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().get(0).journalEntryId()).isEqualTo(9001L);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(response.getBody());
        assertThat(json).contains("\"journalEntryId\":9001");
    }

    @Test
    void accountStatement_serializesNullJournalEntryIdInApiResponse() throws Exception {
        ReportService reportService = mock(ReportService.class);
        ReportController controller = new ReportController(reportService);
        when(reportService.accountStatement()).thenReturn(List.of(
                new AccountStatementEntryDto(
                        "Dealer No Journal",
                        LocalDate.of(2026, 2, 12),
                        "BALANCE",
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        null
                )
        ));

        ResponseEntity<ApiResponse<List<AccountStatementEntryDto>>> response = controller.accountStatement();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().get(0).journalEntryId()).isNull();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(response.getBody());
        assertThat(json).contains("\"journalEntryId\":null");
    }
}
