package com.bigbrightpaints.erp.modules.admin.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimeMetricsDto;
import com.bigbrightpaints.erp.modules.admin.dto.TenantRuntimePolicyUpdateRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
class TenantRuntimePolicyServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-20T10:15:30Z");

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private AuditService auditService;

    private final Map<String, String> settings = new HashMap<>();
    private TenantRuntimePolicyService service;
    private Company company;

    @BeforeEach
    void setUp() {
        freezeTime(FIXED_NOW);
        service = new TenantRuntimePolicyService(
                companyContextService,
                systemSettingsRepository,
                userAccountRepository,
                auditService
        );
        company = tenant(42L, "ACME");
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
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void updatePolicy_normalizesUnsupportedHoldStateToBlocked_failClosed() {
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true)));

        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                null,
                null,
                null,
                "PAUSED",
                "Manual override",
                "invalid state test"
        );

        TenantRuntimeMetricsDto metrics = service.updatePolicy(request);

        assertThat(metrics.holdState()).isEqualTo("BLOCKED");
        assertThat(metrics.holdReason()).isEqualTo("Manual override");
        assertThat(settings.get(keyHoldState(42L))).isEqualTo("BLOCKED");
        assertThat(settings.get(keyHoldReason(42L))).isEqualTo("Manual override");
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }

    @Test
    void updatePolicy_withNullRequest_preservesCurrentPolicy_andAuditsWithEmptyRequestContext() {
        settings.put(keyHoldState(42L), "HOLD");
        settings.put(keyHoldReason(42L), "Manual review");
        settings.put(keyMaxActiveUsers(42L), "21");
        settings.put(keyMaxRequestsPerMinute(42L), "600");
        settings.put(keyMaxConcurrentRequests(42L), "13");
        settings.put(keyPolicyReference(42L), "policy-before-null-request");
        settings.put(keyPolicyUpdatedAt(42L), "2026-02-19T12:00:00Z");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false)));

        TenantRuntimeMetricsDto result = service.updatePolicy(null);

        assertThat(result.holdState()).isEqualTo("HOLD");
        assertThat(result.holdReason()).isEqualTo("Manual review");
        assertThat(result.maxActiveUsers()).isEqualTo(21);
        assertThat(result.maxRequestsPerMinute()).isEqualTo(600);
        assertThat(result.maxConcurrentRequests()).isEqualTo(13);
        assertThat(result.policyUpdatedAt()).isEqualTo(FIXED_NOW);
        assertThat(result.policyReference()).isNotEqualTo("policy-before-null-request");
        assertThat(settings.get(keyPolicyReference(42L))).isEqualTo(result.policyReference());

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("changeReason", "")
                .containsEntry("requestId", "")
                .containsEntry("traceId", "")
                .containsEntry("ipAddress", "")
                .containsEntry("userAgent", "");
    }

    @Test
    void updatePolicy_rejectsHoldWithoutReason_failClosed() {
        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                50,
                500,
                10,
                "HOLD",
                "   ",
                "missing reason test"
        );

        assertThatThrownBy(() -> service.updatePolicy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdReason is required when holdState is HOLD or BLOCKED");
        verify(systemSettingsRepository, never()).save(any(SystemSetting.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void updatePolicy_rejectsNonPositiveQuotaValues_failClosed() {
        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                0,
                500,
                10,
                "ACTIVE",
                null,
                "quota guard"
        );

        assertThatThrownBy(() -> service.updatePolicy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quota values must be at least 1");
        verify(systemSettingsRepository, never()).save(any(SystemSetting.class));
        verifyNoInteractions(auditService);
    }

    @Test
    void updatePolicy_clearsHoldReasonWhenActivated_andWritesAuditContext() {
        settings.put(keyHoldState(42L), "hold");
        settings.put(keyHoldReason(42L), " Legacy hold ");
        settings.put(keyMaxActiveUsers(42L), "99");
        settings.put(keyMaxRequestsPerMinute(42L), "300");
        settings.put(keyMaxConcurrentRequests(42L), "10");
        settings.put(keyPolicyReference(42L), "policy-before");
        settings.put(keyPolicyUpdatedAt(42L), "2026-02-18T00:00:00Z");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false), user(true)));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/admin/tenant-runtime/policy");
        request.addHeader("X-Request-Id", "req-123");
        request.addHeader("X-Trace-Id", "trace-456");
        request.addHeader("User-Agent", "tenant-runtime-test");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        TenantRuntimePolicyUpdateRequest policyUpdate = new TenantRuntimePolicyUpdateRequest(
                120,
                800,
                20,
                "ACTIVE",
                "This should be removed",
                "Risk accepted"
        );

        TenantRuntimeMetricsDto result = service.updatePolicy(policyUpdate);

        assertThat(result.holdState()).isEqualTo("ACTIVE");
        assertThat(result.holdReason()).isNull();
        assertThat(result.maxActiveUsers()).isEqualTo(120);
        assertThat(result.maxRequestsPerMinute()).isEqualTo(800);
        assertThat(result.maxConcurrentRequests()).isEqualTo(20);
        assertThat(result.enabledUsers()).isEqualTo(2);
        assertThat(result.totalUsers()).isEqualTo(3);
        assertThat(result.policyUpdatedAt()).isEqualTo(FIXED_NOW);
        assertThat(settings.get(keyHoldState(42L))).isEqualTo("ACTIVE");
        assertThat(settings.get(keyHoldReason(42L))).isEmpty();
        assertThat(settings.get(keyPolicyReference(42L))).isEqualTo(result.policyReference());
        assertThat(settings.get(keyPolicyUpdatedAt(42L))).isEqualTo(FIXED_NOW.toString());

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("oldHoldState", "HOLD")
                .containsEntry("newHoldState", "ACTIVE")
                .containsEntry("oldMaxActiveUsers", "99")
                .containsEntry("newMaxActiveUsers", "120")
                .containsEntry("changeReason", "Risk accepted")
                .containsEntry("requestId", "req-123")
                .containsEntry("traceId", "trace-456")
                .containsEntry("ipAddress", "203.0.113.10")
                .containsEntry("userAgent", "tenant-runtime-test")
                .containsEntry("policyReference", result.policyReference());
    }

    @Test
    void metrics_returnsCurrentMinuteSnapshot_andNormalizesPersistedValues() {
        long minuteEpoch = FIXED_NOW.getEpochSecond() / 60;
        settings.put(keyHoldState(42L), "ACTIVE");
        settings.put(keyHoldReason(42L), "should be ignored for active state");
        settings.put(keyMaxActiveUsers(42L), "0");
        settings.put(keyMaxRequestsPerMinute(42L), "invalid");
        settings.put(keyMaxConcurrentRequests(42L), "-10");
        settings.put(keyPolicyReference(42L), "   ");
        settings.put(keyPolicyUpdatedAt(42L), "not-an-instant");
        settings.put(keyMetricMinuteEpoch(42L), Long.toString(minuteEpoch));
        settings.put(keyMetricRequestsMinute(42L), "-3");
        settings.put(keyMetricBlockedMinute(42L), "7");
        settings.put(keyMetricInFlight(42L), "-9");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false), user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("ACTIVE");
        assertThat(metrics.holdReason()).isNull();
        assertThat(metrics.maxActiveUsers()).isEqualTo(250);
        assertThat(metrics.maxRequestsPerMinute()).isEqualTo(1200);
        assertThat(metrics.maxConcurrentRequests()).isEqualTo(40);
        assertThat(metrics.enabledUsers()).isEqualTo(2);
        assertThat(metrics.totalUsers()).isEqualTo(3);
        assertThat(metrics.requestsThisMinute()).isZero();
        assertThat(metrics.blockedThisMinute()).isEqualTo(7);
        assertThat(metrics.inFlightRequests()).isZero();
        assertThat(metrics.policyReference()).isEqualTo("bootstrap");
        assertThat(metrics.policyUpdatedAt()).isNull();
    }

    @Test
    void metrics_zerosMinuteCounters_whenSnapshotMinuteIsStale() {
        long staleMinute = (FIXED_NOW.getEpochSecond() / 60) - 1;
        settings.put(keyHoldState(42L), "HOLD");
        settings.put(keyHoldReason(42L), "Manual review");
        settings.put(keyMaxActiveUsers(42L), "200");
        settings.put(keyMaxRequestsPerMinute(42L), "500");
        settings.put(keyMaxConcurrentRequests(42L), "50");
        settings.put(keyPolicyReference(42L), "policy-42");
        settings.put(keyPolicyUpdatedAt(42L), "2026-02-19T10:00:00Z");
        settings.put(keyMetricMinuteEpoch(42L), Long.toString(staleMinute));
        settings.put(keyMetricRequestsMinute(42L), "11");
        settings.put(keyMetricBlockedMinute(42L), "2");
        settings.put(keyMetricInFlight(42L), "4");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("HOLD");
        assertThat(metrics.holdReason()).isEqualTo("Manual review");
        assertThat(metrics.requestsThisMinute()).isZero();
        assertThat(metrics.blockedThisMinute()).isZero();
        assertThat(metrics.inFlightRequests()).isEqualTo(4);
    }

    @Test
    void metrics_defaultsHoldStateAndParsesMalformedMetricValuesAsZero() {
        long minuteEpoch = FIXED_NOW.getEpochSecond() / 60;
        settings.put(keyMetricMinuteEpoch(42L), Long.toString(minuteEpoch));
        settings.put(keyMetricRequestsMinute(42L), "not-a-number");
        settings.put(keyMetricBlockedMinute(42L), "also-not-a-number");
        settings.put(keyMetricInFlight(42L), "broken");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("ACTIVE");
        assertThat(metrics.requestsThisMinute()).isZero();
        assertThat(metrics.blockedThisMinute()).isZero();
        assertThat(metrics.inFlightRequests()).isZero();
    }

    @Test
    void metrics_normalizesPersistedUnsupportedHoldStateToBlocked_failClosed() {
        settings.put(keyHoldState(42L), "PAUSED");
        settings.put(keyHoldReason(42L), "Unexpected runtime hold state");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("BLOCKED");
        assertThat(metrics.holdReason()).isEqualTo("Unexpected runtime hold state");
    }

    @Test
    void metrics_normalizesPersistedBlankHoldStateToBlocked_failClosed() {
        settings.put(keyHoldState(42L), "   ");
        settings.put(keyHoldReason(42L), "Malformed runtime hold state");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true)));

        TenantRuntimeMetricsDto metrics = service.metrics();

        assertThat(metrics.holdState()).isEqualTo("BLOCKED");
        assertThat(metrics.holdReason()).isEqualTo("Malformed runtime hold state");
    }

    @Test
    void assertCanAddEnabledUser_throwsQuotaException_andAuditsFailure() {
        settings.put(keyMaxActiveUsers(42L), "1");
        settings.put(keyPolicyReference(42L), "policy-ref-7");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false)));

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
        settings.put(keyMaxActiveUsers(42L), "5");
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false)));

        service.assertCanAddEnabledUser(company, "ENABLE_USER");

        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), org.mockito.ArgumentMatchers.<Map<String, String>>any());
    }

    @Test
    void assertCanAddEnabledUser_noopsWhenCompanyMissing() {
        service.assertCanAddEnabledUser(null, "ENABLE_USER");

        verifyNoInteractions(userAccountRepository, auditService, systemSettingsRepository);
    }

    @Test
    void metrics_delegatesToTenantRuntimeEnforcementService_whenAvailable() {
        TenantRuntimeEnforcementService tenantRuntimeEnforcementService = mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService delegatedService = new TenantRuntimePolicyService(
                companyContextService,
                systemSettingsRepository,
                userAccountRepository,
                auditService,
                tenantRuntimeEnforcementService
        );
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false), user(true)));
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(
                new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                        "INCIDENT_CONTAINMENT",
                        "audit-chain-1",
                        FIXED_NOW,
                        30,
                        900,
                        120,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(71, 8, 2, 3, 11, 8, 2)
                )
        );

        TenantRuntimeMetricsDto metrics = delegatedService.metrics();

        assertThat(metrics.companyCode()).isEqualTo("ACME");
        assertThat(metrics.holdState()).isEqualTo("HOLD");
        assertThat(metrics.holdReason()).isEqualTo("INCIDENT_CONTAINMENT");
        assertThat(metrics.maxActiveUsers()).isEqualTo(120);
        assertThat(metrics.maxRequestsPerMinute()).isEqualTo(900);
        assertThat(metrics.maxConcurrentRequests()).isEqualTo(30);
        assertThat(metrics.enabledUsers()).isEqualTo(2);
        assertThat(metrics.totalUsers()).isEqualTo(3);
        assertThat(metrics.requestsThisMinute()).isEqualTo(11);
        assertThat(metrics.blockedThisMinute()).isEqualTo(8);
        assertThat(metrics.inFlightRequests()).isEqualTo(3);
        assertThat(metrics.policyReference()).isEqualTo("audit-chain-1");
        assertThat(metrics.policyUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void updatePolicy_delegatesToTenantRuntimeEnforcementService_andNormalizesUnknownHoldStateToBlocked() {
        TenantRuntimeEnforcementService tenantRuntimeEnforcementService = mock(TenantRuntimeEnforcementService.class);
        TenantRuntimePolicyService delegatedService = new TenantRuntimePolicyService(
                companyContextService,
                systemSettingsRepository,
                userAccountRepository,
                auditService,
                tenantRuntimeEnforcementService
        );
        when(userAccountRepository.findDistinctByCompanies_Id(42L))
                .thenReturn(List.of(user(true), user(false)));
        when(tenantRuntimeEnforcementService.snapshot("ACME")).thenReturn(
                new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
                        "POLICY_ACTIVE",
                        "audit-chain-before",
                        FIXED_NOW.minusSeconds(60),
                        40,
                        1200,
                        250,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(3, 0, 0, 0, 1, 0, 1)
                )
        );
        TenantRuntimePolicyUpdateRequest request = new TenantRuntimePolicyUpdateRequest(
                55,
                800,
                21,
                "PAUSED",
                "Manual override",
                "Controlled rollout"
        );
        when(tenantRuntimeEnforcementService.updatePolicy(
                eq("ACME"),
                eq(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED),
                eq("Manual override"),
                eq(21),
                eq(800),
                eq(55),
                eq(null)
        )).thenReturn(
                new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                        "Manual override",
                        "audit-chain-2",
                        FIXED_NOW,
                        21,
                        800,
                        55,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(10, 1, 0, 0, 4, 1, 1)
                )
        );

        TenantRuntimeMetricsDto updated = delegatedService.updatePolicy(request);

        assertThat(updated.holdState()).isEqualTo("BLOCKED");
        assertThat(updated.holdReason()).isEqualTo("Manual override");
        assertThat(updated.maxConcurrentRequests()).isEqualTo(21);
        assertThat(updated.maxRequestsPerMinute()).isEqualTo(800);
        assertThat(updated.maxActiveUsers()).isEqualTo(55);
        assertThat(updated.enabledUsers()).isEqualTo(1);
        assertThat(updated.totalUsers()).isEqualTo(2);
        assertThat(updated.policyReference()).isEqualTo("audit-chain-2");
        verify(tenantRuntimeEnforcementService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "Manual override",
                21,
                800,
                55,
                null
        );
        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("companyCode", "ACME")
                .containsEntry("oldHoldState", "ACTIVE")
                .containsEntry("newHoldState", "BLOCKED")
                .containsEntry("oldMaxActiveUsers", "250")
                .containsEntry("newMaxActiveUsers", "55")
                .containsEntry("changeReason", "Controlled rollout")
                .containsEntry("policyReference", "audit-chain-2");
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

    private UserAccount user(boolean enabled) {
        String email = enabled ? "enabled-user@example.com" : "disabled-user@example.com";
        UserAccount account = new UserAccount(email, "hash", "User");
        account.setEnabled(enabled);
        return account;
    }

    private String keyHoldState(Long companyId) {
        return "tenant.runtime.hold-state." + companyId;
    }

    private String keyHoldReason(Long companyId) {
        return "tenant.runtime.hold-reason." + companyId;
    }

    private String keyMaxActiveUsers(Long companyId) {
        return "tenant.runtime.max-active-users." + companyId;
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

    private String keyPolicyUpdatedAt(Long companyId) {
        return "tenant.runtime.policy-updated-at." + companyId;
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
