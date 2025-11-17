package com.bigbrightpaints.erp.modules.rbac.service;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
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

    public Role ensureRoleExists(String roleName) {
        String normalizedName = normalizeRoleName(roleName);
        SystemRole definition = SystemRole.fromName(normalizedName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown platform role: " + normalizedName));
        return roleRepository.findByName(normalizedName).orElseGet(() -> {
            Role role = new Role();
            role.setName(normalizedName);
            role.setDescription(definition.getDescription());
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
        SystemRole definition = SystemRole.fromName(normalizedName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown platform role: " + normalizedName));

        Role role = roleRepository.findByName(normalizedName).orElseGet(Role::new);
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
}
