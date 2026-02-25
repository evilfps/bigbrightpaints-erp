package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long RESET_TOKEN_TTL_SECONDS = 3600; // 1 hour
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

    private final UserAccountRepository userAccountRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserAccountRepository userAccountRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordService passwordService,
                                EmailService emailService,
                                EmailProperties emailProperties,
                                TokenBlacklistService tokenBlacklistService,
                                RefreshTokenService refreshTokenService) {
        this.userAccountRepository = userAccountRepository;
        this.tokenRepository = tokenRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.emailProperties = emailProperties;
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

    public void requestResetForSuperAdmin(String email) {
        userAccountRepository.findByEmailIgnoreCase(email)
                .filter(UserAccount::isEnabled)
                .filter(this::hasSuperAdminRole)
                .ifPresent(this::dispatchSuperAdminResetEmail);
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

    private boolean hasSuperAdminRole(UserAccount user) {
        if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
            return false;
        }
        return user.getRoles().stream()
                .anyMatch(role -> role != null && SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName()));
    }

    private void ensureRequiredResetEmailDelivery() {
        if (!emailProperties.isEnabled() || !emailProperties.isSendPasswordReset()) {
            throw new ApplicationException(
                    ErrorCode.SYSTEM_CONFIGURATION_ERROR,
                    "Password reset email delivery is disabled; enable erp.mail.enabled=true and erp.mail.send-password-reset=true");
        }
    }

    private void dispatchSuperAdminResetEmail(UserAccount user) {
        boolean tokenPersisted = false;
        String persistedToken = null;
        try {
            ensureRequiredResetEmailDelivery();
            String token = generateToken();
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(RESET_TOKEN_TTL_SECONDS);
            tokenRepository.deleteByUser(user);
            PasswordResetToken resetToken = new PasswordResetToken(user, token, expiresAt);
            tokenRepository.saveAndFlush(resetToken);
            tokenPersisted = true;
            persistedToken = token;
            String resetLink = emailProperties.getBaseUrl() + "/reset-password?token=" + token;
            String displayName = StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName().trim() : "User";
            String body = "Hello " + displayName
                    + ",\n\nUse this secure link to reset your BigBright ERP password:\n"
                    + resetLink
                    + "\n\nThis link expires in 60 minutes.";
            emailService.sendSimpleEmail(user.getEmail(), "Reset your BigBright ERP password", body);
        } catch (RuntimeException ex) {
            if (tokenPersisted) {
                cleanupFailedSuperAdminResetToken(user, persistedToken);
            }
            // Keep public endpoint semantics uniform to avoid account-enumeration side channels.
            log.warn("Super-admin forgot-password delivery suppressed for {}: {}",
                    obfuscateEmail(user != null ? user.getEmail() : null),
                    ex.getMessage());
        }
    }

    private void cleanupFailedSuperAdminResetToken(UserAccount user, String tokenValue) {
        if (!StringUtils.hasText(tokenValue)) {
            return;
        }
        try {
            tokenRepository.deleteByToken(tokenValue);
        } catch (DataAccessException cleanupEx) {
            log.warn("Super-admin forgot-password cleanup failed for {}: {}",
                    obfuscateEmail(user != null ? user.getEmail() : null),
                    cleanupEx.getMessage());
        }
    }

    private String obfuscateEmail(String email) {
        if (!StringUtils.hasText(email)) {
            return "<empty>";
        }
        String normalized = email.trim();
        int atIndex = normalized.indexOf('@');
        if (atIndex <= 1) {
            return "***";
        }
        return normalized.charAt(0) + "***" + normalized.substring(atIndex);
    }
}
