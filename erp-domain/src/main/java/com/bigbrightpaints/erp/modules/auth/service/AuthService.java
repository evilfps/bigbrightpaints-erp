package com.bigbrightpaints.erp.modules.auth.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserPrincipal;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.AuthResponse;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserAccountRepository userAccountRepository;
    private final CompanyRepository companyRepository;
    private final JwtProperties properties;
    private final MfaService mfaService;
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
        this.tokenBlacklistService = tokenBlacklistService;
        this.auditService = auditService;
        this.tenantRuntimeEnforcementService = tenantRuntimeEnforcementService;
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = null;
        try {
            user = userAccountRepository.findByEmailIgnoreCase(request.email())
                    .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid credentials"));
            ensureEnabledForAuthentication(user);
            enforceLock(user);
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            ensureEnabledForAuthentication(principal.getUser());
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

    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.TokenRecord record = refreshTokenService.consume(request.refreshToken())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid refresh token"));
        String userEmail = record.userEmail();
        if (tokenBlacklistService.isUserTokenRevoked(userEmail, record.issuedAt())) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Refresh token revoked");
        }
        UserAccount user = userAccountRepository.findByEmailIgnoreCase(userEmail)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("User not found"));
        ensureEnabledForAuthentication(user);
        enforceLock(user);
        Company company = resolveCompanyForUser(user, request.companyCode());
        tenantRuntimeEnforcementService.enforceAuthOperationAllowed(
                company.getCode(),
                userEmail,
                "REFRESH_TOKEN");
        Map<String, Object> claims = Map.of("name", user.getDisplayName());
        String accessToken = tokenService.generateAccessToken(userEmail, company.getCode(), claims);
        String refreshToken = refreshTokenService.issue(userEmail,
                Instant.now().plusSeconds(properties.getRefreshTokenTtlSeconds()));
        return new AuthResponse("Bearer", accessToken, refreshToken, properties.getAccessTokenTtlSeconds(),
                company.getCode(), user.getDisplayName(), user.isMustChangePassword());
    }

    private Company resolveCompanyForUser(UserAccount user, String companyCode) {
        Company company = companyRepository.findByCodeIgnoreCase(companyCode)
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Company not found: " + companyCode));
        if (hasSuperAdminRole(user)) {
            return company;
        }
        boolean member = user.getCompanies().stream()
                .anyMatch(c -> c.getCode().equalsIgnoreCase(companyCode));
        if (!member) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("User not assigned to company: " + companyCode);
        }
        return company;
    }

    private boolean hasSuperAdminRole(UserAccount user) {
        return user.getRoles().stream()
                .anyMatch(role -> SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName()));
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

    private void ensureEnabledForAuthentication(UserAccount user) {
        if (user == null || !user.isEnabled()) {
            throw new ApplicationException(ErrorCode.AUTH_ACCOUNT_DISABLED, ErrorCode.AUTH_ACCOUNT_DISABLED.getDefaultMessage());
        }
    }

    public void logout(String refreshToken, String accessToken) {
        Claims accessTokenClaims = parseLogoutClaims(accessToken);
        String tokenUserEmail = extractTokenSubject(accessTokenClaims);

        if (tokenUserEmail != null) {
            revokeActiveSessions(tokenUserEmail);
        } else if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        }

        blacklistAccessToken(accessTokenClaims, tokenUserEmail);
    }

    private void revokeActiveSessions(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            return;
        }
        tokenBlacklistService.revokeAllUserTokens(userEmail);
        refreshTokenService.revokeAllForUser(userEmail);
    }

    private Claims parseLogoutClaims(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return null;
        }

        try {
            return tokenService.parse(accessToken);
        } catch (Exception ex) {
            log.warn("Failed to parse access token during logout; skipping token-derived identity operations", ex);
            return null;
        }
    }

    private String extractTokenSubject(Claims claims) {
        if (claims == null) {
            return null;
        }
        String subject = claims.getSubject();
        if (subject == null) {
            return null;
        }
        String normalized = subject.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void blacklistAccessToken(Claims claims, String userEmail) {
        if (claims == null) {
            return;
        }

        String tokenId = claims.getId();
        if (tokenId == null || claims.getExpiration() == null) {
            return;
        }
        Instant expiration = claims.getExpiration().toInstant();

        try {
            tokenBlacklistService.blacklistToken(
                    tokenId,
                    expiration,
                    userEmail,
                    "logout");
        } catch (Exception ex) {
            String actor = SecurityActorResolver.resolveActorWithSystemProcessFallback();
            log.warn("Failed to blacklist access token during logout (actor={}, tokenHash={}, expiresAt={})",
                    actor,
                    IdempotencyUtils.sha256Hex(tokenId, 12),
                    expiration,
                    ex);
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
        boolean locked = attempts >= MAX_FAILED_ATTEMPTS;
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION));
        }
        userAccountRepository.save(user);
        if (locked) {
            revokeActiveSessions(user.getEmail());
        }
    }
}
