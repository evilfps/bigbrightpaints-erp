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

import java.time.Instant;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CryptoService;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicket;
import com.bigbrightpaints.erp.modules.admin.domain.SupportTicketRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordPolicy;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
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
  @Mock private InvoiceRepository invoiceRepository;
  @Mock private ExportRequestRepository exportRequestRepository;
  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private CreditRequestRepository creditRequestRepository;
  @Mock private SystemSettingsRepository systemSettingsRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private CryptoService cryptoService;
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
        .when(exportRequestRepository.save(any(ExportRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(supportTicketRepository.save(any(SupportTicket.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(creditRequestRepository.save(any(CreditRequest.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(
            exportRequestRepository.findByCompanyAndStatusOrderByCreatedAtAsc(
                any(Company.class), any(ExportApprovalStatus.class)))
        .thenReturn(List.of());
    lenient()
        .when(
            supportTicketRepository.findByCompanyAndUserIdOrderByCreatedAtDesc(
                any(Company.class), any()))
        .thenReturn(List.of());
    lenient()
        .when(creditRequestRepository.findPendingByCompanyOrderByCreatedAtDesc(any(Company.class)))
        .thenReturn(List.of());
    lenient()
        .when(passwordEncoder.encode(anyString()))
        .thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));
    lenient()
        .when(cryptoService.encrypt(anyString()))
        .thenAnswer(invocation -> "encrypted:" + invocation.getArgument(0, String.class));
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
            invoiceRepository,
            exportRequestRepository,
            supportTicketRepository,
            creditRequestRepository,
            systemSettingsRepository,
            passwordEncoder,
            cryptoService,
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
        invoiceRepository,
        exportRequestRepository,
        supportTicketRepository,
        creditRequestRepository,
        systemSettingsRepository,
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
            invoiceRepository,
            exportRequestRepository,
            supportTicketRepository,
            creditRequestRepository,
            systemSettingsRepository,
            passwordEncoder,
            cryptoService,
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
        exportRequestRepository,
        supportTicketRepository,
        creditRequestRepository,
        systemSettingsRepository,
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
            invoiceRepository,
            exportRequestRepository,
            supportTicketRepository,
            creditRequestRepository,
            systemSettingsRepository,
            passwordEncoder,
            cryptoService,
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
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            anyString(), anyString()))
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
            invoiceRepository,
            exportRequestRepository,
            supportTicketRepository,
            creditRequestRepository,
            systemSettingsRepository,
            passwordEncoder,
            cryptoService,
            passwordPolicy,
            authScopeService,
            activeProfiles("mock", "validation-seed"),
            true,
            "ValidationSeed1!");

    runner.run();

    ArgumentCaptor<UserAccount> users = ArgumentCaptor.forClass(UserAccount.class);
    verify(userAccountRepository, times(16)).save(users.capture());

    assertThat(users.getAllValues())
        .extracting(UserAccount::getEmail)
        .containsExactlyInAnyOrder(
            "validation.admin@example.com",
            "validation.mustchange.admin@example.com",
            "validation.locked.admin@example.com",
            "validation.accounting@example.com",
            "validation.sales@example.com",
            "validation.factory@example.com",
            "validation.mfa.admin@example.com",
            "validation.dealer@example.com",
            "validation.rival.dealer@example.com",
            "validation.rival.admin@example.com",
            "validation.hold.admin@example.com",
            "validation.blocked.admin@example.com",
            "validation.quota.alpha@example.com",
            "validation.quota.beta@example.com",
            "validation.tenant.superadmin@example.com",
            "validation.superadmin@example.com");

    UserAccount platformUser =
        users.getAllValues().stream()
            .filter(user -> "validation.superadmin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(platformUser.getAuthScopeCode()).isEqualTo("PLATFORM");
    assertThat(platformUser.getCompany()).isNull();

    UserAccount tenantReopenSuperAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.tenant.superadmin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(tenantReopenSuperAdmin.getAuthScopeCode()).isEqualTo("MOCK");
    assertThat(tenantReopenSuperAdmin.getCompany()).extracting(Company::getCode).isEqualTo("MOCK");
    assertThat(tenantReopenSuperAdmin.getRoles())
        .extracting(Role::getName)
        .containsExactly("ROLE_SUPER_ADMIN");

    UserAccount mockAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.admin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(mockAdmin.getAuthScopeCode()).isEqualTo("MOCK");
    assertThat(mockAdmin.getCompany()).extracting(Company::getCode).isEqualTo("MOCK");
    assertThat(mockAdmin.getRoles())
        .extracting(Role::getName)
        .contains("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES");

    UserAccount mustChangeAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.mustchange.admin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(mustChangeAdmin.isMustChangePassword()).isTrue();

    UserAccount lockedAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.locked.admin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(lockedAdmin.getLockedUntil()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));

    UserAccount mfaAdmin =
        users.getAllValues().stream()
            .filter(user -> "validation.mfa.admin@example.com".equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(mfaAdmin.isMfaEnabled()).isTrue();
    assertThat(mfaAdmin.getMfaSecret()).isEqualTo("encrypted:JBSWY3DPEHPK3PXP");
    assertThat(mfaAdmin.getMfaRecoveryCodeHashes())
        .containsExactly("encoded:VALMFA0001", "encoded:VALMFA0002", "encoded:VALMFA0003");

    assertThat(users.getAllValues())
        .allMatch(
            user ->
                user.getAuthScopeCode().equals(user.getAuthScopeCode().toUpperCase(Locale.ROOT)));
    verify(dealerRepository, times(2)).save(any(Dealer.class));
    verify(exportRequestRepository).save(any(ExportRequest.class));
    verify(supportTicketRepository).save(any(SupportTicket.class));
    verify(creditRequestRepository).save(any(CreditRequest.class));
    verify(authScopeService).getPlatformScopeCode();
  }

  @Test
  void ensureRuntimePolicyWritesExpectedKeysAndDeletesNullEntries() {
    Company company = new Company();
    company.setCode("HOLD");
    ReflectionTestUtils.setField(company, "id", 41L);

    com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
        initializer,
        "ensureRuntimePolicy",
        systemSettingsRepository,
        company,
        "HOLD",
        "Manual hold for validation",
        12,
        null,
        5);

    ArgumentCaptor<SystemSetting> settings = ArgumentCaptor.forClass(SystemSetting.class);
    verify(systemSettingsRepository, times(6)).save(settings.capture());
    assertThat(settings.getAllValues())
        .extracting(SystemSetting::getKey)
        .containsExactlyInAnyOrder(
            "tenant.runtime.hold-state.41",
            "tenant.runtime.hold-reason.41",
            "tenant.runtime.max-active-users.41",
            "tenant.runtime.max-concurrent-requests.41",
            "tenant.runtime.policy-reference.41",
            "tenant.runtime.policy-updated-at.41");
    assertThat(settings.getAllValues())
        .filteredOn(setting -> "tenant.runtime.policy-reference.41".equals(setting.getKey()))
        .singleElement()
        .extracting(SystemSetting::getValue)
        .isEqualTo("validation-seed-hold");
    assertThat(settings.getAllValues())
        .filteredOn(setting -> "tenant.runtime.policy-updated-at.41".equals(setting.getKey()))
        .singleElement()
        .extracting(SystemSetting::getValue)
        .isInstanceOf(String.class)
        .satisfies(value -> assertThat((String) value).isNotBlank());
    verify(systemSettingsRepository).deleteById("tenant.runtime.max-requests-per-minute.41");
  }

  private MockEnvironment activeProfiles(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }
}
