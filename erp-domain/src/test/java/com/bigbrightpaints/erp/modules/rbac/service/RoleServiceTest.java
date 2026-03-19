package com.bigbrightpaints.erp.modules.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

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
    void synchronizeSystemRolePermissions_reconcilesExistingRoleToCanonicalPermissionSet() {
        Role accounting = role("ROLE_ACCOUNTING", permission("portal:accounting"), permission("portal:rogue"));
        accounting.setDescription("Legacy accounting");
        when(roleRepository.findByNameIn(List.of(
                "ROLE_SUPER_ADMIN",
                "ROLE_ADMIN",
                "ROLE_ACCOUNTING",
                "ROLE_FACTORY",
                "ROLE_SALES",
                "ROLE_DEALER"))).thenReturn(List.of(accounting));
        when(permissionRepository.findByCode(any())).thenAnswer(invocation ->
                Optional.of(permission(invocation.getArgument(0, String.class))));
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        int updatedRoles = service.synchronizeSystemRolePermissions();

        assertThat(updatedRoles).isEqualTo(1);
        assertThat(accounting.getDescription()).isEqualTo(SystemRole.ACCOUNTING.getDescription());
        assertThat(accounting.getPermissions())
                .extracting(Permission::getCode)
                .containsExactlyInAnyOrderElementsOf(SystemRole.ACCOUNTING.getDefaultPermissions());
        verify(roleRepository).save(accounting);
    }

    @Test
    void synchronizeSystemRoles_returnsZeroWhenRolesAlreadyAligned() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        for (SystemRole definition : SystemRole.values()) {
            Role role = role(definition.getRoleName(), definition.getDefaultPermissions().stream()
                    .map(this::permission)
                    .toArray(Permission[]::new));
            ReflectionTestUtils.setField(role, "id", (long) definition.ordinal() + 1L);
            role.setDescription(definition.getDescription());
            when(roleRepository.lockByName(definition.getRoleName())).thenReturn(Optional.of(role));
        }

        int synchronizedRoles = service.synchronizeSystemRoles();

        assertThat(synchronizedRoles).isZero();
    }

    @Test
    void synchronizeSystemRolePermissions_returnsZeroWhenExistingRoleAlreadyAligned() {
        Role accounting = role(
                "ROLE_ACCOUNTING",
                SystemRole.ACCOUNTING.getDefaultPermissions().stream()
                        .map(this::permission)
                        .toArray(Permission[]::new));
        accounting.setDescription(SystemRole.ACCOUNTING.getDescription());
        when(roleRepository.findByNameIn(List.of(
                "ROLE_SUPER_ADMIN",
                "ROLE_ADMIN",
                "ROLE_ACCOUNTING",
                "ROLE_FACTORY",
                "ROLE_SALES",
                "ROLE_DEALER"))).thenReturn(List.of(accounting));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        int updatedRoles = service.synchronizeSystemRolePermissions();

        assertThat(updatedRoles).isZero();
        verify(roleRepository, never()).save(accounting);
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
    void listRolesForCurrentActor_hidesSuperAdminRoleFromSuperAdminAdminSurface() {
        authenticate("root-superadmin@bbp.com", "ROLE_SUPER_ADMIN");
        when(roleRepository.findByNameIn(SystemRole.roleNames())).thenReturn(List.of());

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.listRolesForCurrentActor())
                .extracting(role -> role.name())
                .doesNotContain("ROLE_SUPER_ADMIN");
    }

    @Test
    void requireFixedSystemRole_returnsPersistedSystemRole() {
        Role accounting = role("ROLE_ACCOUNTING", permission("portal:accounting"));
        when(roleRepository.findByName("ROLE_ACCOUNTING")).thenReturn(Optional.of(accounting));

        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.requireFixedSystemRole("ROLE_ACCOUNTING")).isSameAs(accounting);
    }

    @Test
    void requireFixedSystemRole_rejectsUnknownPlatformRole() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThatThrownBy(() -> service.requireFixedSystemRole("ROLE_CUSTOM"))
                .isInstanceOf(com.bigbrightpaints.erp.core.exception.ApplicationException.class)
                .hasMessageContaining("Unknown platform role: ROLE_CUSTOM");
    }

    @Test
    void requireFixedSystemRole_rejectsMissingPersistedRole() {
        when(roleRepository.findByName("ROLE_ACCOUNTING")).thenReturn(Optional.empty());
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThatThrownBy(() -> service.requireFixedSystemRole("ROLE_ACCOUNTING"))
                .isInstanceOf(ApplicationException.class)
                .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_INVALID_STATE))
                .hasMessageContaining("Required platform role is missing: ROLE_ACCOUNTING");
    }

    @Test
    void requireAdminSurfaceAssignmentRole_allowsFixedAdminRoleForTenantAdmin() {
        authenticate("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("TENANT-A");
        Role adminRole = role("ROLE_ADMIN", permission("portal:admin"));
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.requireAdminSurfaceAssignmentRole("ROLE_ADMIN")).isSameAs(adminRole);

        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
    }

    @Test
    void requireAdminSurfaceAssignmentRole_rejectsSuperAdminRoleOnAdminSurface() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThatThrownBy(() -> service.requireAdminSurfaceAssignmentRole("ROLE_SUPER_ADMIN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_SUPER_ADMIN is reserved for platform-owner internal use");
    }

    @Test
    void requireAdminSurfaceAssignmentRole_allowsFixedNonPrivilegedRole() {
        Role accounting = role("ROLE_ACCOUNTING", permission("portal:accounting"));
        when(roleRepository.findByName("ROLE_ACCOUNTING")).thenReturn(Optional.of(accounting));
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);

        assertThat(service.requireAdminSurfaceAssignmentRole("ROLE_ACCOUNTING")).isSameAs(accounting);
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
