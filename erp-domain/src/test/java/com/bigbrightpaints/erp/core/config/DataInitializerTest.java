package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

  @Mock private UserAccountRepository userRepository;
  @Mock private CompanyRepository companyRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuthScopeService authScopeService;

  private DataInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new DataInitializer();
    lenient()
        .when(authScopeService.updatePlatformScopeCode(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().toUpperCase(Locale.ROOT));
    lenient()
        .when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(passwordEncoder.encode(anyString()))
        .thenAnswer(invocation -> "enc-" + invocation.getArgument(0, String.class));
    lenient()
        .when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
        .thenReturn(Optional.of(new Account()));
  }

  @Test
  void seedDefaultUser_createsScopedSuperAdminAndScopedDevAdmin() throws Exception {
    Role adminRole = role("ROLE_ADMIN");
    Role superAdminRole = role("ROLE_SUPER_ADMIN");
    Company bbp = company("BBP");

    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
    when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("super@erp.com", "PLATFORM"))
        .thenReturn(Optional.empty());
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("admin@bbp.dev", "BBP"))
        .thenReturn(Optional.empty());

    CommandLineRunner runner =
        initializer.seedDefaultUser(
            userRepository,
            companyRepository,
            roleRepository,
            accountRepository,
            passwordEncoder,
            authScopeService,
            " super@erp.com ",
            "Bootstrap@123",
            "platform",
            " admin@bbp.dev ",
            "DevAdmin@123!");

    runner.run();

    ArgumentCaptor<UserAccount> users = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository, atLeastOnce()).save(users.capture());

    UserAccount superAdmin =
        users.getAllValues().stream()
            .filter(user -> "super@erp.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(superAdmin.getAuthScopeCode()).isEqualTo("PLATFORM");
    assertThat(superAdmin.getCompany()).isNull();
    assertThat(superAdmin.isMustChangePassword()).isTrue();
    assertThat(superAdmin.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");

    UserAccount devAdmin =
        users.getAllValues().stream()
            .filter(user -> "admin@bbp.dev".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(devAdmin.getAuthScopeCode()).isEqualTo("BBP");
    assertThat(devAdmin.getCompany()).extracting(Company::getCode).isEqualTo("BBP");
    assertThat(devAdmin.isMustChangePassword()).isTrue();
    assertThat(devAdmin.getRoles()).extracting(Role::getName).containsExactly("ROLE_ADMIN");
  }

  @Test
  void seedDefaultUser_skipsSuperAdminWhenEmailBlank() throws Exception {
    Role adminRole = role("ROLE_ADMIN");
    Role superAdminRole = role("ROLE_SUPER_ADMIN");
    Company bbp = company("BBP");
    UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "BBP", "dev-hash", "Dev Admin");

    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
    when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("admin@bbp.dev", "BBP"))
        .thenReturn(Optional.of(existingDevAdmin));
    when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);

    CommandLineRunner runner =
        initializer.seedDefaultUser(
            userRepository,
            companyRepository,
            roleRepository,
            accountRepository,
            passwordEncoder,
            authScopeService,
            "   ",
            "Bootstrap@123",
            "PLATFORM",
            "admin@bbp.dev",
            "DevAdmin@123!");

    runner.run();

    verify(authScopeService, never()).updatePlatformScopeCode(anyString());
    verify(userRepository, never())
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("super@erp.com", "PLATFORM");
  }

  @Test
  void seedDefaultUser_requiresPasswordWhenSuperAdminMustBeCreated() {
    Role adminRole = role("ROLE_ADMIN");
    Role superAdminRole = role("ROLE_SUPER_ADMIN");

    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("super@erp.com", "PLATFORM"))
        .thenReturn(Optional.empty());

    CommandLineRunner runner =
        initializer.seedDefaultUser(
            userRepository,
            companyRepository,
            roleRepository,
            accountRepository,
            passwordEncoder,
            authScopeService,
            "super@erp.com",
            " ",
            "PLATFORM",
            "   ",
            "   ");

    assertThatThrownBy(runner::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("erp.seed.super-admin.password is required");
  }

  @Test
  void seedDefaultUser_requiresPasswordChallengeForExistingDevAdmin() {
    Role adminRole = role("ROLE_ADMIN");
    Role superAdminRole = role("ROLE_SUPER_ADMIN");
    Company bbp = company("BBP");
    UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "BBP", "dev-hash", "Dev Admin");

    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
    when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("admin@bbp.dev", "BBP"))
        .thenReturn(Optional.of(existingDevAdmin));

    CommandLineRunner runner =
        initializer.seedDefaultUser(
            userRepository,
            companyRepository,
            roleRepository,
            accountRepository,
            passwordEncoder,
            authScopeService,
            "   ",
            "   ",
            "PLATFORM",
            "admin@bbp.dev",
            "   ");

    assertThatThrownBy(runner::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be provided and match existing bootstrap user credentials");
  }

  @Test
  void ensureCompanyMembership_handlesNullsAndAvoidsDuplicateMemberships() {
    Company targetCompany = company("BBP");

    ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", null, targetCompany);
    ReflectionTestUtils.invokeMethod(
        initializer, "ensureCompanyMembership", new UserAccount("u@x.com", "hash", "User"), null);

    UserAccount userWithCodeMatch = new UserAccount("a@x.com", "hash", "User A");
    userWithCodeMatch.setCompany(company("bbp"));
    ReflectionTestUtils.invokeMethod(
        initializer, "ensureCompanyMembership", userWithCodeMatch, targetCompany);
    assertThat(userWithCodeMatch.getCompany()).isNotNull();

    UserAccount userWithIdMatch = new UserAccount("b@x.com", "hash", "User B");
    Company existing = company("OTHER");
    ReflectionTestUtils.setField(existing, "id", 7L);
    Company sameId = company("DIFFERENT");
    ReflectionTestUtils.setField(sameId, "id", 7L);
    userWithIdMatch.setCompany(existing);
    ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", userWithIdMatch, sameId);
    assertThat(userWithIdMatch.getCompany()).isNotNull();
  }

  @Test
  void ensureRoleMembership_handlesNullsAndAvoidsDuplicateRoleNames() {
    Role adminRole = role("ROLE_ADMIN");

    ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", null, adminRole);
    ReflectionTestUtils.invokeMethod(
        initializer, "ensureRoleMembership", new UserAccount("u@x.com", "hash", "User"), null);

    UserAccount user = new UserAccount("seed@x.com", "hash", "Seed");
    user.addRole(role("role_admin"));
    ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", user, adminRole);

    assertThat(
            user.getRoles().stream()
                .filter(role -> role != null && "ROLE_ADMIN".equalsIgnoreCase(role.getName()))
                .count())
        .isEqualTo(1);
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    return role;
  }

  private Company company(String code) {
    Company company = new Company();
    company.setName(code);
    company.setCode(code);
    company.setTimezone("UTC");
    return company;
  }
}
