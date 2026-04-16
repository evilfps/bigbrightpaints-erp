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
import com.bigbrightpaints.erp.modules.admin.dto.AdminSelfSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.service.AdminSelfService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminSelfControllerContractTest {

  @Test
  void classLevelMapping_and_authority_are_tenant_admin_only() {
    RequestMapping mapping = AdminSelfController.class.getAnnotation(RequestMapping.class);
    PreAuthorize preAuthorize = AdminSelfController.class.getAnnotation(PreAuthorize.class);

    assertThat(mapping).isNotNull();
    assertThat(mapping.value()).containsExactly("/api/v1/admin/self");
    assertThat(preAuthorize).isNotNull();
    assertThat(preAuthorize.value()).isEqualTo(PortalRoleActionMatrix.TENANT_ADMIN_ONLY);
  }

  @Test
  void settings_delegates_to_service_contract() {
    AdminSelfService service = mock(AdminSelfService.class);
    AdminSelfController controller = new AdminSelfController(service);
    AdminSelfSettingsDto dto =
        new AdminSelfSettingsDto(
            "admin@tenant-a.test",
            "Tenant Admin",
            "TENANT-A",
            true,
            false,
            List.of("ROLE_ADMIN"),
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
            4L);
    when(service.settings()).thenReturn(dto);

    ApiResponse<AdminSelfSettingsDto> response = controller.settings().getBody();

    assertThat(response).isNotNull();
    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(dto);
    verify(service).settings();
  }
}
