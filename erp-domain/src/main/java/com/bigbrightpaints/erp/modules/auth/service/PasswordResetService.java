package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final long RESET_TOKEN_TTL_SECONDS = 3600; // 1 hour
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";
    private static final String RESET_POLICY_SCOPE = "GLOBAL_IDENTITY";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String COMPANY_CODE_HEADER = "X-Company-Code";
    private static final String LEGACY_COMPANY_ID_HEADER = "X-Company-Id";
    private static final int MAX_CORRELATION_ID_LENGTH = 128;
    private static final Pattern SAFE_CORRELATION_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final String SUPER_ADMIN_MASKED_OUTCOME = "request_accepted";
    private static final String SUPER_ADMIN_DISPATCH_FAILURE_REASON_CODE = "RESET_DISPATCH_FAILURE";
    private static final String SUPER_ADMIN_DISPATCH_FAILURE_CLASS_CONFIGURATION = "CONFIGURATION";
    private static final String SUPER_ADMIN_DISPATCH_FAILURE_CLASS_TOKEN_PERSISTENCE = "TOKEN_PERSISTENCE";
    private static final String SUPER_ADMIN_DISPATCH_FAILURE_CLASS_EMAIL_DISPATCH = "EMAIL_DISPATCH";
    private static final String SUPER_ADMIN_CLEANUP_FAILURE_REASON_CODE = "RESET_CLEANUP_FAILURE";
    private static final String SUPER_ADMIN_CLEANUP_FAILURE_CLASS_TOKEN_CLEANUP = "TOKEN_CLEANUP";
    private static final String SUPER_ADMIN_CLEANUP_FAILURE_SECURITY_EVENT_CODE =
            "SEC_AUTH_SUPERADMIN_RESET_CLEANUP_FAILURE";
    private static final int MAX_TENANT_CONTEXT_LENGTH = 64;
    private static final Pattern SAFE_TENANT_CONTEXT_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    private final UserAccountRepository userAccountRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final TokenBlacklistService tokenBlacklistService;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate tokenLifecycleTransactionTemplate;
    private final TransactionTemplate tokenCleanupTransactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetService(UserAccountRepository userAccountRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordService passwordService,
                                EmailService emailService,
                                EmailProperties emailProperties,
                                TokenBlacklistService tokenBlacklistService,
                                RefreshTokenService refreshTokenService,
                                PlatformTransactionManager transactionManager) {
        this.userAccountRepository = userAccountRepository;
        this.tokenRepository = tokenRepository;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.emailProperties = emailProperties;
        this.tokenBlacklistService = tokenBlacklistService;
        this.refreshTokenService = refreshTokenService;
        this.tokenLifecycleTransactionTemplate = new TransactionTemplate(transactionManager);
        this.tokenCleanupTransactionTemplate = new TransactionTemplate(transactionManager);
        this.tokenCleanupTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public void requestReset(String email) {
        logTenantContextIgnoredIfPresent("forgot_password", resolveCorrelationId());
        // GLOBAL_IDENTITY policy: one user identity spans all company memberships.
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
        String correlationId = resolveCorrelationId();
        logTenantContextIgnoredIfPresent("forgot_password_superadmin", correlationId);
        String requestedEmail = obfuscateEmail(email);
        userAccountRepository.findByEmailIgnoreCase(email)
                .filter(UserAccount::isEnabled)
                .filter(this::hasSuperAdminRole)
                .ifPresentOrElse(
                        user -> dispatchSuperAdminResetEmail(user, correlationId),
                        () -> log.info(
                                "event=password_reset.superadmin_forgot.suppressed policy={} correlationId={} email={} outcome={} reasonCode=masked_eligibility",
                                RESET_POLICY_SCOPE,
                                correlationId,
                                requestedEmail,
                                SUPER_ADMIN_MASKED_OUTCOME));
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword, String confirmPassword) {
        logTenantContextIgnoredIfPresent("reset_password", resolveCorrelationId());
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

    private void dispatchSuperAdminResetEmail(UserAccount user, String correlationId) {
        String persistedToken = null;
        String email = user != null ? user.getEmail() : null;
        String maskedEmail = obfuscateEmail(email);
        try {
            ensureRequiredResetEmailDelivery();
            persistedToken = issueSuperAdminResetToken(user, correlationId, maskedEmail);
            String resetLink = emailProperties.getBaseUrl() + "/reset-password?token=" + persistedToken;
            String displayName = StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName().trim() : "User";
            String body = "Hello " + displayName
                    + ",\n\nUse this secure link to reset your BigBright ERP password:\n"
                    + resetLink
                    + "\n\nThis link expires in 60 minutes.";
            emailService.sendSimpleEmail(email, "Reset your BigBright ERP password", body);
            log.info(
                    "event=password_reset.superadmin_forgot.dispatched policy={} correlationId={} email={} outcome=email_dispatched",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail);
        } catch (RuntimeException ex) {
            cleanupFailedSuperAdminResetToken(user, persistedToken, correlationId);
            // Keep public endpoint semantics uniform to avoid account-enumeration side channels.
            String failureClass = classifySuperAdminDispatchFailure(ex, persistedToken);
            log.warn(
                    "event=password_reset.superadmin_forgot.masked policy={} correlationId={} email={} outcome=suppressed_failure reasonCode={} failureClass={} exceptionClass={}",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    SUPER_ADMIN_DISPATCH_FAILURE_REASON_CODE,
                    failureClass,
                    ex.getClass().getSimpleName());
        }
    }

    private String issueSuperAdminResetToken(UserAccount user, String correlationId, String maskedEmail) {
        String tokenValue = tokenLifecycleTransactionTemplate.execute(status -> {
            assertTokenLifecycleTransactionActive("issue", correlationId, maskedEmail);
            tokenRepository.deleteByUser(user);
            String token = generateToken();
            Instant expiresAt = Instant.now().plusSeconds(RESET_TOKEN_TTL_SECONDS);
            PasswordResetToken resetToken = new PasswordResetToken(user, token, expiresAt);
            tokenRepository.saveAndFlush(resetToken);
            return token;
        });
        if (!StringUtils.hasText(tokenValue)) {
            throw new IllegalStateException("Failed to persist super-admin password reset token");
        }
        log.info(
                "event=password_reset.superadmin_forgot.token_issued policy={} correlationId={} email={} outcome=token_persisted",
                RESET_POLICY_SCOPE,
                correlationId,
                maskedEmail);
        return tokenValue;
    }

    private void cleanupFailedSuperAdminResetToken(UserAccount user, String tokenValue, String correlationId) {
        if (!StringUtils.hasText(tokenValue)) {
            return;
        }
        String maskedEmail = obfuscateEmail(user != null ? user.getEmail() : null);
        try {
            tokenCleanupTransactionTemplate.executeWithoutResult(status -> {
                assertTokenLifecycleTransactionActive("cleanup", correlationId, maskedEmail);
                tokenRepository.deleteByToken(tokenValue);
            });
            log.info(
                    "event=password_reset.superadmin_forgot.cleanup policy={} correlationId={} email={} outcome=token_removed",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail);
        } catch (RuntimeException cleanupEx) {
            log.warn(
                    "event=password_reset.superadmin_forgot.cleanup policy={} correlationId={} email={} outcome=cleanup_failed reasonCode={} failureClass={} exceptionClass={}",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    SUPER_ADMIN_CLEANUP_FAILURE_REASON_CODE,
                    SUPER_ADMIN_CLEANUP_FAILURE_CLASS_TOKEN_CLEANUP,
                    cleanupEx.getClass().getSimpleName());
            log.error(
                    "event=password_reset.superadmin_forgot.security_alert policy={} correlationId={} email={} securityEventCode={} outcome=manual_remediation_required",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    SUPER_ADMIN_CLEANUP_FAILURE_SECURITY_EVENT_CODE);
        }
    }

    private void assertTokenLifecycleTransactionActive(String stage, String correlationId, String maskedEmail) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        log.error(
                "event=password_reset.superadmin_forgot.transaction policy={} correlationId={} email={} stage={} outcome=missing_transaction",
                RESET_POLICY_SCOPE,
                correlationId,
                maskedEmail,
                stage);
        throw new IllegalStateException("Password reset token lifecycle operation requires an active transaction");
    }

    private String classifySuperAdminDispatchFailure(RuntimeException exception, String persistedToken) {
        if (exception instanceof ApplicationException appException
                && appException.getErrorCode() == ErrorCode.SYSTEM_CONFIGURATION_ERROR) {
            return SUPER_ADMIN_DISPATCH_FAILURE_CLASS_CONFIGURATION;
        }
        if (!StringUtils.hasText(persistedToken)) {
            return SUPER_ADMIN_DISPATCH_FAILURE_CLASS_TOKEN_PERSISTENCE;
        }
        return SUPER_ADMIN_DISPATCH_FAILURE_CLASS_EMAIL_DISPATCH;
    }

    private void logTenantContextIgnoredIfPresent(String operation, String correlationId) {
        String tenantContext = resolveTenantContextForObservability();
        if (!StringUtils.hasText(tenantContext)) {
            return;
        }
        log.info(
                "event=password_reset.scope policy={} operation={} correlationId={} tenantContext={} outcome=tenant_context_ignored",
                RESET_POLICY_SCOPE,
                operation,
                correlationId,
                sanitizeTenantContextForLog(tenantContext));
    }

    private String resolveTenantContextForObservability() {
        String tenantContext = CompanyContextHolder.getCompanyCode();
        if (StringUtils.hasText(tenantContext)) {
            return tenantContext;
        }
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        if (request == null) {
            return null;
        }
        return firstNonBlank(
                request.getHeader(COMPANY_CODE_HEADER),
                request.getHeader(LEGACY_COMPANY_ID_HEADER));
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

    private String resolveCorrelationId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return UUID.randomUUID().toString();
        }
        HttpServletRequest request = attributes.getRequest();
        String headerCorrelationId = firstNonBlank(
                request.getHeader(CORRELATION_ID_HEADER),
                request.getHeader(REQUEST_ID_HEADER),
                request.getHeader(TRACE_ID_HEADER));
        String sanitizedCorrelationId = sanitizeCorrelationId(headerCorrelationId);
        if (sanitizedCorrelationId != null) {
            return sanitizedCorrelationId;
        }
        return UUID.randomUUID().toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String sanitizeCorrelationId(String correlationId) {
        if (!StringUtils.hasText(correlationId)) {
            return null;
        }
        String candidate = correlationId.trim();
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        if (candidate.length() > MAX_CORRELATION_ID_LENGTH) {
            return null;
        }
        if (candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            return null;
        }
        if (!SAFE_CORRELATION_ID_PATTERN.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }

    private String sanitizeTenantContextForLog(String tenantContext) {
        if (!StringUtils.hasText(tenantContext)) {
            return "<empty>";
        }
        String candidate = tenantContext.trim();
        if (!StringUtils.hasText(candidate)) {
            return "<empty>";
        }
        if (candidate.length() > MAX_TENANT_CONTEXT_LENGTH) {
            return "<redacted>";
        }
        if (candidate.indexOf('\r') >= 0 || candidate.indexOf('\n') >= 0) {
            return "<redacted>";
        }
        if (!SAFE_TENANT_CONTEXT_PATTERN.matcher(candidate).matches()) {
            return "<redacted>";
        }
        return candidate;
    }
}
