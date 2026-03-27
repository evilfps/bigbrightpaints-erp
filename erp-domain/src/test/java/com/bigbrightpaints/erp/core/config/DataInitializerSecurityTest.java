package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class DataInitializerSecurityTest {

  @Mock private RoleRepository roleRepository;

  @Mock private UserAccountRepository userRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Test
  void dataInitializer_skipsDevAdminBootstrapWhenEmailBlank() {
    DataInitializer initializer = new DataInitializer();

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedConfiguredDevAdmin",
        userRepository,
        passwordEncoder,
        role("ROLE_ADMIN"),
        company("BBP"),
        "   ",
        "ignored");

    verify(userRepository, never())
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(anyString(), anyString());
    verify(userRepository, never()).save(any(UserAccount.class));
    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void dataInitializer_requiresDevAdminPasswordForNewUser() {
    DataInitializer initializer = new DataInitializer();
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("dev.admin@bbp.com", "BBP"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    initializer,
                    "seedConfiguredDevAdmin",
                    userRepository,
                    passwordEncoder,
                    role("ROLE_ADMIN"),
                    company("BBP"),
                    "dev.admin@bbp.com",
                    "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("erp.seed.dev-admin.password is required");

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void dataInitializer_createsDevAdminWhenPasswordProvided() {
    DataInitializer initializer = new DataInitializer();
    Company company = company("BBP");
    Role adminRole = role("ROLE_ADMIN");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("dev.admin@bbp.com", "BBP"))
        .thenReturn(Optional.empty());
    when(passwordEncoder.encode("DevAdmin@123!")).thenReturn("enc-dev");
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedConfiguredDevAdmin",
        userRepository,
        passwordEncoder,
        adminRole,
        company,
        " DEV.ADMIN@BBP.COM ",
        " DevAdmin@123! ");

    ArgumentCaptor<UserAccount> savedUserCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(savedUserCaptor.capture());
    UserAccount savedUser = savedUserCaptor.getValue();
    assertThat(savedUser.getEmail()).isEqualTo("dev.admin@bbp.com");
    assertThat(savedUser.getDisplayName()).isEqualTo("Dev Admin");
    assertThat(savedUser.isMustChangePassword()).isTrue();
    assertThat(savedUser.getCompany()).isEqualTo(company);
    assertThat(savedUser.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN");
    verify(passwordEncoder).encode("DevAdmin@123!");
  }

  @Test
  void dataInitializer_requiresPasswordChallengeForExistingDevAdminWhenPasswordBlank() {
    DataInitializer initializer = new DataInitializer();
    Company company = company("BBP");
    Role adminRole = role("ROLE_ADMIN");
    UserAccount existingUser = new UserAccount("dev.admin@bbp.com", "existing-hash", "Existing");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("dev.admin@bbp.com", "BBP"))
        .thenReturn(Optional.of(existingUser));

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    initializer,
                    "seedConfiguredDevAdmin",
                    userRepository,
                    passwordEncoder,
                    adminRole,
                    company,
                    "dev.admin@bbp.com",
                    "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must be provided and match existing bootstrap user credentials");

    verify(passwordEncoder, never()).encode(anyString());
    verify(passwordEncoder, never()).matches(anyString(), anyString());
    verify(userRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void dataInitializer_rejectsPasswordChallengeForExistingDevAdminWhenPasswordDoesNotMatch() {
    DataInitializer initializer = new DataInitializer();
    Company company = company("BBP");
    Role adminRole = role("ROLE_ADMIN");
    UserAccount existingUser = new UserAccount("dev.admin@bbp.com", "existing-hash", "Existing");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("dev.admin@bbp.com", "BBP"))
        .thenReturn(Optional.of(existingUser));
    when(passwordEncoder.matches("WrongPassword@123", "existing-hash")).thenReturn(false);

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    initializer,
                    "seedConfiguredDevAdmin",
                    userRepository,
                    passwordEncoder,
                    adminRole,
                    company,
                    "dev.admin@bbp.com",
                    "WrongPassword@123"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must match existing bootstrap user credentials");

    verify(userRepository, never()).save(any(UserAccount.class));
  }

  @Test
  void dataInitializer_reusesExistingDevAdminWhenPasswordChallengeMatches() {
    DataInitializer initializer = new DataInitializer();
    Company company = company("BBP");
    Role adminRole = role("ROLE_ADMIN");
    UserAccount existingUser = new UserAccount("dev.admin@bbp.com", "existing-hash", "Existing");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("dev.admin@bbp.com", "BBP"))
        .thenReturn(Optional.of(existingUser));
    when(passwordEncoder.matches("DevAdmin@123!", "existing-hash")).thenReturn(true);
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedConfiguredDevAdmin",
        userRepository,
        passwordEncoder,
        adminRole,
        company,
        "dev.admin@bbp.com",
        "DevAdmin@123!");

    verify(passwordEncoder).matches("DevAdmin@123!", "existing-hash");
    verify(userRepository).save(existingUser);
    assertThat(existingUser.getDisplayName()).isEqualTo("Dev Admin");
    assertThat(existingUser.getCompany()).isEqualTo(company);
    assertThat(existingUser.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN");
  }

  @Test
  void mockInitializer_requiresPasswordWhenSeedingNewAdmin() {
    MockDataInitializer initializer = new MockDataInitializer();
    Company company = company("MOCK");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "mock.admin@bbp.com", "MOCK"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    initializer,
                    "seedRolesAndUsers",
                    roleRepository,
                    userRepository,
                    passwordEncoder,
                    company,
                    "mock.admin@bbp.com",
                    "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("erp.seed.mock-admin.password is required");

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void mockInitializer_skipsWhenAdminEmailBlank() {
    MockDataInitializer initializer = new MockDataInitializer();

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company("MOCK"),
        "   ",
        "ignored");

    verify(userRepository, never())
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(anyString(), anyString());
    verify(userRepository, never()).save(any(UserAccount.class));
    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void mockInitializer_createsAdminWhenPasswordProvided() {
    MockDataInitializer initializer = new MockDataInitializer();
    Company company = company("MOCK");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "mock.admin@bbp.com", "MOCK"))
        .thenReturn(Optional.empty());
    when(passwordEncoder.encode("MockAdmin@123!")).thenReturn("enc-mock");
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " mock.admin@bbp.com ",
        " MockAdmin@123! ");

    verify(passwordEncoder).encode("MockAdmin@123!");
    verify(userRepository).save(any(UserAccount.class));
  }

  @Test
  void benchmarkInitializer_requiresPasswordWhenSeedingNewAdmin() {
    BenchmarkDataInitializer initializer = new BenchmarkDataInitializer();
    Company company = company("BBP");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(roleRepository.findByName("ROLE_FACTORY")).thenReturn(Optional.of(role("ROLE_FACTORY")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "benchmark.admin@bbp.com", "BBP"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                ReflectionTestUtils.invokeMethod(
                    initializer,
                    "seedRolesAndUsers",
                    roleRepository,
                    userRepository,
                    passwordEncoder,
                    company,
                    "benchmark.admin@bbp.com",
                    " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("erp.seed.benchmark-admin.password is required");

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void benchmarkInitializer_skipsWhenAdminEmailBlank() {
    BenchmarkDataInitializer initializer = new BenchmarkDataInitializer();

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company("BBP"),
        "   ",
        "ignored");

    verify(userRepository, never())
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(anyString(), anyString());
    verify(userRepository, never()).save(any(UserAccount.class));
    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void benchmarkInitializer_createsAdminWhenPasswordProvided() {
    BenchmarkDataInitializer initializer = new BenchmarkDataInitializer();
    Company company = company("BBP");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(roleRepository.findByName("ROLE_FACTORY")).thenReturn(Optional.of(role("ROLE_FACTORY")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "benchmark.admin@bbp.com", "BBP"))
        .thenReturn(Optional.empty());
    when(passwordEncoder.encode("BenchmarkAdmin@123!")).thenReturn("enc-benchmark");
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " benchmark.admin@bbp.com ",
        " BenchmarkAdmin@123! ");

    verify(passwordEncoder).encode("BenchmarkAdmin@123!");
    verify(userRepository).save(any(UserAccount.class));
  }

  @Test
  void mockInitializer_doesNotRequirePasswordWhenAdminAlreadyExists() {
    MockDataInitializer initializer = new MockDataInitializer();
    Company company = company("MOCK");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "mock.admin@bbp.com", "MOCK"))
        .thenReturn(
            Optional.of(new UserAccount("mock.admin@bbp.com", "existing-hash", "Mock Admin")));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        "mock.admin@bbp.com",
        "   ");

    verify(passwordEncoder, never()).encode(anyString());
  }

  @Test
  void benchmarkInitializer_doesNotRequirePasswordWhenAdminAlreadyExists() {
    BenchmarkDataInitializer initializer = new BenchmarkDataInitializer();
    Company company = company("BBP");
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(role("ROLE_ADMIN")));
    when(roleRepository.findByName("ROLE_ACCOUNTING"))
        .thenReturn(Optional.of(role("ROLE_ACCOUNTING")));
    when(roleRepository.findByName("ROLE_SALES")).thenReturn(Optional.of(role("ROLE_SALES")));
    when(roleRepository.findByName("ROLE_FACTORY")).thenReturn(Optional.of(role("ROLE_FACTORY")));
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "benchmark.admin@bbp.com", "BBP"))
        .thenReturn(
            Optional.of(
                new UserAccount("benchmark.admin@bbp.com", "existing-hash", "Benchmark Admin")));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        "benchmark.admin@bbp.com",
        "   ");

    verify(passwordEncoder, never()).encode(anyString());
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    return role;
  }

  private Company company(String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code);
    company.setTimezone("UTC");
    return company;
  }
}
