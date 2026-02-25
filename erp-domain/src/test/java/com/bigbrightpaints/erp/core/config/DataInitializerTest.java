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
                "SKE");

        runner.run();

        assertThat(existingSuperAdmin.getPasswordHash()).isEqualTo("keep-hash");
        verify(passwordEncoder, never()).encode("Bootstrap@123");
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
                "   ");

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
                "SKE");

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
                "SKE");

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
                "SKE");

        runner.run();

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, atLeast(2)).save(roleCaptor.capture());
        assertThat(roleCaptor.getAllValues()).extracting(Role::getName)
                .contains("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
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
