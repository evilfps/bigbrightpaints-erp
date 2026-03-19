package com.bigbrightpaints.erp.modules.rbac.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.rbac.dto.PermissionDto;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class RoleService {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AuditService auditService;

    public RoleService(RoleRepository roleRepository,
                       PermissionRepository permissionRepository,
                       AuditService auditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.auditService = auditService;
    }

    public List<RoleDto> listRoles() {
        return allSystemRoles();
    }

    public List<RoleDto> listRolesForCurrentActor() {
        return allSystemRoles().stream()
                .filter(role -> role.name() == null || !SUPER_ADMIN_ROLE.equalsIgnoreCase(role.name()))
                .toList();
    }

    @Transactional
    public int synchronizeSystemRolePermissions() {
        Map<String, Permission> permissionCache = new HashMap<>();
        int updatedRoles = 0;
        for (Role role : roleRepository.findByNameIn(SystemRole.roleNames())) {
            SystemRole definition = SystemRole.fromName(role.getName()).orElse(null);
            if (definition != null && synchronizeSystemRolePermissions(role, definition, permissionCache)) {
                roleRepository.save(role);
                updatedRoles++;
            }
        }
        return updatedRoles;
    }

    private List<RoleDto> allSystemRoles() {
        Map<String, Role> rolesByName = roleRepository.findByNameIn(SystemRole.roleNames())
                .stream()
                .collect(Collectors.toMap(Role::getName, Function.identity()));
        return Arrays.stream(SystemRole.values())
                .map(definition -> {
                    Role role = rolesByName.get(definition.getRoleName());
                    if (role == null) {
                        return new RoleDto(null, definition.getRoleName(), definition.getDescription(), List.of());
                    }
                    return toDto(role);
                })
                .toList();
    }

    public Role requireFixedSystemRole(String roleName) {
        String normalizedName = normalizeRoleName(roleName);
        SystemRole.fromName(normalizedName)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Unknown platform role: " + normalizedName));
        return roleRepository.findByName(normalizedName)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                        "Required platform role is missing: " + normalizedName));
    }

    public Role requireAdminSurfaceAssignmentRole(String roleName) {
        String normalizedName = normalizeRoleName(roleName);
        if (SUPER_ADMIN_ROLE.equalsIgnoreCase(normalizedName)) {
            throw new AccessDeniedException("ROLE_SUPER_ADMIN is reserved for platform-owner internal use");
        }
        if (ADMIN_ROLE.equalsIgnoreCase(normalizedName)) {
            enforceSuperAdminForPrivilegedRoles(normalizedName, "tenant-admin-role-management");
        }
        return requireFixedSystemRole(normalizedName);
    }

    public Permission ensurePermissionExists(String code) {
        return permissionRepository.findByCode(code).orElseGet(() -> {
            Permission permission = new Permission();
            permission.setCode(code);
            permission.setDescription(code);
            return permissionRepository.save(permission);
        });
    }

    public boolean isSystemRole(String roleName) {
        return SystemRole.fromName(roleName).isPresent();
    }

    @Transactional
    public int synchronizeSystemRoles() {
        Map<String, Permission> permissionCache = new HashMap<>();
        int synchronizedRoles = 0;
        for (SystemRole definition : SystemRole.values()) {
            if (synchronizeSystemRole(definition, permissionCache)) {
                synchronizedRoles++;
            }
        }
        return synchronizedRoles;
    }

    private boolean synchronizeSystemRole(SystemRole definition, Map<String, Permission> permissionCache) {
        Role role = roleRepository.lockByName(definition.getRoleName()).orElseGet(Role::new);
        boolean dirty = role.getId() == null;
        if (!definition.getRoleName().equals(role.getName())) {
            role.setName(definition.getRoleName());
            dirty = true;
        }
        if (!definition.getDescription().equals(role.getDescription())) {
            role.setDescription(definition.getDescription());
            dirty = true;
        }
        if (replacePermissionsIfDrifted(role, definition, permissionCache)) {
            dirty = true;
        }
        if (!dirty) {
            return false;
        }
        roleRepository.save(role);
        return true;
    }

    private String normalizeRoleName(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Role name is required");
        }
        return roleName.trim().toUpperCase(Locale.ROOT);
    }

    private boolean synchronizeSystemRolePermissions(Role role, SystemRole definition, Map<String, Permission> permissionCache) {
        boolean changed = false;
        if (!definition.getDescription().equals(role.getDescription())) {
            role.setDescription(definition.getDescription());
            changed = true;
        }
        if (replacePermissionsIfDrifted(role, definition, permissionCache)) {
            changed = true;
        }
        return changed;
    }

    private boolean replacePermissionsIfDrifted(Role role, SystemRole definition, Map<String, Permission> permissionCache) {
        Set<String> existingCodes = role.getPermissions().stream()
                .map(Permission::getCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(HashSet::new));
        LinkedHashSet<String> expectedCodes = new LinkedHashSet<>(definition.getDefaultPermissions());
        if (existingCodes.equals(expectedCodes)) {
            return false;
        }
        role.getPermissions().clear();
        for (String code : definition.getDefaultPermissions()) {
            role.getPermissions().add(permissionCache.computeIfAbsent(code, this::ensurePermissionExists));
        }
        return true;
    }

    private RoleDto toDto(Role role) {
        List<PermissionDto> permissions = role.getPermissions().stream()
                .map(p -> new PermissionDto(p.getId(), p.getCode(), p.getDescription()))
                .toList();
        String description = StringUtils.hasText(role.getDescription())
                ? role.getDescription()
                : SystemRole.fromName(role.getName())
                        .map(SystemRole::getDescription)
                        .orElse(role.getName());
        return new RoleDto(role.getId(), role.getName(), description, permissions);
    }

    private void enforceSuperAdminForPrivilegedRoles(String normalizedRoleName, String action) {
        if (!requiresSuperAdmin(normalizedRoleName)) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAuthority(authentication, "ROLE_SUPER_ADMIN")) {
            auditAuthorityDecision(false, action, normalizedRoleName, authentication);
            throw new AccessDeniedException("SUPER_ADMIN authority required for role: " + normalizedRoleName);
        }
        auditAuthorityDecision(true, action, normalizedRoleName, authentication);
    }

    private boolean requiresSuperAdmin(String roleName) {
        return ADMIN_ROLE.equalsIgnoreCase(roleName) || SUPER_ADMIN_ROLE.equalsIgnoreCase(roleName);
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(granted -> authority.equalsIgnoreCase(granted));
    }

    private void auditAuthorityDecision(boolean granted, String action, String targetRole, Authentication authentication) {
        HashMap<String, String> metadata = new HashMap<>();
        metadata.put("actor", resolveActor(authentication));
        metadata.put("reason", granted
                ? action + "-approved"
                : action + "-requires-super-admin");
        metadata.put("tenantScope", resolveTenantScope(authentication));
        metadata.put("targetRole", targetRole);
        if (granted) {
            auditService.logSuccess(AuditEvent.ACCESS_GRANTED, metadata);
        } else {
            auditService.logFailure(AuditEvent.ACCESS_DENIED, metadata);
        }
    }

    private String resolveActor(Authentication authentication) {
        if (authentication == null) {
            return "anonymous";
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? name.trim() : "anonymous";
    }

    private String resolveTenantScope(Authentication authentication) {
        String scope = CompanyContextHolder.getCompanyCode();
        if (StringUtils.hasText(scope)) {
            return scope.trim();
        }
        return "none";
    }
}
