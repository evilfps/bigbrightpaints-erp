package com.bigbrightpaints.erp.modules.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record MfaActivateRequest(
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "MFA code must be 6 digits")
        String code
) {}

