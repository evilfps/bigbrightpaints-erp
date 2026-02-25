package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.controller.CompanyController;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

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

    @Test
    void resetTenantAdminPassword_delegatesAndReturnsResponseEnvelope() {
        CompanyService companyService = mock(CompanyService.class);
        CompanyController controller = new CompanyController(companyService);
        CompanyController.CompanyAdminPasswordResetRequest request =
                new CompanyController.CompanyAdminPasswordResetRequest("admin@ske.com");
        CompanyAdminCredentialResetDto payload =
                new CompanyAdminCredentialResetDto(42L, "SKE", "admin@ske.com", "credentials-emailed");
        when(companyService.resetTenantAdminPassword(42L, "admin@ske.com")).thenReturn(payload);

        ResponseEntity<ApiResponse<CompanyAdminCredentialResetDto>> response =
                controller.resetTenantAdminPassword(42L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).isEqualTo(payload);
        verify(companyService).resetTenantAdminPassword(42L, "admin@ske.com");
    }
}
