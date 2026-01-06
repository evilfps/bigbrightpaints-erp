package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class PasswordResetService {

    private static final long RESET_TOKEN_TTL_SECONDS = 3600; // 1 hour

    private final UserAccountRepository userAccountRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserAccountRepository userAccountRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordService passwordService,
                                EmailService emailService,
                                TokenBlacklistService tokenBlacklistService,
                                RefreshTokenService refreshTokenService) {
        this.userAccountRepository = userAccountRepository;
        this.tokenRepository = tokenRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public void requestReset(String email) {
        userAccountRepository.findByEmailIgnoreCase(email)
                .filter(UserAccount::isEnabled)
                .ifPresent(user -> {
                    tokenRepository.deleteByUser(user);
                    String token = generateToken();
                    Instant now = Instant.now();
                    Instant expiresAt = now.plusSeconds(RESET_TOKEN_TTL_SECONDS);
                    PasswordResetToken resetToken = new PasswordResetToken(user, token, expiresAt);
                    tokenRepository.save(resetToken);
                    emailService.sendPasswordResetEmail(user.getEmail(), user.getDisplayName(), token);
                });
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword, String confirmPassword) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired token"));
        Instant now = Instant.now();
        if (token.isUsed() || token.isExpired(now)) {
            throw new IllegalArgumentException("Invalid or expired token");
        }
        UserAccount user = token.getUser();
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("User account is disabled");
        }
        passwordService.resetPassword(user, newPassword, confirmPassword);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userAccountRepository.save(user);
        tokenBlacklistService.revokeAllUserTokens(user.getEmail());
        refreshTokenService.revokeAllForUser(user.getEmail());
        token.markUsed();
        tokenRepository.save(token);
        tokenRepository.deleteByUser(user);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
