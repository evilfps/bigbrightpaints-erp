package com.bigbrightpaints.erp.modules.admin.controller;

import com.bigbrightpaints.erp.core.config.SystemSettingsService;
import com.bigbrightpaints.erp.core.notification.EmailService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTenantRuntimeContractTest {

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
        assertThat(response.data()).isEqualTo(updated);
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
