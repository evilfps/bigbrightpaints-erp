package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.company.controller.SuperAdminController;
import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.SuperAdminTenantControlPlaneService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeCompanyControllerExecutableCoverageTest {

  @Test
  void canonicalLifecycleUpdate_delegatesToControlPlaneService() {
    CompanyService companyService = mock(CompanyService.class);
    SuperAdminTenantControlPlaneService controlPlaneService =
        mock(SuperAdminTenantControlPlaneService.class);
    SuperAdminController controller = new SuperAdminController(companyService, controlPlaneService);
    CompanyLifecycleStateRequest request =
        new CompanyLifecycleStateRequest("SUSPENDED", "reconciliation");
    CompanyLifecycleStateDto responseDto =
        new CompanyLifecycleStateDto(42L, "ACME", "ACTIVE", "SUSPENDED", "reconciliation");
    when(controlPlaneService.updateLifecycleState(42L, request)).thenReturn(responseDto);

    ResponseEntity<ApiResponse<CompanyLifecycleStateDto>> response =
        controller.updateLifecycleState(42L, request);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).isEqualTo(responseDto);
    verify(controlPlaneService).updateLifecycleState(42L, request);
  }

  @Test
  void canonicalAdminPasswordReset_delegatesAndReturnsResponseEnvelope() {
    CompanyService companyService = mock(CompanyService.class);
    SuperAdminTenantControlPlaneService controlPlaneService =
        mock(SuperAdminTenantControlPlaneService.class);
    SuperAdminController controller = new SuperAdminController(companyService, controlPlaneService);
    SuperAdminController.TenantAdminPasswordResetRequest request =
        new SuperAdminController.TenantAdminPasswordResetRequest("admin@ske.com", null);
    CompanyAdminCredentialResetDto payload =
        new CompanyAdminCredentialResetDto(42L, "SKE", "admin@ske.com", "reset-link-emailed");
    when(controlPlaneService.resetTenantAdminPassword(42L, "admin@ske.com", null))
        .thenReturn(payload);

    ResponseEntity<ApiResponse<CompanyAdminCredentialResetDto>> response =
        controller.resetTenantAdminPassword(42L, request);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).isEqualTo(payload);
    verify(controlPlaneService).resetTenantAdminPassword(42L, "admin@ske.com", null);
  }
}
