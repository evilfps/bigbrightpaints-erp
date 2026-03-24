package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.TemporalBalanceService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountingControllerActivityContractTest {

    @Test
    void getAccountActivity_acceptsFromToAliases() {
        TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        AccountingController controller = controller(temporalBalanceService, companyContextService, companyClock);

        TemporalBalanceService.AccountActivityReport report = new TemporalBalanceService.AccountActivityReport(
                "CASH",
                "Cash",
                LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 2, 10),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()
        );
        when(temporalBalanceService.getAccountActivity(
                1L,
                LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 2, 10)
        )).thenReturn(report);

        ResponseEntity<ApiResponse<TemporalBalanceService.AccountActivityReport>> response =
                controller.getAccountActivity(1L, null, null, "2026-02-09", "2026-02-10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(report);
        verify(temporalBalanceService).getAccountActivity(
                1L,
                LocalDate.of(2026, 2, 9),
                LocalDate.of(2026, 2, 10)
        );
    }

    @Test
    void getAccountActivity_rejectsMissingDateParameters() {
        AccountingController controller = controller(null, null, null);

        assertThatThrownBy(() -> controller.getAccountActivity(1L, null, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("requires startDate/endDate (or from/to)");
    }

    @Test
    void getAccountingDateContext_returnsCompanyClockContext() {
        TemporalBalanceService temporalBalanceService = mock(TemporalBalanceService.class);
        CompanyContextService companyContextService = mock(CompanyContextService.class);
        CompanyClock companyClock = mock(CompanyClock.class);
        AccountingController controller = controller(temporalBalanceService, companyContextService, companyClock);

        Company company = new Company();
        company.setCode("BBP");
        company.setTimezone("Asia/Kolkata");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 10));
        when(companyClock.now(company)).thenReturn(Instant.parse("2026-02-10T12:00:00Z"));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getAccountingDateContext();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).containsEntry("companyCode", "BBP");
        assertThat(response.getBody().data()).containsEntry("timezone", "Asia/Kolkata");
        assertThat(response.getBody().data()).containsEntry("today", LocalDate.of(2026, 2, 10));
    }

    private AccountingController controller(TemporalBalanceService temporalBalanceService,
                                           CompanyContextService companyContextService,
                                           CompanyClock companyClock) {
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
                null,
                null,
                null,
                null,
                temporalBalanceService,
                null,
                null,
                null,
                null,
                companyContextService,
                companyClock,
                null,
                null
        );
    }
}
