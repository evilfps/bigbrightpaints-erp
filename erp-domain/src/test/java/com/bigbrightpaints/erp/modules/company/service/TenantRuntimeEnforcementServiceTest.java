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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

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
        .when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(anyLong()))
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
  }

  @AfterEach
  void tearDown() {
    ReflectionTestUtils.setField(CompanyTime.class, "companyClock", null);
  }

  @Test
  void beginRequest_doesNotTrackWhenCompanyContextMissing() {
    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        service.beginRequest("   ", "/api/v1/auth/me", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isFalse();
    assertThat(admission.statusCode()).isEqualTo(200);
    assertThat(admission.companyCode()).isNull();
    assertThat(admission.auditChainId()).isNull();
    verifyNoInteractions(companyRepository, auditService, userAccountRepository);
  }

  @Test
  void completeRequest_ignoresNullAndNotAdmittedHandles() {
    service.completeRequest(null, 503);
    service.completeRequest(
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
        service.beginRequest("ACME", "/api/v1/auth/me", "post", "actor@bbp.com");

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
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    service.completeRequest(admission, 200);
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
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    service.completeRequest(heldAdmission, 200);

    service.blockTenant("ACME", "incident_block", "ops@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmissionWithContextPath =
        service.beginRequest(
            "ACME", "/erp/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedMalformedPrefixAdmission =
        service.beginRequest(
            "ACME", "/erpapi/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedUnprivilegedControl =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", false);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedPolicyRead =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "GET", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission retiredAdminPath =
        service.beginRequest(
            "ACME", "/api/v1/admin/tenant-runtime/policy", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission blockedNonControl =
        service.beginRequest("ACME", "/api/v1/private", "GET", "ops@bbp.com");
    service.completeRequest(blockedAdmission, 200);

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
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    service.completeRequest(first, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission quotaRejected =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    service.completeRequest(controlAdmission, 200);

    assertThat(first.isAdmitted()).isTrue();
    assertThat(quotaRejected.isAdmitted()).isFalse();
    assertThat(quotaRejected.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(controlAdmission.isAdmitted()).isTrue();
  }

  @Test
  void blockTenant_rejectsAuthOperations_withForbiddenStatus() {
    service.blockTenant("ACME", "abuse_incident", "ops@bbp.com");

    assertThatThrownBy(
            () -> service.enforceAuthOperationAllowed("ACME", "auth-op@bbp.com", "login"))
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
            () -> service.enforceAuthOperationAllowed("UNKNOWN", "actor@bbp.com", "login"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company not found: UNKNOWN");
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

    assertThatThrownBy(() -> service.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
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
  void beginRequest_rejectsWhenRateQuotaExceeded() {
    service.updateQuotas("ACME", 10, 1, 10, "rate_test", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        service.beginRequest("ACME", "/api/v1/auth/me", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        service.beginRequest("ACME", "/api/v1/auth/me", "GET", "actor@bbp.com");

    service.completeRequest(first, 200);
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
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isFalse();
    assertThat(admission.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(admission.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void beginRequest_fallsBackToCachedPolicyWhenSettingsReadFailsDuringRefresh() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    service.completeRequest(warmed, 200);
    expireCachedPolicyRefreshDeadline("ACME");

    when(systemSettingsRepository.findById(any()))
        .thenThrow(new RuntimeException("settings-unavailable"));

    TenantRuntimeEnforcementService.TenantRequestAdmission fallbackAdmission =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(fallbackAdmission.isAdmitted()).isTrue();
    assertThat(fallbackAdmission.auditChainId()).isEqualTo(warmed.auditChainId());
  }

  @Test
  void beginRequest_fallsBackToDefaultPolicyWhenCompanyLookupFails() {
    when(companyRepository.findByCodeIgnoreCase(eq("ACME")))
        .thenThrow(new RuntimeException("company-lookup-unavailable"));

    TenantRuntimeEnforcementService.TenantRequestAdmission admission =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isTrue();
    assertThat(admission.auditChainId()).isNotBlank();
  }

  @Test
  void beginRequest_usesCachedPolicy_withoutReloadingPersistedSettingsPerRequest() {
    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-01");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(first.isAdmitted()).isFalse();
    clearInvocations(systemSettingsRepository, companyRepository);

    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(second.isAdmitted()).isFalse();
    assertThat(second.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    verifyNoInteractions(systemSettingsRepository, companyRepository);
  }

  @Test
  void completeRequest_policyControlSuccessInvalidatesCachedPolicyImmediately() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    service.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-02");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    TenantRuntimeEnforcementService.TenantRequestAdmission staleAllowed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(staleAllowed.isAdmitted()).isTrue();
    service.completeRequest(staleAllowed, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();
    service.completeRequest(controlAdmission, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAfterInvalidate =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(blockedAfterInvalidate.isAdmitted()).isFalse();
    assertThat(blockedAfterInvalidate.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedAfterInvalidate.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void completeRequest_policyControlErrorDoesNotInvalidateCachedPolicy() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    service.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-error");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();

    service.completeRequest(controlAdmission, 500);

    TenantRuntimeEnforcementService.TenantRequestAdmission stillUsingCachedPolicy =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillUsingCachedPolicy.isAdmitted()).isTrue();
  }

  @Test
  void canonicalUpdatePolicy_persistsSettings_and_survivesPolicyControlInvalidation() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    service.completeRequest(warmed, 200);

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
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "ops@bbp.com", true);
    assertThat(controlAdmission.isAdmitted()).isTrue();
    service.completeRequest(controlAdmission, 200);

    TenantRuntimeEnforcementService.TenantRequestAdmission blockedAfterInvalidate =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(blockedAfterInvalidate.isAdmitted()).isFalse();
    assertThat(blockedAfterInvalidate.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(blockedAfterInvalidate.message()).isEqualTo("Tenant is currently blocked");
  }

  @Test
  void invalidatePolicyCache_ignoresBlankCompanyCode() {
    TenantRuntimeEnforcementService.TenantRequestAdmission warmed =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(warmed.isAdmitted()).isTrue();
    service.completeRequest(warmed, 200);

    persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
    persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
    persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-blank");
    persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

    service.invalidatePolicyCache("   ");

    TenantRuntimeEnforcementService.TenantRequestAdmission stillUsingCachedPolicy =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    assertThat(stillUsingCachedPolicy.isAdmitted()).isTrue();
  }

  @Test
  void beginRequest_policyControlBypassesHoldStateAndRateLimits_forPrivilegedActor() {
    service.holdTenant("ACME", "manual-hold", "ops@bbp.com");
    service.updateQuotas("ACME", 1, 1, 10, "tight-limits", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "super-admin@bbp.com", true);
    service.completeRequest(policyAdmission, 200);

    assertThat(policyAdmission.isAdmitted()).isTrue();
    assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void beginRequest_policyControlRequiresPrivilegedFlag() {
    service.holdTenant("ACME", "manual-hold", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits", "PUT", "actor@bbp.com", false);

    assertThat(policyAdmission.isAdmitted()).isFalse();
    assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
  }

  @Test
  void beginRequest_rejectsWhenConcurrencyQuotaExceeded_andCompleteTracksErrors() {
    service.updateQuotas("ACME", 1, 10, 10, "concurrency_test", "ops@bbp.com");

    TenantRuntimeEnforcementService.TenantRequestAdmission first =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission second =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    service.completeRequest(first, 500);
    TenantRuntimeEnforcementService.TenantRequestAdmission third =
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");
    service.completeRequest(third, 200);

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

    assertThatThrownBy(() -> service.enforceAuthOperationAllowed("ACME", "   ", "sign_in"))
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
        service.beginRequest("ACME", "/api/v1/private", "   ", "actor@bbp.com");
    TenantRuntimeEnforcementService.TenantRequestAdmission controlTrailingSlashAllowed =
        service.beginRequest(
            "ACME", "/api/v1/superadmin/tenants/1/limits///", "PUT", "ops@bbp.com", true);
    TenantRuntimeEnforcementService.TenantRequestAdmission missingPathRejected =
        service.beginRequest("ACME", null, "PUT", "ops@bbp.com", true);

    assertThat(blankMethodRejected.isAdmitted()).isFalse();
    assertThat(blankMethodRejected.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
    assertThat(controlTrailingSlashAllowed.isAdmitted()).isTrue();
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
            invokeIsPolicyControlRequest("/api/v1/superadmin/tenants/1/limits///", " put ", true))
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
        service.beginRequest("ACME", "/api/v1/private", "GET", "actor@bbp.com");

    assertThat(admission.isAdmitted()).isTrue();
    assertThat(admission.auditChainId()).isEqualTo("cached-chain");
  }

  @Test
  void policyFor_prefersFreshCachedEntryInsideComputeWithoutReloadingPersistedSettings()
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
    when(mockedPolicies.get("ACME")).thenReturn(stalePolicy);
    when(mockedPolicies.compute(eq("ACME"), any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              BiFunction<String, Object, Object> remappingFunction =
                  invocation.getArgument(1, BiFunction.class);
              return remappingFunction.apply("ACME", freshPolicy);
            });
    ReflectionTestUtils.setField(service, "policies", mockedPolicies);

    Object resolved = ReflectionTestUtils.invokeMethod(service, "policyFor", "ACME");

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
    Object missingPolicy = ReflectionTestUtils.invokeMethod(service, "loadPersistedPolicy", "   ");
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
  void loadPersistedPolicy_returnsNullWhenCompanyMissingOrIdMissing() {
    Object unknownCompanyPolicy = invokeLoadPersistedPolicy("UNKNOWN");
    companiesByCode.put("NOID", company(null, "NOID"));
    Object missingIdPolicy = invokeLoadPersistedPolicy("NOID");

    assertThat(unknownCompanyPolicy).isNull();
    assertThat(missingIdPolicy).isNull();
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

  private boolean invokeShouldUsePersistedPolicy(Object current, Object persisted) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(service, "shouldUsePersistedPolicy", current, persisted);
    assertThat(result).isNotNull();
    return result;
  }

  private boolean invokeIsPolicyControlRequest(
      String requestPath, String requestMethod, boolean privilegedActor) {
    Boolean result =
        ReflectionTestUtils.invokeMethod(
            service,
            "isTenantRuntimePolicyControlRequest",
            requestPath,
            requestMethod,
            privilegedActor);
    assertThat(result).isNotNull();
    return result;
  }

  private Object invokeLoadPersistedPolicy(String companyCode) {
    return ReflectionTestUtils.invokeMethod(service, "loadPersistedPolicy", companyCode);
  }

  private TenantRuntimeEnforcementService.TenantRuntimeState invokeNormalizeState(String rawState) {
    TenantRuntimeEnforcementService.TenantRuntimeState state =
        ReflectionTestUtils.invokeMethod(service, "normalizeState", rawState);
    assertThat(state).isNotNull();
    return state;
  }

  private int invokeParsePositiveInt(String rawValue, int fallback) {
    Integer value =
        ReflectionTestUtils.invokeMethod(service, "parsePositiveInt", rawValue, fallback);
    assertThat(value).isNotNull();
    return value;
  }

  private Instant invokeParseInstantOrNull(String rawValue) {
    return ReflectionTestUtils.invokeMethod(service, "parseInstantOrNull", rawValue);
  }
}
