package com.bigbrightpaints.erp.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
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
  @Mock private ScopedAccountBootstrapService scopedAccountBootstrapService;
  @Mock private PasswordResetService passwordResetService;

  @Test
  void isCredentialProvisioningReady_delegatesToScopedBootstrapService() {
    TenantAdminProvisioningService service = newService();
    when(scopedAccountBootstrapService.isCredentialProvisioningReady()).thenReturn(true);

    assertThat(service.isCredentialProvisioningReady()).isTrue();
  }

  @Test
  void provisionInitialAdmin_bootstrapsScopedTenantAdmin() {
    TenantAdminProvisioningService service = newService();
    Company company = company(10L, "SKE", "SKE");
    Role adminRole = role("ROLE_ADMIN");
    UserAccount provisioned = new UserAccount("new-admin@ske.com", "SKE", "hash", "New Admin");
    ReflectionTestUtils.setField(provisioned, "id", 44L);

    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "new-admin@ske.com", "SKE"))
        .thenReturn(false);
    when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
    when(scopedAccountBootstrapService.provisionTenantAccount(
            eq(company),
            eq("new-admin@ske.com"),
            eq("New Admin"),
            eq(java.util.List.of(adminRole))))
        .thenReturn(provisioned);

    UserAccount provisionedAdmin =
        service.provisionInitialAdmin(company, " NEW-ADMIN@SKE.COM ", "New Admin");

    assertThat(provisionedAdmin.getEmail()).isEqualTo("new-admin@ske.com");
    assertThat(company.getMainAdminUserId()).isEqualTo(provisioned.getId());
    assertThat(company.getOnboardingAdminEmail()).isEqualTo("new-admin@ske.com");
    assertThat(company.getOnboardingAdminUserId()).isEqualTo(provisioned.getId());
    verify(roleService).ensureRoleExists("ROLE_ADMIN");
    verify(scopedAccountBootstrapService)
        .provisionTenantAccount(
            company, "new-admin@ske.com", "New Admin", java.util.List.of(adminRole));
  }

  @Test
  void provisionInitialAdmin_rejectsScopedDuplicateEmail() {
    TenantAdminProvisioningService service = newService();
    Company company = company(10L, "SKE", "SKE");
    when(userAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "admin@ske.com", "SKE"))
        .thenReturn(true);

    assertThatThrownBy(() -> service.provisionInitialAdmin(company, "admin@ske.com", "Admin"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void provisionInitialAdmin_requiresPersistedCompany() {
    TenantAdminProvisioningService service = newService();

    assertThatThrownBy(
            () ->
                service.provisionInitialAdmin(
                    company(null, "SKE", "SKE"), "admin@ske.com", "Admin"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("Company must be persisted");
  }

  @Test
  void resetTenantAdminPassword_delegatesToCanonicalResetLinkFlow() {
    TenantAdminProvisioningService service = newService();
    Company company = company(10L, "SKE", "SKE");
    UserAccount admin = new UserAccount("admin@ske.com", "SKE", "hash", "Admin");
    admin.setCompany(company);
    admin.addRole(role("ROLE_ADMIN"));

    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "admin@ske.com", "SKE"))
        .thenReturn(Optional.of(admin));

    String email = service.resetTenantAdminPassword(company, " admin@ske.com ");

    assertThat(email).isEqualTo("admin@ske.com");
    verify(passwordResetService).requestResetByAdmin(admin);
  }

  @Test
  void resetTenantAdminPassword_rejectsNonAdminTargets() {
    TenantAdminProvisioningService service = newService();
    Company company = company(10L, "SKE", "SKE");
    UserAccount user = new UserAccount("user@ske.com", "SKE", "hash", "User");
    user.setCompany(company);

    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@ske.com", "SKE"))
        .thenReturn(Optional.of(user));

    assertThatThrownBy(() -> service.resetTenantAdminPassword(company, "user@ske.com"))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("not an admin");
  }

  private TenantAdminProvisioningService newService() {
    return new TenantAdminProvisioningService(
        userAccountRepository,
        roleService,
        roleRepository,
        scopedAccountBootstrapService,
        passwordResetService);
  }

  private Company company(Long id, String code, String name) {
    Company company = new Company();
    ReflectionTestUtils.setField(company, "id", id);
    company.setCode(code);
    company.setName(name);
    return company;
  }

  private Role role(String name) {
    Role role = new Role();
    role.setName(name);
    return role;
  }
}
