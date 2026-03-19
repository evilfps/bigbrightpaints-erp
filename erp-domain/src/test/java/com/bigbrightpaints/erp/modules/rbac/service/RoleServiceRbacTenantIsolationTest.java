package com.bigbrightpaints.erp.modules.rbac.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
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
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
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
    void tenant_admin_can_assign_fixed_admin_role() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-TENANT-A");
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));

        Role ensured = service.requireAdminSurfaceAssignmentRole("ROLE_ADMIN");

        assertThat(ensured.getName()).isEqualTo("ROLE_ADMIN");
        verify(roleRepository).findByName("ROLE_ADMIN");
        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
    }

    @Test
    void admin_surface_never_exposes_super_admin_assignment() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("super-admin@bbp.com", "ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-ROOT");

        assertThatThrownBy(() -> service.requireAdminSurfaceAssignmentRole("ROLE_SUPER_ADMIN"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ROLE_SUPER_ADMIN is reserved for platform-owner internal use");
    }

    @Test
    void tenant_admin_can_still_resolve_fixed_non_privileged_roles() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        CompanyContextHolder.setCompanyCode("AUTH-TENANT-A");

        Role dealerRole = new Role();
        dealerRole.setName("ROLE_DEALER");
        when(roleRepository.findByName("ROLE_DEALER")).thenReturn(Optional.of(dealerRole));

        Role ensured = service.requireAdminSurfaceAssignmentRole("ROLE_DEALER");

        assertThat(ensured.getName()).isEqualTo("ROLE_DEALER");
        verify(auditService, never()).logFailure(eq(AuditEvent.ACCESS_DENIED), any(Map.class));
    }

    @Test
    void listRolesForCurrentActor_hidesSuperAdminRole_forTenantAdmin() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("tenant-admin@bbp.com", "ROLE_ADMIN");
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of());

        List<RoleDto> roles = service.listRolesForCurrentActor();

        assertThat(roles).isNotEmpty();
        assertThat(roles).extracting(RoleDto::name).doesNotContain("ROLE_SUPER_ADMIN");
    }

    @Test
    void listRolesForCurrentActor_hidesSuperAdminRole_forSuperAdminAdminSurface() {
        RoleService service = new RoleService(roleRepository, permissionRepository, auditService);
        setAuthentication("super-admin@bbp.com", "ROLE_SUPER_ADMIN");
        when(roleRepository.findByNameIn(anyCollection())).thenReturn(List.of());

        List<RoleDto> roles = service.listRolesForCurrentActor();

        assertThat(roles).extracting(RoleDto::name).doesNotContain("ROLE_SUPER_ADMIN");
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
