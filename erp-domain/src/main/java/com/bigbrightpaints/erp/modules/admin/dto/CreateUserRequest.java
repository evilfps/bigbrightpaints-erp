package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateUserRequest(
    @Email @NotBlank String email,
    @NotBlank String displayName,
    @NotEmpty List<@NotBlank String> roles) {}
