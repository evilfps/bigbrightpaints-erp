package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import com.bigbrightpaints.erp.core.security.PortalRoleActionMatrix;
import com.bigbrightpaints.erp.modules.admin.dto.AdminDashboardDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminDashboardService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminDashboardControllerContractTest {

  @Test
  void classLevelMapping_and_authority_are_tenant_admin_only() {
    RequestMapping mapping = AdminDashboardController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = AdminDashboardController.class.getAnnotation(PreAuthorize.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/v1/admin/dashboard");
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo(PortalRoleActionMatrix.TENANT_ADMIN_ONLY);
  }

  @Test
  void dashboard_delegates_to_service_contract() {
    AdminDashboardService service = mock(AdminDashboardService.class);
    AdminDashboardController controller = new AdminDashboardController(service);
    AdminDashboardDto dto =
        new AdminDashboardDto(
            List.of(),
            new AdminDashboardDto.ApprovalSummary(2, 1, 0, 0, 0, 1),
            new AdminDashboardDto.UserSummary(10, 8, 2, 4),
            new AdminDashboardDto.SupportSummary(1, 1, 0, 0),
            new TenantRuntimeMetricsDto(
                "TENANT-A",
                "ACTIVE",
                null,
                100,
                1000,
                25,
                8L,
                10L,
                32,
                0,
                2,
                "runtime-policy-1",
                null),
            new AdminDashboardDto.SecuritySummary(4, 99, 3));
    when(service.dashboard()).thenReturn(dto);

    ApiResponse<AdminDashboardDto> body = controller.dashboard().getBody();

    assertThat(body).isNotNull();
    assertThat(body.success()).isTrue();
    assertThat(body.data()).isEqualTo(dto);
    verify(service).dashboard();
  }
}
