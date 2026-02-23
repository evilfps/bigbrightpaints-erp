package com.bigbrightpaints.erp.modules.portal.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementInterceptorTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-20T10:16:05Z");

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private AuditService auditService;

    private final Map<String, String> settings = new HashMap<>();
    private TenantRuntimeEnforcementInterceptor interceptor;
    private Company company;

    @BeforeEach
    void setUp() {
        freezeTime(FIXED_NOW);
        interceptor = new TenantRuntimeEnforcementInterceptor(
                companyContextService,
                systemSettingsRepository,
                auditService
        );
        company = tenant(55L, "ACME");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);

        lenient().when(systemSettingsRepository.findById(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            String value = settings.get(key);
            return value == null ? Optional.empty() : Optional.of(new SystemSetting(key, value));
        });
        lenient().when(systemSettingsRepository.save(any(SystemSetting.class))).thenAnswer(invocation -> {
            SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
            settings.put(setting.getKey(), setting.getValue());
            return setting;
        });
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(CompanyTime.class, "companyClock", null);
    }

    @Test
    void preHandle_bypassesWhenPathIsNotEnforced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/settings");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verifyNoInteractions(auditService);
        verify(companyContextService, never()).requireCurrentCompany();
    }

    @Test
    void preHandle_bypassesWhenPathIsBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("   ");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        verify(companyContextService, never()).requireCurrentCompany();
        verifyNoInteractions(auditService);
    }

    @Test
    void isMutatingMethod_treatsBlankMethodAsMutating() {
        Boolean result = ReflectionTestUtils.invokeMethod(interceptor, "isMutatingMethod", (String) null);

        assertThat(result).isTrue();
    }

    @Test
    void isMutatingMethod_treatsHeadAndOptionsAsNonMutating() {
        Boolean head = ReflectionTestUtils.invokeMethod(interceptor, "isMutatingMethod", " HEAD ");
        Boolean options = ReflectionTestUtils.invokeMethod(interceptor, "isMutatingMethod", "options");
        Boolean patch = ReflectionTestUtils.invokeMethod(interceptor, "isMutatingMethod", "PATCH");

        assertThat(head).isFalse();
        assertThat(options).isFalse();
        assertThat(patch).isTrue();
    }

    @Test
    void preHandle_enforcesAccountingReportsPath_withMissingHoldStateAndMalformedQuotas() throws Exception {
        settings.put(keyMaxRequestsPerMinute(55L), "bad-rpm");
        settings.put(keyMaxConcurrentRequests(55L), "bad-concurrency");
        settings.put(keyPolicyReference(55L), "policy-accounting");

        MockHttpServletRequest request = request("GET", "/api/v1/accounting/reports/pnl-summary");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(attrEnforced())).isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute(attrCompanyId())).isEqualTo(55L);
        verifyNoInteractions(auditService);
    }

    @Test
    void preHandle_deniesWhenHoldStateIsUnsupported_failClosed() {
        settings.put(keyHoldState(55L), "PAUSED");
        settings.put(keyHoldReason(55L), "Unexpected runtime hold state");
        settings.put(keyPolicyReference(55L), "policy-paused");

        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");
        request.setRemoteAddr("198.51.100.31");
        request.addHeader("X-Request-Id", "req-paused-1");
        request.addHeader("X-Trace-Id", "trace-paused-1");
        request.addHeader("User-Agent", "runtime-paused-test");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("holdState", "BLOCKED")
                            .containsEntry("holdReason", "Unexpected runtime hold state")
                            .containsEntry("policyReference", "policy-paused")
                            .containsEntry("path", "/api/v1/portal/dashboard");
                });

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("action", "TENANT_RUNTIME_STATE_DENIED")
                .containsEntry("holdState", "BLOCKED")
                .containsEntry("holdReason", "Unexpected runtime hold state")
                .containsEntry("reason", "Tenant runtime state is BLOCKED")
                .containsEntry("requestId", "req-paused-1")
                .containsEntry("traceId", "trace-paused-1")
                .containsEntry("userAgent", "runtime-paused-test")
                .containsEntry("remoteAddr", "198.51.100.31");
    }

    @Test
    void preHandle_deniesWhenHoldStateIsBlank_failClosed() {
        settings.put(keyHoldState(55L), "   ");
        settings.put(keyHoldReason(55L), "Malformed runtime hold state");
        settings.put(keyPolicyReference(55L), "policy-blank");

        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("holdState", "BLOCKED")
                            .containsEntry("holdReason", "Malformed runtime hold state")
                            .containsEntry("policyReference", "policy-blank")
                            .containsEntry("path", "/api/v1/portal/dashboard");
                });
    }

    @Test
    void preHandle_deniesWhenTenantStateIsBlocked_andPersistsBlockedMetrics() {
        settings.put(keyHoldState(55L), "BLOCKED");
        settings.put(keyHoldReason(55L), "Security review");
        settings.put(keyPolicyReference(55L), "policy-blocked");

        MockHttpServletRequest request = request("POST", "/api/v1/portal/orders");
        request.setRemoteAddr("198.51.100.11");
        request.addHeader("X-Request-Id", "req-state-1");
        request.addHeader("X-Trace-Id", "trace-state-1");
        request.addHeader("User-Agent", "runtime-state-test");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("holdState", "BLOCKED")
                            .containsEntry("holdReason", "Security review")
                            .containsEntry("policyReference", "policy-blocked")
                            .containsEntry("path", "/api/v1/portal/orders");
                });

        long minuteEpoch = FIXED_NOW.getEpochSecond() / 60;
        assertThat(settings.get(keyMetricMinuteEpoch(55L))).isEqualTo(Long.toString(minuteEpoch));
        assertThat(settings.get(keyMetricRequestsMinute(55L))).isEqualTo("0");
        assertThat(settings.get(keyMetricBlockedMinute(55L))).isEqualTo("1");
        assertThat(settings.get(keyMetricInFlight(55L))).isEqualTo("0");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("policyReference", "policy-blocked")
                .containsEntry("action", "TENANT_RUNTIME_STATE_DENIED")
                .containsEntry("holdState", "BLOCKED")
                .containsEntry("holdReason", "Security review")
                .containsEntry("reason", "Tenant runtime state is BLOCKED")
                .containsEntry("requestPath", "/api/v1/portal/orders")
                .containsEntry("requestMethod", "POST")
                .containsEntry("remoteAddr", "198.51.100.11")
                .containsEntry("requestId", "req-state-1")
                .containsEntry("traceId", "trace-state-1")
                .containsEntry("userAgent", "runtime-state-test")
                .containsEntry("occurredAt", FIXED_NOW.toString());
    }

    @Test
    void preHandle_holdState_allowsReadOnlyAndBlocksMutatingRequests() throws Exception {
        settings.put(keyHoldState(55L), "HOLD");
        settings.put(keyHoldReason(55L), "Temporary hold");
        settings.put(keyPolicyReference(55L), "policy-hold");

        MockHttpServletRequest readRequest = request("GET", "/api/v1/portal/dashboard");
        boolean readAllowed = interceptor.preHandle(readRequest, new MockHttpServletResponse(), new Object());
        assertThat(readAllowed).isTrue();
        assertThat(readRequest.getAttribute(attrEnforced())).isEqualTo(Boolean.TRUE);
        assertThat(readRequest.getAttribute(attrCompanyId())).isEqualTo(55L);

        MockHttpServletRequest writeRequest = request("POST", "/api/v1/portal/dashboard");
        assertThatThrownBy(() -> interceptor.preHandle(writeRequest, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(exception.getDetails())
                            .containsEntry("holdState", "HOLD")
                            .containsEntry("path", "/api/v1/portal/dashboard");
                });
    }

    @Test
    void preHandle_deniesWhenRequestsPerMinuteExceeded_withQuotaMetadata() throws Exception {
        settings.put(keyHoldState(55L), "ACTIVE");
        settings.put(keyMaxRequestsPerMinute(55L), "1");
        settings.put(keyMaxConcurrentRequests(55L), "5");
        settings.put(keyPolicyReference(55L), "policy-rpm");

        MockHttpServletRequest first = request("GET", "/api/v1/reports/inventory");
        boolean firstAllowed = interceptor.preHandle(first, new MockHttpServletResponse(), new Object());
        assertThat(firstAllowed).isTrue();
        assertThat(first.getAttribute(attrEnforced())).isEqualTo(Boolean.TRUE);
        assertThat(first.getAttribute(attrCompanyId())).isEqualTo(55L);

        MockHttpServletRequest second = request("GET", "/api/v1/reports/inventory");
        second.setRemoteAddr("203.0.113.20");
        second.addHeader("X-Request-Id", "req-rpm-2");
        second.addHeader("X-Trace-Id", "trace-rpm-2");
        second.addHeader("User-Agent", "runtime-rpm-test");

        assertThatThrownBy(() -> interceptor.preHandle(second, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("quotaType", "REQUESTS_PER_MINUTE")
                            .containsEntry("quotaValue", 1)
                            .containsEntry("observed", 2)
                            .containsEntry("policyReference", "policy-rpm")
                            .containsEntry("path", "/api/v1/reports/inventory");
                });

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("action", "TENANT_RUNTIME_RATE_LIMIT_DENIED")
                .containsEntry("quotaType", "REQUESTS_PER_MINUTE")
                .containsEntry("quotaValue", "1")
                .containsEntry("observed", "2")
                .containsEntry("requestId", "req-rpm-2")
                .containsEntry("traceId", "trace-rpm-2")
                .containsEntry("userAgent", "runtime-rpm-test")
                .containsEntry("remoteAddr", "203.0.113.20");
    }

    @Test
    void preHandle_deniesWhenConcurrentLimitExceeded_andAfterCompletionReleasesCounter() throws Exception {
        settings.put(keyHoldState(55L), "ACTIVE");
        settings.put(keyMaxRequestsPerMinute(55L), "10");
        settings.put(keyMaxConcurrentRequests(55L), "1");
        settings.put(keyPolicyReference(55L), "policy-concurrency");

        MockHttpServletRequest first = request("GET", "/api/v1/demo/health");
        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), new Object())).isTrue();

        MockHttpServletRequest second = request("GET", "/api/v1/demo/health");
        second.setRemoteAddr("203.0.113.21");
        second.addHeader("X-Request-Id", "req-con-2");
        second.addHeader("X-Trace-Id", "trace-con-2");
        second.addHeader("User-Agent", "runtime-concurrency-test");

        assertThatThrownBy(() -> interceptor.preHandle(second, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApplicationException.class)
                .satisfies(error -> {
                    ApplicationException exception = (ApplicationException) error;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_LIMIT_EXCEEDED);
                    assertThat(exception.getDetails())
                            .containsEntry("companyCode", "ACME")
                            .containsEntry("quotaType", "CONCURRENT_REQUESTS")
                            .containsEntry("quotaValue", 1)
                            .containsEntry("observed", 2)
                            .containsEntry("policyReference", "policy-concurrency")
                            .containsEntry("path", "/api/v1/demo/health");
                });

        interceptor.afterCompletion(first, new MockHttpServletResponse(), new Object(), null);

        assertThat(settings.get(keyMetricInFlight(55L))).isEqualTo("0");
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("action", "TENANT_RUNTIME_CONCURRENCY_DENIED")
                .containsEntry("quotaType", "CONCURRENT_REQUESTS")
                .containsEntry("quotaValue", "1")
                .containsEntry("observed", "2");
    }

    @Test
    void preHandle_continuesWhenMetricPersistenceFails() throws Exception {
        settings.put(keyHoldState(55L), "ACTIVE");
        settings.put(keyMaxRequestsPerMinute(55L), "100");
        settings.put(keyMaxConcurrentRequests(55L), "10");
        when(systemSettingsRepository.save(any(SystemSetting.class))).thenThrow(new RuntimeException("db-down"));

        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(attrEnforced())).isEqualTo(Boolean.TRUE);
        assertThat(request.getAttribute(attrCompanyId())).isEqualTo(55L);
        verifyNoInteractions(auditService);
    }

    @Test
    void afterCompletion_returnsEarlyWhenRequestWasNotEnforced() {
        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(systemSettingsRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void afterCompletion_returnsEarlyWhenCompanyAttributeIsNotLong() {
        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");
        request.setAttribute(attrEnforced(), Boolean.TRUE);
        request.setAttribute(attrCompanyId(), "55");

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(systemSettingsRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void afterCompletion_returnsEarlyWhenInFlightCounterMissingForCompany() {
        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");
        request.setAttribute(attrEnforced(), Boolean.TRUE);
        request.setAttribute(attrCompanyId(), 999L);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        verify(systemSettingsRepository, never()).save(any(SystemSetting.class));
    }

    @Test
    void afterCompletion_resetsNegativeCounterToZero() throws Exception {
        settings.put(keyHoldState(55L), "ACTIVE");
        settings.put(keyMaxRequestsPerMinute(55L), "10");
        settings.put(keyMaxConcurrentRequests(55L), "2");

        MockHttpServletRequest request = request("GET", "/api/v1/portal/dashboard");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(settings.get(keyMetricInFlight(55L))).isEqualTo("0");
    }

    @Test
    void headerValue_returnsNullForNullRequestOrBlankHeaderName() {
        String valueFromNullRequest = ReflectionTestUtils.invokeMethod(interceptor, "headerValue", null, "X-Request-Id");
        String valueFromBlankHeader = ReflectionTestUtils.invokeMethod(
                interceptor,
                "headerValue",
                request("GET", "/api/v1/portal/dashboard"),
                "   "
        );

        assertThat(valueFromNullRequest).isNull();
        assertThat(valueFromBlankHeader).isNull();
    }

    private void freezeTime(Instant now) {
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
        when(clockProvider.getIfAvailable(any())).thenReturn(Clock.fixed(now, ZoneOffset.UTC));
        new CompanyTime(new CompanyClock(clockProvider));
    }

    private Company tenant(Long id, String code) {
        Company result = mock(Company.class);
        lenient().when(result.getId()).thenReturn(id);
        lenient().when(result.getCode()).thenReturn(code);
        return result;
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private String attrEnforced() {
        return TenantRuntimeEnforcementInterceptor.class.getName() + ".enforced";
    }

    private String attrCompanyId() {
        return TenantRuntimeEnforcementInterceptor.class.getName() + ".companyId";
    }

    private String keyHoldState(Long companyId) {
        return "tenant.runtime.hold-state." + companyId;
    }

    private String keyHoldReason(Long companyId) {
        return "tenant.runtime.hold-reason." + companyId;
    }

    private String keyMaxRequestsPerMinute(Long companyId) {
        return "tenant.runtime.max-requests-per-minute." + companyId;
    }

    private String keyMaxConcurrentRequests(Long companyId) {
        return "tenant.runtime.max-concurrent-requests." + companyId;
    }

    private String keyPolicyReference(Long companyId) {
        return "tenant.runtime.policy-reference." + companyId;
    }

    private String keyMetricMinuteEpoch(Long companyId) {
        return "tenant.runtime.metrics.minute-epoch." + companyId;
    }

    private String keyMetricRequestsMinute(Long companyId) {
        return "tenant.runtime.metrics.requests-minute." + companyId;
    }

    private String keyMetricBlockedMinute(Long companyId) {
        return "tenant.runtime.metrics.blocked-minute." + companyId;
    }

    private String keyMetricInFlight(Long companyId) {
        return "tenant.runtime.metrics.inflight." + companyId;
    }
}
