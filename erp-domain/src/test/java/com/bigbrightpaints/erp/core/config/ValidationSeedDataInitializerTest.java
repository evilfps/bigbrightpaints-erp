package com.bigbrightpaints.erp.core.config;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationSeedDataInitializerTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private ValidationSeedDataInitializer initializer;
    private PasswordPolicy passwordPolicy;

    @BeforeEach
    void setUp() {
        initializer = new ValidationSeedDataInitializer();
        passwordPolicy = new PasswordPolicy();
    }

    @Test
    void seedValidationActorsSkipsWhenExplicitlyDisabled() throws Exception {
        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                false,
                "ValidationSeed1!");

        runner.run();

        verifyNoInteractions(companyRepository, roleRepository, userAccountRepository, dealerRepository, accountRepository, passwordEncoder);
    }

    @Test
    void seedValidationActorsRejectsNonMockProfiles() throws Exception {
        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("validation-seed"),
                true,
                "ValidationSeed1!");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mock profile");

        verifyNoInteractions(companyRepository, roleRepository, userAccountRepository, dealerRepository, accountRepository, passwordEncoder);
    }

    @Test
    void seedValidationActorsRejectsWeakPasswords() throws Exception {
        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                true,
                "changeme");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password policy");

        verifyNoInteractions(companyRepository, roleRepository, userAccountRepository, dealerRepository, accountRepository, passwordEncoder);
    }

    @Test
    void seedValidationActorsSeedsExpectedActorsWhenEnabled() throws Exception {
        when(companyRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));

        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                true,
                "ValidationSeed1!");

        runner.run();

        var savedUsers = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository, times(8)).save(savedUsers.capture());
        assertThat(savedUsers.getAllValues())
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
        assertThat(savedUsers.getAllValues())
                .allSatisfy(user -> {
                    assertThat(user.isEnabled()).isTrue();
                    assertThat(user.isMustChangePassword()).isFalse();
                    assertThat(user.getPasswordHash()).isEqualTo("encoded:ValidationSeed1!");
                });
        verify(dealerRepository, times(2)).save(any(Dealer.class));
    }

    @Test
    void seedValidationActorsNormalizesExistingCompanyAndRoleMemberships() throws Exception {
        Company existingMockCompany = company("MOCK", "Mock Training Co");
        Company currentMockCompany = company("MOCK", "Mock Training Co");
        Company rivalCompany = company("RIVAL", "Rival Validation Co");
        Company superAdminCompany = company("SKE", "Platform Super Admin");

        Role existingAdminRole = role("ROLE_ADMIN", "Administrator");
        Role currentAdminRole = role("ROLE_ADMIN", "Administrator");
        Role accountingRole = role("ROLE_ACCOUNTING", "Accounting");
        Role salesRole = role("ROLE_SALES", "Sales");
        Role factoryRole = role("ROLE_FACTORY", "Factory");
        Role dealerRole = role("ROLE_DEALER", "Dealer portal");
        Role superAdminRole = role("ROLE_SUPER_ADMIN", "Platform super administrator");

        UserAccount existingAdminUser = new UserAccount("validation.admin@example.com", "encoded:old", "Validation Admin");
        existingAdminUser.addCompany(existingMockCompany);
        existingAdminUser.addCompany(rivalCompany);
        existingAdminUser.addRole(existingAdminRole);
        existingAdminUser.addRole(factoryRole);

        when(companyRepository.findByCodeIgnoreCase(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "MOCK" -> Optional.of(currentMockCompany);
            case "RIVAL" -> Optional.of(rivalCompany);
            case "SKE" -> Optional.of(superAdminCompany);
            default -> Optional.empty();
        });
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(roleRepository.findByName(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "ROLE_ADMIN" -> Optional.of(currentAdminRole);
            case "ROLE_ACCOUNTING" -> Optional.of(accountingRole);
            case "ROLE_SALES" -> Optional.of(salesRole);
            case "ROLE_FACTORY" -> Optional.of(factoryRole);
            case "ROLE_DEALER" -> Optional.of(dealerRole);
            case "ROLE_SUPER_ADMIN" -> Optional.of(superAdminRole);
            default -> Optional.empty();
        });
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(userAccountRepository.findByEmailIgnoreCase(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0, String.class);
            if ("validation.admin@example.com".equalsIgnoreCase(email)) {
                return Optional.of(existingAdminUser);
            }
            return Optional.empty();
        });
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));

        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                true,
                "ValidationSeed1!");

        runner.run();

        assertThat(existingAdminUser.getCompanies())
                .extracting(Company::getCode)
                .containsExactly("MOCK");
        assertThat(existingAdminUser.getRoles())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES");
    }

    @Test
    void seedValidationActorsClearsExistingLockoutAndMfaStateOnReseed() throws Exception {
        Company mockCompany = company("MOCK", "Mock Training Co");
        Company rivalCompany = company("RIVAL", "Rival Validation Co");
        Company superAdminCompany = company("SKE", "Platform Super Admin");

        Role adminRole = role("ROLE_ADMIN", "Administrator");
        Role accountingRole = role("ROLE_ACCOUNTING", "Accounting");
        Role salesRole = role("ROLE_SALES", "Sales");
        Role factoryRole = role("ROLE_FACTORY", "Factory");
        Role dealerRole = role("ROLE_DEALER", "Dealer portal");
        Role superAdminRole = role("ROLE_SUPER_ADMIN", "Platform super administrator");

        UserAccount existingSalesUser = new UserAccount("validation.sales@example.com", "encoded:old", "Validation Sales");
        existingSalesUser.setFailedLoginAttempts(5);
        existingSalesUser.setLockedUntil(Instant.now().plusSeconds(600));
        existingSalesUser.setMfaEnabled(true);
        existingSalesUser.setMfaSecret("encrypted-secret");
        existingSalesUser.setMfaRecoveryCodeHashes(java.util.List.of("hash-1", "hash-2"));

        when(companyRepository.findByCodeIgnoreCase(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "MOCK" -> Optional.of(mockCompany);
            case "RIVAL" -> Optional.of(rivalCompany);
            case "SKE" -> Optional.of(superAdminCompany);
            default -> Optional.empty();
        });
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.findByName(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "ROLE_ADMIN" -> Optional.of(adminRole);
            case "ROLE_ACCOUNTING" -> Optional.of(accountingRole);
            case "ROLE_SALES" -> Optional.of(salesRole);
            case "ROLE_FACTORY" -> Optional.of(factoryRole);
            case "ROLE_DEALER" -> Optional.of(dealerRole);
            case "ROLE_SUPER_ADMIN" -> Optional.of(superAdminRole);
            default -> Optional.empty();
        });
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountRepository.findByEmailIgnoreCase(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0, String.class);
            if ("validation.sales@example.com".equalsIgnoreCase(email)) {
                return Optional.of(existingSalesUser);
            }
            return Optional.empty();
        });
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenReturn(Optional.empty());
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));

        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                true,
                "ValidationSeed1!");

        runner.run();

        assertThat(existingSalesUser.getFailedLoginAttempts()).isZero();
        assertThat(existingSalesUser.getLockedUntil()).isNull();
        assertThat(existingSalesUser.isMfaEnabled()).isFalse();
        assertThat(existingSalesUser.getMfaSecret()).isNull();
        assertThat(existingSalesUser.getMfaRecoveryCodeHashes()).isEmpty();
    }

    @Test
    void seedValidationActorsPreservesExistingDealerOutstandingBalanceOnReseed() throws Exception {
        Company mockCompany = company("MOCK", "Mock Training Co");
        Company rivalCompany = company("RIVAL", "Rival Validation Co");
        Company superAdminCompany = company("SKE", "Platform Super Admin");

        Role adminRole = role("ROLE_ADMIN", "Administrator");
        Role accountingRole = role("ROLE_ACCOUNTING", "Accounting");
        Role salesRole = role("ROLE_SALES", "Sales");
        Role factoryRole = role("ROLE_FACTORY", "Factory");
        Role dealerRole = role("ROLE_DEALER", "Dealer portal");
        Role superAdminRole = role("ROLE_SUPER_ADMIN", "Platform super administrator");

        Account mockReceivable = new Account();
        mockReceivable.setCompany(mockCompany);
        mockReceivable.setCode("AR");

        UserAccount dealerUser = new UserAccount("validation.dealer@example.com", "encoded:old", "Validation Dealer");
        Dealer existingDealer = new Dealer();
        existingDealer.setCompany(mockCompany);
        existingDealer.setCode("VALID-DEALER");
        existingDealer.setOutstandingBalance(new BigDecimal("1450.75"));

        when(companyRepository.findByCodeIgnoreCase(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "MOCK" -> Optional.of(mockCompany);
            case "RIVAL" -> Optional.of(rivalCompany);
            case "SKE" -> Optional.of(superAdminCompany);
            default -> Optional.empty();
        });
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.findByName(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "ROLE_ADMIN" -> Optional.of(adminRole);
            case "ROLE_ACCOUNTING" -> Optional.of(accountingRole);
            case "ROLE_SALES" -> Optional.of(salesRole);
            case "ROLE_FACTORY" -> Optional.of(factoryRole);
            case "ROLE_DEALER" -> Optional.of(dealerRole);
            case "ROLE_SUPER_ADMIN" -> Optional.of(superAdminRole);
            default -> Optional.empty();
        });
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0, Company.class);
            String code = invocation.getArgument(1, String.class);
            if (company == mockCompany && "AR".equalsIgnoreCase(code)) {
                return Optional.of(mockReceivable);
            }
            return Optional.empty();
        });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userAccountRepository.findByEmailIgnoreCase(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0, String.class);
            if ("validation.dealer@example.com".equalsIgnoreCase(email)) {
                return Optional.of(dealerUser);
            }
            return Optional.empty();
        });
        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dealerRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString())).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0, Company.class);
            String code = invocation.getArgument(1, String.class);
            if (company == mockCompany && "VALID-DEALER".equalsIgnoreCase(code)) {
                return Optional.of(existingDealer);
            }
            return Optional.empty();
        });
        when(dealerRepository.save(any(Dealer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "encoded:" + invocation.getArgument(0, String.class));

        CommandLineRunner runner = initializer.seedValidationActors(
                companyRepository,
                roleRepository,
                userAccountRepository,
                dealerRepository,
                accountRepository,
                passwordEncoder,
                passwordPolicy,
                activeProfiles("mock", "validation-seed"),
                true,
                "ValidationSeed1!");

        runner.run();

        assertThat(existingDealer.getOutstandingBalance()).isEqualByComparingTo("1450.75");
        assertThat(existingDealer.getPortalUser()).isSameAs(dealerUser);
        assertThat(existingDealer.getReceivableAccount()).isSameAs(mockReceivable);
    }

    private MockEnvironment activeProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private Company company(String code, String name) {
        Company company = new Company();
        company.setCode(code);
        company.setName(name);
        return company;
    }

    private Role role(String name, String description) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        return role;
    }
}
