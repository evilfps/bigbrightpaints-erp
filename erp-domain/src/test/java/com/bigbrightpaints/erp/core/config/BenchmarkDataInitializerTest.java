package com.bigbrightpaints.erp.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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

@ExtendWith(MockitoExtension.class)
class BenchmarkDataInitializerTest {

  @Mock private RoleRepository roleRepository;
  @Mock private UserAccountRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;

  private BenchmarkDataInitializer initializer;

  @BeforeEach
  void setUp() {
    initializer = new BenchmarkDataInitializer();
    when(roleRepository.findByName(anyString()))
        .thenAnswer(invocation -> Optional.of(role(invocation.getArgument(0, String.class))));
    when(userRepository.save(any(UserAccount.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, UserAccount.class));
  }

  @Test
  void seedRolesAndUsers_createsScopedBenchmarkAdminWhenMissing() {
    Company company = company("BBP");
    when(passwordEncoder.encode("Temp123!")).thenReturn("encoded-password");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "benchmark@example.com", "BBP"))
        .thenReturn(Optional.empty());

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " Benchmark@Example.com ",
        " Temp123! ");

    ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
    verify(userRepository).save(userCaptor.capture());
    UserAccount saved = userCaptor.getValue();

    assertThat(saved.getEmail()).isEqualTo("benchmark@example.com");
    assertThat(saved.getAuthScopeCode()).isEqualTo("BBP");
    assertThat(saved.getCompany()).isEqualTo(company);
    assertThat(saved.isMustChangePassword()).isTrue();
    assertThat(saved.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY");
  }

  @Test
  void seedRolesAndUsers_updatesExistingScopedBenchmarkAdminWhenPresent() {
    Company company = company("BBP");
    UserAccount existingAdmin =
        new UserAccount("legacy@example.com", "OLD", "hash", "Legacy Admin");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "legacy@example.com", "BBP"))
        .thenReturn(Optional.of(existingAdmin));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "seedRolesAndUsers",
        roleRepository,
        userRepository,
        passwordEncoder,
        company,
        " legacy@example.com ",
        "");

    verify(userRepository).save(existingAdmin);
    assertThat(existingAdmin.getAuthScopeCode()).isEqualTo("BBP");
    assertThat(existingAdmin.getCompany()).isEqualTo(company);
    assertThat(existingAdmin.getRoles())
        .extracting(Role::getName)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES", "ROLE_FACTORY");
  }

  private Company company(String code) {
    Company company = new Company();
    company.setCode(code);
    company.setName(code + " Co");
    company.setTimezone("UTC");
    return company;
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    role.setDescription(name);
    return role;
  }
}
