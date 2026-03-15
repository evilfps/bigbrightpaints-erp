package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class TenantRuntimePolicyServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private TenantRuntimePolicyService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new TenantRuntimePolicyService(
                companyContextService,
                userAccountRepository,
                auditService,
                tenantRuntimeEnforcementService
        );
        company = tenant(42L, "ACME");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void metrics_returnsCanonicalSnapshotIncludingDefaultsAndActiveReason() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot(
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "POLICY_ACTIVE",
                "bootstrap",
                null,
                500,
                5000,
                200,
                8,
                2,
                3,
                2L));
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false), user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.companyCode()).isEqualTo("ACME");
        assertThat(metrics.holdState()).isEqualTo("ACTIVE");
        assertThat(metrics.holdReason()).isEqualTo("POLICY_ACTIVE");
        assertThat(metrics.maxActiveUsers()).isEqualTo(500);
        assertThat(metrics.maxRequestsPerMinute()).isEqualTo(5000);
        assertThat(metrics.maxConcurrentRequests()).isEqualTo(200);
        assertThat(metrics.enabledUsers()).isEqualTo(2L);
        assertThat(metrics.totalUsers()).isEqualTo(3L);
        assertThat(metrics.requestsThisMinute()).isEqualTo(8);
        assertThat(metrics.blockedThisMinute()).isEqualTo(2);
        assertThat(metrics.inFlightRequests()).isEqualTo(3);
        assertThat(metrics.policyReference()).isEqualTo("bootstrap");
        assertThat(metrics.policyUpdatedAt()).isNull();
    }

    @Test
    void assertCanAddEnabledUser_throwsQuotaException_andAuditsFailure() {
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot(
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "POLICY_ACTIVE",
                "policy-ref-7",
                Instant.parse("2026-02-20T10:15:30Z"),
                1,
                5000,
                200,
                0,
                0,
                0,
                1L));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        request.setRemoteAddr("198.51.100.7");
        request.addHeader("X-Request-Id", "req-q-1");
        request.addHeader("X-Trace-Id", "trace-q-1");
        request.addHeader("User-Agent", "quota-check");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(() -> service.assertCanAddEnabledUser(company, "ENABLE_USER"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(throwable -> {
                    ApplicationException exception = (ApplicationException) throwable;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("operation", "ENABLE_USER")
                            .containsEntry("enabledUsers", 1L)
                            .containsEntry("maxActiveUsers", 1)
                            .containsEntry("policyReference", "policy-ref-7");
                });

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("operation", "ENABLE_USER")
                .containsEntry("enabledUsers", "1")
                .containsEntry("maxActiveUsers", "1")
                .containsEntry("policyReference", "policy-ref-7")
                .containsEntry("requestId", "req-q-1")
                .containsEntry("traceId", "trace-q-1")
                .containsEntry("ipAddress", "198.51.100.7")
                .containsEntry("userAgent", "quota-check");
    }

    @Test
    void assertCanAddEnabledUser_allowsWhenBelowQuota() {
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(snapshot(
                TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                "POLICY_ACTIVE",
                "policy-ref-8",
                Instant.parse("2026-02-20T10:15:30Z"),
                5,
                5000,
                200,
                0,
                0,
                0,
                2L));

        service.assertCanAddEnabledUser(company, "ENABLE_USER");

        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }

    @Test
    void assertCanAddEnabledUser_noopsWhenCompanyMissing() {
        service.assertCanAddEnabledUser(null, "ENABLE_USER");

        verifyNoInteractions(tenantRuntimeEnforcementService, userAccountRepository, auditService);
    }

    private Company tenant(Long id, String code) {
        Company result = new Company();
        org.springframework.test.util.ReflectionTestUtils.setField(result, "id", id);
        result.setCode(code);
        return result;
    }

    private UserAccount user(boolean enabled) {
        String email = enabled ? "enabled-user@example.com" : "disabled-user@example.com";
        UserAccount account = new UserAccount(email, "hash", "User");
        account.setEnabled(enabled);
        return account;
    }

    private TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot(
            TenantRuntimeEnforcementService.TenantRuntimeState state,
            String reasonCode,
            String auditChainId,
            Instant updatedAt,
            int maxActiveUsers,
            int maxRequestsPerMinute,
            int maxConcurrentRequests,
            int requestsThisMinute,
            int blockedThisMinute,
            int inFlightRequests,
            long activeUsers) {
        return new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                "ACME",
                state,
                reasonCode,
                auditChainId,
                updatedAt,
                maxConcurrentRequests,
                maxRequestsPerMinute,
                maxActiveUsers,
                new TenantRuntimeEnforcementService.TenantRuntimeMetrics(
                        requestsThisMinute,
                        blockedThisMinute,
                        0,
                        inFlightRequests,
                        requestsThisMinute,
                        blockedThisMinute,
                        activeUsers));
    }
}
