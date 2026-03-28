package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.SecurityMonitoringService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;

@Tag("critical")
class TS_RuntimePasswordResetServiceExecutableCoverageTest {

  private static final String TENANT_SCOPE = "ACME";

  @Test
  void requestReset_correlationHeaderSanitizationBranches_areExecutable() {
    UserAccountRepository userRepository = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service = newService(userRepository, tokenRepository, emailService);

    UserAccount user = user("user@example.com");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    stubIssuedResetToken(tokenRepository, 11L);

    invokeRequest(service, "corr-support-123", null, null);
    invokeRequest(service, "   ", "req-fallback-123", null);
    invokeRequest(service, "bad|pattern", null, null);
    invokeRequest(service, "x".repeat(129), null, null);
    invokeRequest(service, "corr-newline\nsegment", null, null);
    invokeRequest(service, " ", " ", "\t");

    verify(emailService, atLeast(1))
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requestReset_missingLifecycleTransactionStaysMasked_runtimeCoverage() {
    UserAccountRepository userRepository = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service = newService(userRepository, tokenRepository, emailService);

    UserAccount user = user("user@example.com");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));

    TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
    when(lifecycleTemplate.execute(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .getArgument(
                        0, org.springframework.transaction.support.TransactionCallback.class)
                    .doInTransaction(null));
    ReflectionTestUtils.setField(service, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

    invokeRequest(service, "corr-tx-missing-123", null, null);

    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void correlationHelperMethods_coverNullArraysTrimmedControlAndNewlineBranches() {
    PasswordResetService service =
        newService(
            mock(UserAccountRepository.class),
            mock(PasswordResetTokenRepository.class),
            mock(EmailService.class));

    assertThat((String) ReflectionTestUtils.invokeMethod(service, "firstNonBlank", (Object) null))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(service, "firstNonBlank", (Object) new String[0]))
        .isNull();
    assertThat(
            (String) ReflectionTestUtils.invokeMethod(service, "sanitizeCorrelationId", "\u0000"))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    service, "sanitizeCorrelationId", "corr-with-cr\rsegment"))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    service, "sanitizeCorrelationId", "corr-with-lf\nsegment"))
        .isNull();
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(
                    service, "sanitizeForPlainTextLog", " corr\r\nsegment\tvalue "))
        .isEqualTo("corr__segment_value");
    assertThat(
            (String)
                ReflectionTestUtils.invokeMethod(service, "sanitizeForPlainTextLog", (Object) null))
        .isEqualTo("<empty>");
    assertThat((String) ReflectionTestUtils.invokeMethod(service, "obfuscateEmail", (Object) null))
        .isEqualTo("<empty>");
  }

  @Test
  void requestReset_masksUnexpectedDeliveryFailures_andCleansUpIssuedToken_runtimeCoverage() {
    UserAccountRepository userRepository = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service = newService(userRepository, tokenRepository, emailService);

    UserAccount user = user("user@example.com");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    stubIssuedResetToken(tokenRepository, 41L);
    doThrow(new IllegalStateException("unexpected dispatch bug"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));

    invokeRequest(service, "corr-delivery-mask-123", null, null);

    verify(tokenRepository).deleteByTokenDigest(anyString());
  }

  @Test
  void requestReset_deliveryTrackingFailure_keepsIssuedToken_runtimeCoverage() {
    UserAccountRepository userRepository = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service = newService(userRepository, tokenRepository, emailService);

    UserAccount user = user("user@example.com");
    when(userRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    stubIssuedResetToken(tokenRepository, 42L);
    doThrow(new IllegalStateException("tracking write failed"))
        .when(tokenRepository)
        .markDeliveredAt(any(Long.class), any(Instant.class));

    invokeRequest(service, "corr-track-123", null, null);

    verify(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));
    verify(tokenRepository, never()).deleteByTokenDigest(anyString());
  }

  @Test
  void requestReset_skipsDisabledAndMissingUsers_runtimeCoverage() {
    UserAccountRepository userRepo = mock(UserAccountRepository.class);
    PasswordResetTokenRepository tokenRepo = mock(PasswordResetTokenRepository.class);
    EmailService emailService = mock(EmailService.class);
    PasswordResetService service = newService(userRepo, tokenRepo, emailService);

    UserAccount disabledUser = user("disabled@example.com");
    disabledUser.setEnabled(false);
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("disabled@example.com", "ACME"))
        .thenReturn(Optional.of(disabledUser));
    when(userRepo.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase("missing@example.com", "ACME"))
        .thenReturn(Optional.empty());

    invokeRequest(service, "disabled-corr-123", null, null, "disabled@example.com");
    invokeRequest(service, "missing-corr-123", null, null, "missing@example.com");

    verify(tokenRepo, never()).deleteByTokenDigest(anyString());
    verify(tokenRepo, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  private PasswordResetService newService(
      UserAccountRepository userRepository,
      PasswordResetTokenRepository tokenRepository,
      EmailService emailService) {
    AuthScopeService authScopeService = mock(AuthScopeService.class);
    SecurityMonitoringService securityMonitoringService = mock(SecurityMonitoringService.class);
    when(authScopeService.requireScopeCode(anyString()))
        .thenAnswer(
            invocation -> invocation.getArgument(0, String.class).trim().toUpperCase(Locale.ROOT));
    when(securityMonitoringService.checkRateLimit(anyString())).thenReturn(true);
    when(tokenRepository.saveAndFlush(any(PasswordResetToken.class)))
        .thenAnswer(
            invocation -> {
              PasswordResetToken token = invocation.getArgument(0);
              if (ReflectionTestUtils.getField(token, "id") == null) {
                ReflectionTestUtils.setField(token, "id", 1L);
              }
              return token;
            });
    return new PasswordResetService(
        userRepository,
        tokenRepository,
        mock(PasswordService.class),
        emailService,
        emailProperties(),
        mock(AuditService.class),
        securityMonitoringService,
        mock(TokenBlacklistService.class),
        mock(RefreshTokenService.class),
        authScopeService,
        new ResourcelessTransactionManager());
  }

  private void stubIssuedResetToken(PasswordResetTokenRepository tokenRepository, long tokenId) {
    when(tokenRepository.saveAndFlush(any(PasswordResetToken.class)))
        .thenAnswer(
            invocation -> {
              PasswordResetToken token = invocation.getArgument(0);
              ReflectionTestUtils.setField(token, "id", tokenId);
              return token;
            });
  }

  private void invokeRequest(
      PasswordResetService service, String correlationId, String requestId, String traceId) {
    invokeRequest(service, correlationId, requestId, traceId, "user@example.com");
  }

  private void invokeRequest(
      PasswordResetService service,
      String correlationId,
      String requestId,
      String traceId,
      String email) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot");
    if (correlationId != null) {
      request.addHeader("X-Correlation-Id", correlationId);
    }
    if (requestId != null) {
      request.addHeader("X-Request-Id", requestId);
    }
    if (traceId != null) {
      request.addHeader("X-Trace-Id", traceId);
    }
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      service.requestReset(email, TENANT_SCOPE);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private UserAccount user(String email) {
    UserAccount user = new UserAccount(email, TENANT_SCOPE, "hash", "User");
    user.setEnabled(true);
    return user;
  }

  private EmailProperties emailProperties() {
    EmailProperties properties = new EmailProperties();
    properties.setEnabled(true);
    properties.setSendPasswordReset(true);
    properties.setBaseUrl("http://localhost:3004");
    return properties;
  }
}
