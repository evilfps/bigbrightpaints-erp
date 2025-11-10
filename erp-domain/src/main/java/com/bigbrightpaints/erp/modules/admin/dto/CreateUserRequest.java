package com.bigbrightpaints.erp.modules.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateUserRequest(
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotBlank String displayName,
        @NotEmpty List<Long> companyIds,
        @NotEmpty List<String> roles
) {}
