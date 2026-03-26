package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;

@ExtendWith(MockitoExtension.class)
class TenantAdminProvisioningServiceTest {

  @Mock private UserAccountRepository userAccountRepository;

  @Mock private RoleService roleService;

  @Mock private RoleRepository roleRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private EmailService emailService;

  @Mock private TokenBlacklistService tokenBlacklistService;

  @Mock private RefreshTokenService refreshTokenService;

  @Test
  void provisionInitialAdmin_savesUserAndEmailsCredentials() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company company = company(10L, "SKE", "SKE");
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    when(userAccountRepository.findByEmailIgnoreCase("new-admin@ske.com"))
        .thenReturn(Optional.empty());
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(
            invocation -> {
              UserAccount saved = invocation.getArgument(0);
              ReflectionTestUtils.setField(saved, "id", 91L);
              return saved;
            });

    TenantAdminProvisioningService.ProvisionedTenantAdmin provisioned =
        service.provisionInitialAdmin(company, "new-admin@ske.com", "New Admin");

    assertThat(provisioned.email()).isEqualTo("new-admin@ske.com");
    assertThat(provisioned.userId()).isNotNull();
    verify(userAccountRepository).saveAndFlush(any(UserAccount.class));
    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("new-admin@ske.com"), eq("New Admin"), any(), eq("SKE"));
  }

  @Test
  void provisionInitialAdmin_usesCompanyAdminFallbackDisplayNameWhenCompanyNameBlank() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company company = company(10L, "SKE", "   ");
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    when(userAccountRepository.findByEmailIgnoreCase("new-admin@ske.com"))
        .thenReturn(Optional.empty());
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TenantAdminProvisioningService.ProvisionedTenantAdmin provisioned =
        service.provisionInitialAdmin(company, "new-admin@ske.com", "   ");

    assertThat(provisioned.email()).isEqualTo("new-admin@ske.com");
    verify(emailService)
        .sendUserCredentialsEmailRequired(
            eq("new-admin@ske.com"), eq("Company Admin"), any(), eq("SKE"));
  }

  @Test
  void provisionInitialAdmin_reloadsPersistedAdminRoleAfterSynchronization() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company company = company(10L, "SKE", "SKE");
    Role persistedRole = new Role();
    ReflectionTestUtils.setField(persistedRole, "id", 42L);
    persistedRole.setName("ROLE_ADMIN");
    when(userAccountRepository.findByEmailIgnoreCase("new-admin@ske.com"))
        .thenReturn(Optional.empty());
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(persistedRole));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.saveAndFlush(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TenantAdminProvisioningService.ProvisionedTenantAdmin provisioned =
        service.provisionInitialAdmin(company, "new-admin@ske.com", "New Admin");

    assertThat(provisioned.email()).isEqualTo("new-admin@ske.com");
    verify(roleRepository).findByName("ROLE_ADMIN");
    verify(roleService).ensureRoleExists("ROLE_ADMIN");
  }

  @Test
  void provisionInitialAdmin_failsFastWhenAdminRoleMissingAfterSynchronization() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company company = company(10L, "SKE", "SKE");
    when(userAccountRepository.findByEmailIgnoreCase("new-admin@ske.com"))
        .thenReturn(Optional.empty());
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.provisionInitialAdmin(company, "new-admin@ske.com", "New Admin"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("ROLE_ADMIN must exist before tenant admin provisioning");
    verify(roleService).ensureRoleExists("ROLE_ADMIN");
  }

  @Test
  void provisionInitialAdmin_rejectsBlankEmail() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company company = company(10L, "SKE", "SKE");

    assertThatThrownBy(() -> service.provisionInitialAdmin(company, "   ", "New Admin"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("firstAdminEmail is required");
  }

  @Test
  void provisionInitialAdmin_fallsBackToDefaultDisplayNameWhenCompanyMissingInHelper() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);

    String displayName =
        ReflectionTestUtils.invokeMethod(service, "resolveFirstAdminDisplayName", "   ", null);

    assertThat(displayName).isEqualTo("Company Admin");
  }

  @Test
  void resetTenantAdminPassword_rejectsTransientCompanyTarget() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company transientCompany = company(null, "SKE", "SKE");

    assertThatThrownBy(() -> service.resetTenantAdminPassword(transientCompany, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company must exist");
  }

  @Test
  void resetTenantAdminPassword_rejectsUserOutsideCompany() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    Company other = company(56L, "OTH", "Other");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(other);
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    user.addRole(adminRole);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not assigned to company");
  }

  @Test
  void resetTenantAdminPassword_rejectsUserAssignedOnlyToTransientCompanyMembership() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    Company transientAssignment = company(null, "SKE", "SKE");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(transientAssignment);
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    user.addRole(adminRole);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not assigned to company");
  }

  @Test
  void resetTenantAdminPassword_surfacesCredentialEmailDispatchFailures() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(target);
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    user.addRole(adminRole);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.save(any(UserAccount.class))).thenReturn(user);
    doThrow(new ApplicationException(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR, "smtp-failed"))
        .when(emailService)
        .sendUserCredentialsEmailRequired(eq("admin@ske.com"), eq("Admin"), any(), eq("SKE"));

    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("smtp-failed");
  }

  @Test
  void resetTenantAdminPassword_rejectsWhenRoleCollectionMissing() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(target);
    ReflectionTestUtils.setField(user, "roles", null);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.resetTenantAdminPassword(target, "admin@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not an admin");
  }

  @Test
  void resetTenantAdminPassword_ignoresBlankRoleNamesAndAcceptsSuperAdminRole() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(target);
    Role blankRole = new Role();
    blankRole.setName("   ");
    user.addRole(blankRole);
    Role superAdminRole = new Role();
    superAdminRole.setName("ROLE_SUPER_ADMIN");
    user.addRole(superAdminRole);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.save(any(UserAccount.class))).thenReturn(user);

    String resetEmail = service.resetTenantAdminPassword(target, "admin@ske.com");

    assertThat(resetEmail).isEqualTo("admin@ske.com");
    verify(emailService)
        .sendUserCredentialsEmailRequired(eq("admin@ske.com"), eq("Admin"), any(), eq("SKE"));
  }

  @Test
  void resetTenantAdminPassword_revokesExistingSessionsAndClearsLockout() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    Company target = company(55L, "SKE", "SKE");
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.addCompany(target);
    user.setFailedLoginAttempts(5);
    user.setLockedUntil(Instant.parse("2026-01-01T00:00:00Z"));
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    user.addRole(adminRole);
    when(userAccountRepository.findByEmailIgnoreCase("admin@ske.com"))
        .thenReturn(Optional.of(user));
    when(passwordEncoder.encode(any())).thenReturn("encoded");
    when(userAccountRepository.save(any(UserAccount.class))).thenReturn(user);

    String resetEmail = service.resetTenantAdminPassword(target, "admin@ske.com");

    assertThat(resetEmail).isEqualTo("admin@ske.com");
    assertThat(user.getFailedLoginAttempts()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.isMustChangePassword()).isTrue();
    verify(tokenBlacklistService).revokeAllUserTokens("admin@ske.com");
    verify(refreshTokenService).revokeAllForUser("admin@ske.com");
    verify(emailService)
        .sendUserCredentialsEmailRequired(eq("admin@ske.com"), eq("Admin"), any(), eq("SKE"));
  }

  @Test
  void hasAnyAuthority_privateHelper_handlesNullAndBlankInputs() {
    TenantAdminProvisioningService service =
        new TenantAdminProvisioningService(
            userAccountRepository,
            roleService,
            roleRepository,
            passwordEncoder,
            emailService,
            tokenBlacklistService,
            refreshTokenService);
    UserAccount user = new UserAccount("admin@ske.com", "hash", "Admin");
    user.getRoles().add(null);
    Role blankRole = new Role();
    blankRole.setName("   ");
    user.addRole(blankRole);
    Role adminRole = new Role();
    adminRole.setName("ROLE_ADMIN");
    user.addRole(adminRole);

    boolean nullUser = invokeHasAnyAuthority(service, null, new String[] {"ROLE_ADMIN"});
    boolean nullAuthorities = invokeHasAnyAuthority(service, user, null);
    boolean emptyAuthorities = invokeHasAnyAuthority(service, user, new String[] {});
    boolean blankThenValidAuthorities =
        invokeHasAnyAuthority(service, user, new String[] {"   ", "ROLE_ADMIN"});

    assertThat(nullUser).isFalse();
    assertThat(nullAuthorities).isFalse();
    assertThat(emptyAuthorities).isFalse();
    assertThat(blankThenValidAuthorities).isTrue();
  }

  private boolean invokeHasAnyAuthority(
      TenantAdminProvisioningService service, UserAccount user, String[] authorities) {
    return (boolean)
        ReflectionTestUtils.invokeMethod(service, "hasAnyAuthority", user, authorities);
  }

  private Company company(Long id, String code, String name) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    company.setCode(code);
    company.setName(name);
    company.setTimezone("UTC");
    return company;
  }
}
