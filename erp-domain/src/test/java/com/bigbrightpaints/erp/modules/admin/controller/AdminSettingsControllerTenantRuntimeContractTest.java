package com.bigbrightpaints.erp.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;

class AdminSettingsControllerTenantRuntimeContractTest {

  @Test
  void updateSettings_requiresSuperAdminAuthority() throws Exception {
    Method method =
        AdminSettingsController.class.getMethod(
            "updateSettings", SystemSettingsUpdateRequest.class);

    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("hasAuthority('ROLE_SUPER_ADMIN')");
  }

  @Test
  void tenantRuntimeMetrics_reader_is_not_exposed_from_admin_settings_controller() {
    assertThat(
            Arrays.stream(AdminSettingsController.class.getDeclaredMethods()).map(Method::getName))
        .doesNotContain("tenantRuntimeMetrics");
  }

  @Test
  void tenantRuntimePolicy_writer_is_not_exposed_from_admin_settings_controller() {
    assertThat(
            Arrays.stream(AdminSettingsController.class.getDeclaredMethods()).map(Method::getName))
        .doesNotContain("updateTenantRuntimePolicy");
  }

  @Test
  void getSettings_returnsCurrentSnapshot() {
    SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
    SystemSettingsDto snapshot =
        new SystemSettingsDto(
            java.util.List.of("https://admin.bigbrightpaints.com"),
            true,
            true,
            true,
            "PLATFORM",
            true,
            "ops@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            false,
            true);
    when(systemSettingsService.snapshot()).thenReturn(snapshot);

    AdminSettingsController controller = new AdminSettingsController(systemSettingsService, null);

    ApiResponse<SystemSettingsDto> response = controller.getSettings();

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("Settings fetched");
    assertThat(response.data()).isEqualTo(snapshot);
    verify(systemSettingsService).snapshot();
  }

  @Test
  void updateSettings_delegatesToSettingsService() {
    SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
    SystemSettingsUpdateRequest request =
        new SystemSettingsUpdateRequest(
            java.util.List.of("https://portal.bigbrightpaints.com"),
            false,
            true,
            true,
            "PLATFORM",
            true,
            "noreply@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            true,
            false);
    SystemSettingsDto updated =
        new SystemSettingsDto(
            java.util.List.of("https://portal.bigbrightpaints.com"),
            false,
            true,
            true,
            "PLATFORM",
            true,
            "noreply@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            true,
            false);
    when(systemSettingsService.update(request)).thenReturn(updated);

    AdminSettingsController controller = new AdminSettingsController(systemSettingsService, null);

    ApiResponse<SystemSettingsDto> response = controller.updateSettings(request);

    assertThat(response.success()).isTrue();
    assertThat(response.message()).isEqualTo("Settings updated");
    assertThat(response.data()).isEqualTo(updated);
    verify(systemSettingsService).update(request);
  }

  @Test
  void updateSettings_audits_requested_fields_with_explicit_placeholders() {
    SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
    AuditService auditService = mock(AuditService.class);
    SystemSettingsDto before =
        new SystemSettingsDto(
            java.util.List.of("https://portal.bigbrightpaints.com"),
            true,
            false,
            true,
            "PLATFORM",
            true,
            "ops@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            false,
            true);
    SystemSettingsUpdateRequest request =
        new SystemSettingsUpdateRequest(
            java.util.List.of("https://portal.bigbrightpaints.com"),
            null,
            null,
            false,
            "plat\nform",
            true,
            "noreply@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            true,
            false);
    SystemSettingsDto after =
        new SystemSettingsDto(
            java.util.List.of("https://portal.bigbrightpaints.com"),
            true,
            false,
            false,
            "PLATFORM",
            true,
            "noreply@bigbrightpaints.com",
            "https://mail.bigbrightpaints.com",
            true,
            false);
    when(systemSettingsService.snapshot()).thenReturn(before);
    when(systemSettingsService.update(request)).thenReturn(after);

    AdminSettingsController controller =
        new AdminSettingsController(systemSettingsService, auditService);

    controller.updateSettings(request);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logAuthSuccess(
            org.mockito.ArgumentMatchers.eq(AuditEvent.CONFIGURATION_CHANGED),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertThat(metadata.get("requestedAutoApprovalEnabled")).isEqualTo("<not_requested>");
    assertThat(metadata.get("requestedPeriodLockEnforced")).isEqualTo("<not_requested>");
    assertThat(metadata.get("requestedExportApprovalRequired")).isEqualTo("false");
    assertThat(metadata.get("requestedPlatformAuthCode")).isEqualTo("<redacted>");
  }
}
