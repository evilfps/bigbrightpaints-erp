package com.bigbrightpaints.erp.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.dto.CreateUserRequest;
import com.bigbrightpaints.erp.modules.admin.dto.UpdateUserRequest;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

  @Mock private UserAccountRepository userRepository;
  @Mock private CompanyContextService companyContextService;
  @Mock private CompanyRepository companyRepository;
  @Mock private RoleService roleService;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private EmailService emailService;
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

  @BeforeEach
  void setUp() {
    service =
        new AdminUserService(
            userRepository,
            companyContextService,
            companyRepository,
            roleService,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService,
            passwordResetService,
            auditService,
            auditLogRepository,
            dealerRepository,
            accountRepository,
            tenantRuntimePolicyService);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 1L);
    company.setCode("TEST");
    lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
    lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");
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
        new CreateUserRequest(
            "dealer@example.com",
            "Password@123",
            "Dealer User",
            List.of(1L),
            List.of("ROLE_DEALER")));

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
  void createUser_superAdminCanAssignUserToRequestedTenantCompany() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 2L);
    foreignCompany.setCode("FOREIGN");

    when(companyRepository.findAllById(any())).thenReturn(List.of(foreignCompany));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));

    try {
      service.createUser(
          new CreateUserRequest(
              "ops-user@example.com",
              "Password@123",
              "Ops User",
              List.of(2L),
              List.of("ROLE_SALES")));
    } finally {
      SecurityContextHolder.clearContext();
    }

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    UserAccount savedUser = userCaptor.getValue();
    assertThat(savedUser.getCompanies()).extracting(Company::getCode).containsExactly("FOREIGN");
    verify(tenantRuntimePolicyService).assertCanAddEnabledUser(foreignCompany, "ADMIN_USER_CREATE");
  }

  @Test
  void createUser_nonSuperAdminRejectsForeignCompanyScope() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    new CreateUserRequest(
                        "scope-user@example.com",
                        "Password@123",
                        "Scope User",
                        List.of(2L),
                        List.of("ROLE_SALES"))))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("User must be assigned to the active company");
  }

  @Test
  void createUser_superAdminRejectsMissingCompanyAssignments() {
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
                          "missing-company@example.com",
                          "Password@123",
                          "Missing Company",
                          List.of(),
                          List.of("ROLE_SALES"))))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("User must belong to an active company");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void createUser_superAdminRejectsUnknownCompanyId() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    when(companyRepository.findAllById(any())).thenReturn(List.of());
    try {
      assertThatThrownBy(
              () ->
                  service.createUser(
                      new CreateUserRequest(
                          "unknown-company@example.com",
                          "Password@123",
                          "Unknown Company",
                          List.of(99L),
                          List.of("ROLE_SALES"))))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("Company not found: 99");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void createUser_nonSuperAdminCannotAssignSuperAdminRole() {
    assertThatThrownBy(
            () ->
                service.createUser(
                    new CreateUserRequest(
                        "tenant-user@example.com",
                        "Password@123",
                        "Tenant User",
                        List.of(1L),
                        List.of("ROLE_SUPER_ADMIN"))))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("SUPER_ADMIN authority required");
  }

  @Test
  void createUser_nonSuperAdminCannotAssignAdminRoleWithoutPrefix() {
    when(roleService.isSystemRole("ROLE_ADMIN")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createUser(
                    new CreateUserRequest(
                        "tenant-admin@example.com",
                        "Password@123",
                        "Tenant Admin",
                        List.of(1L),
                        List.of("admin"))))
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
  void createUser_superAdminCanAssignSuperAdminRole() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    when(companyRepository.findAllById(any())).thenReturn(List.of(company));
    try {
      service.createUser(
          new CreateUserRequest(
              "platform-owner@example.com",
              "Password@123",
              "Platform Owner",
              List.of(1L),
              List.of("ROLE_SUPER_ADMIN")));
      verify(userRepository).save(any(UserAccount.class));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @Test
  void createUser_returnsFallbackRolesAndCompaniesWhenSavedAssociationsAreMissing() {
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "fallback-user@example.com"))
        .thenReturn(Optional.empty());
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
                "fallback-user@example.com",
                "Password@123",
                "Fallback User",
                List.of(1L),
                List.of("ROLE_SALES")));

    assertThat(response.roles()).containsExactly("ROLE_SALES");
    assertThat(response.companies()).containsExactly("TEST");
  }

  @Test
  void createUser_withoutPasswordIssuesTemporaryPasswordAndForcesReset() {
    service.createUser(
        new CreateUserRequest(
            "temp-user@example.com", null, "Temp User", List.of(1L), List.of("ROLE_SALES")));

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    assertThat(userCaptor.getValue().isMustChangePassword()).isTrue();
    verify(emailService)
        .sendUserCredentialsEmail(
            eq("temp-user@example.com"),
            eq("Temp User"),
            argThat(password -> password != null && !password.isBlank()));
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
            "dealer-active@example.com",
            "Password@123",
            "Dealer Active",
            List.of(1L),
            List.of("ROLE_DEALER")));

    verify(dealerRepository, times(1)).save(any(Dealer.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  void listUsers_includesLastLoginAtDerivedFromLatestLoginAuditEvent() {
    UserAccount user = new UserAccount("audited-user@example.com", "hash", "Audited User");
    ReflectionTestUtils.setField(user, "id", 301L);
    user.addCompany(company);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    user.addRole(role);

    AuditLog latestLogin = new AuditLog();
    latestLogin.setEventType(AuditEvent.LOGIN_SUCCESS);
    latestLogin.setUsername("AUDITED-USER@example.com");
    latestLogin.setTimestamp(LocalDateTime.of(2026, 1, 5, 10, 15, 30));

    when(userRepository.findDistinctByCompanies_Id(company.getId())).thenReturn(List.of(user));
    when(auditLogRepository.findLatestTimestampByEventTypeAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS, java.util.Set.of("audited-user@example.com")))
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
  }

  @Test
  void listUsers_ignoresNullRolesAndBlankCompanyCodes() {
    UserAccount user = new UserAccount("filtered-user@example.com", "hash", "Filtered User");
    ReflectionTestUtils.setField(user, "id", 303L);
    user.addCompany(company);
    Company blankCompany = new Company();
    blankCompany.setCode("   ");
    user.getCompanies().add(blankCompany);
    user.getCompanies().add(null);

    Role validRole = new Role();
    validRole.setName("ROLE_ADMIN");
    Role blankRole = new Role();
    blankRole.setName("   ");
    user.addRole(validRole);
    user.getRoles().add(blankRole);
    user.getRoles().add(null);

    when(userRepository.findDistinctByCompanies_Id(company.getId())).thenReturn(List.of(user));
    when(auditLogRepository.findLatestTimestampByEventTypeAndUsernameIn(
            AuditEvent.LOGIN_SUCCESS, java.util.Set.of("filtered-user@example.com")))
        .thenReturn(List.of());

    var results = service.listUsers();

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().companies()).containsExactly("TEST");
    assertThat(results.getFirst().roles()).containsExactly("ROLE_ADMIN");
  }

  @Test
  void updateUserStatus_disablingUserRevokesTokensSendsNotificationAndAudits() {
    UserAccount user = new UserAccount("status-user@example.com", "hash", "Status User");
    ReflectionTestUtils.setField(user, "id", 302L);
    user.addCompany(company);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    user.addRole(role);

    when(userRepository.findById(302L)).thenReturn(Optional.of(user));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "status-user@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUserStatus(302L, false);

    assertThat(response.enabled()).isFalse();
    verify(tokenBlacklistService).revokeAllUserTokens("status-user@example.com");
    verify(refreshTokenService).revokeAllForUser("status-user@example.com");
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
    user.addCompany(company);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    user.addRole(role);

    when(userRepository.findById(303L)).thenReturn(Optional.of(user));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "reenable-user@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUserStatus(303L, true);

    assertThat(response.enabled()).isTrue();
    verify(tenantRuntimePolicyService).assertCanAddEnabledUser(company, "ADMIN_USER_STATUS");
    verify(emailService, never()).sendUserSuspendedEmail(anyString(), anyString());
    verify(tokenBlacklistService, never()).revokeAllUserTokens("reenable-user@example.com");
    verify(refreshTokenService, never()).revokeAllForUser("reenable-user@example.com");
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.USER_ACTIVATED), eq("UNKNOWN_AUTH_ACTOR"), eq("TEST"), any(Map.class));
  }

  @Test
  void forceResetPassword_delegatesToPasswordResetServiceAndAudits() {
    UserAccount user = new UserAccount("force-reset@example.com", "hash", "Force Reset");
    ReflectionTestUtils.setField(user, "id", 304L);
    user.addCompany(company);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
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
    foreignUser.addCompany(foreignCompany);

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
  void updateUserStatus_crossTenantUser_forTenantAdmin_masksTargetAsMissingWithoutLocking() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 312L);
    foreignUser.addCompany(foreignCompany);

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
    foreignUser.addCompany(foreignCompany);
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
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "foreign-user@example.com"))
        .thenReturn(Optional.empty());

    try {
      var response =
          service.updateUser(305L, new UpdateUserRequest("Foreign User Updated", null, null, true));
      assertThat(response.displayName()).isEqualTo("Foreign User Updated");
    } finally {
      SecurityContextHolder.clearContext();
    }

    verify(userRepository).findById(305L);
    verify(userRepository, never()).findByIdAndCompanies_Id(eq(305L), any());
  }

  @Test
  void updateUser_sameEnabledStateDoesNotTriggerReauthRevocation() {
    UserAccount user = new UserAccount("same-enabled@example.com", "hash", "Same Enabled");
    ReflectionTestUtils.setField(user, "id", 401L);
    user.addCompany(company);
    user.setEnabled(true);
    Role role = new Role();
    role.setName("ROLE_SALES");
    user.addRole(role);

    when(userRepository.findById(401L)).thenReturn(Optional.of(user));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "same-enabled@example.com"))
        .thenReturn(Optional.empty());

    var response =
        service.updateUser(401L, new UpdateUserRequest("Same Enabled Updated", null, null, true));

    assertThat(response.enabled()).isTrue();
    verify(tokenBlacklistService, never()).revokeAllUserTokens("same-enabled@example.com");
    verify(refreshTokenService, never()).revokeAllForUser("same-enabled@example.com");
    verify(tenantRuntimePolicyService, never())
        .assertCanAddEnabledUser(any(Company.class), anyString());
  }

  @Test
  void updateUser_reassignsScopedCompaniesAndRolesAndRevokesTokens() {
    UserAccount user = new UserAccount("update-user@example.com", "hash", "Update User");
    ReflectionTestUtils.setField(user, "id", 402L);
    user.addCompany(company);
    Role existingRole = new Role();
    existingRole.setName("ROLE_DEALER");
    user.addRole(existingRole);

    when(userRepository.findById(402L)).thenReturn(Optional.of(user));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "update-user@example.com"))
        .thenReturn(Optional.empty());

    var response =
        service.updateUser(
            402L, new UpdateUserRequest("Updated User", List.of(1L), List.of("ROLE_SALES"), null));

    assertThat(response.displayName()).isEqualTo("Updated User");
    assertThat(response.roles()).containsExactly("ROLE_SALES");
    assertThat(response.companies()).containsExactly("TEST");
    verify(tokenBlacklistService).revokeAllUserTokens("update-user@example.com");
    verify(refreshTokenService).revokeAllForUser("update-user@example.com");
  }

  @Test
  void suspend_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 306L);
    foreignUser.addCompany(foreignCompany);

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
    foreignUser.addCompany(foreignCompany);

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
    foreignUser.addCompany(foreignCompany);

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
  void deleteUser_rejectsMainAdminForActorCompany() {
    company.setMainAdminUserId(909L);
    UserAccount mainAdmin = new UserAccount("main-admin@example.com", "hash", "Main Admin");
    ReflectionTestUtils.setField(mainAdmin, "id", 909L);
    mainAdmin.addCompany(company);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    mainAdmin.addRole(role);

    when(userRepository.lockByIdAndCompanyId(909L, 1L)).thenReturn(Optional.of(mainAdmin));

    assertThatThrownBy(() -> service.deleteUser(909L))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Replace the tenant main admin before attempting to delete this user");

    verify(userRepository, never()).delete(mainAdmin);
    verify(tokenBlacklistService, never()).revokeAllUserTokens("main-admin@example.com");
    verify(refreshTokenService, never()).revokeAllForUser("main-admin@example.com");
  }

  @Test
  void assertNotProtectedMainAdmin_returnsWhenUserIsNullOrMissingId() {
    UserAccount pendingUser = new UserAccount("pending-user@example.com", "hash", "Pending User");

    assertThatCode(
            () -> ReflectionTestUtils.invokeMethod(
                service, "assertNotProtectedMainAdmin", null, company, "delete"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> ReflectionTestUtils.invokeMethod(
                service, "assertNotProtectedMainAdmin", pendingUser, company, "delete"))
        .doesNotThrowAnyException();
  }

  @Test
  void assertNotProtectedMainAdmin_ignoresNullCompaniesAndUnassignedMainAdmins() {
    UserAccount user = new UserAccount("tenant-user@example.com", "hash", "Tenant User");
    ReflectionTestUtils.setField(user, "id", 911L);
    user.addCompany(null);
    user.addCompany(new Company());
    user.addCompany(company);

    assertThatCode(
            () -> ReflectionTestUtils.invokeMethod(
                service, "assertNotProtectedMainAdmin", user, company, "disable"))
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveActorScopedTargetCompanies_returnsEmptyWhenActorCompanyMissingId() {
    Company actorCompanyWithoutId = new Company();
    actorCompanyWithoutId.setCode("NO-ID");
    UserAccount user = new UserAccount("tenant-user@example.com", "hash", "Tenant User");
    ReflectionTestUtils.setField(user, "id", 912L);

    List<Company> scopedCompanies =
        (List<Company>)
            ReflectionTestUtils.invokeMethod(
                service, "resolveActorScopedTargetCompanies", user, actorCompanyWithoutId);

    assertThat(scopedCompanies).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveActorScopedTargetCompanies_returnsActorCompanyForNullMissingOrEmptyAssignments() {
    UserAccount userWithNullCompanies =
        new UserAccount("null-companies@example.com", "hash", "Null Companies");
    ReflectionTestUtils.setField(userWithNullCompanies, "id", 913L);
    ReflectionTestUtils.setField(userWithNullCompanies, "companies", null);
    UserAccount userWithEmptyCompanies =
        new UserAccount("empty-companies@example.com", "hash", "Empty Companies");
    ReflectionTestUtils.setField(userWithEmptyCompanies, "id", 914L);

    List<Company> scopedForNullUser =
        (List<Company>)
            ReflectionTestUtils.invokeMethod(
                service, "resolveActorScopedTargetCompanies", null, company);
    List<Company> scopedForNullCompanies =
        (List<Company>)
            ReflectionTestUtils.invokeMethod(
                service, "resolveActorScopedTargetCompanies", userWithNullCompanies, company);
    List<Company> scopedForEmptyCompanies =
        (List<Company>)
            ReflectionTestUtils.invokeMethod(
                service, "resolveActorScopedTargetCompanies", userWithEmptyCompanies, company);

    assertThat(scopedForNullUser).containsExactly(company);
    assertThat(scopedForNullCompanies).containsExactly(company);
    assertThat(scopedForEmptyCompanies).containsExactly(company);
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveActorScopedTargetCompanies_filtersToActorCompanyAndSkipsNullEntries() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");
    UserAccount user = new UserAccount("shared-user@example.com", "hash", "Shared User");
    ReflectionTestUtils.setField(user, "id", 915L);
    user.addCompany(null);
    user.addCompany(company);
    user.addCompany(foreignCompany);

    List<Company> scopedCompanies =
        (List<Company>)
            ReflectionTestUtils.invokeMethod(
                service, "resolveActorScopedTargetCompanies", user, company);

    assertThat(scopedCompanies).containsExactly(company);
  }

  @Test
  void disableMfa_crossTenantUser_forTenantAdmin_usesScopedLockAndMasksTargetAsMissing() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 310L);
    foreignUser.addCompany(foreignCompany);

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
  void updateUserStatus_tenantAdminIgnoresForeignMainAdminProtectionOnSharedUser() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");
    foreignCompany.setMainAdminUserId(777L);

    UserAccount sharedUser = new UserAccount("shared-user@example.com", "hash", "Shared User");
    ReflectionTestUtils.setField(sharedUser, "id", 777L);
    sharedUser.addCompany(company);
    sharedUser.addCompany(foreignCompany);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    sharedUser.addRole(role);

    when(userRepository.findById(777L)).thenReturn(Optional.of(sharedUser));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(auditLogRepository.findFirstByEventTypeAndUsernameIgnoreCaseOrderByTimestampDesc(
            AuditEvent.LOGIN_SUCCESS, "shared-user@example.com"))
        .thenReturn(Optional.empty());

    var response = service.updateUserStatus(777L, false);

    assertThat(response.enabled()).isFalse();
    verify(userRepository).save(sharedUser);
    verify(tokenBlacklistService).revokeAllUserTokens("shared-user@example.com");
    verify(refreshTokenService).revokeAllForUser("shared-user@example.com");
  }

  @Test
  void updateUserStatus_superAdminRejectsSharedForeignMainAdminDisable() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");
    foreignCompany.setMainAdminUserId(777L);

    UserAccount sharedUser = new UserAccount("shared-user@example.com", "hash", "Shared User");
    ReflectionTestUtils.setField(sharedUser, "id", 777L);
    sharedUser.addCompany(company);
    sharedUser.addCompany(foreignCompany);
    Role role = new Role();
    role.setName("ROLE_ADMIN");
    sharedUser.addRole(role);

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "super-admin@bbp.com",
                "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    when(userRepository.findById(777L)).thenReturn(Optional.of(sharedUser));

    try {
      assertThatThrownBy(() -> service.updateUserStatus(777L, false))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining(
              "Replace the tenant main admin before attempting to disable this user");
    } finally {
      SecurityContextHolder.clearContext();
    }

    verify(userRepository, never()).save(sharedUser);
    verify(tokenBlacklistService, never()).revokeAllUserTokens("shared-user@example.com");
    verify(refreshTokenService, never()).revokeAllForUser("shared-user@example.com");
  }

  @Test
  void suspend_allowsSuperAdminToTargetForeignTenantUser() {
    Company foreignCompany = new Company();
    ReflectionTestUtils.setField(foreignCompany, "id", 21L);
    foreignCompany.setCode("FOREIGN");

    UserAccount foreignUser = new UserAccount("foreign-user@example.com", "hash", "Foreign User");
    ReflectionTestUtils.setField(foreignUser, "id", 307L);
    foreignUser.addCompany(foreignCompany);

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
    verify(tokenBlacklistService).revokeAllUserTokens("foreign-user@example.com");
    verify(refreshTokenService).revokeAllForUser("foreign-user@example.com");
  }
}
