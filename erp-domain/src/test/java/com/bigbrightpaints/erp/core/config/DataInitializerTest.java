package com.bigbrightpaints.erp.core.config;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void seedDefaultUser_existingSuperAdmin_doesNotOverwritePassword() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company ske = company("SKE");
        Company bbp = company("BBP");
        UserAccount existingSuperAdmin = new UserAccount("super@erp.com", "keep-hash", "Existing Super Admin");
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.of(ske));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("super@erp.com")).thenReturn(Optional.of(existingSuperAdmin));
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));
        when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "super@erp.com",
                "Bootstrap@123",
                "SKE",
                "admin@bbp.dev",
                "DevAdmin@123!");

        runner.run();

        assertThat(existingSuperAdmin.getPasswordHash()).isEqualTo("keep-hash");
        verify(passwordEncoder, never()).encode("Bootstrap@123");
    }

    @Test
    void seedDefaultUser_existingSuperAdmin_doesNotDuplicateRolesWhenRoleInstancesDiffer() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Role adminRoleFromRepository = role("ROLE_ADMIN");
        Role superAdminRoleFromRepository = role("ROLE_SUPER_ADMIN");
        Company ske = company("SKE");
        Company bbp = company("BBP");
        UserAccount existingSuperAdmin = new UserAccount("super@erp.com", "keep-hash", "Existing Super Admin");
        existingSuperAdmin.addRole(role("ROLE_ADMIN"));
        existingSuperAdmin.addRole(role("ROLE_SUPER_ADMIN"));
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRoleFromRepository));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRoleFromRepository));
        when(companyRepository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.of(ske));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("super@erp.com")).thenReturn(Optional.of(existingSuperAdmin));
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));
        when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "super@erp.com",
                "Bootstrap@123",
                "SKE",
                "admin@bbp.dev",
                "DevAdmin@123!");

        runner.run();

        assertThat(existingSuperAdmin.getRoles()).extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThat(existingSuperAdmin.getRoles()).hasSize(2);
    }

    @Test
    void seedDefaultUser_createsConfiguredSuperAdmin_withNormalizedEmailAndFallbackCompanyCode() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company bbp = company("BBP");
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("SKE")).thenReturn(Optional.empty());
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findByEmailIgnoreCase("super@erp.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));
        when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "enc-" + invocation.getArgument(0));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                " SUPER@ERP.COM ",
                "Bootstrap@123",
                "   ",
                "admin@bbp.dev",
                "DevAdmin@123!");

        runner.run();

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        UserAccount savedSuperAdmin = userCaptor.getAllValues().stream()
                .filter(user -> "super@erp.com".equalsIgnoreCase(user.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(savedSuperAdmin.getDisplayName()).isEqualTo("Platform Super Admin");
        assertThat(savedSuperAdmin.isMustChangePassword()).isTrue();
        assertThat(savedSuperAdmin.getRoles()).extracting(Role::getName)
                .contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        verify(passwordEncoder).encode("Bootstrap@123");
        verify(companyRepository).findByCodeIgnoreCase("SKE");
    }

    @Test
    void seedDefaultUser_skipsSuperAdminBootstrap_whenConfiguredEmailIsBlank() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company bbp = company("BBP");
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));
        when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "   ",
                "Bootstrap@123",
                "SKE",
                "admin@bbp.dev",
                "DevAdmin@123!");

        runner.run();

        verify(userRepository, never()).findByEmailIgnoreCase("super@erp.com");
        verify(passwordEncoder, never()).encode("Bootstrap@123");
    }

    @Test
    void seedDefaultUser_requiresPasswordWhenSeedingNewSuperAdmin() {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(userRepository.findByEmailIgnoreCase("super@erp.com")).thenReturn(Optional.empty());

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "super@erp.com",
                " ",
                "SKE",
                "admin@bbp.dev",
                "DevAdmin@123!");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("erp.seed.super-admin.password is required");
    }

    @Test
    void seedDefaultUser_createsDefaultPlatformRolesWhenMissing() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Company bbp = company("BBP");
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));
        when(passwordEncoder.matches("DevAdmin@123!", "dev-hash")).thenReturn(true);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "   ",
                "Bootstrap@123",
                "SKE",
                "admin@bbp.dev",
                "DevAdmin@123!");

        runner.run();

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, atLeast(2)).save(roleCaptor.capture());
        assertThat(roleCaptor.getAllValues()).extracting(Role::getName)
                .contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
    }

    @Test
    void seedDefaultUser_createsDevAdminWithConfiguredPassword() throws Exception {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company bbp = company("BBP");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("dev.seed@bbp.dev")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenAnswer(invocation -> "enc-" + invocation.getArgument(0));
        when(accountRepository.findByCompanyAndCodeIgnoreCase(any(Company.class), anyString()))
                .thenReturn(Optional.of(new Account()));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "   ",
                "   ",
                "SKE",
                "dev.seed@bbp.dev",
                "DevSeed@123!");

        runner.run();

        verify(passwordEncoder).encode("DevSeed@123!");
        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());
        UserAccount savedDevAdmin = userCaptor.getAllValues().stream()
                .filter(user -> "dev.seed@bbp.dev".equalsIgnoreCase(user.getEmail()))
                .findFirst()
                .orElseThrow();
        assertThat(savedDevAdmin.isMustChangePassword()).isTrue();
        assertThat(savedDevAdmin.getDisplayName()).isEqualTo("Dev Admin");
        assertThat(savedDevAdmin.getRoles()).extracting(Role::getName).contains("ROLE_ADMIN");
    }

    @Test
    void seedDefaultUser_requiresPasswordWhenSeedingNewDevAdmin() {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company bbp = company("BBP");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("dev.missing-password@bbp.dev")).thenReturn(Optional.empty());

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "   ",
                "   ",
                "SKE",
                "dev.missing-password@bbp.dev",
                "   ");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("erp.seed.dev-admin.password is required");
    }

    @Test
    void seedDefaultUser_requiresPasswordChallengeWhenExistingDevAdminPasswordIsBlank() {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");
        Role superAdminRole = role("ROLE_SUPER_ADMIN");
        Company bbp = company("BBP");
        UserAccount existingDevAdmin = new UserAccount("admin@bbp.dev", "dev-hash", "Dev Admin");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_SUPER_ADMIN")).thenReturn(Optional.of(superAdminRole));
        when(companyRepository.findByCodeIgnoreCase("BBP")).thenReturn(Optional.of(bbp));
        when(userRepository.findByEmailIgnoreCase("admin@bbp.dev")).thenReturn(Optional.of(existingDevAdmin));

        CommandLineRunner runner = initializer.seedDefaultUser(
                userRepository,
                companyRepository,
                roleRepository,
                accountRepository,
                passwordEncoder,
                "   ",
                "   ",
                "SKE",
                "admin@bbp.dev",
                "   ");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be provided and match existing bootstrap user credentials");
    }

    @Test
    void ensureCompanyMembership_handlesNullsAndAvoidsDuplicateMemberships() {
        DataInitializer initializer = new DataInitializer();
        Company company = company("SKE");

        ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", null, company);
        ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", new UserAccount("u@x.com", "h", "U"), null);

        UserAccount userWithNullSlot = new UserAccount("a@x.com", "h", "A");
        userWithNullSlot.getCompanies().add(null);
        ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", userWithNullSlot, company);
        assertThat(userWithNullSlot.getCompanies()).contains(company);

        UserAccount userWithCodeMatch = new UserAccount("b@x.com", "h", "B");
        Company existingCodeMatch = company("ske");
        userWithCodeMatch.addCompany(existingCodeMatch);
        ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", userWithCodeMatch, company);
        assertThat(userWithCodeMatch.getCompanies()).hasSize(1);

        UserAccount userWithIdMatch = new UserAccount("c@x.com", "h", "C");
        Company existingIdMatch = company("OTHER");
        ReflectionTestUtils.setField(existingIdMatch, "id", 7L);
        Company targetIdMatch = company("DIFFERENT");
        ReflectionTestUtils.setField(targetIdMatch, "id", 7L);
        userWithIdMatch.addCompany(existingIdMatch);
        ReflectionTestUtils.invokeMethod(initializer, "ensureCompanyMembership", userWithIdMatch, targetIdMatch);
        assertThat(userWithIdMatch.getCompanies()).hasSize(1);
    }

    @Test
    void ensureRoleMembership_handlesNullsAndAvoidsDuplicateRoleNames() {
        DataInitializer initializer = new DataInitializer();
        Role adminRole = role("ROLE_ADMIN");

        ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", null, adminRole);
        ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", new UserAccount("u@x.com", "h", "U"), null);
        ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", new UserAccount("u2@x.com", "h", "U2"), role("   "));

        UserAccount user = new UserAccount("seed@x.com", "h", "Seed");
        user.getRoles().add(null);
        user.addRole(role("   "));
        ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", user, adminRole);
        assertThat(user.getRoles().stream()
                .filter(role -> role != null)
                .map(Role::getName))
                .contains("ROLE_ADMIN");

        ReflectionTestUtils.invokeMethod(initializer, "ensureRoleMembership", user, role("role_admin"));
        assertThat(user.getRoles().stream()
                .filter(role -> role != null && "ROLE_ADMIN".equalsIgnoreCase(role.getName()))
                .count()).isEqualTo(1);
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
