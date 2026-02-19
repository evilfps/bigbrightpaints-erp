package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.AuthResponse;
import com.bigbrightpaints.erp.modules.auth.web.ForgotPasswordRequest;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.auth.web.ResetPasswordRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserAccountRepository userAccountRepository;
    private final CompanyRepository companyRepository;
    private final JwtProperties properties;
    private final MfaService mfaService;
    private final PasswordService passwordService;
    private final EmailService emailService;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuditService auditService;
    private final TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenService tokenService,
                       RefreshTokenService refreshTokenService,
                       UserAccountRepository userAccountRepository,
                       CompanyRepository companyRepository,
                       JwtProperties properties,
                       MfaService mfaService,
                       PasswordService passwordService,
                       EmailService emailService,
                       TokenBlacklistService tokenBlacklistService,
                       AuditService auditService,
                       TenantRuntimeEnforcementService tenantRuntimeEnforcementService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.userAccountRepository = userAccountRepository;
        this.companyRepository = companyRepository;
        this.properties = properties;
        this.mfaService = mfaService;
        this.passwordService = passwordService;
        this.emailService = emailService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.auditService = auditService;
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = null;
        try {
            user = userAccountRepository.findByEmailIgnoreCase(request.email())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
            enforceLock(user);
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            Company company = resolveCompanyForUser(principal.getUser(), request.companyCode());
            tenantRuntimeEnforcementService.enforceAuthOperationAllowed(
                    company.getCode(),
                    user.getEmail(),
                    "LOGIN");
            mfaService.verifyDuringLogin(principal.getUser(), request.mfaCode(), request.recoveryCode());
            resetLock(user);
            auditService.logAuthSuccess(AuditEvent.LOGIN_SUCCESS, user.getEmail(),
                    company.getCode(), Map.of("companyCode", company.getCode()));
            Map<String, Object> claims = new HashMap<>();
            claims.put("name", principal.getUser().getDisplayName());
            String accessToken = tokenService.generateAccessToken(principal.getUsername(), company.getCode(), claims);
            String refreshToken = refreshTokenService.issue(principal.getUsername(),
                    Instant.now().plusSeconds(properties.getRefreshTokenTtlSeconds()));
            return new AuthResponse("Bearer", accessToken, refreshToken, properties.getAccessTokenTtlSeconds(),
                    company.getCode(), principal.getUser().getDisplayName(), user.isMustChangePassword());
        } catch (AuthenticationException ex) {
            if (user != null) {
                registerFailure(user);
            }
            auditService.logAuthFailure(
                    AuditEvent.LOGIN_FAILURE,
                    normalizeAuditIdentifier(request.email()),
                    normalizeAuditIdentifier(request.companyCode()),
                    Map.of("reason", "Authentication failed"));
            throw ex;
        } catch (RuntimeException ex) {
            if (user != null && isMfaFailure(ex)) {
                registerFailure(user);
            }
            String reason = ex.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = "Login failed";
            }
            auditService.logAuthFailure(
                    AuditEvent.LOGIN_FAILURE,
                    normalizeAuditIdentifier(request.email()),
                    normalizeAuditIdentifier(request.companyCode()),
                    Map.of("reason", reason));
            throw ex;
        }
    }

    // Forgot Password
    public void forgotPassword(ForgotPasswordRequest request) {
        userAccountRepository.findByEmailIgnoreCase(request.email()).ifPresent(user -> {
            String resetToken = UUID.randomUUID().toString();
            user.setResetToken(resetToken);
            user.setResetExpiry(Instant.now().plus(Duration.ofHours(1)));
            userAccountRepository.save(user);
            emailService.sendPasswordResetEmail(user.getEmail(), user.getDisplayName(), resetToken);
        });
    }

    public Map<String, Object> resetPassword(ResetPasswordRequest request) {
        UserAccount user = userAccountRepository.findByResetToken(request.token())
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired token"));
        if (user.getResetExpiry().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Token expired");
        }
        passwordService.resetPassword(user, request.newPassword(), request.confirmPassword());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setResetToken(null);
        user.setResetExpiry(null);
        userAccountRepository.save(user);
        tokenBlacklistService.revokeAllUserTokens(user.getEmail());
        refreshTokenService.revokeAllForUser(user.getEmail());
        emailService.sendPasswordResetConfirmation(user.getEmail(), user.getDisplayName());
        return Map.of("success", true, "message", "Password reset. Login now.");
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.TokenRecord record = refreshTokenService.consume(request.refreshToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));
        String userEmail = record.userEmail();
        if (tokenBlacklistService.isUserTokenRevoked(userEmail, record.issuedAt())) {
            throw new IllegalArgumentException("Refresh token revoked");
        }
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("User account is disabled");
        }
        enforceLock(user);
        Company company = resolveCompanyForUser(user, request.companyCode());
        tenantRuntimeEnforcementService.enforceAuthOperationAllowed(
                company.getCode(),
                userEmail,
                "REFRESH_TOKEN");
        Map<String, Object> claims = Map.of("name", userEmail);
        String accessToken = tokenService.generateAccessToken(userEmail, company.getCode(), claims);
        String refreshToken = refreshTokenService.issue(userEmail,
                Instant.now().plusSeconds(properties.getRefreshTokenTtlSeconds()));
        return new AuthResponse("Bearer", accessToken, refreshToken, properties.getAccessTokenTtlSeconds(),
                company.getCode(), userEmail, user.isMustChangePassword());
    }

    private Company resolveCompanyForUser(UserAccount user, String companyCode) {
        Company company = companyRepository.findByCodeIgnoreCase(companyCode)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyCode));
        boolean member = user.getCompanies().stream()
                .anyMatch(c -> c.getCode().equalsIgnoreCase(companyCode));
        if (!member) {
            throw new IllegalArgumentException("User not assigned to company: " + companyCode);
        }
        return company;
    }

    private boolean isMfaFailure(RuntimeException ex) {
        return ex instanceof InvalidMfaException || ex instanceof MfaRequiredException;
    }

    private String normalizeAuditIdentifier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public void logout(String refreshToken, String accessToken, String userEmail) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        } else if (userEmail != null && !userEmail.isBlank()) {
            refreshTokenService.revokeAllForUser(userEmail);
        }
        blacklistAccessToken(accessToken, userEmail);
    }

    private void blacklistAccessToken(String accessToken, String userEmail) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            Claims claims = tokenService.parse(accessToken);
            if (claims.getId() == null || claims.getExpiration() == null) {
                return;
            }
            tokenBlacklistService.blacklistToken(
                    claims.getId(),
                    claims.getExpiration().toInstant(),
                    userEmail,
                    "logout");
        } catch (Exception ignored) {
            // Best-effort logout: skip blacklist if token cannot be parsed.
        }
    }

    private void enforceLock(UserAccount user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new LockedException("Account locked until " + user.getLockedUntil());
        }
    }

    private void resetLock(UserAccount user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userAccountRepository.save(user);
    }

    private void registerFailure(UserAccount user) {
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION));
        }
        userAccountRepository.save(user);
    }
}
