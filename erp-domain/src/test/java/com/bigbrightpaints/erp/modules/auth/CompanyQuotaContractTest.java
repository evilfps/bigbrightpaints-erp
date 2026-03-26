package com.bigbrightpaints.erp.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;
import com.bigbrightpaints.erp.modules.company.service.CompanyService;

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

    CompanyRequest request =
        new CompanyRequest(
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
  void tenant_quota_update_fails_closed_when_authentication_context_is_missing() {
    CompanyRepository repository = mock(CompanyRepository.class);
    Company company = company(1L, "TENANT_A");
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    CompanyService service = new CompanyService(repository);
    SecurityContextHolder.clearContext();

    CompanyRequest request =
        new CompanyRequest(
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
  void tenant_metrics_read_fails_closed_when_authentication_context_is_missing() {
    CompanyRepository repository = mock(CompanyRepository.class);
    Company company = company(1L, "TENANT_A");
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    CompanyService service = new CompanyService(repository);
    SecurityContextHolder.clearContext();

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

    CompanyRequest request =
        new CompanyRequest(
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
    assertThat(company.getQuotaMaxConcurrentRequests()).isEqualTo(7L);
    assertThat(company.isQuotaSoftLimitEnabled()).isFalse();
    assertThat(company.isQuotaHardLimitEnabled()).isTrue();
  }

  @Test
  void runtime_access_hard_limits_fail_closed_when_quota_envelope_unset() {
    CompanyRepository repository = mock(CompanyRepository.class);
    Company company = company(1L, "TENANT_A");
    company.setQuotaSoftLimitEnabled(false);
    company.setQuotaHardLimitEnabled(true);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    CompanyService service = new CompanyService(repository);

    assertThat(service.isRuntimeAccessAllowed(1L)).isFalse();
  }

  @Test
  void runtime_access_hard_limits_allow_only_when_telemetry_is_within_configured_quota() {
    CompanyRepository repository = mock(CompanyRepository.class);
    UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    Company company = company(1L, "TENANT_A");
    company.setQuotaMaxActiveUsers(10L);
    company.setQuotaMaxApiRequests(100L);
    company.setQuotaMaxStorageBytes(5_000L);
    company.setQuotaMaxConcurrentRequests(4L);
    company.setQuotaSoftLimitEnabled(false);
    company.setQuotaHardLimitEnabled(true);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(userAccountRepository.countDistinctByCompanies_IdAndEnabledTrue(1L)).thenReturn(4L);
    when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(80L);
    when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(3_000L);
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(2L);
    CompanyService service =
        new CompanyService(repository, null, userAccountRepository, auditLogRepository);

    assertThat(service.isRuntimeAccessAllowed(1L)).isTrue();

    when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(5L);
    assertThat(service.isRuntimeAccessAllowed(1L)).isFalse();
  }

  @Test
  void tenant_metrics_uses_canonical_quota_contract_names() throws Exception {
    CompanyRepository repository = mock(CompanyRepository.class);
    Company company = company(1L, "TENANT_A");
    company.setQuotaMaxActiveUsers(120L);
    company.setQuotaMaxApiRequests(3_000L);
    company.setQuotaMaxStorageBytes(2_097_152L);
    company.setQuotaMaxConcurrentRequests(7L);
    company.setQuotaSoftLimitEnabled(true);
    company.setQuotaHardLimitEnabled(false);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    CompanyService service = new CompanyService(repository);

    authenticateAs("ROLE_SUPER_ADMIN");

    CompanyTenantMetricsDto metrics = service.getTenantMetrics(1L);

    assertThat(metrics.quotaMaxActiveUsers()).isEqualTo(120L);
    assertThat(metrics.quotaMaxApiRequests()).isEqualTo(3_000L);
    assertThat(metrics.quotaMaxStorageBytes()).isEqualTo(2_097_152L);
    assertThat(metrics.quotaMaxConcurrentRequests()).isEqualTo(7L);
    assertThat(metrics.quotaSoftLimitEnabled()).isTrue();
    assertThat(metrics.quotaHardLimitEnabled()).isFalse();

    String json = new ObjectMapper().writeValueAsString(metrics);
    assertThat(json).contains("quotaMaxActiveUsers");
    assertThat(json).contains("quotaMaxApiRequests");
    assertThat(json).contains("quotaMaxStorageBytes");
    assertThat(json).contains("quotaMaxConcurrentRequests");
    assertThat(json).contains("quotaSoftLimitEnabled");
    assertThat(json).contains("quotaHardLimitEnabled");
    assertThat(json).doesNotContain("activeUserQuota");
    assertThat(json).doesNotContain("apiRateLimitPerMinute");
    assertThat(json).doesNotContain("auditStorageQuotaBytes");
  }

  private void authenticateAs(String... authorities) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
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
