package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordPolicy;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class ValidationSeedDataInitializerTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private UserAccountRepository userAccountRepository;
  @Mock private DealerRepository dealerRepository;
  @Mock private AccountRepository accountRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private AuthScopeService authScopeService;

  private ValidationSeedDataInitializer initializer;
  private PasswordPolicy passwordPolicy;

  @BeforeEach
  void setUp() {
    initializer = new ValidationSeedDataInitializer();
    passwordPolicy = new PasswordPolicy();
    lenient().when(authScopeService.getPlatformScopeCode()).thenReturn("PLATFORM");
    lenient()
        .when(companyRepository.save(any(Company.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(roleRepository.save(any(Role.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(accountRepository.save(any(Account.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(dealerRepository.save(any(Dealer.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(userAccountRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(passwordEncoder.encode(anyString()))
        .thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));
  }

  @Test
  void seedValidationActorsSkipsWhenDisabled() throws Exception {
    CommandLineRunner runner =
        initializer.seedValidationActors(
            companyRepository,
            roleRepository,
            userAccountRepository,
            dealerRepository,
            accountRepository,
            passwordEncoder,
            passwordPolicy,
            authScopeService,
            activeProfiles("mock", "validation-seed"),
            false,
            "ValidationSeed1!");

    runner.run();

    verifyNoInteractions(
        companyRepository,
        roleRepository,
        userAccountRepository,
        dealerRepository,
        accountRepository,
        passwordEncoder,
        authScopeService);
  }

  @Test
  void seedValidationActorsRejectsNonMockProfiles() throws Exception {
    CommandLineRunner runner =
        initializer.seedValidationActors(
            companyRepository,
            roleRepository,
            userAccountRepository,
            dealerRepository,
            accountRepository,
            passwordEncoder,
            passwordPolicy,
            authScopeService,
            activeProfiles("validation-seed"),
            true,
            "ValidationSeed1!");

    assertThatThrownBy(runner::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mock profile");

    verifyNoInteractions(
        companyRepository,
        roleRepository,
        userAccountRepository,
        dealerRepository,
        accountRepository,
        passwordEncoder);
  }

  @Test
  void seedValidationActorsRejectsWeakPasswords() throws Exception {
    CommandLineRunner runner =
        initializer.seedValidationActors(
            companyRepository,
            roleRepository,
            userAccountRepository,
            dealerRepository,
            accountRepository,
            passwordEncoder,
            passwordPolicy,
            authScopeService,
            activeProfiles("mock", "validation-seed"),
            true,
            "changeme");

    assertThatThrownBy(runner::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("password policy");
  }

  @Test
  void seedValidationActorsSeedsTenantAndPlatformScopedActors() throws Exception {
    when(companyRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
    when(roleRepository.findByName(anyString())).thenReturn(Optional.empty());
    when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
        .thenReturn(Optional.empty());
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
        .thenReturn(Optional.empty());

    CommandLineRunner runner =
        initializer.seedValidationActors(
            companyRepository,
            roleRepository,
            userAccountRepository,
            dealerRepository,
            accountRepository,
            passwordEncoder,
            passwordPolicy,
            authScopeService,
            activeProfiles("mock", "validation-seed"),
            true,
            "ValidationSeed1!");

    runner.run();

    ArgumentCaptor<UserAccount> users = ArgumentCaptor.forClass(UserAccount.class);
    verify(userAccountRepository, times(8)).save(users.capture());

    assertThat(users.getAllValues())
        .extracting(UserAccount::getEmail)
        .containsExactlyInAnyOrder(
            "validation.admin@example.com",
            "validation.accounting@example.com",
            "validation.sales@example.com",
            "validation.factory@example.com",
            "validation.dealer@example.com",
            "validation.rival.dealer@example.com",
            "validation.rival.admin@example.com",
            "validation.superadmin@example.com");

    UserAccount platformUser =
        users.getAllValues().stream()
            .filter(user -> "validation.superadmin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(platformUser.getAuthScopeCode()).isEqualTo("PLATFORM");
    assertThat(platformUser.getCompany()).isNull();

    UserAccount mockAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.admin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(mockAdmin.getAuthScopeCode()).isEqualTo("MOCK");
    assertThat(mockAdmin.getCompany()).extracting(Company::getCode).isEqualTo("MOCK");
    assertThat(mockAdmin.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES");

    assertThat(users.getAllValues())
        .allMatch(user -> user.getAuthScopeCode().equals(user.getAuthScopeCode().toUpperCase(Locale.ROOT)));
    verify(dealerRepository, times(2)).save(any(Dealer.class));
    verify(authScopeService).getPlatformScopeCode();
  }

  private MockEnvironment activeProfiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }
}
