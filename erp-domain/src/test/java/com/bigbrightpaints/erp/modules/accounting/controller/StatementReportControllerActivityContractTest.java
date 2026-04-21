package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class StatementReportControllerActivityContractTest {

  @Test
  void getAccountActivity_acceptsFromToAliases() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller = controller(accountingService);

    TemporalBalanceService.AccountActivityReport report =
        new TemporalBalanceService.AccountActivityReport(
            "CASH",
            "Cash",
            LocalDate.of(2026, 2, 9),
            LocalDate.of(2026, 2, 10),
            new BigDecimal("80.00"),
            new BigDecimal("100.00"),
            new BigDecimal("10.00"),
            new BigDecimal("30.00"),
            new BigDecimal("20.00"),
            List.of());
    when(accountingService.getAccountActivity(
            1L, LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 10)))
        .thenReturn(report);

    ResponseEntity<ApiResponse<StatementReportControllerSupport.AccountActivitySummaryResponse>>
        response = controller.getAccountActivity(1L, null, null, "2026-02-09", "2026-02-10");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data().accountCode()).isEqualTo("CASH");
    assertThat(response.getBody().data().totalDebits()).isEqualByComparingTo("10.00");
    assertThat(response.getBody().data().totalCredits()).isEqualByComparingTo("30.00");
    assertThat(response.getBody().data().netMovement()).isEqualByComparingTo("20.00");
    assertThat(response.getBody().data().transactionCount()).isEqualTo(0);
    verify(accountingService)
        .getAccountActivity(1L, LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 10));
  }

  @Test
  void getAccountActivity_rejectsMissingDateParameters() {
    StatementReportController controller = controller(mock(AccountingService.class));

    assertThatThrownBy(() -> controller.getAccountActivity(1L, null, null, null, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("requires startDate/endDate (or from/to)");
  }

  @Test
  void getAccountingDateContext_returnsCompanyClockContext() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller = controller(accountingService);
    when(accountingService.getAccountingDateContext())
        .thenReturn(
            Map.of(
                "companyCode", "BBP",
                "timezone", "Asia/Kolkata",
                "today", LocalDate.of(2026, 2, 10)));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.getAccountingDateContext();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).containsEntry("companyCode", "BBP");
    assertThat(response.getBody().data()).containsEntry("timezone", "Asia/Kolkata");
    assertThat(response.getBody().data()).containsEntry("today", LocalDate.of(2026, 2, 10));
  }

  private StatementReportController controller(AccountingService accountingService) {
    return new StatementReportController(
        new StatementReportControllerSupport(
            accountingService,
            mock(SalesReturnService.class),
            mock(StatementService.class),
            mock(AuditService.class)));
  }
}
