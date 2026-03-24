package com.bigbrightpaints.erp.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetToken;
import com.bigbrightpaints.erp.modules.auth.domain.PasswordResetTokenRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
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
        lenient().when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            if (token != null && ReflectionTestUtils.getField(token, "id") == null) {
                ReflectionTestUtils.setField(token, "id", 1L);
            }
            return token;
        });
        lenient().when(tokenRepository.markDeliveredAt(anyLong(), any(Instant.class))).thenReturn(1);
    }

    @Test
    void requestResetSendsEmailAndStoresToken() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));

        passwordResetService.requestReset("user@example.com");

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
        ArgumentCaptor<String> emailTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), emailTokenCaptor.capture());
        verify(tokenRepository).deleteByUserAndIdNot(eq(user), anyLong());
        // ensure token is linked to the same user
        assertEquals(user, tokenCaptor.getValue().getUser());
        assertNull(tokenCaptor.getValue().getToken());
        assertTrue(tokenCaptor.getValue().getTokenDigest() instanceof String);
        assertTrue(emailTokenCaptor.getValue() != null && !emailTokenCaptor.getValue().isBlank());
    }

    @Test
    void requestResetDispatchesEmailAfterTokenCleanupWithinForgotFlow() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());

        passwordResetService.requestReset("user@example.com");

        InOrder inOrder = inOrder(tokenRepository, emailService);
        inOrder.verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        inOrder.verify(tokenRepository).touchCreatedAt(anyLong(), any(Instant.class));
        inOrder.verify(tokenRepository).deleteByUserAndIdNot(eq(user), anyLong());
        inOrder.verify(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        inOrder.verify(tokenRepository).markDeliveredAt(anyLong(), any(Instant.class));
    }

    @Test
    void requestResetRestoresPriorTokenWhenDeliveryMarkerUpdateIsMissing() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());

        String priorTokenDigest = AuthTokenDigests.passwordResetTokenDigest("prior-reset-token");
        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                priorTokenDigest,
                Instant.now().plusSeconds(600));
        Instant deliveredAt = Instant.now().minusSeconds(30);
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", deliveredAt);
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        when(tokenRepository.markDeliveredAt(anyLong(), any(Instant.class))).thenReturn(0);
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(1);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, atLeast(2)).saveAndFlush(tokenCaptor.capture());
        assertTrue(
                tokenCaptor.getAllValues().stream()
                        .anyMatch(token -> priorTokenDigest.equals(token.getTokenDigest())
                                && deliveredAt.equals(token.getDeliveredAt())),
                "Missing delivery markers must roll back to the last delivered reset token");
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetRestoresPriorTokenWhenDeliveryMarkerUpdateThrows() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());

        String priorTokenDigest = AuthTokenDigests.passwordResetTokenDigest("prior-reset-token");
        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                priorTokenDigest,
                Instant.now().plusSeconds(600));
        Instant deliveredAt = Instant.now().minusSeconds(30);
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", deliveredAt);
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        doThrow(new DataAccessResourceFailureException("marker unavailable"))
                .when(tokenRepository)
                .markDeliveredAt(anyLong(), any(Instant.class));
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(1);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, atLeast(2)).saveAndFlush(tokenCaptor.capture());
        assertTrue(
                tokenCaptor.getAllValues().stream()
                        .anyMatch(token -> priorTokenDigest.equals(token.getTokenDigest())
                                && deliveredAt.equals(token.getDeliveredAt())),
                "Marker exceptions must roll back to the last delivered reset token");
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetMasksCleanupBeforeCommitFailuresAndSkipsEmail() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
                .when(tokenRepository)
                .deleteByUserAndIdNot(any(UserAccount.class), anyLong());

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksUnexpectedNonPersistenceRuntimeFailuresWithoutDatabaseError() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("unexpected dispatch bug"))
                .when(tokenRepository)
                .touchCreatedAt(anyLong(), any(Instant.class));

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void resetPasswordThrowsWhenTokenExpired() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        String rawToken = "token";
        PasswordResetToken expired = PasswordResetToken.digestOnly(
                user,
                AuthTokenDigests.passwordResetTokenDigest(rawToken),
                Instant.now().minusSeconds(10));
        when(tokenRepository.findByTokenDigest(AuthTokenDigests.passwordResetTokenDigest(rawToken)))
                .thenReturn(Optional.of(expired));

        assertThrows(ApplicationException.class,
                () -> passwordResetService.resetPassword(rawToken, "NewPass123", "NewPass123"));

        verify(passwordService, never()).resetPassword(any(), any(), any());
    }

    @Test
    void resetPasswordRejectsLegacyRawTokenRows() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);

        assertThrows(ApplicationException.class,
                () -> passwordResetService.resetPassword("legacy-token", "NewPass123!", "NewPass123!"));

        verify(passwordService, never()).resetPassword(any(), any(), any());
    }

    @Test
    void resetPasswordRejectsAlreadyUsedDigestToken() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        String rawToken = "used-token";
        PasswordResetToken used = PasswordResetToken.digestOnly(
                user,
                AuthTokenDigests.passwordResetTokenDigest(rawToken),
                Instant.now().plusSeconds(300));
        used.markUsed();
        when(tokenRepository.findByTokenDigest(AuthTokenDigests.passwordResetTokenDigest(rawToken)))
                .thenReturn(Optional.of(used));

        assertThrows(ApplicationException.class,
                () -> passwordResetService.resetPassword(rawToken, "NewPass123!", "NewPass123!"));

        verify(passwordService, never()).resetPassword(any(), any(), any());
    }

    @Test
    void requestResetByAdminUsesPublicResetFlowForEnabledUser() {
        UserAccount adminManagedUser = new UserAccount("managed@example.com", "hash", "Managed User");
        adminManagedUser.setEnabled(true);

        passwordResetService.requestResetByAdmin(adminManagedUser);

        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmailRequired(eq("managed@example.com"), eq("Managed User"), anyString());
        verify(tokenRepository).deleteByUser(adminManagedUser);
        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetByAdminMasksRuntimeDispatchFailureAndDeletesIssuedToken() {
        UserAccount adminManagedUser = new UserAccount("managed@example.com", "hash", "Managed User");
        adminManagedUser.setEnabled(true);
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("managed@example.com"), eq("Managed User"), anyString());

        assertThrows(RuntimeException.class, () -> passwordResetService.requestResetByAdmin(adminManagedUser));

        verify(tokenRepository).deleteByUser(adminManagedUser);
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetByAdminDoesNotIssueTokenWhenResetIssuanceLockUnavailable() {
        UserAccount adminManagedUser = new UserAccount("managed@example.com", "hash", "Managed User");
        adminManagedUser.setEnabled(true);
        ReflectionTestUtils.setField(adminManagedUser, "id", 101L);
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.empty());

        passwordResetService.requestResetByAdmin(adminManagedUser);

        verify(userAccountRepository).lockById(101L);
        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetByAdminSkipsDisabledUsers() {
        UserAccount disabledUser = new UserAccount("disabled-managed@example.com", "hash", "Disabled User");
        disabledUser.setEnabled(false);

        passwordResetService.requestResetByAdmin(disabledUser);

        verifyNoInteractions(tokenRepository, emailService);
        verify(userAccountRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void requestResetSkipsTokenIssuanceWhenResetEmailDeliveryDisabled() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        emailProperties.setSendPasswordReset(false);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).deleteByUserAndIdNot(any(), anyLong());
        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetSkipsEmailDispatchWhenPublicIssueTokenIsUnavailable() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(userAccountRepository).lockById(101L);
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository, never()).touchCreatedAt(anyLong(), any(Instant.class));
        verify(tokenRepository, never()).deleteByUserAndIdNot(any(UserAccount.class), anyLong());
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksDispatchFailureAndDeletesIssuedToken() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(
                passwordResetService,
                "tokenAfterCommitCleanupTransactionTemplate",
                requiredPropagationTemplate());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetMasksUnexpectedRuntimeFailureWithoutMisclassifyingItAsDatabaseError() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("unexpected ordering failure"))
                .when(tokenRepository)
                .touchCreatedAt(anyLong(), any(Instant.class));

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository).touchCreatedAt(anyLong(), any(Instant.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksTokenPersistenceFailuresForKnownUser() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(tokenRepository)
                .saveAndFlush(any(PasswordResetToken.class));

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).deleteByUserAndIdNot(any(), anyLong());
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksLifecycleTransactionStartupFailures() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any()))
                .thenThrow(new CannotCreateTransactionException("db unavailable"));
        ReflectionTestUtils.setField(passwordResetService, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(lifecycleTemplate).execute(any());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksCleanupPersistenceFailures() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(
                passwordResetService,
                "tokenAfterCommitCleanupTransactionTemplate",
                requiredPropagationTemplate());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
                .when(tokenRepository)
                .deleteByTokenDigest(anyString());

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetConfiguresAfterCommitCleanupWithRequiresNewPropagation() {
        TransactionTemplate afterCommitTemplate = (TransactionTemplate) ReflectionTestUtils.getField(
                passwordResetService,
                "tokenAfterCommitCleanupTransactionTemplate");

        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, afterCommitTemplate.getPropagationBehavior());
    }

    @Test
    void requestResetKeepsIssuedTokenDuringCleanup() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        when(tokenRepository.saveAndFlush(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            ReflectionTestUtils.setField(token, "id", 41L);
            return token;
        });

        passwordResetService.requestReset("user@example.com");

        verify(tokenRepository).deleteByUserAndIdNot(user, 41L);
        verify(tokenRepository, never()).findTopByUserOrderByCreatedAtDescIdDesc(any(UserAccount.class));
        verify(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
    }

    @Test
    void requestResetDoesNotRestorePriorTokenWhenDispatchCleanupFailureLeavesIssuedTokenStateUnknown() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));

        String priorTokenDigest = AuthTokenDigests.passwordResetTokenDigest("prior-reset-token");
        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                priorTokenDigest,
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));

        TransactionTemplate cleanupFallbackTemplate = new TransactionTemplate(new ResourcelessTransactionManager());
        cleanupFallbackTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        ReflectionTestUtils.setField(passwordResetService, "tokenCleanupTransactionTemplate", cleanupFallbackTemplate);
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());

        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        doThrow(new DataAccessResourceFailureException("cleanup unavailable"))
                .when(tokenRepository)
                .deleteByTokenDigest(anyString());

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
        assertFalse(
                tokenCaptor.getAllValues().stream()
                        .map(PasswordResetToken::getTokenDigest)
                        .anyMatch(priorTokenDigest::equals),
                "Fallback restore must not reinsert the prior token when issued-token cleanup did not complete");
        verify(userAccountRepository, atLeast(3)).lockById(101L);
        verify(userAccountRepository, never()).findById(101L);
        verify(tokenRepository, times(2)).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetDoesNotRestorePriorTokenWhenIssuedTokenWasAlreadySuperseded() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));

        String priorTokenDigest = AuthTokenDigests.passwordResetTokenDigest("prior-reset-token");
        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                priorTokenDigest,
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());

        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(0);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(tokenRepository).deleteByTokenDigest(anyString());
        verify(userAccountRepository, never()).findById(101L);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).saveAndFlush(tokenCaptor.capture());
        assertTrue(
                tokenCaptor.getAllValues().stream()
                        .map(PasswordResetToken::getTokenDigest)
                        .noneMatch(priorTokenDigest::equals),
                "Cleanup should not resurrect a superseded prior token when the issued token was already gone");
    }

    @Test
    void requestResetRestoresPriorTokenWhenDispatchCleanupDeletesIssuedToken() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));

        String priorTokenDigest = AuthTokenDigests.passwordResetTokenDigest("prior-reset-token");
        PasswordResetToken priorToken = PasswordResetToken.digestOnly(
                user,
                priorTokenDigest,
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(priorToken, "id", 7L);
        ReflectionTestUtils.setField(priorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(eq(user), anyLong()))
                .thenReturn(Optional.of(priorToken));
        ReflectionTestUtils.setField(passwordResetService, "tokenAfterCommitCleanupTransactionTemplate", requiredPropagationTemplate());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(1);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository, atLeast(2)).saveAndFlush(tokenCaptor.capture());
        assertTrue(
                tokenCaptor.getAllValues().stream()
                        .map(PasswordResetToken::getTokenDigest)
                        .anyMatch(priorTokenDigest::equals),
                "Cleanup should restore the prior token when the issued token was actually deleted");
        verify(userAccountRepository, atLeast(3)).lockById(101L);
        verify(userAccountRepository, never()).findById(101L);
        verify(tokenRepository).deleteByTokenDigest(anyString());
    }

    @Test
    void restorePublicResetStateAfterDispatchFailureLocksUserBeforeDeleteAndRestore() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        when(tokenRepository.deleteByTokenDigest(anyString())).thenReturn(1);

        Object dispatchPlan = publicResetDispatchPlan(
                issuedResetToken(41L, "raw-token"),
                priorResetTokenSnapshot(
                        101L,
                        AuthTokenDigests.passwordResetTokenDigest("prior-reset-token"),
                        Instant.now().plusSeconds(600)));

        assertNull(ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePublicResetStateAfterDispatchFailure",
                dispatchPlan,
                "corr-lock-123",
                "u***@example.com",
                "forgot_password"));

        InOrder inOrder = inOrder(userAccountRepository, tokenRepository);
        inOrder.verify(userAccountRepository).lockById(101L);
        inOrder.verify(tokenRepository).deleteByTokenDigest(anyString());
        inOrder.verify(tokenRepository).saveAndFlush(argThat(token -> token != null
                && AuthTokenDigests.passwordResetTokenDigest("prior-reset-token").equals(token.getTokenDigest())));
        verify(userAccountRepository, never()).findById(101L);
    }

    private TransactionTemplate requiredPropagationTemplate() {
        TransactionTemplate template = new TransactionTemplate(new ResourcelessTransactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return template;
    }

    @Test
    void privateHelperGuardsFailClosedOutsideTransactionSynchronization() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        Object dispatchPlan = publicResetDispatchPlan(issuedResetToken(41L, "raw-token"), null);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "registerPublicResetDispatchAfterCommit",
                        user,
                        dispatchPlan,
                        "corr-guard-123",
                        "u***@example.com",
                        "forgot_password"));

        assertEquals("Password reset email dispatch requires an active transaction synchronization context", exception.getUserMessage());
        TransactionSynchronizationManager.initSynchronization();
        try {
            ApplicationException syncOnlyException = assertThrows(
                    ApplicationException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            passwordResetService,
                            "registerPublicResetDispatchAfterCommit",
                            user,
                            dispatchPlan,
                            "corr-guard-123",
                            "u***@example.com",
                            "forgot_password"));
            assertEquals(
                    "Password reset email dispatch requires an active transaction synchronization context",
                    syncOnlyException.getUserMessage());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertNull(requiredPropagationTemplate().execute(status -> ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "cleanupSupersededPublicResetTokensWithinActiveTransaction",
                null,
                41L,
                "corr-guard-123",
                "u***@example.com")));
        assertNull(requiredPropagationTemplate().execute(status -> ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "cleanupSupersededPublicResetTokensWithinActiveTransaction",
                user,
                null,
                "corr-guard-123",
                "u***@example.com")));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", null, 41L));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", user, null));
        requiredPropagationTemplate().executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "touchIssuedResetTokenForDispatchOrderingWithinActiveTransaction",
                null,
                "corr-guard-123",
                "u***@example.com"));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "lockUserForResetTokenCleanup", new Object[] {null}));
        assertNull(ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePublicResetStateAfterDispatchFailure",
                null,
                "corr-guard-123",
                "u***@example.com",
                "forgot_password"));

        verify(tokenRepository, never()).touchCreatedAt(anyLong(), any(Instant.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void privateHelperCoverageHandlesSnapshotAndRestoreBranches() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);

        when(userAccountRepository.lockById(101L)).thenReturn(Optional.empty());
        assertNull(requiredPropagationTemplate().execute(status -> ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "cleanupSupersededPublicResetTokensWithinActiveTransaction",
                user,
                41L,
                "corr-helper-123",
                "u***@example.com")));

        PasswordResetToken usedPriorToken = PasswordResetToken.digestOnly(
                user,
                AuthTokenDigests.passwordResetTokenDigest("used-prior"),
                Instant.now().plusSeconds(600));
        usedPriorToken.markUsed();
        ReflectionTestUtils.setField(usedPriorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(user, 41L))
                .thenReturn(Optional.of(usedPriorToken));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", user, 41L));

        PasswordResetToken expiredPriorToken = PasswordResetToken.digestOnly(
                user,
                AuthTokenDigests.passwordResetTokenDigest("expired-prior"),
                Instant.now().minusSeconds(60));
        ReflectionTestUtils.setField(expiredPriorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(user, 44L))
                .thenReturn(Optional.of(expiredPriorToken));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", user, 44L));

        PasswordResetToken blankDigestPriorToken = PasswordResetToken.digestOnly(user, "", Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(blankDigestPriorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(user, 42L))
                .thenReturn(Optional.of(blankDigestPriorToken));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", user, 42L));

        UserAccount transientUser = new UserAccount("transient@example.com", "hash", "Transient User");
        transientUser.setEnabled(true);
        PasswordResetToken transientPriorToken = PasswordResetToken.digestOnly(
                transientUser,
                AuthTokenDigests.passwordResetTokenDigest("transient-prior"),
                Instant.now().plusSeconds(600));
        ReflectionTestUtils.setField(transientPriorToken, "deliveredAt", Instant.now().minusSeconds(30));
        when(tokenRepository.findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(transientUser, 43L))
                .thenReturn(Optional.of(transientPriorToken));
        assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "capturePriorResetTokenSnapshot", transientUser, 43L));
        assertEquals(transientUser, ReflectionTestUtils.invokeMethod(passwordResetService, "lockUserForResetTokenCleanup", transientUser));

        Object invalidSnapshot = priorResetTokenSnapshot(101L, "", Instant.now().plusSeconds(600));
        ReflectionTestUtils.invokeMethod(passwordResetService, "restorePriorResetTokenWithinActiveTransaction", invalidSnapshot);
        ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePriorResetTokenWithinActiveTransaction",
                priorResetTokenSnapshot(null, AuthTokenDigests.passwordResetTokenDigest("missing-user-id"), Instant.now().plusSeconds(600)));
        ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePriorResetTokenWithinActiveTransaction",
                priorResetTokenSnapshot(101L, AuthTokenDigests.passwordResetTokenDigest("missing-expiry"), null));
        ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePriorResetTokenWithinActiveTransaction",
                priorResetTokenSnapshot(
                        101L,
                        AuthTokenDigests.passwordResetTokenDigest("missing-delivered-at"),
                        Instant.now().plusSeconds(600),
                        null));
        ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePriorResetTokenWithinActiveTransaction",
                priorResetTokenSnapshot(101L, AuthTokenDigests.passwordResetTokenDigest("expired-snapshot"), Instant.now().minusSeconds(60)));

        UserAccount disabledUser = new UserAccount("disabled@example.com", "hash", "Disabled User");
        disabledUser.setEnabled(false);
        ReflectionTestUtils.setField(disabledUser, "id", 102L);
        when(userAccountRepository.lockById(102L)).thenReturn(Optional.of(disabledUser));
        Object disabledSnapshot = priorResetTokenSnapshot(102L, AuthTokenDigests.passwordResetTokenDigest("disabled-prior"), Instant.now().plusSeconds(600));
        ReflectionTestUtils.invokeMethod(passwordResetService, "restorePriorResetTokenWithinActiveTransaction", disabledSnapshot);
        when(userAccountRepository.lockById(103L)).thenReturn(Optional.empty());
        ReflectionTestUtils.invokeMethod(
                passwordResetService,
                "restorePriorResetTokenWithinActiveTransaction",
                priorResetTokenSnapshot(103L, AuthTokenDigests.passwordResetTokenDigest("missing-prior"), Instant.now().plusSeconds(600)));

        TransactionTemplate failingCleanupTemplate = mock(TransactionTemplate.class);
        doThrow(new RuntimeException("cleanup unavailable"))
                .when(failingCleanupTemplate)
                .execute(any());
        ReflectionTestUtils.setField(passwordResetService, "tokenCleanupTransactionTemplate", failingCleanupTemplate);
        Object issuedResetToken = issuedResetToken(41L, "restorable-issued");
        Object validSnapshot = priorResetTokenSnapshot(101L, AuthTokenDigests.passwordResetTokenDigest("restorable-prior"), Instant.now().plusSeconds(600));

        assertEquals(
                Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "restorePriorResetTokenAfterCleanupFailure",
                        issuedResetToken,
                        validSnapshot,
                        "corr-helper-123",
                        "u***@example.com",
                        "forgot_password"));

        verify(tokenRepository, never()).saveAndFlush(argThat(token -> token != null
                && AuthTokenDigests.passwordResetTokenDigest("restorable-prior").equals(token.getTokenDigest())));
    }

    @Test
    void restorePriorResetTokenAfterCleanupFailureDeletesIssuedTokenBeforeRestore() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        when(tokenRepository.deleteByTokenDigest(AuthTokenDigests.passwordResetTokenDigest("issued-token"))).thenReturn(1);
        Instant deliveredAt = Instant.now().minusSeconds(60);

        assertEquals(
                Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "restorePriorResetTokenAfterCleanupFailure",
                        issuedResetToken(41L, "issued-token"),
                        priorResetTokenSnapshot(
                                101L,
                                AuthTokenDigests.passwordResetTokenDigest("prior-reset-token"),
                                Instant.now().plusSeconds(600),
                                deliveredAt),
                        "corr-fallback-restore-123",
                        "u***@example.com",
                        "forgot_password"));

        InOrder inOrder = inOrder(userAccountRepository, tokenRepository);
        inOrder.verify(userAccountRepository).lockById(101L);
        inOrder.verify(tokenRepository).deleteByTokenDigest(AuthTokenDigests.passwordResetTokenDigest("issued-token"));
        inOrder.verify(tokenRepository).saveAndFlush(argThat(token -> token != null
                && AuthTokenDigests.passwordResetTokenDigest("prior-reset-token").equals(token.getTokenDigest())
                && deliveredAt.equals(token.getDeliveredAt())));
    }

    @Test
    void restorePriorResetTokenAfterCleanupFailureSkipsRestoreWhenIssuedTokenDeleteFails() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.lockById(101L)).thenReturn(Optional.of(user));
        when(tokenRepository.deleteByTokenDigest(AuthTokenDigests.passwordResetTokenDigest("issued-token"))).thenReturn(0);

        assertEquals(
                Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "restorePriorResetTokenAfterCleanupFailure",
                        issuedResetToken(41L, "issued-token"),
                        priorResetTokenSnapshot(
                                101L,
                                AuthTokenDigests.passwordResetTokenDigest("prior-reset-token"),
                                Instant.now().plusSeconds(600)),
                        "corr-fallback-no-restore-123",
                        "u***@example.com",
                        "forgot_password"));

        verify(tokenRepository).deleteByTokenDigest(AuthTokenDigests.passwordResetTokenDigest("issued-token"));
        verify(tokenRepository, never()).saveAndFlush(argThat(token -> token != null
                && AuthTokenDigests.passwordResetTokenDigest("prior-reset-token").equals(token.getTokenDigest())));
    }

    @Test
    void privateHelperCoverageClassifiesPersistenceFailures() {
        assertEquals(
                Boolean.TRUE,
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "isPublicResetPersistenceFailure",
                        new ApplicationException(ErrorCode.SYSTEM_DATABASE_ERROR, "db unavailable")));
        assertEquals(
                Boolean.FALSE,
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "isPublicResetPersistenceFailure",
                        new ApplicationException(ErrorCode.SYSTEM_CONFIGURATION_ERROR, "mail disabled")));
    }

    @Test
    void concurrentForgotPasswordRequests_keepExactlyOneLatestResetToken() throws Exception {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 101L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        ReentrantLock issuanceLock = new ReentrantLock(true);
        when(userAccountRepository.lockById(101L)).thenAnswer(invocation -> {
            issuanceLock.lock();
            return Optional.of(user);
        });

        ConcurrentResetTokenState tokenState = new ConcurrentResetTokenState();
        tokenState.stub(tokenRepository, issuanceLock);
        List<String> dispatchedTokens = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            dispatchedTokens.add(invocation.getArgument(2));
            return null;
        }).when(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> passwordResetService.requestReset("user@example.com"));
            Future<?> second = executor.submit(() -> passwordResetService.requestReset("user@example.com"));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, dispatchedTokens.size());
        assertEquals(
                1,
                tokenState.activeTokenCount(),
                "Concurrent forgot-password requests must not cross-delete every valid reset token");
        String survivingDigest = tokenState.activeTokenDigests().iterator().next();
        assertTrue(
                dispatchedTokens.stream()
                        .map(AuthTokenDigests::passwordResetTokenDigest)
                        .anyMatch(survivingDigest::equals),
                "The surviving reset token digest should correspond to one of the dispatched reset links");
    }

    @Test
    void overlappingForgotAndAdminResetRequests_keepExactlyOneLatestResetToken() throws Exception {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        ReflectionTestUtils.setField(user, "id", 202L);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        ReentrantLock issuanceLock = new ReentrantLock(true);
        when(userAccountRepository.lockById(202L)).thenAnswer(invocation -> {
            issuanceLock.lock();
            return Optional.of(user);
        });

        ConcurrentResetTokenState tokenState = new ConcurrentResetTokenState();
        tokenState.stub(tokenRepository, issuanceLock);
        List<String> dispatchedTokens = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            dispatchedTokens.add(invocation.getArgument(2));
            return null;
        }).when(emailService).sendPasswordResetEmailRequired(eq("user@example.com"), eq("User"), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publicReset = executor.submit(() -> passwordResetService.requestReset("user@example.com"));
            Future<?> adminReset = executor.submit(() -> passwordResetService.requestResetByAdmin(user));
            publicReset.get(5, TimeUnit.SECONDS);
            adminReset.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, dispatchedTokens.size());
        assertEquals(
                1,
                tokenState.activeTokenCount(),
                "Overlapping public and admin reset issuance must leave exactly one valid reset token behind");
        String survivingDigest = tokenState.activeTokenDigests().iterator().next();
        assertTrue(
                dispatchedTokens.stream()
                        .map(AuthTokenDigests::passwordResetTokenDigest)
                        .anyMatch(survivingDigest::equals),
                "The surviving reset token digest should correspond to one of the dispatched reset links");
    }

    @Test
    void requestResetByAdminRequiresResetEmailDelivery() {
        UserAccount adminManagedUser = new UserAccount("managed@example.com", "hash", "Managed User");
        adminManagedUser.setEnabled(true);
        emailProperties.setSendPasswordReset(false);

        assertThrows(ApplicationException.class,
                () -> passwordResetService.requestResetByAdmin(adminManagedUser));

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).save(any(PasswordResetToken.class));
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetMasksMissingTransactionSynchronizationDuringDispatchRegistration() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ReflectionTestUtils.setField(passwordResetService, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

        assertDoesNotThrow(() -> passwordResetService.requestReset("user@example.com"));

        verify(lifecycleTemplate).execute(any());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendPasswordResetEmailRequired(anyString(), anyString(), anyString());
    }

    @Test
    void requestResetForSuperAdminMasksWhenResetEmailDeliveryDisabled() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        emailProperties.setSendPasswordReset(false);

        assertDoesNotThrow(() -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminClassifiesConfigurationFailuresInLogs() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        emailProperties.setSendPasswordReset(false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-config-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("reasonCode=RESET_DISPATCH_FAILURE")
                            && message.contains("failureClass=CONFIGURATION")
                            && message.contains("correlationId=corr-config-123")),
                    "Expected disabled delivery path to emit configuration failure classification");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
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
        inOrder.verify(tokenRepository).deleteByTokenDigest(anyString());
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
        }).when(tokenRepository).deleteByTokenDigest(anyString());
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        passwordResetService.requestResetForSuperAdmin("superadmin@example.com");

        assertTrue(deleteByUserInTx.get(), "deleteByUser should execute inside an active transaction");
        assertTrue(saveAndFlushInTx.get(), "saveAndFlush should execute inside an active transaction");
        assertTrue(cleanupInTx.get(), "cleanup deleteByTokenDigest should execute inside an active transaction");
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
                    messages.stream().anyMatch(message -> message.contains("failureClass=EMAIL_DISPATCH")),
                    "Expected dispatch failures to include email-delivery failure classification");
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
                .deleteByTokenDigest(anyString());

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
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("failureClass=TOKEN_CLEANUP")),
                    "Expected cleanup failures to emit cleanup failure classification for support triage");
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
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
    }

    @Test
    void requestResetForSuperAdminMasksTokenPersistenceFailure() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(tokenRepository)
                .saveAndFlush(any(PasswordResetToken.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-token-persistence-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("reasonCode=RESET_DISPATCH_FAILURE")
                            && message.contains("failureClass=TOKEN_PERSISTENCE")
                            && message.contains("correlationId=corr-token-persistence-123")),
                    "Expected token persistence failures to be classified for support triage");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        verify(tokenRepository).deleteByUser(superAdmin);
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
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
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
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
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminIgnoresTenantContextAndLogsGlobalIdentityScope() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-global-scope-123");
        request.addHeader("X-Company-Code", "TENANT_A");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("event=password_reset.scope")
                            && message.contains("operation=forgot_password_superadmin")
                            && message.contains("correlationId=corr-global-scope-123")
                            && message.contains("tenantContext=TENANT_A")
                            && message.contains("outcome=tenant_context_ignored")),
                    "Expected superadmin forgot flow to explicitly ignore tenant context under global-identity policy");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        verify(tokenRepository).deleteByUser(superAdmin);
        verify(tokenRepository).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService).sendSimpleEmail(eq("superadmin@example.com"), any(), any());
    }

    @Test
    void resetPasswordIgnoresTenantContextAndLogsGlobalIdentityScope() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        String rawToken = "token-value";
        PasswordResetToken token = PasswordResetToken.digestOnly(
                superAdmin,
                AuthTokenDigests.passwordResetTokenDigest(rawToken),
                Instant.now().plusSeconds(600));
        when(tokenRepository.findByTokenDigest(AuthTokenDigests.passwordResetTokenDigest(rawToken)))
                .thenReturn(Optional.of(token));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/reset");
        request.addHeader("X-Correlation-Id", "corr-reset-scope-123");
        request.addHeader("X-Company-Code", "TENANT_B");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.resetPassword(rawToken, "NewPass123", "NewPass123"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("event=password_reset.scope")
                            && message.contains("operation=reset_password")
                            && message.contains("correlationId=corr-reset-scope-123")
                            && message.contains("tenantContext=TENANT_B")
                            && message.contains("outcome=tenant_context_ignored")),
                    "Expected password reset to use global identity policy even when tenant context is present");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        verify(passwordService).resetPassword(superAdmin, "NewPass123", "NewPass123");
        verify(tokenBlacklistService).revokeAllUserTokens("superadmin@example.com");
        verify(refreshTokenService).revokeAllForUser("superadmin@example.com");
    }

    @Test
    void requestResetForSuperAdminMasksBlankTokenLifecycleResult() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any())).thenReturn("   ");
        ReflectionTestUtils.setField(passwordResetService, "tokenLifecycleTransactionTemplate", lifecycleTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-blank-token-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));

            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("reasonCode=RESET_DISPATCH_FAILURE")
                            && message.contains("exceptionClass=ApplicationException")
                            && message.contains("correlationId=corr-blank-token-123")),
                    "Expected blank token persistence to be fail-closed and masked in public forgot-password flow");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        verify(lifecycleTemplate).execute(any());
        verify(tokenRepository, never()).deleteByTokenDigest(anyString());
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestResetForSuperAdminMasksMissingLifecycleTransactionAndLogsReasonCode() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));

        TransactionTemplate lifecycleTemplate = mock(TransactionTemplate.class);
        when(lifecycleTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<String> callback = (TransactionCallback<String>) invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        ReflectionTestUtils.setField(passwordResetService, "tokenLifecycleTransactionTemplate", lifecycleTemplate);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-tx-missing-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("outcome=missing_transaction")
                            && message.contains("stage=issue")
                            && message.contains("correlationId=corr-tx-missing-123")),
                    "Expected lifecycle stage to emit transaction-missing evidence with correlation id");
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("reasonCode=RESET_DISPATCH_FAILURE")
                            && message.contains("exceptionClass=ApplicationException")),
                    "Expected missing transaction to be masked by public forgot-password response contract");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        verify(tokenRepository, never()).deleteByUser(any());
        verify(tokenRepository, never()).saveAndFlush(any(PasswordResetToken.class));
        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
    }

    @Test
    void requestResetForSuperAdminUsesRequestIdWhenCorrelationHeaderIsBlank() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "   ");
        request.addHeader("X-Request-Id", "req-fallback-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("correlationId=req-fallback-123")),
                    "Expected request-id header to be used when primary correlation header is blank");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdWhenAllHeadersAreBlank() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "   ");
        request.addHeader("X-Request-Id", " ");
        request.addHeader("X-Trace-Id", "\t");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when all correlation headers are blank");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdForOversizedHeader() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());
        String oversizedCorrelationId = "x".repeat(129);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", oversizedCorrelationId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains(oversizedCorrelationId)),
                    "Expected oversized correlation header to be rejected from structured logs");
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when correlation header exceeds max length");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdForPatternMismatchHeader() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "bad|pattern");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("bad|pattern")),
                    "Expected pattern-mismatched correlation header to be rejected in logs");
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when header contains disallowed characters");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdForEmbeddedNewlineHeader() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "corr-newline\nsegment");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().noneMatch(message -> message.contains("corr-newline")),
                    "Expected newline-bearing correlation header to be rejected in logs");
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when header contains an embedded newline");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetForSuperAdminFallsBackToGeneratedCorrelationIdForControlCharacterOnlyHeader() {
        UserAccount superAdmin = superAdminUser("superadmin@example.com");
        when(userAccountRepository.findByEmailIgnoreCase("superadmin@example.com"))
                .thenReturn(Optional.of(superAdmin));
        doThrow(new RuntimeException("smtp down"))
                .when(emailService)
                .sendSimpleEmail(eq("superadmin@example.com"), any(), any());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot/superadmin");
        request.addHeader("X-Correlation-Id", "\u0007");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestResetForSuperAdmin("superadmin@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.matches(".*correlationId=[0-9a-fA-F\\-]{36}.*")),
                    "Expected fallback UUID correlation id when trim collapses control-only header values");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetScopeLogRedactsMalformedTenantContext() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot");
        request.addHeader("X-Correlation-Id", "corr-request-scope-123");
        request.addHeader("X-Company-Code", "TENANT\nINJECT");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestReset("user@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("event=password_reset.scope")
                            && message.contains("operation=forgot_password")
                            && message.contains("correlationId=corr-request-scope-123")
                            && message.contains("tenantContext=<redacted>")),
                    "Expected malformed tenant context to be redacted in global-identity scope logs");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestResetScopeUsesLegacyCompanyHeaderWhenPrimaryHeaderMissing() {
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/password/forgot");
        request.addHeader("X-Correlation-Id", "corr-legacy-header-123");
        request.addHeader("X-Company-Id", "LEGACY_TENANT");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            List<String> messages = captureServiceLogMessages(
                    () -> passwordResetService.requestReset("user@example.com"));
            assertTrue(
                    messages.stream().anyMatch(message -> message.contains("event=password_reset.scope")
                            && message.contains("operation=forgot_password")
                            && message.contains("correlationId=corr-legacy-header-123")
                            && message.contains("tenantContext=LEGACY_TENANT")),
                    "Expected legacy company header to be observed when company context holder is empty");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void resolveTenantContextForObservabilityPrefersCompanyContextHolderValue() {
        CompanyContextHolder.setCompanyCode("HOLDER_TENANT");
        try {
            assertEquals(
                    "HOLDER_TENANT",
                    ReflectionTestUtils.invokeMethod(passwordResetService, "resolveTenantContextForObservability"));
        } finally {
            CompanyContextHolder.clear();
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void resolveTenantContextForObservabilityReturnsNullWhenRequestAttributesContainNoRequest() {
        ServletRequestAttributes attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        ReflectionTestUtils.setField(attributes, "request", null);
        RequestContextHolder.setRequestAttributes(attributes);
        CompanyContextHolder.clear();
        try {
            assertNull(ReflectionTestUtils.invokeMethod(passwordResetService, "resolveTenantContextForObservability"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void sanitizeTenantContextForLogCoversRedactionBranches() {
        assertEquals(
                "<empty>",
                ReflectionTestUtils.invokeMethod(passwordResetService, "sanitizeTenantContextForLog", (String) null));
        assertEquals(
                "<empty>",
                ReflectionTestUtils.invokeMethod(passwordResetService, "sanitizeTenantContextForLog", "   "));
        assertEquals(
                "<empty>",
                ReflectionTestUtils.invokeMethod(passwordResetService, "sanitizeTenantContextForLog", "\u0007"));
        assertEquals(
                "<redacted>",
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "sanitizeTenantContextForLog",
                        "X".repeat(65)));
        assertEquals(
                "<redacted>",
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "sanitizeTenantContextForLog",
                        "TENANT\nINJECT"));
        assertEquals(
                "<redacted>",
                ReflectionTestUtils.invokeMethod(
                        passwordResetService,
                        "sanitizeTenantContextForLog",
                        "TENANT\rINJECT"));
        assertEquals(
                "<redacted>",
                ReflectionTestUtils.invokeMethod(passwordResetService, "sanitizeTenantContextForLog", "bad|tenant"));
        assertEquals(
                "TENANT_01",
                ReflectionTestUtils.invokeMethod(passwordResetService, "sanitizeTenantContextForLog", "TENANT_01"));
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

    private Object issuedResetToken(Long id, String rawToken) {
        return instantiatePasswordResetServiceRecord("IssuedResetToken", new Class<?>[] {Long.class, String.class}, id, rawToken);
    }

    private Object priorResetTokenSnapshot(Long userId, String tokenDigest, Instant expiresAt) {
        return priorResetTokenSnapshot(userId, tokenDigest, expiresAt, Instant.now().minusSeconds(30));
    }

    private Object priorResetTokenSnapshot(Long userId, String tokenDigest, Instant expiresAt, Instant deliveredAt) {
        return instantiatePasswordResetServiceRecord(
                "PriorResetTokenSnapshot",
                new Class<?>[] {Long.class, String.class, Instant.class, Instant.class},
                userId,
                tokenDigest,
                expiresAt,
                deliveredAt);
    }

    private Object publicResetDispatchPlan(Object issuedResetToken, Object priorTokenSnapshot) {
        return instantiatePasswordResetServiceRecord(
                "PublicResetDispatchPlan",
                new Class<?>[] {issuedResetTokenClass(), priorResetTokenSnapshotClass()},
                issuedResetToken,
                priorTokenSnapshot);
    }

    private Object instantiatePasswordResetServiceRecord(String simpleName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> recordClass = Class.forName(PasswordResetService.class.getName() + "$" + simpleName);
            Constructor<?> constructor = recordClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to instantiate PasswordResetService." + simpleName, ex);
        }
    }

    private Class<?> issuedResetTokenClass() {
        try {
            return Class.forName(PasswordResetService.class.getName() + "$IssuedResetToken");
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(ex);
        }
    }

    private Class<?> priorResetTokenSnapshotClass() {
        try {
            return Class.forName(PasswordResetService.class.getName() + "$PriorResetTokenSnapshot");
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(ex);
        }
    }

    private static final class ConcurrentResetTokenState {
        private final AtomicLong idSequence = new AtomicLong();
        private final AtomicLong dispatchSequence = new AtomicLong();
        private final Map<Long, String> activeTokenDigests = new ConcurrentHashMap<>();
        private final Map<Long, Long> dispatchOrderByTokenId = new ConcurrentHashMap<>();

        void stub(PasswordResetTokenRepository tokenRepository, ReentrantLock issuanceLock) {
            doAnswer(invocation -> {
                PasswordResetToken token = invocation.getArgument(0);
                long id = idSequence.incrementAndGet();
                ReflectionTestUtils.setField(token, "id", id);
                activeTokenDigests.put(id, token.getTokenDigest());
                dispatchOrderByTokenId.putIfAbsent(id, dispatchSequence.incrementAndGet());
                return token;
            }).when(tokenRepository).saveAndFlush(any(PasswordResetToken.class));

            lenient().doAnswer(invocation -> {
                activeTokenDigests.clear();
                dispatchOrderByTokenId.clear();
                releaseLock(issuanceLock);
                return null;
            }).when(tokenRepository).deleteByUser(any(UserAccount.class));

            doAnswer(invocation -> {
                Long keepId = invocation.getArgument(1);
                activeTokenDigests.entrySet().removeIf(entry -> !entry.getKey().equals(keepId));
                releaseLock(issuanceLock);
                return 1;
            }).when(tokenRepository).deleteByUserAndIdNot(any(UserAccount.class), anyLong());

            lenient().doAnswer(invocation -> {
                Long tokenId = invocation.getArgument(0);
                if (tokenId != null) {
                    dispatchOrderByTokenId.put(tokenId, dispatchSequence.incrementAndGet());
                }
                return 1;
            }).when(tokenRepository).touchCreatedAt(anyLong(), any(Instant.class));

            lenient().doReturn(1).when(tokenRepository).markDeliveredAt(anyLong(), any(Instant.class));

            lenient().doAnswer(invocation -> {
                Long latestId = activeTokenDigests.keySet().stream()
                        .max((left, right) -> {
                            long leftOrder = dispatchOrderByTokenId.getOrDefault(left, left);
                            long rightOrder = dispatchOrderByTokenId.getOrDefault(right, right);
                            return Long.compare(leftOrder, rightOrder);
                        })
                        .orElse(null);
                if (latestId == null) {
                    return Optional.empty();
                }
                PasswordResetToken token = mock(PasswordResetToken.class);
                when(token.getId()).thenReturn(latestId);
                return Optional.of(token);
            }).when(tokenRepository).findTopByUserOrderByCreatedAtDescIdDesc(any(UserAccount.class));

            lenient().doAnswer(invocation -> {
                Long keepId = invocation.getArgument(1);
                Long latestPriorId = activeTokenDigests.keySet().stream()
                        .filter(tokenId -> !tokenId.equals(keepId))
                        .max((left, right) -> {
                            long leftOrder = dispatchOrderByTokenId.getOrDefault(left, left);
                            long rightOrder = dispatchOrderByTokenId.getOrDefault(right, right);
                            return Long.compare(leftOrder, rightOrder);
                        })
                        .orElse(null);
                if (latestPriorId == null) {
                    return Optional.empty();
                }
                PasswordResetToken token = mock(PasswordResetToken.class);
                when(token.getId()).thenReturn(latestPriorId);
                when(token.getTokenDigest()).thenReturn(activeTokenDigests.get(latestPriorId));
                when(token.getExpiresAt()).thenReturn(Instant.now().plusSeconds(600));
                when(token.isUsed()).thenReturn(false);
                when(token.isExpired(any(Instant.class))).thenReturn(false);
                return Optional.of(token);
            }).when(tokenRepository).findTopDeliveredByUserAndIdNotOrderByDeliveredAtDescCreatedAtDescIdDesc(
                    any(UserAccount.class),
                    anyLong());
        }

        int activeTokenCount() {
            return activeTokenDigests.size();
        }

        Set<String> activeTokenDigests() {
            return Set.copyOf(activeTokenDigests.values());
        }

        private void releaseLock(ReentrantLock issuanceLock) {
            if (issuanceLock == null) {
                return;
            }
            while (issuanceLock.isHeldByCurrentThread()) {
                issuanceLock.unlock();
            }
        }
    }
}
