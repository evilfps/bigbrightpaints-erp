package com.bigbrightpaints.erp.modules.rbac.controller;

import com.bigbrightpaints.erp.modules.rbac.dto.CreateRoleRequest;
import com.bigbrightpaints.erp.modules.rbac.dto.RoleDto;
import com.bigbrightpaints.erp.modules.rbac.service.RoleService;
import java.util.Locale;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<List<RoleDto>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.success("Platform roles", roleService.listRolesForCurrentActor()));
    }

    @GetMapping("/{roleKey}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<RoleDto>> getRoleByKey(@org.springframework.web.bind.annotation.PathVariable String roleKey) {
        String normalized = roleKey == null ? "" : roleKey.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("ROLE_")) {
            normalized = "ROLE_" + normalized;
        }
        String target = normalized;
        RoleDto match = roleService.listRolesForCurrentActor().stream()
                .filter(r -> r.name() != null && r.name().equalsIgnoreCase(target))
                .findFirst()
                .orElseGet(() -> new RoleDto(null, target, target, List.of()));
        return ResponseEntity.ok(ApiResponse.success("Role " + target, match));
    }

    @PostMapping
    @PreAuthorize("@roleService.canManageSharedRoleMutation(authentication, #request.name())")
    public ResponseEntity<ApiResponse<RoleDto>> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Role saved", roleService.createRole(request)));
    }
}
