package com.bigbrightpaints.erp.modules.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.UnexpectedRollbackException;

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
                refreshTokenService);
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
}
