package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;

final class AuthTokenDigests {

    private static final String REFRESH_TOKEN_SCOPE = "refresh-token";
    private static final String PASSWORD_RESET_TOKEN_SCOPE = "password-reset-token";

    private AuthTokenDigests() {
    }

    static String refreshTokenDigest(String token) {
        return digest(REFRESH_TOKEN_SCOPE, token);
    }

    static String passwordResetTokenDigest(String token) {
        return digest(PASSWORD_RESET_TOKEN_SCOPE, token);
    }

    private static String digest(String scope, String token) {
        return IdempotencyUtils.sha256Hex(scope + ":" + (token == null ? "" : token));
    }
}
