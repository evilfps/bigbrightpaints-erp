package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.company.controller.CompanyController;
import com.bigbrightpaints.erp.modules.company.controller.SuperAdminController;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.SuperAdminTenantControlPlaneService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeCompanyControllerExecutableCoverageTest {

  @Test
  void list_delegatesToCompanyScopedServiceForTenantPrincipal() {
    CompanyService companyService = mock(CompanyService.class);
    CompanyController controller = new CompanyController(companyService);
    Company company = new Company();
    company.setCode("ACME");
    UserAccount user = new UserAccount("admin@acme.com", "hash", "Admin");
    user.addCompany(company);
    CompanyDto dto = new CompanyDto(42L, null, "Acme", "ACME", "UTC", new BigDecimal("18.0"));
    when(companyService.findAll(Set.of(company))).thenReturn(List.of(dto));

    ResponseEntity<ApiResponse<List<CompanyDto>>> response =
        controller.list(new UserPrincipal(user));

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().data()).containsExactly(dto);
    verify(companyService).findAll(Set.of(company));
  }

  @Test
  void resetTenantAdminPassword_delegatesToCanonicalSuperadminController() {
    CompanyService companyService = mock(CompanyService.class);
    SuperAdminTenantControlPlaneService controlPlaneService =
        mock(SuperAdminTenantControlPlaneService.class);
    SuperAdminController controller = new SuperAdminController(companyService, controlPlaneService);
    CompanyAdminCredentialResetDto payload =
        new CompanyAdminCredentialResetDto(42L, "SKE", "admin@ske.com", "credentials-emailed");
    when(controlPlaneService.resetTenantAdminPassword(42L, "admin@ske.com", "support"))
        .thenReturn(payload);

    ResponseEntity<ApiResponse<CompanyAdminCredentialResetDto>> response =
        controller.resetTenantAdminPassword(
            42L,
            new SuperAdminController.TenantAdminPasswordResetRequest("admin@ske.com", "support"));

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).isEqualTo(payload);
    verify(controlPlaneService).resetTenantAdminPassword(42L, "admin@ske.com", "support");
  }
}
