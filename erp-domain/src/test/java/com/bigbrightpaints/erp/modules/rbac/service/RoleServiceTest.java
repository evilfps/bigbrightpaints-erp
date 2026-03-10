package com.bigbrightpaints.erp.modules.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private AuditService auditService;

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
}
