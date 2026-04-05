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
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventCommand;
import com.bigbrightpaints.erp.core.audittrail.EnterpriseAuditTrailService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeAccessServiceTest {

  private static final long ACME_ID = 1L;

  @Mock private CompanyRepository companyRepository;
  @Mock private SystemSettingsRepository settingsRepository;
  @Mock private AuditService auditService;
  @Mock private EnterpriseAuditTrailService enterpriseAuditTrailService;

  private final Map<String, Company> companies = new HashMap<>();
  private final Map<String, String> settings = new HashMap<>();

  private TenantRuntimeAccessService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantRuntimeAccessService(
            companyRepository,
            settingsRepository,
            auditService,
            enterpriseAuditTrailService,
            null,
            0,
            0,
            60);
    Company acme = new Company();
    ReflectionTestUtils.setField(acme, "id", ACME_ID);
    acme.setCode("ACME");
    companies.put("ACME", acme);

    lenient()
        .when(companyRepository.findByCodeIgnoreCase(any()))
        .thenAnswer(
            invocation -> {
              String code = invocation.getArgument(0, String.class);
              if (code == null) {
                return Optional.empty();
              }
              return Optional.ofNullable(companies.get(code.trim().toUpperCase(Locale.ROOT)));
            });
    lenient()
        .when(settingsRepository.findById(any()))
        .thenAnswer(
            invocation -> {
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
    settings.put(keyHoldState(ACME_ID), "HOLD");
    settings.put(keyHoldReason(ACME_ID), "REVIEW_PENDING");

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales/orders");

    TenantRuntimeAccessService.AccessHandle accessHandle = service.acquire("ACME", request);

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(423);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_ON_HOLD");
    verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), anyMap());
    verify(enterpriseAuditTrailService).recordBusinessEvent(any(AuditActionEventCommand.class));
  }

  @Test
  void holdState_allowsReadOnlyRequests() {
    settings.put(keyHoldState(ACME_ID), "HOLD");

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/reports/trial-balance");

    TenantRuntimeAccessService.AccessHandle accessHandle = service.acquire("ACME", request);

    assertThat(accessHandle.allowed()).isTrue();
    accessHandle.close();

    TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot snapshot =
        service.snapshot("ACME").orElseThrow();
    assertThat(snapshot.allowedRequests()).isEqualTo(1L);
    assertThat(snapshot.deniedRequests()).isZero();
    assertThat(snapshot.activeRequests()).isZero();
  }

  @Test
  void blockedState_blocksReadAndWriteRequests() {
    settings.put(keyHoldState(ACME_ID), "BLOCKED");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

    TenantRuntimeAccessService.AccessHandle accessHandle = service.acquire("ACME", request);

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
  }

  @Test
  void activeState_allowsMutatingRequests_whenRateLimitDisabled() {
    settings.put(keyHoldState(ACME_ID), "ACTIVE");
    settings.put(keyMaxRequestsPerMinute(ACME_ID), "0");

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));

    assertThat(accessHandle.allowed()).isTrue();
    accessHandle.close();

    TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot snapshot =
        service.snapshot("ACME").orElseThrow();
    assertThat(snapshot.totalRequests()).isEqualTo(1L);
    assertThat(snapshot.allowedRequests()).isEqualTo(1L);
    assertThat(snapshot.deniedRequests()).isZero();
    assertThat(snapshot.activeRequests()).isZero();
  }

  @Test
  void requestPerMinuteQuota_isEnforced() {
    settings.put(keyMaxRequestsPerMinute(ACME_ID), "1");

    MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
    MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

    TenantRuntimeAccessService.AccessHandle first = service.acquire("ACME", request1);
    assertThat(first.allowed()).isTrue();
    first.close();

    TenantRuntimeAccessService.AccessHandle second = service.acquire("ACME", request2);
    assertThat(second.allowed()).isFalse();
    assertThat(second.httpStatus()).isEqualTo(429);
    assertThat(second.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");
  }

  @Test
  void concurrentQuota_isEnforced_andReleasedOnClose() {
    settings.put(keyMaxConcurrentRequests(ACME_ID), "1");

    MockHttpServletRequest request1 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");
    MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

    TenantRuntimeAccessService.AccessHandle first = service.acquire("ACME", request1);
    assertThat(first.allowed()).isTrue();

    TenantRuntimeAccessService.AccessHandle second = service.acquire("ACME", request2);
    assertThat(second.allowed()).isFalse();
    assertThat(second.httpStatus()).isEqualTo(429);
    assertThat(second.reasonCode()).isEqualTo("TENANT_QUOTA_CONCURRENCY_LIMIT");

    first.close();
    TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot snapshot =
        service.snapshot("ACME").orElseThrow();
    assertThat(snapshot.activeRequests()).isZero();
  }

  @Test
  void acquire_failsClosed_whenCompanyContextMissing() {
    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("   ", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_CONTEXT_MISSING");
    verifyNoInteractions(
        companyRepository, settingsRepository, auditService, enterpriseAuditTrailService);
  }

  @Test
  void acquire_failsClosed_whenCompanyDoesNotExist() {
    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("NO_SUCH_CO", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_NOT_FOUND");
    verify(companyRepository).findByCodeIgnoreCase("NO_SUCH_CO");
    verifyNoInteractions(auditService, enterpriseAuditTrailService);
  }

  @Test
  void acquire_failsClosed_whenCompanyIdCannotBeResolved() {
    Company unresolved = new Company();
    unresolved.setCode("NOID");
    companies.put("NOID", unresolved);

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("NOID", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_NOT_FOUND");
    verifyNoInteractions(auditService, enterpriseAuditTrailService);
  }

  @Test
  void acquire_failsClosed_whenCompanyLookupThrows() {
    when(companyRepository.findByCodeIgnoreCase("ACME"))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(503);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_COMPANY_LOOKUP_UNAVAILABLE");
  }

  @Test
  void acquire_failsClosed_whenSettingsReadThrows() {
    when(settingsRepository.findById(any()))
        .thenThrow(new RuntimeException("settings-store-down"));

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/auth/me"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(503);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_RUNTIME_POLICY_UNAVAILABLE");
  }

  @Test
  void invalidPersistedHoldState_normalizesToBlockedFailClosed() {
    settings.put(keyHoldState(ACME_ID), "mystery");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/portal/dashboard");

    TenantRuntimeAccessService.AccessHandle accessHandle = service.acquire("ACME", request);

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
  }

  @Test
  void invalidLegacyState_failsClosedToBlocked_whenTenantStateIsPresentButInvalid() {
    settings.put(legacyKey("acme", "state"), "mystery");
    settings.put(legacyKey("default", "state"), "ACTIVE");

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/portal/dashboard"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
  }

  @Test
  void invalidLegacyDefaultState_failsClosedToBlocked_whenTenantStateIsMissing() {
    settings.put(legacyKey("default", "state"), "mystery");

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/portal/dashboard"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
  }

  @Test
  void fallsBackToLegacyTenantCodeKeys_whenCompanyIdKeysAreAbsent() {
    settings.put(legacyKey("acme", "state"), "HOLD");
    settings.put(legacyKey("acme", "reason-code"), "LEGACY_HOLD");

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/private"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(423);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_ON_HOLD");
  }

  @Test
  void metadataOnlyCompanyScopedKeys_doNotBypassLegacyBlockedState() {
    settings.put(legacyKey("acme", "state"), "BLOCKED");
    settings.put(keyPolicyReference(ACME_ID), "policy-v2");
    settings.put(keyPolicyUpdatedAt(ACME_ID), "2026-02-23T10:56:00Z");

    TenantRuntimeAccessService.AccessHandle accessHandle =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

    assertThat(accessHandle.allowed()).isFalse();
    assertThat(accessHandle.httpStatus()).isEqualTo(403);
    assertThat(accessHandle.reasonCode()).isEqualTo("TENANT_BLOCKED");
  }

  @Test
  void policyCache_isolatedByCompanyId_whenNormalizedCompanyTokensCollide() {
    long firstCompanyId = 11L;
    long secondCompanyId = 12L;
    Company first = new Company();
    ReflectionTestUtils.setField(first, "id", firstCompanyId);
    first.setCode("ACME INC");
    companies.put("ACME INC", first);
    Company second = new Company();
    ReflectionTestUtils.setField(second, "id", secondCompanyId);
    second.setCode("ACME@INC");
    companies.put("ACME@INC", second);

    settings.put(keyHoldState(firstCompanyId), "BLOCKED");
    settings.put(keyHoldState(secondCompanyId), "ACTIVE");

    TenantRuntimeAccessService.AccessHandle blocked =
        service.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(blocked.allowed()).isFalse();
    assertThat(blocked.reasonCode()).isEqualTo("TENANT_BLOCKED");

    TenantRuntimeAccessService.AccessHandle allowed =
        service.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(allowed.allowed()).isTrue();
    allowed.close();
  }

  @Test
  void runtimeQuotasAndCounters_areIsolatedByCompanyId_whenNormalizedCompanyTokensCollide() {
    long firstCompanyId = 21L;
    long secondCompanyId = 22L;
    Company first = new Company();
    ReflectionTestUtils.setField(first, "id", firstCompanyId);
    first.setCode("ACME INC");
    companies.put("ACME INC", first);
    Company second = new Company();
    ReflectionTestUtils.setField(second, "id", secondCompanyId);
    second.setCode("ACME@INC");
    companies.put("ACME@INC", second);

    settings.put(keyMaxRequestsPerMinute(firstCompanyId), "1");
    settings.put(keyMaxRequestsPerMinute(secondCompanyId), "1");

    TenantRuntimeAccessService.AccessHandle firstAllowed =
        service.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(firstAllowed.allowed()).isTrue();
    firstAllowed.close();

    TenantRuntimeAccessService.AccessHandle secondAllowed =
        service.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(secondAllowed.allowed()).isTrue();
    secondAllowed.close();

    TenantRuntimeAccessService.AccessHandle firstDenied =
        service.acquire("ACME INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(firstDenied.allowed()).isFalse();
    assertThat(firstDenied.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");

    TenantRuntimeAccessService.AccessHandle secondDenied =
        service.acquire("ACME@INC", new MockHttpServletRequest("GET", "/api/v1/private"));
    assertThat(secondDenied.allowed()).isFalse();
    assertThat(secondDenied.reasonCode()).isEqualTo("TENANT_QUOTA_RATE_LIMIT");

    TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot firstSnapshot =
        service.snapshot("ACME INC").orElseThrow();
    TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot secondSnapshot =
        service.snapshot("ACME@INC").orElseThrow();
    assertThat(firstSnapshot.totalRequests()).isEqualTo(2L);
    assertThat(firstSnapshot.allowedRequests()).isEqualTo(1L);
    assertThat(firstSnapshot.deniedRequests()).isEqualTo(1L);
    assertThat(secondSnapshot.totalRequests()).isEqualTo(2L);
    assertThat(secondSnapshot.allowedRequests()).isEqualTo(1L);
    assertThat(secondSnapshot.deniedRequests()).isEqualTo(1L);

    Map<String, TenantRuntimeAccessService.TenantRuntimeMetricsSnapshot> allSnapshots =
        service.snapshotAll();
    assertThat(allSnapshots).containsKeys("21:acme_inc", "22:acme_inc");
  }

  @Test
  void snapshotAndSnapshotAll_returnEmptyUntilTenantObserved() {
    assertThat(service.snapshot(null)).isEmpty();
    assertThat(service.snapshot("ACME")).isEmpty();
    assertThat(service.snapshotAll()).isEmpty();

    TenantRuntimeAccessService.AccessHandle allowed =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/portal/dashboard"));
    assertThat(allowed.allowed()).isTrue();
    allowed.close();

    assertThat(service.snapshotAll()).containsKey("acme");
    assertThat(service.snapshot("ACME")).isPresent();
  }

  @Test
  void evictPolicyCache_forcesPolicyReloadOnNextRequest() {
    settings.put(keyHoldState(ACME_ID), "HOLD");

    TenantRuntimeAccessService.AccessHandle deniedWhileCached =
        service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
    assertThat(deniedWhileCached.allowed()).isFalse();
    assertThat(deniedWhileCached.reasonCode()).isEqualTo("TENANT_ON_HOLD");

    settings.put(keyHoldState(ACME_ID), "ACTIVE");
    TenantRuntimeAccessService.AccessHandle stillDeniedFromCache =
        service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
    assertThat(stillDeniedFromCache.allowed()).isFalse();
    assertThat(stillDeniedFromCache.reasonCode()).isEqualTo("TENANT_ON_HOLD");

    service.evictPolicyCache(" ACME ");
    TenantRuntimeAccessService.AccessHandle allowedAfterEviction =
        service.acquire("ACME", new MockHttpServletRequest("POST", "/api/v1/sales/orders"));
    assertThat(allowedAfterEviction.allowed()).isTrue();
    allowedAfterEviction.close();
  }

  @Test
  void denyPath_continuesWhenLegacyAuditWriteFails_andRestoresContext() {
    CompanyContextHolder.setCompanyCode("PREVIOUS_COMPANY");
    settings.put(keyHoldState(ACME_ID), "BLOCKED");
    doThrow(new RuntimeException("legacy-audit-down"))
        .when(auditService)
        .logFailure(eq(AuditEvent.ACCESS_DENIED), anyMap());

    TenantRuntimeAccessService.AccessHandle denied =
        service.acquire("ACME", new MockHttpServletRequest("GET", "/api/v1/private"));

    assertThat(denied.allowed()).isFalse();
    assertThat(denied.reasonCode()).isEqualTo("TENANT_BLOCKED");
    assertThat(CompanyContextHolder.getCompanyCode()).isEqualTo("PREVIOUS_COMPANY");
    verify(enterpriseAuditTrailService).recordBusinessEvent(any(AuditActionEventCommand.class));
  }

  @Test
  void normalizeTenantToken_returnsUnknown_forNullOrBlank() {
    String tokenForNull =
        ReflectionTestUtils.invokeMethod(service, "normalizeTenantToken", (Object) null);
    String tokenForBlank = ReflectionTestUtils.invokeMethod(service, "normalizeTenantToken", "   ");

    assertThat(tokenForNull).isEqualTo("unknown");
    assertThat(tokenForBlank).isEqualTo("unknown");
  }

  @Test
  void isMutating_treatsHeadAndOptionsAsNonMutating_andBlankAsMutating() {
    Boolean head = ReflectionTestUtils.invokeMethod(service, "isMutating", "HEAD");
    Boolean options = ReflectionTestUtils.invokeMethod(service, "isMutating", "OPTIONS");
    Boolean blank = ReflectionTestUtils.invokeMethod(service, "isMutating", "   ");

    assertThat(head).isFalse();
    assertThat(options).isFalse();
    assertThat(blank).isTrue();
  }

  @Test
  void acquire_registersMetrics_whenMeterRegistryPresent() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TenantRuntimeAccessService meteredService =
        new TenantRuntimeAccessService(
            companyRepository,
            settingsRepository,
            auditService,
            enterpriseAuditTrailService,
            meterRegistry,
            0,
            0,
            60);

    TenantRuntimeAccessService.AccessHandle accessHandle =
        meteredService.acquire(
            "ACME", new MockHttpServletRequest("GET", "/api/v1/portal/dashboard"));
    assertThat(accessHandle.allowed()).isTrue();
    accessHandle.close();

    Gauge activeGauge =
        meterRegistry.find("tenant.runtime.requests.active").tag("tenant", "ACME").gauge();
    Gauge totalGauge =
        meterRegistry.find("tenant.runtime.requests.total").tag("tenant", "ACME").gauge();
    Gauge deniedGauge =
        meterRegistry.find("tenant.runtime.requests.denied").tag("tenant", "ACME").gauge();

    assertThat(activeGauge).isNotNull();
    assertThat(totalGauge).isNotNull();
    assertThat(deniedGauge).isNotNull();
    assertThat(activeGauge.value()).isZero();
    assertThat(totalGauge.value()).isEqualTo(1.0d);
    assertThat(deniedGauge.value()).isZero();
  }

  private String keyHoldState(long companyId) {
    return "tenant.runtime.hold-state." + companyId;
  }

  private String keyHoldReason(long companyId) {
    return "tenant.runtime.hold-reason." + companyId;
  }

  private String keyMaxConcurrentRequests(long companyId) {
    return "tenant.runtime.max-concurrent-requests." + companyId;
  }

  private String keyMaxRequestsPerMinute(long companyId) {
    return "tenant.runtime.max-requests-per-minute." + companyId;
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
