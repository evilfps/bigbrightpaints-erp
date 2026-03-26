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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Locale;
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

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.audit.AuditService;
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
        .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim().toUpperCase(Locale.ROOT));
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

    assertDoesNotThrow(() -> passwordResetService.requestReset("missing@example.com", TENANT_SCOPE));

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

    assertDoesNotThrow(() -> passwordResetService.requestReset("disabled@example.com", TENANT_SCOPE));

    verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
    verify(emailService, never())
        .sendPasswordResetEmailRequired(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void requestResetFailsFastWhenDeliveryDisabledForScopedAccount() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    emailProperties.setSendPasswordReset(false);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));

    assertThrows(ApplicationException.class, () -> passwordResetService.requestReset("user@example.com", TENANT_SCOPE));

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
  void requestResetByAdminFailsFastWhenDeliveryDisabled() {
    UserAccount user = enabledUser("admin-reset@example.com", TENANT_SCOPE);
    emailProperties.setSendPasswordReset(false);

    assertThrows(ApplicationException.class, () -> passwordResetService.requestResetByAdmin(user));

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

    assertThrows(RuntimeException.class, () -> passwordResetService.requestResetByAdmin(user));

    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(tokenRepository).deleteByTokenDigest(anyString());
  }

  @Test
  void requestResetCleansUpIssuedTokenWhenDispatchFailsInPublicFlow() {
    UserAccount user = enabledUser("user@example.com", TENANT_SCOPE);
    when(userAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(
            "user@example.com", TENANT_SCOPE))
        .thenReturn(Optional.of(user));
    doThrow(new RuntimeException("smtp down"))
        .when(emailService)
        .sendPasswordResetEmailRequired(
            eq("user@example.com"), eq("User"), anyString(), eq(TENANT_SCOPE));

    assertThrows(RuntimeException.class, () -> passwordResetService.requestReset("user@example.com", TENANT_SCOPE));

    verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
    verify(tokenRepository).deleteByTokenDigest(anyString());
  }

  private UserAccount enabledUser(String email, String scopeCode) {
    UserAccount user = new UserAccount(email, scopeCode, "hash", "User");
    user.setEnabled(true);
    return user;
  }
}
