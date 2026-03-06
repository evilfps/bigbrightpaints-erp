package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPasswordHistory;
import com.bigbrightpaints.erp.modules.auth.domain.UserPasswordHistoryRepository;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.web.ChangePasswordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserPasswordHistoryRepository passwordHistoryRepository;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private RefreshTokenService refreshTokenService;

    private PasswordEncoder passwordEncoder;
    private PasswordPolicy passwordPolicy;
    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        passwordPolicy = new PasswordPolicy();
        passwordService = new PasswordService(
                userAccountRepository,
                passwordHistoryRepository,
                passwordEncoder,
                passwordPolicy,
                tokenBlacklistService,
                refreshTokenService);
    }

    @Test
    void changePasswordRejectsHistoryReuse() {
        UserAccount user = userWithPassword("CurrentPass1!");
        UserPasswordHistory history = new UserPasswordHistory(user, passwordEncoder.encode("PriorPass1!"));
        when(passwordHistoryRepository.findTop5ByUserOrderByChangedAtDesc(user))
                .thenReturn(List.of(history));

        ChangePasswordRequest request = new ChangePasswordRequest("CurrentPass1!", "PriorPass1!", "PriorPass1!");

        assertThrows(ApplicationException.class, () -> passwordService.changePassword(user, request));
        verify(passwordHistoryRepository, never()).save(any());
        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(tokenBlacklistService, refreshTokenService);
    }

    @Test
    void changePasswordRejectsWeakPasswords() {
        UserAccount user = userWithPassword("CurrentPass1!");
        ChangePasswordRequest request = new ChangePasswordRequest("CurrentPass1!", "weak", "weak");

        assertThrows(ApplicationException.class, () -> passwordService.changePassword(user, request));
        verify(passwordHistoryRepository, never()).save(any());
        verifyNoInteractions(userAccountRepository);
        verifyNoInteractions(tokenBlacklistService, refreshTokenService);
    }

    @Test
    void changePasswordPersistsHistoryAndNewHash() {
        UserAccount user = userWithPassword("CurrentPass1!");
        when(passwordHistoryRepository.findTop5ByUserOrderByChangedAtDesc(user))
                .thenReturn(Collections.emptyList());
        when(passwordHistoryRepository.findByUserOrderByChangedAtDesc(user))
                .thenReturn(new ArrayList<>());

        ChangePasswordRequest request = new ChangePasswordRequest("CurrentPass1!", "NewPassword1!", "NewPassword1!");

        passwordService.changePassword(user, request);

        verify(passwordHistoryRepository).save(argThat(entry ->
                entry.getUser() == user && passwordEncoder.matches("CurrentPass1!", entry.getPasswordHash())));
        verify(userAccountRepository).save(user);
        verify(tokenBlacklistService).revokeAllUserTokens("user@bbp.dev");
        verify(refreshTokenService).revokeAllForUser("user@bbp.dev");
        assertTrue(passwordEncoder.matches("NewPassword1!", user.getPasswordHash()));
    }

    private UserAccount userWithPassword(String rawPassword) {
        return new UserAccount("user@bbp.dev", passwordEncoder.encode(rawPassword), "User");
    }
}
