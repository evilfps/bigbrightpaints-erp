package com.bigbrightpaints.erp.modules.company.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeCompanyControllerExecutableCoverageTest {

    @Test
    void update_delegatesToServiceWithEmptyAllowedCompanies() {
        CompanyService companyService = mock(CompanyService.class);
        CompanyController controller = new CompanyController(companyService);
        CompanyRequest request = new CompanyRequest("Acme", "ACME", "UTC", new BigDecimal("18.0"));
        CompanyDto responseDto = new CompanyDto(42L, null, "Acme", "ACME", "UTC", new BigDecimal("18.0"));
        when(companyService.update(eq(42L), eq(request), anySet())).thenReturn(responseDto);

        controller.update(42L, request);

        ArgumentCaptor<Set<Company>> allowedCompaniesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(companyService).update(eq(42L), eq(request), allowedCompaniesCaptor.capture());
        assertThat(allowedCompaniesCaptor.getValue()).isEmpty();
    }
}
