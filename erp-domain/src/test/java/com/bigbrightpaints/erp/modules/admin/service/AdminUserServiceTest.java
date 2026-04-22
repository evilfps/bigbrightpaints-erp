package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UserDto;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.auth.service.ScopedAccountBootstrapService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

  @Mock private UserAccountRepository userRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private RoleService roleService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private EmailService emailService;
  @Mock private AuthScopeService authScopeService;
  @Mock private TokenBlacklistService tokenBlacklistService;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private PasswordResetService passwordResetService;
  @Mock private AuditService auditService;
  @Mock private AuditLogRepository auditLogRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private TenantRuntimePolicyService tenantRuntimePolicyService;

  private AdminUserService service;
  private Company company;
  private ScopedAccountBootstrapService scopedAccountBootstrapService;

  @BeforeEach
  void setUp() {
    scopedAccountBootstrapService =
        new ScopedAccountBootstrapService(
            userRepository, passwordEncoder, emailService, authScopeService);
    service =
        new AdminUserService(
            userRepository,
            companyContextService,
            roleService,
            emailService,
            tokenBlacklistService,
            refreshTokenService,
            passwordResetService,
            scopedAccountBootstrapService,
            auditService,
            auditLogRepository,
            dealerRepository,
            accountRepository,
            tenantRuntimePolicyService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("TEST");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient()
        .when(authScopeService.requireScopeCode(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().toUpperCase());
    lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
    lenient()
        .when(
            userRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
                anyString(), anyString()))
        .thenReturn(false);
    lenient()
        .when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(
            invocation -> {
              UserAccount user = invocation.getArgument(0);
              if (user.getId() == null) {
                ReflectionTestUtils.setField(user, "id", 200L);
              }
              return user;
            });
    lenient()
        .when(roleService.ensureRoleExists(anyString()))
        .thenAnswer(
            invocation -> {
              Role role = new Role();
              role.setName(invocation.getArgument(0));
              return role;
            });
    lenient()
        .when(dealerRepository.save(any(Dealer.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(accountRepository.save(any(Account.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createUser_relinksExistingDealerByEmailAndReactivatesReceivableAccount() {
    Dealer existingDealer = new Dealer();
    existingDealer.setCompany(company);
    ReflectionTestUtils.setField(existingDealer, "id", 44L);
    existingDealer.setCode("LEGACY44");
    existingDealer.setName("Legacy Dealer");
    existingDealer.setStatus("INACTIVE");
    existingDealer.setEmail("dealer@example.com");

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-LEGACY44");
    receivable.setActive(false);
    existingDealer.setReceivableAccount(receivable);

    when(dealerRepository.findByCompanyAndPortalUserEmail(company, "dealer@example.com"))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndEmailIgnoreCase(company, "dealer@example.com"))
        .thenReturn(Optional.of(existingDealer));

    service.createUser(
        new CreateUserRequest("dealer@example.com", "Dealer User", List.of("ROLE_DEALER")));

    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository).save(dealerCaptor.capture());
    Dealer savedDealer = dealerCaptor.getValue();
    assertThat(savedDealer.getId()).isEqualTo(44L);
    assertThat(savedDealer.getStatus()).isEqualTo("ACTIVE");
    assertThat(savedDealer.getPortalUser()).isNotNull();
    assertThat(savedDealer.getPortalUser().getEmail()).isEqualTo("dealer@example.com");
    assertThat(receivable.isActive()).isTrue();
    verify(accountRepository).save(receivable);
  }

  @Test
  void createUser_relinksExistingDealerByEmailAndPreservesBlockedStatus() {
    Dealer existingDealer = new Dealer();
    existingDealer.setCompany(company);
    ReflectionTestUtils.setField(existingDealer, "id", 45L);
    existingDealer.setCode("BLOCKED45");
    existingDealer.setName("Blocked Dealer");
    existingDealer.setStatus("BLOCKED");
    existingDealer.setEmail("blocked-dealer@example.com");

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-BLOCKED45");
    receivable.setActive(false);
    existingDealer.setReceivableAccount(receivable);

    when(dealerRepository.findByCompanyAndPortalUserEmail(company, "blocked-dealer@example.com"))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndEmailIgnoreCase(company, "blocked-dealer@example.com"))
        .thenReturn(Optional.of(existingDealer));

    service.createUser(
        new CreateUserRequest(
            "blocked-dealer@example.com", "Blocked Dealer", List.of("ROLE_DEALER")));

    ArgumentCaptor<Dealer> dealerCaptor = ArgumentCaptor.forClass(Dealer.class);
    verify(dealerRepository).save(dealerCaptor.capture());
    Dealer savedDealer = dealerCaptor.getValue();
    assertThat(savedDealer.getId()).isEqualTo(45L);
    assertThat(savedDealer.getStatus()).isEqualTo("BLOCKED");
    assertThat(savedDealer.getPortalUser()).isNotNull();
    assertThat(savedDealer.getPortalUser().getEmail()).isEqualTo("blocked-dealer@example.com");
    assertThat(receivable.isActive()).isTrue();
    verify(accountRepository).save(receivable);
  }

  @Test
  void createUser_nonSuperAdminCannotAssignSuperAdminRole() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    new CreateUserRequest(
                        "tenant-user@example.com", "Tenant User", List.of("ROLE_SUPER_ADMIN"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required");
  }

  @Test
  void createUser_nonSuperAdminCannotAssignAdminRoleWithoutPrefix() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    new CreateUserRequest(
                        "tenant-admin@example.com", "Tenant Admin", List.of("admin"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required for role: ROLE_ADMIN");

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED),
            eq("UNKNOWN_AUTH_ACTOR"),
            eq("TEST"),
            argThat(
                (Map<String, String> metadata) ->
                    "ROLE_ADMIN".equals(metadata.get("targetRole"))
                        && "tenant-admin-role-management-requires-super-admin"
                            .equals(metadata.get("reason"))
                        && "TEST".equals(metadata.get("tenantScope"))));
  }

  @Test
  void createUser_superAdminStillCannotAssignUnsupportedTenantAdminRoles() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    try {
      assertThatThrownBy(
              () ->
                  service.createUser(
                      new CreateUserRequest(
                          "platform-owner@example.com",
                          "Platform Owner",
                          List.of("ROLE_SUPER_ADMIN"))))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("Unsupported role for tenant-admin user management")
          .hasMessageContaining("ROLE_SUPER_ADMIN");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void createUser_superAdminCanAssignAllowlistedTenantRole() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    try {
      service.createUser(
          new CreateUserRequest("platform-sales@example.com", "Platform Sales", List.of("sales")));
      verify(userRepository).save(any(UserAccount.class));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void createUser_returnsCanonicalPersistedRolesAndCompanyWhenSavedAssociationsAreMissing() {
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(
            invocation -> {
              UserAccount persisted = invocation.getArgument(0);
              UserAccount detached =
                  new UserAccount(persisted.getEmail(), "encoded", persisted.getDisplayName());
              ReflectionTestUtils.setField(detached, "id", 401L);
              detached.setEnabled(persisted.isEnabled());
              detached.setMfaEnabled(persisted.isMfaEnabled());
              return detached;
            });

    var response =
        service.createUser(
            new CreateUserRequest(
                "fallback-user@example.com", "Fallback User", List.of("ROLE_SALES")));

    assertThat(response.roles()).isEmpty();
    assertThat(response.companyCode()).isNull();
  }

  @Test
  void createUser_issuesScopedBootstrapEmailAndForcesReset() {
    service.createUser(
        new CreateUserRequest("temp-user@example.com", "Temp User", List.of("ROLE_SALES")));

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    assertThat(userCaptor.getValue().isMustChangePassword()).isTrue();
    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("temp-user@example.com"),
            eq("Temp User"),
            argThat(password -> password != null && !password.isBlank()),
            eq("TEST"));
  }

  @Test
  void createUser_reusesExistingPortalDealerWithoutProvisioningAnotherAccount() {
    Dealer existingDealer = new Dealer();
    existingDealer.setCompany(company);
    ReflectionTestUtils.setField(existingDealer, "id", 55L);
    existingDealer.setCode("ACTIVE55");
    existingDealer.setName("Active Dealer");
    existingDealer.setEmail("dealer-active@example.com");

    Account receivable = new Account();
    receivable.setCompany(company);
    receivable.setCode("AR-ACTIVE55");
    receivable.setActive(true);
    existingDealer.setReceivableAccount(receivable);

    when(dealerRepository.findByCompanyAndPortalUserEmail(company, "dealer-active@example.com"))
        .thenReturn(Optional.of(existingDealer));

    service.createUser(
        new CreateUserRequest(
            "dealer-active@example.com", "Dealer Active", List.of("ROLE_DEALER")));

    verify(dealerRepository, times(1)).save(any(Dealer.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  void createUser_normalizedDealerRoleStillTriggersDealerProvisioning() {
    when(dealerRepository.findByCompanyAndPortalUserEmail(company, "dealer-normalized@example.com"))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndEmailIgnoreCase(company, "dealer-normalized@example.com"))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
        .thenReturn(Optional.empty());

    service.createUser(
        new CreateUserRequest(
            "dealer-normalized@example.com", "Dealer Normalized", List.of(" dealer ")));

    verify(dealerRepository, times(2)).save(any(Dealer.class));
    verify(accountRepository).save(any(Account.class));
  }

  @Test
  void listUsers_includesLastLoginAtDerivedFromLatestLoginAuditEvent() {
    UserAccount user = new UserAccount("audited-user@example.com", "hash", "Audited User");
    ReflectionTestUtils.setField(user, "id", 301L);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    AuditLog latestLogin = new AuditLog();
    latestLogin.setEventType(AuditEvent.LOGIN_SUCCESS);
    latestLogin.setUsername("AUDITED-USER@example.com");
    latestLogin.setTimestamp(LocalDateTime.of(2026, 1, 5, 10, 15, 30));

    when(userRepository.findByCompany_Id(company.getId())).thenReturn(List.of(user));
    when(auditLogRepository.findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS,
            company.getId(),
            java.util.Set.of("audited-user@example.com")))
        .thenReturn(
            List.of(
                new AuditLogRepository.UsernameLastLoginProjection() {
                  @Override
                  public String getUsernameKey() {
                    return "audited-user@example.com";
                  }

                  @Override
                  public LocalDateTime getLastLoginAt() {
                    return latestLogin.getTimestamp();
                  }
                }));

    var results = service.listUsers();

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().lastLoginAt())
        .isEqualTo(LocalDateTime.of(2026, 1, 5, 10, 15, 30).atZone(ZoneOffset.UTC).toInstant());
    verify(auditLogRepository)
        .findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS,
            company.getId(),
            java.util.Set.of("audited-user@example.com"));
  }

  @Test
  void listUsers_ignoresNullRolesAndBlankCompanyCodes() {
    UserAccount user = new UserAccount("filtered-user@example.com", "hash", "Filtered User");
    ReflectionTestUtils.setField(user, "id", 303L);
    user.setCompany(company);

    Role validRole = new Role();
    validRole.setName("ROLE_SALES");
    Role blankRole = new Role();
    blankRole.setName("   ");
    user.addRole(validRole);
    user.getRoles().add(blankRole);
    user.getRoles().add(null);

    when(userRepository.findByCompany_Id(company.getId())).thenReturn(List.of(user));
    when(auditLogRepository.findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS,
            company.getId(),
            java.util.Set.of("filtered-user@example.com")))
        .thenReturn(List.of());

    var results = service.listUsers();

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().companyCode()).isEqualTo("TEST");
    assertThat(results.getFirst().roles()).containsExactly("ROLE_SALES");
  }

  @Test
  void listUsers_filtersTenantAdminProtectedTargets() {
    UserAccount tenantAdmin = new UserAccount("tenant-admin@example.com", "hash", "Tenant Admin");
    ReflectionTestUtils.setField(tenantAdmin, "id", 910L);
    tenantAdmin.setCompany(company);
    Role tenantAdminRole = new Role();
    tenantAdminRole.setName("ROLE_ADMIN");
    tenantAdmin.addRole(tenantAdminRole);

    UserAccount tenantSuperAdmin =
        new UserAccount("tenant-superadmin@example.com", "hash", "Tenant Super Admin");
    ReflectionTestUtils.setField(tenantSuperAdmin, "id", 911L);
    tenantSuperAdmin.setCompany(company);
    Role tenantSuperAdminRole = new Role();
    tenantSuperAdminRole.setName("ROLE_SUPER_ADMIN");
    tenantSuperAdmin.addRole(tenantSuperAdminRole);

    UserAccount tenantSales = new UserAccount("tenant-sales@example.com", "hash", "Tenant Sales");
    ReflectionTestUtils.setField(tenantSales, "id", 912L);
    tenantSales.setCompany(company);
    Role tenantSalesRole = new Role();
    tenantSalesRole.setName("ROLE_SALES");
    tenantSales.addRole(tenantSalesRole);

    when(userRepository.findByCompany_Id(company.getId()))
        .thenReturn(List.of(tenantAdmin, tenantSuperAdmin, tenantSales));
    when(auditLogRepository.findLatestTimestampByEventTypeAndCompanyIdAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS,
            company.getId(),
            java.util.Set.of("tenant-sales@example.com")))
        .thenReturn(List.of());

    var results = service.listUsers();

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().email()).isEqualTo("tenant-sales@example.com");
    assertThat(results.getFirst().roles()).containsExactly("ROLE_SALES");
  }

  @Test
  void getUser_returnsScopedUserWithLastLoginAt() {
    UserAccount user = new UserAccount("detail-user@example.com", "hash", "Detail User");
    ReflectionTestUtils.setField(user, "id", 304L);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    AuditLog latestLogin = new AuditLog();
    latestLogin.setEventType(AuditEvent.LOGIN_SUCCESS);
    latestLogin.setUsername("detail-user@example.com");
    latestLogin.setTimestamp(LocalDateTime.of(2026, 2, 7, 8, 45, 0));

    when(userRepository.findById(304L)).thenReturn(Optional.of(user));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "detail-user@example.com"))
        .thenReturn(Optional.of(latestLogin));

    UserDto result = service.getUser(304L);

    assertThat(result.id()).isEqualTo(304L);
    assertThat(result.roles()).containsExactly("ROLE_SALES");
    assertThat(result.lastLoginAt())
        .isEqualTo(LocalDateTime.of(2026, 2, 7, 8, 45, 0).atZone(ZoneOffset.UTC).toInstant());
  }

  @Test
  void getUser_rejectsCrossTenantTargetForTenantAdmin() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-detail@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 305L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.findById(305L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.getUser(305L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Target user is out of scope for this operation");

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void getUser_rejectsSameTenantProtectedRoleTargetForTenantAdmin() {
    UserAccount tenantSuperAdmin =
        new UserAccount("tenant-superadmin-detail@example.com", "hash", "Tenant Super Admin");
    ReflectionTestUtils.setField(tenantSuperAdmin, "id", 399L);
    tenantSuperAdmin.setCompany(company);
    Role superAdminRole = new Role();
    superAdminRole.setName("ROLE_SUPER_ADMIN");
    tenantSuperAdmin.addRole(superAdminRole);

    when(userRepository.findById(399L)).thenReturn(Optional.of(tenantSuperAdmin));

    assertThatThrownBy(() -> service.getUser(399L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Target user is out of scope for this operation");

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void getUser_missingTargetForTenantAdmin_isAccessDeniedToAvoidEnumeration() {
    when(userRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getUser(999L))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Target user is out of scope for this operation");

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void updateUserStatus_disablingUserRevokesTokensSendsNotificationAndAudits() {
    UserAccount user = new UserAccount("status-user@example.com", "hash", "Status User");
    ReflectionTestUtils.setField(user, "id", 302L);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    when(userRepository.findById(302L)).thenReturn(Optional.of(user));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "status-user@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUserStatus(302L, false);

    assertThat(response.enabled()).isFalse();
    verify(tokenBlacklistService).revokeAllUserTokens(user.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(user.getPublicId());
    verify(emailService).sendUserSuspendedEmail("status-user@example.com", "Status User");
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.USER_DEACTIVATED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void updateUserStatus_enablingUserChecksQuotaAndDoesNotSendSuspensionEmail() {
    UserAccount user = new UserAccount("reenable-user@example.com", "hash", "Reenabled User");
    ReflectionTestUtils.setField(user, "id", 303L);
    user.setEnabled(false);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    when(userRepository.findById(303L)).thenReturn(Optional.of(user));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "reenable-user@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUserStatus(303L, true);

    assertThat(response.enabled()).isTrue();
    verify(tenantRuntimePolicyService).assertCanAddEnabledUser(company, "ADMIN_USER_STATUS");
    verify(emailService, never()).sendUserSuspendedEmail(anyString(), anyString());
    verify(tokenBlacklistService, never()).revokeAllUserTokens(user.getPublicId().toString());
    verify(refreshTokenService, never()).revokeAllForUser(user.getPublicId());
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.USER_ACTIVATED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void forceResetPassword_delegatesToPasswordResetServiceAndAudits() {
    UserAccount user = new UserAccount("force-reset@example.com", "hash", "Force Reset");
    ReflectionTestUtils.setField(user, "id", 304L);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    when(userRepository.findById(304L)).thenReturn(Optional.of(user));

    service.forceResetPassword(304L);

    verify(passwordResetService).requestResetByAdmin(user);
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("UNKNOWN_AUTH_ACTOR"),
            eq("TEST"),
            any(Map.class));
  }

  @Test
  void forceResetPassword_crossTenantUser_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 311L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.findById(311L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.forceResetPassword(311L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).findById(311L);
    verify(userRepository, never()).lockById(311L);
    verify(userRepository, never()).lockByIdAndCompanyId(311L, 1L);
    verify(passwordResetService, never()).requestResetByAdmin(any(UserAccount.class));
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void
      forceResetPassword_sameTenantProtectedRole_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
    UserAccount protectedUser =
        new UserAccount("tenant-admin-protected@example.com", "hash", "Tenant Admin");
    ReflectionTestUtils.setField(protectedUser, "id", 313L);
    protectedUser.setCompany(company);
    Role protectedRole = new Role();
    protectedRole.setName("ROLE_ADMIN");
    protectedUser.addRole(protectedRole);

    when(userRepository.findById(313L)).thenReturn(Optional.of(protectedUser));

    assertThatThrownBy(() -> service.forceResetPassword(313L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).findById(313L);
    verify(userRepository, never()).lockById(313L);
    verify(userRepository, never()).lockByIdAndCompanyId(313L, 1L);
    verify(passwordResetService, never()).requestResetByAdmin(any(UserAccount.class));
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void updateUserStatus_crossTenantUser_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 312L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.findById(312L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.updateUserStatus(312L, false))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).findById(312L);
    verify(userRepository, never()).lockById(312L);
    verify(userRepository, never()).lockByIdAndCompanyId(312L, 1L);
    verify(userRepository, never()).save(any(UserAccount.class));
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void updateUser_allowsSuperAdminToTargetForeignTenantUser() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 305L);
    foreignUser.setCompany(foreignCompany);
    Role role = new Role();
    role.setName("ROLE_SALES");
    foreignUser.addRole(role);

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    when(userRepository.findById(305L)).thenReturn(Optional.of(foreignUser));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, foreignCompany.getId(), "foreign-user@example.com"))
        .thenReturn(Optional.empty());

    try {
      var response = service.updateUser(305L, new UpdateUserRequest("Foreign User Updated", null));
      assertThat(response.displayName()).isEqualTo("Foreign User Updated");
    } finally {
      SecurityContextHolder.clearContext();
    }

    verify(userRepository).findById(305L);
    verify(userRepository, never()).lockByIdAndCompanyId(eq(305L), any());
  }

  @Test
  void updateUser_withoutRoleChangeDoesNotTriggerReauthRevocation() {
    UserAccount user = new UserAccount("same-enabled@example.com", "hash", "Same Enabled");
    ReflectionTestUtils.setField(user, "id", 401L);
    user.setCompany(company);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    when(userRepository.findById(401L)).thenReturn(Optional.of(user));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "same-enabled@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUser(401L, new UpdateUserRequest("Same Enabled Updated", null));

    assertThat(response.displayName()).isEqualTo("Same Enabled Updated");
    verify(tokenBlacklistService, never()).revokeAllUserTokens(user.getPublicId().toString());
    verify(refreshTokenService, never()).revokeAllForUser(user.getPublicId());
    verify(tenantRuntimePolicyService, never())
        .assertCanAddEnabledUser(any(Company.class), anyString());
  }

  @Test
  void updateUser_updatesRolesAndRevokesTokens() {
    UserAccount user = new UserAccount("update-user@example.com", "hash", "Update User");
    ReflectionTestUtils.setField(user, "id", 402L);
    user.setCompany(company);
    Role existingRole = new Role();
    existingRole.setName("ROLE_DEALER");
    user.addRole(existingRole);

    when(userRepository.findById(402L)).thenReturn(Optional.of(user));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "update-user@example.com"))
        .thenReturn(Optional.empty());

    var response =
        service.updateUser(402L, new UpdateUserRequest("Updated User", List.of("ROLE_SALES")));

    assertThat(response.displayName()).isEqualTo("Updated User");
    assertThat(response.roles()).containsExactly("ROLE_SALES");
    assertThat(response.companyCode()).isEqualTo("TEST");
    verify(tokenBlacklistService).revokeAllUserTokens(user.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(user.getPublicId());
  }

  @Test
  void updateUser_equivalentRoleSetDoesNotTriggerReauthRevocation() {
    UserAccount user = new UserAccount("update-same-role@example.com", "hash", "Update User");
    ReflectionTestUtils.setField(user, "id", 409L);
    user.setCompany(company);
    user.setEnabled(true);
    Role existingRole = new Role();
    existingRole.setName("ROLE_SALES");
    user.addRole(existingRole);

    when(userRepository.findById(409L)).thenReturn(Optional.of(user));
    when(auditLogRepository
            .findFirstByEventTypeAndCompanyIdAndUsernameIgnoreCaseOrderByTimestampDesc(
                AuditEvent.LOGIN_SUCCESS, company.getId(), "update-same-role@example.com"))
        .thenReturn(Optional.empty());

    var response =
        service.updateUser(
            409L, new UpdateUserRequest("Updated User", List.of("sales", "ROLE_SALES")));

    assertThat(response.displayName()).isEqualTo("Updated User");
    assertThat(response.roles()).containsExactly("ROLE_SALES");
    verify(roleService, never()).ensureRoleExists(anyString());
    verify(tokenBlacklistService, never()).revokeAllUserTokens(anyString());
    verify(refreshTokenService, never()).revokeAllForUser(any());
  }

  @Test
  void updateUser_rejectsUnsupportedRoleBeforeMutatingAssignedRoles() {
    UserAccount user = new UserAccount("update-invalid-role@example.com", "hash", "Update User");
    ReflectionTestUtils.setField(user, "id", 403L);
    user.setCompany(company);
    user.setEnabled(true);
    Role existingRole = new Role();
    existingRole.setName("ROLE_DEALER");
    user.addRole(existingRole);

    when(userRepository.findById(403L)).thenReturn(Optional.of(user));

    assertThatThrownBy(
            () ->
                service.updateUser(
                    403L, new UpdateUserRequest("Updated User", List.of("ROLE_SUPER_ADMIN"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required for role: ROLE_SUPER_ADMIN");

    assertThat(user.getRoles()).extracting(Role::getName).containsExactly("ROLE_DEALER");
    verify(userRepository, never()).save(any(UserAccount.class));
    verify(emailService, never()).sendUserSuspendedEmail(anyString(), anyString());
    verify(tokenBlacklistService, never()).revokeAllUserTokens(anyString());
    verify(refreshTokenService, never()).revokeAllForUser(any());
  }

  @Test
  void suspend_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 306L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.lockByIdAndCompanyId(306L, 1L)).thenReturn(Optional.empty());
    when(userRepository.findById(306L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.suspend(306L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).lockByIdAndCompanyId(306L, 1L);
    verify(userRepository, never()).lockById(306L);
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
    verify(userRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void unsuspend_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 308L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.lockByIdAndCompanyId(308L, 1L)).thenReturn(Optional.empty());
    when(userRepository.findById(308L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.unsuspend(308L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).lockByIdAndCompanyId(308L, 1L);
    verify(userRepository, never()).lockById(308L);
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
    verify(userRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void deleteUser_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 309L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.lockByIdAndCompanyId(309L, 1L)).thenReturn(Optional.empty());
    when(userRepository.findById(309L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.deleteUser(309L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).lockByIdAndCompanyId(309L, 1L);
    verify(userRepository, never()).lockById(309L);
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
    verify(userRepository, never()).delete(any(UserAccount.class));
  }

  @Test
  void disableMfa_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 310L);
    foreignUser.setCompany(foreignCompany);

    when(userRepository.lockByIdAndCompanyId(310L, 1L)).thenReturn(Optional.empty());
    when(userRepository.findById(310L)).thenReturn(Optional.of(foreignUser));

    assertThatThrownBy(() -> service.disableMfa(310L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User not found");

    verify(userRepository).lockByIdAndCompanyId(310L, 1L);
    verify(userRepository, never()).lockById(310L);
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.ACCESS_DENIED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
    verify(userRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void suspend_allowsSuperAdminToTargetForeignTenantUser() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 307L);
    foreignUser.setCompany(foreignCompany);

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    when(userRepository.lockById(307L)).thenReturn(Optional.of(foreignUser));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    try {
      service.suspend(307L);
    } finally {
      SecurityContextHolder.clearContext();
    }

    assertThat(foreignUser.isEnabled()).isFalse();
    verify(tokenBlacklistService).revokeAllUserTokens(foreignUser.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(foreignUser.getPublicId());
  }

  @Test
  void helper_createDealerForUser_buildsFreshDealerAndReceivableWhenNoDealerExists() {
    UserAccount user = new UserAccount("fresh-dealer@example.com", "TEST", "hash", "Fresh Dealer");
    Company tenant = new Company();
    ReflectionTestUtils.setField(tenant, "id", 8L);
    tenant.setCode("TEST");
    when(dealerRepository.findByCompanyAndPortalUserEmail(tenant, user.getEmail()))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndEmailIgnoreCase(tenant, user.getEmail()))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
        .thenReturn(Optional.empty());

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "createDealerForUser", user, tenant);

    verify(dealerRepository, times(2)).save(any(Dealer.class));
    verify(accountRepository).save(any(Account.class));
  }

  @Test
  void helper_scopeResolutionAndProtectionBranches_failClosed() {
    UserAccount user = new UserAccount("user@example.com", "TEST", "hash", "User");
    ReflectionTestUtils.setField(user, "id", 901L);
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 22L);
    foreignCompany.setCode("FOREIGN");
    user.setCompany(foreignCompany);

    assertThat(
            (Object)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "resolveActorScopedTargetCompanies", user, (Company) null))
        .isEqualTo(List.of());

    assertThat(
            (Object)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "resolveActorScopedTargetCompanies", null, company))
        .isEqualTo(List.of(company));

    Company protectedTenant = new Company();
    ReflectionTestUtils.setField(protectedTenant, "id", 1L);
    protectedTenant.setCode("TEST");
    protectedTenant.setMainAdminUserId(901L);
    user.setCompany(protectedTenant);

    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "assertNotProtectedMainAdmin", user, company, "disable"))
        .hasMessageContaining("Replace the tenant main admin");
  }

  @Test
  void helper_validateAndNormalizeAssignableRoles_rejectsInvalidAndNormalizesAllowedRoles() {
    UserAccount user = new UserAccount("user@example.com", "TEST", "hash", "User");
    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "validateAndNormalizeAssignableRoles", List.of(" "), company))
        .hasMessageContaining("Role entries cannot be blank");

    assertThatThrownBy(
            () ->
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "validateAndNormalizeAssignableRoles", List.of("admin"), company))
        .hasMessageContaining("SUPER_ADMIN authority required for role: ROLE_ADMIN");

    @SuppressWarnings("unchecked")
    List<String> normalizedRoles =
        (List<String>)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                service,
                "validateAndNormalizeAssignableRoles",
                List.of("sales", "ROLE_SALES", "ROLE_FACTORY"),
                company);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        service, "attachRoles", user, normalizedRoles);

    assertThat(user.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_SALES", "ROLE_FACTORY");
  }

  @Test
  void helper_lastLoginLookups_failClosedWhenInputsAreMissing() {
    assertThat(
            (Object)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "resolveLastLoginByEmail", null, List.of(new UserAccount())))
        .isEqualTo(Map.of());

    UserAccount blankEmailUser = new UserAccount("user@example.com", "TEST", "hash", "User");
    blankEmailUser.setEmail(" ");
    assertThat(
            (Object)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    service, "resolveLastLoginAt", blankEmailUser))
        .isNull();
  }
}
