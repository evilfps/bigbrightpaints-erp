package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
@Tag("reconciliation")
class TS_RuntimeTenantControlPlaneEnforcementTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private SystemSettingsRepository systemSettingsRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private EnterpriseAuditTrailService enterpriseAuditTrailService;

    private final Map<String, Company> companiesByCode = new HashMap<>();
    private final Map<String, String> persistedSettingsByKey = new HashMap<>();

    @BeforeEach
    void setUp() {
        Company acme = new Company();
        ReflectionTestUtils.setField(acme, "id", 1L);
        acme.setCode("ACME");
        companiesByCode.put("ACME", acme);

        lenient().when(companyRepository.findByCodeIgnoreCase(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(companiesByCode.get(code.trim().toUpperCase(Locale.ROOT)));
        });
        lenient().when(systemSettingsRepository.findById(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);
            String value = persistedSettingsByKey.get(key);
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new SystemSetting(key, value));
        });
        lenient().when(systemSettingsRepository.save(any(SystemSetting.class))).thenAnswer(invocation -> {
            SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
            persistedSettingsByKey.put(setting.getKey(), setting.getValue());
            return setting;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        com.bigbrightpaints.erp.core.security.CompanyContextHolder.clear();
    }

    @Test
    void coreRuntimeAdmission_usesLegacyBlockedState_whenCompanyScopedPolicyIsMetadataOnly() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(legacyKey("acme", "state"), "BLOCKED");
        persistedSettingsByKey.put(keyPolicyReference(1L), "policy-v2");
        persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-23T11:05:00Z");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle blocked =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.httpStatus()).isEqualTo(403);
        assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");
    }

    @Test
    void coreRuntimeAdmission_invalidLegacyState_failsClosedToBlocked() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(legacyKey("acme", "state"), "mystery");
        persistedSettingsByKey.put(legacyKey("default", "state"), "ACTIVE");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle blocked =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.httpStatus()).isEqualTo(403);
        assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");
    }

    @Test
    void coreRuntimeAdmission_invalidLegacyDefaultState_failsClosedToBlocked_whenTenantStateMissing() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(legacyKey("default", "state"), "mystery");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle blocked =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.httpStatus()).isEqualTo(403);
        assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");
    }

    @Test
    void coreRuntimeAdmission_denyPath_continuesWhenLegacyAuditWriteFails_andRestoresContext() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
        com.bigbrightpaints.erp.core.security.CompanyContextHolder.setCompanyCode("PREVIOUS_COMPANY");
        doThrow(new RuntimeException("legacy-audit-down"))
                .when(auditService)
                .logFailure(eq(com.bigbrightpaints.erp.core.audit.AuditEvent.ACCESS_DENIED), anyMap());

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle denied =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasonCode()).isEqualTo("TENANT_BLOCKED");
        assertThat(com.bigbrightpaints.erp.core.security.CompanyContextHolder.getCompanyCode())
                .isEqualTo("PREVIOUS_COMPANY");
    }

    @Test
    void coreRuntimeAdmission_settingReadFailure_defaultsSafelyWithoutThrowing() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        when(systemSettingsRepository.findById(any())).thenThrow(new RuntimeException("settings-store-down"));

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle allowed =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

        assertThat(allowed.allowed()).isTrue();
        allowed.close();
    }

    @Test
    void coreRuntimeAdmission_isolatesCacheByCompanyId_whenNormalizedCodeTokensCollide() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        Company first = new Company();
        ReflectionTestUtils.setField(first, "id", 101L);
        first.setCode("ACME INC");
        companiesByCode.put("ACME INC", first);
        Company second = new Company();
        ReflectionTestUtils.setField(second, "id", 102L);
        second.setCode("ACME@INC");
        companiesByCode.put("ACME@INC", second);
        persistedSettingsByKey.put(keyHoldState(101L), "BLOCKED");
        persistedSettingsByKey.put(keyHoldState(102L), "ACTIVE");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle blocked =
                coreRuntimeService.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle allowed =
                coreRuntimeService.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(allowed.allowed()).isTrue();
        allowed.close();
    }

    @Test
    void coreRuntimeAdmission_isolatesRuntimeQuotasAndCounters_whenNormalizedCodeTokensCollide() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        Company first = new Company();
        ReflectionTestUtils.setField(first, "id", 201L);
        first.setCode("ACME INC");
        companiesByCode.put("ACME INC", first);
        Company second = new Company();
        ReflectionTestUtils.setField(second, "id", 202L);
        second.setCode("ACME@INC");
        companiesByCode.put("ACME@INC", second);
        persistedSettingsByKey.put(keyMaxRequestsPerMinute(201L), "1");
        persistedSettingsByKey.put(keyMaxRequestsPerMinute(202L), "1");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle firstAllowed =
                coreRuntimeService.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(firstAllowed.allowed()).isTrue();
        firstAllowed.close();

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle secondAllowed =
                coreRuntimeService.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(secondAllowed.allowed()).isTrue();
        secondAllowed.close();

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle firstDenied =
                coreRuntimeService.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(firstDenied.allowed()).isFalse();
        assertThat(firstDenied.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle secondDenied =
                coreRuntimeService.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(secondDenied.allowed()).isFalse();
        assertThat(secondDenied.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot firstSnapshot =
                coreRuntimeService.snapshot("ACME INC").orElseThrow();
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot secondSnapshot =
                coreRuntimeService.snapshot("ACME@INC").orElseThrow();
        assertThat(firstSnapshot.totalRequests()).isEqualTo(2L);
        assertThat(firstSnapshot.deniedRequests()).isEqualTo(1L);
        assertThat(secondSnapshot.totalRequests()).isEqualTo(2L);
        assertThat(secondSnapshot.deniedRequests()).isEqualTo(1L);

        Map<String, com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot> allSnapshots =
                coreRuntimeService.snapshotAll();
        assertThat(allSnapshots).containsKeys("201:acme_inc", "202:acme_inc");
    }

    @Test
    void coreRuntimeAdmission_deniedAuditClientIp_usesForwardedForWithAndWithoutComma() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");

        MockHttpServletRequest withComma = new MockHttpServletRequest("GET", "/api/v1/private");
        withComma.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle firstDenied =
                coreRuntimeService.acquire("ACME", withComma);
        assertThat(firstDenied.allowed()).isFalse();
        assertThat(firstDenied.reasonCode()).isEqualTo("TENANT_BLOCKED");

        MockHttpServletRequest withoutComma = new MockHttpServletRequest("GET", "/api/v1/private");
        withoutComma.addHeader("X-Forwarded-For", "10.0.0.3");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle secondDenied =
                coreRuntimeService.acquire("ACME", withoutComma);
        assertThat(secondDenied.allowed()).isFalse();
        assertThat(secondDenied.reasonCode()).isEqualTo("TENANT_BLOCKED");
    }

    @Test
    void coreRuntimeAdmission_resolvePolicy_handlesNullCompanyId_andExpiredCacheEntry() throws InterruptedException {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService shortCacheService =
                new com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService(
                        companyRepository,
                        systemSettingsRepository,
                        auditService,
                        enterpriseAuditTrailService,
                        null,
                        0,
                        0,
                        1);

        Object cachedPolicy = ReflectionTestUtils.invokeMethod(shortCacheService, "resolvePolicy", "acme", 1L);
        assertThat(cachedPolicy).isNotNull();
        Object cacheHitPolicy = ReflectionTestUtils.invokeMethod(shortCacheService, "resolvePolicy", "acme", 1L);
        assertThat(cacheHitPolicy).isNotNull();

        Thread.sleep(1100L);
        Object expiredCachePolicy = ReflectionTestUtils.invokeMethod(shortCacheService, "resolvePolicy", "acme", 1L);
        assertThat(expiredCachePolicy).isNotNull();
        Object nullCompanyPolicy = ReflectionTestUtils.invokeMethod(shortCacheService, "resolvePolicy", "acme", null);
        assertThat(nullCompanyPolicy).isNotNull();
    }

    @Test
    void coreRuntimeAdmission_prefersCompanyScopedQuotasAndReason_overLegacyTenantCodeValues() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(legacyKey("acme", "state"), "ACTIVE");
        persistedSettingsByKey.put(legacyKey("acme", "reason-code"), "LEGACY_REASON");
        persistedSettingsByKey.put(legacyKey("acme", "quota.max-concurrent"), "1");
        persistedSettingsByKey.put(legacyKey("acme", "quota.max-requests-per-minute"), "1");
        persistedSettingsByKey.put(keyHoldState(1L), "HOLD");
        persistedSettingsByKey.put(keyHoldReason(1L), "OPS_FREEZE");
        persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "7");
        persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "9");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle denied =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasonCode()).isEqualTo("TENANT_ON_HOLD");
        assertThat(denied.message()).contains("OPS_FREEZE");
    }

    @Test
    void coreRuntimeAdmission_cacheEvictionReloadsPolicyAndHandlesMissingTenantGracefully() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "HOLD");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle firstDenied =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));
        assertThat(firstDenied.allowed()).isFalse();
        assertThat(firstDenied.reasonCode()).isEqualTo("TENANT_ON_HOLD");

        persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle stillDeniedFromCache =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));
        assertThat(stillDeniedFromCache.allowed()).isFalse();
        assertThat(stillDeniedFromCache.reasonCode()).isEqualTo("TENANT_ON_HOLD");

        ReflectionTestUtils.invokeMethod(coreRuntimeService, "evictPolicyCache", "ACME");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle allowedAfterEviction =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));
        assertThat(allowedAfterEviction.allowed()).isTrue();
        allowedAfterEviction.close();

        ReflectionTestUtils.invokeMethod(coreRuntimeService, "evictPolicyCache", "UNKNOWN");
        ReflectionTestUtils.invokeMethod(coreRuntimeService, "evictPolicyCache", "   ");
    }

    @Test
    void coreRuntimeAdmission_failsClosed_whenCompanyContextMissingOrUnresolvedId() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle missingContext =
                coreRuntimeService.acquire("   ", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(missingContext.allowed()).isFalse();
        assertThat(missingContext.reasonCode()).isEqualTo("TENANT_CONTEXT_MISSING");

        Company unresolvedId = new Company();
        unresolvedId.setCode("NOID");
        companiesByCode.put("NOID", unresolvedId);
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle unresolved =
                coreRuntimeService.acquire("NOID", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(unresolved.allowed()).isFalse();
        assertThat(unresolved.reasonCode()).isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void coreRuntimeAdmission_enforcesBlockedAndLegacyHoldReasonCodes() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle blocked =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.httpStatus()).isEqualTo(403);
        assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");

        persistedSettingsByKey.put(keyHoldState(1L), "");
        persistedSettingsByKey.put(legacyKey("acme", "state"), "HOLD");
        ReflectionTestUtils.invokeMethod(coreRuntimeService, "evictPolicyCache", "ACME");
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle hold =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));
        assertThat(hold.allowed()).isFalse();
        assertThat(hold.httpStatus()).isEqualTo(423);
        assertThat(hold.reasonCode()).isEqualTo("TENANT_ON_HOLD");
    }

    @Test
    void coreRuntimeAdmission_allowsMutatingRequest_whenActiveAndRateLimitDisabled() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
        persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "0");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle allowed =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));

        assertThat(allowed.allowed()).isTrue();
        allowed.close();
    }

    @Test
    void coreRuntimeAdmission_rateLimitAllowsFirstRequest_thenDeniesSecond() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
        persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "1");

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle firstAllowed =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(firstAllowed.allowed()).isTrue();
        firstAllowed.close();

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle secondDenied =
                coreRuntimeService.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(secondDenied.allowed()).isFalse();
        assertThat(secondDenied.httpStatus()).isEqualTo(429);
        assertThat(secondDenied.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");
    }

    @Test
    void coreRuntimeAdmission_snapshotFallsBackToLegacyTokenMetrics_whenCompanyScopedMetricsMissing() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeMetrics =
                (Map<String, Object>) ReflectionTestUtils.getField(coreRuntimeService, "runtimeMetrics");
        Object legacyMetrics = ReflectionTestUtils.invokeMethod(coreRuntimeService, "createMetrics", "ACME");
        runtimeMetrics.put("acme", legacyMetrics);

        Optional<com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot> snapshot =
                coreRuntimeService.snapshot("ACME");

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().totalRequests()).isZero();
    }

    @Test
    void coreRuntimeAdmission_snapshotAllUsesTenantToken_whenTokenFrequencyIsUnique() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeMetrics =
                (Map<String, Object>) ReflectionTestUtils.getField(coreRuntimeService, "runtimeMetrics");
        Object metrics = ReflectionTestUtils.invokeMethod(coreRuntimeService, "createMetrics", "ACME");
        runtimeMetrics.put("201:acme", metrics);

        Map<String, com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.TenantRuntimeMetricsSnapshot> snapshot =
                coreRuntimeService.snapshotAll();

        assertThat(snapshot).containsKey("acme");
        assertThat(snapshot).doesNotContainKey("201:acme");
    }

    @Test
    void coreRuntimeAdmission_runtimeMetricHelpers_coverNullAndBlankBranches() {
        String keyWithoutCompany = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "runtimeMetricsKey",
                null,
                "acme");
        String tokenForBlank = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "metricsToken",
                "   ");
        String tokenWithoutSeparator = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "metricsToken",
                "acme");
        String tokenWithTrailingSeparator = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "metricsToken",
                "7:");

        assertThat(keyWithoutCompany).isEqualTo("acme");
        assertThat(tokenForBlank).isEqualTo("unknown");
        assertThat(tokenWithoutSeparator).isEqualTo("acme");
        assertThat(tokenWithTrailingSeparator).isEqualTo("7:");
    }

    @Test
    void coreRuntimeAdmission_normalizeTenantToken_returnsUnknown_forNullAndBlank() {
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService = coreRuntimeService();

        String normalizedNull = ReflectionTestUtils.invokeMethod(coreRuntimeService, "normalizeTenantToken", (Object) null);
        String normalizedBlank = ReflectionTestUtils.invokeMethod(coreRuntimeService, "normalizeTenantToken", "   ");

        assertThat(normalizedNull).isEqualTo("unknown");
        assertThat(normalizedBlank).isEqualTo("unknown");
    }

    @Test
    void coreRuntimeAdmission_isMutating_handlesNullBlankSafeMethodsAndPost() {
        Boolean nullMethod = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "isMutating",
                (Object) null);
        Boolean blankMethod = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "isMutating",
                "");
        Boolean headMethod = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "isMutating",
                "HEAD");
        Boolean optionsMethod = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "isMutating",
                "OPTIONS");
        Boolean postMethod = ReflectionTestUtils.invokeMethod(
                com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.class,
                "isMutating",
                "POST");

        assertThat(nullMethod).isTrue();
        assertThat(blankMethod).isTrue();
        assertThat(headMethod).isFalse();
        assertThat(optionsMethod).isFalse();
        assertThat(postMethod).isTrue();
    }

    @Test
    void coreRuntimeAdmission_registersMetricsGauges_whenMeterRegistryIsPresent() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService meteredService =
                new com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService(
                        companyRepository,
                        systemSettingsRepository,
                        auditService,
                        enterpriseAuditTrailService,
                        meterRegistry,
                        0,
                        0,
                        30);

        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService.AccessHandle allowed =
                meteredService.acquire("acme", new MockHttpServletRequest("GET", "/api/v1/private"));
        assertThat(allowed.allowed()).isTrue();
        allowed.close();

        assertThat(meterRegistry.find("tenant.runtime.requests.active").tag("tenant", "ACME").gauge()).isNotNull();
        assertThat(meterRegistry.find("tenant.runtime.requests.total").tag("tenant", "ACME").gauge()).isNotNull();
        assertThat(meterRegistry.find("tenant.runtime.requests.denied").tag("tenant", "ACME").gauge()).isNotNull();
    }

    @Test
    void coreRuntimeAdmission_createMetrics_usesUnknownTag_whenCompanyCodeBlank() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService meteredService =
                new com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService(
                        companyRepository,
                        systemSettingsRepository,
                        auditService,
                        enterpriseAuditTrailService,
                        meterRegistry,
                        0,
                        0,
                        30);

        Object metrics = ReflectionTestUtils.invokeMethod(meteredService, "createMetrics", "   ");
        assertThat(metrics).isNotNull();
        assertThat(meterRegistry.find("tenant.runtime.requests.active").tag("tenant", "UNKNOWN").gauge()).isNotNull();
        assertThat(meterRegistry.find("tenant.runtime.requests.total").tag("tenant", "UNKNOWN").gauge()).isNotNull();
        assertThat(meterRegistry.find("tenant.runtime.requests.denied").tag("tenant", "UNKNOWN").gauge()).isNotNull();
    }

    private com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService coreRuntimeService() {
        return new com.bigbrightpaints.erp.core.security.TenantRuntimeEnforcementService(
                companyRepository,
                systemSettingsRepository,
                auditService,
                enterpriseAuditTrailService,
                null,
                0,
                0,
                30);
    }

    private String keyHoldState(long companyId) {
        return "tenant.runtime.hold-state." + companyId;
    }

    private String keyHoldReason(long companyId) {
        return "tenant.runtime.hold-reason." + companyId;
    }

    private String keyMaxRequestsPerMinute(long companyId) {
        return "tenant.runtime.max-requests-per-minute." + companyId;
    }

    private String keyMaxConcurrentRequests(long companyId) {
        return "tenant.runtime.max-concurrent-requests." + companyId;
    }

    private String keyPolicyReference(long companyId) {
        return "tenant.runtime.policy-reference." + companyId;
    }

    private String keyPolicyUpdatedAt(long companyId) {
        return "tenant.runtime.policy-updated-at." + companyId;
    }

    private String legacyKey(String tenantCodeToken, String suffix) {
        return "tenant.runtime." + tenantCodeToken + "." + suffix;
    }
}
