package com.bigbrightpaints.erp.modules.reports.controller;

import com.bigbrightpaints.erp.modules.reports.dto.AccountStatementEntryDto;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportControllerContractTest {

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
}
