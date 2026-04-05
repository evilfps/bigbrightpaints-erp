# Auth / Identity Flow

Last reviewed: 2026-03-30

This packet documents the **auth/identity flow**: the canonical authentication and identity lifecycle from credential entry through session termination. It covers login, token refresh, logout, MFA, password management, session revocation, and tenant-scoping behavior.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Tenant user** | Regular user with `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY`, or custom role | Own tenant only (`companyCode` from login) |
| **Tenant admin** | User with `ROLE_ADMIN` | Own tenant, can manage other tenant users |
| **Super admin** | User with `ROLE_SUPER_ADMIN` | Platform-wide, can access `/api/v1/superadmin/**` endpoints |
| **Dealer user** | Authenticated via dealer-portal, mapped to `ROLE_DEALER` | Dealer self-service host (`/api/v1/dealer-portal/**`) |

---

## 2. Entrypoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Login | POST | `/api/v1/auth/login` | Public | Primary credential entry; issues JWT access token + refresh token |
| Token refresh | POST | `/api/v1/auth/refresh-token` | Public | Rotate refresh token for new access token |
| Logout | POST | `/api/v1/auth/logout` | Authenticated | Revoke sessions and blacklist access token |
| `/me` | GET | `/api/v1/auth/me` | Authenticated | Current user identity, roles, permissions |
| Password change | POST | `/api/v1/auth/password/change` | Authenticated | Authenticated password change |
| Password forgot | POST | `/api/v1/auth/password/forgot` | Public | Request password reset email |
| Password reset | POST | `/api/v1/auth/password/reset` | Public | Complete password reset with token |
| MFA setup | POST | `/api/v1/auth/mfa/setup` | Authenticated | Begin TOTP enrollment |
| MFA activate | POST | `/api/v1/auth/mfa/activate` | Authenticated | Confirm TOTP enrollment |
| MFA disable | POST | `/api/v1/auth/mfa/disable` | Authenticated | Disable MFA |
| Admin force-reset | POST | `/api/v1/admin/users/{userId}/force-reset-password` | `ROLE_ADMIN` | Admin triggers password reset for user |
| Super-admin support reset | POST | `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `ROLE_SUPER_ADMIN` | Super-admin issues the canonical tenant-admin reset-link recovery action |

---

## 3. Preconditions

### Login Preconditions

1. **Company code must be valid** — `companyCode` must match an existing active tenant
2. **User must exist** — `(email, companyCode)` pair must resolve to an enabled user account
3. **Account must be enabled** — `enabled = true` on `app_users`
4. **Account must not be locked** — `lockedUntil` must be null or in the past
5. **Password must match** — BCrypt hash comparison succeeds
6. **MFA must be satisfied** — if `mfaEnabled = true`, valid TOTP or recovery code required
7. **Tenant runtime must allow auth** — `TenantRuntimeEnforcementService` must not block the request (`HOLD` allows login, `BLOCKED` denies)

### Refresh Preconditions

1. **Refresh token must exist and be valid** — single-use, not expired, digest matches
2. **User must still be enabled** — account not disabled after token issuance
3. **Tenant runtime must allow refresh** — same runtime check as login

### Password Reset Preconditions

1. **User must exist** — `(email, companyCode)` pair resolves to a user
2. **User must be enabled** — disabled users are silently skipped (enumeration protection)
3. **Reset token must be valid** — not used, not expired, digest matches

---

## 4. Lifecycle

### 4.1 Login Lifecycle

```
[Start] → Validate companyCode → Resolve user → Check enabled → Check lockout 
→ Verify password → [Optional: MFA challenge] → Reset lockout counters → 
Emit audit event → Issue JWT + refresh token → [End: Authenticated session]
```

**Key behaviors:**
- Company code is required in request body (`companyCode` field)
- Lockout counter increments on failed password OR failed MFA attempts
- At 5 failed attempts, account locks for 15 minutes
- MFA challenge returns `428 Precondition Required` with `{"required": true}` if MFA is enabled but not provided
- JWT access token is short-lived (15 minutes default), refresh token is long-lived (30 days)
- `mustChangePassword` flag in response is advisory only — backend still issues full tokens

### 4.2 Refresh Lifecycle

```
[Start] → Consume refresh token (delete it) → Re-validate user enabled → 
Re-validate company → Check tenant runtime → Issue new JWT + new refresh token → [End]
```

**Key behaviors:**
- Refresh tokens are **single-use** — each refresh deletes the old token and issues a new one
- Token is consumed **before** company/user validation — a bad company code or disabled user burns the refresh token
- Re-validation includes enabled check, lock check, tenant runtime admission

### 4.3 Logout Lifecycle

```
[Start] → Parse access token → Revoke all refresh tokens → 
Blacklist access token by jti → [End]
```

**Key behaviors:**
- Access token blacklisting is best-effort — failures are logged but don't fail the request
- If refresh token is provided in query param, only that token is revoked; otherwise all tokens for the user are revoked

### 4.4 Password Reset Lifecycle

```
[Start] → Validate companyCode + email → Generate reset token → 
Send reset email → [End: Reset link delivered]

[Start: Reset link] → Validate reset token → Validate new password against policy → 
Check password history → Revoke all sessions → Update password → 
Mark token used → [End: Password changed]
```

**Key behaviors:**
- Public forgot-password always returns generic success response to prevent email enumeration
- Known scoped accounts fail closed when reset token storage or email delivery fails
- Admin-initiated reset (force-reset-password) and support reset reuse the same reset-link machinery

### 4.5 MFA Lifecycle

```
[Setup] → Generate secret + recovery codes → Encrypt secret → Store on user → 
Return secret + otpauth URI + recovery codes → [End: Awaiting activation]

[Activate] → Validate TOTP code → Set mfa_enabled = true → [End: MFA active]

[Login with MFA] → Require TOTP or recovery code → Validate → 
[Success: Issue tokens] or [Failure: 401]
```

**Key behaviors:**
- Recovery codes are single-use — each use consumes one code
- Admin can disable MFA for another user via `PATCH /api/v1/admin/users/{id}/mfa/disable`
- The backend stores MFA secrets encrypted (AES-256-GCM)

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Login** — User receives a valid JWT access token and refresh token pair
2. **Refresh** — User receives a new access token and refresh token (old refresh token consumed)
3. **Logout** — All refresh tokens are revoked and access token is blacklisted
4. **Password reset** — User password is changed, all sessions are revoked, reset token is consumed

### Current Limitations

1. **MFA table unused** — A dedicated `mfa_recovery_codes` table exists but the live service uses the legacy comma-delimited `app_users.mfa_recovery_codes` column instead. The relational model is bypassed.

2. **Refresh burns token before validation** — The refresh token is consumed (deleted) before company/user validation. A caller using the wrong company code or hitting a tenant denial still loses their refresh token.

3. **Lockout not enforced on existing tokens** — When an account is locked (`lockedUntil` set), existing bearer tokens are not invalidated. `JwtAuthenticationFilter` checks `enabled` but not `lockedUntil`.

4. **Brute-force monitoring not active** — `SecurityMonitoringService` exists but has no production callers. Brute-force monitoring and token-revocation analytics are architectural dead weight.

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/auth/login` | `AuthController` / `AuthService` | Primary credential entry, issues JWT + refresh |
| `POST /api/v1/auth/refresh-token` | `AuthController` / `AuthService` | Single-use rotating refresh |
| `POST /api/v1/auth/logout` | `AuthController` / `AuthService` | Session revocation |
| `POST /api/v1/auth/password/forgot` | `AuthController` / `PasswordResetService` | Public reset request |
| `POST /api/v1/auth/password/reset` | `AuthController` / `PasswordResetService` | Public reset completion |
| `POST /api/v1/auth/mfa/setup` | `MfaController` / `MfaService` | MFA enrollment |
| `POST /api/v1/admin/users/{userId}/force-reset-password` | `AdminUserController` | Admin password reset |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| Legacy super-admin forgot-password alias | Deprecated (V157 hard cut) | Use `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` |
| Email-only recovery identity | Deprecated | Require `email + companyCode` |
| `companyId` auth alias | Deprecated | Use `companyCode` claim in JWT |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `company` | Tenant context resolution, runtime policy enforcement | Read |
| `rbac` | Role resolution for `/me` permissions and authorities | Read |
| `admin` | Admin user lifecycle (force-reset, status update) | Write via service |
| `accounting` | None directly — auth is platform boundary | N/A |

---

## 8. Security Considerations

- **Bearer token auth** — No CSRF, form login, HTTP basic, or server-side logout
- **HS256 JWT** — Access tokens signed with 32-byte minimum secret
- **Company-context filter** — Rejects mismatched token claims vs company headers
- **Rate limiting** — Password reset is rate-limited per email+scope+operation
- **Token blacklist** — Logout and password change revoke tokens, but blacklist persistence failures are logged not failed

---

## 9. Related Documentation

- [docs/modules/auth.md](../modules/auth.md) — Auth module canonical packet
- [docs/modules/company.md](../modules/company.md) — Tenant lifecycle and runtime enforcement
- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md) — Admin and RBAC boundaries
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| MFA recovery code table unused | Live service uses column, not relational table. Table exists for potential future enhancement. |
| Brute-force monitoring | Service exists but has no production callers. |
