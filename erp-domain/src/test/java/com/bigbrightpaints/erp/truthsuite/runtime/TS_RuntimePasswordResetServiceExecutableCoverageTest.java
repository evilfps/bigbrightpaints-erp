package com.bigbrightpaints.erp.truthsuite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bigbrightpaints.erp.core.config.EmailProperties;
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
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
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
        verify(tokenRepository, never()).deleteByToken(anyString());
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

    private PasswordResetService newService(
            UserAccountRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            EmailService emailService) {
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

    private EmailProperties emailProperties() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setSendPasswordReset(true);
        properties.setBaseUrl("http://localhost:3004");
        return properties;
    }
}
