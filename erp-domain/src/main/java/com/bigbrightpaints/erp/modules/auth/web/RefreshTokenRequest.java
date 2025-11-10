package com.bigbrightpaints.erp.modules.auth.web;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank String refreshToken,
        @NotBlank String companyCode
) {}
