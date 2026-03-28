# ERP Definition of Done Bible

> **Version:** 1.0
> **Last Updated:** 2026-03-28
> **Purpose:** Authoritative reference for all 14 P0 flows. Every claim is verified against source code.

This document is the single source of truth for what each ERP flow does, who can do what, what state changes happen, what cannot happen, and what evidence proves it works.

---


cat >> /Users/anas/Documents/Factory/bigbrightpaints-erp/docs/ERP-DOD-BIBLE.md << 'APPEND_EOF'

# Append the actual flow content - using a heredoc approach to avoid escaping issues
cat >> /Users/anas/Documents/Factory/bigbrightpaints-erp/docs/ERP-DOD-BIBLE.md << 'APPEND_EOF'
echo "Appending flow 1 content..."

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
