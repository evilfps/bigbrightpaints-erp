package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpdateRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountingPeriodUpsertRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountingControllerPeriodCostingMethodEndpointsTest {

    @Test
    void createOrUpdatePeriod_delegatesWithCostingMethod() {
        AccountingPeriodService periodService = mock(AccountingPeriodService.class);
        AccountingController controller = controller(periodService);
        AccountingPeriodUpsertRequest request = new AccountingPeriodUpsertRequest(2026, 2, CostingMethod.LIFO);
        AccountingPeriodDto expected = dto(10L, "LIFO");
        when(periodService.createOrUpdatePeriod(request)).thenReturn(expected);

        ApiResponse<AccountingPeriodDto> body = controller.createOrUpdatePeriod(request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isEqualTo(expected);
    }

    @Test
    void updatePeriod_delegatesCostingMethodChange() {
        AccountingPeriodService periodService = mock(AccountingPeriodService.class);
        AccountingController controller = controller(periodService);
        AccountingPeriodUpdateRequest request = new AccountingPeriodUpdateRequest(CostingMethod.WEIGHTED_AVERAGE);
        AccountingPeriodDto expected = dto(11L, "WEIGHTED_AVERAGE");
        when(periodService.updatePeriod(11L, request)).thenReturn(expected);

        ApiResponse<AccountingPeriodDto> body = controller.updatePeriod(11L, request).getBody();

        assertThat(body).isNotNull();
        assertThat(body.success()).isTrue();
        assertThat(body.data()).isEqualTo(expected);
    }

    private AccountingController controller(AccountingPeriodService periodService) {
        return new AccountingController(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                periodService,
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
                costingMethod
        );
    }
}
