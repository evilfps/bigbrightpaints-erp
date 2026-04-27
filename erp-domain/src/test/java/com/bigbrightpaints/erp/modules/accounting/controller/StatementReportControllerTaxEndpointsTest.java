package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.StatementService;
import com.bigbrightpaints.erp.modules.sales.service.SalesReturnService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class StatementReportControllerTaxEndpointsTest {

  @Test
  void generateGstReturn_trimsAndParsesPeriod() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller = controller(accountingService);
    GstReturnDto expected = new GstReturnDto();
    expected.setPeriod(YearMonth.of(2026, 3));
    when(accountingService.generateGstReturn(YearMonth.of(2026, 3))).thenReturn(expected);

    ApiResponse<GstReturnDto> body = controller.generateGstReturn(" 2026-03 ").getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(accountingService).generateGstReturn(YearMonth.of(2026, 3));
  }

  @Test
  void getGstReconciliation_allowsMissingPeriod() {
    AccountingService accountingService = mock(AccountingService.class);
    StatementReportController controller = controller(accountingService);
    GstReconciliationDto expected = new GstReconciliationDto();
    when(accountingService.getGstReconciliation(null)).thenReturn(expected);

    ApiResponse<GstReconciliationDto> body = controller.getGstReconciliation(null).getBody();

    assertThat(body).isNotNull();
    assertThat(body.data()).isSameAs(expected);
    verify(accountingService).getGstReconciliation(null);
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
