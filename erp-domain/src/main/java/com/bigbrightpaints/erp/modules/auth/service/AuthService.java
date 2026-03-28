package com.bigbrightpaints.erp.modules.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.security.AuthScopeService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.security.JwtTokenService;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.security.TokenBlacklistService;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.exception.InvalidMfaException;
import com.bigbrightpaints.erp.modules.auth.exception.MfaRequiredException;
import com.bigbrightpaints.erp.modules.auth.web.AuthResponse;
import com.bigbrightpaints.erp.modules.auth.web.LoginRequest;
import com.bigbrightpaints.erp.modules.auth.web.RefreshTokenRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeRequestAdmissionService;

import io.jsonwebtoken.Claims;

@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final int MAX_FAILED_ATTEMPTS = 5;
  private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
  private static final String SUPER_ADMIN_ROLE = "ROLE_SUPER_ADMIN";

  private final JwtTokenService tokenService;
  private final RefreshTokenService refreshTokenService;
  private final UserAccountRepository userAccountRepository;
  private final CompanyRepository companyRepository;
  private final JwtProperties properties;
  private final MfaService mfaService;
  private final TokenBlacklistService tokenBlacklistService;
  private final AuditService auditService;
  private final TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService;
  private final PasswordEncoder passwordEncoder;
  private final AuthScopeService authScopeService;

  public AuthService(
      JwtTokenService tokenService,
      RefreshTokenService refreshTokenService,
      UserAccountRepository userAccountRepository,
      CompanyRepository companyRepository,
      JwtProperties properties,
      MfaService mfaService,
      TokenBlacklistService tokenBlacklistService,
      AuditService auditService,
      TenantRuntimeRequestAdmissionService tenantRuntimeRequestAdmissionService,
      PasswordEncoder passwordEncoder,
      AuthScopeService authScopeService) {
    this.tokenService = tokenService;
    this.refreshTokenService = refreshTokenService;
    this.userAccountRepository = userAccountRepository;
    this.companyRepository = companyRepository;
    this.properties = properties;
    this.mfaService = mfaService;
    this.tokenBlacklistService = tokenBlacklistService;
    this.auditService = auditService;
    this.tenantRuntimeRequestAdmissionService = tenantRuntimeRequestAdmissionService;
    this.passwordEncoder = passwordEncoder;
    this.authScopeService = authScopeService;
  }

  public AuthResponse login(LoginRequest request) {
    UserAccount user = null;
    boolean failedSecretValidation = false;
    try {
      String scopeCode = authScopeService.requireScopeCode(request.companyCode());
      user = requireScopedAccount(request.email(), scopeCode);
      ensureEnabledForAuthentication(user);
      enforceLock(user);
      if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
        failedSecretValidation = true;
        registerFailure(user);
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Invalid credentials");
      }
      Company company = resolveCompanyForScope(user, scopeCode);
      if (company != null) {
        tenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed(
            company.getCode(), user.getEmail(), "LOGIN");
      }
      mfaService.verifyDuringLogin(user, request.mfaCode(), request.recoveryCode());
      resetLock(user);
      auditService.logAuthSuccess(
          AuditEvent.LOGIN_SUCCESS, user.getEmail(), scopeCode, Map.of("companyCode", scopeCode));
      Map<String, Object> claims = new HashMap<>();
      claims.put("name", user.getDisplayName());
      claims.put("email", user.getEmail());
      Instant issuedAt = Instant.now();
      String accessToken =
          tokenService.generateAccessToken(
              user.getPublicId().toString(), scopeCode, claims, issuedAt);
      String refreshToken =
          refreshTokenService.issue(
              user.getPublicId(),
              scopeCode,
              issuedAt,
              issuedAt.plusSeconds(properties.getRefreshTokenTtlSeconds()));
      return new AuthResponse(
          "Bearer",
          accessToken,
          refreshToken,
          properties.getAccessTokenTtlSeconds(),
          scopeCode,
          user.getDisplayName(),
          user.isMustChangePassword());
    } catch (RuntimeException ex) {
      if (user != null && isMfaFailure(ex) && !failedSecretValidation) {
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
    String requestedScopeCode = authScopeService.requireScopeCode(request.companyCode());
    RefreshTokenService.TokenRecord record =
        refreshTokenService
            .consume(request.refreshToken())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Invalid refresh token"));
    if (!requestedScopeCode.equalsIgnoreCase(record.authScopeCode())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid refresh token");
    }
    String accountKey = record.userPublicId().toString();
    if (tokenBlacklistService.isUserTokenRevoked(accountKey, record.issuedAt())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Refresh token revoked");
    }
    UserAccount user =
        userAccountRepository
            .findByPublicId(record.userPublicId())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "User not found"));
    ensureEnabledForAuthentication(user);
    enforceLock(user);
    Company company = resolveCompanyForScope(user, requestedScopeCode);
    if (company != null) {
      tenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed(
          company.getCode(), user.getEmail(), "REFRESH_TOKEN");
    }
    Map<String, Object> claims = Map.of("name", user.getDisplayName());
    Instant issuedAt = Instant.now();
    String accessToken =
        tokenService.generateAccessToken(
            user.getPublicId().toString(), requestedScopeCode, claims, issuedAt);
    String refreshToken =
        refreshTokenService.issue(
            user.getPublicId(),
            requestedScopeCode,
            issuedAt,
            issuedAt.plusSeconds(properties.getRefreshTokenTtlSeconds()));
    return new AuthResponse(
        "Bearer",
        accessToken,
        refreshToken,
        properties.getAccessTokenTtlSeconds(),
        requestedScopeCode,
        user.getDisplayName(),
        user.isMustChangePassword());
  }

  private Company resolveCompanyForScope(UserAccount user, String scopeCode) {
    if (user == null || !scopeMatches(user, scopeCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid credentials");
    }
    if (authScopeService.isPlatformScope(scopeCode)) {
      if (!hasSuperAdminRole(user)) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Invalid credentials");
      }
      return null;
    }
    Company company = user.getCompany();
    if (company == null || !user.belongsToCompanyCode(scopeCode)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Invalid credentials");
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
      throw new ApplicationException(
          ErrorCode.AUTH_ACCOUNT_DISABLED, ErrorCode.AUTH_ACCOUNT_DISABLED.getDefaultMessage());
    }
  }

  public void logout(String refreshToken, String accessToken) {
    Claims accessTokenClaims = parseLogoutClaims(accessToken);
    UUID tokenUserPublicId = extractTokenSubject(accessTokenClaims);

    if (tokenUserPublicId != null) {
      revokeActiveSessions(tokenUserPublicId);
    } else if (refreshToken != null && !refreshToken.isBlank()) {
      refreshTokenService.revoke(refreshToken);
    }

    blacklistAccessToken(accessTokenClaims, tokenUserPublicId);
  }

  private void revokeActiveSessions(UUID userPublicId) {
    if (userPublicId == null) {
      return;
    }
    String accountKey = userPublicId.toString();
    tokenBlacklistService.revokeAllUserTokens(accountKey);
    refreshTokenService.revokeAllForUser(userPublicId);
  }

  private Claims parseLogoutClaims(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      return null;
    }

    try {
      return tokenService.parse(accessToken);
    } catch (Exception ex) {
      log.warn(
          "Failed to parse access token during logout; skipping token-derived identity operations",
          ex);
      return null;
    }
  }

  private UUID extractTokenSubject(Claims claims) {
    if (claims == null) {
      return null;
    }
    String subject = claims.getSubject();
    if (subject == null) {
      return null;
    }
    String normalized = subject.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(normalized);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private void blacklistAccessToken(Claims claims, UUID userPublicId) {
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
          tokenId, expiration, userPublicId != null ? userPublicId.toString() : null, "logout");
    } catch (Exception ex) {
      String actor = SecurityActorResolver.resolveActorWithSystemProcessFallback();
      log.warn(
          "Failed to blacklist access token during logout (actor={}, tokenHash={}, expiresAt={})",
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
      revokeActiveSessions(user.getPublicId());
    }
  }

  private UserAccount requireScopedAccount(String email, String scopeCode) {
    return userAccountRepository
        .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(normalizeEmail(email), scopeCode)
        .orElseThrow(
            () ->
                com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                    "Invalid credentials"));
  }

  private boolean scopeMatches(UserAccount user, String scopeCode) {
    return user != null
        && user.getAuthScopeCode() != null
        && user.getAuthScopeCode().equalsIgnoreCase(scopeCode);
  }

  private String normalizeEmail(String email) {
    if (email == null) {
      return null;
    }
    return email.trim().toLowerCase(Locale.ROOT);
  }
}
