# ERP Definition of Done Bible

> **⚠️ NON-CANONICAL**: This document is a legacy reference and is no longer maintained. The canonical documentation is now distributed across:
> - [docs/INDEX.md](INDEX.md) — canonical docs entrypoint
> - [docs/RECOMMENDATIONS.md](RECOMMENDATIONS.md) — authoritative recommendations register
> - [docs/flows/](flows/) — flow packets with current definitions of done
> - [docs/modules/](modules/) — module truth packets
>
> This file is retained for reference only and may be stale.

> **Version:** 1.0
> **Last Updated:** 2026-03-28
> **Purpose:** Authoritative reference for all 14 P0 flows. Every claim is verified against source code.

This document is the single source of truth for what each ERP flow does, who can do what, what state changes happen, what cannot happen, and what evidence proves it works.

---

## Flow 1: User Login + Tenant/Company Scoping

### A. Flow Identity
- **Flow Name**: User Login + Tenant/Company Scoping
- **Domain**: Authentication & Authorization
- **Portal**: All portals (Admin, Dealer, Super Admin)
- **P0 Status**: Foundation — every subsequent flow depends on authenticated identity
- **Source Modules**: auth, company, rbac, core/security
- **Primary Entity**: `UserAccount` (table: `app_users`)
- **Auth Model**: Scoped accounts — each `app_users` row belongs to exactly one company, keyed by `(email, auth_scope_code)`

### B. Business Objective
Authenticate a user and establish tenant context so they can access company-scoped business data. This is the gateway flow — without successful login, no other flow can execute.

**What breaks if wrong:**
- Cross-tenant data leakage (user sees another company's data)
- Unauthorized access (wrong role gets admin privileges)
- Session hijacking (tokens not properly invalidated)
- Brute-force attacks (no lockout mechanism)
- Tenant isolation failure (multi-tenant guarantees violated)

### C. Actors

| Actor | Role | Portal | Capability |
|-------|------|--------|------------|
| Super Admin | `ROLE_SUPER_ADMIN` | Platform control plane | Tenant CRUD, lifecycle, limits, modules, support, force-logout |
| Admin | `ROLE_ADMIN` | Admin portal | Full tenant business operations, user management |
| Accounting | `ROLE_ACCOUNTING` | Admin portal | Finance, HR, inventory operations, dispatch.confirm |
| Factory | `ROLE_FACTORY` | Admin portal | Factory, production, dispatch operations, factory.dispatch |
| Sales | `ROLE_SALES` | Admin portal | Sales operations, dealer management |
| Dealer | `ROLE_DEALER` | Dealer portal | Read-only own records, portal access |

**Role Hierarchy**: `ROLE_SUPER_ADMIN > ROLE_ADMIN` (configured in `SecurityConfig.roleHierarchy()`). All other roles are independent.

### D. Preconditions
1. User account exists in `app_users` with `enabled=true`
2. User's `auth_scope_code` matches the requested company scope
3. User's `company_id` FK points to a valid `companies` row
4. Company `lifecycle_state` is not `DEACTIVATED` (for login operations)
5. Tenant runtime state is `ACTIVE` or `HOLD` (HOLD allows GET requests only)
6. JWT secret configured with minimum 256-bit key (`erp.security.jwt.secret`)
7. `auth.platform.code` system setting defines platform scope (default: `PLATFORM`)

### E. Trigger
- **User-initiated**: POST `/api/v1/auth/login` with `{email, password, companyCode, mfaCode?, recoveryCode?}`
- **Token-initiated**: POST `/api/v1/auth/refresh-token` with `{refreshToken, companyCode}`
- **System-initiated**: Tenant onboarding creates initial admin account via `ScopedAccountBootstrapService`

### F. Input Contract

#### Login Request (`LoginRequest`)
| Field | Type | Required | Validation | Default |
|-------|------|----------|------------|---------|
| `email` | String | Yes | Valid email format | — |
| `password` | String | Yes | Non-blank | — |
| `companyCode` | String | Yes | Normalized to uppercase | — |
| `mfaCode` | String | No | 6 digits (if MFA enabled) | null |
| `recoveryCode` | String | No | 10 alphanumeric chars (if MFA enabled) | null |

#### Refresh Token Request (`RefreshTokenRequest`)
| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `refreshToken` | String | Yes | Valid UUID-format token |
| `companyCode` | String | Yes | Normalized to uppercase |

#### Auth Response (`AuthResponse`)
| Field | Type | Description |
|-------|------|-------------|
| `tokenType` | String | Always "Bearer" |
| `accessToken` | String | JWT access token |
| `refreshToken` | String | Single-use refresh token |
| `expiresIn` | long | Access token TTL in seconds |
| `companyCode` | String | Resolved auth scope code |
| `displayName` | String | User display name |
| `mustChangePassword` | boolean | Whether password change is required |

### G. Step-by-Step Lifecycle

#### Login Flow
1. Client sends `POST /api/v1/auth/login` with `{email, password, companyCode}`
2. `AuthScopeService.requireScopeCode()` normalizes companyCode to uppercase
3. `UserAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase()` loads scoped account
4. `ensureEnabledForAuthentication()` checks `user.isEnabled()`
5. `enforceLock()` checks `user.getLockedUntil()` — rejects if still locked
6. `PasswordEncoder.matches()` validates password against BCrypt hash
7. On password mismatch: `registerFailure()` increments `failedLoginAttempts`, locks at ≥5, revokes all sessions on lock
8. `resolveCompanyForScope()` validates user's company matches requested scope
9. `TenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed()` checks tenant runtime state and active user quota
10. `MfaService.verifyDuringLogin()` — skipped if MFA disabled; validates TOTP or recovery code if enabled
11. `resetLock()` resets `failedLoginAttempts=0`, `lockedUntil=null`
12. `AuditService.logAuthSuccess(LOGIN_SUCCESS)` records audit event
13. `JwtTokenService.generateAccessToken()` creates JWT with subject (publicId), companyCode claim, name, email
14. `RefreshTokenService.issue()` creates digest-only refresh token record
15. Returns `AuthResponse` with both tokens

#### Refresh Token Flow
1. Client sends `POST /api/v1/auth/refresh-token` with `{refreshToken, companyCode}`
2. `AuthScopeService.requireScopeCode()` normalizes companyCode
3. `RefreshTokenService.consume()` — looks up digest, deletes record (single-use)
4. Validates `authScopeCode` matches requested scope
5. `TokenBlacklistService.isUserTokenRevoked()` — checks user-level revocation
6. Loads `UserAccount` by public ID
7. Re-validates `enabled` and `locked` status
8. Re-validates tenant runtime state
9. Issues new access token and new refresh token
10. Returns `AuthResponse`

#### Logout Flow
1. Client sends `POST /api/v1/auth/logout?refreshToken=...` (authenticated)
2. `JwtTokenService.parse()` extracts claims from access token
3. `revokeActiveSessions()` — `TokenBlacklistService.revokeAllUserTokens()` + `RefreshTokenService.revokeAllForUser()`
4. `blacklistAccessToken()` — adds specific JTI to blacklist table

### H. State Machine

#### Authentication States
```
[UNAUTHENTICATED]
       │
       ├── login request ──→ [VALIDATING_CREDENTIALS]
       │                          │
       │                          ├── user not found ──→ [LOGIN_FAILED] → [UNAUTHENTICATED]
       │                          ├── user disabled ───→ [LOGIN_FAILED] → [UNAUTHENTICATED]
       │                          ├── account locked ──→ [LOGIN_FAILED] → [UNAUTHENTICATED]
       │                          ├── password wrong ──→ [LOGIN_FAILED] → [UNAUTHENTICATED]
       │                          │                        (increment failed attempts,
       │                          │                         lock at ≥5, revoke sessions)
       │                          │
       │                          ├── MFA enabled ──→ [MFA_PENDING]
       │                          │                       │
       │                          │                       ├── valid TOTP ────────┐
       │                          │                       ├── valid recovery ─────┤
       │                          │                       ├── no code provided ──→ [MFA_REQUIRED] → [UNAUTHENTICATED]
       │                          │                       └── invalid code ──────→ [MFA_FAILED] → [UNAUTHENTICATED]
       │                          │                                               (increment failed attempts)
       │                          │
       │                          ├── MFA disabled ──────────────────────────────┤
       │                          │                                              │
       │                          ├── tenant state invalid ──→ [LOGIN_FAILED]     │
       │                          ├── user quota exceeded ──→ [LOGIN_FAILED]     │
       │                          │                                              │
       │                          └──────────────────────────────────────────────┘
       │                                                                 │
       │                                                          [AUTHENTICATED]
       │                                                                 │
       │           ┌─────────────────────────────────────────────────────┘
       │           │
       │           ├── token refresh ──→ [AUTHENTICATED] (new tokens)
       │           ├── logout ─────────→ [UNAUTHENTICATED] (tokens revoked)
       │           ├── password change → [AUTHENTICATED] (sessions revoked, re-auth needed)
       │           ├── admin force-logout → [UNAUTHENTICATED] (all sessions revoked)
       │           └── account disabled → [UNAUTHENTICATED] (next request fails auth)
       │
       └── expired token ──→ [UNAUTHENTICATED] (401, client must refresh)
```

#### Key State Transitions

| Trigger | From | To | Guard |
|---------|------|----|-------|
| POST /auth/login with valid credentials | UNAUTHENTICATED | AUTHENTICATED | Password matches BCrypt, user enabled, not locked |
| POST /auth/login with MFA enabled | UNAUTHENTICATED | MFA_PENDING | MFA enabled on account |
| Valid TOTP or recovery code | MFA_PENDING | AUTHENTICATED | ±1 time-step drift for TOTP |
| Invalid MFA code | MFA_PENDING | MFA_FAILED | Neither TOTP nor recovery code valid |
| No MFA code provided | MFA_PENDING | MFA_REQUIRED | MFA enabled but no code in request |
| Failed login (wrong password) | UNAUTHENTICATED | LOGIN_FAILED | `failedLoginAttempts` incremented |
| 5+ failed attempts | LOGIN_FAILED | UNAUTHENTICATED (LOCKED) | `lockedUntil` = now + 15min, sessions revoked |
| POST /auth/logout | AUTHENTICATED | UNAUTHENTICATED | All tokens revoked |
| Admin force-logout | AUTHENTICATED | UNAUTHENTICATED | All tenant user sessions revoked |
| Tenant SUSPENDED | AUTHENTICATED | READ_ONLY | GET requests only allowed |
| Tenant DEACTIVATED | ANY | DENIED | All requests denied, irreversible |

**Irreversible transitions**: DEACTIVATED tenant state. Account lockout auto-expires after 15 minutes but is not manually reversible without password reset or wait.

### I. Validation Rules

#### Field-Level Validation

| Field | Rule | Error |
|-------|------|-------|
| `email` | Required, trimmed, lowercase-normalized | 400 INVALID_INPUT |
| `password` | Required, non-blank | 400 INVALID_INPUT |
| `companyCode` | Required, trimmed, uppercase-normalized | 400 INVALID_INPUT |
| `mfaCode` | Optional, 6 digits if provided | 401 AUTH_MFA_INVALID |
| `recoveryCode` | Optional, 10 alphanumeric chars if provided | 401 AUTH_MFA_INVALID |

#### Password Policy (`PasswordPolicy.java`)

| Rule | Description |
|------|-------------|
| Minimum length | 10 characters |
| Lowercase | At least one required |
| Uppercase | At least one required |
| Digit | At least one required |
| Special character | At least one required |
| Whitespace | Must not contain any |
| History | Cannot reuse any of last 5 passwords (`UserPasswordHistory`) |
| Change requires current password | Unless `mustChangePassword=true` |

#### Account Lockout Rules (`AuthService.java`)

| Parameter | Value |
|-----------|-------|
| `MAX_FAILED_ATTEMPTS` | 5 |
| `LOCKOUT_DURATION` | 15 minutes |
| Lock reset | On successful login |
| Session revocation on lock | Yes — all tokens revoked immediately |

#### MFA Validation (`MfaService.java`)

| Rule | Description |
|------|-------------|
| Algorithm | TOTP (RFC 6238, HmacSHA1) |
| Digits | 6 |
| Time step | 30 seconds |
| Drift tolerance | ±1 step (±30 seconds) |
| Recovery codes | 8 codes, 10 chars each, alphabet: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` |
| Secret storage | AES-encrypted via `CryptoService` |
| Secret format | 20 random bytes, Base32 encoded |

#### Auth Scope Validation (`AuthScopeService.java`)

| Rule | Description |
|------|-------------|
| Scope resolution | `requireScopeCode()` normalizes to uppercase |
| Platform scope | Default `PLATFORM`, configurable via `auth.platform.code` |
| Scope collision prevention | `updatePlatformScopeCode()` rejects codes matching tenant company codes |
| Account uniqueness | `(email, auth_scope_code)` unique constraint in `app_users` |

#### Tenant Runtime Enforcement (`TenantRuntimeEnforcementService.java`)

| Rule | Description |
|------|-------------|
| Active user quota | Enforced on login |
| Rate limiting | Per password-reset operation per scope+email |
| Concurrency quota | Per tenant, configurable |
| HOLD state | Blocks mutating requests |
| BLOCKED state | Blocks all requests |
| Policy cache | 15-second TTL, persisted in `system_settings` |
| Fail-closed | Hard limit auto-enabled if both limits disabled |

### J. Side Effects

| Event | Records Affected | Details |
|-------|-----------------|---------|
| Successful login | `UserAccount` update | Reset `failedLoginAttempts=0`, `lockedUntil=null` |
| Failed login | `UserAccount` update | Increment `failedLoginAttempts`, possibly set `lockedUntil` |
| Token issuance | `RefreshToken` insert | SHA-256 digest stored |
| Token refresh | `RefreshToken` delete + insert | Old consumed, new issued |
| Logout | `BlacklistedToken` insert, `UserTokenRevocation` upsert | JTI blacklisted, user revocation timestamp set |
| Lockout | `UserTokenRevocation` upsert, `RefreshToken` delete all | All sessions revoked |
| MFA enrollment | `UserAccount` update | Set `mfaSecret` (encrypted), `mfaRecoveryCodes` (hashed) |
| MFA activation | `UserAccount` update | Set `mfaEnabled=true` |
| Password change | `UserAccount` update, `UserPasswordHistory` insert | New hash, old hash archived, sessions revoked |
| Password reset | `PasswordResetToken` insert then delete | Digest-based, 1-hour TTL |
| Password reset email | Email delivery via `EmailService` | Async after commit |
| Audit trail | `AuditEvent` insert | LOGIN_SUCCESS/FAILURE, PASSWORD_RESET_REQUESTED/COMPLETED |

### K. Inventory Effect

**No inventory mutations.** Login/tenant selection is a pure authentication and authorization flow with no impact on physical or financial inventory.

### L. Accounting Effect

**No journal entries.** Login/tenant selection produces zero accounting entries. Authentication is session management, separate from business ledgers.

### M. Tax Effect

**No tax computation is triggered.** Login/tenant selection has no tax implications. No GST determination, no HSN/SAC handling, no tax posting.

### N. Failure Handling

| Failure Type | Response | Retriable | User Impact |
|--------------|----------|-----------|-------------|
| Invalid credentials | 400 INVALID_INPUT | No | Generic error (no leak) |
| Account disabled | AUTH_ACCOUNT_DISABLED | No | Clear error message |
| Account locked | LockedException | No | "Account locked until {timestamp}" |
| MFA required | MfaRequiredException | No | "Multi-factor authentication required" |
| MFA invalid | InvalidMfaException | No | "Invalid MFA verifier" |
| Tenant SUSPENDED | TENANT_LIFECYCLE_RESTRICTED | No | Read-only access for GET |
| Tenant DEACTIVATED | TENANT_LIFECYCLE_RESTRICTED | No | All access denied |
| User quota exceeded | TENANT_ACTIVE_USER_QUOTA_EXCEEDED | No | Login blocked |
| Password policy violation | 400 INVALID_INPUT | No | Specific violations listed |
| Password reuse | 400 INVALID_INPUT | No | "Cannot reuse last 5 passwords" |
| Token expired | 401 Unauthorized | Yes | Re-authenticate via refresh |
| Token blacklisted | Request proceeds unauthenticated | Yes | 401 on protected endpoints |
| Refresh token invalid | 400 INVALID_INPUT | No | "Invalid refresh token" |
| Refresh token revoked | 400 INVALID_INPUT | No | "Refresh token revoked" |
| Email service down | Token cleanup, retry possible | Yes | Password reset email not delivered |

### O. Auditability

Every authentication event generates an audit log entry:

| Event | Audit Type | Metadata |
|-------|-----------|----------|
| LOGIN_SUCCESS | Auth success | email, companyCode |
| LOGIN_FAILURE | Auth failure | email, companyCode, reason |
| PASSWORD_RESET_REQUESTED | Auth success | email, companyCode, correlationId, operation, outcome |
| PASSWORD_RESET_COMPLETED | Auth success | email, companyCode, correlationId, operation, outcome |
| ACCESS_GRANTED | Auth success | actor, tenantScope, targetRole |
| ACCESS_DENIED | Auth failure | actor, tenantScope, targetRole |
| CONFIGURATION_CHANGED | Auth success | actor, companyCode, policy details, auditChainId |

**Last login derivation**: Derived from `LOGIN_SUCCESS` audit event (no explicit `lastLoginAt` field on `UserAccount`).

**Correlation tracking**: Password reset operations carry `X-Correlation-Id`, `X-Request-Id`, or `X-Trace-Id` headers through the flow.

**Rate limit monitoring**: `SecurityMonitoringService` tracks password reset rate limits per scope+email.

### P. Reporting Impact

- **User Activity Reports**: LOGIN_SUCCESS/FAILURE events power user activity dashboards
- **Security Reports**: Failed login attempts, lockout events, token revocation events
- **Tenant Usage**: Active user counts, request counts, concurrent request metrics via `TenantRuntimeEnforcementService.snapshot()`
- **Audit Trail**: Full chain from login → business operation → logout via audit events

### Q. Completion Criteria

A login/tenant selection flow is considered complete when:
1. Valid JWT access token and refresh token issued
2. Company context established in `CompanyContextHolder`
3. User roles and permissions loaded into `SecurityContext`
4. `mustChangePassword` flag respected (corridor filter applied if true)
5. Audit event (LOGIN_SUCCESS) recorded
6. User identity available via `GET /api/v1/auth/me` endpoint

### R. Edge Cases

#### Concurrent Sessions
- **Scenario**: User logs in from multiple devices
- **Behavior**: Each login issues independent tokens. All sessions valid until revocation.
- **Risk**: No race condition on token issuance.

#### Expired Tokens Mid-Request
- **Scenario**: Access token expires during active request
- **Behavior**: `JwtAuthenticationFilter` catches `ExpiredJwtException`, continues filter chain unauthenticated → 401.
- **Recovery**: Frontend intercepts 401 and uses refresh token.

#### Wrong Company Code
- **Scenario**: User provides credentials valid for Company A but companyCode for Company B
- **Behavior**: `requireScopedAccount()` fails — uniqueness is `(email, auth_scope_code)`. Returns "Invalid credentials".
- **Security**: Prevents credential stuffing across tenants.

#### Super Admin Tenant Business Access
- **Scenario**: Super admin tries `/api/v1/sales/**` or other tenant business endpoints
- **Behavior**: `CompanyContextFilter` returns 403 `SUPER_ADMIN_PLATFORM_ONLY`
- **Design intent**: Super admin is platform control-plane only.

#### Brute-Force Lockout → Session Revocation
- **Scenario**: 5 failed login attempts trigger lockout
- **Behavior**: Account locked for 15 minutes. ALL existing sessions immediately revoked.
- **Impact**: Any active JWT/refresh tokens become invalid.

#### MFA Enrollment Without Activation
- **Scenario**: User starts MFA setup but never activates
- **Behavior**: `mfaEnabled` remains false. MFA check skipped during login.
- **Recovery**: User can re-setup at any time.

#### Must-Change-Password Corridor
- **Scenario**: Admin provisions user with `mustChangePassword=true`
- **Behavior**: `MustChangePasswordCorridorFilter` restricts access to: `/auth/me`, `/auth/profile`, `/auth/password/change`, `/auth/logout`, `/auth/refresh-token`.
- **All other endpoints**: Return 403 with `PASSWORD_CHANGE_REQUIRED`.

#### Tenant Lifecycle Transition During Active Session
- **Scenario**: Tenant suspended while user has active session
- **Behavior**: Next mutating request rejected. GET requests still allowed.
- **DEACTIVATED**: All requests denied immediately.
- **Recovery**: Super admin must re-activate tenant.

#### Legacy X-Company-Id Header
- **Scenario**: Client sends `X-Company-Id` header
- **Behavior**: `CompanyContextFilter` rejects with `COMPANY_CONTEXT_LEGACY_HEADER_UNSUPPORTED`

#### Concurrent Password Changes
- **Scenario**: User changes password from two tabs simultaneously
- **Behavior**: First change succeeds and revokes all sessions. Second tab's token becomes invalid.
- **Protection**: `revokeExistingSessions` called in `applyNewPassword`.

#### Password Reset Email Delivery Failure
- **Scenario**: User requests password reset but email service is down
- **Behavior**: Token cleaned up, logged. User sees generic success (no leak).
- **Recovery**: Token never marked delivered. User can retry.

### S. Non-Negotiables

1. **No cross-tenant data access**: JWT `companyCode` claim must match user's `auth_scope_code`. Database uniqueness `(email, auth_scope_code)` enforced.
2. **No business operations without authentication**: Every `/api/v1/**` endpoint requires valid JWT (except public auth endpoints).
3. **No tenant context injection by unauthenticated users**: `CompanyContextFilter` rejects header without JWT.
4. **JWT secret minimum 256 bits**: Enforced in `JwtTokenService.requireStrongSecret()`.
5. **Password never stored in plaintext**: Always BCrypt-hashed. Password history also BCrypt-hashed.
6. **MFA secret encrypted at rest**: `CryptoService.encrypt()` before storage.
7. **Failed login produces generic error**: "Invalid credentials" — no information leak.
8. **Token revocation is immediate and global**: All sessions invalidated atomically on logout/lockout.
9. **Super admin cannot execute tenant business workflows**: Enforced at filter level.
10. **Tenant DEACTIVATED is deny-all**: Irreversible lifecycle state.
11. **Password reset tokens are digest-only**: Raw token never stored in database.
12. **Refresh tokens are single-use**: Consumed and deleted on first use.
13. **Quota enforcement is fail-closed**: Hard limit auto-enabled if both limits disabled.
14. **Lockout triggers session revocation**: At 5 failed attempts, ALL sessions revoked immediately.

### Current Implementation Map

#### Entry Points (Controllers)

| Endpoint | Method | Auth | Controller |
|----------|--------|------|------------|
| `/api/v1/auth/login` | POST | Public | `AuthController` |
| `/api/v1/auth/refresh-token` | POST | Public | `AuthController` |
| `/api/v1/auth/logout` | POST | Authenticated | `AuthController` |
| `/api/v1/auth/me` | GET | Authenticated | `AuthController` |
| `/api/v1/auth/password/change` | POST | Authenticated | `AuthController` |
| `/api/v1/auth/password/forgot` | POST | Public | `AuthController` |
| `/api/v1/auth/password/reset` | POST | Public | `AuthController` |
| `/api/v1/auth/mfa/setup` | POST | Authenticated | `MfaController` |
| `/api/v1/auth/mfa/activate` | POST | Authenticated | `MfaController` |
| `/api/v1/auth/mfa/disable` | POST | Authenticated | `MfaController` |
| `/api/v1/auth/profile` | GET | Authenticated | `UserProfileController` |
| `/api/v1/auth/profile` | PUT | Authenticated | `UserProfileController` |
| `/api/v1/companies` | GET | `ROLE_SUPER_ADMIN` or `ROLE_ADMIN` or `ROLE_ACCOUNTING` or `ROLE_SALES` | `CompanyController` |
| `/api/v1/companies/{id}` | DELETE | `ROLE_ADMIN` (always denied) | `CompanyController` |
| `/api/v1/superadmin/**` | Various | `ROLE_SUPER_ADMIN` | `SuperAdminController` |
| `/api/v1/superadmin/tenants/onboard` | POST | `ROLE_SUPER_ADMIN` | `SuperAdminTenantOnboardingController` |

#### Security Filter Chain (in order)
1. `JwtAuthenticationFilter` — parse JWT, load `UserPrincipal`, check blacklist/revocation
2. `CompanyContextFilter` — resolve company context, validate tenant lifecycle, enforce runtime admission
3. `MustChangePasswordCorridorFilter` — restrict access if password change required

#### Interceptor Chain
1. `TenantUsageMetricsInterceptor` — record usage metrics
2. `ModuleGatingInterceptor` — check module enabled for tenant

#### Core Services

| Service | Responsibility |
|---------|---------------|
| `AuthService` | Login, refresh, logout orchestration |
| `MfaService` | TOTP enrollment, activation, verification |
| `PasswordService` | Change password, history enforcement |
| `PasswordResetService` | Forgot/reset flow with email delivery |
| `RefreshTokenService` | Issue, consume, revoke refresh tokens |
| `ScopedAccountBootstrapService` | Provision tenant-scoped accounts |
| `TenantAdminProvisioningService` | Provision initial tenant admin |
| `TokenBlacklistService` | Token blacklist + user token revocation |
| `AuthScopeService` | Scope code resolution, platform scope detection |
| `JwtTokenService` | JWT generation and parsing |
| `TenantRuntimeEnforcementService` | Runtime policy and request admission |
| `TenantRuntimeRequestAdmissionService` | Request admission wrapper |
| `ModuleGatingService` | Module enabled checks |
| `CompanyContextService` | Current company resolution |
| `TenantOnboardingService` | Full tenant provisioning |
| `TenantLifecycleService` | Lifecycle state transitions |
| `RoleService` | Role synchronization, permission management |

#### Domain Entities

| Entity | Table | Key Fields |
|--------|-------|------------|
| `UserAccount` | `app_users` | `publicId`, `email`, `authScopeCode`, `passwordHash`, `enabled`, `mfaEnabled`, `failedLoginAttempts`, `lockedUntil`, `mustChangePassword`, `company_id` FK |
| `Company` | `companies` | `publicId`, `name`, `code` (unique), `lifecycleState`, `enabledModules` (jsonb), `timezone`, `baseCurrency`, quota fields |
| `Role` | `roles` | `name` (unique) |
| `Permission` | `permissions` | `code` (unique) |
| `RefreshToken` | `refresh_tokens` | `tokenDigest` (unique), `userPublicId`, `authScopeCode`, `issuedAt`, `expiresAt` |
| `BlacklistedToken` | `blacklisted_tokens` | `tokenId`, `expirationTime`, `userId`, `reason` |
| `UserTokenRevocation` | `user_token_revocations` | `userId`, `revokedAt`, `reason` |
| `PasswordResetToken` | `password_reset_tokens` | `tokenDigest` (unique), `user_id` FK, `expiresAt`, `used`, `deliveredAt` |
| `UserPasswordHistory` | `user_password_history` | `user_id` FK, `passwordHash`, `changedAt` |
| `MfaRecoveryCode` | `mfa_recovery_codes` | `user_id` FK, `codeHash`, `usedAt` |

#### Enums

| Enum | Values |
|------|--------|
| `SystemRole` | `SUPER_ADMIN` (ROLE_SUPER_ADMIN), `ADMIN` (ROLE_ADMIN), `ACCOUNTING` (ROLE_ACCOUNTING), `FACTORY` (ROLE_FACTORY), `SALES` (ROLE_SALES), `DEALER` (ROLE_DEALER) |
| `CompanyLifecycleState` | `ACTIVE`, `SUSPENDED`, `DEACTIVATED` |
| `CompanyModule` | Core: `AUTH`, `ACCOUNTING`, `SALES`, `INVENTORY`. Gatatable: `MANUFACTURING`, `HR_PAYROLL`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED` |
| `TenantRuntimeState` | `ACTIVE`, `HOLD`, `BLOCKED` |

#### Scheduled Tasks
- `RefreshTokenService.cleanupExpiredTokens()` — every 1 hour
- `TokenBlacklistService.cleanupExpiredTokens()` — every 1 hour

### Canonical Path Analysis

#### [CANONICAL] Login Path
```
AuthController.login()
  → AuthService.login()
    → AuthScopeService.requireScopeCode(companyCode)
    → UserAccountRepository.findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase()
    → ensureEnabledForAuthentication()
    → enforceLock()
    → PasswordEncoder.matches()
    → TenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed()
    → MfaService.verifyDuringLogin()
    → resetLock()
    → AuditService.logAuthSuccess(LOGIN_SUCCESS)
    → JwtTokenService.generateAccessToken(subject, companyCode, claims, issuedAt)
    → RefreshTokenService.issue(userPublicId, scopeCode, issuedAt, expiresAt)
    → return AuthResponse
```

#### [CANONICAL] Token Refresh Path
```
AuthController.refresh()
  → AuthService.refresh()
    → AuthScopeService.requireScopeCode()
    → RefreshTokenService.consume() [single-use, delete]
    → TokenBlacklistService.isUserTokenRevoked()
    → UserAccountRepository.findByPublicId()
    → ensureEnabledForAuthentication() + enforceLock()
    → TenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed()
    → JwtTokenService.generateAccessToken()
    → RefreshTokenService.issue() [new token]
    → return AuthResponse
```

#### [CANONICAL] Logout Path
```
AuthController.logout()
  → AuthService.logout()
    → JwtTokenService.parse(accessToken)
    → TokenBlacklistService.revokeAllUserTokens() [user-level revocation]
    → RefreshTokenService.revokeAllForUser() [all refresh tokens]
    → TokenBlacklistService.blacklistToken() [specific JTI]
```

#### [CANONICAL] Self-Service Password Reset
```
AuthController.forgotPassword()
  → PasswordResetService.requestReset(email, companyCode)
    → SecurityMonitoringService.checkRateLimit()
    → UserAccountRepository.findScoped()
    → dispatchResetEmail() → issueResetToken() → EmailService.send()
    → AuditService.logAuthSuccess(PASSWORD_RESET_REQUESTED)

AuthController.resetPassword()
  → PasswordResetService.resetPassword(token, newPassword, confirmPassword)
    → PasswordResetTokenRepository.findByTokenDigest()
    → PasswordService.resetPassword()
    → revokeActiveSessions()
```

#### [CANONICAL] MFA Enrollment
```
MfaController.setup() → MfaService.beginEnrollment()
  → generateSecret() → CryptoService.encrypt()
  → generateRecoveryCodes() → passwordEncoder.encode() each
MfaController.activate() → MfaService.activate()
  → CryptoService.decrypt() → isValidTotp() → user.setMfaEnabled(true)
```

#### [CANONICAL] Tenant Onboarding
```
SuperAdminTenantOnboardingController.onboard()
  → TenantOnboardingService.onboardTenant()
    → createCompany() → createTemplateAccounts() → applyCompanyDefaultAccounts()
    → TenantAdminProvisioningService.provisionInitialAdmin()
    → AccountingPeriodService.ensurePeriod()
    → initializeDefaultSystemSettings()
```

#### [NON-CANONICAL] Admin-Initiated Password Reset
- `PasswordResetService.requestResetByAdmin()` — called from super-admin support actions
- Same flow as self-service but initiated by admin. Functional equivalent exists because super-admin needs to trigger resets without knowing current password.

#### [NON-CANONICAL] Dual Revocation on Logout
- `AuthService.logout()` performs both user-level revocation AND per-token blacklisting
- The user-level revocation already invalidates all tokens, making per-token blacklist redundant for logout
- **[DECISION-REQUIRED]**: Is dual revocation intentional or should logout simplify?

#### [DEAD-CODE] Company Deletion
- `CompanyController.delete()` exists but always throws `AccessDeniedException("Deleting companies is not permitted")`
- No realistic caller. **[DECISION-REQUIRED]**: Remove or keep as explicit guard?

#### [DEAD-CODE] AuthScopeService.updatePlatformScopeCode()
- Method exists for changing platform auth code, but no controller endpoint exposes it
- Called only during bootstrap/migration scenarios

### Cross-Module Coupling Map

#### Module Dependency Graph
```
auth ──→ core/security (JwtTokenService, TokenBlacklistService, AuthScopeService)
auth ──→ company (CompanyRepository [DIRECT], TenantRuntimeRequestAdmissionService)
auth ──→ core/audit (AuditService)
auth ──→ rbac (Role, Permission — via UserAccount.roles EAGER fetch)

core/security ──→ auth (UserAccountDetailsService, UserPrincipal)
core/security ──→ company (CompanyService, TenantRuntimeEnforcementService)

company ──→ auth (UserAccountRepository [DIRECT] — active user count)
company ──→ core/security (AuthScopeService)
company ──→ core/audit (AuditService)

rbac ──→ core/audit (AuditService)
rbac ──→ core/security (CompanyContextHolder — ThreadLocal read)
```

#### Coupling Detail Table

| Source | Target | Class/Method | Direction | Type | Notes |
|--------|--------|-------------|-----------|------|-------|
| auth | core/security | `JwtTokenService.generateAccessToken()` | → | Facade | Clean boundary |
| auth | core/security | `TokenBlacklistService.revokeAllUserTokens()` | → | Facade | Clean boundary |
| auth | core/security | `AuthScopeService.requireScopeCode()` | → | Facade | Clean boundary |
| auth | company | `CompanyRepository` **direct** | → | **TIGHT** | Auth reads company repo directly |
| auth | company | `TenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed()` | → | Facade | Through service |
| auth | core/audit | `AuditService.logAuthSuccess/failure()` | → | Facade | Clean boundary |
| auth | rbac | `UserAccount.roles` EAGER ManyToMany | → | Tight | Direct JPA relationship |
| core/security | auth | `UserAccountDetailsService.loadUserByUsername()` | → | Facade | Spring Security integration |
| core/security | auth | `UserPrincipal` wraps `UserAccount` | → | Tight | Necessary for auth |
| core/security | company | `CompanyService.resolveLifecycleStateByCode()` | → | Facade | Clean boundary |
| core/security | company | `TenantRuntimeRequestAdmissionService.beginRequest()` | → | Facade | Through service |
| company | auth | `UserAccountRepository.countByCompany_IdAndEnabledTrue()` **direct** | → | **TIGHT** | Cross-module repo access |
| company | core/security | `AuthScopeService.requireScopeCode()` | → | Facade | Clean boundary |
| rbac | core/security | `CompanyContextHolder.getCompanyCode()` | → | Tight | ThreadLocal read |
| rbac | core/audit | `AuditService.logAuthSuccess/failure()` | → | Facade | Clean boundary |

#### Tight Coupling Points (Violations)

1. **`AuthService → CompanyRepository`**: Auth module directly reads Company repository instead of going through `CompanyService`. Should use a facade.
2. **`TenantRuntimeEnforcementService → UserAccountRepository`**: Company module directly reads Auth's repository for active user count. Should use a facade.
3. **`UserAccount → Role` (EAGER ManyToMany)**: Tight JPA coupling between auth and rbac entities. Roles loaded eagerly on every `UserAccount` fetch.

#### Shared Entity Risks

- **`UserAccount`**: Mutated by auth (login, lockout, password), admin (user CRUD), company (tenant provisioning). No single module owner.
- **`Company`**: Mutated by company (lifecycle, limits, modules), auth (during onboarding). Company module is primary owner.
- **`Role`/`Permission`**: Mutated by rbac (synchronization), read by auth (every login). RBAC module is primary owner.

### Current Maturity Grade

**Grade: 4 / 5** — Production-Ready with Minor Gaps

#### Scoring Rationale
- ✅ Complete authentication lifecycle (login, refresh, logout, lockout, MFA)
- ✅ Full JWT token management (access + refresh + blacklist + revocation)
- ✅ Tenant isolation enforced at multiple layers (scoped accounts, JWT claims, filter chain)
- ✅ Runtime enforcement (quotas, lifecycle states, module gating)
- ✅ Comprehensive audit trail for all auth events
- ✅ Password policy enforcement with history
- ✅ MFA with TOTP and recovery codes
- ✅ Must-change-password corridor filter
- ⚠️ Tight coupling: AuthService reads CompanyRepository directly
- ⚠️ Tight coupling: TenantRuntimeEnforcementService reads UserAccountRepository directly
- ⚠️ No explicit "last login" timestamp (derived from audit events)
- ⚠️ No UI-level session management (list/revoke individual sessions)
- ⚠️ CompanyController.delete() is dead code

### Done Checklist

| # | Item | Status |
|---|------|--------|
| 1 | Login with valid credentials returns JWT | ✅ Pass |
| 2 | Invalid credentials return generic error | ✅ Pass |
| 3 | Account lockout after 5 failed attempts | ✅ Pass |
| 4 | MFA enrollment and verification works | ✅ Pass |
| 5 | Token refresh cycle works | ✅ Pass |
| 6 | Logout revokes all sessions | ✅ Pass |
| 7 | Password change enforces policy | ✅ Pass |
| 8 | Password history prevents reuse | ✅ Pass |
| 9 | Tenant isolation enforced | ✅ Pass |
| 10 | Super admin limited to platform operations | ✅ Pass |
| 11 | Tenant lifecycle enforcement works | ✅ Pass |
| 12 | Module gating enforced | ✅ Pass |
| 13 | Runtime quotas enforced | ✅ Pass |
| 14 | Must-change-password corridor works | ✅ Pass |
| 15 | Password reset flow works | ✅ Pass |
| 16 | All auth events audited | ✅ Pass |
| 17 | CORS configured | ✅ Pass |
| 18 | CSRF disabled (JWT API) | ✅ Pass |
| 19 | No cross-tenant data access | ✅ Pass |
| 20 | Brute-force lockout revokes sessions | ✅ Pass |

### Open Decisions ([DECISION-REQUIRED] items)

#### D-LOGIN-001: Token Revocation Strategy
**Question**: Should `AuthService.logout()` simplify to only user-level revocation, or maintain both user-level and per-token blacklist?
**Context**: Current logout performs both `revokeAllUserTokens()` and `blacklistAccessToken()`. Per-token blacklist is redundant for logout since all tokens are already invalidated.
**Owner**: Founder

#### D-LOGIN-002: Session Management UI
**Question**: Should there be a UI for admins to view and revoke individual user sessions?
**Context**: Currently force-logout revokes ALL sessions. No way to revoke a single session.
**Owner**: Founder

#### D-LOGIN-003: Last Login Timestamp
**Question**: Should `UserAccount` have an explicit `lastLoginAt` field, or is deriving from audit events acceptable?
**Context**: No explicit field exists. Derived from `LOGIN_SUCCESS` audit events.
**Owner**: Founder

#### D-LOGIN-004: Auth Module Direct Repository Access
**Question**: Should `AuthService` access `CompanyRepository` directly, or go through `CompanyService`?
**Context**: `AuthService` injects `CompanyRepository` directly. Creates tight coupling.
**Owner**: Founder

#### D-LOGIN-005: Company Module Direct User Repository Access
**Question**: Should `TenantRuntimeEnforcementService` access `UserAccountRepository` directly?
**Context**: Company module reads auth's repository for active user count. Cross-module violation.
**Owner**: Founder

#### D-LOGIN-006: Refresh Token Rotation Confirmation
**Question**: Confirm that single-use (consumed on first use) refresh tokens are intentional.
**Context**: Most secure pattern. Already implemented.
**Owner**: Founder (confirmation only)

#### D-LOGIN-007: Dead Code Cleanup
**Question**: Should `CompanyController.delete()` be removed since it always throws AccessDeniedException?
**Context**: Endpoint exists but is unreachable by design.
**Owner**: Founder

---

## Flow 2: User / Role / Permission Assignment

### A. Flow Identity
- **Flow Name**: User / Role / Permission Assignment
- **Domain**: Identity & Access Management (RBAC)
- **Portal**: Admin portal (user/role management), Super Admin portal (cross-tenant user management)
- **P0 Status**: Foundation — every authenticated action depends on correct role/permission assignment
- **Source Modules**: rbac, admin, auth, company
- **Primary Entities**: `UserAccount` (table: `app_users`), `Role` (table: `roles`), `Permission` (table: `permissions`), `role_permissions` (join table), `user_roles` (join table)
- **Auth Model**: Roles are assigned to users via `user_roles` join table. Permissions are assigned to roles via `role_permissions` join table. The 6 system roles are defined in `SystemRole` enum with their default permissions hardcoded.

### B. Business Objective
Allow administrators to provision user accounts, assign roles, and manage the full user lifecycle within a company (tenant). This flow governs who can access what within the ERP — without correct role/permission assignment, no business flow can execute.

**What breaks if wrong:**
- Unauthorized access (non-admin assigns admin roles)
- Privilege escalation (regular user obtains SUPER_ADMIN)
- Tenant isolation failure (admin from one company manages users in another)
- Orphaned dealer records (dealer user created without corresponding Dealer entity)
- Token staleness (role change not reflected until re-authentication)
- User quota bypass (unlimited users beyond plan limits)

### C. Actors

| Actor | Role | Portal | Capability in This Flow |
|-------|------|--------|------------------------|
| Super Admin | `ROLE_SUPER_ADMIN` | Platform control plane | Full user CRUD across all tenants; can assign any role including ROLE_ADMIN and ROLE_SUPER_ADMIN; can transfer users between companies |
| Admin | `ROLE_ADMIN` | Admin portal | User CRUD within own tenant only; cannot assign ROLE_ADMIN or ROLE_SUPER_ADMIN; cannot transfer users to other companies |
| Accounting | `ROLE_ACCOUNTING` | Admin portal | No user management capability (read-only viewer of own profile) |
| Factory | `ROLE_FACTORY` | Admin portal | No user management capability |
| Sales | `ROLE_SALES` | Admin portal | No user management capability |
| Dealer | `ROLE_DEALER` | Dealer portal | No user management capability |

**Role Assignment Guard**: Only `ROLE_SUPER_ADMIN` can assign `ROLE_ADMIN` and `ROLE_SUPER_ADMIN`. `ROLE_ADMIN` can assign all other roles (ACCOUNTING, FACTORY, SALES, DEALER).

### D. Preconditions
1. Authenticated actor has `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` authority
2. Target company exists and is not `DEACTIVATED`
3. For user creation: email does not already exist within the target company scope (`(email, auth_scope_code)` unique constraint)
4. For role assignment: requested role is a known `SystemRole` enum value or an existing custom role
5. For enabling/activating: tenant user quota allows additional enabled users (`TenantRuntimePolicyService.assertCanAddEnabledUser()`)
6. Email service is configured for credential delivery (`ScopedAccountBootstrapService.isCredentialProvisioningReady()`)

### E. Trigger
- **User-initiated**: Admin/Super Admin creates a new user via Admin portal (`POST /api/v1/admin/users`)
- **User-initiated**: Admin/Super Admin updates user details, roles, status (`PUT /api/v1/admin/users/{id}`)
- **User-initiated**: Admin/Super Admin suspends/unsuspends/deletes user (`PATCH/DELETE /api/v1/admin/users/{id}`)
- **System-initiated**: Tenant onboarding auto-provisions initial admin via `TenantAdminProvisioningService.provisionInitialAdmin()`
- **System-initiated**: Role synchronization at startup via `RoleService.synchronizeSystemRoles()`

### F. Input Contract

#### CreateUserRequest (`POST /api/v1/admin/users`)

| Field | Type | Required | Validation | Immutable After Create |
|-------|------|----------|------------|----------------------|
| `email` | String | Yes | `@Email`, `@NotBlank` | No (via transfer) |
| `displayName` | String | Yes | `@NotBlank` | No |
| `companyId` | Long | Yes | `@NotNull`, must be active company for non-SUPER_ADMIN | N/A |
| `roles` | List\<String\> | Yes | `@NotEmpty`, each must be valid `SystemRole` name or existing role | No |

#### UpdateUserRequest (`PUT /api/v1/admin/users/{id}`)

| Field | Type | Required | Validation | Notes |
|-------|------|----------|------------|-------|
| `displayName` | String | Yes | `@NotBlank` | Always required |
| `companyId` | Long | No | Must be active company for non-SUPER_ADMIN | Triggers company transfer if changed |
| `roles` | List\<String\> | No | Each must be valid role name | Replaces all roles (not additive) |
| `enabled` | Boolean | No | — | Toggles user active/suspended state |

#### UpdateUserStatusRequest (`PUT /api/v1/admin/users/{id}/status`)

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `enabled` | Boolean | Yes (`@NotNull`) | true or false |

#### CreateRoleRequest (`POST /api/v1/superadmin/roles`)

| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `name` | String | Yes (`@NotBlank`) | Must match a `SystemRole` enum value |
| `description` | String | Yes (`@NotBlank`) | — |
| `permissions` | List\<String\> | Yes (`@NotEmpty`) | Each must be existing `Permission.code` |

### G. Step-by-Step Lifecycle

#### User Creation (Canonical Path)

1. Admin calls `POST /api/v1/admin/users` with `CreateUserRequest` (email, displayName, companyId, roles)
2. `AdminUserService.createUser()`:
   a. Resolves current company via `CompanyContextService.requireCurrentCompany()`
   b. Resolves target company (for non-SUPER_ADMIN, must match current company)
   c. Validates actor can assign requested roles (`assertActorCanAssignRoles()`)
   d. Checks tenant user quota via `TenantRuntimePolicyService.assertCanAddEnabledUser()`
   e. Calls `ScopedAccountBootstrapService.provisionTenantAccount()`:
      - Normalizes email to lowercase
      - Resolves scope code via `AuthScopeService.requireScopeCode()`
      - Checks email uniqueness within scope
      - Generates temporary password (14 chars via `PasswordUtils.generateTemporaryPassword()`)
      - Creates `UserAccount` with `mustChangePassword=true`
      - Attaches roles via `UserAccount.addRole()`
      - Saves to `app_users` table
      - Schedules credential email delivery after transaction commit
   f. If role includes `ROLE_DEALER`: auto-creates `Dealer` entity with receivable account
   g. Logs `USER_CREATED` audit event

3. User receives credential email with temporary password
4. User logs in and is forced to change password via `MustChangePasswordCorridorFilter`

#### User Update (Canonical Path)

1. Admin calls `PUT /api/v1/admin/users/{id}` with `UpdateUserRequest`
2. `AdminUserService.updateUser()`:
   a. Resolves current company
   b. Resolves target user with scope check (`resolveScopedUserForAdminAction()`)
   c. Updates display name
   d. If `enabled` changed: calls `updateUserStatusInternal()` (disable revokes all tokens)
   e. If `companyId` changed: validates quota, checks email uniqueness, transfers company, updates `authScopeCode`
   f. If `roles` changed: clears existing roles, attaches new roles via `roleService.ensureRoleExists()`
   g. If any permission-affecting change: revokes all JWT tokens and refresh tokens (forces re-authentication)
   h. Logs `USER_UPDATED` audit event

#### User Suspension (Canonical Path)

1. Admin calls `PATCH /api/v1/admin/users/{id}/suspend`
2. `AdminUserService.suspend()`:
   a. Resolves user with pessimistic lock (`lockById` or `lockByIdAndCompanyId`)
   b. Validates user is within company scope (non-SUPER_ADMIN)
   c. Checks user is not the protected main admin (`assertNotProtectedMainAdmin()`)
   d. Sets `enabled=false`, revokes all tokens
   e. Sends suspension email
   f. Logs `USER_DEACTIVATED` audit event

#### User Unsuspension (Canonical Path)

1. Admin calls `PATCH /api/v1/admin/users/{id}/unsuspend`
2. `AdminUserService.unsuspend()`:
   a. Resolves user with pessimistic lock
   b. Checks tenant user quota allows additional enabled users
   c. Sets `enabled=true`
   d. Logs `USER_ACTIVATED` audit event

#### User Deletion (Canonical Path)

1. Admin calls `DELETE /api/v1/admin/users/{id}`
2. `AdminUserService.deleteUser()`:
   a. Resolves user with pessimistic lock
   b. Checks user is not the protected main admin
   c. Revokes all JWT and refresh tokens
   d. Deletes user from `app_users`
   e. Sends deletion email
   f. Logs `USER_DELETED` audit event

#### Role Synchronization (System Path)

1. On application startup or manual trigger, `RoleService.synchronizeSystemRoles()`:
   a. Iterates all `SystemRole` enum values
   b. For each: creates or updates `Role` entity with description and default permissions
   c. Permissions are created on-demand if missing (`ensurePermissionExists()`)
   d. Retired permissions are removed from roles (`SALES` has `dispatch.confirm` retired)

### H. State Machine

#### User Account States

| State | `enabled` | `lockedUntil` | `mustChangePassword` | Description |
|-------|-----------|---------------|---------------------|-------------|
| **Active** | `true` | `null` | `false` | Normal operational state |
| **Must-Change-Password** | `true` | `null` | `true` | Newly provisioned; forced password change on next login |
| **Suspended** | `false` | `null` | — | Admin-disabled; all tokens revoked |
| **Locked** | `true` | future | — | Auto-locked due to failed login attempts (auth module) |
| **Deleted** | — | — | — | Permanently removed from `app_users` |

#### User Lifecycle Transitions

```
                    provisionTenantAccount()
                    ┌─────────────────────────────────┐
                    │                                 ▼
               [Not Exists] ───────────────────► [Must-Change-Password]
                                                     │
                                        first login + password change
                                                     │
                                                     ▼
                                               ┌──────────┐
                                    ┌─────────│  Active   │──────────┐
                                    │          └──────────┘          │
                                    │                                │
                              suspend()                       failed login
                                    │                         (by AuthService)
                                    ▼                                │
                              ┌───────────┐                         ▼
                              │ Suspended │                   ┌───────────┐
                              └───────────┘                   │  Locked   │
                                    │                         └───────────┘
                               unsuspend()                         │
                                    │                     lockout expires
                                    │                           or
                                    │                      successful login
                                    ▼                           │
                              ┌──────────┐◄──────────────────────┘
                              │  Active  │
                              └──────────┘
                                    │
                              deleteUser()
                                    │
                                    ▼
                              [Deleted]
```

**Irreversible transitions**: Must-Change-Password → Active (password change cannot be undone), Not Exists → Must-Change-Password (creation), Active/Suspended → Deleted (permanent).

#### Role Assignment States

Roles are a `Set<Role>` on `UserAccount` (no explicit state machine). Role changes are:
- **Additive**: `attachRoles()` adds roles to the set
- **Replacement**: `updateUser()` clears all roles then re-attaches (not additive)
- **Synchronized**: `RoleService.synchronizeSystemRoles()` ensures DB matches `SystemRole` enum

### I. Validation Rules

| Rule | Field/Context | Constraint | Error Code | Enforced By |
|------|--------------|------------|------------|-------------|
| V-UR-01 | `email` | Must be valid email format | `INVALID_INPUT` | `CreateUserRequest` `@Email` |
| V-UR-02 | `email` | Must not be blank | `INVALID_INPUT` | `CreateUserRequest` `@NotBlank` |
| V-UR-03 | `email` | Must be unique within company scope `(email, auth_scope_code)` | `INVALID_INPUT` | `ScopedAccountBootstrapService` DB unique constraint |
| V-UR-04 | `displayName` | Must not be blank | `INVALID_INPUT` | `CreateUserRequest`/`UpdateUserRequest` `@NotBlank` |
| V-UR-05 | `companyId` | Must be active company (non-SUPER_ADMIN must match current tenant) | `INVALID_INPUT` | `AdminUserService.resolveTargetCompanyForCreate()` |
| V-UR-06 | `roles` | Must not be empty | `INVALID_INPUT` | `CreateUserRequest` `@NotEmpty` |
| V-UR-07 | `roles` | ROLE_ADMIN and ROLE_SUPER_ADMIN require SUPER_ADMIN authority | `ACCESS_DENIED` | `AdminUserService.assertActorCanAssignRoles()` |
| V-UR-08 | `roles` | Each role name must be a known `SystemRole` or existing custom role | `INVALID_INPUT` | `RoleService.ensureRoleExists()` |
| V-UR-09 | user quota | Enabling user must not exceed tenant's `quotaMaxActiveUsers` | `BUSINESS_LIMIT_EXCEEDED` | `TenantRuntimePolicyService.assertCanAddEnabledUser()` |
| V-UR-10 | main admin | Cannot disable, suspend, or delete the tenant's main admin user | `INVALID_STATE` | `AdminUserService.assertNotProtectedMainAdmin()` |
| V-UR-11 | company scope | Non-SUPER_ADMIN can only manage users within own company | `ACCESS_DENIED` / `INVALID_INPUT` | `AdminUserService.resolveScopedUserForAdminAction()` |
| V-UR-12 | company transfer | Email must not already exist in target company scope | `INVALID_INPUT` | `AdminUserService.assertScopedEmailAvailableForTransfer()` |
| V-UR-13 | role mutation | Only SUPER_ADMIN can mutate shared system role definitions | `ACCESS_DENIED` | `RoleService.enforceSuperAdminForSharedRoleMutation()` |

### J. Side Effects

| Action | Side Effects |
|--------|-------------|
| **User Create** | `UserAccount` saved to `app_users`; `user_roles` join entries created; `USER_CREATED` audit event logged; credential email sent (async after commit); if ROLE_DEALER: `Dealer` entity + `Account` (receivable) auto-created |
| **User Update (roles)** | `user_roles` cleared and re-created; all JWT tokens revoked (`TokenBlacklistService`); all refresh tokens revoked (`RefreshTokenService`); `USER_UPDATED` audit event |
| **User Update (company transfer)** | `auth_scope_code` updated; JWT/refresh tokens revoked; `USER_UPDATED` audit event |
| **User Suspend** | `enabled=false`; all JWT tokens revoked; all refresh tokens revoked; suspension email sent; `USER_DEACTIVATED` audit event |
| **User Unsuspend** | `enabled=true`; quota check; `USER_ACTIVATED` audit event |
| **User Delete** | All tokens revoked; `UserAccount` deleted from `app_users`; deletion email sent; `USER_DELETED` audit event |
| **Force Reset Password** | Password reset token created; reset email sent; `PASSWORD_RESET_REQUESTED` audit event |
| **Disable MFA** | `mfa_secret`, `mfa_recovery_codes` cleared; all tokens revoked; `MFA_DISABLED` audit event |
| **Role Sync** | `Role` and `Permission` entities created/updated to match `SystemRole` enum definitions |

### K. Inventory Effect
**No inventory mutations.** User/role/permission management is a pure identity operation. It does not directly affect stock levels, warehouse assignments, or batch quantities.

Indirectly, role assignment determines *who can* perform inventory operations (e.g., only users with `dispatch.confirm` permission can confirm dispatches), but the assignment itself produces zero inventory events.

### L. Accounting Effect
**No journal entries.** User/role/permission management produces zero accounting transactions.

Indirect accounting side effects:
- Creating a `ROLE_DEALER` user triggers auto-creation of a `Dealer` entity with a receivable `Account` (balance sheet: Sundry Debtors group). This is a master data setup step, not a transactional accounting event.
- Role assignment determines who can approve/post journal entries, but the assignment itself does not create journal entries.

### M. Tax Effect
**No tax impact.** User/role/permission management does not trigger any tax computation, GST determination, or tax posting.

### N. Failure Handling

| Failure Scenario | Behavior | Recovery |
|-----------------|----------|----------|
| Email already exists in scope | `INVALID_INPUT` thrown before account creation | Use different email or transfer existing user |
| Insufficient role assignment authority | `ACCESS_DENIED` thrown; `ACCESS_DENIED` audit event logged | Request SUPER_ADMIN to assign privileged roles |
| Tenant user quota exceeded | `BUSINESS_LIMIT_EXCEEDED` with quota details | Reduce enabled users or request quota increase |
| Target user is main admin | `INVALID_STATE` ("Replace the tenant main admin before...") | Assign new main admin first |
| Target user in different company (non-SUPER_ADMIN) | `INVALID_INPUT` (masked as "User not found") or `ACCESS_DENIED` | Use SUPER_ADMIN for cross-tenant operations |
| Credential email delivery failure | Account is still created; error logged after commit | User can request password reset |
| Role not found in DB | `RoleService.ensureRoleExists()` creates it on-demand | Automatic — no manual recovery needed |
| Unknown role name in create request | `INVALID_INPUT` ("Unknown platform role") | Use valid `SystemRole` name |
| Invalid permission codes | `INVALID_INPUT` ("One or more permission codes are invalid") | Use existing permission codes |

### O. Auditability

All user management operations produce audit events via `AuditService`:

| Event Type | When Logged | Key Metadata |
|-----------|------------|--------------|
| `USER_CREATED` | After successful user provisioning | `provisioningMode=CANONICAL_EMAIL_BOOTSTRAP`, `targetUserEmail` |
| `USER_UPDATED` | After user detail/role/status change | `targetUserEmail`, `displayName` |
| `USER_ACTIVATED` | User enabled/unsuspended | `targetUserId`, `previousEnabled`, `enabled` |
| `USER_DEACTIVATED` | User disabled/suspended | `targetUserId`, `previousEnabled`, `enabled` |
| `USER_DELETED` | After user permanently deleted | `targetUserId`, `targetUserEmail` |
| `PASSWORD_RESET_REQUESTED` | Admin triggers force reset | `targetUserId` |
| `MFA_DISABLED` | Admin disables user MFA | `targetUserId` |
| `ACCESS_DENIED` | Failed role assignment or cross-scope access attempt | `reason`, `targetRole`, `targetCompanyCode` |
| `ACCESS_GRANTED` | Successful privileged operation audit | `reason`, `targetRole` |

**Audit metadata includes**: actor (email), tenantScope (company code), targetUserEmail, action, targetCompanyCode. All events are logged async with `REQUIRES_NEW` propagation to never block business transactions.

### P. Reporting Impact

- **User List Report**: `GET /api/v1/admin/users` returns all tenant users with role names, enabled status, MFA status, company code, and last login timestamp
- **Last Login Tracking**: Derived from `LOGIN_SUCCESS` audit events (not a dedicated field)
- **Tenant Runtime Metrics**: `TenantRuntimePolicyService.metrics()` exposes activeUsers, totalUsers, quotaMaxActiveUsers, and quota utilization
- **No direct impact** on financial reports, inventory reports, or tax reports

### Q. Completion Criteria

**Business terms**:
- User can log in with provisioned credentials (after first-time password change)
- User's assigned roles grant access to the expected portal areas and operations
- Revoked user cannot access any API endpoint (all tokens blacklisted)

**System terms**:
- `UserAccount` row exists in `app_users` with correct `(email, auth_scope_code)` and `company_id`
- `user_roles` join entries link to valid `Role` entities
- `role_permissions` join entries link roles to `Permission` entities
- If `ROLE_DEALER`: corresponding `Dealer` row exists with active receivable `Account`
- Audit event `USER_CREATED` or `USER_UPDATED` logged with correct metadata

### R. Edge Cases

| Edge Case | Expected Behavior |
|-----------|------------------|
| **Self-deactivation**: Admin disables own account | Allowed if not the protected main admin; admin locks themselves out |
| **Self-role-removal**: Admin removes own ADMIN role | Allowed via `updateUser()`; admin loses access immediately on re-auth |
| **Role deletion with assigned users**: Role removed from user set | Not a "delete role" — roles are just detached from user; `Role` entity persists |
| **Duplicate email across tenants**: Same email in different companies | Allowed — uniqueness is per `(email, auth_scope_code)` pair |
| **Concurrent permission changes**: Two admins update same user | Pessimistic locking (`lockById`/`lockByIdAndCompanyId`) serializes updates |
| **User creation during HOLD state**: Tenant on hold | Hold state only gates specific paths (portal, reports, demo); user CRUD still allowed |
| **Assign ROLE_DEALER to existing user**: User already exists as non-dealer | `createDealerForUser()` checks for existing Dealer by email; creates if missing, links if found |
| **Transfer user to company at quota**: Target company has max users | `assertCanAddEnabledUser()` rejects transfer if quota would be exceeded |
| **Last admin deletion**: Attempt to delete the main admin | `assertNotProtectedMainAdmin()` blocks with "Replace the tenant main admin first" |
| **Role name normalization**: Request sends "admin" instead of "ROLE_ADMIN" | `normalizeRequestedRoleName()` auto-prefixes "ROLE_" if it matches a `SystemRole` |
| **SALES retired permission**: `dispatch.confirm` on SALES role | `retiredPermissions` mechanism removes it during sync; SALES can no longer confirm dispatches |

### S. Non-Negotiables

1. **Only SUPER_ADMIN can assign ROLE_ADMIN and ROLE_SUPER_ADMIN** — enforced at both `AdminUserService` and `RoleService` levels with audit logging
2. **Tenant isolation is absolute** — non-SUPER_ADMIN admins cannot see, modify, or delete users in other companies; out-of-scope attempts are masked as "User not found" or `ACCESS_DENIED`
3. **Role changes force re-authentication** — all JWT and refresh tokens are revoked when roles, company, or enabled status change
4. **Main admin is undeletable** — the tenant's `mainAdminUserId` user cannot be disabled, suspended, or deleted without first transferring main admin status
5. **User quota is fail-closed** — if both soft and hard limits are disabled, hard limit is automatically re-enabled
6. **Pessimistic locking on user mutations** — suspend, unsuspend, and delete use `PESSIMISTIC_WRITE` lock to prevent concurrent modifications
7. **Email uniqueness per scope** — the `(email, auth_scope_code)` unique constraint in the database is the final authority; application-level checks are a guard, not the enforcement mechanism
8. **Credential delivery after commit** — credential emails are scheduled via `TransactionSynchronization.afterCommit()` so failure never rolls back the user creation

### Current Implementation Map

#### API Endpoints

##### AdminUserController (`/api/v1/admin/users`)
All endpoints require `ROLE_ADMIN` tenant-admin authority and explicitly block `ROLE_SUPER_ADMIN`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/admin/users` | ADMIN (tenant-admin only) | List all users in current tenant |
| `POST` | `/api/v1/admin/users` | ADMIN (tenant-admin only) | Create new user |
| `PUT` | `/api/v1/admin/users/{id}` | ADMIN (tenant-admin only) | Update user (name, company, roles, enabled) |
| `PUT` | `/api/v1/admin/users/{id}/status` | ADMIN (tenant-admin only) | Enable/disable user |
| `PATCH` | `/api/v1/admin/users/{id}/suspend` | ADMIN (tenant-admin only) | Suspend user |
| `PATCH` | `/api/v1/admin/users/{id}/unsuspend` | ADMIN (tenant-admin only) | Unsuspend user |
| `PATCH` | `/api/v1/admin/users/{id}/mfa/disable` | ADMIN (tenant-admin only) | Disable MFA for user |
| `POST` | `/api/v1/admin/users/{id}/force-reset-password` | ADMIN (tenant-admin only) | Force password reset |
| `DELETE` | `/api/v1/admin/users/{id}` | ADMIN (tenant-admin only) | Delete user permanently |

##### RoleController (`/api/v1/superadmin/roles`)
All endpoints require `ROLE_SUPER_ADMIN`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/api/v1/superadmin/roles` | SUPER_ADMIN | List all platform roles |
| `GET` | `/api/v1/superadmin/roles/{roleKey}` | SUPER_ADMIN | Get single role by name (auto-prefixes "ROLE_") |
| `POST` | `/api/v1/superadmin/roles` | SUPER_ADMIN | Create/update role with permissions |

#### Key Services

| Service | Module | Responsibility |
|---------|--------|---------------|
| `AdminUserService` | admin | User lifecycle CRUD, role assignment, scope enforcement, token revocation |
| `ScopedAccountBootstrapService` | auth | Low-level account provisioning with email delivery |
| `TenantAdminProvisioningService` | auth | Initial admin provisioning during tenant onboarding |
| `RoleService` | rbac | Role CRUD, permission synchronization, system role management |
| `CompanyContextService` | company | Resolves current company from `CompanyContextHolder` |
| `TenantRuntimePolicyService` | admin | User quota enforcement |
| `TokenBlacklistService` | core/security | JWT revocation on role/status changes |
| `RefreshTokenService` | auth | Refresh token revocation |
| `EmailService` | core/notification | Credential, suspension, deletion emails |

#### Database Tables

| Table | Purpose |
|-------|---------|
| `app_users` | User accounts with email, password_hash, auth_scope_code, company_id, enabled, mfa fields |
| `roles` | Role definitions (name, description) |
| `permissions` | Permission definitions (code, description) |
| `user_roles` | Many-to-many join: user_id → role_id |
| `role_permissions` | Many-to-many join: role_id → permission_id |

### Canonical Path Analysis

#### `[CANONICAL]` User Provisioning Path

```
AdminUserController.create()
  → AdminUserService.createUser()
    → CompanyContextService.requireCurrentCompany()
    → resolveTargetCompanyForCreate()
    → assertActorCanAssignRoles()
    → TenantRuntimePolicyService.assertCanAddEnabledUser()
    → ScopedAccountBootstrapService.provisionTenantAccount()
      → AuthScopeService.requireScopeCode()
      → UserAccountRepository.save() [with mustChangePassword=true]
      → EmailService.sendUserCredentialsEmailRequired() [after commit]
    → [if ROLE_DEALER] createDealerForUser()
      → DealerRepository.save() + AccountRepository.save()
    → AuditService.logAuthSuccess(USER_CREATED)
```

#### `[CANONICAL]` User Update Path

```
AdminUserController.update()
  → AdminUserService.updateUser()
    → CompanyContextService.requireCurrentCompany()
    → resolveScopedUserForAdminAction() [with scope check + pessimistic lock optional]
    → [if enabled changed] updateUserStatusInternal()
    → [if companyId changed] resolveTargetCompanyForCreate() + quota check + email uniqueness
    → [if roles changed] clear roles → attachRoles() → RoleService.ensureRoleExists()
    → [if permission-affecting] TokenBlacklistService.revokeAllUserTokens()
                              + RefreshTokenService.revokeAllForUser()
    → AuditService.logAuthSuccess(USER_UPDATED)
```

#### `[CANONICAL]` Role Synchronization Path

```
RoleService.synchronizeSystemRoles()
  → for each SystemRole enum value:
    → RoleRepository.lockByName() [pessimistic lock]
    → create Role if missing
    → reconcileSystemRolePermissions():
      → ensurePermissionExists() for each default permission
      → remove retired permissions
    → RoleRepository.save()
```

#### `[NON-CANONICAL]` Tenant Onboarding Admin Provisioning

```
TenantAdminProvisioningService.provisionInitialAdmin()
  → UserAccountRepository.existsByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase() [duplicate check]
  → RoleService.ensureRoleExists("ROLE_ADMIN")
  → ScopedAccountBootstrapService.provisionTenantAccount()
  → Company.setMainAdminUserId()
  → Company.setOnboardingAdminEmail()
  → Company.setOnboardingAdminUserId()
```

**Note**: This is a separate entry point from `AdminUserService.createUser()` used only during tenant onboarding. It directly sets `Company.mainAdminUserId` which protects this user from deletion.

#### `[DEAD-CODE]` Observations

- No actual dead code detected in this flow. All endpoints are reachable, all services are injected and used.
- The `listRoles()` method (without actor filtering) exists but is only called from `RoleController` which uses `listRolesForCurrentActor()` instead.

#### `[UNCLEAR-OWNERSHIP]` Observations

- `TenantRuntimePolicyService` lives in the `admin` module but reads `UserAccountRepository` from the `auth` module to count active users. This is a cross-module repository access violation.
- `AdminUserService.createDealerForUser()` creates `Dealer` entities in the `sales` module. User management reaches into sales module domain.

### Cross-Module Coupling Map

```
admin module
  → auth module: AdminUserService → UserAccountRepository (read/write)
  → auth module: AdminUserService → ScopedAccountBootstrapService (provisioning)
  → auth module: AdminUserService → PasswordResetService (force reset)
  → auth module: AdminUserService → RefreshTokenService (token revocation)
  → rbac module: AdminUserService → RoleService (role lookup/creation)
  → company module: AdminUserService → CompanyContextService (current tenant)
  → company module: AdminUserService → CompanyRepository (company lookup for SUPER_ADMIN)
  → core/security: AdminUserService → TokenBlacklistService (JWT revocation)
  → core/security: AdminUserService → SecurityActorResolver (audit actor)
  → core/audit: AdminUserService → AuditService (event logging)
  → core/audit: AdminUserService → AuditLogRepository (last login lookup)
  → core/notification: AdminUserService → EmailService (credential/suspension/deletion emails)
  → sales module: AdminUserService → DealerRepository (dealer auto-creation)
  → sales module: AdminUserService → AccountRepository (receivable account creation)
  → accounting module: AdminUserService → AccountRepository (receivable account creation)

admin module (TenantRuntimePolicyService)
  → company module: TenantRuntimeEnforcementService (quota check delegation)
  → auth module: UserAccountRepository (active user count — VIOLATION)

rbac module
  → core/audit: RoleService → AuditService (authority decision logging)
  → core/security: RoleService → CompanyContextHolder (tenant scope resolution)

auth module (ScopedAccountBootstrapService)
  → core/security: AuthScopeService (scope code resolution)
  → core/notification: EmailService (credential delivery)
  → company module: UserAccount.company FK
  → rbac module: UserAccount.roles via join table

auth module (TenantAdminProvisioningService)
  → auth module: ScopedAccountBootstrapService (delegated provisioning)
  → auth module: PasswordResetService (admin password reset)
  → rbac module: RoleService + RoleRepository (admin role creation)
  → company module: Company entity (sets mainAdminUserId, onboarding fields)
```

#### Tight Coupling Points

| Source | Target | Type | Severity |
|--------|--------|------|----------|
| `AdminUserService` | `UserAccountRepository` (auth) | Direct repository access across modules | **HIGH** |
| `TenantRuntimePolicyService` | `UserAccountRepository` (auth) | Direct repository access across modules | **HIGH** |
| `AdminUserService` | `DealerRepository` (sales) | Direct repository access across modules | **MEDIUM** |
| `AdminUserService` | `AccountRepository` (accounting) | Direct repository access across modules | **MEDIUM** |
| `AdminUserService` | `CompanyRepository` (company) | Direct repository access (SUPER_ADMIN company lookup) | **LOW** |

#### Hidden Dependencies

- `AdminUserService.listUsers()` reads `AuditLogRepository` to resolve last-login timestamps — user listing depends on audit module availability.
- `TenantRuntimePolicyService` depends on `TenantRuntimeEnforcementService` from company module — quota enforcement chain spans admin → company.
- `CompanyContextHolder` (ThreadLocal set by `CompanyContextFilter`) is an implicit dependency for all admin operations — if not set, `CompanyContextService` throws.

### Current Maturity Grade

**Grade: 3 / 5** — Functional with notable architectural gaps

| Dimension | Score | Rationale |
|-----------|-------|-----------|
| Completeness | 4/5 | Full CRUD lifecycle, role assignment, dealer auto-provisioning, MFA management |
| Correctness | 4/5 | Proper tenant isolation, quota enforcement, main admin protection, pessimistic locking |
| Auditability | 4/5 | All operations logged with metadata, actor, tenant scope, and target user |
| Security | 3/5 | Good role guards but cross-module repository access violates module boundaries |
| Architecture | 2/5 | `AdminUserService` is a 740-line god class touching 14+ external dependencies across 6 modules |
| Edge Case Handling | 4/5 | Handles self-deactivation, last admin, duplicate emails, concurrent modifications |

**Justification**: The flow is functionally complete and secure at the API level. The primary gap is architectural — `AdminUserService` violates module encapsulation by directly accessing repositories from auth, sales, accounting, and company modules. These should be facade-based interactions.

### Done Checklist

| # | Check | Status |
|---|-------|--------|
| 1 | User can be created with email, name, company, and roles | ✅ Pass |
| 2 | Temporary password is generated and emailed securely | ✅ Pass |
| 3 | First login forces password change (`mustChangePassword=true`) | ✅ Pass |
| 4 | ROLE_ADMIN cannot assign ROLE_ADMIN or ROLE_SUPER_ADMIN | ✅ Pass |
| 5 | Non-SUPER_ADMIN cannot manage users outside their company | ✅ Pass |
| 6 | Role changes revoke all active JWT and refresh tokens | ✅ Pass |
| 7 | Tenant main admin cannot be disabled/suspended/deleted | ✅ Pass |
| 8 | User quota is enforced before enabling new users | ✅ Pass |
| 9 | ROLE_DEALER auto-creates Dealer entity with receivable account | ✅ Pass |
| 10 | All user management operations produce audit events | ✅ Pass |
| 11 | Suspend/unsuspend lifecycle works correctly | ✅ Pass |
| 12 | Company transfer validates email uniqueness in target scope | ✅ Pass |
| 13 | System roles synchronized from enum to database | ✅ Pass |
| 14 | Pessimistic locking prevents concurrent user mutations | ✅ Pass |
| 15 | Cross-scope access attempts are audited as ACCESS_DENIED | ✅ Pass |

### Open Decisions ([DECISION-REQUIRED] items)

#### D-UR-001: Cross-Module Repository Access
**Question**: Should `AdminUserService` access `UserAccountRepository`, `DealerRepository`, and `AccountRepository` directly, or go through facade services?
**Context**: `AdminUserService` is a 740-line service touching 14+ dependencies across 6 modules. This is the largest coupling hotspot in the system.
**Owner**: Founder

#### D-UR-002: AdminUserService Decomposition
**Question**: Should `AdminUserService` be decomposed into smaller, module-aligned services (e.g., `UserProvisioningService`, `UserLifecycleService`)?
**Context**: Single service handles creation, updates, suspension, deletion, MFA, password reset, dealer auto-creation, and company transfer. Violates single responsibility.
**Owner**: Founder

#### D-UR-003: Role Assignment API Semantics
**Question**: Should role assignment be additive (PATCH-style) or replacement (PUT-style)?
**Context**: Current `updateUser()` replaces all roles on each call. No way to add a single role without sending the full list. This can cause accidental role removal.
**Owner**: Founder

#### D-UR-004: Custom Role Support
**Question**: Are custom (non-SystemRole) roles intended to be supported?
**Context**: `RoleService.ensureRoleExists()` creates roles on-demand, even non-SystemRole names. But `CreateRoleRequest` validation rejects unknown roles. The code allows but the API does not.
**Owner**: Founder

#### D-UR-005: SALES Retired Permission (dispatch.confirm)
**Question**: Confirm that removing `dispatch.confirm` from ROLE_SALES is intentional.
**Context**: `SystemRole.SALES` has `dispatch.confirm` in `retiredPermissions`. Sales users can no longer confirm dispatches. Only users with `portal:factory` or `portal:accounting` + `dispatch.confirm` can.
**Owner**: Founder (confirmation only)

#### D-UR-006: User Deletion vs Deactivation
**Question**: Should user deletion be hard delete (current) or soft delete?
**Context**: `deleteUser()` permanently removes the `UserAccount` row. This breaks audit trail references and any FK references from other modules. Soft delete (archive) would preserve referential integrity.
**Owner**: Founder

#### D-UR-007: Dealer Auto-Creation Side Effect
**Question**: Is the dealer auto-creation side effect on ROLE_DEALER assignment the intended design?
**Context**: When a user is assigned `ROLE_DEALER`, the system auto-creates a `Dealer` entity with a generated code and receivable account. This tightly couples user management to the sales module and accounting module.
**Owner**: Founder

#### D-UR-008: Tenant Runtime Policy Module Location
**Question**: Should `TenantRuntimePolicyService` remain in the admin module?
**Context**: It reads `UserAccountRepository` from the auth module to count active users — a cross-module repository access violation. Moving it to a shared/tenant module or providing a facade in the auth module would be cleaner.
**Owner**: Founder

---

## Flow 3: Product / Customer / Supplier Masters

### A. Flow Identity
- **Flow Name**: Product / Customer / Supplier Masters
- **Domain**: Master Data Management
- **Portal**: Admin portal, Accounting workspace, Sales workspace, Factory-adjacent catalog reads
- **P0 Status**: Foundation — order-to-cash, procure-to-pay, factory execution, inventory valuation, and GST reporting all depend on these masters being correct
- **Source Modules**: production, sales, purchasing, inventory, accounting, factory, reports, auth
- **Primary Entities**: `ProductionBrand` (`production_brands`), `ProductionProduct` (`production_products`), `Dealer` (`dealers`), `Supplier` (`suppliers`)
- **Mirror / Dependent Entities**: `FinishedGood`, `RawMaterial`, `UserAccount`, `Account`
- **Master Model**: All records are company-scoped. Product catalog setup fans out into inventory/accounting mirrors; dealer and supplier setup fan out into partner ledger accounts.

### B. Business Objective
Maintain the core commercial masters that every downstream transaction reuses: what can be sold or consumed, who can buy, and who can supply. This flow establishes the operational truth that later sales orders, production logs, packing, purchase orders, goods receipts, purchase invoices, statements, GST computations, and ledgers depend on.

**What breaks if wrong:**
- Sales orders reference inactive or misconfigured SKUs and fail at reservation, tax, or posting time
- Factory execution creates WIP/finished-goods gaps because the product master lacks inventory/account metadata
- Dealers receive wrong credit exposure or missing receivable account linkage
- Suppliers can be referenced in P2P documents before approval/activation gates are satisfied
- GST splits become wrong because dealer/supplier state codes or SKU GST rates are incorrect
- Inventory valuation and reports drift because catalog mirrors do not match the master SKU truth

### C. Actors

| Actor | Role | Flow Capability |
|-------|------|-----------------|
| Admin | `ROLE_ADMIN` | Full create/update/deactivate authority across brands, catalog items, dealers, and suppliers |
| Accounting | `ROLE_ACCOUNTING` | Catalog write, supplier write/approval/activation, dealer create/update, accounting-metadata visibility |
| Sales | `ROLE_SALES` | Dealer create/update/search, catalog read, sales use of active SKU/dealer masters |
| Factory | `ROLE_FACTORY` | Catalog read only, factory consumption of product masters and readiness data |
| Dealer | `ROLE_DEALER` | No master maintenance; consumes dealer-scoped portal views only |

**Role boundary highlights:**
- Catalog mutations are restricted to `ROLE_ADMIN` and `ROLE_ACCOUNTING`
- Catalog reads are broader: `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY`
- Dealer create/update/list/search is allowed to Admin, Sales, and Accounting
- Supplier create/update/approve/activate/suspend is allowed to Admin and Accounting; Factory gets read-only supplier access

### D. Preconditions
1. Authenticated request resolves a current company via `CompanyContextService.requireCurrentCompany()`
2. Company-scoped uniqueness constraints remain intact (`brand.code`, `brand.name`, `product.sku`, `dealer.code`, `supplier.code`)
3. For catalog item creation, the referenced brand exists in the same company and is active
4. For finished-good item creation, company default accounts must be configured (inventory, COGS, revenue, tax; discount optional)
5. For raw-material item creation, either explicit inventory account metadata or a company default inventory account must resolve to a valid account
6. Dealer creation requires that the portal user email is not already mapped to another dealer portal relationship in the same company
7. Supplier creation requires a unique supplier code within the company (generated from name if omitted)
8. Supplier transactional usage later depends on the master reaching `ACTIVE`; `PENDING` and `APPROVED` remain reference-only states

### E. Trigger
- **User-initiated**: Catalog brand CRUD on `/api/v1/catalog/brands`
- **User-initiated**: Catalog item CRUD/search on `/api/v1/catalog/items`
- **User-initiated**: Catalog CSV import on `POST /api/v1/catalog/import`
- **User-initiated**: Dealer create/update/list/search on `/api/v1/dealers`
- **User-initiated**: Supplier create/update/approve/activate/suspend on `/api/v1/suppliers`
- **System-initiated side effect**: Catalog item writes synchronize `FinishedGood` or `RawMaterial` mirror truth
- **System-initiated side effect**: Dealer creation provisions/reuses a scoped portal `UserAccount` and ensures a receivable account
- **System-initiated side effect**: Supplier creation ensures a payable account and encrypts bank fields at rest

### F. Input Contract

#### CatalogBrandRequest
| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `name` | String | Yes | Non-blank, unique per company |
| `logoUrl` | String | No | Trimmed, nullable |
| `description` | String | No | Trimmed, nullable |
| `active` | Boolean | No | Defaults to `true` on create |

#### CatalogItemRequest
| Field | Type | Required | Validation |
|-------|------|----------|------------|
| `brandId` | Long | Yes | Must resolve to same-company active brand |
| `name` | String | Yes | Non-blank; becomes canonical display name basis |
| `itemClass` | String | Yes | `FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL` |
| `color` | String | Conditional | Required for finished goods |
| `size` | String | Conditional | Required for finished goods and packaging raw materials |
| `unitOfMeasure` | String | Yes | Non-blank; immutable after create |
| `hsnCode` | String | Yes | Non-blank |
| `basePrice` | BigDecimal | No | Must be `>= 0` |
| `gstRate` | BigDecimal | Yes | `0..100` |
| `minDiscountPercent` | BigDecimal | No | `0..100` |
| `minSellingPrice` | BigDecimal | No | Must be `>= 0` |
| `metadata` | Map | No | Account IDs and factory/accounting metadata |
| `active` | Boolean | No | Defaults to `true` on create |

#### CreateDealerRequest
| Field | Type | Required | Validation / Default |
|-------|------|----------|----------------------|
| `name` | String | Yes | Non-blank |
| `companyName` | String | Yes | Non-blank |
| `contactEmail` | String | Yes | Valid email; used as dealer email + portal user scope lookup |
| `contactPhone` | String | Yes | Non-blank |
| `address` | String | No | Nullable |
| `creditLimit` | BigDecimal | No | `>= 0`; defaults to zero if omitted in entity |
| `gstNumber` | String | No | Must match 15-character GSTIN if supplied |
| `stateCode` | String | No | Exactly 2 characters; normalized uppercase |
| `gstRegistrationType` | Enum | No | Defaults to `UNREGISTERED` |
| `paymentTerms` | Enum | No | Defaults to `NET_30` |
| `region` | String | No | Normalized uppercase |

#### SupplierRequest
| Field | Type | Required | Validation / Default |
|-------|------|----------|----------------------|
| `name` | String | Yes | Non-blank, max 64 |
| `code` | String | No | Unique per company; generated from name if omitted |
| `contactEmail` | String | No | Valid email if supplied |
| `contactPhone` | String | No | Max 32 |
| `address` | String | No | Max 512 |
| `creditLimit` | BigDecimal | No | `>= 0`; defaults to zero |
| `gstNumber` | String | No | Must match 15-character GSTIN if supplied |
| `stateCode` | String | No | Exactly 2 characters; normalized uppercase |
| `gstRegistrationType` | Enum | No | Defaults to `UNREGISTERED` |
| `paymentTerms` | Enum | No | Defaults to `NET_30` |
| `bankAccountName/Number/IFSC/Branch` | String | No | Encrypted before persistence when non-blank |

### G. Step-by-Step Lifecycle

#### Product Brand + Item Lifecycle (Canonical Path)
1. User creates or selects an active brand under `/api/v1/catalog/brands`
2. `CatalogService.createBrand()` normalizes the name, enforces company-local uniqueness, derives a sanitized brand code, and saves `ProductionBrand`
3. User creates an item via `POST /api/v1/catalog/items`
4. `CatalogService.createItem()` converts the REST DTO into `CatalogItemCreateCommand`
5. `ProductionCatalogService.createCatalogItem()`:
   a. resolves/validates brand  
   b. normalizes `itemClass` into catalog category  
   c. composes canonical display name  
   d. builds a canonical SKU if no custom SKU path is used internally  
   e. rejects duplicate SKUs and reserved `-BULK` suffixes  
   f. applies base price, GST, discount floor, HSN, metadata, active flag  
   g. ensures finished-good account metadata or raw-material inventory mapping  
   h. saves `ProductionProduct`
6. `syncInventoryTruth()` creates or updates the downstream inventory mirror:
   - finished goods create/update `FinishedGood`
   - raw materials create/update `RawMaterial`
   - opposite-side mirrors are deleted only when history proves that deletion is safe
7. `CatalogService.getItem()` returns a denormalized item response with stock snapshot and readiness
8. Later updates can change commercial/tax fields and metadata, but identity fields remain locked
9. Deactivation sets `is_active=false`; it does not delete history or mirrors when references exist

#### Dealer Master Lifecycle (Canonical Path)
1. User calls `POST /api/v1/dealers`
2. `DealerService.createDealer()` loads the current company and checks that the portal user email is not already mapped to another dealer relationship
3. It either reuses an existing dealer row by company+email or creates a fresh dealer with a generated company-scoped code
4. Dealer commercial and GST fields are normalized (GSTIN, state code, registration type, payment terms, region, credit limit)
5. Dealer status is set to `ACTIVE`
6. Service resolves a scoped portal user by `(email, companyCode)`:
   - if present, reuse it
   - if absent, provision a tenant account through `ScopedAccountBootstrapService` and attach `ROLE_DEALER`
7. Service ensures the dealer has an active receivable account under `AR` control when available
8. Dealer is linked to both portal user and receivable account and saved
9. Dealer list/search responses include receivable-account identifiers and current outstanding balance from `DealerLedgerService`

#### Supplier Master Lifecycle (Canonical Path)
1. User calls `POST /api/v1/suppliers`
2. `SupplierService.createSupplier()` creates the supplier in `PENDING`
3. Service normalizes contact/GST/state/payment terms, sets credit limit, encrypts bank details, and resolves/generates a unique supplier code
4. Service creates a payable account under `AP` control when available and links it to the supplier
5. Supplier remains visible for read/reference purposes, but cannot be used transactionally
6. User explicitly approves via `POST /api/v1/suppliers/{id}/approve` (`PENDING → APPROVED`)
7. User explicitly activates via `POST /api/v1/suppliers/{id}/activate` (`APPROVED/SUSPENDED → ACTIVE`)
8. Only after activation do P2P flows allow the supplier to create purchase orders, goods receipts, or purchase invoices
9. Suspension via `POST /api/v1/suppliers/{id}/suspend` moves `ACTIVE → SUSPENDED`, preserving the master but fail-closing future transactions

### H. State Machine

#### Catalog Master States
| Entity | States | Transition Notes |
|--------|--------|------------------|
| Brand | `ACTIVE`, `INACTIVE` | New brands default active; inactive brands remain readable but cannot seed new active item creation paths |
| Item | `ACTIVE`, `INACTIVE` | Identity is fixed after create; deactivation blocks future sales usage but preserves history |
| Inventory mirror | Present / blocked-delete | Mirror creation is automatic; mirror deletion is refused if stock, movements, reservations, purchasing, packing, or sales history exists |

#### Dealer Lifecycle
```
[NOT EXISTS]
    └─ createDealer() ─→ [ACTIVE]
                           │
                           ├─ updateDealer() ─→ [ACTIVE]
                           └─ legacy SalesCoreEngine.deleteDealer() ─→ [INACTIVE]
```

**Important**: the canonical `DealerController` exposes create/update/list/search only. Inactivation exists in legacy `SalesCoreEngine` service code, not on the new public dealer-master surface.

#### Supplier Lifecycle
```
[NOT EXISTS]
    └─ createSupplier() ─→ [PENDING]
                              │
                        approveSupplier()
                              ▼
                          [APPROVED]
                              │
                       activateSupplier()
                              ▼
                           [ACTIVE]
                              │
                       suspendSupplier()
                              ▼
                         [SUSPENDED]
                              │
                       activateSupplier()
                              ▼
                           [ACTIVE]
```

**Guardrail**: only `ACTIVE` suppliers pass `requireTransactionalUsage(...)`.

### I. Validation Rules

| Rule | Context | Constraint |
|------|---------|------------|
| V-MD-01 | Brand name | Must be non-blank and unique per company |
| V-MD-02 | Brand on item create | Must exist in the same company and be active |
| V-MD-03 | Item class | Must normalize to `FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL` |
| V-MD-04 | Item SKU | Must be unique per company; `-BULK` suffix is reserved |
| V-MD-05 | Finished-good identity | `brandId`, `itemClass`, color/spec, size, and unit of measure are immutable after create |
| V-MD-06 | Commercial fields | `basePrice >= 0`, `minSellingPrice >= 0`, `gstRate 0..100`, `minDiscountPercent 0..100` |
| V-MD-07 | Finished-good posting metadata | Required FG accounts must resolve directly or from company defaults |
| V-MD-08 | Raw-material inventory metadata | Explicit inventory account IDs must resolve to same-company accounts |
| V-MD-09 | Dealer portal mapping | Duplicate company+portal-user-email mappings are rejected |
| V-MD-10 | Dealer GST | GSTIN must be valid if present; state code must be exactly 2 characters |
| V-MD-11 | Dealer defaults | Missing registration type defaults to `UNREGISTERED`; payment terms default to `NET_30` |
| V-MD-12 | Supplier code | Unique per company; auto-generated from normalized name if absent |
| V-MD-13 | Supplier bank fields | Non-blank bank details are encrypted before persistence |
| V-MD-14 | Supplier transitions | `PENDING→APPROVED`, `APPROVED/SUSPENDED→ACTIVE`, `ACTIVE→SUSPENDED` only |
| V-MD-15 | Supplier transactional gate | Purchase flows reject suppliers unless status is `ACTIVE` |
| V-MD-16 | Catalog import | File must be CSV; idempotency key/signature must match replayed payload |

### J. Side Effects

| Action | Side Effects |
|--------|-------------|
| Brand create/update/deactivate | `ProductionBrand` save; generated code remains the stable identifier for downstream SKU generation |
| Item create/update | `ProductionProduct` save; mirror sync into `FinishedGood` or `RawMaterial`; readiness changes ripple to factory/sales/accounting stages |
| Catalog import | `CatalogImport` record save; per-row upsert; `DATA_CREATE` audit event on success |
| Dealer create | Dealer row save; portal user provision/reuse; `ROLE_DEALER` attachment; receivable account creation/reactivation |
| Dealer update | Dealer commercial/GST master fields mutate; outstanding balance is re-derived from ledger service, not directly edited |
| Supplier create | Supplier row save in `PENDING`; payable account creation; encrypted bank details persisted |
| Supplier approve/activate/suspend | Status-only transition, but downstream purchasing eligibility changes immediately |

### K. Inventory Effect
Product masters are inventory-shaping masters, not stock-movement transactions.

- Finished-good catalog items auto-create or synchronize `FinishedGood` records with valuation/COGS/revenue/tax/discount account bindings
- Raw-material and packaging catalog items auto-create or synchronize `RawMaterial` records with unit, GST rate, material type, costing method, and inventory account linkage
- Creating or updating masters does **not** change on-hand quantity directly
- Deleting incompatible mirrors is fail-closed when stock, movements, reservations, packing mappings, purchase references, or sales references already exist
- Dealer and supplier masters have **no direct stock movement**, but they gate which downstream documents can create inventory effects

### L. Accounting Effect
No journal entry is posted at master-creation time, but the accounting surface is established here.

- Finished-good items require posting metadata that downstream O2C and factory flows rely on
- Raw-material items require a valid inventory account mapping for valuation and purchase intake
- Dealer creation creates a receivable account (`AR-*`) that later anchors sales journals, receipts, statements, and aging
- Supplier creation creates a payable account (`AP-*`) that later anchors purchase journals, settlements, statements, and aging
- If these accounts are missing or invalid, downstream posting fails closed instead of guessing

### M. Tax Effect
This flow sets tax master truth even though it does not post tax by itself.

- Product `gstRate` and `hsnCode` are the default tax master values used by sales-order pricing and reporting
- Dealer `stateCode` drives intra-state vs inter-state GST splitting together with company state code
- Supplier `stateCode` does the same on purchase invoice and purchase return GST splits
- Dealer and supplier `gstRegistrationType` default to `UNREGISTERED`, preventing null tax-class ambiguity
- Raw-material purchase taxation uses raw-material GST rate first, then company default GST rate if needed; mixing GST and non-GST materials in a single purchase invoice is blocked

### N. Failure Handling

| Failure Scenario | Behavior | Recovery |
|-----------------|----------|----------|
| Duplicate brand name/code | Request fails before save | Use a different name/code |
| Brand inactive during item create | Invalid state | Reactivate brand or choose another |
| Duplicate or reserved SKU | Invalid input | Create a distinct SKU / avoid `-BULK` suffix |
| Missing finished-good defaults | Invalid state with company-default-accounts message | Configure default accounts first |
| Invalid metadata account reference | Invalid input naming offending SKU/account key | Supply a valid same-company account |
| Item identity mutation attempt | Invalid input (`itemClass`, brand, color/spec, size, unit) | Create a new SKU instead |
| Dealer portal email already mapped | Invalid input | Reuse/update existing dealer mapping intentionally |
| Invalid GSTIN / state code | Validation error | Correct the master data |
| Supplier not active in P2P flow | Invalid state with “reference only” explanation | Approve/activate supplier first |
| Catalog import replay with different payload | Idempotency mismatch | Retry with matching file/key or new key |

### O. Auditability
Audit coverage is uneven across this flow.

- `ProductionBrand`, `ProductionProduct`, `Dealer`, and `Supplier` all carry stable `publicId` values and persistence timestamps, which helps trace records across modules
- Catalog import explicitly logs a `DATA_CREATE` audit event with idempotency key, file hash, and row counts
- Supplier approval-policy objects (`SupplierApprovalDecision`) produce immutable maker/checker metadata, but the current supplier master CRUD endpoints do **not** capture that object during approve/activate actions
- Dealer and supplier CRUD services do not emit dedicated `AuditService` events in the same way Flow 1 and Flow 2 do
- As a result, downstream financial documents usually provide stronger audit evidence than the master mutations that enabled them

### P. Reporting Impact
- Catalog item lists expose stock and readiness snapshots used by setup and factory-adjacent users
- Inventory valuation and related reports group or display brand/product master attributes
- Aged debtors, dealer statements, and credit posture views depend on dealer master + receivable account linkage
- Supplier balances, statements, settlements, and AP reconciliation depend on supplier master + payable account linkage
- GST and purchase/sales reports consume SKU GST rate, HSN, and partner state-code truth from these masters

### Q. Completion Criteria

**Business terms**
- A sellable SKU can be created, searched, and read with stable identity and readiness
- A dealer can be created with portal access, credit posture, GST identity, and receivable linkage
- A supplier can be created, approved, activated, suspended, and used only when active

**System terms**
- `ProductionBrand`, `ProductionProduct`, `Dealer`, and `Supplier` rows exist in the expected company scope
- Finished-good or raw-material mirrors are synchronized to the catalog SKU truth
- Dealer has `portalUser` + `receivableAccount`
- Supplier has `payableAccount`
- Readiness, search, and downstream document lookups resolve these masters without fallback/manual repair

### R. Edge Cases

| Edge Case | Expected Behavior |
|-----------|------------------|
| Existing scoped portal user with matching dealer email | Dealer creation reuses the user and does not send new credentials |
| Existing dealer by email but no portal mapping yet | Service can reuse/update the dealer row and then attach portal user/account truth |
| Sales/factory user reads catalog | Read succeeds, but accounting metadata is sanitized to `ACCOUNTING_CONFIGURATION_REQUIRED`-style blockers where needed |
| Item deactivation after historical use | SKU becomes inactive for future sales, but history and mirrors remain preserved |
| Attempt to flip item class after purchasing/factory/sales history | Rejected with explicit guidance to create a new SKU or reverse history |
| Supplier approved but not activated | Visible for reference, but purchase flows reject transactional use |
| Suspended supplier | Still readable, but all P2P transactional usage fails closed |
| Packaging raw material without inventory account metadata | Falls back to company default inventory account when configured; otherwise readiness blocks remain |

### S. Non-Negotiables
1. **Company scope is the hard boundary** — all brand, product, dealer, and supplier lookups are company-bound
2. **Catalog SKU identity is stable** — brand, class, color/spec, size, and unit-of-measure are not mutable in place once the SKU exists
3. **Mirror truth is automatic** — product masters are not valid unless their inventory mirror state converges
4. **Finished goods must be posting-ready** — missing FG account defaults are a blocker, not a warning
5. **Dealer creation must converge auth + accounting truth** — portal user and receivable account cannot be optional side channels
6. **Supplier transactional usage is fail-closed** — only `ACTIVE` suppliers may drive PO/GRN/purchase-invoice flows
7. **GST/state master data matters operationally** — state-code and GST-rate mistakes propagate into tax splits and cannot be treated as cosmetic
8. **Deactivation preserves history** — this flow prefers inactive/reference-only states over destructive deletion when downstream references exist

### T. Open Decisions

#### D-MD-001: Dealer Master Ownership
**Question**: Should dealer master truth stay on `DealerService`, or should the legacy `SalesCoreEngine` dealer CRUD path be removed or explicitly demoted?
**Context**: The canonical public write surface is `/api/v1/dealers`, but `SalesCoreEngine` still carries its own create/update/delete dealer logic and account-sync behavior.
**Owner**: Founder

#### D-MD-002: Supplier Approval Evidence Gap
**Question**: Should supplier approval/activation endpoints require a `SupplierApprovalDecision` payload with maker/checker metadata?
**Context**: Approval-policy primitives exist and are tested, but `SupplierService.approveSupplier()` and `activateSupplier()` currently perform status flips without that approval object.
**Owner**: Founder

#### D-MD-003: Master Data Audit Coverage
**Question**: Should dealer/supplier/catalog CRUD emit dedicated `AuditService` events?
**Context**: Flow 1 and Flow 2 are richly audited; Flow 3 currently has explicit audit only for catalog import.
**Owner**: Founder

#### D-MD-004: Supplier Reject / Archive State
**Question**: Is `PENDING/APPROVED/ACTIVE/SUSPENDED` sufficient, or does the business need explicit `REJECTED` / `ARCHIVED` supplier states?
**Context**: Current model allows suspension and reference-only visibility but no explicit rejection terminal state.
**Owner**: Founder

#### D-MD-005: Brand Rename Semantics
**Question**: Should updating a brand name ever cascade into brand code or downstream SKU regeneration?
**Context**: Current implementation lets the display name change while leaving existing brand code and generated SKU identities stable.
**Owner**: Founder

### Current Implementation Map

#### API Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/v1/catalog/brands` | ADMIN, ACCOUNTING | Create brand |
| `GET` | `/api/v1/catalog/brands` | ADMIN, ACCOUNTING, SALES, FACTORY | List brands |
| `PUT` | `/api/v1/catalog/brands/{brandId}` | ADMIN, ACCOUNTING | Update brand |
| `DELETE` | `/api/v1/catalog/brands/{brandId}` | ADMIN, ACCOUNTING, SALES, FACTORY (controller-level broad guard) | Deactivate brand |
| `POST` | `/api/v1/catalog/items` | ADMIN, ACCOUNTING | Create item |
| `GET` | `/api/v1/catalog/items` | ADMIN, ACCOUNTING, SALES, FACTORY | Search items |
| `PUT` | `/api/v1/catalog/items/{itemId}` | ADMIN, ACCOUNTING | Update item |
| `DELETE` | `/api/v1/catalog/items/{itemId}` | ADMIN, ACCOUNTING, SALES, FACTORY (controller-level broad guard) | Deactivate item |
| `POST` | `/api/v1/catalog/import` | ADMIN, ACCOUNTING | CSV import with idempotency |
| `POST` | `/api/v1/dealers` | ADMIN, SALES, ACCOUNTING | Create dealer |
| `PUT` | `/api/v1/dealers/{dealerId}` | ADMIN, SALES, ACCOUNTING | Update dealer |
| `GET` | `/api/v1/dealers` | ADMIN, SALES, ACCOUNTING | List dealers |
| `GET` | `/api/v1/dealers/search` | ADMIN, SALES, ACCOUNTING | Search dealers |
| `POST` | `/api/v1/suppliers` | ADMIN, ACCOUNTING | Create supplier |
| `PUT` | `/api/v1/suppliers/{id}` | ADMIN, ACCOUNTING | Update supplier |
| `POST` | `/api/v1/suppliers/{id}/approve` | ADMIN, ACCOUNTING | Move to approved |
| `POST` | `/api/v1/suppliers/{id}/activate` | ADMIN, ACCOUNTING | Move to active |
| `POST` | `/api/v1/suppliers/{id}/suspend` | ADMIN, ACCOUNTING | Suspend active supplier |

#### Key Services

| Service | Module | Responsibility |
|---------|--------|---------------|
| `CatalogService` | production | Public catalog DTO orchestration, search/read sanitization |
| `ProductionCatalogService` | production | Canonical create/update/import logic, SKU generation, mirror sync |
| `SkuReadinessService` | production | Cross-stage readiness/blocker computation |
| `DealerService` | sales | Canonical dealer master CRUD + portal user/account convergence |
| `SupplierService` | purchasing | Canonical supplier master CRUD + lifecycle gating |
| `PurchaseOrderService` | purchasing | Enforces supplier active-state transactional gate |
| `GoodsReceiptService` | purchasing | Enforces supplier active-state and PO receivability |
| `PurchaseInvoiceEngine` | purchasing | Enforces supplier payable-account + GST/state master usage |

### Canonical Path Analysis

#### `[CANONICAL]` Catalog Item Creation Path

```text
CatalogController.createItem()
  → CatalogService.createItem()
    → ProductionCatalogService.createCatalogItem()
      → resolveBrand()
      → buildCanonicalItemCode()
      → ensureFinishedGoodAccounts() / resolveRawMaterialInventoryAccountId()
      → ProductionProductRepository.save()
      → syncInventoryTruth()
        → ensureCatalogFinishedGood() or syncRawMaterial()
```

#### `[CANONICAL]` Dealer Creation Path

```text
DealerController.createDealer()
  → DealerService.createDealer()
    → DealerRepository.findAllByCompanyAndPortalUserEmailIgnoreCase()
    → DealerRepository.findByCompanyAndEmailIgnoreCase()
    → DealerProvisioningSupport.generateDealerCode()
    → ScopedAccountBootstrapService.provisionTenantAccount() [if portal user missing]
    → RoleService.ensureRoleExists("ROLE_DEALER")
    → DealerProvisioningSupport.createReceivableAccount()
    → DealerRepository.save()
```

#### `[CANONICAL]` Supplier Create → Approve → Activate Path

```text
SupplierController.createSupplier()
  → SupplierService.createSupplier()
    → resolveSupplierCode()
    → applyBankDetails() [CryptoService.encrypt]
    → createPayableAccount()
    → SupplierRepository.save(status=PENDING)

SupplierController.approveSupplier()
  → SupplierService.approveSupplier() [PENDING → APPROVED]

SupplierController.activateSupplier()
  → SupplierService.activateSupplier() [APPROVED/SUSPENDED → ACTIVE]
```

#### `[NON-CANONICAL]` Dealer Legacy Service Path

```text
SalesService.createDealer()
  → SalesDealerCrudService.createDealer()
    → SalesCoreEngine.createDealer()
```

**Note**: this path still exists in service code, but the public write controller for Flow 3 is `DealerController`, not `SalesController`.

### Cross-Module Coupling Map

```text
production module
  → inventory module: ProductionCatalogService → FinishedGoodRepository, RawMaterialRepository
  → accounting module: ProductionCatalogService → CompanyDefaultAccountsService
  → factory module: SkuReadinessService → PackagingSizeMappingRepository
  → purchasing module: ProductionCatalogService → PurchaseOrderRepository, GoodsReceiptRepository, RawMaterialPurchaseRepository
  → sales module: ProductionCatalogService → SalesOrderItemRepository
  → core/audit: ProductionCatalogService → AuditService (catalog import only)

sales module (dealer master)
  → auth module: DealerService → UserAccountRepository, ScopedAccountBootstrapService
  → rbac module: DealerService → RoleService
  → accounting module: DealerService → AccountRepository, DealerLedgerService, StatementService
  → sales module: DealerService → SalesOrderRepository (credit exposure)

purchasing module (supplier master)
  → accounting module: SupplierService → AccountRepository, SupplierLedgerService
  → core/security: SupplierService → CryptoService
  → purchasing module: PurchaseOrderService/GoodsReceiptService/PurchaseInvoiceEngine → Supplier.requireTransactionalUsage()
  → accounting module: PurchaseInvoiceEngine → GstService, payable-account posting
```

#### Tight Coupling Points

| Source | Target | Type | Severity |
|--------|--------|------|----------|
| `ProductionCatalogService` | inventory repositories | Direct mirror synchronization | **HIGH** |
| `ProductionCatalogService` | purchasing/sales repositories | Historical-reference guard checks before mirror deletion | **HIGH** |
| `DealerService` | auth provisioning services | Dealer master directly provisions login identity | **HIGH** |
| `DealerService` | accounting repositories/services | Dealer master directly ensures AR account and ledger-derived balance | **HIGH** |
| `SupplierService` | `CryptoService` + `AccountRepository` | Supplier master owns both data protection and AP-account creation | **MEDIUM** |

### Current Maturity Grade

**Grade: 3 / 5** — operationally strong, but ownership is still split

| Dimension | Score | Rationale |
|-----------|-------|-----------|
| Completeness | 4/5 | Product, dealer, and supplier masters all have working lifecycle surfaces |
| Correctness | 4/5 | Strong validation, immutable SKU identity, supplier fail-closed gating, portal/account convergence |
| Auditability | 2/5 | Import is audited, but ordinary dealer/supplier/catalog CRUD lacks dedicated audit events |
| Architecture | 3/5 | Canonical paths are clear, but dealer and catalog ownership still span multiple services/modules |
| Cross-module safety | 3/5 | Downstream guards are explicit, yet coupling is tight and highly stateful |

### Done Checklist

| # | Check | Status |
|---|-------|--------|
| 1 | Brand creation is company-scoped and uniqueness-protected | ✅ Pass |
| 2 | Catalog items generate stable canonical SKUs | ✅ Pass |
| 3 | Finished-good items synchronize finished-good mirror truth | ✅ Pass |
| 4 | Raw-material items synchronize raw-material mirror truth | ✅ Pass |
| 5 | Historical references block unsafe item-class flips or mirror deletion | ✅ Pass |
| 6 | Dealer creation converges dealer row, portal user, and receivable account | ✅ Pass |
| 7 | Dealer GST/state/terms normalization is enforced | ✅ Pass |
| 8 | Supplier creation starts in `PENDING` with payable account linkage | ✅ Pass |
| 9 | Supplier approval/activation/suspension transitions are guard-railed | ✅ Pass |
| 10 | P2P flows reject non-active suppliers with clear fail-closed messages | ✅ Pass |
| 11 | Catalog reads expose readiness while hiding accounting-only metadata from non-accounting viewers | ✅ Pass |
| 12 | Catalog import is CSV-only and idempotent | ✅ Pass |

---
