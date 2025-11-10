package com.bigbrightpaints.erp.modules.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password,
        @NotBlank String companyCode,
        String mfaCode,
        String recoveryCode
) {}
