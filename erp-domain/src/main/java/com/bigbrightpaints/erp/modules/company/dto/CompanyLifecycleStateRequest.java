package com.bigbrightpaints.erp.modules.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CompanyLifecycleStateRequest(
        @NotBlank
        @Pattern(regexp = "(?i)ACTIVE|HOLD|BLOCKED", message = "state must be ACTIVE, HOLD, or BLOCKED")
        String state,
        @NotBlank
        @Size(max = 1024)
        String reason
) {}
