package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostingMethodServiceTest {

    @Mock
    private AccountingPeriodService accountingPeriodService;

    @Mock
    private CompanyContextService companyContextService;

    @Test
    void resolveActiveMethodForCurrentCompany_usesPeriodCostingMethod() {
        Company company = new Company();
        AccountingPeriod period = new AccountingPeriod();
        period.setCostingMethod(CostingMethod.LIFO);
        LocalDate referenceDate = LocalDate.of(2026, 2, 10);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(accountingPeriodService.ensurePeriod(company, referenceDate)).thenReturn(period);

        CostingMethodService service = new CostingMethodService(accountingPeriodService, companyContextService);

        assertThat(service.resolveActiveMethodForCurrentCompany(referenceDate)).isEqualTo(CostingMethod.LIFO);
    }

    @Test
    void resolveActiveMethod_defaultsToFifoWhenPeriodValueMissing() {
        Company company = new Company();
        AccountingPeriod period = new AccountingPeriod();
        period.setCostingMethod(null);
        LocalDate referenceDate = LocalDate.of(2026, 2, 10);

        when(accountingPeriodService.ensurePeriod(company, referenceDate)).thenReturn(period);

        CostingMethodService service = new CostingMethodService(accountingPeriodService, companyContextService);

        assertThat(service.resolveActiveMethod(company, referenceDate)).isEqualTo(CostingMethod.FIFO);
    }

    @Test
    void resolveActiveMethod_defaultsToFifoWhenPeriodMissing() {
        Company company = new Company();
        LocalDate referenceDate = LocalDate.of(2026, 2, 10);

        when(accountingPeriodService.ensurePeriod(company, referenceDate)).thenReturn(null);

        CostingMethodService service = new CostingMethodService(accountingPeriodService, companyContextService);

        assertThat(service.resolveActiveMethod(company, referenceDate)).isEqualTo(CostingMethod.FIFO);
    }
}
