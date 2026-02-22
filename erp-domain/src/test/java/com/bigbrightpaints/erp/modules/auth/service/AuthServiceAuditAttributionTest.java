package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.auth.web.ResetPasswordRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceAuditAttributionTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtTokenService tokenService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private JwtProperties properties;
    @Mock
    private MfaService mfaService;
    @Mock
    private PasswordService passwordService;
    @Mock
    private EmailService emailService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private AuditService auditService;
    @Mock
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(
                authenticationManager,
                tokenService,
                refreshTokenService,
                userAccountRepository,
                companyRepository,
                properties,
                mfaService,
                passwordService,
                emailService,
                tokenBlacklistService,
                auditService,
                tenantRuntimeEnforcementService);
    }

    @Test
    void loginAuthenticationFailure_usesTrimmedAuditIdentifiers() {
        LoginRequest request = new LoginRequest(" user@example.com ", "wrong-password", "  COMP-01  ", null, null);
        UserAccount user = new UserAccount("user@example.com", "hash", "User");
        user.setEnabled(true);
        when(userAccountRepository.findByEmailIgnoreCase(" user@example.com ")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditService).logAuthFailure(
                eq(AuditEvent.LOGIN_FAILURE),
                eq("user@example.com"),
                eq("COMP-01"),
                argThat((Map<String, String> map) ->
                        map != null && "Authentication failed".equals(map.get("reason"))));
    }

    @Test
    void loginMfaFailure_registersFailure_andFallsBackAuditReasonWhenBlankMessage() {
        LoginRequest request = new LoginRequest("user@example.com", "Passw0rd!", "ACME", null, null);
        UserAccount user = userWithCompany("user@example.com", "ACME");
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null);

        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(companyRepository.findByCodeIgnoreCase("ACME")).thenReturn(Optional.of(company("ACME")));
        doThrow(new InvalidMfaException("   "))
                .when(mfaService)
                .verifyDuringLogin(eq(user), any(), any());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidMfaException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        verify(userAccountRepository).save(user);
        verify(tenantRuntimeEnforcementService).enforceAuthOperationAllowed("ACME", "user@example.com", "LOGIN");
        verify(auditService).logAuthFailure(
                eq(AuditEvent.LOGIN_FAILURE),
                eq("user@example.com"),
                eq("ACME"),
                argThat((Map<String, String> map) ->
                        map != null && "Login failed".equals(map.get("reason"))));
    }

    @Test
    void loginRuntimeFailureFromTenantEnforcement_doesNotRegisterLockFailure_andAuditsReason() {
        LoginRequest request = new LoginRequest("user@example.com", "Passw0rd!", "ACME", null, null);
        UserAccount user = userWithCompany("user@example.com", "ACME");
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null);

        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(companyRepository.findByCodeIgnoreCase("ACME")).thenReturn(Optional.of(company("ACME")));
        doThrow(new IllegalStateException("Tenant runtime hold"))
                .when(tenantRuntimeEnforcementService)
                .enforceAuthOperationAllowed("ACME", "user@example.com", "LOGIN");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant runtime hold");

        assertThat(user.getFailedLoginAttempts()).isZero();
        verify(userAccountRepository, never()).save(any());
        verify(auditService).logAuthFailure(
                eq(AuditEvent.LOGIN_FAILURE),
                eq("user@example.com"),
                eq("ACME"),
                argThat((Map<String, String> map) ->
                        map != null && "Tenant runtime hold".equals(map.get("reason"))));
    }

    @Test
    void refreshSuccess_enforcesRuntimePolicy_andReturnsTokens() {
        RefreshTokenRequest request = new RefreshTokenRequest("refresh-old", "acme");
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        UserAccount user = userWithCompany("user@example.com", "ACME");
        RefreshTokenService.TokenRecord record = new RefreshTokenService.TokenRecord(
                "user@example.com",
                issuedAt,
                issuedAt.plus(1, ChronoUnit.DAYS));

        when(refreshTokenService.consume("refresh-old")).thenReturn(Optional.of(record));
        when(tokenBlacklistService.isUserTokenRevoked("user@example.com", issuedAt)).thenReturn(false);
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(companyRepository.findByCodeIgnoreCase("acme")).thenReturn(Optional.of(company("ACME")));
        when(tokenService.generateAccessToken(
                eq("user@example.com"),
                eq("ACME"),
                argThat((Map<String, Object> claims) ->
                        claims != null && user.getEmail().equals(claims.get("name")))))
                .thenReturn("access-new");
        when(refreshTokenService.issue(eq("user@example.com"), any(Instant.class))).thenReturn("refresh-new");
        when(properties.getRefreshTokenTtlSeconds()).thenReturn(3600L);
        when(properties.getAccessTokenTtlSeconds()).thenReturn(900L);

        var response = authService.refresh(request);

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isEqualTo("access-new");
        assertThat(response.refreshToken()).isEqualTo("refresh-new");
        assertThat(response.companyCode()).isEqualTo("ACME");
        assertThat(response.displayName()).isEqualTo(user.getEmail());
        verify(tenantRuntimeEnforcementService).enforceAuthOperationAllowed("ACME", "user@example.com", "REFRESH_TOKEN");
    }

    @Test
    void refreshRejectsWhenRefreshTokenAlreadyRevoked() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        RefreshTokenService.TokenRecord record = new RefreshTokenService.TokenRecord(
                "user@example.com",
                issuedAt,
                issuedAt.plus(1, ChronoUnit.DAYS));
        when(refreshTokenService.consume("refresh-old")).thenReturn(Optional.of(record));
        when(tokenBlacklistService.isUserTokenRevoked("user@example.com", issuedAt)).thenReturn(true);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-old", "ACME")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Refresh token revoked");
    }

    @Test
    void logoutFallsBackToRevokeAll_andSkipsBlacklistWhenClaimsIncomplete() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(tokenService.parse("access-token")).thenReturn(claims);
        when(claims.getId()).thenReturn(null);

        authService.logout("   ", "access-token", "user@example.com");

        verify(refreshTokenService).revokeAllForUser("user@example.com");
        verify(tokenBlacklistService, never()).blacklistToken(any(), any(), any(), any());
    }

    @Test
    void logoutIgnoresTokenParseErrors() {
        when(tokenService.parse("bad-token")).thenThrow(new IllegalArgumentException("bad token"));

        authService.logout("refresh-token", "bad-token", "user@example.com");

        verify(refreshTokenService).revoke("refresh-token");
        verify(tokenBlacklistService, never()).blacklistToken(any(), any(), any(), any());
    }

    @Test
    void resetPasswordClearsLockAndRevokesIssuedTokens() {
        UserAccount user = userWithCompany("user@example.com", "ACME");
        user.setResetToken("token-123");
        user.setResetExpiry(Instant.now().plus(2, ChronoUnit.HOURS));
        user.setFailedLoginAttempts(4);
        user.setLockedUntil(Instant.now().plus(5, ChronoUnit.MINUTES));
        when(userAccountRepository.findByResetToken("token-123")).thenReturn(Optional.of(user));

        Map<String, Object> result = authService.resetPassword(
                new ResetPasswordRequest("token-123", "Passw0rd!new", "Passw0rd!new"));

        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "Password reset. Login now.");
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getResetToken()).isNull();
        assertThat(user.getResetExpiry()).isNull();
        verify(passwordService).resetPassword(user, "Passw0rd!new", "Passw0rd!new");
        verify(userAccountRepository).save(user);
        verify(tokenBlacklistService).revokeAllUserTokens("user@example.com");
        verify(refreshTokenService).revokeAllForUser("user@example.com");
        verify(emailService).sendPasswordResetConfirmation("user@example.com", user.getDisplayName());
    }

    @Test
    void resetPasswordRejectsExpiredToken() {
        UserAccount user = userWithCompany("user@example.com", "ACME");
        user.setResetToken("expired-token");
        user.setResetExpiry(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(userAccountRepository.findByResetToken("expired-token")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.resetPassword(
                new ResetPasswordRequest("expired-token", "NextPass!1", "NextPass!1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Token expired");
    }

    @Test
    void forgotPasswordPersistsResetTokenAndSendsEmail() {
        UserAccount user = userWithCompany("user@example.com", "ACME");
        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword(new com.bigbrightpaints.erp.modules.auth.web.ForgotPasswordRequest("user@example.com"));

        assertThat(user.getResetToken()).isNotBlank();
        assertThat(user.getResetExpiry()).isAfter(Instant.now());
        verify(userAccountRepository).save(user);
        verify(emailService).sendPasswordResetEmail(
                eq("user@example.com"),
                eq(user.getDisplayName()),
                eq(user.getResetToken()));
    }

    private UserAccount userWithCompany(String email, String companyCode) {
        UserAccount user = new UserAccount(email, "hash", "User");
        user.setEnabled(true);
        user.addCompany(company(companyCode));
        return user;
    }

    private Company company(String code) {
        Company company = new Company();
        company.setCode(code);
        company.setName("Company " + code);
        company.setTimezone("UTC");
        return company;
    }
}
