package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.AuthSecurityContractException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementServiceTest {

  @Mock private CompanyRepository companyRepository;

  @Mock private UserAccountRepository userAccountRepository;

  @Mock private SystemSettingsRepository systemSettingsRepository;

  @Mock private AuditService auditService;

  private final Map<String, Company> companiesByCode = new HashMap<>();
  private final Map<Long, Long> activeUsersByCompanyId = new HashMap<>();
  private final Map<String, String> persistedSettingsByKey = new HashMap<>();

  private TenantRuntimeEnforcementService service;
  private TenantRuntimeRequestAdmissionService admissionService;

  @BeforeEach
  void setUp() {
    CompanyClock fixedClock = org.mockito.Mockito.mock(CompanyClock.class);
    lenient().when(fixedClock.now(any())).thenReturn(Instant.parse("2026-01-01T00:00:10Z"));
    ReflectionTestUtils.setField(CompanyTime.class, "companyClock", fixedClock);

    Company acme = company(1L, "ACME");
    companiesByCode.put("ACME", acme);
    activeUsersByCompanyId.put(1L, 1L);

    service =
        new TenantRuntimeEnforcementService(
            companyRepository,
            systemSettingsRepository,
            userAccountRepository,
            auditService,
            3,
            3,
            3,
            60);
    admissionService = new TenantRuntimeRequestAdmissionService(service);

    lenient()
        .when(companyRepository.findByCodeIgnoreCase(any()))
        .thenAnswer(
            invocation -> {
              String code = invocation.getArgument(0, String.class);
              if (code == null) {
                return Optional.empty();
              }
              return Optional.ofNullable(companiesByCode.get(code.trim().toUpperCase(Locale.ROOT)));
            });
    lenient()
        .when(userAccountRepository.countByCompany_IdAndEnabledTrue(anyLong()))
        .thenAnswer(
            invocation -> {
              Long companyId = invocation.getArgument(0, Long.class);
              return activeUsersByCompanyId.getOrDefault(companyId, 0L);
            });
    lenient()
        .when(systemSettingsRepository.findById(any()))
        .thenAnswer(
            invocation -> {
              String key = invocation.getArgument(0, String.class);
              String value = persistedSettingsByKey.get(key);
              if (value == null) {
                return Optional.empty();
              }
              return Optional.of(new SystemSetting(key, value));
            });
    lenient()
        .when(systemSettingsRepository.save(any(SystemSetting.class)))
        .thenAnswer(
            invocation -> {
              SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
              persistedSettingsByKey.put(setting.getKey(), setting.getValue());
              return setting;
            });
    lenient()
        .doAnswer(
            invocation -> {
              String key = invocation.getArgument(0, String.class);
              persistedSettingsByKey.remove(key);
              return null;
            })
        .when(systemSettingsRepository)
        .deleteById(any());
  }

  @AfterEach
  void tearDown() {
    ReflectionTestUtils.setField(CompanyTime.class, "companyClock", null);
  }

  @Test
  void beginRequest_doesNotTrackWhenCompanyContextMissing() {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        admissionService.beginRequest("   ", "/api/v1/auth/me", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isFalse();
    assertThat(admission.statusCode()).isEqualTo(200);
    assertThat(admission.companyCode()).isNull();
    assertThat(admission.auditChainId()).isNull();
    verifyNoInteractions(companyRepository, auditService, userAccountRepository);
  }

  @Test
  void parsePositiveInt_returnsFallbackWhenValueOverflowsInteger() {
    Integer parsed = ReflectionTestUtils.invokeMethod(service, "parsePositiveInt", "2147483648", 3);

    assertThat(parsed).isEqualTo(3);
  }

  @Test
  void completeRequest_ignoresNullAndNotAdmittedHandles() {
    admissionService.completeRequest(null, 503);
    admissionService.completeRequest(
        TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked(), 503);

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");
    assertThat(snapshot.metrics().errorResponses()).isZero();
    assertThat(snapshot.metrics().inFlightRequests()).isZero();
  }

  @Test
  void snapshot_usesBootstrapReferenceAndConfiguredDefaults_whenPolicyIsUnset() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");

    assertThat(snapshot.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(snapshot.reasonCode()).isEqualTo("POLICY_ACTIVE");
    assertThat(snapshot.auditChainId()).isEqualTo("bootstrap");
    assertThat(snapshot.updatedAt()).isNull();
    assertThat(snapshot.maxConcurrentRequests()).isEqualTo(3);
    assertThat(snapshot.maxRequestsPerMinute()).isEqualTo(3);
    assertThat(snapshot.maxActiveUsers()).isEqualTo(3);
  }

  @Test
  void holdTenant_rejectsNewRequests_withLockedStatusAndAuditFailure() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot holdSnapshot =
        service.holdTenant("ACME", "compliance_review", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission rejected =
        admissionService.beginRequest("ACME", "/api/v1/auth/me", "post", "actor@bbp.com");

    assertThat(holdSnapshot.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
    assertThat(holdSnapshot.reasonCode()).isEqualTo("COMPLIANCE_REVIEW");
    assertThat(rejected.isAdmitted()).isFalse();
    assertThat(rejected.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
    assertThat(rejected.message()).isEqualTo("Tenant is currently on hold");
    assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
    verify(auditService)
        .logAuthFailure(eq(AuditEvent.ACCESS_DENIED), eq("ACTOR@BBP.COM"), eq("ACME"), anyMap());
  }

  @Test
  void holdTenant_allowsReadOnlyRequests_throughRequestPathEnforcement() {
    service.holdTenant("ACME", "compliance_review", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    admissionService.completeRequest(admission, 200);
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");

    assertThat(admission.isAdmitted()).isTrue();
    assertThat(snapshot.metrics().totalRequests()).isEqualTo(1L);
    assertThat(snapshot.metrics().rejectedRequests()).isEqualTo(0L);
    assertThat(snapshot.metrics().inFlightRequests()).isEqualTo(0);
  }

  @Test
  void beginRequest_allowsTenantRuntimePolicyControlPath_whenHeldOrBlocked() {
    service.holdTenant("ACME", "maintenance_hold", "ops@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission heldAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    admissionService.completeRequest(heldAdmission, 200);

    service.blockTenant("ACME", "incident_block", "ops@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmissionWithContextPath =
        admissionService.beginRequest(
            "ACME", "/erp/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedMalformedPrefixAdmission =
        admissionService.beginRequest(
            "ACME", "/erpapi/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedUnprivilegedControl =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", false);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedPolicyRead =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "GET", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission retiredAdminPath =
        admissionService.beginRequest(
            "ACME", "/api/v1/admin/tenant-runtime/policy", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedNonControl =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "ops@bbp.com");
    admissionService.completeRequest(blockedAdmission, 200);

    assertThat(heldAdmission.isAdmitted()).isTrue();
    assertThat(blockedAdmission.isAdmitted()).isTrue();
    assertThat(blockedAdmissionWithContextPath.isAdmitted()).isFalse();
    assertThat(blockedAdmissionWithContextPath.statusCode())
        .isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedMalformedPrefixAdmission.isAdmitted()).isFalse();
    assertThat(blockedMalformedPrefixAdmission.statusCode())
        .isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedUnprivilegedControl.isAdmitted()).isFalse();
    assertThat(blockedUnprivilegedControl.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedPolicyRead.isAdmitted()).isFalse();
    assertThat(blockedPolicyRead.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(retiredAdminPath.isAdmitted()).isFalse();
    assertThat(retiredAdminPath.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedNonControl.isAdmitted()).isFalse();
    assertThat(blockedNonControl.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedNonControl.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void beginRequest_policyControlPathBypassesRateAndConcurrencyQuotas() {
    service.updateQuotas("ACME", 1, 1, 10, "quota_test", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    admissionService.completeRequest(first, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission quotaRejected =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    admissionService.completeRequest(controlAdmission, 200);

    assertThat(first.isAdmitted()).isTrue();
    assertThat(quotaRejected.isAdmitted()).isFalse();
    assertThat(quotaRejected.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(controlAdmission.isAdmitted()).isTrue();
    TenantRuntimeEnforcementService.TenantRequestAdmission lifecycleAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/lifecycle", "PUT", "ops@bbp.com", true);
    assertThat(lifecycleAdmission.isAdmitted()).isTrue();
  }

  @Test
  void blockTenant_rejectsAuthOperations_withForbiddenStatus() {
    service.blockTenant("ACME", "abuse_incident", "ops@bbp.com");

    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("ACME", "auth-op@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant is currently blocked");
            });

    assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
  }

  @Test
  void enforceAuthOperationAllowed_failClosedWhenCompanyNotFound() {
    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("UNKNOWN", "actor@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(ex.getCode()).isEqualTo("TENANT_NOT_FOUND");
              assertThat(ex.getUserMessage()).isEqualTo("Tenant not found");
            });
  }

  @Test
  void enforceAuthOperationAllowed_failClosedWhenCompanyLookupUnavailableAfterPolicyWarmup() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot warmed = service.snapshot("ACME");
    assertThat(warmed.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    when(companyRepository.findByCodeIgnoreCase(eq("ACME")))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getCode()).isEqualTo("TENANT_COMPANY_LOOKUP_UNAVAILABLE");
              assertThat(ex.getUserMessage()).isEqualTo("Tenant company lookup is unavailable");
            });
  }

  @Test
  void enforceAuthOperationAllowed_failClosedWhenActiveUserQuotaLookupUnavailable() {
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(eq(1L)))
        .thenThrow(new RuntimeException("active-user-count-unavailable"));

    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getCode()).isEqualTo("TENANT_ACTIVE_USER_QUOTA_UNAVAILABLE");
              assertThat(ex.getUserMessage()).isEqualTo("Tenant active-user quota is unavailable");
            });
  }

  @Test
  void holdTenant_failClosedWhenCompanyNotFound() {
    assertThatThrownBy(() -> service.holdTenant("UNKNOWN", "ops_hold", "ops@bbp.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company not found: UNKNOWN");
  }

  @Test
  void enforceAuthOperationAllowed_onHeldTenantRejectsWithoutActiveUserLookup() {
    service.holdTenant("ACME", "compliance_pause", "ops@bbp.com");
    clearInvocations(userAccountRepository);

    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.LOCKED);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant is currently on hold");
            });

    verifyNoInteractions(userAccountRepository);
  }

  @Test
  void enforceAuthOperationAllowed_cachedPolicyStillTreatsMissingTenantAsNotFound() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot warmed = service.snapshot("ACME");
    assertThat(warmed.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    companiesByCode.remove("ACME");

    assertThatThrownBy(
            () -> admissionService.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(ex.getCode()).isEqualTo("TENANT_NOT_FOUND");
              assertThat(ex.getUserMessage()).isEqualTo("Tenant not found");
              assertThat(ex.getDetails()).containsEntry("reason", "TENANT_NOT_FOUND");
            });
  }

  @Test
  void beginRequest_rejectsWhenRateQuotaExceeded() {
    service.updateQuotas("ACME", 10, 1, 10, "rate_test", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        admissionService.beginRequest("ACME", "/api/v1/auth/me", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        admissionService.beginRequest("ACME", "/api/v1/auth/me", "GET", "actor@bbp.com");

    admissionService.completeRequest(first, 200);
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");

    assertThat(first.isAdmitted()).isTrue();
    assertThat(second.isAdmitted()).isFalse();
    assertThat(second.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(second.message()).isEqualTo("Tenant request rate quota exceeded");
    assertThat(snapshot.metrics().totalRequests()).isEqualTo(1L);
    assertThat(snapshot.metrics().rejectedRequests()).isEqualTo(1L);
    assertThat(snapshot.metrics().minuteRequestCount()).isEqualTo(2);
    assertThat(snapshot.metrics().inFlightRequests()).isEqualTo(0);
  }

  @Test
  void beginRequest_appliesPersistedPolicyFromSystemSettings() {
    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-01");

    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isFalse();
    assertThat(admission.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(admission.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void beginRequest_failsClosedWhenSettingsReadFailsDuringRefresh() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);
    expireCachedPolicyRefreshDeadline("ACME");

    when(systemSettingsRepository.findById(any()))
        .thenThrow(new RuntimeException("settings-unavailable"));

    TenantRuntimeEnforcementService.TenantRequestAdmission deniedAdmission =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(deniedAdmission.isAdmitted()).isFalse();
    assertThat(deniedAdmission.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(deniedAdmission.reasonCode()).isEqualTo("TENANT_RUNTIME_POLICY_UNAVAILABLE");
    assertThat(deniedAdmission.message()).isEqualTo("Tenant runtime policy is unavailable");
  }

  @Test
  void updatePolicy_translatesSettingsReadFailureToControlledServiceUnavailable() {
    when(systemSettingsRepository.findById(any()))
        .thenThrow(new RuntimeException("settings-unavailable"));

    assertThatThrownBy(
            () ->
                service.updatePolicy(
                    "ACME",
                    TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                    "incident_lockdown",
                    5,
                    7,
                    9,
                    "ops@bbp.com"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant runtime policy is unavailable");
              assertThat(ex.getDetails())
                  .containsEntry("companyCode", "ACME")
                  .containsEntry("reason", "TENANT_RUNTIME_POLICY_UNAVAILABLE");
            });
  }

  @Test
  void updatePolicy_translatesCompanyLookupFailureToControlledServiceUnavailable() {
    when(companyRepository.findByCodeIgnoreCase(eq("ACME")))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    assertThatThrownBy(
            () ->
                service.updatePolicy(
                    "ACME",
                    TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                    "incident_lockdown",
                    5,
                    7,
                    9,
                    "ops@bbp.com"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant company lookup is unavailable");
              assertThat(ex.getDetails())
                  .containsEntry("companyCode", "ACME")
                  .containsEntry("reason", "TENANT_COMPANY_LOOKUP_UNAVAILABLE");
            });
  }

  @Test
  void snapshot_translatesCompanyLookupFailureOnCachedPolicyToControlledServiceUnavailable() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot warmed = service.snapshot("ACME");
    assertThat(warmed.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);

    when(companyRepository.findByCodeIgnoreCase(eq("ACME")))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    assertThatThrownBy(() -> service.snapshot("ACME"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant company lookup is unavailable");
              assertThat(ex.getDetails())
                  .containsEntry("companyCode", "ACME")
                  .containsEntry("reason", "TENANT_COMPANY_LOOKUP_UNAVAILABLE");
            });
  }

  @Test
  void snapshot_translatesSettingsReadFailureToControlledServiceUnavailable() {
    when(systemSettingsRepository.findById(any()))
        .thenThrow(new RuntimeException("settings-unavailable"));

    assertThatThrownBy(() -> service.snapshot("ACME"))
        .isInstanceOf(ApplicationException.class)
        .satisfies(
            error -> {
              ApplicationException ex = (ApplicationException) error;
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant runtime policy is unavailable");
              assertThat(ex.getDetails())
                  .containsEntry("companyCode", "ACME")
                  .containsEntry("reason", "TENANT_RUNTIME_POLICY_UNAVAILABLE");
            });
  }

  @Test
  void beginRequest_failsClosedWhenCompanyLookupFails() {
    when(companyRepository.findByCodeIgnoreCase(eq("ACME")))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isFalse();
    assertThat(admission.statusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(admission.reasonCode()).isEqualTo("TENANT_COMPANY_LOOKUP_UNAVAILABLE");
    assertThat(admission.message()).isEqualTo("Tenant company lookup is unavailable");
  }

  @Test
  void beginRequest_usesCachedPolicy_withoutReloadingPersistedSettingsPerRequest() {
    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-01");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(first.isAdmitted()).isFalse();
    clearInvocations(systemSettingsRepository, companyRepository);

    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(second.isAdmitted()).isFalse();
    assertThat(second.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    verifyNoInteractions(systemSettingsRepository, companyRepository);
  }

  @Test
  void completeRequest_policyControlSuccessInvalidatesCachedPolicyImmediately() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-02");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    TenantRuntimeEnforcementService.TenantRequestAdmission staleAllowed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(staleAllowed.isAdmitted()).isTrue();
    admissionService.completeRequest(staleAllowed, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();
    admissionService.completeRequest(controlAdmission, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAfterInvalidate =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(blockedAfterInvalidate.isAdmitted()).isFalse();
    assertThat(blockedAfterInvalidate.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedAfterInvalidate.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void completeRequest_policyControlErrorDoesNotInvalidateCachedPolicy() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-error");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();

    admissionService.completeRequest(controlAdmission, 500);

    TenantRuntimeEnforcementService.TenantRequestAdmission stillUsingCachedPolicy =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillUsingCachedPolicy.isAdmitted()).isTrue();
  }

  @Test
  void snapshot_waitsForPolicyMutationToFinishBeforeRefreshingExpiredPolicy() throws Exception {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot warmed = service.snapshot("ACME");
    assertThat(warmed.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    expireCachedPolicyRefreshDeadline("ACME");

    CountDownLatch firstPersistedWrite = new CountDownLatch(1);
    CountDownLatch allowPersistToContinue = new CountDownLatch(1);
    AtomicBoolean blockedFirstWrite = new AtomicBoolean();
    when(systemSettingsRepository.save(any(SystemSetting.class)))
        .thenAnswer(
            invocation -> {
              SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
              persistedSettingsByKey.put(setting.getKey(), setting.getValue());
              if (blockedFirstWrite.compareAndSet(false, true)) {
                firstPersistedWrite.countDown();
                if (!allowPersistToContinue.await(5, TimeUnit.SECONDS)) {
                  throw new AssertionError("Timed out waiting to resume policy persistence");
                }
              }
              return setting;
            });

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<TenantRuntimeEnforcementService.TenantRuntimeSnapshot> updateFuture =
          executor.submit(
              () ->
                  service.updatePolicy(
                      "ACME",
                      TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                      "incident_lock",
                      9,
                      11,
                      13,
                      "ops@bbp.com"));
      assertThat(firstPersistedWrite.await(5, TimeUnit.SECONDS)).isTrue();

      Future<TenantRuntimeEnforcementService.TenantRuntimeSnapshot> snapshotFuture =
          executor.submit(() -> service.snapshot("ACME"));

      assertThatThrownBy(() -> snapshotFuture.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowPersistToContinue.countDown();
      TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
          updateFuture.get(5, TimeUnit.SECONDS);
      TenantRuntimeEnforcementService.TenantRuntimeSnapshot refreshed =
          snapshotFuture.get(5, TimeUnit.SECONDS);

      assertThat(refreshed.state()).isEqualTo(updated.state());
      assertThat(refreshed.reasonCode()).isEqualTo(updated.reasonCode());
      assertThat(refreshed.maxConcurrentRequests()).isEqualTo(updated.maxConcurrentRequests());
      assertThat(refreshed.maxRequestsPerMinute()).isEqualTo(updated.maxRequestsPerMinute());
      assertThat(refreshed.maxActiveUsers()).isEqualTo(updated.maxActiveUsers());
      assertThat(refreshed.auditChainId()).isEqualTo(updated.auditChainId());
    } finally {
      allowPersistToContinue.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void canonicalUpdatePolicy_persistsSettings_and_survivesPolicyControlInvalidation() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
        service.updatePolicy(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "incident_lockdown",
            5,
            7,
            9,
            "ops@bbp.com");

    assertThat(updated.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
    assertThat(persistedSettingsByKey.get(keyHoldState(1L))).isEqualTo("BLOCKED");
    assertThat(persistedSettingsByKey.get(keyHoldReason(1L))).isEqualTo("INCIDENT_LOCKDOWN");
    assertThat(persistedSettingsByKey.get(keyMaxConcurrentRequests(1L))).isEqualTo("5");
    assertThat(persistedSettingsByKey.get(keyMaxRequestsPerMinute(1L))).isEqualTo("7");
    assertThat(persistedSettingsByKey.get(keyMaxActiveUsers(1L))).isEqualTo("9");
    assertThat(persistedSettingsByKey.get(keyPolicyReference(1L)))
        .isEqualTo(updated.auditChainId());
    assertThat(persistedSettingsByKey.get(keyPolicyUpdatedAt(1L)))
        .isEqualTo(updated.updatedAt().toString());

    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();
    admissionService.completeRequest(controlAdmission, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAfterInvalidate =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(blockedAfterInvalidate.isAdmitted()).isFalse();
    assertThat(blockedAfterInvalidate.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedAfterInvalidate.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void updatePolicy_persistFailureLeavesPriorLivePolicyIntact() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    when(systemSettingsRepository.save(any(SystemSetting.class)))
        .thenThrow(new RuntimeException("settings-write-failed"));

    assertThatThrownBy(
            () ->
                service.updatePolicy(
                    "ACME",
                    TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                    "incident_lockdown",
                    5,
                    7,
                    9,
                    "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("settings-write-failed");

    TenantRuntimeEnforcementService.TenantRequestAdmission stillAllowed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillAllowed.isAdmitted()).isTrue();
    assertThat(persistedSettingsByKey).isEmpty();
  }

  @Test
  void updatePolicy_auditFailureLeavesPriorLivePolicyIntact() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    org.mockito.Mockito.doThrow(new RuntimeException("audit-write-failed"))
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(
            () ->
                service.updatePolicy(
                    "ACME",
                    TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                    "incident_lockdown",
                    5,
                    7,
                    9,
                    "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    TenantRuntimeEnforcementService.TenantRequestAdmission stillAllowed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillAllowed.isAdmitted()).isTrue();
    assertThat(persistedSettingsByKey).isEmpty();
  }

  @Test
  void updateQuotas_auditFailureRestoresPersistedPolicyState() {
    persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
    persistedSettingsByKey.put(keyHoldReason(1L), "POLICY_ACTIVE");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "3");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "3");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "3");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-policy-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-01T00:00:00Z");
    Map<String, String> persistedBefore = new HashMap<>(persistedSettingsByKey);

    org.mockito.Mockito.doThrow(new RuntimeException("audit-write-failed"))
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 9, 11, 13, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    assertThat(persistedSettingsByKey).containsExactlyInAnyOrderEntriesOf(persistedBefore);
  }

  @Test
  void updateQuotas_persistFailureRestoresPreviouslyPersistedPolicyState() {
    persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
    persistedSettingsByKey.put(keyHoldReason(1L), "POLICY_ACTIVE");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "3");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "3");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "3");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-policy-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-01T00:00:00Z");
    Map<String, String> persistedBefore = new HashMap<>(persistedSettingsByKey);
    int[] saveCalls = {0};

    org.mockito.Mockito.doAnswer(
            invocation -> {
              SystemSetting setting = invocation.getArgument(0, SystemSetting.class);
              saveCalls[0]++;
              if (saveCalls[0] == 4) {
                throw new RuntimeException("persist-write-failed");
              }
              persistedSettingsByKey.put(setting.getKey(), setting.getValue());
              return setting;
            })
        .when(systemSettingsRepository)
        .save(any(SystemSetting.class));

    assertThatThrownBy(() -> service.updateQuotas("ACME", 9, 11, 13, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("persist-write-failed");

    assertThat(persistedSettingsByKey).containsExactlyInAnyOrderEntriesOf(persistedBefore);
  }

  @Test
  void updateQuotas_auditFailurePersistsExistingLiveHoldStateAcrossCacheInvalidation() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot held =
        service.holdTenant("ACME", "manual-hold", "ops@bbp.com");

    org.mockito.Mockito.doThrow(new RuntimeException("audit-write-failed"))
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 9, 11, 13, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    service.invalidatePolicyCache("ACME");
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot restored = service.snapshot("ACME");

    assertThat(restored.state()).isEqualTo(held.state());
    assertThat(restored.reasonCode()).isEqualTo(held.reasonCode());
    assertThat(restored.maxConcurrentRequests()).isEqualTo(held.maxConcurrentRequests());
    assertThat(restored.maxRequestsPerMinute()).isEqualTo(held.maxRequestsPerMinute());
    assertThat(restored.maxActiveUsers()).isEqualTo(held.maxActiveUsers());
  }

  @Test
  void updateQuotas_auditFailureDoesNotOverwriteNewerPersistedPolicyWithCacheOnlyState() {
    service.holdTenant("ACME", "manual-hold", "ops@bbp.com");
    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "INCIDENT_LOCK");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "7");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "9");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "11");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-policy-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    org.mockito.Mockito.doThrow(new RuntimeException("audit-write-failed"))
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 13, 15, 17, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot immediate = service.snapshot("ACME");
    service.invalidatePolicyCache("ACME");
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot restored = service.snapshot("ACME");

    assertThat(immediate.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
    assertThat(immediate.reasonCode()).isEqualTo("INCIDENT_LOCK");
    assertThat(immediate.maxConcurrentRequests()).isEqualTo(7);
    assertThat(immediate.maxRequestsPerMinute()).isEqualTo(9);
    assertThat(immediate.maxActiveUsers()).isEqualTo(11);
    assertThat(restored.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
    assertThat(restored.reasonCode()).isEqualTo("INCIDENT_LOCK");
    assertThat(restored.maxConcurrentRequests()).isEqualTo(7);
    assertThat(restored.maxRequestsPerMinute()).isEqualTo(9);
    assertThat(restored.maxActiveUsers()).isEqualTo(11);
  }

  @Test
  void updateQuotas_auditFailurePreservesNewerPersistedPolicyWrittenAfterSnapshot() {
    persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
    persistedSettingsByKey.put(keyHoldReason(1L), "POLICY_ACTIVE");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "3");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "3");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "3");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-policy-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-01T00:00:00Z");

    org.mockito.Mockito.doAnswer(
            invocation -> {
              persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
              persistedSettingsByKey.put(keyHoldReason(1L), "INCIDENT_LOCK");
              persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "7");
              persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "9");
              persistedSettingsByKey.put(keyMaxActiveUsers(1L), "11");
              persistedSettingsByKey.put(keyPolicyReference(1L), "newer-policy-ref");
              persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");
              throw new RuntimeException("audit-write-failed");
            })
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 13, 15, 17, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot restored = service.snapshot("ACME");

    assertThat(restored.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
    assertThat(restored.reasonCode()).isEqualTo("INCIDENT_LOCK");
    assertThat(restored.maxConcurrentRequests()).isEqualTo(7);
    assertThat(restored.maxRequestsPerMinute()).isEqualTo(9);
    assertThat(restored.maxActiveUsers()).isEqualTo(11);
  }

  @Test
  void updateQuotas_auditFailurePreservesConcurrentEquivalentPolicyWithNewMetadata() {
    persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
    persistedSettingsByKey.put(keyHoldReason(1L), "POLICY_ACTIVE");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "3");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "3");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "3");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-policy-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-01T00:00:00Z");

    Instant concurrentUpdatedAt = Instant.parse("2026-02-20T10:16:30Z");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              persistedSettingsByKey.put(keyHoldState(1L), "ACTIVE");
              persistedSettingsByKey.put(keyHoldReason(1L), "RATCHET");
              persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "13");
              persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "15");
              persistedSettingsByKey.put(keyMaxActiveUsers(1L), "17");
              persistedSettingsByKey.put(keyPolicyReference(1L), "concurrent-policy-ref");
              persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), concurrentUpdatedAt.toString());
              throw new RuntimeException("audit-write-failed");
            })
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 13, 15, 17, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot restored = service.snapshot("ACME");

    assertThat(restored.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(restored.reasonCode()).isEqualTo("RATCHET");
    assertThat(restored.maxConcurrentRequests()).isEqualTo(13);
    assertThat(restored.maxRequestsPerMinute()).isEqualTo(15);
    assertThat(restored.maxActiveUsers()).isEqualTo(17);
    assertThat(restored.auditChainId()).isEqualTo("concurrent-policy-ref");
    assertThat(restored.updatedAt()).isEqualTo(concurrentUpdatedAt);
  }

  @Test
  void updateQuotas_auditFailurePreservesPersistedResumeState() {
    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "PERSISTED_BLOCK");
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "5");
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "7");
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "9");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-block-ref");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot resumed =
        service.resumeTenant("ACME", "ops@bbp.com");
    assertThat(resumed.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);

    org.mockito.Mockito.doThrow(new RuntimeException("audit-write-failed"))
        .when(auditService)
        .logAuthSuccess(eq(AuditEvent.CONFIGURATION_CHANGED), any(), eq("ACME"), anyMap());

    assertThatThrownBy(() -> service.updateQuotas("ACME", 13, 15, 17, "ratchet", "ops@bbp.com"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("audit-write-failed");

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot restored = service.snapshot("ACME");

    assertThat(restored.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(restored.reasonCode()).isEqualTo("POLICY_ACTIVE");
    assertThat(restored.maxConcurrentRequests()).isEqualTo(5);
    assertThat(restored.maxRequestsPerMinute()).isEqualTo(7);
    assertThat(restored.maxActiveUsers()).isEqualTo(9);
  }

  @Test
  void invalidatePolicyCache_ignoresBlankCompanyCode() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    admissionService.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-blank");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    service.invalidatePolicyCache("   ");

    TenantRuntimeEnforcementService.TenantRequestAdmission stillUsingCachedPolicy =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillUsingCachedPolicy.isAdmitted()).isTrue();
  }

  @Test
  void beginRequest_policyControlBypassesHoldStateAndRateLimits_forPrivilegedActor() {
    service.holdTenant("ACME", "manual-hold", "ops@bbp.com");
    service.updateQuotas("ACME", 1, 1, 10, "tight-limits", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "super-admin@bbp.com", true);
    admissionService.completeRequest(policyAdmission, 200);

    assertThat(policyAdmission.isAdmitted()).isTrue();
    assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.OK.value());
    TenantRuntimeEnforcementService.TenantRequestAdmission lifecycleAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/lifecycle", "PUT", "super-admin@bbp.com", true);
    assertThat(lifecycleAdmission.isAdmitted()).isTrue();
  }

  @Test
  void beginRequest_canonicalPolicyControlBypassesBlockedState_forPrivilegedActor() {
    service.blockTenant("ACME", "incident-lock", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "super-admin@bbp.com", true);

    assertThat(policyAdmission.isAdmitted()).isTrue();
    assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void beginRequest_policyControlRequiresPrivilegedFlag() {
    service.holdTenant("ACME", "manual-hold", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "actor@bbp.com", false);

    assertThat(policyAdmission.isAdmitted()).isFalse();
    assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
  }

  @Test
  void beginRequest_rejectsWhenConcurrencyQuotaExceeded_andCompleteTracksErrors() {
    service.updateQuotas("ACME", 1, 10, 10, "concurrency_test", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    admissionService.completeRequest(first, 500);
    TenantRuntimeEnforcementService.TenantRequestAdmission third =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    admissionService.completeRequest(third, 200);

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");
    assertThat(first.isAdmitted()).isTrue();
    assertThat(second.isAdmitted()).isFalse();
    assertThat(second.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(second.message()).isEqualTo("Tenant concurrency quota exceeded");
    assertThat(third.isAdmitted()).isTrue();
    assertThat(snapshot.metrics().totalRequests()).isEqualTo(2L);
    assertThat(snapshot.metrics().rejectedRequests()).isEqualTo(1L);
    assertThat(snapshot.metrics().errorResponses()).isEqualTo(1L);
    assertThat(snapshot.metrics().inFlightRequests()).isEqualTo(0);
  }

  @Test
  void enforceAuthOperationAllowed_rejectsWhenActiveUserQuotaExceeded_andNormalizesUnknownActor() {
    activeUsersByCompanyId.put(1L, 5L);
    service.updateQuotas("ACME", 10, 10, 2, "active_users", "ops@bbp.com");

    assertThatThrownBy(() -> admissionService.enforceAuthOperationAllowed("ACME", "   ", "sign_in"))
        .isInstanceOf(AuthSecurityContractException.class)
        .satisfies(
            error -> {
              AuthSecurityContractException ex = (AuthSecurityContractException) error;
              assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(ex.getUserMessage()).isEqualTo("Tenant active-user quota exceeded");
            });

    assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("ACME"), anyMap());
  }

  @Test
  void updateAndResumeTransitions_refreshAuditChain_andSanitizeLimits() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot held =
        service.holdTenant("ACME", "maintenance", "ops@bbp.com");
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot updated =
        service.updateQuotas("ACME", 0, -5, null, "ratchet", "ops@bbp.com");
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot resumed =
        service.resumeTenant("ACME", "ops@bbp.com");

    assertThat(held.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
    assertThat(updated.maxConcurrentRequests()).isEqualTo(1);
    assertThat(updated.maxRequestsPerMinute()).isEqualTo(1);
    assertThat(updated.maxActiveUsers()).isEqualTo(3);
    assertThat(updated.reasonCode()).isEqualTo("RATCHET");
    assertThat(resumed.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(resumed.reasonCode()).isEqualTo("POLICY_ACTIVE");
    assertThat(resumed.auditChainId()).isNotEqualTo(held.auditChainId());
  }

  @Test
  void updatePolicy_mutatesStateAndQuotasWithSingleAuditChain() {
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
        service.updatePolicy(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "incident",
            4,
            6,
            8,
            "ops@bbp.com");

    assertThat(snapshot.state())
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
    assertThat(snapshot.reasonCode()).isEqualTo("INCIDENT");
    assertThat(snapshot.maxConcurrentRequests()).isEqualTo(4);
    assertThat(snapshot.maxRequestsPerMinute()).isEqualTo(6);
    assertThat(snapshot.maxActiveUsers()).isEqualTo(8);
  }

  @Test
  void updatePolicy_rejectsNoMutationPayload() {
    assertThatThrownBy(
            () -> service.updatePolicy("ACME", null, "reason", null, null, null, "ops@bbp.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Runtime policy mutation payload is required");
  }

  @Test
  void updateAndSnapshot_failClosedForUnknownOrMissingCompanyCode() {
    assertThatThrownBy(() -> service.updateQuotas("UNKNOWN", 5, 5, 5, "reason", "ops@bbp.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company not found: UNKNOWN");

    assertThatThrownBy(() -> service.snapshot("   "))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code is required");
  }

  @Test
  void beginRequest_treatsBlankMethodAsMutating_andPolicyControlPathNormalization() {
    service.holdTenant("ACME", "maintenance", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission blankMethodRejected =
        admissionService.beginRequest("ACME", "/api/v1/private", "   ", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission controlTrailingSlashAllowed =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits///", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission lifecycleTrailingSlashAllowed =
        admissionService.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/lifecycle///", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission retiredCompanyPolicyControl =
        admissionService.beginRequest(
            "ACME", "/api/v1/companies/1/tenant-runtime/policy///", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission missingPathRejected =
        admissionService.beginRequest("ACME", null, "PUT", "ops@bbp.com", true);

    assertThat(blankMethodRejected.isAdmitted()).isFalse();
    assertThat(blankMethodRejected.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
    assertThat(controlTrailingSlashAllowed.isAdmitted()).isTrue();
    assertThat(lifecycleTrailingSlashAllowed.isAdmitted()).isTrue();
    assertThat(retiredCompanyPolicyControl.isAdmitted()).isFalse();
    assertThat(retiredCompanyPolicyControl.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
    assertThat(missingPathRejected.isAdmitted()).isFalse();
    assertThat(missingPathRejected.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
  }

  @Test
  void policyControlHelper_requiresPutMethodText_andTreatsRootPathAsNonControl() {
    assertThat(invokeIsPolicyControlRequest("/api/v1/admin/tenant-runtime/policy", null, true))
        .isFalse();
    assertThat(invokeIsPolicyControlRequest("/api/v1/admin/tenant-runtime/policy", "PATCH", true))
        .isFalse();
    assertThat(invokeIsPolicyControlRequest("/", "PUT", true)).isFalse();
    assertThat(
            invokeIsPolicyControlRequest("/api/v1/admin/tenant-runtime/policy///", " put ", true))
        .isFalse();
    assertThat(
            invokeIsPolicyControlRequest(
                "/api/v1/companies/1/tenant-runtime/policy///", " put ", true))
        .isFalse();
    assertThat(
            invokeIsPolicyControlRequest("/api/v1/superadmin/tenants/1/limits///", " put ", true))
        .isTrue();
    assertThat(
            invokeIsPolicyControlRequest(
                "/api/v1/superadmin/tenants/1/lifecycle///", " put ", true))
        .isTrue();
  }

  @Test
  void beginRequest_prefersCachedPolicy_whenPersistedSnapshotIsOlder() throws Exception {
    Object cachedPolicy =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "cached-chain",
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> policies =
        (ConcurrentMap<String, Object>) ReflectionTestUtils.getField(service, "policies");
    assertThat(policies).isNotNull();
    policies.put("ACME", cachedPolicy);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "persisted_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "persisted-chain");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-01T00:00:00Z");

    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        admissionService.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isTrue();
    assertThat(admission.auditChainId()).isEqualTo("cached-chain");
  }

  @Test
  void policyFor_prefersFreshCachedEntryInsideMutationLockWithoutReloadingPersistedSettings()
      throws Exception {
    Object stalePolicy =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "stale-chain",
            Instant.parse("2026-01-01T00:00:00Z"),
            0L);
    Object freshPolicy =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
            "FRESH_POLICY",
            3,
            3,
            3,
            "fresh-chain",
            Instant.parse("2026-01-02T00:00:00Z"),
            System.currentTimeMillis() + 60_000L);

    @SuppressWarnings({"unchecked", "rawtypes"})
    ConcurrentMap<String, Object> mockedPolicies = org.mockito.Mockito.mock(ConcurrentMap.class);
    when(mockedPolicies.get("ACME")).thenReturn(stalePolicy, freshPolicy);
    ReflectionTestUtils.setField(service, "policies", mockedPolicies);

    Object resolved =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "policyFor", "ACME");

    assertThat(resolved).isSameAs(freshPolicy);
    verifyNoInteractions(systemSettingsRepository);
  }

  @Test
  void helperMethods_coverPolicyComparatorNormalizationAndParsingBranches() throws Exception {
    Object current =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-A",
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    Object samePersisted =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "policy_active",
            3,
            3,
            3,
            "chain-a",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedUpdatedAtMissing =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "BLOCKED_REASON",
            3,
            3,
            3,
            "CHAIN-B",
            null,
            0L);
    Object currentUpdatedAtMissing =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-C",
            null,
            0L);
    Object persistedWithUpdatedAt =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "BLOCKED_REASON",
            3,
            3,
            3,
            "CHAIN-D",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedOlder =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "BLOCKED_REASON",
            3,
            3,
            3,
            "CHAIN-E",
            Instant.parse("2026-01-01T00:00:00Z"),
            0L);

    assertThat(invokeShouldUsePersistedPolicy(current, null)).isFalse();
    assertThat(invokeShouldUsePersistedPolicy(current, samePersisted)).isFalse();
    assertThat(invokeShouldUsePersistedPolicy(current, persistedUpdatedAtMissing)).isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentUpdatedAtMissing, persistedWithUpdatedAt))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(current, persistedOlder)).isFalse();

    assertThat(invokeNormalizeState(null))
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(invokeNormalizeState("ACTIVE"))
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
    assertThat(invokeNormalizeState("HOLD"))
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
    assertThat(invokeNormalizeState("mystery"))
        .isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);

    assertThat(invokeParsePositiveInt("12", 5)).isEqualTo(12);
    assertThat(invokeParsePositiveInt("0", 5)).isEqualTo(5);
    assertThat(invokeParsePositiveInt("bad", 5)).isEqualTo(5);

    assertThat(invokeParseInstantOrNull("bad-instant")).isNull();
    Object missingPolicy =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "loadPersistedPolicy", "   ");
    assertThat(missingPolicy).isNull();
  }

  @Test
  void shouldUsePersistedPolicy_coversAuditChainAndReasonEdgeCases() throws Exception {
    Object currentWithNullChain =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            null,
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    Object persistedWithChain =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-N",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object currentWithNullReason =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            null,
            3,
            3,
            3,
            "CHAIN-R",
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    Object persistedNullReasonSamePolicy =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            null,
            3,
            3,
            3,
            "chain-r",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedReasonPresent =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-R",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object currentWithChain =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-A",
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    Object persistedOlderDifferentChain =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "POLICY_ACTIVE",
            3,
            3,
            3,
            "CHAIN-B",
            Instant.parse("2026-01-01T00:00:00Z"),
            0L);
    Object currentFullyMatched =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "REASON-A",
            5,
            6,
            7,
            "CHAIN-Z",
            Instant.parse("2026-01-02T00:00:00Z"),
            0L);
    Object persistedDifferentConcurrent =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "REASON-A",
            8,
            6,
            7,
            "CHAIN-Z",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedDifferentState =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "REASON-A",
            5,
            6,
            7,
            "CHAIN-Z",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedDifferentPerMinute =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "REASON-A",
            5,
            9,
            7,
            "CHAIN-Z",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedDifferentActiveUsers =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "REASON-A",
            5,
            6,
            10,
            "CHAIN-Z",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);
    Object persistedDifferentReason =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE,
            "REASON-B",
            5,
            6,
            7,
            "CHAIN-Z",
            Instant.parse("2026-01-03T00:00:00Z"),
            0L);

    assertThat(invokeShouldUsePersistedPolicy(currentWithNullChain, persistedWithChain)).isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentWithNullReason, persistedNullReasonSamePolicy))
        .isFalse();
    assertThat(invokeShouldUsePersistedPolicy(currentWithNullReason, persistedReasonPresent))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentWithChain, persistedOlderDifferentChain))
        .isFalse();
    assertThat(invokeShouldUsePersistedPolicy(currentFullyMatched, persistedDifferentConcurrent))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentFullyMatched, persistedDifferentState))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentFullyMatched, persistedDifferentPerMinute))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentFullyMatched, persistedDifferentActiveUsers))
        .isTrue();
    assertThat(invokeShouldUsePersistedPolicy(currentFullyMatched, persistedDifferentReason))
        .isTrue();
  }

  @Test
  void loadPersistedPolicy_throwsWhenCompanyMissingOrIdMissing() {
    assertThatThrownBy(() -> invokeLoadPersistedPolicy("UNKNOWN"))
        .isInstanceOf(RuntimeException.class);
    companiesByCode.put("NOID", company(null, "NOID"));
    assertThatThrownBy(() -> invokeLoadPersistedPolicy("NOID"))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void
      loadPersistedPolicy_usesEachPersistedFieldAsPresenceSignal_andFallbacksForReasonAndReference() {
    persistedSettingsByKey.clear();
    assertThat(invokeLoadPersistedPolicy("ACME")).isNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyHoldReason(1L), "reason-only");
    assertThat(invokeLoadPersistedPolicy("ACME")).isNotNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyMaxConcurrentRequests(1L), "9");
    assertThat(invokeLoadPersistedPolicy("ACME")).isNotNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyMaxRequestsPerMinute(1L), "11");
    assertThat(invokeLoadPersistedPolicy("ACME")).isNotNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyMaxActiveUsers(1L), "7");
    assertThat(invokeLoadPersistedPolicy("ACME")).isNotNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyPolicyReference(1L), "ref-only");
    assertThat(invokeLoadPersistedPolicy("ACME")).isNotNull();

    persistedSettingsByKey.clear();
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-01-04T00:00:00Z");
    Object updatedAtOnlyPolicy = invokeLoadPersistedPolicy("ACME");

    assertThat(updatedAtOnlyPolicy).isNotNull();
    assertThat(ReflectionTestUtils.getField(updatedAtOnlyPolicy, "reasonCode"))
        .isEqualTo("POLICY_ACTIVE");
    assertThat(ReflectionTestUtils.getField(updatedAtOnlyPolicy, "auditChainId"))
        .isEqualTo("bootstrap");
  }

  @Test
  void toManagedOperationException_copiesAllRejectionDetails() throws Exception {
    Object rejection =
        tenantRuntimeRejection(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "INCIDENT_LOCK",
            "CHAIN-501",
            HttpStatus.SERVICE_UNAVAILABLE,
            "TENANT_RUNTIME_POLICY_UNAVAILABLE",
            "Tenant runtime policy is unavailable",
            "MAX_ACTIVE_USERS",
            "12",
            "10");
    Object failure =
        tenantRuntimeAdmissionFailure(rejection, new IllegalStateException("policy backend down"));

    ApplicationException ex =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "toManagedOperationException", "ACME", failure);

    assertThat(ex).isNotNull();
    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SYSTEM_SERVICE_UNAVAILABLE);
    assertThat(ex.getDetails())
        .containsEntry("companyCode", "ACME")
        .containsEntry("reason", "TENANT_RUNTIME_POLICY_UNAVAILABLE")
        .containsEntry("auditChainId", "CHAIN-501")
        .containsEntry("tenantReasonCode", "INCIDENT_LOCK")
        .containsEntry("limitType", "MAX_ACTIVE_USERS")
        .containsEntry("observedValue", "12")
        .containsEntry("limitValue", "10");
  }

  @Test
  void auditPolicyChange_defaultsMissingTenantStateWithoutNullDereference() throws Exception {
    Object policy =
        tenantRuntimePolicy(
            null, "POLICY_ACTIVE", 3, 5, 7, "CHAIN-700", Instant.parse("2026-01-07T00:00:00Z"), 0L);

    @SuppressWarnings("unchecked")
    var metadataCaptor =
        org.mockito.ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service,
        "auditPolicyChange",
        "UPDATE_TENANT_RUNTIME_POLICY",
        "ACME",
        "OPS@BBP.COM",
        "POLICY_ACTIVE",
        "CHAIN-699",
        "CHAIN-700",
        policy);

    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.CONFIGURATION_CHANGED),
            eq("OPS@BBP.COM"),
            eq("ACME"),
            metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("tenantState", "ACTIVE")
        .containsEntry("auditChainId", "CHAIN-700")
        .containsEntry("previousAuditChainId", "CHAIN-699");
  }

  @Test
  void auditRejection_defaultsMissingTenantStateWithoutNullDereference() throws Exception {
    Object rejection =
        tenantRuntimeRejection(
            "ACME",
            null,
            "POLICY_ACTIVE",
            "CHAIN-701",
            HttpStatus.SERVICE_UNAVAILABLE,
            "TENANT_RUNTIME_POLICY_UNAVAILABLE",
            "Tenant runtime policy is unavailable",
            null,
            null,
            null);

    @SuppressWarnings("unchecked")
    var metadataCaptor =
        org.mockito.ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "auditRejection", rejection, "ops@bbp.com", "/api/v1/private", "GET");

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("OPS@BBP.COM"), eq("ACME"), metadataCaptor.capture());
    assertThat(metadataCaptor.getValue())
        .containsEntry("tenantState", "BLOCKED")
        .containsEntry("reasonCode", "TENANT_RUNTIME_POLICY_UNAVAILABLE")
        .containsEntry("requestMethod", "GET");
  }

  @Test
  void tenantRuntimeAdmissionFailure_declaresSerialVersionUid() throws Exception {
    Class<?> failureClass =
        Class.forName(
            TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeAdmissionFailure");

    Field serialVersionUid = failureClass.getDeclaredField("serialVersionUID");
    serialVersionUid.setAccessible(true);

    assertThat(serialVersionUid.getType()).isEqualTo(long.class);
    assertThat(serialVersionUid.getLong(null)).isEqualTo(1L);
  }

  @Test
  void policyPersistedStateHelpers_applyFallbacksAndReturnEmptyWhenInputsMissing()
      throws Exception {
    @SuppressWarnings("unchecked")
    Map<String, String> emptyPersistedState =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "policyToPersistedState", null, null);
    assertThat(emptyPersistedState).isEmpty();

    @SuppressWarnings("unchecked")
    Map<String, String> persistedState =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service,
            "policyToPersistedState",
            1L,
            tenantRuntimePolicy(
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "MAINTENANCE",
                0,
                -1,
                0,
                null,
                null,
                0L));
    Object noPolicy =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "policyFromPersistedState", 1L, Map.of());
    Object resolvedPolicy =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "policyFromPersistedState", 1L, persistedState);

    assertThat(noPolicy).isNull();
    assertThat(ReflectionTestUtils.getField(resolvedPolicy, "reasonCode")).isEqualTo("MAINTENANCE");
    assertThat(ReflectionTestUtils.getField(resolvedPolicy, "auditChainId")).isEqualTo("bootstrap");
  }

  @Test
  void readPersistedPolicySetting_wrapsRepositoryFailuresAsManagedOperationFailures() {
    when(systemSettingsRepository.findById(keyHoldState(1L)))
        .thenThrow(new RuntimeException("boom"));

    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "readPersistedPolicySetting", "ACME", keyHoldState(1L)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Tenant runtime policy is unavailable");
  }

  @Test
  void restorePersistedPolicyState_suppressesRestoreFailures() throws Exception {
    RuntimeException originalFailure = new RuntimeException("audit failure");
    Object attemptedPolicy =
        tenantRuntimePolicy(
            TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
            "INCIDENT",
            7,
            9,
            11,
            "CHAIN-600",
            Instant.parse("2026-01-06T00:00:00Z"),
            0L);
    Map<String, String> persistedState = new HashMap<>();
    persistedState.put(keyHoldState(1L), null);
    org.mockito.Mockito.doThrow(new RuntimeException("restore failed"))
        .when(systemSettingsRepository)
        .deleteById(keyHoldState(1L));

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service,
        "restorePersistedPolicyState",
        company(1L, "ACME"),
        "ACME",
        attemptedPolicy,
        persistedState,
        originalFailure);

    assertThat(originalFailure.getSuppressed()).hasSize(1);
    assertThat(originalFailure.getSuppressed()[0]).hasMessageContaining("restore failed");
  }

  @Test
  void resolveActiveUsers_returnsZeroForUnknownTenantAndWrapsLookupFailures() {
    companiesByCode.remove("ACME");

    Long activeUsers =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "resolveActiveUsers", "ACME");

    assertThat(activeUsers).isZero();
    when(companyRepository.findByCodeIgnoreCase("FAIL"))
        .thenThrow(new RuntimeException("lookup down"));
    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "resolveActiveUsers", "FAIL"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Tenant company lookup is unavailable");
  }

  private Company company(Long id, String code) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    ReflectionTestUtils.setField(company, "publicId", UUID.randomUUID());
    company.setName("Company " + code);
    company.setCode(code);
    company.setTimezone("UTC");
    company.setDefaultGstRate(BigDecimal.TEN);
    return company;
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

  private void expireCachedPolicyRefreshDeadline(String companyCode) {
    @SuppressWarnings("unchecked")
    ConcurrentMap<String, Object> policies =
        (ConcurrentMap<String, Object>) ReflectionTestUtils.getField(service, "policies");
    assertThat(policies).isNotNull();
    Object cachedPolicy = policies.get(companyCode);
    assertThat(cachedPolicy).isNotNull();
    ReflectionTestUtils.setField(cachedPolicy, "policyRefreshAfterEpochMillis", 0L);
  }

  private Object tenantRuntimePolicy(
      TenantRuntimeEnforcementService.TenantRuntimeState state,
      String reasonCode,
      int maxConcurrentRequests,
      int maxRequestsPerMinute,
      int maxActiveUsers,
      String auditChainId,
      Instant updatedAt,
      long refreshAfterEpochMillis)
      throws Exception {
    Class<?> policyClass =
        Class.forName(TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimePolicy");
    Constructor<?> constructor =
        policyClass.getDeclaredConstructor(
            TenantRuntimeEnforcementService.TenantRuntimeState.class,
            String.class,
            int.class,
            int.class,
            int.class,
            String.class,
            Instant.class,
            long.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        state,
        reasonCode,
        maxConcurrentRequests,
        maxRequestsPerMinute,
        maxActiveUsers,
        auditChainId,
        updatedAt,
        refreshAfterEpochMillis);
  }

  private Object tenantRuntimeRejection(
      String companyCode,
      TenantRuntimeEnforcementService.TenantRuntimeState tenantState,
      String tenantReasonCode,
      String auditChainId,
      HttpStatus httpStatus,
      String reasonCode,
      String reasonDetail,
      String limitType,
      String observedValue,
      String limitValue)
      throws Exception {
    Class<?> rejectionClass =
        Class.forName(TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeRejection");
    Constructor<?> constructor =
        rejectionClass.getDeclaredConstructor(
            String.class,
            TenantRuntimeEnforcementService.TenantRuntimeState.class,
            String.class,
            String.class,
            HttpStatus.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        companyCode,
        tenantState,
        tenantReasonCode,
        auditChainId,
        httpStatus,
        reasonCode,
        reasonDetail,
        limitType,
        observedValue,
        limitValue);
  }

  private Object tenantRuntimeAdmissionFailure(Object rejection, Throwable cause) throws Exception {
    Class<?> rejectionClass =
        Class.forName(TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeRejection");
    Class<?> failureClass =
        Class.forName(
            TenantRuntimeEnforcementService.class.getName() + "$TenantRuntimeAdmissionFailure");
    Constructor<?> constructor;
    Object[] args;
    try {
      constructor = failureClass.getDeclaredConstructor(rejectionClass, Throwable.class);
      args = new Object[] {rejection, cause};
    } catch (NoSuchMethodException ex) {
      constructor =
          failureClass.getDeclaredConstructor(
              TenantRuntimeEnforcementService.class, rejectionClass, Throwable.class);
      args = new Object[] {service, rejection, cause};
    }
    constructor.setAccessible(true);
    return constructor.newInstance(args);
  }

  private boolean invokeShouldUsePersistedPolicy(Object current, Object persisted) {
    Boolean result =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "shouldUsePersistedPolicy", current, persisted);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsPolicyControlRequest(
      String requestPath, String requestMethod, boolean privilegedActor) {
    Boolean result =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service,
            "isTenantRuntimePolicyControlRequest",
            requestPath,
            requestMethod,
            privilegedActor);
    assertThat(result).isNotNull();
    return result;
  }

  private Object invokeLoadPersistedPolicy(String companyCode) {
    return com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "loadPersistedPolicy", companyCode);
  }

  private TenantRuntimeEnforcementService.TenantRuntimeState invokeNormalizeState(String rawState) {
    TenantRuntimeEnforcementService.TenantRuntimeState state =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "normalizeState", rawState);
    assertThat(state).isNotNull();
    return state;
  }

  private int invokeParsePositiveInt(String rawValue, int fallback) {
    Integer value =
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            service, "parsePositiveInt", rawValue, fallback);
    assertThat(value).isNotNull();
    return value;
  }

  private Instant invokeParseInstantOrNull(String rawValue) {
    return com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "parseInstantOrNull", rawValue);
  }
}
