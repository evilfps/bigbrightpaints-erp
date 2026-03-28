package com.bigbrightpaints.erp.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.TenantAdminProvisioningService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyLifecycleState;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.dto.CompanyAdminCredentialResetDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyEnabledModulesDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyLifecycleStateRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanyRequest;
import com.bigbrightpaints.erp.modules.company.dto.CompanySuperAdminDashboardDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanySupportWarningDto;
import com.bigbrightpaints.erp.modules.company.dto.CompanyTenantMetricsDto;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

  @Mock private CompanyRepository repository;

  @Mock private AuditService auditService;

  @Mock private UserAccountRepository userAccountRepository;

  @Mock private AuditLogRepository auditLogRepository;

  @Mock private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

  @Mock private TenantAdminProvisioningService tenantAdminProvisioningService;

  @Mock private PasswordResetService passwordResetService;

  @Mock private CompanyClock companyClock;

  @Mock private AuthScopeService authScopeService;

  private TenantLifecycleService tenantLifecycleService;

  private CompanyService companyService;

  @BeforeEach
  void setUp() {
    lenient().when(companyClock.now(any())).thenReturn(Instant.parse("2026-03-18T06:30:00Z"));
    new CompanyTime(companyClock);
    tenantLifecycleService = new TenantLifecycleService(auditService);
    companyService =
        new CompanyService(
            repository,
            auditService,
            userAccountRepository,
            auditLogRepository,
            tenantRuntimeEnforcementService,
            tenantAdminProvisioningService,
            tenantLifecycleService,
            passwordResetService,
            authScopeService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    CompanyContextHolder.clear();
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
  void update_deniesWhenBoundContextDoesNotMatchTargetTenant() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyContextHolder.setCompanyCode("ROOT");
    Company target = company(2L, "TENANT-A");
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);
    when(repository.findById(2L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Bound company context does not match targeted tenant");
  }

  @Test
  void update_preservesExistingGstRateWhenPayloadOmitsDefaultGstRate() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    target.setDefaultGstRate(new BigDecimal("18.00"));
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", null);
    when(repository.findById(2L)).thenReturn(Optional.of(target));

    CompanyDto dto = companyService.update(2L, request, Set.of(target));

    assertThat(dto.defaultGstRate()).isEqualByComparingTo("18.00");
  }

  @Test
  void update_appliesEnabledModulesWhenPayloadIncludesGatableModules() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    CompanyRequest request =
        new CompanyRequest(
            "New Name",
            "NEW",
            "UTC",
            BigDecimal.TEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Set.of("portal", "PURCHASING"));
    when(repository.findById(2L)).thenReturn(Optional.of(target));

    CompanyDto dto = companyService.update(2L, request, Set.of(target));

    assertThat(dto.code()).isEqualTo("NEW");
    assertThat(target.getEnabledModules()).containsExactlyInAnyOrder("PORTAL", "PURCHASING");
  }

  @Test
  void update_rejectsUnknownEnabledModules() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    CompanyRequest request =
        new CompanyRequest(
            "New Name",
            "NEW",
            "UTC",
            BigDecimal.TEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Set.of("NO_SUCH_MODULE"));
    when(repository.findById(2L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("enabledModules contains unknown modules");
  }

  @Test
  void update_rejectsCoreEnabledModules() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    CompanyRequest request =
        new CompanyRequest(
            "New Name",
            "NEW",
            "UTC",
            BigDecimal.TEN,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Set.of("AUTH"));
    when(repository.findById(2L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("enabledModules can only include gatable modules");
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
  void create_normalizesCode_defaultsGstAndProvisionsFirstAdmin() {
    authenticateAs("ROLE_SUPER_ADMIN");
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));
    when(tenantAdminProvisioningService.isCredentialProvisioningReady()).thenReturn(true);

    CompanyRequest request =
        new CompanyRequest(
            "SKE Corp",
            " ske ",
            "UTC",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "tenant-admin@ske.com",
            "SKE Tenant Admin");

    CompanyDto dto = companyService.create(request);

    assertThat(dto.code()).isEqualTo("SKE");
    assertThat(dto.defaultGstRate()).isEqualByComparingTo("18");
    verify(tenantAdminProvisioningService)
        .provisionInitialAdmin(
            org.mockito.ArgumentMatchers.any(Company.class),
            eq("tenant-admin@ske.com"),
            eq("SKE Tenant Admin"));
  }

  @Test
  void create_rejectsWhenCredentialProvisioningIsNotReady() {
    authenticateAs("ROLE_SUPER_ADMIN");
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
    when(tenantAdminProvisioningService.isCredentialProvisioningReady()).thenReturn(false);

    CompanyRequest request =
        new CompanyRequest(
            "SKE Corp",
            "ske",
            "UTC",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "tenant-admin@ske.com",
            "SKE Tenant Admin");

    assertThatThrownBy(() -> companyService.create(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Credential email delivery is disabled");

    verify(tenantAdminProvisioningService, never())
        .provisionInitialAdmin(any(Company.class), any(), any());
  }

  @Test
  void create_andUpdate_roundTripStateCodeInDto() {
    authenticateAs("ROLE_SUPER_ADMIN");
    when(repository.findByCodeIgnoreCase("GST")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Company.class));

    CompanyDto created =
        companyService.create(
            new CompanyRequest(
                "GST Co",
                "gst",
                "UTC",
                "mh",
                BigDecimal.TEN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    assertThat(created.stateCode()).isEqualTo("MH");

    Company existing = company(55L, "GST");
    when(repository.findById(55L)).thenReturn(Optional.of(existing));
    CompanyDto updated =
        companyService.update(
            55L,
            new CompanyRequest(
                "GST Co",
                "GST",
                "UTC",
                "ka",
                BigDecimal.TEN,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            Set.of(existing));

    assertThat(updated.stateCode()).isEqualTo("KA");
  }

  @Test
  void getTenantMetrics_deniesWhenBoundContextDoesNotMatchTargetTenant() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyContextHolder.setCompanyCode("ROOT");
    Company target = company(1L, "TENANT-A");
    when(repository.findById(1L)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> companyService.getTenantMetrics(1L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Bound company context does not match targeted tenant");
  }

  @Test
  void resolveCompanyCodeById_returnsNullForNullAndUnknownTargets_andTrimsResolvedValue() {
    Company company = company(2L, "  TENANT-A  ");
    when(repository.findById(77L)).thenReturn(Optional.empty());
    when(repository.findById(2L)).thenReturn(Optional.of(company));

    assertThat(companyService.resolveCompanyCodeById(null)).isNull();
    assertThat(companyService.resolveCompanyCodeById(77L)).isNull();
    assertThat(companyService.resolveCompanyCodeById(2L)).isEqualTo("TENANT-A");
  }

  @Test
  void assertBoundControlPlaneCompanyMatchesTarget_allowsBlankTargetCode() {
    CompanyContextHolder.setCompanyCode("ROOT");

    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "assertBoundControlPlaneCompanyMatchesTarget", "   "))
        .doesNotThrowAnyException();
  }

  @Test
  void create_rejectsCaseInsensitiveDuplicateCompanyCode() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company existing = company(11L, "SKE");
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.of(existing));

    CompanyRequest request = new CompanyRequest("SKE Copy", "ske", "UTC", null);

    assertThatThrownBy(() -> companyService.create(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code already exists: SKE");
    verify(repository, never()).save(org.mockito.ArgumentMatchers.any(Company.class));
  }

  @Test
  void create_rejectsBlankCompanyCode() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyRequest request = new CompanyRequest("SKE Corp", "   ", "UTC", null);

    assertThatThrownBy(() -> companyService.create(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code is required");
  }

  @Test
  void update_rejectsDuplicateCompanyCodeOwnedByAnotherCompany() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    Company existing = company(9L, "NEW");
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(repository.findByCodeIgnoreCase("NEW")).thenReturn(Optional.of(existing));
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code already exists: NEW");
  }

  @Test
  void update_allowsDuplicateLookupWhenCodeBelongsToSameCompany() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    Company same = company(2L, "NEW");
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(repository.findByCodeIgnoreCase("NEW")).thenReturn(Optional.of(same));
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

    CompanyDto dto = companyService.update(2L, request, Set.of(target));

    assertThat(dto.code()).isEqualTo("NEW");
  }

  @Test
  void update_allowsDuplicateLookupWhenExistingCompanyIdIsNull() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    Company existingWithoutId = new Company();
    existingWithoutId.setCode("NEW");
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(repository.findByCodeIgnoreCase("NEW")).thenReturn(Optional.of(existingWithoutId));
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

    CompanyDto dto = companyService.update(2L, request, Set.of(target));

    assertThat(dto.code()).isEqualTo("NEW");
  }

  @Test
  void update_synchronizes_user_auth_scope_codes_when_company_code_changes() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    UserAccount tenantUser = new UserAccount("user@example.com", "ACME", "hash", "User");
    ReflectionTestUtils.setField(tenantUser, "id", 7L);
    tenantUser.setCompany(target);
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(userAccountRepository.findByCompany_Id(2L)).thenReturn(List.of(tenantUser));
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

    CompanyDto dto = companyService.update(2L, request, Set.of(target));

    assertThat(dto.code()).isEqualTo("NEW");
    assertThat(tenantUser.getAuthScopeCode()).isEqualTo("NEW");
    verify(userAccountRepository).saveAll(List.of(tenantUser));
  }

  @Test
  void update_rejects_company_code_that_conflicts_with_platform_auth_code() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(authScopeService.isPlatformScope("PLATFORM")).thenReturn(true);
    CompanyRequest request = new CompanyRequest("New Name", "platform", "UTC", BigDecimal.TEN);

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code conflicts with platform auth code: PLATFORM");

    verify(userAccountRepository, never()).findByCompany_Id(anyLong());
    verify(userAccountRepository, never()).saveAll(any());
  }

  @Test
  void update_allowsBoundMatchingContextWhenAuthScopeServiceIsUnavailable() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyContextHolder.setCompanyCode("ACME");
    Company target = company(2L, "ACME");
    CompanyRequest request = new CompanyRequest("New Name", "bbb", "UTC", BigDecimal.TEN);
    CompanyService withoutAuthScopeService =
        new CompanyService(
            repository,
            auditService,
            userAccountRepository,
            auditLogRepository,
            tenantRuntimeEnforcementService,
            tenantAdminProvisioningService,
            tenantLifecycleService,
            passwordResetService,
            null);
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(repository.findByCodeIgnoreCase("BBB")).thenReturn(Optional.empty());
    when(userAccountRepository.findByCompany_Id(2L)).thenReturn(List.of());
    CompanyDto dto = withoutAuthScopeService.update(2L, request, Set.of(target));

    assertThat(dto.code()).isEqualTo("BBB");
  }

  @Test
  void create_rejects_company_code_that_conflicts_with_platform_auth_code() {
    authenticateAs("ROLE_SUPER_ADMIN");
    when(authScopeService.isPlatformScope("PLATFORM")).thenReturn(true);
    CompanyRequest request = new CompanyRequest("Platform", "platform", "UTC", BigDecimal.TEN);

    assertThatThrownBy(() -> companyService.create(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company code conflicts with platform auth code: PLATFORM");

    verify(repository, never()).findByCodeIgnoreCase(any());
  }

  @Test
  void update_allowsPlatformScopeBoundContextForSuperAdminControlPlane() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyContextHolder.setCompanyCode("PLATFORM");
    Company target = company(2L, "ACME");
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(repository.findByCodeIgnoreCase("ACME")).thenReturn(Optional.of(target));
    when(authScopeService.isPlatformScope("PLATFORM")).thenReturn(true);
    CompanyRequest request = new CompanyRequest("New Name", "ACME", "UTC", BigDecimal.TEN);

    CompanyDto response = companyService.update(2L, request, Set.of(target));

    assertThat(response.code()).isEqualTo("ACME");
    assertThat(response.name()).isEqualTo("New Name");
  }

  @Test
  void update_rejects_scoped_account_conflict_when_company_code_changes() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(2L, "ACME");
    UserAccount tenantUser = new UserAccount("user@example.com", "ACME", "hash", "User");
    ReflectionTestUtils.setField(tenantUser, "id", 7L);
    tenantUser.setCompany(target);
    when(repository.findById(2L)).thenReturn(Optional.of(target));
    when(userAccountRepository.findByCompany_Id(2L)).thenReturn(List.of(tenantUser));
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCaseAndIdNot(
            "user@example.com", "NEW", 7L))
        .thenReturn(true);
    CompanyRequest request = new CompanyRequest("New Name", "NEW", "UTC", BigDecimal.TEN);

    assertThatThrownBy(() -> companyService.update(2L, request, Set.of(target)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Scoped account already exists for email in company code");

    verify(userAccountRepository, never()).saveAll(any());
  }

  @Test
  void create_usesRequestedDefaultGstRateWhenProvided() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company incoming = company(7L, "SKE");
    incoming.setDefaultGstRate(new BigDecimal("18.00"));
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class))).thenReturn(incoming);

    CompanyRequest request = new CompanyRequest("SKE Corp", "ske", "UTC", new BigDecimal("18.00"));
    CompanyDto dto = companyService.create(request);

    assertThat(dto.defaultGstRate()).isEqualByComparingTo("18.00");
  }

  @Test
  void create_preservesExplicitZeroDefaultGstRate() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company incoming = company(8L, "ZERO");
    incoming.setDefaultGstRate(BigDecimal.ZERO);
    when(repository.findByCodeIgnoreCase("ZERO")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class))).thenReturn(incoming);

    CompanyRequest request = new CompanyRequest("Zero GST Corp", "zero", "UTC", BigDecimal.ZERO);
    CompanyDto dto = companyService.create(request);

    assertThat(dto.defaultGstRate()).isEqualByComparingTo("0");
  }

  @Test
  void create_skipsInitialAdminProvisioningWhenEmailIsBlank() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company incoming = company(7L, "SKE");
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class))).thenReturn(incoming);

    CompanyRequest request =
        new CompanyRequest(
            "SKE Corp", "ske", "UTC", null, null, null, null, null, null, null, "   ", "Display");
    CompanyDto dto = companyService.create(request);

    assertThat(dto.code()).isEqualTo("SKE");
    verify(tenantAdminProvisioningService, never()).isCredentialProvisioningReady();
    verify(tenantAdminProvisioningService, never())
        .provisionInitialAdmin(
            org.mockito.ArgumentMatchers.any(Company.class),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void synchronizeScopedAccountsToCompanyCode_shortCircuitsAcrossGuardClauses() {
    CompanyService withoutUserRepository =
        new CompanyService(
            repository,
            auditService,
            null,
            auditLogRepository,
            tenantRuntimeEnforcementService,
            tenantAdminProvisioningService,
            tenantLifecycleService,
            passwordResetService,
            authScopeService);
    Company persisted = company(7L, "ACME");

    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "synchronizeScopedAccountsToCompanyCode", null, "BBB"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService,
                    "synchronizeScopedAccountsToCompanyCode",
                    company(null, "ACME"),
                    "BBB"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "synchronizeScopedAccountsToCompanyCode", persisted, "   "))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    withoutUserRepository,
                    "synchronizeScopedAccountsToCompanyCode",
                    persisted,
                    "BBB"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "synchronizeScopedAccountsToCompanyCode", persisted, "ACME"))
        .doesNotThrowAnyException();

    verifyNoInteractions(userAccountRepository);
  }

  @Test
  void create_requiresCredentialProvisioningDependenciesWhenFirstAdminIsRequested() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyService serviceWithoutProvisioning =
        new CompanyService(repository, auditService, userAccountRepository, auditLogRepository);
    Company incoming = company(7L, "SKE");
    when(repository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
    when(repository.save(org.mockito.ArgumentMatchers.any(Company.class))).thenReturn(incoming);

    CompanyRequest request =
        new CompanyRequest(
            "SKE Corp",
            "ske",
            "UTC",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "tenant-admin@ske.com",
            "Tenant Admin");

    assertThatThrownBy(() -> serviceWithoutProvisioning.create(request))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Credential provisioning dependencies are not available");
  }

  @Test
  void companyRequest_legacyQuotaConstructor_initializesAdminFieldsAsNull() {
    CompanyRequest request =
        new CompanyRequest("Acme", "ACME", "UTC", BigDecimal.TEN, 10L, 20L, 30L, 40L, true, true);

    assertThat(request.firstAdminEmail()).isNull();
    assertThat(request.firstAdminDisplayName()).isNull();
    assertThat(request.enabledModules()).isNull();
  }

  @Test
  void updateEnabledModules_updatesTenantModulesForSuperAdmin() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company target = company(3L, "ACME");
    when(repository.findById(3L)).thenReturn(Optional.of(target));

    CompanyEnabledModulesDto response = companyService.updateEnabledModules(3L, Set.of("PORTAL"));

    assertThat(response.companyId()).isEqualTo(3L);
    assertThat(response.companyCode()).isEqualTo("ACME");
    assertThat(response.enabledModules()).containsExactly("PORTAL");
    assertThat(target.getEnabledModules()).containsExactly("PORTAL");
  }

  @Test
  void updateEnabledModules_rejectsMissingPayload() {
    authenticateAs("ROLE_SUPER_ADMIN");

    assertThatThrownBy(() -> companyService.updateEnabledModules(3L, null))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("enabledModules is required");

    verify(repository, never()).findById(anyLong());
  }

  @Test
  void updateEnabledModules_deniesNonSuperAdmin() {
    authenticateAs("ROLE_ADMIN");

    assertThatThrownBy(() -> companyService.updateEnabledModules(3L, Set.of("PORTAL")))
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
    company.setLifecycleState(CompanyLifecycleState.SUSPENDED);
    company.setLifecycleReason("compliance-review");
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(1L)).thenReturn(3L);
    when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(20L);
    when(auditLogRepository.countApiFailureActivityByCompanyId(1L)).thenReturn(5L);
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(2L);
    when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(4_096L);

    CompanyTenantMetricsDto metrics = companyService.getTenantMetrics(1L);

    assertThat(metrics.companyId()).isEqualTo(1L);
    assertThat(metrics.companyCode()).isEqualTo("ACME");
    assertThat(metrics.lifecycleState()).isEqualTo("SUSPENDED");
    assertThat(metrics.lifecycleReason()).isEqualTo("compliance-review");
    assertThat(metrics.activeUserCount()).isEqualTo(3L);
    assertThat(metrics.apiActivityCount()).isEqualTo(20L);
    assertThat(metrics.apiErrorCount()).isEqualTo(5L);
    assertThat(metrics.apiErrorRateInBasisPoints()).isEqualTo(2500L);
    assertThat(metrics.currentConcurrentRequests()).isEqualTo(2L);
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

    verify(userAccountRepository, never()).countByCompany_IdAndEnabledTrue(1L);
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
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(1L)).thenReturn(2L);

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
  void isRuntimeAccessAllowed_deniesWhenConcurrentRequestQuotaExceeded() {
    Company company = company(1L, "ACME");
    configureHardLimitEnvelope(company);
    company.setQuotaMaxConcurrentRequests(1L);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L)).thenReturn(2L);

    assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
  }

  @Test
  void isRuntimeAccessAllowed_failsClosedWhenConcurrentRequestTelemetryIsUnavailable() {
    Company company = company(1L, "ACME");
    configureHardLimitEnvelope(company);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(1L)).thenReturn(50L);
    when(auditLogRepository.countApiActivityByCompanyId(1L)).thenReturn(20L);
    when(auditLogRepository.estimateAuditStorageBytesByCompanyId(1L)).thenReturn(10_000L);
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(1L))
        .thenThrow(new RuntimeException("session-telemetry-down"));

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
    verify(userAccountRepository, never()).countByCompany_IdAndEnabledTrue(1L);
  }

  @Test
  void updateLifecycleState_transitionsState_andWritesAuditEvidence() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(1L, "ACME");
    company.setLifecycleState(CompanyLifecycleState.ACTIVE);
    when(repository.lockById(1L)).thenReturn(Optional.of(company));

    CompanyLifecycleStateDto response =
        companyService.updateLifecycleState(
            1L, new CompanyLifecycleStateRequest("SUSPENDED", "  compliance-review  "));

    assertThat(response.previousLifecycleState()).isEqualTo("ACTIVE");
    assertThat(response.lifecycleState()).isEqualTo("SUSPENDED");
    assertThat(response.reason()).isEqualTo("compliance-review");
    assertThat(company.getLifecycleState()).isEqualTo(CompanyLifecycleState.SUSPENDED);
    assertThat(company.getLifecycleReason()).isEqualTo("compliance-review");
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.CONFIGURATION_CHANGED), eq("tester@bbp.com"), eq("ACME"), anyMap());
  }

  @Test
  void updateLifecycleState_deniesNonSuperAdmin_andAuditsAccessDenied() {
    authenticateAs("ROLE_ADMIN");
    Company company = company(1L, "ACME");
    when(repository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                companyService.updateLifecycleState(
                    1L, new CompanyLifecycleStateRequest("DEACTIVATED", "fraud-investigation")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required for tenant lifecycle control");

    verify(repository, never()).lockById(anyLong());
    verify(auditService)
        .logAuthFailure(eq(AuditEvent.ACCESS_DENIED), eq("tester@bbp.com"), eq("ACME"), anyMap());
  }

  @Test
  void isRuntimeAccessAllowed_deniesWhenLifecycleStateIsNotActive() {
    Company company = company(1L, "ACME");
    company.setLifecycleState(CompanyLifecycleState.SUSPENDED);
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
    company.setQuotaMaxConcurrentRequests(100L);
    when(repository.findById(1L)).thenReturn(Optional.of(company));

    assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    verifyNoInteractions(userAccountRepository, auditLogRepository);
  }

  @Test
  void isRuntimeAccessAllowed_deniesWhenConcurrentRequestQuotaEnvelopeIsMissing() {
    Company company = company(1L, "ACME");
    company.setQuotaHardLimitEnabled(true);
    company.setQuotaSoftLimitEnabled(false);
    company.setQuotaMaxActiveUsers(100L);
    company.setQuotaMaxApiRequests(100L);
    company.setQuotaMaxStorageBytes(100_000L);
    company.setQuotaMaxConcurrentRequests(0L);
    when(repository.findById(1L)).thenReturn(Optional.of(company));

    assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
    verifyNoInteractions(userAccountRepository, auditLogRepository);
  }

  @Test
  void isRuntimeAccessAllowed_failsClosedWhenMetricLookupThrows() {
    Company company = company(1L, "ACME");
    configureHardLimitEnvelope(company);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(1L))
        .thenThrow(new RuntimeException("telemetry-store-down"));

    assertThat(companyService.isRuntimeAccessAllowed(1L)).isFalse();
  }

  @Test
  void isRuntimeAccessAllowed_allowsWhenLifecycleAndQuotasAreHealthy() {
    Company company = company(1L, "ACME");
    configureHardLimitEnvelope(company);
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(1L)).thenReturn(50L);
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

    assertThat(companyService.resolveLifecycleStateByCode("   "))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
    assertThat(companyService.resolveLifecycleStateByCode("NOPE"))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
    assertThat(companyService.resolveLifecycleStateByCode(" ACME "))
        .isEqualTo(CompanyLifecycleState.ACTIVE);
    assertThat(companyService.resolveLifecycleStateById(null))
        .isEqualTo(CompanyLifecycleState.DEACTIVATED);
  }

  @Test
  void helperMembershipChecks_failClosedForNullBlankAndNonMemberInputs() {
    Company allowed = company(1L, "ACME");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "requireMembershipById", null, Set.of(allowed)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "requireMembershipById", 2L, Set.of(allowed)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "requireMembershipByCode", " ", Set.of(allowed)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "requireMembershipByCode", "BBB", Set.of(allowed)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void normalizeStateCode_rejectsNonTwoCharacterValues() {
    assertThatThrownBy(
            () -> ReflectionTestUtils.invokeMethod(companyService, "normalizeStateCode", "ABC"))
        .hasMessageContaining("State code must be exactly 2 characters");
  }

  @Test
  void requireSuperAdminForTenantBootstrap_deniesNonSuperAdmin() {
    authenticateAs("ROLE_ADMIN");

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    companyService, "requireSuperAdminForTenantBootstrap", "MOCK"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required for tenant bootstrap");
  }

  @Test
  void updateTenantRuntimePolicy_deniesTenantAdmin() {
    authenticateAs("ROLE_ADMIN");
    Company company = company(1L, "ACME");
    when(repository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                companyService.updateTenantRuntimePolicy(
                    1L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        "HOLD", "policy", null, null, null)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required for tenant runtime policy control");
  }

  @Test
  void updateTenantRuntimePolicy_rejectsMissingMutationPayload() {
    authenticateAs("ROLE_SUPER_ADMIN");
    assertThatThrownBy(
            () ->
                companyService.updateTenantRuntimePolicy(
                    1L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        null, null, null, null, null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Runtime policy mutation payload is required");
  }

  @Test
  void updateTenantRuntimePolicy_rejectsUnsupportedHoldState() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(1L, "ACME");
    when(repository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                companyService.updateTenantRuntimePolicy(
                    1L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        "PAUSED", "policy", null, null, null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Unsupported runtime holdState");
  }

  @Test
  void updateTenantRuntimePolicy_appliesPolicyThroughRuntimeService() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(1L, "ACME");
    when(repository.findById(1L)).thenReturn(Optional.of(company));
    TenantRuntimeEnforcementService.TenantRuntimeSnapshot snapshot =
        new TenantRuntimeEnforcementService.TenantRuntimeSnapshot(
            "ACME",
            TenantRuntimeEnforcementService.TenantRuntimeState.HOLD,
            "INCIDENT_RESPONSE",
            "chain-1",
            Instant.parse("2026-01-01T00:00:00Z"),
            20,
            100,
            80,
            new TenantRuntimeEnforcementService.TenantRuntimeMetrics(1, 0, 0, 0, 1, 0, 5));
    when(tenantRuntimeEnforcementService.updatePolicy(
            eq("ACME"),
            eq(TenantRuntimeEnforcementService.TenantRuntimeState.HOLD),
            eq("incident-response"),
            eq(20),
            eq(100),
            eq(80),
            eq("tester@bbp.com")))
        .thenReturn(snapshot);

    TenantRuntimeEnforcementService.TenantRuntimeSnapshot response =
        companyService.updateTenantRuntimePolicy(
            1L,
            new CompanyService.TenantRuntimePolicyMutationRequest(
                "HOLD", "incident-response", 20, 100, 80));

    assertThat(response).isEqualTo(snapshot);
    verify(auditService).logSuccess(eq(AuditEvent.ACCESS_GRANTED), anyMap());
  }

  @Test
  void updateTenantRuntimePolicy_failsWhenRuntimeServiceUnavailable() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyService withoutRuntimeService =
        new CompanyService(repository, auditService, userAccountRepository, auditLogRepository);

    assertThatThrownBy(
            () ->
                withoutRuntimeService.updateTenantRuntimePolicy(
                    1L,
                    new CompanyService.TenantRuntimePolicyMutationRequest(
                        "HOLD", "policy", null, null, null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Tenant runtime enforcement service unavailable");
  }

  @Test
  void resetTenantAdminPassword_emailsScopedResetLink() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));
    when(passwordResetService.isResetEmailDeliveryEnabled()).thenReturn(true);
    when(tenantAdminProvisioningService.resetTenantAdminPassword(company, "tenant-admin@ske.com"))
        .thenReturn("tenant-admin@ske.com");

    CompanyAdminCredentialResetDto response =
        companyService.resetTenantAdminPassword(5L, "tenant-admin@ske.com");

    assertThat(response.companyCode()).isEqualTo("SKE");
    assertThat(response.adminEmail()).isEqualTo("tenant-admin@ske.com");
    verify(tenantAdminProvisioningService)
        .resetTenantAdminPassword(company, "tenant-admin@ske.com");
  }

  @Test
  void resetTenantAdminPassword_rejectsWhenPasswordResetEmailDeliveryIsDisabled() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));
    when(passwordResetService.isResetEmailDeliveryEnabled()).thenReturn(false);

    assertThatThrownBy(() -> companyService.resetTenantAdminPassword(5L, "tenant-admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Password reset email delivery is disabled");

    verify(tenantAdminProvisioningService, never())
        .resetTenantAdminPassword(company, "tenant-admin@ske.com");
  }

  @Test
  void resetTenantAdminPassword_deniesWhenBoundContextDoesNotMatchTargetTenant() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyContextHolder.setCompanyCode("ROOT");
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));

    assertThatThrownBy(() -> companyService.resetTenantAdminPassword(5L, "tenant-admin@ske.com"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Bound company context does not match targeted tenant");

    verify(tenantAdminProvisioningService, never())
        .resetTenantAdminPassword(company, "tenant-admin@ske.com");
  }

  @Test
  void resetTenantAdminPassword_surfacesDisabledAdminAsError() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));
    when(passwordResetService.isResetEmailDeliveryEnabled()).thenReturn(true);
    when(tenantAdminProvisioningService.resetTenantAdminPassword(company, "tenant-admin@ske.com"))
        .thenThrow(
            new ApplicationException(
                ErrorCode.AUTH_ACCOUNT_DISABLED,
                ErrorCode.AUTH_ACCOUNT_DISABLED.getDefaultMessage()));

    assertThatThrownBy(() -> companyService.resetTenantAdminPassword(5L, "tenant-admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Account is disabled");
  }

  @Test
  void resetTenantAdminPassword_requiresPasswordResetDependencies() {
    authenticateAs("ROLE_SUPER_ADMIN");
    CompanyService withoutPasswordReset =
        new CompanyService(
            repository,
            auditService,
            userAccountRepository,
            auditLogRepository,
            tenantRuntimeEnforcementService,
            tenantAdminProvisioningService,
            tenantLifecycleService,
            null,
            authScopeService);
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () -> withoutPasswordReset.resetTenantAdminPassword(5L, "tenant-admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Password reset dependencies are not available");
  }

  @Test
  void getSuperAdminDashboard_aggregatesTenantUsageAndQuotas() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company alpha = company(10L, "ALPHA");
    alpha.setQuotaMaxActiveUsers(20L);
    alpha.setQuotaMaxStorageBytes(500L);
    alpha.setQuotaMaxConcurrentRequests(5L);
    alpha.setQuotaMaxApiRequests(100L);
    alpha.setLifecycleState(CompanyLifecycleState.ACTIVE);
    Company beta = company(11L, "BETA");
    beta.setQuotaMaxActiveUsers(30L);
    beta.setQuotaMaxStorageBytes(900L);
    beta.setQuotaMaxConcurrentRequests(7L);
    beta.setQuotaMaxApiRequests(200L);
    beta.setLifecycleState(CompanyLifecycleState.SUSPENDED);
    when(repository.findAll()).thenReturn(List.of(alpha, beta));
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(10L)).thenReturn(8L);
    when(userAccountRepository.countByCompany_IdAndEnabledTrue(11L)).thenReturn(12L);
    when(auditLogRepository.countApiActivityByCompanyId(10L)).thenReturn(90L);
    when(auditLogRepository.countApiActivityByCompanyId(11L)).thenReturn(110L);
    when(auditLogRepository.countApiFailureActivityByCompanyId(10L)).thenReturn(3L);
    when(auditLogRepository.countApiFailureActivityByCompanyId(11L)).thenReturn(6L);
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(10L)).thenReturn(2L);
    when(auditLogRepository.countDistinctSessionActivityByCompanyId(11L)).thenReturn(4L);
    when(auditLogRepository.estimateAuditStorageBytesByCompanyId(10L)).thenReturn(120L);
    when(auditLogRepository.estimateAuditStorageBytesByCompanyId(11L)).thenReturn(300L);

    CompanySuperAdminDashboardDto dashboard = companyService.getSuperAdminDashboard();

    assertThat(dashboard.totalTenants()).isEqualTo(2L);
    assertThat(dashboard.activeTenants()).isEqualTo(1L);
    assertThat(dashboard.suspendedTenants()).isEqualTo(1L);
    assertThat(dashboard.totalActiveUsers()).isEqualTo(20L);
    assertThat(dashboard.totalAuditStorageBytes()).isEqualTo(420L);
    assertThat(dashboard.totalCurrentConcurrentRequests()).isEqualTo(6L);
    assertThat(dashboard.tenants()).hasSize(2);
  }

  @Test
  void issueTenantSupportWarning_requiresSuperAdminAndReturnsWarningPayload() {
    authenticateAs("ROLE_SUPER_ADMIN");
    Company company = company(5L, "SKE");
    when(repository.findById(5L)).thenReturn(Optional.of(company));

    CompanySupportWarningDto warning =
        companyService.issueTenantSupportWarning(
            5L,
            new CompanyService.TenantSupportWarningRequest(
                "quota", "Approaching storage limit", "SUSPENDED", 48));

    assertThat(warning.companyCode()).isEqualTo("SKE");
    assertThat(warning.warningCategory()).isEqualTo("QUOTA");
    assertThat(warning.requestedLifecycleState()).isEqualTo("SUSPENDED");
    assertThat(warning.gracePeriodHours()).isEqualTo(48);
    assertThat(warning.warningId()).isNotBlank();
  }

  private void configureHardLimitEnvelope(Company company) {
    company.setQuotaHardLimitEnabled(true);
    company.setQuotaSoftLimitEnabled(false);
    company.setQuotaMaxActiveUsers(100L);
    company.setQuotaMaxApiRequests(100L);
    company.setQuotaMaxStorageBytes(100_000L);
    company.setQuotaMaxConcurrentRequests(100L);
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
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "tester@bbp.com", "n/a", java.util.List.of(() -> authority)));
  }
}
