package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.domain.TenantAdminEmailChangeRequestRepository;
import com.bigbrightpaints.erp.modules.company.domain.TenantSupportWarningRepository;
import com.bigbrightpaints.erp.modules.company.dto.SuperAdminTenantLimitsDto;

@ExtendWith(MockitoExtension.class)
class SuperAdminTenantControlPlaneServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private AuditLogRepository auditLogRepository;
  @Mock private AuditService auditService;
  @Mock private EmailService emailService;
  @Mock private TokenBlacklistService tokenBlacklistService;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private TenantSupportWarningRepository tenantSupportWarningRepository;
  @Mock private TenantAdminEmailChangeRequestRepository tenantAdminEmailChangeRequestRepository;
  @Mock private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;
  @Mock private CompanyService companyService;

  private SuperAdminTenantControlPlaneService service;

  @BeforeEach
  void setUp() {
    service =
        new SuperAdminTenantControlPlaneService(
            companyRepository,
            userAccountRepository,
            auditLogRepository,
            auditService,
            emailService,
            tokenBlacklistService,
            refreshTokenService,
            tenantSupportWarningRepository,
            tenantAdminEmailChangeRequestRepository,
            tenantRuntimeEnforcementService,
            companyService);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("super-admin@bbp.com", "n/a"));
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void updateLimits_preservesZeroQuotasWhenSyncingRuntimePolicy() {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", 7L);
    company.setCode("ACME");
    when(companyRepository.findById(7L)).thenReturn(Optional.of(company));
    when(companyRepository.save(company)).thenReturn(company);

    SuperAdminTenantLimitsDto response = service.updateLimits(7L, 0L, 0L, 0L, 0L, true, false);

    assertThat(response.quotaMaxActiveUsers()).isZero();
    assertThat(response.quotaMaxApiRequests()).isZero();
    assertThat(response.quotaMaxStorageBytes()).isZero();
    assertThat(response.quotaMaxConcurrentRequests()).isZero();
    verify(tenantRuntimeEnforcementService)
        .updatePolicy("ACME", null, "ERP37_LIMITS_UPDATE", 0, 0, 0, "super-admin@bbp.com");
  }
}
