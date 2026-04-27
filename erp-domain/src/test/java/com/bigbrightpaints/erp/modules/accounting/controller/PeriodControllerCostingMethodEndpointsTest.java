package com.bigbrightpaints.erp.modules.accounting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class PeriodControllerCostingMethodEndpointsTest {

  @Test
  void createOrUpdatePeriod_delegatesWithDateAndCostingContract() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    AccountingPeriodRequest request =
        new AccountingPeriodRequest(
            2026, 2, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), CostingMethod.LIFO);
    AccountingPeriodDto expected = dto(10L, "LIFO");
    when(periodService.createOrUpdatePeriod(request)).thenReturn(expected);

    ApiResponse<AccountingPeriodDto> body = controller.createOrUpdatePeriod(request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(expected);
  }

  @Test
  void updatePeriod_delegatesDateUpdateForExplicitPeriodPath() {
    AccountingPeriodService periodService = mock(AccountingPeriodService.class);
    PeriodController controller = controller(periodService);
    AccountingPeriodRequest request =
        new AccountingPeriodRequest(
            2026,
            2,
            LocalDate.of(2026, 2, 2),
            LocalDate.of(2026, 2, 27),
            CostingMethod.WEIGHTED_AVERAGE);
    AccountingPeriodDto expected = dto(11L, "WEIGHTED_AVERAGE");
    when(periodService.updatePeriod(11L, request)).thenReturn(expected);

    ApiResponse<AccountingPeriodDto> body = controller.updatePeriod(11L, request).getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(expected);
  }

  private PeriodController controller(AccountingPeriodService periodService) {
    return new PeriodController(periodService);
  }

  private AccountingPeriodDto dto(Long id, String costingMethod) {
    return new AccountingPeriodDto(
        id,
        2026,
        2,
        LocalDate.of(2026, 2, 1),
        LocalDate.of(2026, 2, 28),
        "February 2026",
        "OPEN",
        false,
        null,
        null,
        false,
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
        null,
        costingMethod);
  }
}
