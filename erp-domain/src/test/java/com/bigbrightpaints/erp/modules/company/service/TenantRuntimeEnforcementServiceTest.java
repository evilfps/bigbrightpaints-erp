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
    private AuditService auditService;

    private final Map<String, Company> companiesByCode = new HashMap<>();
    private final Map<Long, Long> activeUsersByCompanyId = new HashMap<>();

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
                userAccountRepository,
                auditService,
                3,
                3,
                3);

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
    void beginRequest_policyControlBypassesHoldStateAndRateLimits_forPrivilegedActor() {
        service.holdTenant("ACME", "manual-hold", "ops@bbp.com");
        service.updateQuotas("ACME", 1, 1, 10, "tight-limits", "ops@bbp.com");

        TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
                service.beginRequest(
                        "ACME",
                        "/api/v1/companies/1/tenant-runtime/policy",
                        "PUT",
                        "super-admin@bbp.com",
                        true);
        service.completeRequest(policyAdmission, 200);

        assertThat(policyAdmission.isAdmitted()).isTrue();
        assertThat(policyAdmission.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void beginRequest_policyControlRequiresPrivilegedFlag() {
        service.holdTenant("ACME", "manual-hold", "ops@bbp.com");

        TenantRuntimeEnforcementService.TenantRequestAdmission policyAdmission =
                service.beginRequest(
                        "ACME",
                        "/api/v1/companies/1/tenant-runtime/policy",
                        "PUT",
                        "actor@bbp.com",
                        false);

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
    void updatePolicy_mutatesStateAndQuotasWithSingleAuditChain() {
        TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot = service.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED,
                "incident",
                4,
                6,
                8,
                "ops@bbp.com");

        assertThat(snapshot.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.BLOCKED);
        assertThat(snapshot.reasonCode()).isEqualTo("INCIDENT");
        assertThat(snapshot.maxConcurrentRequests()).isEqualTo(4);
        assertThat(snapshot.maxRequestsPerMinute()).isEqualTo(6);
        assertThat(snapshot.maxActiveUsers()).isEqualTo(8);
    }

    @Test
    void updatePolicy_rejectsNoMutationPayload() {
        assertThatThrownBy(() -> service.updatePolicy("ACME", null, "reason", null, null, null, "ops@bbp.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Runtime policy mutation payload is required");
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
}
