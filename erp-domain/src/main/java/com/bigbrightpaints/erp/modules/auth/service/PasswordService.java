package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPasswordHistory;
import com.bigbrightpaints.erp.modules.auth.domain.UserPasswordHistoryRepository;
import com.bigbrightpaints.erp.modules.auth.web.ChangePasswordRequest;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class PasswordService {

    private static final int PASSWORD_HISTORY_LIMIT = 5;

    private final UserAccountRepository userAccountRepository;
    private final UserPasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    public PasswordService(UserAccountRepository userAccountRepository,
                           UserPasswordHistoryRepository passwordHistoryRepository,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicy passwordPolicy) {
        this.userAccountRepository = userAccountRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional
    public void changePassword(UserAccount user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from current password");
        }
        applyNewPassword(user, request.newPassword());
    }

    @Transactional
    public void resetPassword(UserAccount user, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }
        applyNewPassword(user, newPassword);
    }

    private void applyNewPassword(UserAccount user, String newPassword) {
        List<String> violations = passwordPolicy.validate(newPassword);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Password does not meet policy: " + String.join(", ", violations));
        }
        ensureNotReused(user, newPassword);
        rememberCurrentPassword(user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userAccountRepository.save(user);
    }

    private void ensureNotReused(UserAccount user, String candidate) {
        List<UserPasswordHistory> recent = passwordHistoryRepository.findTop5ByUserOrderByChangedAtDesc(user);
        boolean reused = recent.stream()
                .anyMatch(entry -> passwordEncoder.matches(candidate, entry.getPasswordHash()));
        if (reused) {
            throw new IllegalArgumentException("Cannot reuse one of the last " + PASSWORD_HISTORY_LIMIT + " passwords");
        }
    }

    private void rememberCurrentPassword(UserAccount user) {
        if (!StringUtils.hasText(user.getPasswordHash())) {
            return;
        }
        passwordHistoryRepository.save(new UserPasswordHistory(user, user.getPasswordHash()));
        trimHistory(user);
    }

    private void trimHistory(UserAccount user) {
        List<UserPasswordHistory> ordered = passwordHistoryRepository.findByUserOrderByChangedAtDesc(user);
        if (ordered.size() <= PASSWORD_HISTORY_LIMIT) {
            return;
        }
        List<UserPasswordHistory> toDelete = ordered.subList(PASSWORD_HISTORY_LIMIT, ordered.size());
        passwordHistoryRepository.deleteAll(toDelete);
    }
}
