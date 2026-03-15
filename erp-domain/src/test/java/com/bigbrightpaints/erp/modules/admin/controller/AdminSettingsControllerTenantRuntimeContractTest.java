package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminNotifyRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.service.ExportApprovalService;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.util.Arrays;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTenantRuntimeContractTest {

    @Test
    void updateSettings_requiresSuperAdminAuthority() throws Exception {
        Method method = AdminSettingsController.class.getMethod("updateSettings", SystemSettingsUpdateRequest.class);

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('ROLE_SUPER_ADMIN')");
    }

    @Test
    void tenantRuntimeMetrics_remainsTenantAdminReadable() throws Exception {
        Method method = AdminSettingsController.class.getMethod("tenantRuntimeMetrics");

        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
    }

    @Test
    void tenantRuntimePolicy_writer_is_not_exposed_from_admin_settings_controller() {
        assertThat(Arrays.stream(AdminSettingsController.class.getDeclaredMethods())
                .map(Method::getName))
                .doesNotContain("updateTenantRuntimePolicy");
    }

    @Test
    void getSettings_returnsCurrentSnapshot() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        SystemSettingsDto snapshot = new SystemSettingsDto(
                java.util.List.of("https://admin.bigbrightpaints.com"),
                true,
                true,
                true,
                true,
                "ops@bigbrightpaints.com",
                "https://mail.bigbrightpaints.com",
                false,
                true
        );
        when(systemSettingsService.snapshot()).thenReturn(snapshot);

        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                mock(EmailService.class),
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PayrollRunRepository.class)
        );

        ApiResponse<SystemSettingsDto> response = controller.getSettings();

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Settings fetched");
        assertThat(response.data()).isEqualTo(snapshot);
        verify(systemSettingsService).snapshot();
    }

    @Test
    void updateSettings_delegatesToSettingsService() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        SystemSettingsUpdateRequest request = new SystemSettingsUpdateRequest(
                java.util.List.of("https://portal.bigbrightpaints.com"),
                false,
                true,
                true,
                true,
                "noreply@bigbrightpaints.com",
                "https://mail.bigbrightpaints.com",
                true,
                false
        );
        SystemSettingsDto updated = new SystemSettingsDto(
                java.util.List.of("https://portal.bigbrightpaints.com"),
                false,
                true,
                true,
                true,
                "noreply@bigbrightpaints.com",
                "https://mail.bigbrightpaints.com",
                true,
                false
        );
        when(systemSettingsService.update(request)).thenReturn(updated);

        AdminSettingsController controller = new AdminSettingsController(
                systemSettingsService,
                mock(EmailService.class),
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PayrollRunRepository.class)
        );

        ApiResponse<SystemSettingsDto> response = controller.updateSettings(request);

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Settings updated");
        assertThat(response.data()).isEqualTo(updated);
        verify(systemSettingsService).update(request);
    }

    @Test
    void notifyUser_dispatchesEmailAndReturnsSuccessContract() {
        EmailService emailService = mock(EmailService.class);
        AdminSettingsController controller = new AdminSettingsController(
                mock(SystemSettingsService.class),
                emailService,
                mock(CompanyContextService.class),
                mock(TenantRuntimePolicyService.class),
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PayrollRunRepository.class)
        );
        AdminNotifyRequest request = new AdminNotifyRequest(
                "admin.user@bigbrightpaints.com",
                "Tenant runtime maintenance",
                "Maintenance window starts at 23:00 UTC"
        );

        ApiResponse<String> response = controller.notifyUser(request);

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Notification sent");
        assertThat(response.data()).isEqualTo("Email dispatched");
        verify(emailService).sendSimpleEmail(
                "admin.user@bigbrightpaints.com",
                "Tenant runtime maintenance",
                "Maintenance window starts at 23:00 UTC"
        );
    }

    @Test
    void tenantRuntimeMetrics_returnsPolicyAndUsageSnapshot() {
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        AdminSettingsController controller = newController(tenantRuntimePolicyService);
        TenantRuntimeMetricsDto snapshot = new TenantRuntimeMetricsDto(
                "ACME",
                "ACTIVE",
                null,
                250,
                1200,
                40,
                12,
                15,
                89,
                1,
                3,
                "policy-ref-1",
                Instant.parse("2026-02-18T00:00:00Z")
        );
        when(tenantRuntimePolicyService.metrics()).thenReturn(snapshot);

        ApiResponse<TenantRuntimeMetricsDto> response = controller.tenantRuntimeMetrics();

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Tenant runtime metrics");
        assertThat(response.data()).isEqualTo(snapshot);
        verify(tenantRuntimePolicyService).metrics();
    }

    private AdminSettingsController newController(TenantRuntimePolicyService tenantRuntimePolicyService) {
        return new AdminSettingsController(
                mock(SystemSettingsService.class),
                mock(EmailService.class),
                mock(CompanyContextService.class),
                tenantRuntimePolicyService,
                mock(ExportApprovalService.class),
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PayrollRunRepository.class)
        );
    }
}
