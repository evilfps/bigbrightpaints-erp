package com.bigbrightpaints.erp.modules.company.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyEnabledModulesDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanySuperAdminDashboardDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanySupportWarningDto;
import com.bigbrightpaints.erp.modules.company.dto.MainAdminSummaryDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantAdminEmailChangeConfirmationDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantAdminEmailChangeRequestDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantDetailDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantForceLogoutDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantLimitsDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantSummaryDto;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantSupportContextDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.bigbrightpaints.erp.modules.company.service.SuperAdminTenantControlPlaneService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

@ExtendWith(MockitoExtension.class)
class SuperAdminControllerTest {

  @Mock private CompanyService companyService;
  @Mock private SuperAdminTenantControlPlaneService controlPlaneService;

  private SuperAdminController controller;

  @BeforeEach
  void setUp() {
    controller = new SuperAdminController(companyService, controlPlaneService);
  }

  @Test
  void routes_delegateToServicesAcrossCanonicalSuperadminSurface() {
    when(companyService.getSuperAdminDashboard())
        .thenReturn(
            new CompanySuperAdminDashboardDto(
                1,
                1,
                0,
                0,
                2,
                10,
                200,
                400,
                1,
                4,
                List.of(
                    new CompanySuperAdminDashboardDto.TenantOverview(
                        7L, "ACME", "Acme", "KA", "ACTIVE", null, 2, 10, 200, 400, 1, 4, 40, 2000,
                        1, 250, true, false, 2000, 5000, 2500))));
    when(controlPlaneService.listTenants("ACTIVE"))
        .thenReturn(
            List.of(
                new SuperAdminTenantSummaryDto(
                    7L,
                    "ACME",
                    "Acme",
                    "UTC",
                    "ACTIVE",
                    null,
                    12,
                    120,
                    400,
                    2000,
                    2048,
                    4096,
                    3,
                    8,
                    Set.of("ACCOUNTING"),
                    new MainAdminSummaryDto(91L, "admin@acme.com", "Main Admin", true, true),
                    Instant.parse("2026-03-26T11:00:00Z"))));
    SuperAdminTenantDetailDto detail =
        new SuperAdminTenantDetailDto(
            7L,
            "ACME",
            "Acme",
            "UTC",
            "KA",
            "ACTIVE",
            null,
            Set.of("ACCOUNTING"),
            new SuperAdminTenantDetailDto.Onboarding(
                "SME",
                "admin@acme.com",
                91L,
                true,
                true,
                Instant.parse("2026-03-26T09:00:00Z"),
                Instant.parse("2026-03-26T09:30:00Z")),
            new MainAdminSummaryDto(91L, "admin@acme.com", "Main Admin", true, true),
            new SuperAdminTenantDetailDto.Limits(10, 20, 30, 4, true, false),
            new SuperAdminTenantDetailDto.Usage(
                2, 40, 1, 250, 200, 1, Instant.parse("2026-03-26T11:00:00Z")),
            new SuperAdminTenantDetailDto.SupportContext("note", Set.of("URGENT")),
            List.of(
                new SuperAdminTenantDetailDto.SupportTimelineEvent(
                    "WARNING",
                    "FINANCE",
                    "Check payment",
                    "ops@bbp.com",
                    Instant.parse("2026-03-26T08:00:00Z"))),
            new SuperAdminTenantDetailDto.AvailableActions(
                true, true, true, true, true, true, true, true));
    when(controlPlaneService.getTenantDetail(7L)).thenReturn(detail);
    when(controlPlaneService.updateLifecycleState(
            7L,
            new com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest(
                "ACTIVE", "ok")))
        .thenReturn(new CompanyLifecycleStateDto(7L, "ACME", "SUSPENDED", "ACTIVE", "ok"));
    when(controlPlaneService.updateLimits(7L, 10L, 20L, 30L, 4L, true, false))
        .thenReturn(new SuperAdminTenantLimitsDto(7L, "ACME", 10, 20, 30, 4, true, false));
    when(controlPlaneService.updateModules(7L, Set.of("ACCOUNTING", "SALES")))
        .thenReturn(new CompanyEnabledModulesDto(7L, "ACME", Set.of("ACCOUNTING", "SALES")));
    when(controlPlaneService.issueSupportWarning(7L, "OPS", "Check", "SUSPENDED", 24))
        .thenReturn(
            new CompanySupportWarningDto(
                7L,
                "ACME",
                "55",
                "OPS",
                "Check",
                "SUSPENDED",
                24,
                "super-admin@bbp.com",
                Instant.parse("2026-03-26T12:30:00Z")));
    when(controlPlaneService.resetTenantAdminPassword(7L, "admin@acme.com", "support"))
        .thenReturn(new CompanyAdminCredentialResetDto(7L, "ACME", "admin@acme.com", "EMAIL_SENT"));
    when(controlPlaneService.updateSupportContext(7L, "note", Set.of("OPS")))
        .thenReturn(new SuperAdminTenantSupportContextDto(7L, "ACME", "note", Set.of("OPS")));
    when(controlPlaneService.forceLogoutAllUsers(7L, "security"))
        .thenReturn(
            new SuperAdminTenantForceLogoutDto(
                7L,
                "ACME",
                3,
                "security",
                "super-admin@bbp.com",
                Instant.parse("2026-03-26T13:30:00Z")));
    when(controlPlaneService.replaceMainAdmin(7L, 91L))
        .thenReturn(new MainAdminSummaryDto(91L, "admin@acme.com", "Main Admin", true, true));
    when(controlPlaneService.requestAdminEmailChange(7L, 91L, "new-admin@acme.com"))
        .thenReturn(
            new SuperAdminTenantAdminEmailChangeRequestDto(
                301L,
                7L,
                "ACME",
                91L,
                "admin@acme.com",
                "new-admin@acme.com",
                Instant.parse("2026-03-26T13:40:00Z"),
                Instant.parse("2026-03-27T13:40:00Z")));
    when(controlPlaneService.confirmAdminEmailChange(7L, 91L, 301L, "verify-123"))
        .thenReturn(
            new SuperAdminTenantAdminEmailChangeConfirmationDto(
                301L,
                7L,
                "ACME",
                91L,
                "new-admin@acme.com",
                Instant.parse("2026-03-26T14:00:00Z"),
                Instant.parse("2026-03-26T14:00:00Z")));

    assertSuccess(controller.dashboard().getBody(), "Superadmin dashboard fetched");
    assertSuccess(controller.listTenants("ACTIVE").getBody(), "Superadmin tenant list fetched");
    assertThat(controller.getTenantDetail(7L).getBody().data()).isEqualTo(detail);
    assertSuccess(
        controller
            .updateLifecycleState(
                7L,
                new com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest(
                    "ACTIVE", "ok"))
            .getBody(),
        "Tenant lifecycle state updated");
    assertSuccess(
        controller
            .updateTenantLimits(
                7L,
                new SuperAdminController.TenantLimitsUpdateRequest(10L, 20L, 30L, 4L, true, false))
            .getBody(),
        "Tenant limits updated");
    assertSuccess(
        controller
            .updateTenantModules(
                7L,
                new SuperAdminController.TenantModulesUpdateRequest(Set.of("ACCOUNTING", "SALES")))
            .getBody(),
        "Tenant modules updated");
    assertSuccess(
        controller
            .issueSupportWarning(
                7L,
                new SuperAdminController.TenantSupportWarningRequest(
                    "OPS", "Check", "SUSPENDED", 24))
            .getBody(),
        "Tenant warning issued");
    assertSuccess(
        controller
            .resetTenantAdminPassword(
                7L,
                new SuperAdminController.TenantAdminPasswordResetRequest(
                    "admin@acme.com", "support"))
            .getBody(),
        "Admin credentials reset and emailed");
    assertSuccess(
        controller
            .updateSupportContext(
                7L,
                new SuperAdminController.TenantSupportContextUpdateRequest("note", Set.of("OPS")))
            .getBody(),
        "Tenant support context updated");
    assertSuccess(
        controller
            .forceLogout(7L, new SuperAdminController.TenantForceLogoutRequest("security"))
            .getBody(),
        "Tenant sessions revoked");
    assertSuccess(
        controller
            .replaceMainAdmin(7L, new SuperAdminController.TenantMainAdminUpdateRequest(91L))
            .getBody(),
        "Tenant main admin replaced");
    assertSuccess(
        controller
            .requestAdminEmailChange(
                7L,
                91L,
                new SuperAdminController.TenantAdminEmailChangeRequest("new-admin@acme.com"))
            .getBody(),
        "Tenant admin email change requested");
    assertSuccess(
        controller
            .confirmAdminEmailChange(
                7L,
                91L,
                new SuperAdminController.TenantAdminEmailChangeConfirmRequest(301L, "verify-123"))
            .getBody(),
        "Tenant admin email change confirmed");
  }

  private void assertSuccess(ApiResponse<?> response, String message) {
    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo(message);
    assertThat(response.data()).isNotNull();
  }
}
