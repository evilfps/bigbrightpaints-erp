package com.bigbrightpaints.erp.modules.admin.dto;

import java.util.List;

import org.springframework.lang.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @NotBlank String displayName,
    @Nullable
        @Schema(
            nullable = true,
            description = "Optional full role set; omit to keep existing role assignments")
        @Size(min = 1)
        List<@NotBlank String> roles) {}
