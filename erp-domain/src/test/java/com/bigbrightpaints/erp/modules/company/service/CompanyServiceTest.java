package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import java.math.BigDecimal;
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

    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(repository, auditService, userAccountRepository, auditLogRepository);
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
    void update_deniesWhenNotMember() {
        authenticateAs("ROLE_SUPER_ADMIN");
        Company allowed = company(1L, "ACME");
        CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

        assertThatThrownBy(() -> companyService.update(2L, request, Set.of(allowed)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Not allowed");

        verify(repository, never()).findById(anyLong());
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
        company.setQuotaHardLimitEnabled(true);
        company.setQuotaMaxActiveUsers(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L)).thenReturn(2L);

        assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
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
