package com.bigbrightpaints.erp.modules.rbac.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceRbacTenantIsolationTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private AuditService auditService;

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        CompanyContextHolder.clear();
    }

    @Test
    void tenant_admin_cannot_mutate_shared_role_permissions() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-TENANT-A");

        CreateRoleRequest request = new CreateRoleRequest(
                "ROLE_FACTORY",
                "Tenant mutation attempt",
                List.of("portal:factory"));

        assertThatThrownBy(() -> service.createRole(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("role mutation");

        verify(auditService).logFailure(
                eq(AuditEvent.ACCESS_DENIED),
                argThat((Map<String, String> metadata) ->
                        "tenant-admin@bbp.com".equals(metadata.get("actor"))
                                && "AUTH-TENANT-A".equals(metadata.get("tenantScope"))
                                && "ROLE_FACTORY".equals(metadata.get("targetRole"))
                                && "shared-role-permission-mutation-requires-super-admin"
                                .equals(metadata.get("reason"))));
        verify(roleRepository, never()).save(any(Role.class));
    }

    @Test
    void super_admin_can_mutate_shared_role_permissions() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("super-admin@bbp.com", "ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-ROOT");

        Role existing = new Role();
        existing.setName("ROLE_SALES");
        existing.setDescription("Platform sales role");
        Permission permission = new Permission();
        permission.setCode("portal:sales");
        permission.setDescription("portal:sales");

        when(roleRepository.lockByName("ROLE_SALES")).thenReturn(Optional.of(existing));
        when(permissionRepository.findByCodeIn(List.of("portal:sales"))).thenReturn(List.of(permission));
        when(roleRepository.save(existing)).thenReturn(existing);

        RoleDto response = service.createRole(new CreateRoleRequest(
                "ROLE_SALES",
                "Platform sales role",
                List.of("portal:sales")));

        assertThat(response.name()).isEqualTo("ROLE_SALES");
        assertThat(response.permissions()).hasSize(1);
        assertThat(response.permissions().get(0).code()).isEqualTo("portal:sales");
        verify(auditService).logSuccess(
                eq(AuditEvent.ACCESS_GRANTED),
                argThat((Map<String, String> metadata) ->
                        "super-admin@bbp.com".equals(metadata.get("actor"))
                                && "AUTH-ROOT".equals(metadata.get("tenantScope"))
                                && "ROLE_SALES".equals(metadata.get("targetRole"))
                                && "shared-role-permission-mutation-approved".equals(metadata.get("reason"))));
    }

    @Test
    void tenant_admin_can_still_ensure_non_privileged_roles() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-TENANT-A");

        Permission permission = new Permission();
        permission.setCode("portal:dealer");
        permission.setDescription("portal:dealer");
        when(permissionRepository.findByCode("portal:dealer")).thenReturn(Optional.of(permission));
        when(roleRepository.lockByName("ROLE_DEALER")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role ensured = service.ensureRoleExists("ROLE_DEALER");

        assertThat(ensured.getName()).isEqualTo("ROLE_DEALER");
        assertThat(ensured.getPermissions()).extracting(Permission::getCode).contains("portal:dealer");
        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
    }

    @Test
    void ensureRoleExists_recoversFromConcurrentPermissionInsertRace() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-TENANT-A");

        Permission recoveredPermission = new Permission();
        recoveredPermission.setCode("portal:dealer");
        recoveredPermission.setDescription("portal:dealer");

        when(permissionRepository.findByCode("portal:dealer"))
                .thenReturn(Optional.empty(), Optional.of(recoveredPermission));
        when(permissionRepository.save(any(Permission.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate permission code"));
        when(roleRepository.lockByName("ROLE_DEALER")).thenReturn(Optional.empty());
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Role ensured = service.ensureRoleExists("ROLE_DEALER");

        assertThat(ensured.getPermissions()).extracting(Permission::getCode).contains("portal:dealer");
        verify(permissionRepository).save(any(Permission.class));
    }

    private void setAuthentication(String username, String... authorities) {
        List<org.springframework.security.core.authority.SimpleGrantedAuthority> granted =
                java.util.Arrays.stream(authorities)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", granted));
    }
}
