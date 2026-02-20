package com.bigbrightpaints.erp.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

        lenient().when(companyRepository.findByCodeIgnoreCase(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(companies.get(code.trim().toUpperCase(Locale.ROOT)));
        });
        lenient().when(settingsRepository.findById(any())).thenAnswer(invocation -> {
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
        CompanyContextHolder.clear();
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

    @Test
    void acquire_failsClosed_whenCompanyContextMissing() {
        TenantRuntimeEnforcementService.AccessHandle accessHandle =
                service.acquire("   ", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

        assertThat(accessHandle.allowed()).isFalse();
        assertThat(accessHandle.httpStatus()).isEqualTo(403);
        assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_CONTEXT_MISSING");
        verifyNoInteractions(companyRepository, settingsRepository, auditService, enterpriseAuditTrailService);
    }

    @Test
    void acquire_failsClosed_whenCompanyDoesNotExist() {
        TenantRuntimeEnforcementService.AccessHandle accessHandle =
                service.acquire("NO_SUCH_CO", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

        assertThat(accessHandle.allowed()).isFalse();
        assertThat(accessHandle.httpStatus()).isEqualTo(403);
        assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_NOT_FOUND");
        verify(companyRepository).findByCodeIgnoreCase("NO_SUCH_CO");
        verifyNoInteractions(auditService, enterpriseAuditTrailService);
    }

    @Test
    void snapshotAndSnapshotAll_returnEmptyUntilTenantObserved() {
        assertThat(service.snapshot(null)).isEmpty();
        assertThat(service.snapshot("ACME")).isEmpty();
        assertThat(service.snapshotAll()).isEmpty();

        TenantRuntimeEnforcementService.AccessHandle allowed =
                service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/portal/dashboard"));
        assertThat(allowed.allowed()).isTrue();
        allowed.close();

        assertThat(service.snapshotAll()).containsKey("acme");
        assertThat(service.snapshot("ACME")).isPresent();
    }

    @Test
    void evictPolicyCache_forcesPolicyReloadOnNextRequest() {
        settings.put("tenant.runtime.acme.state", "HOLD");

        TenantRuntimeEnforcementService.AccessHandle deniedWhileCached =
                service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
        assertThat(deniedWhileCached.allowed()).isFalse();
        assertThat(deniedWhileCached.reasonCode()).isEqualTo("TENANT_HOLD");

        settings.put("tenant.runtime.acme.state", "ACTIVE");
        TenantRuntimeEnforcementService.AccessHandle stillDeniedFromCache =
                service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
        assertThat(stillDeniedFromCache.allowed()).isFalse();
        assertThat(stillDeniedFromCache.reasonCode()).isEqualTo("TENANT_HOLD");

        service.evictPolicyCache(" ACME ");
        TenantRuntimeEnforcementService.AccessHandle allowedAfterEviction =
                service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
        assertThat(allowedAfterEviction.allowed()).isTrue();
        allowedAfterEviction.close();
    }

    @Test
    void denyPath_continuesWhenLegacyAuditWriteFails_andRestoresContext() {
        CompanyContextHolder.setCompanyCode("PREVIOUS_COMPANY");
        settings.put("tenant.runtime.acme.state", "BLOCKED");
        doThrow(new RuntimeException("legacy-audit-down"))
                .when(auditService)
                .logFailure(eq(AuditEvent.ACCESS_DENIED), anyMap());

        TenantRuntimeEnforcementService.AccessHandle denied =
                service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasonCode()).isEqualTo("TENANT_BLOCKED");
        assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("PREVIOUS_COMPANY");
        verify(enterpriseAuditTrailService).recordBusinessEvent(any(AuditActionEventCommand.class));
    }
}
