package com.bigbrightpaints.erp.modules.auth.service;

import ch.qos.logback.classic.Level;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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
        doThrow(com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Tenant runtime hold"))
                .when(tenantRuntimeEnforcementService)
                .enforceAuthOperationAllowed("ACME", "user@example.com", "LOGIN");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ApplicationException.class)
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
    void loginAllowsSuperAdminOutsideTenantMembership() {
        LoginRequest request = new LoginRequest("super-admin@example.com", "Passw0rd!", "TARGET", null, null);
        UserAccount user = userWithCompany("super-admin@example.com", "ROOT");
        Role superAdminRole = new Role();
        superAdminRole.setName("ROLE_SUPER_ADMIN");
        user.addRole(superAdminRole);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null);

        when(userAccountRepository.findByEmailIgnoreCase("super-admin@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(companyRepository.findByCodeIgnoreCase("TARGET")).thenReturn(Optional.of(company("TARGET")));
        when(tokenService.generateAccessToken(eq("super-admin@example.com"), eq("TARGET"), any(Map.class)))
                .thenReturn("access-new");
        when(refreshTokenService.issue(eq("super-admin@example.com"), any(Instant.class))).thenReturn("refresh-new");
        when(properties.getRefreshTokenTtlSeconds()).thenReturn(3600L);
        when(properties.getAccessTokenTtlSeconds()).thenReturn(900L);

        var response = authService.login(request);

        assertThat(response.companyCode()).isEqualTo("TARGET");
        assertThat(response.accessToken()).isEqualTo("access-new");
        assertThat(response.refreshToken()).isEqualTo("refresh-new");
        verify(tenantRuntimeEnforcementService).enforceAuthOperationAllowed(
                "TARGET",
                "super-admin@example.com",
                "LOGIN");
    }

    @Test
    void loginRejectsNonSuperAdminOutsideTenantMembership() {
        LoginRequest request = new LoginRequest("tenant-user@example.com", "Passw0rd!", "TARGET", null, null);
        UserAccount user = userWithCompany("tenant-user@example.com", "ROOT");
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");
        user.addRole(adminRole);
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(new UserPrincipal(user), null);

        when(userAccountRepository.findByEmailIgnoreCase("tenant-user@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(companyRepository.findByCodeIgnoreCase("TARGET")).thenReturn(Optional.of(company("TARGET")));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("User not assigned to company: TARGET");
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
                        claims != null && user.getDisplayName().equals(claims.get("name")))))
                .thenReturn("access-new");
        when(refreshTokenService.issue(eq("user@example.com"), any(Instant.class))).thenReturn("refresh-new");
        when(properties.getRefreshTokenTtlSeconds()).thenReturn(3600L);
        when(properties.getAccessTokenTtlSeconds()).thenReturn(900L);

        var response = authService.refresh(request);

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isEqualTo("access-new");
        assertThat(response.refreshToken()).isEqualTo("refresh-new");
        assertThat(response.companyCode()).isEqualTo("ACME");
        assertThat(response.displayName()).isEqualTo(user.getDisplayName());
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
                .isInstanceOf(ApplicationException.class)
                .hasMessage("Refresh token revoked");
    }

    @Test
    void logoutDerivesIdentityFromTokenSubject_whenRefreshingTokenIsAbsent() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        Instant expiresAt = Instant.parse("2026-01-01T00:05:00Z");
        when(tokenService.parse("access-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("token-user@example.com");
        when(claims.getId()).thenReturn("jti-logout");
        when(claims.getExpiration()).thenReturn(Date.from(expiresAt));

        authService.logout("   ", "access-token");

        verify(tokenBlacklistService).revokeAllUserTokens("token-user@example.com");
        verify(refreshTokenService).revokeAllForUser("token-user@example.com");
        verify(refreshTokenService, never()).revokeAllForUser("caller@example.com");
        verify(tokenBlacklistService).blacklistToken(
                "jti-logout",
                expiresAt,
                "token-user@example.com",
                "logout");
    }

    @Test
    void logoutLogsWarningWhenTokenBlacklistingFails() {
        Logger logger = (Logger) LoggerFactory.getLogger(AuthService.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logger.addAppender(logAppender);
        logAppender.start();
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        Instant expiresAt = Instant.parse("2026-01-01T00:05:00Z");
        when(tokenService.parse("access-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user@example.com");
        when(claims.getId()).thenReturn("jti-logout");
        when(claims.getExpiration()).thenReturn(Date.from(expiresAt));
        doThrow(new RuntimeException("store unavailable"))
                .when(tokenBlacklistService)
                .blacklistToken("jti-logout", expiresAt, "user@example.com", "logout");

        try {
            authService.logout("refresh-token", "access-token");
        } finally {
            logger.detachAppender(logAppender);
            logAppender.stop();
        }

        verify(tokenBlacklistService).revokeAllUserTokens("user@example.com");
        verify(refreshTokenService).revokeAllForUser("user@example.com");
        verify(tokenBlacklistService).blacklistToken("jti-logout", expiresAt, "user@example.com", "logout");
        assertThat(logAppender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("Failed to blacklist access token during logout");
                });
    }

    @Test
    void loginLockoutThreshold_revokesExistingSessions() {
        LoginRequest request = new LoginRequest("user@example.com", "wrong-password", "ACME", null, null);
        UserAccount user = userWithCompany("user@example.com", "ACME");
        user.setFailedLoginAttempts(4);

        when(userAccountRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(user.getLockedUntil()).isNotNull();
        verify(userAccountRepository).save(user);
        verify(tokenBlacklistService).revokeAllUserTokens("user@example.com");
        verify(refreshTokenService).revokeAllForUser("user@example.com");
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
