package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordResetService;
import com.bigbrightpaints.erp.modules.auth.service.PasswordService;
import com.bigbrightpaints.erp.modules.auth.service.RefreshTokenService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Tag("critical")
class TS_RuntimePasswordResetServiceExecutableCoverageTest {

    @Test
    void requestResetForSuperAdmin_correlationHeaderSanitizationBranches_areExecutable() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userRepository.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), eq("Reset your BigBright ERP password"), anyString());

        invokeRequest(service, "corr-support-123", null, null);
        invokeRequest(service, "   ", "req-fallback-123", null);
        invokeRequest(service, "bad|pattern", null, null);
        invokeRequest(service, "x".repeat(129), null, null);
        invokeRequest(service, "corr-newline\nsegment", null, null);
        invokeRequest(service, " ", " ", "\t");

        verify(emailService, times(6))
                .sendSimpleEmail(eq("superadmin@example.com"), eq("Reset your BigBright ERP password"), anyString());
    }

    @Test
    void requestResetForSuperAdmin_masksBlankTokenLifecycleResult_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userRepository.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any())).thenReturn("   ");
        ReflectionTestUtils.setField(service, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

        invokeRequest(service, "corr-blank-token-123", null, null);

        verify(lifecycleTemplate).execute(any());
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestResetForSuperAdmin_masksMissingLifecycleTransaction_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userRepository.findByEmailIgnoreCase("superadmin@example.com")).thenReturn(Optional.of(superAdmin));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<String> callback = (TransactionCallback<String>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ReflectionTestUtils.setField(service, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

        invokeRequest(service, "corr-tx-missing-123", null, null);

        verify(tokenRepository, never()).deleteByUser(any(UserAccount.class));
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void correlationHelperMethods_coverNullArraysTrimmedControlAndNewlineBranches() {
        PasswordResetService service = newService(
                mock(UserAccountRepository.class),
                mock(PasswordResetTokenRepository.class),
                mock(EmailService.class));

        assertThat((String) ReflectionTestUtils.invokeMethod(service, "firstNonBlank", (Object) null)).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "firstNonBlank", (Object) new String[0])).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "sanitizeCorrelationId", "\u0000")).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "sanitizeCorrelationId", "corr-with-cr\rsegment"))
                .isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "sanitizeCorrelationId", "corr-with-lf\nsegment"))
                .isNull();

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(
                service,
                "cleanupFailedSuperAdminResetToken",
                null,
                "token-123",
                "corr-null-user"))
                .doesNotThrowAnyException();
    }

    @Test
    void requestReset_masksUnexpectedNonPersistenceRuntimeFailures_withoutDatabaseError_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            ReflectionTestUtils.setField(token, "id", 41L);
            return token;
        });
        doThrow(new IllegalStateException("unexpected dispatch bug"))
                .when(tokenRepository)
                .touchCreatedAt(anyLong(), any(Instant.class));

        assertThatCode(() -> service.requestReset("user@example.com")).doesNotThrowAnyException();

        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestReset_configuresAfterCommitCleanupWithRequiresNewPropagation_runtimeCoverage() {
        PasswordResetService service = newService(
                mock(UserAccountRepository.class),
                mock(PasswordResetTokenRepository.class),
                mock(EmailService.class));

        TransactionTemplate afterCommitTemplate = (TransactionTemplate) ReflectionTestUtils.getField(
                service,
                "tokenAfterCommitCleanupTransactionTemplate");

        assertThat(afterCommitTemplate).isNotNull();
        assertThat(afterCommitTemplate.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void requestReset_keepsIssuedTokenDuringCleanup_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.lockById(101L)).thenReturn(Optional.of(user));
        when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            ReflectionTestUtils.setField(token, "id", 41L);
            return token;
        });

        service.requestReset("user@example.com");

        verify(tokenRepository).deleteByUserAndIdNot(user, 41L);
        verify(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
    }

    @Test
    void requestReset_skipsPriorRestoreWhenDispatchCleanupLeavesIssuedTokenStateUnknown_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.lockById(101L)).thenReturn(Optional.of(user));

        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                passwordResetDigest("prior-reset-token"),
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            if (ReflectionTestUtils.getField(token, "id") == null) {
                ReflectionTestUtils.setField(token, "id", 41L);
            }
            return token;
        });
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
                .when(tokenRepository)
                .deleteByTokenDigest(anyString());

        assertThatCode(() -> service.requestReset("user@example.com")).doesNotThrowAnyException();

        verify(userRepository, atLeast(3)).lockById(101L);
        verify(userRepository, never()).findById(101L);
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestReset_skipsPriorRestoreWhenIssuedTokenAlreadyMissingDuringDispatchCleanup_runtimeCoverage() {
        UserAccountRepository userRepository = mock(UserAccountRepository.class);
        PasswordResetTokenRepository tokenRepository = mock(PasswordResetTokenRepository.class);
        EmailService emailService = mock(EmailService.class);
        PasswordResetService service = newService(userRepository, tokenRepository, emailService);

        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.lockById(101L)).thenReturn(Optional.of(user));
        TransactionTemplate cleanupTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        cleanupTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        ReflectionTestUtils.setField(service, "tokenAfterCommitCleanupTransactionTemplate", cleanupTemplate);

        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                passwordResetDigest("prior-reset-token"),
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            if (ReflectionTestUtils.getField(token, "id") == null) {
                ReflectionTestUtils.setField(token, "id", 41L);
            }
            return token;
        });
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(0);

        assertThatCode(() -> service.requestReset("user@example.com")).doesNotThrowAnyException();

        verify(tokenRepository).deleteByTokenDigest(anyString());
        verify(userRepository, never()).findById(101L);
    }

    private PasswordResetService newService(
            UserAccountRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            EmailService emailService) {
        when(tokenRepository.markDeliveredAt(anyLong(), any(Instant.class))).thenReturn(1);
        return new PasswordResetService(
                userRepository,
                tokenRepository,
                mock(PasswordService.class),
                emailService,
                emailProperties(),
                mock(TokenBlacklistService.class),
                mock(RefreshTokenService.class),
                new ResourcelessTransactionManager());
    }

    private void invokeRequest(
            PasswordResetService service,
            String correlationId,
            String requestId,
            String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
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
            assertThatCode(() -> service.requestResetForSuperAdmin("superadmin@example.com"))
                    .doesNotThrowAnyException();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private UserAccount superAdminUser(String email) {
        UserAccount user = new UserAccount(email, "hash", "Super Admin");
        user.setEnabled(true);
        Role role = new Role();
        role.setName("ROLE_SUPER_ADMIN");
        user.addRole(role);
        return user;
    }

    private String passwordResetDigest(String token) {
        return IdempotencyUtils.sha256Hex("password-reset-token:" + token);
    }

    private EmailProperties emailProperties() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setSendPasswordReset(true);
        properties.setBaseUrl("http://localhost:3004");
        return properties;
    }
}
