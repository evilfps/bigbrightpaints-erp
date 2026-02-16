package com.bigbrightpaints.erp.modules.rbac.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.rbac.domain.Permission;
import com.bigbrightpaints.erp.modules.rbac.domain.PermissionRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.SystemRole;
import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import com.bigbrightpaints.erp.modules.rbac.dto.PermissionDto;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
import jakarta.transaction.Transactional;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RoleService {

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

    @Transactional
    public RoleDto createRole(CreateRoleRequest request) {
        return persistRole(request);
    }

    @Transactional
    public Role ensureRoleExists(String roleName) {
        String normalizedName = normalizeRoleName(roleName);
        enforceSuperAdminForPrivilegedRoles(normalizedName, "tenant-admin-role-management");
        SystemRole definition = SystemRole.fromName(normalizedName).orElse(null);
        // Use pessimistic lock to prevent race condition on concurrent role creation
        return roleRepository.lockByName(normalizedName).orElseGet(() -> {
            Role role = new Role();
            role.setName(normalizedName);
            role.setDescription(definition != null ? definition.getDescription() : normalizedName);
            if (definition != null) {
                definition.getDefaultPermissions().forEach(code -> role.getPermissions().add(ensurePermissionExists(code)));
            }
            return roleRepository.save(role);
        });
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

    private RoleDto persistRole(CreateRoleRequest request) {
        String normalizedName = normalizeRoleName(request.name());
        enforceSuperAdminForPrivilegedRoles(normalizedName, "platform-role-create");
        SystemRole definition = SystemRole.fromName(normalizedName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown platform role: " + normalizedName));

        // Use pessimistic lock to prevent race condition
        Role role = roleRepository.lockByName(normalizedName).orElseGet(Role::new);
        role.setName(normalizedName);
        String description = StringUtils.hasText(request.description())
                ? request.description().trim()
                : definition.getDescription();
        role.setDescription(description);

        List<String> permissionCodes = request.permissions().stream()
                .map(String::trim)
                .toList();
        var permissions = permissionRepository.findByCodeIn(permissionCodes);
        if (permissions.size() != permissionCodes.size()) {
            throw new IllegalArgumentException("One or more permission codes are invalid.");
        }

        role.getPermissions().clear();
        role.getPermissions().addAll(new HashSet<>(permissions));

        Role saved = roleRepository.save(role);
        return toDto(saved);
    }

    private String normalizeRoleName(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw new IllegalArgumentException("Role name is required");
        }
        return roleName.trim().toUpperCase(Locale.ROOT);
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
        return "ROLE_ADMIN".equalsIgnoreCase(roleName) || "ROLE_SUPER_ADMIN".equalsIgnoreCase(roleName);
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
