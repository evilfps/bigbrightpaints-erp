package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.clearInvocations;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.SystemSetting;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TenantRuntimeEnforcementServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private SystemSettingsRepository systemSettingsRepository;

    @Mock
    private AuditService auditService;

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

        service = new TenantRuntimeEnforcementService(
                companyRepository,
                systemSettingsRepository,
                userAccountRepository,
                auditService,
                3,
                3,
                3,
                60);

        lenient().when(companyRepository.findByCodeIgnoreCase(any())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0, String.class);
            if (code == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(companiesByCode.get(code.trim().toUpperCase(Locale.ROOT)));
        });
        lenient().when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(anyLong()))
                .thenAnswer(invocation -> {
                    Long companyId = invocation.getArgument(0, Long.class);
                    return activeUsersByCompanyId.getOrDefault(companyId, 0L);
                });
        lenient().when(systemSettingsRepository.findById(any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0, String.class);
                    String value = persistedSettingsByKey.get(key);
                    if (value == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new SystemSetting(key, value));
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
        service.completeRequest(TenantRuntimeEnforcementService.TenantRequestAdmission.notTracked(), 503);

        TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.snapshot("ACME");
        assertThat(snapshot.metrics().errorResponses()).isZero();
        assertThat(snapshot.metrics().inFlightRequests()).isZero();
    }

    @Test
    void holdTenant_rejectsNewRequests_withLockedStatusAndAuditFailure() {
        TenantRuntimeEnforcementService.TenantRuntimeSnapshot holdSnapshot =
                service.holdTenant("ACME", "compliance_review", "ops@bbp.com");

        TenantRuntimeEnforcementService.TenantRequestAdmission rejected =
                service.beginRequest("ACME", "/api/v1/auth/me", "post", "actor@bbp.com");

        assertThat(holdSnapshot.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
        assertThat(holdSnapshot.reasonCode()).isEqualTo("COMPLIANCE_REVIEW");
        assertThat(rejected.isAdmitted()).isFalse();
        assertThat(rejected.statusCode()).isEqualTo(HttpStatus.LOCKED.value());
        assertThat(rejected.message()).isEqualTo("Tenant is currently on hold");
        assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
        verify(auditService).logAuthFailure(eq(AuditEvent.ACCESS_DENIED), eq("ACTOR@BBP.COM"), eq("ACME"), anyMap());
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
        TenantRuntimeEnforcementService.TenantRequestAdmission heldAdmission = service.beginRequest(
                "ACME",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        service.completeRequest(heldAdmission, 200);

        service.blockTenant("ACME", "incident_block", "ops@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmission = service.beginRequest(
                "ACME",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission blockedAdmissionWithContextPath = service.beginRequest(
                "ACME",
                "/erp/api/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission blockedMalformedPrefixAdmission = service.beginRequest(
                "ACME",
                "/erpapi/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission blockedPolicyRead = service.beginRequest(
                "ACME",
                "/api/v1/admin/tenant-runtime/policy",
                "GET",
                "ops@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission blockedNonControl = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "ops@bbp.com");
        service.completeRequest(blockedAdmission, 200);

        assertThat(heldAdmission.isAdmitted()).isTrue();
        assertThat(blockedAdmission.isAdmitted()).isTrue();
        assertThat(blockedAdmissionWithContextPath.isAdmitted()).isFalse();
        assertThat(blockedAdmissionWithContextPath.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(blockedMalformedPrefixAdmission.isAdmitted()).isFalse();
        assertThat(blockedMalformedPrefixAdmission.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(blockedPolicyRead.isAdmitted()).isFalse();
        assertThat(blockedPolicyRead.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(blockedNonControl.isAdmitted()).isFalse();
        assertThat(blockedNonControl.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(blockedNonControl.message()).isEqualTo("Tenant is currently blocked");
    }

    @Test
    void beginRequest_policyControlPathBypassesRateAndConcurrencyQuotas() {
        service.updateQuotas("ACME", 1, 1, 10, "quota_test", "ops@bbp.com");

        TenantRuntimeEnforcementService.TenantRequestAdmission first = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com");
        service.completeRequest(first, 200);

        TenantRuntimeEnforcementService.TenantRequestAdmission quotaRejected = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com");
        TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission = service.beginRequest(
                "ACME",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        service.completeRequest(controlAdmission, 200);

        assertThat(first.isAdmitted()).isTrue();
        assertThat(quotaRejected.isAdmitted()).isFalse();
        assertThat(quotaRejected.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(controlAdmission.isAdmitted()).isTrue();
    }

    @Test
    void blockTenant_rejectsAuthOperations_withForbiddenStatus() {
        service.blockTenant("ACME", "abuse_incident", "ops@bbp.com");

        assertThatThrownBy(() -> service.enforceAuthOperationAllowed("ACME", "auth-op@bbp.com", "login"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getReason()).isEqualTo("Tenant is currently blocked");
                });

        assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
    }

    @Test
    void enforceAuthOperationAllowed_failClosedWhenCompanyNotFound() {
        assertThatThrownBy(() -> service.enforceAuthOperationAllowed("UNKNOWN", "actor@bbp.com", "login"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found: UNKNOWN");
    }

    @Test
    void holdTenant_failClosedWhenCompanyNotFound() {
        assertThatThrownBy(() -> service.holdTenant("UNKNOWN", "ops_hold", "ops@bbp.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found: UNKNOWN");
    }

    @Test
    void enforceAuthOperationAllowed_onHeldTenantRejectsWithoutActiveUserLookup() {
        service.holdTenant("ACME", "compliance_pause", "ops@bbp.com");
        clearInvocations(userAccountRepository);

        assertThatThrownBy(() -> service.enforceAuthOperationAllowed("ACME", "actor@bbp.com", "login"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
                    assertThat(ex.getReason()).isEqualTo("Tenant is currently on hold");
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
        TenantRuntimeEnforcementService.TenantRequestAdmission warmed = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com");
        assertThat(warmed.isAdmitted()).isTrue();
        service.completeRequest(warmed, 200);

        persistedSettingsByKey.put(keyHoldState(1L), "BLOCKED");
        persistedSettingsByKey.put(keyHoldReason(1L), "policy_block");
        persistedSettingsByKey.put(keyPolicyReference(1L), "policy-ref-02");
        persistedSettingsByKey.put(keyPolicyUpdatedAt(1L), "2026-02-20T10:16:00Z");

        TenantRuntimeEnforcementService.TenantRequestAdmission staleAllowed = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com");
        assertThat(staleAllowed.isAdmitted()).isTrue();
        service.completeRequest(staleAllowed, 200);

        TenantRuntimeEnforcementService.TenantRequestAdmission controlAdmission = service.beginRequest(
                "ACME",
                "/api/v1/admin/tenant-runtime/policy",
                "PUT",
                "ops@bbp.com");
        assertThat(controlAdmission.isAdmitted()).isTrue();
        service.completeRequest(controlAdmission, 200);

        TenantRuntimeEnforcementService.TenantRequestAdmission blockedAfterInvalidate = service.beginRequest(
                "ACME",
                "/api/v1/private",
                "GET",
                "actor@bbp.com");

        assertThat(blockedAfterInvalidate.isAdmitted()).isFalse();
        assertThat(blockedAfterInvalidate.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(blockedAfterInvalidate.message()).isEqualTo("Tenant is currently blocked");
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
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException ex = (ResponseStatusException) error;
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getReason()).isEqualTo("Tenant active-user quota exceeded");
                });

        assertThat(service.snapshot("ACME").metrics().rejectedRequests()).isEqualTo(1L);
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("UNKNOWN_AUTH_ACTOR"),
                eq("ACME"),
                anyMap());
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
        assertThat(resumed.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.ACTIVE);
        assertThat(resumed.reasonCode()).isEqualTo("POLICY_ACTIVE");
        assertThat(resumed.auditChainId()).isNotEqualTo(held.auditChainId());
    }

    @Test
    void updateAndSnapshot_failClosedForUnknownOrMissingCompanyCode() {
        assertThatThrownBy(() -> service.updateQuotas("UNKNOWN", 5, 5, 5, "reason", "ops@bbp.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found: UNKNOWN");

        assertThatThrownBy(() -> service.snapshot("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company code is required");
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

    private String keyPolicyReference(Long companyId) {
        return "tenant.runtime.policy-reference." + companyId;
    }

    private String keyPolicyUpdatedAt(Long companyId) {
        return "tenant.runtime.policy-updated-at." + companyId;
    }
}
