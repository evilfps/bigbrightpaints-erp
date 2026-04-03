# Auth Module

Last reviewed: 2026-03-30

This packet documents the **auth module** (`modules/auth`) and the core security infrastructure that underpins the authentication corridor. It covers login, refresh, logout, MFA, password management, must-change-password enforcement, token/session revocation, JWT-based tenant scoping, and the key security filters in the request pipeline.

## Ownership Summary

The auth module owns the **authentication corridor**: credential verification, token issuance, MFA lifecycle, password policy and reset, and token/session revocation for scoped accounts.

Core security infrastructure in `core/security/` owns the **request-pipeline enforcement**: JWT validation, company-context resolution, must-change-password corridor filtering, token blacklisting, security monitoring, and licensing validation at startup.

| Area | Package |
| --- | --- |
| Auth controllers | `modules/auth/controller/` |
| Auth services | `modules/auth/service/` |
| Auth DTOs | `modules/auth/web/` |
| Auth domain entities | `modules/auth/domain/` |
| Core security filters and services | `core/security/` |

## Primary Controllers and Routes

### AuthController — `/api/v1/auth`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | Public | Credential verification, MFA challenge, token issuance |
| POST | `/api/v1/auth/refresh-token` | Public | Refresh-token rotation, new access/refresh pair |
| POST | `/api/v1/auth/logout` | Authenticated | Revoke sessions and blacklist access token |
| GET | `/api/v1/auth/me` | Authenticated | Current user identity, roles, permissions, must-change-password flag |
| POST | `/api/v1/auth/password/change` | Authenticated | Authenticated password change (respects must-change-password skip) |
| POST | `/api/v1/auth/password/forgot` | Public | Password-reset email dispatch (rate-limited, scope-aware) |
| POST | `/api/v1/auth/password/reset` | Public | Token-based password reset with confirmation |

### MfaController — `/api/v1/auth/mfa`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/mfa/setup` | Authenticated | Begin TOTP enrollment (secret + QR URI + recovery codes) |
| POST | `/api/v1/auth/mfa/activate` | Authenticated | Confirm TOTP enrollment with first valid code |
| POST | `/api/v1/auth/mfa/disable` | Authenticated | Disable MFA (requires TOTP code or recovery code) |

### SuperAdminController — tenant admin support recovery

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `ROLE_SUPER_ADMIN` | Issue the canonical tenant-admin reset-link recovery action |

## Key Services

### AuthService

The central authentication orchestrator. Handles:

- **Login flow**: scope resolution → user lookup by email+scope → enable check → lock check → password verification → MFA verification → lockout reset → audit → token pair issuance.
- **Refresh flow**: scope resolution → consume refresh token (single-use, digest-based) → user-state re-validation → tenant runtime admission check → new token pair issuance.
- **Logout flow**: parse access token → revoke all user tokens and refresh tokens → blacklist the access token by ID.

Account lockout is enforced inline: after 5 consecutive failed login attempts (configurable via `MAX_FAILED_ATTEMPTS`), the account is locked for 15 minutes. On lock, all active sessions and refresh tokens are revoked immediately.

### RefreshTokenService

Issues and manages refresh tokens stored as digests. Key behavior:

- **Single-use**: each refresh token is consumed (deleted) on use and a new one is issued (rotation model).
- **Digest-based**: the raw token value is never stored; only its SHA-256 digest is persisted.
- **Scoped**: each refresh token is bound to an `authScopeCode` (company code or platform scope).
- **Cleanup**: expired tokens are cleaned up hourly via `@Scheduled`.

### MfaService

Implements TOTP-based multi-factor authentication:

- **TOTP algorithm**: HMAC-SHA1, 6-digit codes, 30-second time steps, ±1 step window.
- **Recovery codes**: 8 single-use alphanumeric recovery codes (10 characters each), BCrypt-hashed.
- **Secret encryption**: MFA secrets are stored encrypted via `CryptoService`, never in plaintext.
- **Lifecycle**: setup (generate secret + recovery codes) → activate (verify first code) → active during login → disable (verify TOTP or recovery code).

### PasswordService

Handles authenticated password changes and internal password reset:

- **Password policy**: enforced via `PasswordPolicy` service with configurable rules.
- **History enforcement**: cannot reuse any of the last 5 passwords.
- **Session revocation**: changing or resetting a password revokes all active tokens and refresh tokens for the user.
- **Must-change-password skip**: when `mustChangePassword` is true, the current-password check is skipped so users with temporary/forced passwords can set a new one without knowing the old one.

### PasswordResetService

Handles the forgot-password / reset-password flow:

- **Scope-aware**: requires `companyCode` to look up the user in the correct tenant scope.
- **Rate-limited**: uses `SecurityMonitoringService.checkRateLimit()` per email+scope+operation.
- **Token lifecycle**: reset tokens are 32-byte random values stored as digests, expire after 1 hour, single-use, and are cleaned up after use.
- **Email delivery**: dispatches password reset emails only when mail is configured and enabled (`erp.mail.enabled` + `erp.mail.send-password-reset`). If delivery fails, the issued token is cleaned up in a separate transaction.
- **Masked response**: the forgot-password endpoint always returns a success-like message regardless of whether the email exists, to prevent user enumeration.
- **Admin-initiated reset**: supports `requestResetByAdmin()` which also revokes active sessions and sends the reset email.

### TokenBlacklistService

Database-backed token revocation with two mechanisms:

- **Per-token blacklist**: stores individual JWT token IDs (`jti` claim) with expiration. Checked during JWT validation.
- **User-level revocation**: stores a timestamp per user; any token issued before that timestamp is considered revoked. Used during logout, password changes, and admin force-logout.
- **Cleanup**: expired blacklisted tokens and stale user-revocation records are cleaned up hourly.

### AuthTokenDigests

Centralizes token digest computation. All token types (refresh, password-reset) store only SHA-256 digests rather than raw values.

## Domain Entities

| Entity | Purpose |
| --- | --- |
| `UserAccount` | User identity, credentials, MFA state, must-change-password flag, lockout counters, company reference |
| `UserPrincipal` | Spring Security `UserDetails` adapter wrapping `UserAccount` |
| `RefreshToken` | Single-use refresh token record (digest, user, scope, issuedAt, expiresAt) |
| `BlacklistedToken` | Per-JWT revocation record (tokenId, expiration, userId, reason) |
| `UserTokenRevocation` | User-level revocation timestamp (revokedAt) |
| `PasswordResetToken` | Password-reset token record (digest, user, expiresAt, used, deliveredAt) |
| `MfaRecoveryCode` | Recovery code storage (embedded in UserAccount as hashed list) |
| `UserPasswordHistory` | Password reuse prevention (stores BCrypt hashes of recent passwords) |

## JWT and Token Model

### Access Token

- **Format**: JWT (HS256 signed).
- **Claims**: `sub` (user publicId), `companyCode`, `name`, `email`, `iatMs`, `jti` (unique token ID), standard `iat`/`exp`.
- **TTL**: configurable via `jwt.access-token-ttl-seconds`, defaults to **900 seconds (15 minutes)**.
- **Secret**: HMAC key derived from `jwt.secret` (minimum 32 bytes / 256 bits). Unsafe static secrets are rejected at startup unless running in test-only profile context.
- **Validation**: `JwtAuthenticationFilter` validates the JWT, loads the `UserAccount` via `UserAccountDetailsService`, and sets the Spring Security context.

### Refresh Token

- **Format**: opaque UUID-based token, stored as SHA-256 digest.
- **TTL**: configurable via `jwt.refresh-token-ttl-seconds`, defaults to **2,592,000 seconds (30 days)**.
- **Rotation**: single-use; consumed on refresh and replaced with a new token.
- **Scope binding**: each refresh token is bound to an `authScopeCode` that must match the requested scope on refresh.

## Security Filter Chain

The filters execute in this order (configured in `SecurityConfig`):

1. **JwtAuthenticationFilter** — validates JWT from `Authorization: Bearer` header, resolves `UserAccount` + roles/permissions, sets `SecurityContext`.
2. **CompanyContextFilter** — reads `companyCode` from JWT claims, validates against header, enforces company lifecycle state, performs tenant runtime admission, sets `CompanyContextHolder`. Rejects legacy `X-Company-Id` header.
3. **MustChangePasswordCorridorFilter** — blocks all requests except the must-change-password corridor when `mustChangePassword` is true.

### SecurityConfig Behavior

- **Stateless sessions**: `SessionCreationPolicy.STATELESS` (no server-side HTTP sessions).
- **CSRF disabled**: all authentication is token-based; no session cookies are used.
- **CORS**: enabled via Spring defaults.
- **Public endpoints**: `/api/v1/auth/login`, `/api/v1/auth/refresh-token`, `/api/v1/auth/password/forgot`, `/api/v1/auth/password/reset`, `/api/v1/changelog`, actuator health.
- **Swagger**: conditionally public when `erp.security.swagger-public=true` and not in `prod` profile.
- **Role hierarchy**: `ROLE_SUPER_ADMIN > ROLE_ADMIN`.

## Must-Change-Password Corridor

When a user's `mustChangePassword` flag is true, the `MustChangePasswordCorridorFilter` restricts all requests except:

| Method | Allowed Paths |
| --- | --- |
| GET/HEAD | `/api/v1/auth/me` |
| POST | `/api/v1/auth/password/change`, `/api/v1/auth/logout`, `/api/v1/auth/refresh-token` |
| OPTIONS | All paths (CORS preflight) |

All other requests receive `403 Forbidden` with `PASSWORD_CHANGE_REQUIRED` reason and `mustChangePassword: true` in the response body.

The password-change endpoint (`PasswordService.changePassword`) skips the current-password verification when `mustChangePassword` is true, allowing users with forced/temporary passwords to set a new one.

## MFA Implementation

### Enrollment Flow

1. **Setup** (`POST /api/v1/auth/mfa/setup`): generates a Base32 TOTP secret, 8 recovery codes, and a QR URI (`otpauth://totp/...`). The secret is encrypted before storage. MFA is not yet enabled.
2. **Activate** (`POST /api/v1/auth/mfa/activate`): user provides a valid TOTP code to prove authenticator app is configured. MFA is enabled.
3. **Disable** (`POST /api/v1/auth/mfa/disable`): user provides either a valid TOTP code or a recovery code. MFA is disabled and secret/recovery codes are cleared.

### Login MFA Verification

During login, `MfaService.verifyDuringLogin()` checks:

- If MFA is not enabled: skip verification, proceed with login.
- If MFA is enabled and no code is provided: throw `MfaRequiredException`.
- If MFA is enabled and a TOTP code is provided: validate against the decrypted secret with ±1 time-step window.
- If MFA is enabled and a recovery code is provided: consume it (one-time use, BCrypt match).
- Invalid TOTP or recovery code: throw `InvalidMfaException`.

Both `MfaRequiredException` and `InvalidMfaException` count as failed login attempts for lockout purposes.

## Password Reset Flow

### Forgot-Password (Public)

1. Client calls `POST /api/v1/auth/password/forgot` with `{ email, companyCode }`.
2. `PasswordResetService.requestReset()` resolves the scope, checks rate limits, looks up the user by email+scope.
3. If the user exists and is enabled: issues a reset token (digest-based), dispatches a password-reset email.
4. Response is always a success-like message: `"If the email exists, a reset link has been sent"`.
5. **Caveat**: if mail delivery is disabled or fails, the reset token is cleaned up. The user receives no email and cannot reset.

### Reset Password (Public)

1. Client calls `POST /api/v1/auth/password/reset` with `{ token, newPassword, confirmPassword }`.
2. Token is looked up by digest, validated (not used, not expired, user enabled).
3. Password policy and history are enforced.
4. Password is changed, all active sessions are revoked, reset token is consumed, all other reset tokens for the user are deleted.

### Admin-Initiated Reset

`PasswordResetService.requestResetByAdmin()` is available for super-admin force-reset operations. It also revokes active sessions immediately.

## Token and Session Revocation

### Logout Behavior

`AuthService.logout()`:

1. Parses the access token (best-effort; continues even if token is expired or malformed).
2. If the user public ID is extracted: revokes **all** user tokens (user-level revocation) and **all** refresh tokens for that user.
3. Blacklists the individual access token by its `jti` claim.

### Other Revocation Triggers

- **Password change**: revokes all active tokens + refresh tokens.
- **Password reset**: revokes all active tokens + refresh tokens.
- **Account lockout** (5 failed attempts): revokes all active tokens + refresh tokens.
- **Admin force-logout** (`SuperAdminController`): revokes all user tokens + refresh tokens.
- **Admin force-reset-password**: revokes all user tokens + refresh tokens.

### Revocation Checking

- Access tokens: checked against `BlacklistedToken` (per-token) and `UserTokenRevocation` (user-level timestamp) during JWT validation.
- Refresh tokens: checked against user-level revocation timestamp during refresh.

## Auth Scope Model

`AuthScopeService` distinguishes two scope types:

- **Tenant scope**: a company code (e.g., `ACME`). All tenant users authenticate within their company scope.
- **Platform scope**: defaults to `PLATFORM` (configurable via `auth.platform.code` system setting). Super-admin users authenticate in platform scope.

Login and refresh both require a `companyCode` parameter. The user must exist within that exact scope (email + authScopeCode lookup). This prevents cross-tenant authentication.

## Account Lockout

- **Threshold**: 5 consecutive failed attempts (password failure or MFA failure).
- **Lockout duration**: 15 minutes.
- **Effect**: `LockededException` is thrown during `enforceLock()` check, which runs before password verification in both login and refresh flows.
- **Auto-reset**: successful login clears the failed-attempts counter and lockout timestamp.
- **Side effect**: hitting the lockout threshold immediately revokes all active sessions and refresh tokens for the user.

## Security Monitoring

`SecurityMonitoringService` provides:

- **Rate limiting**: `checkRateLimit(identifier)` — configurable per-minute limit (default 100). Used by password-reset operations.
- **Brute-force tracking**: in-memory tracking of failed login attempts by username and IP.
- **Suspicious-activity scoring**: accumulates scores for IPs and users; triggers alerts at configurable threshold.
- **IP/user blocking**: in-memory blocks with configurable duration.

### Caveat: In-Memory Monitoring State

> **⚠ The security monitoring tracking maps (`failedLoginAttempts`, `blockedIPs`, `blockedUsers`, `suspiciousActivityScores`) are stored in-memory (`ConcurrentHashMap`).** This means:
>
> - All monitoring state is **lost on server restart**.
> - State is **not shared across instances** in a multi-node deployment.
> - IP and user blocks are **not persistent** — a restart clears all blocks.
> - The per-minute request counts reset every 60 seconds via scheduled cleanup.
>
> The primary brute-force protection for authentication is the database-backed `UserAccount.lockedUntil` field and `failedLoginAttempts` counter, which **do** survive restarts and are shared across instances. The in-memory monitoring layer provides supplementary detection and alerting.

## Licensing Guard

`LicensingGuard` runs at application startup (`ApplicationRunner`):

- **When `erp.licensing.enforce=true`**: requires `ERP_LICENSE_PRODUCT_ID` and `ERP_LICENSE_KEY` to be configured; fails startup if missing.
- **When `erp.licensing.enforce=false`** (default): logs a warning that licensing enforcement is disabled.
- **Access token**: `ERP_LICENSE_ACCESS_TOKEN` is optional; if absent, remote activation checks are skipped.

### Caveat: Startup-Only Check

> **⚠ Licensing is enforced only at startup time.** The guard validates the license key presence during `ApplicationRunner.run()`. It does **not** perform ongoing license validation during request processing. Runtime quota enforcement is handled separately by `TenantRuntimeEnforcementService`.

## Cross-Module Boundaries

| Boundary | Direction | Description |
| --- | --- | --- |
| auth → company | dependency | `AuthService` calls `TenantRuntimeRequestAdmissionService` for login/refresh admission checks |
| auth → company | dependency | `CompanyContextFilter` enforces tenant lifecycle and runtime admission on every request |
| company → auth | dependency | `SuperAdminTenantControlPlaneService` calls `RefreshTokenService` and `TokenBlacklistService` for force-logout |
| auth → rbac | read | `AuthService` reads user roles for JWT claims; `AuthController.me()` resolves permissions |
| auth → core/security | dependency | `AuthService` uses `TokenBlacklistService`, `JwtTokenService`, `AuthScopeService` |
| auth → core/audit | dependency | `AuthService` and `PasswordResetService` emit audit events |
| auth → core/notification | dependency | `PasswordResetService` dispatches password-reset emails |

## Known Caveats

1. **Security monitoring is in-memory** (see above): not persistent, not shared across instances. Do not rely on it as the sole brute-force protection.
2. **Licensing is startup-only**: no per-request license check. Runtime quotas are enforced by tenant runtime services instead.
3. **Password-reset delivery depends on mail configuration**: if `erp.mail.enabled=false` or `erp.mail.send-password-reset=false`, forgot-password requests will silently fail to deliver emails. The endpoint still returns a success-like response.
4. **Must-change-password is a flag, not a policy**: there is no automatic expiration that sets this flag. It must be set explicitly by admin actions or provisioning flows.
5. **MFA disable requires a valid code**: if a user loses access to their authenticator and exhausts all recovery codes, they need an admin intervention to reset MFA.
6. **Access tokens cannot be reliably revoked before expiration** for requests already in flight: the blacklist check happens per-request, but a very fast request might pass the filter before the blacklist is written. This is a standard JWT trade-off mitigated by short access-token TTL (15 minutes).
7. **Retired identity aliases must stay absent**: `GET /api/v1/auth/me` is the only supported bootstrap read surface; old profile aliases must not be treated as current contract.

## Cross-References

- [docs/modules/company.md](company.md) — company/tenant module (lifecycle, runtime admission, module gating)
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — canonical module inventory
- [docs/adrs/ADR-002-multi-tenant-auth-scoping.md](../adrs/ADR-002-multi-tenant-auth-scoping.md) — ADR for multi-tenant auth scoping via JWT company claims
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — overall architecture reference
- [docs/SECURITY.md](../SECURITY.md) — security review policy and R2 escalation
- [docs/RELIABILITY.md](../RELIABILITY.md) — reliability posture
- [docs/flows/auth-identity.md](../flows/auth-identity.md) — canonical auth/identity flow (behavioral entrypoint)
