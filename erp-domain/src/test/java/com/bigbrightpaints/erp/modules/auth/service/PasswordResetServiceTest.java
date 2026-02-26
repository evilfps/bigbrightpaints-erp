package com.bigbrightpaints.erp.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private PasswordService passwordService;
    @Mock
    private EmailService emailService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private RefreshTokenService refreshTokenService;

    private EmailProperties emailProperties;
    private PasswordResetService passwordResetService;

    @BeforeEach
    void setup() {
        emailProperties = new EmailProperties();
        emailProperties.setEnabled(true);
        emailProperties.setSendPasswordReset(true);
        emailProperties.setBaseUrl("http://localhost:3004");
        passwordResetService = new PasswordResetService(
                userAccountRepository,
                tokenRepository,
                passwordService,
                emailService,
                emailProperties,
                tokenBlacklistService,
                refreshTokenService,
                new ResourcelessTransactionManager());
    }

    @Test
    void requestResetSendsEmailAndStoresToken() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));

        passwordResetService.requestReset("user@example.com");

        verify(tokenRepository).deleteByUser(user);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        verify(emailService).sendPasswordResetEmail(eq("user@example.com"), eq("User"), any());
        // ensure token is linked to the same user
        org.junit.jupiter.api.Assertions.assertEquals(user, tokenCaptor.getValue().getUser());
    }

    @Test
    void resetPasswordThrowsWhenTokenExpired() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        PasswordResetToken expired = new PasswordResetToken(user, "token", Instant.now().minusSeconds(10));
        when(tokenRepository.findByToken("token")).thenReturn(Optional.of(expired));

        assertThrows(IllegalArgumentException.class,
                () -> passwordResetService.resetPassword("token", "NewPass123", "NewPass123"));

        verify(passwordService, never()).resetPassword(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminMasksWhenResetEmailDeliveryDisabled() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        emailProperties.setSendPasswordReset(false);

        assertDoesNotThrow(() -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).deleteByToken(anyString());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminMasksSmtpDispatchFailure() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new ApplicationException(ErrorCode.SYSTEM_EXTERNAL_SERVICE_ERROR, "Failed to dispatch email via SMTP"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        assertDoesNotThrow(() -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        InOrder inOrder = inOrder(tokenRepository, emailService);
        inOrder.verify(tokenRepository).deleteByUser(superAdmin);
        inOrder.verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
        inOrder.verify(emailService).sendSimpleEmail(eq("superadmin@example.com"), any(), any());
        inOrder.verify(tokenRepository).deleteByToken(tokenCaptor.getValue().getToken());
    }

    @Test
    void requestResetForSuperAdminExecutesTokenLifecycleWithinActiveTransactions() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));

        AtomicBoolean deleteByUserInTx = new AtomicBoolean(false);
        AtomicBoolean saveAndFlushInTx = new AtomicBoolean(false);
        AtomicBoolean cleanupInTx = new AtomicBoolean(false);
        doAnswer(invocation -> {
            deleteByUserInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(tokenRepository).deleteByUser(superAdmin);
        doAnswer(invocation -> {
            saveAndFlushInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return invocation.getArgument(0);
        }).when(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        doAnswer(invocation -> {
            cleanupInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(tokenRepository).deleteByToken(anyString());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        passwordResetService.requestResetForSuperAdmin("superadmin@example.com");

        assertTrue(deleteByUserInTx.get(), "deleteByUser should execute inside an active transaction");
        assertTrue(saveAndFlushInTx.get(), "saveAndFlush should execute inside an active transaction");
        assertTrue(cleanupInTx.get(), "cleanup deleteByToken should execute inside an active transaction");
    }

    @Test
    void requestResetForSuperAdminPropagatesRequestCorrelationIdIntoStructuredLogs() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-support-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Logger serviceLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        serviceLogger.addAppender(listAppender);
        try {
            passwordResetService.requestResetForSuperAdmin("superadmin@example.com");
            assertTrue(
                    listAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(message -> message.contains("correlationId=corr-support-123")),
                    "Expected structured log message to contain the request correlation id");
        } finally {
            serviceLogger.detachAppender(listAppender);
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdForMalformedHeader() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-bad\r\nx-injected:1|evil");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("corr-bad")),
                    "Expected malformed request correlation header to be rejected in logs");
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when request header is malformed");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminRedactsRawExceptionMessagesInLogs() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp credential leak"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-redaction-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("reasonCode=RESET_DISPATCH_FAILURE")),
                    "Expected dispatch failure reason code in masked superadmin forgot logs");
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("exceptionClass=RuntimeException")),
                    "Expected exception class to remain available in masked superadmin forgot logs");
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("smtp credential leak")),
                    "Expected raw exception message to be redacted from logs");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminCleanupFailureEmitsSecurityEventCode() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());
        doThrow(new RuntimeException("cleanup token leaked"))
                .when(tokenRepository)
                .deleteByToken(anyString());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-cleanup-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains(
                                    "securityEventCode=SEC_AUTH_SUPERADMIN_RESET_CLEANUP_FAILURE")
                            && message.contains("correlationId=corr-cleanup-123")),
                    "Expected cleanup failures to emit a deterministic security event code with correlation id");
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("cleanup token leaked")),
                    "Expected cleanup raw exception details to remain redacted");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminStoresTokenBeforeSuccessfulEmailDispatch() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));

        passwordResetService.requestResetForSuperAdmin("superadmin@example.com");

        InOrder inOrder = inOrder(tokenRepository, emailService);
        inOrder.verify(tokenRepository).deleteByUser(superAdmin);
        inOrder.verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        inOrder.verify(emailService).sendSimpleEmail(eq("superadmin@example.com"), any(), any());
        verify(tokenRepository, never()).deleteByToken(anyString());
    }

    @Test
    void requestResetForSuperAdminMasksTokenPersistenceFailure() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(tokenRepository)
                .saveAndFlush(any(PasswordResetToken.class));

        assertDoesNotThrow(() -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

        verify(tokenRepository).deleteByUser(superAdmin);
        verify(tokenRepository, never()).deleteByToken(anyString());
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminMasksUnexpectedRollbackFailure() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new UnexpectedRollbackException("tx marked rollback-only"))
                .when(tokenRepository)
                .saveAndFlush(any(PasswordResetToken.class));

        assertDoesNotThrow(() -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

        verify(tokenRepository).deleteByUser(superAdmin);
        verify(tokenRepository, never()).deleteByToken(anyString());
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminSkipsNonSuperAdminUsers() {
        UserAccount adminUser = new UserAccount("admin@example.com", "hash", "Admin");
        adminUser.setEnabled(true);
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        adminUser.addRole(adminRole);
        when(userAccountRepository.findByEmailIgnoreCase("admin@example.com"))
                .thenReturn(Optional.of(adminUser));

        passwordResetService.requestResetForSuperAdmin("admin@example.com");

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).deleteByToken(anyString());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    private UserAccount superAdminUser(String email) {
        UserAccount user = new UserAccount(email, "hash", "Super Admin");
        user.setEnabled(true);
        Role role = new Role();
        role.setName("ROLE_SUPER_ADMIN");
        user.addRole(role);
        return user;
    }

    private List<String> captureServiceLogMessages(Runnable invocation) {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        serviceLogger.addAppender(listAppender);
        try {
            invocation.run();
            return listAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            serviceLogger.detachAppender(listAppender);
        }
    }
}
