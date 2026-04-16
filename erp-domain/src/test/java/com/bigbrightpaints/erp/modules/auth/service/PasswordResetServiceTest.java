package com.bigbrightpaints.erp.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.SecurityMonitoringService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PasswordResetServiceTest {

  private static final String TENANT_SCOPE = "ACME";

  @Mock private UserAccountRepository userAccountRepository;
  @Mock private PasswordResetTokenRepository tokenRepository;
  @Mock private PasswordService passwordService;
  @Mock private EmailService emailService;
  @Mock private AuditService auditService;
  @Mock private SecurityMonitoringService securityMonitoringService;
  @Mock private TokenBlacklistService tokenBlacklistService;
  @Mock private RefreshTokenService refreshTokenService;
  @Mock private AuthScopeService authScopeService;

  private EmailProperties emailProperties;
  private PasswordResetService passwordResetService;

  @BeforeEach
  void setup() {
    emailProperties = new EmailProperties();
    emailProperties.setEnabled(true);
    emailProperties.setSendPasswordReset(true);
    emailProperties.setBaseUrl("http://localhost:3004");
    passwordResetService =
        new PasswordResetService(
            userAccountRepository,
            tokenRepository,
            passwordService,
            emailService,
            emailProperties,
            auditService,
            securityMonitoringService,
            tokenBlacklistService,
            refreshTokenService,
            authScopeService,
            new ResourcelessTransactionManager());
    lenient()
        .when(authScopeService.requireScopeCode(anyString()))
        .thenAnswer(
            invocation -> invocation.getArgument(0, String.class).trim().toUpperCase(Locale.ROOT));
    lenient()
        .when(tokenRepository.saveAndFlush(any(PasswordResetToken.class)))
        .thenAnswer(
            invocation -> {
              PasswordResetToken token = invocation.getArgument(0);
              if (ReflectionTestUtils.getField(token, "id") == null) {
                ReflectionTestUtils.setField(token, "id", 1L);
              }
              return token;
            });
    lenient().when(tokenRepository.markDeliveredAt(anyLong(), any(Instant.class))).thenReturn(1);
    lenient().when(securityMonitoringService.checkRateLimit(anyString())).thenReturn(true);
  }

  @Test
  void requestResetSendsEmailAndStoresTokenForScopedAccount() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));

    passwordResetService.requestReset("user@example.com", "acme");

    ArgumentCaptor<PasswordResetToken> tokenCaptor =
        ArgumentCaptor.forClass(PasswordResetToken.class);
    verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
    ArgumentCaptor<String> emailTokenCaptor = ArgumentCaptor.forClass(String.class);
    verify(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), emailTokenCaptor.capture(), eq(TENANT_SCOPE));
    verify(tokenRepository).deleteByUser(eq(user));
    assertEquals(user, tokenCaptor.getValue().getUser());
    assertEquals(TENANT_SCOPE, tokenCaptor.getValue().getUser().getAuthScopeCode());
    org.assertj.core.api.Assertions.assertThat(emailTokenCaptor.getValue()).isNotBlank();
  }

  @Test
  void requestResetMasksMissingScopedAccount() {
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "missing@example.com", TENANT_SCOPE))
        .thenReturn(Optional.empty());

    assertDoesNotThrow(
        () -> passwordResetService.requestReset("missing@example.com", TENANT_SCOPE));

    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetMasksDisabledScopedAccount() {
    UserAccount user = enabledUser("disabled@example.com", TENANT_SCOPE);
    user.setEnabled(false);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "disabled@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));

    assertDoesNotThrow(
        () -> passwordResetService.requestReset("disabled@example.com", TENANT_SCOPE));

    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetMasksDeliveryDisabledForScopedAccount() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    emailProperties.setSendPasswordReset(false);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));

    assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com", TENANT_SCOPE));

    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetByAdminSendsResetEmailForEnabledUser() {
    UserAccount user = enabledUser("admin-reset@example.com", TENANT_SCOPE);

    passwordResetService.requestResetByAdmin(user);

    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService)
        .sendPasswordResetEmailRequired(
            eq("admin-reset@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));
  }

  @Test
  void requestResetByAdminAuditMetadataDoesNotTreatTargetAsActor() {
    UserAccount user = enabledUser("admin-reset-audit@example.com", TENANT_SCOPE);

    passwordResetService.requestResetByAdmin(user);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(auditService)
        .logAuthSuccess(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("admin-reset-audit@example.com"),
            eq(TENANT_SCOPE),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertEquals("admin_force_reset", metadata.get("operation"));
    assertEquals("email_dispatched", metadata.get("outcome"));
    assertEquals(null, metadata.get("actorPublicId"));
    assertEquals(user.getPublicId().toString(), metadata.get("subjectPublicId"));
  }

  @Test
  void requestResetByAdminFailsFastWhenDeliveryDisabled() {
    UserAccount user = enabledUser("admin-reset@example.com", TENANT_SCOPE);
    emailProperties.setSendPasswordReset(false);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    assertThrows(ApplicationException.class, () -> passwordResetService.requestResetByAdmin(user));

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("admin-reset@example.com"),
            eq(TENANT_SCOPE),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertEquals("delivery_failed", metadata.get("outcome"));
    assertEquals(user.getPublicId().toString(), metadata.get("subjectPublicId"));
    assertEquals(null, metadata.get("actorPublicId"));
    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetByAdminRejectsDisabledUser() {
    UserAccount user = enabledUser("disabled-admin@example.com", TENANT_SCOPE);
    user.setEnabled(false);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    ApplicationException exception =
        assertThrows(
            ApplicationException.class, () -> passwordResetService.requestResetByAdmin(user));

    assertEquals(ErrorCode.AUTH_ACCOUNT_DISABLED, exception.getErrorCode());
    assertEquals("Account is disabled", exception.getMessage());
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("disabled-admin@example.com"),
            eq(TENANT_SCOPE),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertEquals("user_disabled", metadata.get("outcome"));
    assertEquals(user.getPublicId().toString(), metadata.get("subjectPublicId"));
    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetByAdminRateLimitedAuditIncludesSubjectPublicId() {
    UserAccount user = enabledUser("rate-limited-admin@example.com", TENANT_SCOPE);
    when(securityMonitoringService.checkRateLimit(anyString())).thenReturn(false);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    ApplicationException exception =
        assertThrows(
            ApplicationException.class, () -> passwordResetService.requestResetByAdmin(user));

    assertEquals(ErrorCode.SYSTEM_RATE_LIMIT_EXCEEDED, exception.getErrorCode());
    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("rate-limited-admin@example.com"),
            eq(TENANT_SCOPE),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertEquals("rate_limited", metadata.get("outcome"));
    assertEquals(user.getPublicId().toString(), metadata.get("subjectPublicId"));
    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void resetPasswordRevokesSessionsByScopedPublicId() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    String rawToken = "token-value";
    PasswordResetToken token =
        PasswordResetToken.digestOnly(
            user,
            AuthTokenDigests.passwordResetTokenDigest(rawToken),
            Instant.now().plusSeconds(600));
    when(tokenRepository.findByTokenDigest(AuthTokenDigests.passwordResetTokenDigest(rawToken)))
        .thenReturn(Optional.of(token));

    passwordResetService.resetPassword(rawToken, "NewPass123", "NewPass123");

    verify(passwordService).resetPassword(user, "NewPass123", "NewPass123");
    verify(userAccountRepository).save(user);
    verify(tokenBlacklistService).revokeAllUserTokens(user.getPublicId().toString());
    verify(refreshTokenService).revokeAllForUser(user.getPublicId());
    verify(tokenRepository).save(token);
    verify(tokenRepository).deleteByUser(user);
  }

  @Test
  void requestResetCleansUpIssuedTokenWhenDispatchFailsInAdminFlow() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
    assertThrows(RuntimeException.class, () -> passwordResetService.requestResetByAdmin(user));

    verify(auditService)
        .logAuthFailure(
            eq(AuditEvent.PASSWORD_RESET_REQUESTED),
            eq("user@example.com"),
            eq(TENANT_SCOPE),
            metadataCaptor.capture());
    Map<String, String> metadata = metadataCaptor.getValue();
    assertEquals("delivery_failed", metadata.get("outcome"));
    assertEquals(user.getPublicId().toString(), metadata.get("subjectPublicId"));
    assertEquals(null, metadata.get("actorPublicId"));
    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(tokenRepository).deleteByTokenDigest(anyString());
  }

  @Test
  void requestResetMasksDispatchFailureAndCleansUpIssuedTokenInPublicFlow() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));

    assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com", TENANT_SCOPE));

    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(tokenRepository).deleteByTokenDigest(anyString());
  }

  @Test
  void requestResetKeepsIssuedTokenWhenDeliveryTrackingFailsAfterEmailDispatch() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    doThrow(new RuntimeException("tracking write failed"))
        .when(tokenRepository)
        .markDeliveredAt(anyLong(), any(Instant.class));

    assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com", TENANT_SCOPE));

    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));
    verify(tokenRepository, never()).deleteByTokenDigest(anyString());
  }

  @Test
  void requestReset_rateLimitedPasswordResetAbuseSendsOnlyOneSecurityAlertEmail() {
    SecurityMonitoringService monitoringService = new SecurityMonitoringService();
    ReflectionTestUtils.setField(monitoringService, "auditService", auditService);
    ReflectionTestUtils.setField(
        monitoringService, "auditLogRepository", mock(AuditLogRepository.class));
    ReflectionTestUtils.setField(monitoringService, "tokenBlacklistService", tokenBlacklistService);
    ReflectionTestUtils.setField(monitoringService, "emailService", emailService);
    ReflectionTestUtils.setField(monitoringService, "maxRequestsPerMinute", 0);
    ReflectionTestUtils.setField(monitoringService, "suspiciousActivityThreshold", 3);
    ReflectionTestUtils.setField(
        monitoringService, "securityNotificationEmail", "alerts@acme.test");
    ReflectionTestUtils.setField(monitoringService, "suspiciousActivityAlertWindowMinutes", 60);

    PasswordResetService rateLimitedService =
        new PasswordResetService(
            userAccountRepository,
            tokenRepository,
            passwordService,
            emailService,
            emailProperties,
            auditService,
            monitoringService,
            tokenBlacklistService,
            refreshTokenService,
            authScopeService,
            new ResourcelessTransactionManager());

    for (int attempt = 0; attempt < 3; attempt++) {
      ApplicationException exception =
          assertThrows(
              ApplicationException.class,
              () -> rateLimitedService.requestReset("user@example.com", "acme"));
      assertEquals(ErrorCode.SYSTEM_RATE_LIMIT_EXCEEDED, exception.getErrorCode());
    }

    verify(emailService, times(1))
        .sendSimpleEmail(
            eq("alerts@acme.test"),
            eq("Security alert: Suspicious activity detected"),
            anyString());
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
    verify(userAccountRepository, never())
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(anyString(), anyString());
  }

  @Test
  void requestResetByAdminRejectsNullTargetUser() {
    assertThrows(ApplicationException.class, () -> passwordResetService.requestResetByAdmin(null));
  }

  @Test
  void resetPasswordRejectsDisabledScopedUser() {
    UserAccount user = enabledUser("disabled@example.com", TENANT_SCOPE);
    user.setEnabled(false);
    PasswordResetToken token =
        PasswordResetToken.digestOnly(
            user,
            AuthTokenDigests.passwordResetTokenDigest("raw-token"),
            Instant.now().plusSeconds(600));
    when(tokenRepository.findByTokenDigest(AuthTokenDigests.passwordResetTokenDigest("raw-token")))
        .thenReturn(Optional.of(token));

    assertThrows(
        ApplicationException.class,
        () -> passwordResetService.resetPassword("raw-token", "NewPass123", "NewPass123"));
  }

  @Test
  void helperSanitizers_coverNullBlankLongAndAnonymousExceptions() {
    assertEquals(
        "<empty>",
        (String)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "sanitizeForPlainTextLog", "   "));
    assertEquals(
        "<unknown>",
        (String)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "sanitizeExceptionClass", (Object) null));
    org.assertj.core.api.Assertions.assertThat(
            (String)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    passwordResetService, "normalizeEmail", (Object) null))
        .isNull();
    assertEquals(
        "x".repeat(160),
        (String)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "sanitizeForPlainTextLog", "x".repeat(170)));
    String anonymousClass =
        (String)
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "sanitizeExceptionClass", new RuntimeException("boom") {});
    org.assertj.core.api.Assertions.assertThat(anonymousClass).contains("PasswordResetServiceTest");
  }

  @Test
  void helperMethods_coverDisabledDeliveryAndNullLifecycleShortCircuits() {
    emailProperties.setSendPasswordReset(false);
    assertEquals(false, passwordResetService.isResetEmailDeliveryEnabled());

    UserAccount disabledUser = enabledUser("user@example.com", TENANT_SCOPE);
    disabledUser.setEnabled(false);
    org.assertj.core.api.Assertions.assertThat(
            (Object)
                com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                    passwordResetService, "lockUserForResetIssuance", disabledUser))
        .isNull();

    UserAccount unsavedUser = enabledUser("user@example.com", TENANT_SCOPE);
    assertEquals(
        unsavedUser,
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            passwordResetService, "lockUserForResetIssuance", unsavedUser));

    assertDoesNotThrow(
        () ->
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "cleanupIssuedResetToken", null, "corr", "masked"));
    assertDoesNotThrow(
        () ->
            com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
                passwordResetService, "markIssuedResetTokenDelivered", null, "corr", "masked"));
    assertEquals(
        false,
        com.bigbrightpaints.erp.test.support.ReflectionFieldAccess.invokeMethod(
            passwordResetService, "dispatchResetEmail", null, "corr", "forgot_password"));
  }

  private UserAccount enabledUser(String email, String scopeCode) {
    UserAccount user = new UserAccount(email, scopeCode, "hash", "User");
    user.setEnabled(true);
    return user;
  }
}
