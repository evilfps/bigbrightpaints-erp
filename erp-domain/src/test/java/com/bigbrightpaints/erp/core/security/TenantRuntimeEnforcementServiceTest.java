package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private SystemSettingsRepository settingsRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private EnterpriseAuditTrailService enterpriseAuditTrailService;

    private final Map<String, Company> companies = new HashMap<>();
    private final Map<String, String> settings = new HashMap<>();

    private TenantRuntimeEnforcementService service;

    @BeforeEach
    void setUp() {
        service = new TenantRuntimeEnforcementService(
                companyRepository,
                settingsRepository,
                auditService,
                enterpriseAuditTrailService,
                null,
                0,
                0,
                60);
        Company acme = new Company();
        acme.setCode("ACME");
        companies.put("ACME", acme);

        when(companyRepository.findByCodeIgnoreCase(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(companies.get(code.trim().toUpperCase(Locale.ROOT)));
        });
        when(settingsRepository.findById(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            String value = settings.get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new SystemSetting(key, value));
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void holdState_blocksMutatingRequests_andWritesAuditChain() {
        settings.put("tenant.runtime.acme.state", "HOLD");
        settings.put("tenant.runtime.acme.reason-code", "REVIEW_PENDING");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales/orders");

        TenantRuntimeEnforcementService.AccessHandle accessHandle = service.acquire("ACME", request);

        assertThat(accessHandle.allowed()).isFalse();
        assertThat(accessHandle.httpStatus()).isEqualTo(423);
        assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_HOLD");
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), anyMap());
        verify(enterpriseAuditTrailService).recordBusinessEvent(any(AuditActionEventCommand.class));
    }

    @Test
    void holdState_allowsReadOnlyRequests() {
        settings.put("tenant.runtime.acme.state", "HOLD");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance");

        TenantRuntimeEnforcementService.AccessHandle accessHandle = service.acquire("ACME", request);

        assertThat(accessHandle.allowed()).isTrue();
        accessHandle.close();

        TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot snapshot = service.snapshot("ACME").orElseThrow();
        assertThat(snapshot.allowedRequests()).isEqualTo(1L);
        assertThat(snapshot.deniedRequests()).isZero();
        assertThat(snapshot.activeRequests()).isZero();
    }

    @Test
    void blockedState_blocksReadAndWriteRequests() {
        settings.put("tenant.runtime.acme.state", "BLOCKED");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        TenantRuntimeEnforcementService.AccessHandle accessHandle = service.acquire("ACME", request);

        assertThat(accessHandle.allowed()).isFalse();
        assertThat(accessHandle.httpStatus()).isEqualTo(423);
        assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
    }

    @Test
    void requestPerMinuteQuota_isEnforced() {
        settings.put("tenant.runtime.acme.quota.max-requests-per-minute", "1");

        MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        TenantRuntimeEnforcementService.AccessHandle first = service.acquire("ACME", request1);
        assertThat(first.allowed()).isTrue();
        first.close();

        TenantRuntimeEnforcementService.AccessHandle second = service.acquire("ACME", request2);
        assertThat(second.allowed()).isFalse();
        assertThat(second.httpStatus()).isEqualTo(429);
        assertThat(second.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");
    }

    @Test
    void concurrentQuota_isEnforced_andReleasedOnClose() {
        settings.put("tenant.runtime.acme.quota.max-concurrent", "1");

        MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

        TenantRuntimeEnforcementService.AccessHandle first = service.acquire("ACME", request1);
        assertThat(first.allowed()).isTrue();

        TenantRuntimeEnforcementService.AccessHandle second = service.acquire("ACME", request2);
        assertThat(second.allowed()).isFalse();
        assertThat(second.httpStatus()).isEqualTo(429);
        assertThat(second.reasonCode()).isEqualTo("TENANT_QUOTA_CONCURRENCY_LIMIT");

        first.close();
        TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot snapshot = service.snapshot("ACME").orElseThrow();
        assertThat(snapshot.activeRequests()).isZero();
    }
}
