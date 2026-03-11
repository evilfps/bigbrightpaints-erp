package com.bigbrightpaints.erp.modules.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private AuditService auditService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        CompanyContextHolder.clear();
    }

    @Test
    void synchronizeSystemRolePermissions_backfillsMissingDispatchConfirmForExistingAccountingRole() {
        Role accounting = role("ROLE_ACCOUNTING", permission("portal:accounting"));
        when(roleRepository.findByNameIn(List.of(
                "ROLE_SUPER_ADMIN",
                "ROLE_ADMIN",
                "ROLE_ACCOUNTING",
                "ROLE_FACTORY",
                "ROLE_SALES",
                "ROLE_DEALER"))).thenReturn(List.of(accounting));
        when(permissionRepository.findByCode("dispatch.confirm")).thenReturn(Optional.of(permission("dispatch.confirm")));
        when(permissionRepository.findByCode("payroll.run")).thenReturn(Optional.of(permission("payroll.run")));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        int updatedRoles = service.synchronizeSystemRolePermissions();

        assertThat(updatedRoles).isEqualTo(1);
        assertThat(accounting.getPermissions())
                .extracting(Permission::getCode)
                .contains("portal:accounting", "dispatch.confirm", "payroll.run");
        verify(roleRepository).save(accounting);
    }

    @Test
    void ensureRoleExists_backfillsExistingAccountingRoleBeforeReturningIt() {
        Role accounting = role("ROLE_ACCOUNTING", permission("portal:accounting"));
        when(roleRepository.lockByName("ROLE_ACCOUNTING")).thenReturn(Optional.of(accounting));
        when(permissionRepository.findByCode("dispatch.confirm")).thenReturn(Optional.of(permission("dispatch.confirm")));
        when(permissionRepository.findByCode("payroll.run")).thenReturn(Optional.of(permission("payroll.run")));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        Role ensured = service.ensureRoleExists("ROLE_ACCOUNTING");

        assertThat(ensured.getPermissions())
                .extracting(Permission::getCode)
                .contains("dispatch.confirm", "payroll.run");
        verify(roleRepository).save(accounting);
    }

    @Test
    void listRolesForCurrentActor_hidesSuperAdminRoleFromNonSuperAdminActors() {
        authenticate("tenant-admin@bbp.com", "ROLE_ADMIN");
        when(roleRepository.findByNameIn(SystemRole.roleNames())).thenReturn(List.of());

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.listRolesForCurrentActor())
                .extracting(role -> role.name())
                .doesNotContain("ROLE_SUPER_ADMIN");
    }

    @Test
    void listRolesForCurrentActor_includesSuperAdminRoleForSuperAdminActors() {
        authenticate("root-superadmin@bbp.com", "ROLE_SUPER_ADMIN");
        when(roleRepository.findByNameIn(SystemRole.roleNames())).thenReturn(List.of());

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.listRolesForCurrentActor())
                .extracting(role -> role.name())
                .contains("ROLE_SUPER_ADMIN");
    }

    @Test
    void createRole_requiresSuperAdminForSharedRoleMutation() {
        authenticate("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThatThrownBy(() -> service.createRole(new CreateRoleRequest(
                        "ROLE_ACCOUNTING",
                        "Accounting role",
                        List.of("dispatch.confirm"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for role mutation");

        ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue())
                .containsEntry("actor", "tenant-admin@bbp.com")
                .containsEntry("reason", "shared-role-permission-mutation-requires-super-admin")
                .containsEntry("tenantScope", "TENANT-A")
                .containsEntry("targetRole", "ROLE_ACCOUNTING");
    }

    @Test
    void createRole_allowsSuperAdminToMutateSharedRole() {
        authenticate("root-superadmin@bbp.com", "ROLE_SUPER_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        when(roleRepository.lockByName("ROLE_ACCOUNTING")).thenReturn(Optional.empty());
        when(permissionRepository.findByCodeIn(List.of("dispatch.confirm", "payroll.run")))
                .thenReturn(List.of(permission("dispatch.confirm"), permission("payroll.run")));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.createRole(new CreateRoleRequest(
                        "ROLE_ACCOUNTING",
                        "Accounting role",
                        List.of("dispatch.confirm", "payroll.run"))))
                .extracting(role -> role.name(), role -> role.description())
                .containsExactly("ROLE_ACCOUNTING", "Accounting role");

        verify(auditService).logSuccess(eq(AuditEvent.ACCESS_GRANTED), any(Map.class));
    }

    @Test
    void canManageSharedRoleMutation_requiresSuperAdminForSystemRoles() {
        authenticate("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.canManageSharedRoleMutation(
                        SecurityContextHolder.getContext().getAuthentication(),
                        "ROLE_ACCOUNTING"))
                .isFalse();

        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
    }

    @Test
    void canManageSharedRoleMutation_allowsSystemRolesForSuperAdmin() {
        authenticate("root-superadmin@bbp.com", "ROLE_SUPER_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.canManageSharedRoleMutation(
                        SecurityContextHolder.getContext().getAuthentication(),
                        "ROLE_ACCOUNTING"))
                .isTrue();

        verify(auditService).logSuccess(eq(AuditEvent.ACCESS_GRANTED), any(Map.class));
    }

    @Test
    void ensureRoleExists_allowsSuperAdminForPrivilegedRoles() {
        authenticate("root-superadmin@bbp.com", "ROLE_SUPER_ADMIN");
        when(roleRepository.lockByName("ROLE_ADMIN")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        Role ensured = service.ensureRoleExists("ROLE_ADMIN");

        assertThat(ensured.getName()).isEqualTo("ROLE_ADMIN");
        verify(auditService).logSuccess(eq(AuditEvent.ACCESS_GRANTED), any(Map.class));
    }

    @Test
    void ensureRoleExists_requiresSuperAdminForPrivilegedRoles() {
        authenticate("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThatThrownBy(() -> service.ensureRoleExists("ROLE_ADMIN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SUPER_ADMIN authority required for role: ROLE_ADMIN");

        verify(auditService).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
        verifyNoMoreInteractions(roleRepository);
    }

    @Test
    void canManageSharedRoleMutation_allowsCustomRolesWithoutAudit() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.canManageSharedRoleMutation(null, "custom-role")).isTrue();
        assertThat(service.canManageSharedRoleMutation(null, null)).isTrue();

        verifyNoMoreInteractions(auditService);
    }

    private Role role(String name, Permission... permissions) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(name + " description");
        role.getPermissions().addAll(List.of(permissions));
        return role;
    }

    private Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        permission.setDescription(code);
        return permission;
    }

    private void authenticate(String username, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username,
                "N/A",
                List.of(new SimpleGrantedAuthority(authority))));
    }
}
