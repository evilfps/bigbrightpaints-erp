package com.bigbrightpaints.erp.modules.auth.web;

public record AuthResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long expiresIn,
        String companyCode,
        String displayName
) {}
