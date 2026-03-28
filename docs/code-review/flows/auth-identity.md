# Auth / identity

## Scope and evidence

This review covers login, refresh, logout, `/me`, password change, public forgot/reset password, tenant-admin and super-admin reset paths, MFA setup/activation/disable/login challenges, disabled-user handling, and lockout behavior.

Current-state note:

- Auth V2 hard cut is now active on this branch. Older review notes about the retired super-admin forgot-password alias, email-only recovery identity, legacy `cid` / `companyId` auth aliases, and plaintext onboarding-password responses are resolved and should not be treated as live contract.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/controller/{AuthController,MfaController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/{AuthService,RefreshTokenService,PasswordResetService,PasswordService,MfaService,TenantAdminProvisioningService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{SecurityConfig,JwtAuthenticationFilter,JwtTokenService,JwtProperties,CompanyContextFilter,TokenBlacklistService,CryptoService,SecurityMonitoringService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/{controller/AdminUserController.java,service/AdminUserService.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/{controller/CompanyController.java,service/CompanyService.java,dto/CompanyAdminCredentialResetDto.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/{UserAccount,RefreshToken,PasswordResetToken,UserPasswordHistory,UserTokenRevocation,BlacklistedToken,MfaRecoveryCode}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V1__core_auth_rbac.sql,V157__drop_legacy_user_reset_columns.sql}` and legacy migrations `V13__mfa_support.sql`, `V32__mfa_recovery_codes_table.sql`, `V37__password_reset_tokens.sql`, `V51__auth_lockout_controls.sql`, `V61__token_blacklist_tables.sql`, `V77__user_must_change_password.sql`, `V112__refresh_tokens.sql`
- `erp-domain/src/main/resources/{application.yml,application-prod.yml}` and `erp-domain/src/main/java/com/bigbrightpaints/erp/{config/CorsConfig.java,core/config/SystemSettingsService.java,core/notification/EmailService.java}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/{AuthControllerIT,AuthAuditIT,AuthDisabledUserTokenIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,MfaControllerIT,TenantRuntimeEnforcementAuthIT,CompanyContextFilterPasswordResetBypassTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/{PasswordResetServiceTest,MfaServiceTest,PasswordServiceTest}.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/service/AdminUserServiceTest.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Session bootstrap | `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh-token`, `POST /api/v1/auth/logout` | `AuthController` | Login/refresh issue JWT + opaque refresh token for exactly one scoped account; logout revokes refresh tokens and blacklists the current access token when possible. |
| Identity read/write | `GET /api/v1/auth/me`, `POST /api/v1/auth/password/change` | `AuthController` | `/me` is claim/context-derived and is the only canonical frontend identity read; password change uses password policy + history checks. |
| Public password reset | `POST /api/v1/auth/password/forgot`, `POST /api/v1/auth/password/reset` | `AuthController` | Public and explicitly bypass tenant binding; forgot-password requires `email + companyCode` and reset tokens are issued per scoped account. |
| Self-service MFA | `POST /api/v1/auth/mfa/setup`, `POST /api/v1/auth/mfa/activate`, `POST /api/v1/auth/mfa/disable` | `MfaController` | Setup returns the raw secret, otpauth URI, and recovery codes; activation/disable verify TOTP or recovery code. |
| Admin user reset / status / MFA override | `POST /api/v1/admin/users/{userId}/force-reset-password`, `PUT /api/v1/admin/users/{userId}/status`, `PATCH /api/v1/admin/users/{id}/mfa/disable` | `AdminUserController` | Tenant-admin surface only. Platform super admins must stay on `/api/v1/superadmin/tenants/**` support/control-plane routes. |
| Super-admin support reset | `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `SuperAdminController` | Platform control-plane path that issues the same scoped reset-link workflow for the target admin and requires successful email delivery. |

## Endpoint-to-entity path

| Flow | Call chain | Entity / store touchpoints |
| --- | --- | --- |
| Login | `AuthController.login` -> `AuthService.login` -> `UserAccountRepository` + `AuthenticationManager` + `CompanyRepository` + `TenantRuntimeEnforcementService` + `MfaService` + `JwtTokenService` + `RefreshTokenService` + `AuditService` | `app_users` (including `company_id`), `user_roles`, `refresh_tokens`, `audit_logs` |
| Refresh | `AuthController.refresh` -> `AuthService.refresh` -> `RefreshTokenService.consume` -> `TokenBlacklistService` -> `CompanyRepository` -> `TenantRuntimeEnforcementService` -> `JwtTokenService` -> `RefreshTokenService.issue` | `refresh_tokens`, `user_token_revocations`, `app_users` |
| Logout | `AuthController.logout` -> `AuthService.logout` -> `RefreshTokenService.revoke*` + `TokenBlacklistService.blacklistToken` | `refresh_tokens`, `blacklisted_tokens` |
| `/me` | `JwtAuthenticationFilter` + `CompanyContextFilter` -> `AuthController.me` | JWT claims, `app_users`, `user_roles` |
| Profile fields at rest | no public self-service API | `app_users` (`display_name`, `preferred_name`, `job_title`, `profile_picture_url`, `phone_secondary`, `secondary_email`) |
| Password change | `AuthController.changePassword` -> `PasswordService.changePassword` | `app_users.password_hash`, `app_users.must_change_password`, `user_password_history` |
| Public forgot/reset | `AuthController.{forgotPassword,resetPassword}` -> `PasswordResetService` -> `PasswordService.resetPassword` -> `TokenBlacklistService` + `RefreshTokenService` + `EmailService` | `password_reset_tokens`, `app_users`, `blacklisted_tokens`, `user_token_revocations`, `refresh_tokens` |
| Admin link reset | `AdminUserController.forceResetPassword` -> `AdminUserService.forceResetPassword` -> `PasswordResetService.requestResetByAdmin` | `app_users`, `password_reset_tokens` |
| Super-admin support reset | `SuperAdminController.resetTenantAdminPassword` -> `SuperAdminService.resetTenantAdminPassword` -> `TenantAdminProvisioningService.resetTenantAdminPassword` -> `PasswordResetService.requestResetByAdmin` | `app_users`, `password_reset_tokens`, `blacklisted_tokens`, `user_token_revocations`, `refresh_tokens`, `audit_logs` |
| MFA lifecycle | `MfaController` -> `MfaService` and `AdminUserController` -> `AdminUserService.disableMfa` | `app_users.mfa_secret`, `app_users.mfa_enabled`, `app_users.mfa_recovery_codes`; `mfa_recovery_codes` table exists but is not used by the live service path |

## 1. Login and token issuance narrative

`POST /api/v1/auth/login` is the only first-class credential entrypoint. `AuthService.login(...)` normalizes the supplied `companyCode` into an auth scope, loads `UserAccount` by `(normalized_email, auth_scope_code)`, rejects disabled accounts up front, enforces a 5-attempt / 15-minute lockout window, and verifies the password. Platform scope is valid only for `ROLE_SUPER_ADMIN`; tenant scope requires the same scoped account plus matching tenant membership.

Before any token is minted, tenant runtime policy is consulted through `TenantRuntimeEnforcementService.enforceAuthOperationAllowed(...)`. That means login can fail with runtime-policy semantics (`423 Locked` for hold, `403 Forbidden` for blocked tenants, `429 Too Many Requests` for active-user quota breaches) even when the username/password pair is valid; `TenantRuntimeEnforcementAuthIT` proves those behaviors and the accompanying audit-chain metadata.

If the user has MFA enabled, `MfaService.verifyDuringLogin(...)` requires either a valid 6-digit TOTP or a single-use recovery code. Missing MFA material raises `MfaRequiredException`, which `CoreFallbackExceptionHandler` converts into `428 Precondition Required` with `{"required": true}`; invalid MFA input raises `401`. Successful login resets `failed_login_attempts` and `locked_until`, writes a login audit event, signs an HS256 access token carrying `companyCode` plus `iatMs` claim data, and persists an opaque refresh token row.

Notable side effects:

- Access token TTL is 15 minutes by default in `application-prod.yml`; refresh token TTL is 30 days.
- `AuthResponse` returns `mustChangePassword`, but the flag is advisory only; the backend still issues full bearer tokens.
- Bad credentials and MFA failures increment the same lockout counter; tenant runtime denials and disabled-account failures do not.

## 2. Refresh rotation and logout narrative

`POST /api/v1/auth/refresh-token` is a rotating-token flow, not a passive re-sign. `RefreshTokenService.consume(...)` pessimistically locks the row, rejects expired tokens, deletes the row, and returns the embedded `userEmail` plus issuance timestamps. `AuthService.refresh(...)` then checks user-wide token revocation, re-loads the account, re-applies enabled/lock checks, re-resolves the target company, re-applies tenant runtime policy, and issues a brand-new access token plus a brand-new refresh token row.

Two important implications fall out of that ordering:

1. Refresh tokens are one-time-use, which is good for replay resistance.
2. The token is consumed **before** company validation, disabled-user checks, or runtime admission. A wrong `companyCode`, a freshly disabled user, or a tenant-quota denial burns the refresh token anyway.

`POST /api/v1/auth/logout` requires an authenticated bearer session. `AuthController.logout(...)` extracts the current access token from the Spring `Authentication` object and passes an optional `refreshToken` query parameter to `AuthService.logout(...)`. If a refresh token is supplied, only that token is deleted; if not, the service revokes all refresh tokens for the token subject. The access token is blacklisted by `jti` only when the bearer token can still be parsed and contains both `jti` and `exp`. Blacklist persistence failures are logged but do not fail the request.

## 3. `/me` narrative

`GET /api/v1/auth/me` is entirely claim/context-derived. `JwtAuthenticationFilter` authenticates the bearer token, `CompanyContextFilter` reconciles the token's `companyCode` claim against `X-Company-Code`, and `AuthController.me(...)` assembles a response from the authenticated `UserPrincipal`. The payload contains email, display name, current company code, `mfaEnabled`, `mustChangePassword`, flattened roles, and flattened permissions with no legacy `companyId` alias.

`/api/v1/auth/profile` is retired in the hard-cut contract. The `app_users` profile columns still exist at rest, but there is no public self-service CRUD surface for them. Frontend identity bootstraps from `/api/v1/auth/me` only, and profile-shape drift is no longer part of the runtime auth contract.

## 4. Password change narrative

`POST /api/v1/auth/password/change` stays inside the authenticated identity surface and never touches reset tokens. `PasswordService.changePassword(...)` normally requires the current password, but it explicitly skips that check when `mustChangePassword=true` so that temporary-password flows do not dead-end. It then enforces confirmation equality, disallows reusing the current password, validates the password against the built-in policy (>=10 chars, upper, lower, digit, symbol, no whitespace), rejects any of the last five historic passwords, stores the previous hash in `user_password_history`, writes the new BCrypt hash, and flips `mustChangePassword` back to false.

Unlike the earlier implementation, password change now revokes existing access tokens and refresh tokens through the same shared revocation utilities used by reset flows.

## 5. Forgot/reset password narrative

The public forgot/reset surface is scoped-account aware. `SecurityConfig` permits `/api/v1/auth/password/forgot` and `/api/v1/auth/password/reset` without authentication, and `CompanyContextFilter` explicitly bypasses tenant-header enforcement for those exact POST paths. `PasswordResetService.logTenantContextIgnoredIfPresent(...)` records that any tenant header was ignored, while `ForgotPasswordRequest` itself now requires `email + companyCode`.

`requestReset(...)` looks up the user by `(email, auth_scope_code)`, silently suppresses unknown or disabled identities, rotates the scoped reset token, and asks `EmailService` to deliver a reset link rooted at `erp.mail.base-url`. Unknown or disabled identities still get the generic success message to reduce enumeration via response bodies, but known scoped accounts now fail closed when reset-token storage or email delivery/configuration fails.

`resetPassword(...)` is the opposite edge of the flow: load token, reject used/expired tokens, require an enabled user, delegate password policy/history checks to `PasswordService.resetPassword(...)`, clear lockout counters, revoke all access-token revocations and refresh tokens for the user, mark the token used, and delete every reset token for that user.

Public forgot-password still masks unknown or disabled identities, but it no longer returns a false-success response when a known scoped account cannot store or deliver the reset link. Admin-triggered reset-link issuance uses the same fail-closed delivery behavior.

## 6. Admin and super-admin reset paths

There are three materially different reset/control paths:

### 6.1 Tenant-admin user link reset

`POST /api/v1/admin/users/{userId}/force-reset-password` routes through `AdminUserService.forceResetPassword(...)`. On the live public surface this stays tenant-admin only; platform `ROLE_SUPER_ADMIN` callers are blocked from the `/api/v1/admin/users/**` workflow family and must use the canonical `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` route instead. The actual reset action is `PasswordResetService.requestResetByAdmin(...)`, which reuses the same scoped reset-link issuance service as public forgot-password. Disabled users are silently skipped; enabled users fail closed if the reset token cannot be stored or the email cannot be dispatched, and the service still audits `PASSWORD_RESET_REQUESTED`.

### 6.2 Root-only super-admin tenant-admin support reset

`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` is the canonical platform recovery path. `SuperAdminService.resetTenantAdminPassword(...)` requires the canonical superadmin control-plane binding, verifies that credential email delivery is enabled, and delegates to `TenantAdminProvisioningService.resetTenantAdminPassword(...)`, which reuses `PasswordResetService.requestResetByAdmin(...)` for the target scoped admin. The API response exposes only `companyCode`, `adminEmail`, and `status=reset-link-emailed`.

## 7. MFA lifecycle narrative

MFA is a TOTP + recovery-code design implemented in `MfaService`.

1. `POST /api/v1/auth/mfa/setup` generates a random base32 secret, eight random recovery codes, hashes the recovery codes, encrypts the secret at rest with `CryptoService` (AES-256-GCM over an externally supplied key), stores everything on `app_users`, and returns the raw secret, otpauth URI, and recovery codes to the caller.
2. `POST /api/v1/auth/mfa/activate` decrypts the secret and requires a valid current TOTP before flipping `mfa_enabled=true`.
3. `verifyDuringLogin(...)` runs during login: valid TOTP succeeds, valid recovery code is consumed and saved, missing verifier yields `428`, invalid verifier yields `401`.
4. `POST /api/v1/auth/mfa/disable` requires either a valid TOTP or a valid recovery code, then clears the secret and recovery-code hashes.
5. `PATCH /api/v1/admin/users/{id}/mfa/disable` is an operator override that clears MFA for another user and revokes their access and refresh tokens.

The lifecycle mostly works, but the persistence model is split. The schema contains a dedicated `mfa_recovery_codes` table, entity, repository, cleanup queries, and uniqueness constraints, yet the live `MfaService` uses the legacy comma-delimited `app_users.mfa_recovery_codes` column instead.

## 8. Disabled-user and lockout behavior narrative

Disabled-user handling is fail-closed across most entrypoints:

- `AuthService.ensureEnabledForAuthentication(...)` blocks login and refresh with `AUTH_ACCOUNT_DISABLED`.
- `PasswordResetService.requestReset(...)` and `requestResetByAdmin(...)` skip disabled users, making them look like unknown users from the reset surface.
- `PasswordResetService.resetPassword(...)` refuses to reset a disabled user.
- `JwtAuthenticationFilter` refuses to build an authenticated principal when a token belongs to a now-disabled user.
- `AdminUserService.updateUserStatusInternal(false)` revokes access tokens, refresh tokens, and sends a suspension email.

Lockout is narrower than disablement. `AuthService.registerFailure(...)` increments `failed_login_attempts` on bad credentials and MFA failures; at 5 attempts it sets `locked_until = now + 15 minutes`. `AuthService.enforceLock(...)` is only called during login and refresh, and both public reset plus support reset clear the lockout fields. Existing bearer tokens are not revoked by lockout alone, because `JwtAuthenticationFilter` checks only `enabled`, not `lockedUntil`, and `UserPrincipal.isAccountNonLocked()` hardcodes `true`.

## Invariants

- Login and refresh both require an explicit `companyCode`; the value selects the scoped account being authenticated or refreshed.
- Authenticated company-scoped requests must carry a token claim whose `companyCode` matches any supplied company header; `CompanyContextFilter` fails closed on mismatches.
- Public forgot/reset flows ignore tenant headers by design, but forgot-password itself is scoped by `{ email, companyCode }`.
- Access tokens are signed HS256 JWTs with `jti`, `sub`, `companyCode`, and `iatMs`; refresh tokens are opaque server-side rows.
- Refresh tokens are single-use and rotated on every successful refresh.
- Password change/reset enforce the password policy and last-five-history rule.
- MFA activation requires a live TOTP; MFA login requires TOTP or an unused recovery code.
- Disabled users cannot log in, refresh, or use existing JWTs.
- Lockout clears on successful login, public password reset, or tenant-admin hard reset.

## Side effects

- Login and refresh emit audit events and mutate `refresh_tokens`.
- Logout mutates `refresh_tokens` and `blacklisted_tokens`, but blacklist failure is logged rather than surfaced.
- Public forgot/reset mutates `password_reset_tokens`, `app_users`, `blacklisted_tokens`, `user_token_revocations`, and `refresh_tokens`.
- Admin link reset and support tenant-admin reset both create audit trails without exposing or rewriting a plaintext password server-side.
- Profile updates persist plaintext identity/profile fields on `app_users`.
- MFA setup/disable mutates `app_users` and admin-triggered disable also revokes tokens.

## Failure points and recovery behavior

- Tenant runtime policy can reject login or refresh even when credentials are correct (`423`, `403`, `429`), and those denials are tied to audit-chain metadata.
- Public forgot-password still returns the generic success envelope for unknown or disabled identities, but a known scoped account now fails closed when reset-token storage or email delivery/configuration fails.
- Refresh rotation burns the caller's old refresh token before downstream validation, so a bad `companyCode` or disabled account can strand the client.
- Lockout errors are surfaced as generic authentication failures rather than a dedicated `AUTH_ACCOUNT_LOCKED` contract.

## Token/session handling

- Access tokens are short-lived HS256 JWTs with `sub`, random `jti`, `companyCode`, and `iatMs`; the company claim is later reconciled by `CompanyContextFilter` against request headers.
- Refresh tokens are long-lived opaque UUID rows stored in `refresh_tokens`; refresh is rotation-based and deletes the previous token on every successful use.
- Logout revokes refresh tokens first and then tries to blacklist the current access token by `jti`; access-token blacklist persistence is best-effort rather than fail-closed.
- Password change, password reset, user disablement, and support tenant-admin reset all revoke outstanding sessions for the affected user.
- Password-reset tokens are one-hour bearer tokens stored in `password_reset_tokens`; reset completion marks them used and deletes all outstanding tokens for the user.

## Security findings

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| medium | schema drift / MFA | The live MFA implementation ignores the relational `mfa_recovery_codes` table/repository and instead stores comma-joined hashes on `app_users.mfa_recovery_codes`. | `MfaService`, `UserAccount.getMfaRecoveryCodeHashes()`, `MfaRecoveryCode.java`, `MfaRecoveryCodeRepository.java`, `V32__mfa_recovery_codes_table.sql` | DB uniqueness, used-at tracking, cleanup jobs, and the intended relational audit trail are bypassed, leaving a second unused persistence model behind. |
| medium | refresh rotation semantics | Refresh token rotation still consumes the existing refresh token before company validation and later runtime checks complete. | `AuthService.refresh(...)`, `RefreshTokenService.consume(...)` | A caller using the wrong `companyCode` or hitting a later runtime denial still burns the previous refresh token. |

## Data privacy

- `app_users` stores identity and profile data in plaintext columns: email, display name, preferred name, job title, profile image URL, secondary phone, and secondary email. The hard-cut auth surface now limits frontend identity reads to `/api/v1/auth/me` rather than a separate profile CRUD contract.
- `/me` returns role and permission expansions, which is useful for UI bootstrapping but widens the amount of authorization state exposed to every authenticated caller.
- MFA setup intentionally returns raw recovery codes and the raw TOTP secret once; at rest, the secret is encrypted with AES-256-GCM and the recovery codes are hashed, but the live code stores those hashes on `app_users` rather than in the dedicated recovery-code table.
- Support/admin reset reuses the same scoped reset-link machinery as public forgot-password; the remaining privacy concern is that `SuperAdminService.resetTenantAdminPassword(...)` records `resetEmail` in audit metadata as plain text.
- The remaining profile columns are persistent account metadata, but there is no longer a public self-service profile mutation surface to audit separately.

## Protocol/protection

- The auth surface is stateless bearer-token auth: CSRF, form login, HTTP basic, and server-side logout are disabled in `SecurityConfig`, while JWT bearer auth is inserted before the username/password filter.
- `JwtProperties` enforces a 32-byte minimum secret outside test-only contexts, and `JwtTokenService` signs access tokens with HS256 plus a random `jti`.
- `CompanyContextFilter` is the key tenant-protection layer after JWT auth. It rejects mismatched token claims, mismatched company headers, unauthenticated attempts to set tenant headers, and authenticated tokens missing company claims.
- CORS is runtime-configurable via `SystemSettingsService`; production validation requires explicit `https` origins and forbids wildcards, while the built `CorsConfiguration` still sets `allowCredentials=true`.
- Swagger/OpenAPI endpoints remain non-public unless `erp.security.swagger-public=true` and the runtime is not on the `prod` profile.
- Tenant runtime protection is part of the auth protocol itself: hold/block/quota policies can deny login, refresh, and authenticated requests before business controllers run.
- A second security-control surface exists in `SecurityMonitoringService`, but there are no production callers in main code, so brute-force monitoring and token-revocation analytics there are currently architectural dead weight rather than an active control.

## Evidence notes

- `AuthControllerIT` proves login -> `/me`, refresh rotation, and logout-driven refresh-token revocation.
- `MfaControllerIT` proves MFA enrollment, activation, 428 challenge responses, and one-time recovery-code consumption.
- Profile CRUD is intentionally not part of the current-state auth surface.
- `AuthPasswordResetPublicContractIT` and `CompanyContextFilterPasswordResetBypassTest` prove public forgot/reset flows bypass tenant headers but are scoped by `email + companyCode`.
- `AuthDisabledUserTokenIT` proves disabled users cannot log in and that tokens for now-disabled users stop authenticating.
- `AuthHardeningIT` proves five failed logins set `locked_until` and that successful login works again after the lock is cleared.
- `TenantRuntimeEnforcementAuthIT` proves tenant runtime hold/block/quota policy can reject login and authenticated requests with audit-chain evidence.
- `AdminUserServiceTest` proves admin force-reset delegates to `PasswordResetService.requestResetByAdmin(...)` and that the action is audited as `PASSWORD_RESET_REQUESTED`.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| medium | refresh rotation semantics | Refresh token rotation still consumes the existing refresh token before company validation and later runtime checks complete. | `AuthService.refresh(...)`, `RefreshTokenService.consume(...)` | A caller using the wrong `companyCode` or hitting a later runtime denial still burns the previous refresh token. |

## Additional regression note

- The current branch hard-cuts public forgot-password to one scoped contract: unknown or disabled identities stay masked, while known-account storage or delivery failures surface as real failures instead of false-success `200 OK`.
