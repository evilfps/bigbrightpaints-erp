package com.bigbrightpaints.erp.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuthSecretStorageBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretStorageBackfillRunner.class);

    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;

    public AuthSecretStorageBackfillRunner(RefreshTokenService refreshTokenService,
                                           PasswordResetService passwordResetService) {
        this.refreshTokenService = refreshTokenService;
        this.passwordResetService = passwordResetService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int migratedRefreshTokens = refreshTokenService.backfillLegacyTokens();
        int migratedPasswordResetTokens = passwordResetService.backfillLegacyTokens();
        if (migratedRefreshTokens + migratedPasswordResetTokens == 0) {
            return;
        }
        log.info(
                "Backfilled {} refresh tokens and {} password-reset tokens to digest-only storage",
                migratedRefreshTokens,
                migratedPasswordResetTokens);
    }
}
