package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.modules.admin.dto.AdminNotifyRequest;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsDto;
import com.bigbrightpaints.erp.modules.admin.dto.SystemSettingsUpdateRequest;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimePolicyUpdateRequest;
import com.bigbrightpaints.erp.modules.admin.service.TenantRuntimePolicyService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditLimitOverrideRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTenantRuntimeContractTest {

    @Test
    void getSettings_returnsCurrentSnapshot() {
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        SystemSettingsDto snapshot = new SystemSettingsDto(
                java.util.List.of("https://admin.bigbrightpaints.com"),
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

    @Test
    void updateTenantRuntimePolicy_delegatesToService() {
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        AdminSettingsController controller = newController(tenantRuntimePolicyService);
        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                200,
                1000,
                35,
                "HOLD",
                "Fraud investigation",
                "Risk-control update"
        );
        TenantRuntimeMetricsDto updated = new TenantRuntimeMetricsDto(
                "ACME",
                "HOLD",
                "Fraud investigation",
                200,
                1000,
                35,
                12,
                15,
                0,
                0,
                0,
                "policy-ref-2",
                Instant.parse("2026-02-18T01:15:00Z")
        );
        when(tenantRuntimePolicyService.updatePolicy(request)).thenReturn(updated);

        ApiResponse<TenantRuntimeMetricsDto> response = controller.updateTenantRuntimePolicy(request);

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("Tenant runtime policy updated");
        assertThat(response.data()).isEqualTo(updated);
        verify(tenantRuntimePolicyService).updatePolicy(request);
    }

    @Test
    void updateTenantRuntimePolicy_propagatesServiceFailureContract() {
        TenantRuntimePolicyService tenantRuntimePolicyService = mock(TenantRuntimePolicyService.class);
        AdminSettingsController controller = newController(tenantRuntimePolicyService);
        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                200,
                1000,
                35,
                "HOLD",
                "Fraud investigation",
                "Risk-control update"
        );
        when(tenantRuntimePolicyService.updatePolicy(request))
                .thenThrow(new IllegalArgumentException("Unsupported holdState: PAUSED"));

        assertThatThrownBy(() -> controller.updateTenantRuntimePolicy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported holdState: PAUSED");
        verify(tenantRuntimePolicyService).updatePolicy(request);
    }

    private AdminSettingsController newController(TenantRuntimePolicyService tenantRuntimePolicyService) {
        return new AdminSettingsController(
                mock(SystemSettingsService.class),
                mock(EmailService.class),
                mock(CompanyContextService.class),
                tenantRuntimePolicyService,
                mock(CreditRequestRepository.class),
                mock(CreditLimitOverrideRequestRepository.class),
                mock(PayrollRunRepository.class)
        );
    }
}
