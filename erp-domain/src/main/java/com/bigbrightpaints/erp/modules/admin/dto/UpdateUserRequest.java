package com.bigbrightpaints.erp.modules.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateUserRequest(
        @NotBlank String displayName,
        List<Long> companyIds,
        List<String> roles,
        Boolean enabled
) {}
