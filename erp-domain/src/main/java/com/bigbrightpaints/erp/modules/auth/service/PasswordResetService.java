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
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
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
    private static final String PUBLIC_RESET_PERSISTENCE_FAILURE_REASON_CODE = "RESET_PERSISTENCE_FAILURE";
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
    private final TransactionTemplate tokenAfterCommitCleanupTransactionTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    private record IssuedResetToken(Long id, String rawToken) {
    }

    private record PriorResetTokenSnapshot(Long userId, String tokenDigest, Instant expiresAt, Instant deliveredAt) {
    }

    private record PublicResetDispatchPlan(IssuedResetToken issuedResetToken,
                                           PriorResetTokenSnapshot priorTokenSnapshot) {
    }

    private static final class PublicResetCleanupFailureException extends RuntimeException {
        private final IssuedResetToken issuedResetToken;
        private final PriorResetTokenSnapshot priorTokenSnapshot;

        private PublicResetCleanupFailureException(RuntimeException cleanupFailure,
                                                   IssuedResetToken issuedResetToken,
                                                   PriorResetTokenSnapshot priorTokenSnapshot) {
            super(cleanupFailure);
            this.issuedResetToken = issuedResetToken;
            this.priorTokenSnapshot = priorTokenSnapshot;
        }

        private IssuedResetToken issuedResetToken() {
            return issuedResetToken;
        }

        private PriorResetTokenSnapshot priorTokenSnapshot() {
            return priorTokenSnapshot;
        }
    }

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
        this.tokenLifecycleTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tokenCleanupTransactionTemplate = new TransactionTemplate(transactionManager);
        this.tokenCleanupTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tokenAfterCommitCleanupTransactionTemplate = new TransactionTemplate(transactionManager);
        this.tokenAfterCommitCleanupTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public void requestReset(String email) {
        String correlationId = resolveCorrelationId();
        logTenantContextIgnoredIfPresent("forgot_password", correlationId);
        // GLOBAL_IDENTITY policy: one user identity spans all company memberships.
        userAccountRepository.findByEmailIgnoreCase(email)
                .filter(UserAccount::isEnabled)
                .ifPresent(user -> dispatchResetEmail(user, correlationId, true, "forgot_password"));
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
    public void requestResetByAdmin(UserAccount targetUser) {
        if (targetUser == null || !targetUser.isEnabled()) {
            return;
        }
        String correlationId = resolveCorrelationId();
        logTenantContextIgnoredIfPresent("admin_force_reset", correlationId);
        if (dispatchResetEmail(targetUser, correlationId, false, "admin_force_reset")) {
            log.info(
                    "event=password_reset.admin_force_reset.dispatched policy={} correlationId={} email={} outcome=email_dispatched",
                    RESET_POLICY_SCOPE,
                    correlationId,
                    obfuscateEmail(targetUser.getEmail()));
        }
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword, String confirmPassword) {
        logTenantContextIgnoredIfPresent("reset_password", resolveCorrelationId());
        String tokenDigest = AuthTokenDigests.passwordResetTokenDigest(tokenValue);
        PasswordResetToken token = tokenRepository.findByTokenDigest(tokenDigest)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid or expired token"));
        Instant now = Instant.now();
        if (token.isUsed() || token.isExpired(now)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid or expired token");
        }
        UserAccount user = token.getUser();
        if (!user.isEnabled()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("User account is disabled");
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
            PasswordResetToken resetToken = PasswordResetToken.digestOnly(
                    user,
                    AuthTokenDigests.passwordResetTokenDigest(token),
                    expiresAt);
            tokenRepository.saveAndFlush(resetToken);
            return token;
        });
        if (!StringUtils.hasText(tokenValue)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Failed to persist super-admin password reset token");
        }
        log.info(
                "event=password_reset.superadmin_forgot.token_issued policy={} correlationId={} email={} outcome=token_persisted",
                RESET_POLICY_SCOPE,
                correlationId,
                maskedEmail);
        return tokenValue;
    }

    private boolean dispatchResetEmail(UserAccount user,
                                       String correlationId,
                                       boolean suppressFailures,
                                       String operation) {
        if (user == null) {
            return false;
        }
        if (suppressFailures) {
            return dispatchResetEmailMaskedPublic(user, correlationId, operation);
        }
        String maskedEmail = obfuscateEmail(user.getEmail());
        IssuedResetToken issuedResetToken = null;
        try {
            ensureRequiredResetEmailDelivery();
            issuedResetToken = issueResetToken(user, correlationId, maskedEmail);
            if (issuedResetToken == null) {
                return false;
            }
            emailService.sendPasswordResetEmailRequired(user.getEmail(), user.getDisplayName(), issuedResetToken.rawToken());
            return true;
        } catch (RuntimeException ex) {
            cleanupIssuedResetToken(issuedResetToken, correlationId, maskedEmail);
            log.warn(
                    "event=password_reset.{}.failed policy={} correlationId={} email={} outcome=delivery_failed exceptionClass={}",
                    operation,
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private boolean dispatchResetEmailMaskedPublic(UserAccount user,
                                                   String correlationId,
                                                   String operation) {
        String maskedEmail = obfuscateEmail(user.getEmail());
        try {
            ensureRequiredResetEmailDelivery();
            Boolean dispatched = tokenLifecycleTransactionTemplate.execute(status -> {
                PublicResetDispatchPlan dispatchPlan = preparePublicResetDispatchWithinActiveTransaction(
                        user,
                        correlationId,
                        maskedEmail);
                if (dispatchPlan == null) {
                    return Boolean.FALSE;
                }
                registerPublicResetDispatchAfterCommit(
                        user,
                        dispatchPlan,
                        correlationId,
                        maskedEmail,
                        operation);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(dispatched);
        } catch (RuntimeException ex) {
            logMaskedPublicResetFailure(operation, correlationId, maskedEmail, ex, null, null);
            if (ex instanceof PublicResetCleanupFailureException cleanupFailureException) {
                restorePriorResetTokenAfterCleanupFailure(
                        cleanupFailureException.issuedResetToken(),
                        cleanupFailureException.priorTokenSnapshot(),
                        correlationId,
                        maskedEmail,
                        operation);
            }
            // Keep the public forgot-password response uniform even when token persistence/cleanup fails.
            return false;
        }
    }

    private PublicResetDispatchPlan preparePublicResetDispatchWithinActiveTransaction(UserAccount user,
                                                                                       String correlationId,
                                                                                       String maskedEmail) {
        IssuedResetToken issuedResetToken = issuePublicResetTokenWithinActiveTransaction(user, correlationId, maskedEmail);
        if (issuedResetToken == null) {
            return null;
        }
        touchIssuedResetTokenForDispatchOrderingWithinActiveTransaction(issuedResetToken.id(), correlationId, maskedEmail);
        PriorResetTokenSnapshot priorTokenSnapshot = cleanupSupersededPublicResetTokensWithinActiveTransaction(
                user,
                issuedResetToken.id(),
                correlationId,
                maskedEmail);
        return new PublicResetDispatchPlan(issuedResetToken, priorTokenSnapshot);
    }

    private void registerPublicResetDispatchAfterCommit(UserAccount user,
                                                        PublicResetDispatchPlan dispatchPlan,
                                                        String correlationId,
                                                        String maskedEmail,
                                                        String operation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "Password reset email dispatch requires an active transaction synchronization context");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    emailService.sendPasswordResetEmailRequired(
                            user.getEmail(),
                            user.getDisplayName(),
                            dispatchPlan.issuedResetToken().rawToken());
                } catch (RuntimeException dispatchFailure) {
                    RuntimeException cleanupFailure = restorePublicResetStateAfterDispatchFailure(
                            dispatchPlan,
                            correlationId,
                            maskedEmail,
                            operation);
                    logMaskedPublicResetFailure(
                            operation,
                            correlationId,
                            maskedEmail,
                            dispatchFailure,
                            dispatchPlan.issuedResetToken(),
                            cleanupFailure);
                    if (cleanupFailure != null) {
                        throw new PublicResetCleanupFailureException(
                                cleanupFailure,
                                dispatchPlan.issuedResetToken(),
                                dispatchPlan.priorTokenSnapshot());
                    }
                    return;
                }
                markPublicResetTokenDelivered(
                        dispatchPlan,
                        correlationId,
                        maskedEmail,
                        operation);
            }
        });
    }

    private IssuedResetToken issueResetToken(UserAccount user, String correlationId, String maskedEmail) {
        IssuedResetToken issuedResetToken = tokenLifecycleTransactionTemplate.execute(
                status -> issueResetTokenWithinActiveTransaction(user, correlationId, maskedEmail));
        if (issuedResetToken == null) {
            return null;
        }
        if (issuedResetToken == null || !StringUtils.hasText(issuedResetToken.rawToken())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Failed to persist password reset token");
        }
        return issuedResetToken;
    }

    private IssuedResetToken issueResetTokenWithinActiveTransaction(UserAccount user,
                                                                     String correlationId,
                                                                     String maskedEmail) {
        assertTokenLifecycleTransactionActive("issue", correlationId, maskedEmail);
        UserAccount lockedUser = lockUserForResetIssuance(user);
        if (lockedUser == null) {
            return null;
        }
        tokenRepository.deleteByUser(lockedUser);
        String token = generateToken();
        Instant expiresAt = Instant.now().plusSeconds(RESET_TOKEN_TTL_SECONDS);
        PasswordResetToken resetToken = PasswordResetToken.digestOnly(
                lockedUser,
                AuthTokenDigests.passwordResetTokenDigest(token),
                expiresAt);
        PasswordResetToken saved = tokenRepository.saveAndFlush(resetToken);
        return new IssuedResetToken(saved.getId(), token);
    }

    private IssuedResetToken issuePublicResetTokenWithinActiveTransaction(UserAccount user,
                                                                           String correlationId,
                                                                           String maskedEmail) {
        assertTokenLifecycleTransactionActive("issue", correlationId, maskedEmail);
        UserAccount lockedUser = lockUserForResetIssuance(user);
        if (lockedUser == null) {
            return null;
        }
        String token = generateToken();
        Instant expiresAt = Instant.now().plusSeconds(RESET_TOKEN_TTL_SECONDS);
        PasswordResetToken resetToken = PasswordResetToken.digestOnly(
                lockedUser,
                AuthTokenDigests.passwordResetTokenDigest(token),
                expiresAt);
        PasswordResetToken saved = tokenRepository.saveAndFlush(resetToken);
        return new IssuedResetToken(saved.getId(), token);
    }

    private PriorResetTokenSnapshot cleanupSupersededPublicResetTokensWithinActiveTransaction(UserAccount user,
                                                                                                Long keepTokenId,
                                                                                                String correlationId,
                                                                                                String maskedEmail) {
        assertTokenLifecycleTransactionActive("stabilize", correlationId, maskedEmail);
        if (user == null || keepTokenId == null) {
            return null;
        }
        UserAccount lockedUser = lockUserForResetTokenCleanup(user);
        PriorResetTokenSnapshot priorTokenSnapshot = capturePriorResetTokenSnapshot(lockedUser, keepTokenId);
        tokenRepository.deleteByUserAndIdNot(lockedUser, keepTokenId);
        return priorTokenSnapshot;
    }

    private PriorResetTokenSnapshot capturePriorResetTokenSnapshot(UserAccount user, Long keepTokenId) {
        if (user == null || keepTokenId == null) {
            return null;
        }
        PasswordResetToken priorToken = tokenRepository
                .findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(user, keepTokenId)
                .orElse(null);
        if (priorToken == null || priorToken.isUsed() || priorToken.isExpired(Instant.now())) {
            return null;
        }
        if (priorToken.getDeliveredAt() == null) {
            return null;
        }
        if (!StringUtils.hasText(priorToken.getTokenDigest())) {
            return null;
        }
        if (user.getId() == null) {
            return null;
        }
        return new PriorResetTokenSnapshot(
                user.getId(),
                priorToken.getTokenDigest(),
                priorToken.getExpiresAt(),
                priorToken.getDeliveredAt());
    }

    private void touchIssuedResetTokenForDispatchOrderingWithinActiveTransaction(Long tokenId,
                                                                                  String correlationId,
                                                                                  String maskedEmail) {
        assertTokenLifecycleTransactionActive("dispatch_ordering", correlationId, maskedEmail);
        if (tokenId == null) {
            return;
        }
        tokenRepository.touchCreatedAt(tokenId, Instant.now());
    }

    private void markPublicResetTokenDelivered(PublicResetDispatchPlan dispatchPlan,
                                               String correlationId,
                                               String maskedEmail,
                                               String operation) {
        IssuedResetToken issuedResetToken = dispatchPlan != null ? dispatchPlan.issuedResetToken() : null;
        if (issuedResetToken == null || issuedResetToken.id() == null) {
            return;
        }
        RuntimeException markerFailure;
        try {
            Boolean marked = tokenAfterCommitCleanupTransactionTemplate.execute(status -> {
                assertTokenLifecycleTransactionActive("mark_delivered", correlationId, maskedEmail);
                return tokenRepository.markDeliveredAt(issuedResetToken.id(), Instant.now()) > 0;
            });
            if (Boolean.TRUE.equals(marked)) {
                return;
            }
            markerFailure = com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
                    "Password reset delivery marker missing after dispatch");
        } catch (RuntimeException deliveryMarkerEx) {
            markerFailure = deliveryMarkerEx;
        }

        RuntimeException cleanupFailure = restorePublicResetStateAfterDispatchFailure(
                dispatchPlan,
                correlationId,
                maskedEmail,
                operation);
        logMaskedPublicResetFailure(
                operation,
                correlationId,
                maskedEmail,
                markerFailure,
                issuedResetToken,
                cleanupFailure);
    }

    private UserAccount lockUserForResetIssuance(UserAccount user) {
        if (user == null || !user.isEnabled()) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userAccountRepository.lockById(user.getId())
                .filter(UserAccount::isEnabled)
                .orElse(null);
    }

    private UserAccount lockUserForResetTokenCleanup(UserAccount user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null) {
            return user;
        }
        return userAccountRepository.lockById(user.getId()).orElse(user);
    }

    private UserAccount lockUserForResetTokenCleanup(Long userId) {
        if (userId == null) {
            return null;
        }
        return userAccountRepository.lockById(userId).orElse(null);
    }

    private void cleanupIssuedResetToken(IssuedResetToken issuedResetToken,
                                         String correlationId,
                                         String maskedEmail) {
        if (issuedResetToken == null || !StringUtils.hasText(issuedResetToken.rawToken())) {
            return;
        }
        tokenCleanupTransactionTemplate.executeWithoutResult(status -> {
            assertTokenLifecycleTransactionActive("cleanup_failed_issue", correlationId, maskedEmail);
            deletePersistedResetToken(issuedResetToken.rawToken());
        });
    }

    private RuntimeException restorePublicResetStateAfterDispatchFailure(PublicResetDispatchPlan dispatchPlan,
                                                                         String correlationId,
                                                                         String maskedEmail,
                                                                         String operation) {
        if (dispatchPlan == null) {
            return null;
        }
        try {
            tokenAfterCommitCleanupTransactionTemplate.executeWithoutResult(status -> {
                assertTokenLifecycleTransactionActive("cleanup_failed_dispatch", correlationId, maskedEmail);
                UserAccount lockedUser = lockUserForResetTokenCleanup(
                        dispatchPlan.priorTokenSnapshot() != null ? dispatchPlan.priorTokenSnapshot().userId() : null);
                int deletedIssuedTokenCount = deletePersistedResetToken(dispatchPlan.issuedResetToken().rawToken());
                if (deletedIssuedTokenCount > 0) {
                    restorePriorResetTokenWithinActiveTransaction(lockedUser, dispatchPlan.priorTokenSnapshot());
                }
            });
            return null;
        } catch (RuntimeException cleanupEx) {
            log.error(
                    "event=password_reset.{}.masked policy={} correlationId={} email={} outcome=cleanup_failed reasonCode={} exceptionClass={}",
                    operation,
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    PUBLIC_RESET_PERSISTENCE_FAILURE_REASON_CODE,
                    cleanupEx.getClass().getSimpleName());
            return cleanupEx;
        }
    }

    private boolean restorePriorResetTokenAfterCleanupFailure(IssuedResetToken issuedResetToken,
                                                              PriorResetTokenSnapshot priorTokenSnapshot,
                                                              String correlationId,
                                                              String maskedEmail,
                                                              String operation) {
        if (issuedResetToken == null || !StringUtils.hasText(issuedResetToken.rawToken()) || priorTokenSnapshot == null) {
            return false;
        }
        try {
            Boolean restored = tokenCleanupTransactionTemplate.execute(status -> {
                assertTokenLifecycleTransactionActive("restore_prior_after_cleanup_failure", correlationId, maskedEmail);
                UserAccount lockedUser = lockUserForResetTokenCleanup(priorTokenSnapshot.userId());
                int deletedIssuedTokenCount = deletePersistedResetToken(issuedResetToken.rawToken());
                if (deletedIssuedTokenCount <= 0) {
                    return Boolean.FALSE;
                }
                restorePriorResetTokenWithinActiveTransaction(lockedUser, priorTokenSnapshot);
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(restored);
        } catch (RuntimeException restoreEx) {
            log.error(
                    "event=password_reset.{}.masked policy={} correlationId={} email={} outcome=prior_restore_failed reasonCode={} exceptionClass={}",
                    operation,
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    PUBLIC_RESET_PERSISTENCE_FAILURE_REASON_CODE,
                    restoreEx.getClass().getSimpleName());
            return false;
        }
    }

    private void restorePriorResetTokenWithinActiveTransaction(PriorResetTokenSnapshot priorTokenSnapshot) {
        UserAccount lockedUser = lockUserForResetTokenCleanup(
                priorTokenSnapshot != null ? priorTokenSnapshot.userId() : null);
        restorePriorResetTokenWithinActiveTransaction(lockedUser, priorTokenSnapshot);
    }

    private void restorePriorResetTokenWithinActiveTransaction(UserAccount lockedUser,
                                                               PriorResetTokenSnapshot priorTokenSnapshot) {
        if (priorTokenSnapshot == null
                || priorTokenSnapshot.userId() == null
                || !StringUtils.hasText(priorTokenSnapshot.tokenDigest())
                || priorTokenSnapshot.expiresAt() == null
                || priorTokenSnapshot.deliveredAt() == null
                || !priorTokenSnapshot.expiresAt().isAfter(Instant.now())) {
            return;
        }
        if (lockedUser == null || !lockedUser.isEnabled()) {
            return;
        }
        PasswordResetToken restoredToken = PasswordResetToken.digestOnly(
                lockedUser,
                priorTokenSnapshot.tokenDigest(),
                priorTokenSnapshot.expiresAt());
        restoredToken.markDelivered(priorTokenSnapshot.deliveredAt());
        tokenRepository.saveAndFlush(restoredToken);
    }

    private void logMaskedPublicResetFailure(String operation,
                                             String correlationId,
                                             String maskedEmail,
                                             RuntimeException dispatchFailure,
                                             IssuedResetToken issuedResetToken,
                                             RuntimeException cleanupFailure) {
        RuntimeException effectiveFailure = cleanupFailure != null ? cleanupFailure : dispatchFailure;
        if (isPublicResetPersistenceFailure(dispatchFailure) || cleanupFailure != null) {
            log.error(
                    "event=password_reset.{}.masked policy={} correlationId={} email={} outcome=suppressed_failure reasonCode={} exceptionClass={}",
                    operation,
                    RESET_POLICY_SCOPE,
                    correlationId,
                    maskedEmail,
                    PUBLIC_RESET_PERSISTENCE_FAILURE_REASON_CODE,
                    effectiveFailure.getClass().getSimpleName());
            return;
        }
        log.warn(
                "event=password_reset.{}.masked policy={} correlationId={} email={} outcome=suppressed_failure exceptionClass={}",
                operation,
                RESET_POLICY_SCOPE,
                correlationId,
                maskedEmail,
                effectiveFailure.getClass().getSimpleName());
    }

    private boolean isPublicResetPersistenceFailure(RuntimeException exception) {
        Throwable cursor = exception;
        while (cursor != null) {
            if (cursor instanceof PublicResetCleanupFailureException) {
                return true;
            }
            if (cursor instanceof ApplicationException appException
                    && appException.getErrorCode() != ErrorCode.SYSTEM_CONFIGURATION_ERROR) {
                return appException.getErrorCode() == ErrorCode.SYSTEM_DATABASE_ERROR;
            }
            if (cursor instanceof DataAccessException || cursor instanceof TransactionException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void cleanupFailedSuperAdminResetToken(UserAccount user, String tokenValue, String correlationId) {
        if (!StringUtils.hasText(tokenValue)) {
            return;
        }
        String maskedEmail = obfuscateEmail(user != null ? user.getEmail() : null);
        try {
            tokenCleanupTransactionTemplate.executeWithoutResult(status -> {
                assertTokenLifecycleTransactionActive("cleanup", correlationId, maskedEmail);
                deletePersistedResetToken(tokenValue);
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
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Password reset token lifecycle operation requires an active transaction");
    }

    private int deletePersistedResetToken(String tokenValue) {
        String tokenDigest = AuthTokenDigests.passwordResetTokenDigest(tokenValue);
        return tokenRepository.deleteByTokenDigest(tokenDigest);
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
