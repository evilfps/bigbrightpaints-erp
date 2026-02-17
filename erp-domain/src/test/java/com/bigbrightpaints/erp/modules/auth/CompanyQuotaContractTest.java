package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class CompanyQuotaContractTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenant_quota_update_requires_super_admin_authority() {
        CompanyRepository repository = mock(CompanyRepository.class);
        Company company = company(1L, "TENANT_A");
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        CompanyService service = new CompanyService(repository);

        authenticateAs("ROLE_ADMIN");

        CompanyRequest request = new CompanyRequest(
                "Tenant A",
                "TENANT_A",
                "UTC",
                BigDecimal.valueOf(18),
                120L,
                3_000L,
                2_097_152L,
                7L,
                false,
                false);

        assertThatThrownBy(() -> service.update(1L, request, Set.of(company)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant configuration updates");
    }

    @Test
    void tenant_metrics_read_requires_super_admin_authority() {
        CompanyRepository repository = mock(CompanyRepository.class);
        Company company = company(1L, "TENANT_A");
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        CompanyService service = new CompanyService(repository);

        authenticateAs("ROLE_ADMIN");

        assertThatThrownBy(() -> service.getTenantMetrics(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for tenant metrics");
    }

    @Test
    void super_admin_update_applies_canonical_quota_fields_and_fail_closed_policy() {
        CompanyRepository repository = mock(CompanyRepository.class);
        Company company = company(1L, "TENANT_A");
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        CompanyService service = new CompanyService(repository);

        authenticateAs("ROLE_SUPER_ADMIN");

        CompanyRequest request = new CompanyRequest(
                "Tenant A Updated",
                "TENANT_A",
                "UTC",
                BigDecimal.valueOf(18),
                120L,
                3_000L,
                2_097_152L,
                7L,
                false,
                false);

        service.update(1L, request, Set.of(company));

        assertThat(company.getQuotaMaxActiveUsers()).isEqualTo(120L);
        assertThat(company.getQuotaMaxApiRequests()).isEqualTo(3_000L);
        assertThat(company.getQuotaMaxStorageBytes()).isEqualTo(2_097_152L);
        assertThat(company.getQuotaMaxConcurrentSessions()).isEqualTo(7L);
        assertThat(company.isQuotaSoftLimitEnabled()).isFalse();
        assertThat(company.isQuotaHardLimitEnabled()).isTrue();
    }

    @Test
    void tenant_metrics_uses_canonical_quota_contract_names() throws Exception {
        CompanyRepository repository = mock(CompanyRepository.class);
        Company company = company(1L, "TENANT_A");
        company.setQuotaMaxActiveUsers(120L);
        company.setQuotaMaxApiRequests(3_000L);
        company.setQuotaMaxStorageBytes(2_097_152L);
        company.setQuotaMaxConcurrentSessions(7L);
        company.setQuotaSoftLimitEnabled(true);
        company.setQuotaHardLimitEnabled(false);
        when(repository.findById(1L)).thenReturn(Optional.of(company));
        CompanyService service = new CompanyService(repository);

        authenticateAs("ROLE_SUPER_ADMIN");

        CompanyTenantMetricsDto metrics = service.getTenantMetrics(1L);

        assertThat(metrics.quotaMaxActiveUsers()).isEqualTo(120L);
        assertThat(metrics.quotaMaxApiRequests()).isEqualTo(3_000L);
        assertThat(metrics.quotaMaxStorageBytes()).isEqualTo(2_097_152L);
        assertThat(metrics.quotaMaxConcurrentSessions()).isEqualTo(7L);
        assertThat(metrics.quotaSoftLimitEnabled()).isTrue();
        assertThat(metrics.quotaHardLimitEnabled()).isFalse();

        String json = new ObjectMapper().writeValueAsString(metrics);
        assertThat(json).contains("quotaMaxActiveUsers");
        assertThat(json).contains("quotaMaxApiRequests");
        assertThat(json).contains("quotaMaxStorageBytes");
        assertThat(json).contains("quotaMaxConcurrentSessions");
        assertThat(json).contains("quotaSoftLimitEnabled");
        assertThat(json).contains("quotaHardLimitEnabled");
        assertThat(json).doesNotContain("activeUserQuota");
        assertThat(json).doesNotContain("apiRateLimitPerMinute");
        assertThat(json).doesNotContain("auditStorageQuotaBytes");
    }

    private void authenticateAs(String... authorities) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "tester",
                "n/a",
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Company company(Long id, String code) {
        Company company = new Company();
        ReflectionTestUtils.setField(company, "id", id);
        company.setName(code);
        company.setCode(code);
        company.setTimezone("UTC");
        return company;
    }
}
