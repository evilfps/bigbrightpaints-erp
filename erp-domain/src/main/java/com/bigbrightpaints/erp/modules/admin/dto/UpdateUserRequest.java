package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserRequest(
    @NotBlank String displayName, Long companyId, List<String> roles, Boolean enabled) {}
