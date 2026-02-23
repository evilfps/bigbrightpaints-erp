package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository repository;

    @Mock
    private AuditService auditService;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(
                repository,
                auditService,
                userAccountRepository,
                auditLogRepository,
                tenantRuntimeEnforcementService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void switchCompany_deniesWhenNotMember() {
        Company allowed = company(1L, "ACME");

        assertThatThrownBy(() -> companyService.switchCompany("BBP", Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).findByCodeIgnoreCase("BBP");
    }

    @Test
    void switchCompany_returnsDtoWhenMember() {
        Company allowed = company(1L, "ACME");
        when(repository.findByCodeIgnoreCase("acme")).thenReturn(Optional.of(allowed));

        CompanyDto dto = companyService.switchCompany("acme", Set.of(allowed));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.code()).isEqualTo("ACME");
    }

    @Test
    void update_allowsSuperAdminWithoutTenantMembership() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company target = company(2L, "BBP");
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);
        when(repository.findById(2L)).thenReturn(Optional.of(target));

        CompanyDto dto = companyService.update(2L, request, Set.of(allowed));

        assertThat(dto.id()).isEqualTo(2L);
        assertThat(dto.name()).isEqualTo("New Name");
        assertThat(dto.code()).isEqualTo("NEW");
    }

    @Test
    void update_allowsMember() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);
        when(repository.findById(1L)).thenReturn(Optional.of(allowed));

        CompanyDto dto = companyService.update(1L, request, Set.of(allowed));

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.name()).isEqualTo("New Name");
        assertThat(dto.code()).isEqualTo("NEW");
    }

    @Test
    void update_deniesTenantAdminEvenWhenMember() {
        authenticateAs("ROLE_ADMIN");
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

        assertThatThrownBy(() -> companyService.update(1L, request, Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant configuration updates");

        verify(repository, never()).findById(anyLong());
    }

    @Test
    void delete_deniesWhenNotMember() {
        Company allowed = company(1L, "ACME");

        assertThatThrownBy(() -> companyService.delete(2L, Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).deleteById(anyLong());
    }

    @Test
    void getTenantMetrics_returnsMetricsForSuperAdmin() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company company = company(1L, "ACME");
        company.setLifecycleState(CompanyLifecycleState.HOLD);
        company.setLifecycleReason("compliance-review");
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L)).thenReturn(3L);
        when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(20L);
        when(auditLogRepository.countApiFailureActivityByCompanyId(1L)).thenReturn(5L);
        when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(2L);
        when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(4_096L);

        CompanyTenantMetricsDto metrics = companyService.getTenantMetrics(1L);

        assertThat(metrics.companyId()).isEqualTo(1L);
        assertThat(metrics.companyCode()).isEqualTo("ACME");
        assertThat(metrics.lifecycleState()).isEqualTo("HOLD");
        assertThat(metrics.lifecycleReason()).isEqualTo("compliance-review");
        assertThat(metrics.activeUserCount()).isEqualTo(3L);
        assertThat(metrics.apiActivityCount()).isEqualTo(20L);
        assertThat(metrics.apiErrorCount()).isEqualTo(5L);
        assertThat(metrics.apiErrorRateInBasisPoints()).isEqualTo(2500L);
        assertThat(metrics.distinctSessionCount()).isEqualTo(2L);
        assertThat(metrics.auditStorageBytes()).isEqualTo(4_096L);
    }

    @Test
    void getTenantMetrics_deniesTenantAdmin() {
        authenticateAs("ROLE_ADMIN");
        Company company = company(1L, "ACME");
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> companyService.getTenantMetrics(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant metrics");

        verify(userAccountRepository, never()).countDistinctByCompanies_IdAndEnabledTrue(1L);
        verify(auditLogRepository, never()).countApiActivityByCompanyId(1L);
        verify(auditLogRepository, never()).countApiFailureActivityByCompanyId(1L);
        verify(auditLogRepository, never()).countDistinctSessionActivityByCompanyId(1L);
        verify(auditLogRepository, never()).estimateAuditStorageBytesByCompanyId(1L);
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenHardLimitQuotaExceeded() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        company.setQuotaMaxActiveUsers(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L)).thenReturn(2L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenApiActivityQuotaExceeded() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        company.setQuotaMaxApiRequests(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(2L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenAuditStorageQuotaExceeded() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        company.setQuotaMaxStorageBytes(1_024L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(2_048L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenConcurrentSessionQuotaExceeded() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        company.setQuotaMaxConcurrentSessions(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(2L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_failsClosedWhenCompanyIsMissing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(companyService.isRuntimeAccessAllowed(null)).isFalse();
        assertThat(companyService.isRuntimeAccessAllowed(99L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_allowsWhenHardLimitEnforcementDisabled() {
        Company company = company(1L, "ACME");
        company.setQuotaSoftLimitEnabled(true);
        company.setQuotaHardLimitEnabled(false);
        company.setQuotaMaxActiveUsers(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isTrue();
        verify(userAccountRepository, never()).countDistinctByCompanies_IdAndEnabledTrue(1L);
    }

    @Test
    void updateLifecycleState_transitionsState_andWritesAuditEvidence() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company company = company(1L, "ACME");
        company.setLifecycleState(CompanyLifecycleState.ACTIVE);
        when(repository.lockById(1L)).thenReturn(Optional.of(company));
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        CompanyLifecycleStateDto response =
                companyService.updateLifecycleState(1L, new CompanyLifecycleStateRequest("HOLD", "  compliance-review  "));

        assertThat(response.previousLifecycleState()).isEqualTo("ACTIVE");
        assertThat(response.lifecycleState()).isEqualTo("HOLD");
        assertThat(response.reason()).isEqualTo("compliance-review");
        assertThat(company.getLifecycleState()).isEqualTo(CompanyLifecycleState.HOLD);
        assertThat(company.getLifecycleReason()).isEqualTo("compliance-review");
        verify(auditService).logAuthSuccess(
                eq(AuditEvent.CONFIGURATION_CHANGED),
                eq("tester@bbp.com"),
                eq("ACME"),
                anyMap());
    }

    @Test
    void updateLifecycleState_deniesNonSuperAdmin_andAuditsAccessDenied() {
        authenticateAs("ROLE_ADMIN");
        Company company = company(1L, "ACME");
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> companyService.updateLifecycleState(
                1L,
                new CompanyLifecycleStateRequest("BLOCKED", "fraud-investigation")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant lifecycle control");

        verify(repository, never()).lockById(anyLong());
        verify(auditService).logAuthFailure(
                eq(AuditEvent.ACCESS_DENIED),
                eq("tester@bbp.com"),
                eq("ACME"),
                anyMap());
    }

    @Test
    void updateTenantRuntimePolicy_deniesNonSuperAdmin() {
        authenticateAs("ROLE_ADMIN");
        Company company = company(1L, "ACME");
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        CompanyService.TenantRuntimePolicyMutationRequest request =
                new CompanyService.TenantRuntimePolicyMutationRequest("HOLD", "maintenance", null, null, null);

        assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(1L, request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant runtime policy control");

        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void updateTenantRuntimePolicy_appliesStateAndQuotaMutationForSuperAdmin() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company company = company(1L, "ACME");
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        TenantRuntimeEnforcementService.TenantRuntimeSnapshot mergedSnapshot =
                new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
                        "ACME",
                        TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                        "MAINTENANCE",
                        "chain-merged",
                        Instant.parse("2026-01-01T00:00:01Z"),
                        8,
                        25,
                        40,
                        new TenantRuntimeEnforcementService.TenantRuntimeMetrics(1, 0, 0, 0, 1, 0, 5L));
        when(tenantRuntimeEnforcementService.updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "maintenance",
                8,
                25,
                40,
                "tester@bbp.com")).thenReturn(mergedSnapshot);

        CompanyService.TenantRuntimePolicyMutationRequest request =
                new CompanyService.TenantRuntimePolicyMutationRequest("HOLD", "maintenance", 8, 25, 40);

        TenantRuntimeEnforcementService.TenantRuntimeSnapshot response =
                companyService.updateTenantRuntimePolicy(1L, request);

        assertThat(response.companyCode()).isEqualTo("ACME");
        assertThat(response.state()).isEqualTo(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD);
        assertThat(response.maxConcurrentRequests()).isEqualTo(8);
        assertThat(response.maxRequestsPerMinute()).isEqualTo(25);
        assertThat(response.maxActiveUsers()).isEqualTo(40);
        verify(tenantRuntimeEnforcementService).updatePolicy(
                "ACME",
                TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
                "maintenance",
                8,
                25,
                40,
                "tester@bbp.com");
    }

    @Test
    void updateTenantRuntimePolicy_failsClosedForMissingPayloadAndUnknownCompany() {
        authenticateAs("ROLE_SUPER_ADMIN");

        CompanyService.TenantRuntimePolicyMutationRequest emptyMutation =
                new CompanyService.TenantRuntimePolicyMutationRequest(null, null, null, null, null);
        assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(1L, emptyMutation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Runtime policy mutation payload is required");

        CompanyService.TenantRuntimePolicyMutationRequest holdMutation =
                new CompanyService.TenantRuntimePolicyMutationRequest("HOLD", "maintenance", null, null, null);
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(404L, holdMutation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found");
    }

    @Test
    void updateTenantRuntimePolicy_failsClosedForUnknownHoldState() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company company = company(1L, "ACME");
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        CompanyService.TenantRuntimePolicyMutationRequest request =
                new CompanyService.TenantRuntimePolicyMutationRequest("PAUSED", "maintenance", null, null, null);

        assertThatThrownBy(() -> companyService.updateTenantRuntimePolicy(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported runtime holdState");

        verifyNoInteractions(tenantRuntimeEnforcementService);
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenLifecycleStateIsNotActive() {
        Company company = company(1L, "ACME");
        company.setLifecycleState(CompanyLifecycleState.HOLD);
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
        verifyNoInteractions(userAccountRepository, auditLogRepository);
    }

    @Test
    void isRuntimeAccessAllowed_deniesWhenHardQuotaEnvelopeIsIncomplete() {
        Company company = company(1L, "ACME");
        company.setQuotaHardLimitEnabled(true);
        company.setQuotaSoftLimitEnabled(false);
        company.setQuotaMaxActiveUsers(100L);
        company.setQuotaMaxApiRequests(0L);
        company.setQuotaMaxStorageBytes(100_000L);
        company.setQuotaMaxConcurrentSessions(100L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
        verifyNoInteractions(userAccountRepository, auditLogRepository);
    }

    @Test
    void isRuntimeAccessAllowed_failsClosedWhenMetricLookupThrows() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L))
                .thenThrow(new RuntimeException("telemetry-store-down"));

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    }

    @Test
    void isRuntimeAccessAllowed_allowsWhenLifecycleAndQuotasAreHealthy() {
        Company company = company(1L, "ACME");
        configureHardLimitEnvelope(company);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L)).thenReturn(50L);
        when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(20L);
        when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(10_000L);
        when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(5L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isTrue();
    }

    @Test
    void resolveLifecycleStateByCode_failsClosedForBlankOrUnknown_andDefaultsNullStateToActive() {
        Company company = company(1L, "ACME");
        company.setLifecycleState(null);
        when(repository.findByCodeIgnoreCase("ACME")).thenReturn(Optional.of(company));
        when(repository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        when(repository.findById(1L)).thenReturn(Optional.of(company));

        assertThat(companyService.resolveLifecycleStateByCode("   ")).isEqualTo(CompanyLifecycleState.BLOCKED);
        assertThat(companyService.resolveLifecycleStateByCode("NOPE")).isEqualTo(CompanyLifecycleState.BLOCKED);
        assertThat(companyService.resolveLifecycleStateByCode(" ACME ")).isEqualTo(CompanyLifecycleState.ACTIVE);
        assertThat(companyService.resolveLifecycleStateById(null)).isEqualTo(CompanyLifecycleState.BLOCKED);
    }

    private void configureHardLimitEnvelope(Company company) {
        company.setQuotaHardLimitEnabled(true);
        company.setQuotaSoftLimitEnabled(false);
        company.setQuotaMaxActiveUsers(100L);
        company.setQuotaMaxApiRequests(100L);
        company.setQuotaMaxStorageBytes(100_000L);
        company.setQuotaMaxConcurrentSessions(100L);
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

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tester@bbp.com", "n/a", java.util.List.of(() -> authority)));
    }
}
