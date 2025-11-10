package com.bigbrightpaints.erp.modules.rbac.dto;

import java.util.List;

public record RoleDto(Long id, String name, String description, List<PermissionDto> permissions) {}
