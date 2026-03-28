package com.bigbrightpaints.erp.modules.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.security.SecurityMonitoringService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class PasswordResetService {

  private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
  // 1 hour
  private static final long RESET_TOKEN_TTL_SECONDS = 3600;
  private static final String RESET_POLICY_SCOPE = "SCOPED_ACCOUNT";
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String TRACE_ID_HEADER = "X-Trace-Id";
  private static final String COMPANY_CODE_HEADER = "X-Company-Code";
  private static final int MAX_CORRELATION_ID_LENGTH = 128;
  private static final Pattern SAFE_CORRELATION_ID_PATTERN =
      Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
  private static final int MAX_TENANT_CONTEXT_LENGTH = 64;
  private static final Pattern SAFE_TENANT_CONTEXT_PATTERN =
      Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
  private static final int MAX_LOG_VALUE_LENGTH = 160;
  private static final String RATE_LIMIT_PREFIX = "password-reset";

  private final UserAccountRepository userAccountRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final PasswordService passwordService;
  private final EmailService emailService;
  private final EmailProperties emailProperties;
  private final AuditService auditService;
  private final SecurityMonitoringService securityMonitoringService;
  private final TokenBlacklistService tokenBlacklistService;
  private final RefreshTokenService refreshTokenService;
  private final AuthScopeService authScopeService;
  private final TransactionTemplate tokenLifecycleTransactionTemplate;
  private final TransactionTemplate tokenCleanupTransactionTemplate;
  private final SecureRandom secureRandom = new SecureRandom();

  private record IssuedResetToken(Long id, String rawToken) {}

  public PasswordResetService(
      UserAccountRepository userAccountRepository,
      PasswordResetTokenRepository tokenRepository,
      PasswordService passwordService,
      EmailService emailService,
      EmailProperties emailProperties,
      AuditService auditService,
      SecurityMonitoringService securityMonitoringService,
      TokenBlacklistService tokenBlacklistService,
      RefreshTokenService refreshTokenService,
      AuthScopeService authScopeService,
      PlatformTransactionManager transactionManager) {
    this.userAccountRepository = userAccountRepository;
    this.tokenRepository = tokenRepository;
    this.passwordService = passwordService;
    this.emailService = emailService;
    this.emailProperties = emailProperties;
    this.auditService = auditService;
    this.securityMonitoringService = securityMonitoringService;
    this.tokenBlacklistService = tokenBlacklistService;
    this.refreshTokenService = refreshTokenService;
    this.authScopeService = authScopeService;
    this.tokenLifecycleTransactionTemplate = new TransactionTemplate(transactionManager);
    this.tokenLifecycleTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tokenCleanupTransactionTemplate = new TransactionTemplate(transactionManager);
    this.tokenCleanupTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public boolean isResetEmailDeliveryEnabled() {
    return emailProperties.isEnabled() && emailProperties.isSendPasswordReset();
  }

  @Transactional
  public void requestReset(String email, String companyCode) {
    String correlationId = resolveCorrelationId();
    logTenantContextIgnoredIfPresent("forgot_password", correlationId);
    String normalizedEmail = normalizeEmail(email);
    String scopeCode = authScopeService.requireScopeCode(companyCode);
    enforceResetRateLimit("forgot_password", normalizedEmail, scopeCode, correlationId);
    UserAccount user =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizedEmail, scopeCode)
            .filter(UserAccount::isEnabled)
            .orElse(null);
    if (user != null) {
      dispatchResetEmailMaskedPublic(user, correlationId, scopeCode);
    }
  }

  @Transactional
  public void requestResetByAdmin(UserAccount targetUser) {
    if (targetUser == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Target user is required");
    }
    String correlationId = resolveCorrelationId();
    logTenantContextIgnoredIfPresent("admin_force_reset", correlationId);
    String scopeCode = authScopeService.requireScopeCode(targetUser.getAuthScopeCode());
    if (!targetUser.isEnabled()) {
      auditResetFailure(
          "admin_force_reset", correlationId, scopeCode, targetUser.getEmail(), "user_disabled");
      throw new ApplicationException(
          ErrorCode.AUTH_ACCOUNT_DISABLED, ErrorCode.AUTH_ACCOUNT_DISABLED.getDefaultMessage());
    }
    String normalizedEmail = normalizeEmail(targetUser.getEmail());
    enforceResetRateLimit("admin_force_reset", normalizedEmail, scopeCode, correlationId);
    if (dispatchResetEmail(targetUser, correlationId, "admin_force_reset")) {
      String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
      String safeMaskedEmail = sanitizeForPlainTextLog(obfuscateEmail(targetUser.getEmail()));
      revokeActiveSessions(targetUser);
      auditResetRequested("admin_force_reset", targetUser, scopeCode, correlationId);
      log.info(
          "event=password_reset.admin_force_reset.dispatched policy={} correlationId={} email={}"
              + " outcome=email_dispatched",
          RESET_POLICY_SCOPE,
          safeCorrelationId,
          safeMaskedEmail);
    }
  }

  @Transactional
  public void resetPassword(String tokenValue, String newPassword, String confirmPassword) {
    String correlationId = resolveCorrelationId();
    logTenantContextIgnoredIfPresent("reset_password", correlationId);
    String tokenDigest = AuthTokenDigests.passwordResetTokenDigest(tokenValue);
    PasswordResetToken token =
        tokenRepository
            .findByTokenDigest(tokenDigest)
            .orElseThrow(() -> invalidOrExpiredResetToken(correlationId));
    Instant now = Instant.now();
    if (token.isUsed() || token.isExpired(now)) {
      throw invalidOrExpiredResetToken(correlationId);
    }
    UserAccount user = token.getUser();
    if (!user.isEnabled()) {
      auditResetFailure(
          "reset_password",
          correlationId,
          authScopeService.requireScopeCode(user.getAuthScopeCode()),
          user.getEmail(),
          "user_disabled");
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "User account is disabled");
    }
    String scopeCode = authScopeService.requireScopeCode(user.getAuthScopeCode());
    enforceResetRateLimit(
        "reset_password", normalizeEmail(user.getEmail()), scopeCode, correlationId);
    passwordService.resetPassword(user, newPassword, confirmPassword);
    user.setFailedLoginAttempts(0);
    user.setLockedUntil(null);
    userAccountRepository.save(user);
    revokeActiveSessions(user);
    token.markUsed();
    tokenRepository.save(token);
    tokenRepository.deleteByUser(user);
    auditResetCompleted(user, scopeCode, correlationId);
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String normalizeEmail(String email) {
    if (email == null) {
      return null;
    }
    return email.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private void ensureRequiredResetEmailDelivery() {
    if (!isResetEmailDeliveryEnabled()) {
      throw new ApplicationException(
          ErrorCode.SYSTEM_CONFIGURATION_ERROR,
          "Password reset email delivery is disabled; enable erp.mail.enabled=true and"
              + " erp.mail.send-password-reset=true");
    }
  }

  private boolean dispatchResetEmail(UserAccount user, String correlationId, String operation) {
    if (user == null) {
      return false;
    }
    String maskedEmail = sanitizeForPlainTextLog(obfuscateEmail(user.getEmail()));
    IssuedResetToken issuedResetToken = null;
    try {
      ensureRequiredResetEmailDelivery();
      issuedResetToken = issueResetToken(user, correlationId, maskedEmail);
      if (issuedResetToken == null) {
        return false;
      }
      emailService.sendPasswordResetEmailRequired(
          user.getEmail(),
          user.getDisplayName(),
          issuedResetToken.rawToken(),
          user.getAuthScopeCode());
    } catch (RuntimeException ex) {
      cleanupIssuedResetToken(issuedResetToken, correlationId, maskedEmail);
      String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
      String safeExceptionClass = sanitizeExceptionClass(ex);
      auditResetFailure(
          operation, correlationId, user.getAuthScopeCode(), user.getEmail(), "delivery_failed");
      log.warn(
          "event=password_reset.{}.failed policy={} correlationId={} email={}"
              + " outcome=delivery_failed exceptionClass={}",
          operation,
          RESET_POLICY_SCOPE,
          safeCorrelationId,
          maskedEmail,
          safeExceptionClass);
      throw ex;
    }
    try {
      markIssuedResetTokenDelivered(issuedResetToken, correlationId, maskedEmail);
    } catch (RuntimeException ex) {
      String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
      String safeExceptionClass = sanitizeExceptionClass(ex);
      log.warn(
          "event=password_reset.{}.delivery_tracking_failed policy={} correlationId={} email={}"
              + " outcome=delivery_bookkeeping_failed exceptionClass={}",
          operation,
          RESET_POLICY_SCOPE,
          safeCorrelationId,
          maskedEmail,
          safeExceptionClass);
    }
    return true;
  }

  private void revokeActiveSessions(UserAccount user) {
    tokenBlacklistService.revokeAllUserTokens(user.getPublicId().toString());
    refreshTokenService.revokeAllForUser(user.getPublicId());
  }

  private IssuedResetToken issueResetToken(
      UserAccount user, String correlationId, String maskedEmail) {
    IssuedResetToken issuedResetToken =
        tokenLifecycleTransactionTemplate.execute(
            status -> issueResetTokenWithinActiveTransaction(user, correlationId, maskedEmail));
    if (issuedResetToken == null) {
      return null;
    }
    if (issuedResetToken == null || !StringUtils.hasText(issuedResetToken.rawToken())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Failed to persist password reset token");
    }
    return issuedResetToken;
  }

  private IssuedResetToken issueResetTokenWithinActiveTransaction(
      UserAccount user, String correlationId, String maskedEmail) {
    assertTokenLifecycleTransactionActive("issue", correlationId, maskedEmail);
    UserAccount lockedUser = lockUserForResetIssuance(user);
    if (lockedUser == null) {
      return null;
    }
    tokenRepository.deleteByUser(lockedUser);
    String token = generateToken();
    Instant expiresAt = Instant.now().plusSeconds(RESET_TOKEN_TTL_SECONDS);
    PasswordResetToken resetToken =
        PasswordResetToken.digestOnly(
            lockedUser, AuthTokenDigests.passwordResetTokenDigest(token), expiresAt);
    PasswordResetToken saved = tokenRepository.saveAndFlush(resetToken);
    return new IssuedResetToken(saved.getId(), token);
  }

  private UserAccount lockUserForResetIssuance(UserAccount user) {
    if (user == null || !user.isEnabled()) {
      return null;
    }
    if (user.getId() == null) {
      return user;
    }
    return userAccountRepository.lockById(user.getId()).filter(UserAccount::isEnabled).orElse(null);
  }

  private void cleanupIssuedResetToken(
      IssuedResetToken issuedResetToken, String correlationId, String maskedEmail) {
    if (issuedResetToken == null || !StringUtils.hasText(issuedResetToken.rawToken())) {
      return;
    }
    tokenCleanupTransactionTemplate.executeWithoutResult(
        status -> {
          assertTokenLifecycleTransactionActive("cleanup_failed_issue", correlationId, maskedEmail);
          deletePersistedResetToken(issuedResetToken.rawToken());
        });
  }

  private void markIssuedResetTokenDelivered(
      IssuedResetToken issuedResetToken, String correlationId, String maskedEmail) {
    if (issuedResetToken == null || issuedResetToken.id() == null) {
      return;
    }
    tokenCleanupTransactionTemplate.execute(
        new TransactionCallbackWithoutResult() {
          @Override
          protected void doInTransactionWithoutResult(
              org.springframework.transaction.TransactionStatus status) {
            markIssuedResetTokenDeliveredInLifecycleTransaction(
                issuedResetToken, correlationId, maskedEmail);
          }
        });
  }

  private void dispatchResetEmailMaskedPublic(
      UserAccount user, String correlationId, String scopeCode) {
    try {
      if (dispatchResetEmail(user, correlationId, "forgot_password")) {
        auditResetRequested("forgot_password", user, scopeCode, correlationId);
      }
    } catch (RuntimeException ex) {
      String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
      String safeMaskedEmail = sanitizeForPlainTextLog(obfuscateEmail(user.getEmail()));
      String safeExceptionClass = sanitizeExceptionClass(ex);
      log.warn(
          "event=password_reset.forgot_password.masked_failure policy={} correlationId={}"
              + " email={} outcome=masked_response exceptionClass={}",
          RESET_POLICY_SCOPE,
          safeCorrelationId,
          safeMaskedEmail,
          safeExceptionClass);
    }
  }

  private void markIssuedResetTokenDeliveredInLifecycleTransaction(
      IssuedResetToken issuedResetToken, String correlationId, String maskedEmail) {
    assertTokenLifecycleTransactionActive("mark_delivered", correlationId, maskedEmail);
    tokenRepository.markDeliveredAt(issuedResetToken.id(), Instant.now());
  }

  private void assertTokenLifecycleTransactionActive(
      String stage, String correlationId, String maskedEmail) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
    String safeMaskedEmail = sanitizeForPlainTextLog(maskedEmail);
    log.error(
        "event=password_reset.token_lifecycle.transaction policy={} correlationId={} email={}"
            + " stage={} outcome=missing_transaction",
        RESET_POLICY_SCOPE,
        safeCorrelationId,
        safeMaskedEmail,
        stage);
    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
        "Password reset token lifecycle operation requires an active transaction");
  }

  private int deletePersistedResetToken(String tokenValue) {
    String tokenDigest = AuthTokenDigests.passwordResetTokenDigest(tokenValue);
    return tokenRepository.deleteByTokenDigest(tokenDigest);
  }

  private void enforceResetRateLimit(
      String operation, String normalizedEmail, String scopeCode, String correlationId) {
    String rateLimitKey =
        RATE_LIMIT_PREFIX + ":" + operation + ":" + scopeCode + ":" + normalizedEmail;
    if (securityMonitoringService.checkRateLimit(rateLimitKey)) {
      return;
    }
    auditResetFailure(operation, correlationId, scopeCode, normalizedEmail, "rate_limited");
    throw new ApplicationException(
            ErrorCode.SYSTEM_RATE_LIMIT_EXCEEDED, "Password reset rate limit exceeded")
        .withDetail("companyCode", scopeCode);
  }

  private void auditResetRequested(
      String operation, UserAccount user, String scopeCode, String correlationId) {
    auditService.logAuthSuccess(
        AuditEvent.PASSWORD_RESET_REQUESTED,
        user.getEmail(),
        scopeCode,
        resetAuditMetadata(operation, correlationId, scopeCode, "email_dispatched"));
  }

  private void auditResetCompleted(UserAccount user, String scopeCode, String correlationId) {
    auditService.logAuthSuccess(
        AuditEvent.PASSWORD_RESET_COMPLETED,
        user.getEmail(),
        scopeCode,
        resetAuditMetadata("reset_password", correlationId, scopeCode, "password_updated"));
  }

  private void auditResetFailure(
      String operation, String correlationId, String scopeCode, String email, String outcome) {
    auditService.logAuthFailure(
        "reset_password".equals(operation)
            ? AuditEvent.PASSWORD_RESET_COMPLETED
            : AuditEvent.PASSWORD_RESET_REQUESTED,
        normalizeEmail(email),
        scopeCode,
        resetAuditMetadata(operation, correlationId, scopeCode, outcome));
  }

  private ApplicationException invalidOrExpiredResetToken(String correlationId) {
    auditService.logFailure(
        AuditEvent.PASSWORD_RESET_COMPLETED,
        resetAuditMetadata("reset_password", correlationId, null, "invalid_or_expired_token"));
    return com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
        "Invalid or expired token");
  }

  private java.util.Map<String, String> resetAuditMetadata(
      String operation, String correlationId, String scopeCode, String outcome) {
    java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>();
    metadata.put("operation", operation);
    metadata.put("policy", RESET_POLICY_SCOPE);
    metadata.put("correlationId", correlationId);
    metadata.put("outcome", outcome);
    if (StringUtils.hasText(scopeCode)) {
      metadata.put("companyCode", scopeCode);
    }
    return metadata;
  }

  private void logTenantContextIgnoredIfPresent(String operation, String correlationId) {
    String tenantContext = resolveTenantContextForObservability();
    if (!StringUtils.hasText(tenantContext)) {
      return;
    }
    String safeCorrelationId = sanitizeForPlainTextLog(correlationId);
    String safeTenantContext = sanitizeForPlainTextLog(sanitizeTenantContextForLog(tenantContext));
    log.info(
        "event=password_reset.scope policy={} operation={} correlationId={} tenantContext={}"
            + " outcome=tenant_context_ignored",
        RESET_POLICY_SCOPE,
        operation,
        safeCorrelationId,
        safeTenantContext);
  }

  private String resolveTenantContextForObservability() {
    String tenantContext = CompanyContextHolder.getCompanyCode();
    if (StringUtils.hasText(tenantContext)) {
      return tenantContext;
    }
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    HttpServletRequest request = attributes.getRequest();
    if (request == null) {
      return null;
    }
    return firstNonBlank(request.getHeader(COMPANY_CODE_HEADER));
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
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return UUID.randomUUID().toString();
    }
    HttpServletRequest request = attributes.getRequest();
    String headerCorrelationId =
        firstNonBlank(
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

  private String sanitizeForPlainTextLog(String value) {
    if (!StringUtils.hasText(value)) {
      return "<empty>";
    }
    String sanitized = value.trim().replace('\r', '_').replace('\n', '_').replace('\t', '_');
    if (!StringUtils.hasText(sanitized)) {
      return "<empty>";
    }
    if (sanitized.length() > MAX_LOG_VALUE_LENGTH) {
      return sanitized.substring(0, MAX_LOG_VALUE_LENGTH);
    }
    return sanitized;
  }

  private String sanitizeExceptionClass(Throwable throwable) {
    if (throwable == null) {
      return "<unknown>";
    }
    String exceptionClass = throwable.getClass().getSimpleName();
    if (!StringUtils.hasText(exceptionClass)) {
      exceptionClass = throwable.getClass().getName();
    }
    return sanitizeForPlainTextLog(exceptionClass);
  }
}
