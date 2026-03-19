# Auth / identity

## Scope and evidence

This review covers login, refresh, logout, `/me`, profile read/update, password change, public forgot/reset password, tenant-admin and super-admin reset paths, MFA setup/activation/disable/login challenges, disabled-user handling, and lockout behavior.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/controller/{AuthController,MfaController,UserProfileController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/{AuthService,RefreshTokenService,PasswordResetService,PasswordService,MfaService,UserProfileService,TenantAdminProvisioningService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{SecurityConfig,JwtAuthenticationFilter,JwtTokenService,JwtProperties,CompanyContextFilter,TokenBlacklistService,CryptoService,SecurityMonitoringService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/{controller/AdminUserController.java,service/AdminUserService.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/{controller/CompanyController.java,service/CompanyService.java,dto/CompanyAdminCredentialResetDto.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/domain/{UserAccount,RefreshToken,PasswordResetToken,UserPasswordHistory,UserTokenRevocation,BlacklistedToken,MfaRecoveryCode}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V1__core_auth_rbac.sql,V157__drop_legacy_user_reset_columns.sql}` and legacy migrations `V13__mfa_support.sql`, `V32__mfa_recovery_codes_table.sql`, `V37__password_reset_tokens.sql`, `V51__auth_lockout_controls.sql`, `V61__token_blacklist_tables.sql`, `V77__user_must_change_password.sql`, `V112__refresh_tokens.sql`
- `erp-domain/src/main/resources/{application.yml,application-prod.yml}` and `erp-domain/src/main/java/com/bigbrightpaints/erp/{config/CorsConfig.java,core/config/SystemSettingsService.java,core/notification/EmailService.java}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/{AuthControllerIT,AuthAuditIT,AuthDisabledUserTokenIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,MfaControllerIT,ProfileControllerIT,TenantRuntimeEnforcementAuthIT,CompanyContextFilterPasswordResetBypassTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/{PasswordResetServiceTest,MfaServiceTest,PasswordServiceTest}.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/service/AdminUserServiceTest.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Session bootstrap | `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh-token`, `POST /api/v1/auth/logout` | `AuthController` | Login/refresh issue JWT + opaque refresh token; logout revokes refresh tokens and blacklists the current access token when possible. |
| Identity read/write | `GET /api/v1/auth/me`, `GET/PUT /api/v1/auth/profile`, `POST /api/v1/auth/password/change` | `AuthController`, `UserProfileController` | `/me` is claim/context-derived; `/profile` mutates `app_users`; password change uses password policy + history checks. |
| Public password reset | `POST /api/v1/auth/password/forgot`, `POST /api/v1/auth/password/reset` | `AuthController` | Public and explicitly bypass tenant binding; uses one reset token per user identity. |
| Deprecated super-admin forgot alias | `POST /api/v1/auth/password/forgot/superadmin` | `AuthController` | Intentionally retired compatibility alias. The route now returns `410 Gone` with `canonicalPath=/api/v1/auth/password/forgot` plus `supportResetPath=/api/v1/companies/{id}/support/admin-password-reset`. |
| Self-service MFA | `POST /api/v1/auth/mfa/setup`, `POST /api/v1/auth/mfa/activate`, `POST /api/v1/auth/mfa/disable` | `MfaController` | Setup returns the raw secret, otpauth URI, and recovery codes; activation/disable verify TOTP or recovery code. |
| Admin user reset / status / MFA override | `POST /api/v1/admin/users/{userId}/force-reset-password`, `PUT /api/v1/admin/users/{userId}/status`, `PATCH /api/v1/admin/users/{id}/mfa/disable` | `AdminUserController` | Tenant admins can reset a scoped user; super admins can target foreign-tenant users through admin service shortcuts. |
| Super-admin support reset | `POST /api/v1/companies/{id}/support/admin-password-reset` | `CompanyController` | Root-only company control-plane path that rewrites the tenant admin password, revokes tokens, and requires credential-email delivery. |

## Endpoint-to-entity path

| Flow | Call chain | Entity / store touchpoints |
| --- | --- | --- |
| Login | `AuthController.login` -> `AuthService.login` -> `UserAccountRepository` + `AuthenticationManager` + `CompanyRepository` + `TenantRuntimeEnforcementService` + `MfaService` + `JwtTokenService` + `RefreshTokenService` + `AuditService` | `app_users`, `user_roles`, `user_companies`, `refresh_tokens`, `audit_logs` |
| Refresh | `AuthController.refresh` -> `AuthService.refresh` -> `RefreshTokenService.consume` -> `TokenBlacklistService` -> `CompanyRepository` -> `TenantRuntimeEnforcementService` -> `JwtTokenService` -> `RefreshTokenService.issue` | `refresh_tokens`, `user_token_revocations`, `app_users` |
| Logout | `AuthController.logout` -> `AuthService.logout` -> `RefreshTokenService.revoke*` + `TokenBlacklistService.blacklistToken` | `refresh_tokens`, `blacklisted_tokens` |
| `/me` | `JwtAuthenticationFilter` + `CompanyContextFilter` -> `AuthController.me` | JWT claims, `app_users`, `user_roles`, `user_companies` |
| Profile read/update | `UserProfileController.{profile,update}` -> `UserProfileService` | `app_users` (`display_name`, `preferred_name`, `job_title`, `profile_picture_url`, `phone_secondary`, `secondary_email`) |
| Password change | `AuthController.changePassword` -> `PasswordService.changePassword` | `app_users.password_hash`, `app_users.must_change_password`, `user_password_history` |
| Public forgot/reset | `AuthController.{forgotPassword,resetPassword}` -> `PasswordResetService` -> `PasswordService.resetPassword` -> `TokenBlacklistService` + `RefreshTokenService` + `EmailService` | `password_reset_tokens`, `app_users`, `blacklisted_tokens`, `user_token_revocations`, `refresh_tokens` |
| Admin link reset | `AdminUserController.forceResetPassword` -> `AdminUserService.forceResetPassword` -> `PasswordResetService.requestResetByAdmin` -> `requestReset` | `app_users`, `password_reset_tokens` |
| Super-admin hard reset | `CompanyController.resetTenantAdminPassword` -> `CompanyService.resetTenantAdminPassword` -> `TenantAdminProvisioningService.resetTenantAdminPassword` -> `EmailService.sendUserCredentialsEmailRequired` | `app_users.password_hash`, `app_users.must_change_password`, `blacklisted_tokens`, `user_token_revocations`, `refresh_tokens`, `audit_logs` |
| MFA lifecycle | `MfaController` -> `MfaService` and `AdminUserController` -> `AdminUserService.disableMfa` | `app_users.mfa_secret`, `app_users.mfa_enabled`, `app_users.mfa_recovery_codes`; `mfa_recovery_codes` table exists but is not used by the live service path |

## 1. Login and token issuance narrative

`POST /api/v1/auth/login` is the only first-class credential entrypoint. `AuthService.login(...)` loads `UserAccount` by email, rejects disabled accounts up front, enforces a 5-attempt / 15-minute lockout window, then delegates password verification to Spring Security. After credential validation it resolves the requested `companyCode`, allowing `ROLE_SUPER_ADMIN` actors to mint tokens for any company code while ordinary users must already belong to that company via `user_companies`.

Before any token is minted, tenant runtime policy is consulted through `TenantRuntimeEnforcementService.enforceAuthOperationAllowed(...)`. That means login can fail with runtime-policy semantics (`423 Locked` for hold, `403 Forbidden` for blocked tenants, `429 Too Many Requests` for active-user quota breaches) even when the username/password pair is valid; `TenantRuntimeEnforcementAuthIT` proves those behaviors and the accompanying audit-chain metadata.

If the user has MFA enabled, `MfaService.verifyDuringLogin(...)` requires either a valid 6-digit TOTP or a single-use recovery code. Missing MFA material raises `MfaRequiredException`, which `CoreFallbackExceptionHandler` converts into `428 Precondition Required` with `{"required": true}`; invalid MFA input raises `401`. Successful login resets `failed_login_attempts` and `locked_until`, writes a login audit event, signs an HS256 access token carrying both `companyCode` and legacy `cid` claims, and persists an opaque refresh token row.

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

## 3. `/me` and profile narrative

`GET /api/v1/auth/me` is entirely claim/context-derived. `JwtAuthenticationFilter` authenticates the bearer token, `CompanyContextFilter` reconciles the token's `companyCode`/`cid` claims against `X-Company-Code` / `X-Company-Id`, and `AuthController.me(...)` assembles a response from the authenticated `UserPrincipal`. The payload contains email, display name, current company code, `mfaEnabled`, `mustChangePassword`, flattened roles, and flattened permissions. The canonical tenant/session identifier in this surface is `companyCode`; there is no public `companyId` alias in the response contract anymore.

`GET/PUT /api/v1/auth/profile` is a thin CRUD wrapper over the `app_users` row. `UserProfileService.update(...)` trims and persists `displayName`, `preferredName`, `jobTitle`, `profilePictureUrl`, `phoneSecondary`, and `secondaryEmail`, then returns the updated `ProfileResponse` including the user's `publicId` and company list. There is no dedicated audit call around profile mutation, so identity-profile changes are durable but not first-class audit events.

## 4. Password change narrative

`POST /api/v1/auth/password/change` stays inside the authenticated identity surface and never touches reset tokens. `PasswordService.changePassword(...)` normally requires the current password, but it explicitly skips that check when `mustChangePassword=true` so that temporary-password flows do not dead-end. It then enforces confirmation equality, disallows reusing the current password, validates the password against the built-in policy (>=10 chars, upper, lower, digit, symbol, no whitespace), rejects any of the last five historic passwords, stores the previous hash in `user_password_history`, writes the new BCrypt hash, flips `mustChangePassword` back to false, and revokes all access and refresh sessions for that user.

## 5. Forgot/reset password narrative

The public forgot/reset surface is deliberately global-identity rather than tenant-scoped. `SecurityConfig` permits `/api/v1/auth/password/forgot` and `/api/v1/auth/password/reset` without authentication, and `CompanyContextFilter` explicitly bypasses tenant-header enforcement for those exact POST paths. `PasswordResetService.logTenantContextIgnoredIfPresent(...)` records that any tenant header was ignored. The identity model is one `UserAccount` per unique email, so the same email address carries one password across every tenant membership and any attached platform-owner authority; `companyCode` selects workspace at login, not which password record is being reset.

`requestReset(...)` looks up the user by email, silently suppresses unknown or disabled identities, deletes any prior reset token for that user, generates a 32-byte base64url token, stores it in `password_reset_tokens`, and asks `EmailService.sendPasswordResetEmail(...)` to deliver a reset link rooted at `erp.mail.base-url`. The controller always returns the same success message to reduce enumeration via response bodies.

`resetPassword(...)` is the opposite edge of the flow: load token, reject used/expired tokens, require an enabled user, delegate password policy/history checks to `PasswordService.resetPassword(...)`, clear lockout counters, revoke all access-token revocations and refresh tokens for the user, mark the token used, and delete every reset token for that user.

The generic forgot/admin-link-reset flow is best-effort on delivery. `EmailService.sendPasswordResetEmail(...)` quietly returns when mail is disabled and logs SMTP failures without throwing, so the API can report success even when the message never leaves the system.

## 6. Admin and super-admin reset paths

There are three materially different reset/control paths:

### 6.1 Tenant-admin user link reset

`POST /api/v1/admin/users/{userId}/force-reset-password` routes through `AdminUserService.forceResetPassword(...)`. The target user is resolved against the current company unless the actor already has `ROLE_SUPER_ADMIN`, in which case the service can target a foreign-tenant user. The actual reset action is `PasswordResetService.requestResetByAdmin(...)`, which simply reuses the public forgot-password machinery. Disabled users are silently skipped; enabled users get a reset token and a best-effort email, and the service still audits `PASSWORD_RESET_REQUESTED`.

### 6.2 Root-only super-admin tenant-admin hard reset

`POST /api/v1/companies/{id}/support/admin-password-reset` is the strongest reset path. `CompanyService.resetTenantAdminPassword(...)` requires the canonical company-control-plane binding, verifies that credential email delivery is enabled, and delegates to `TenantAdminProvisioningService.resetTenantAdminPassword(...)`. That service generates a new temporary password, writes a fresh BCrypt hash, sets `mustChangePassword=true`, clears lockout counters, revokes all access and refresh tokens for that admin, and requires credential-email delivery to succeed before returning. The target must be assigned to the company and must carry either `ROLE_ADMIN` or tenant-attached `ROLE_SUPER_ADMIN`. The API response exposes only `companyCode`, `adminEmail`, and `status=credentials-emailed`, not the temporary password itself.

### 6.3 Deprecated super-admin forgot alias

`POST /api/v1/auth/password/forgot/superadmin` is no longer a live recovery flow. `AuthController.forgotPasswordForSuperAdmin(...)` now returns `410 Gone` with canonical migration pointers, so callers must use one of the two supported paths instead:

- `POST /api/v1/auth/password/forgot` for self-service reset of the shared email identity.
- `POST /api/v1/companies/{id}/support/admin-password-reset` for root-only assisted recovery against a specific tenant-attached admin or tenant-attached platform-owner account.

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

- Login and refresh both require an explicit `companyCode`; super admins may choose any company, while non-super-admins must already belong to the company.
- Authenticated company-scoped requests must carry a token claim whose `companyCode`/`cid` matches any supplied company header; `CompanyContextFilter` fails closed on mismatches.
- Public forgot/reset flows ignore tenant headers by design; one user identity spans all company memberships.
- Access tokens are signed HS256 JWTs with `jti`, `sub`, `companyCode`, and `cid`; refresh tokens are opaque server-side rows.
- Refresh tokens are single-use and rotated on every successful refresh.
- Password change/reset enforce the password policy and last-five-history rule.
- MFA activation requires a live TOTP; MFA login requires TOTP or an unused recovery code.
- Disabled users cannot log in, refresh, or use existing JWTs.
- Lockout clears on successful login, public password reset, or tenant-admin hard reset.

## Side effects

- Login and refresh emit audit events and mutate `refresh_tokens`.
- Logout mutates `refresh_tokens` and `blacklisted_tokens`, but blacklist failure is logged rather than surfaced.
- Public forgot/reset mutates `password_reset_tokens`, `app_users`, `blacklisted_tokens`, `user_token_revocations`, and `refresh_tokens`.
- Admin link reset and support hard reset both create audit trails; the hard reset also rewrites the password hash immediately.
- Profile updates persist plaintext identity/profile fields on `app_users`.
- MFA setup/disable mutates `app_users` and admin-triggered disable also revokes tokens.

## Failure points and recovery behavior

- Tenant runtime policy can reject login or refresh even when credentials are correct (`423`, `403`, `429`), and those denials are tied to audit-chain metadata.
- Generic forgot/admin link-reset can succeed outwardly while email delivery is disabled or SMTP dispatch fails.
- Refresh rotation burns the caller's old refresh token before downstream validation, so a bad `companyCode` or disabled account can strand the client.
- Any client still calling the retired `/api/v1/auth/password/forgot/superadmin` alias now receives `410 Gone` and must migrate to the canonical self-service or support-reset path.
- Lockout errors are surfaced as generic authentication failures rather than a dedicated `AUTH_ACCOUNT_LOCKED` contract.

## Token/session handling

- Access tokens are short-lived HS256 JWTs with `sub`, random `jti`, `companyCode`, and legacy `cid` claims; the company claims are later reconciled by `CompanyContextFilter` against request headers.
- Refresh tokens are long-lived opaque UUID rows stored in `refresh_tokens`; refresh is rotation-based and deletes the previous token on every successful use.
- Logout revokes refresh tokens first and then tries to blacklist the current access token by `jti`; access-token blacklist persistence is best-effort rather than fail-closed.
- Password change, password reset, user disablement, and support hard reset all revoke existing sessions for the user.
- Password-reset tokens are one-hour bearer tokens stored in `password_reset_tokens`; reset completion marks them used and deletes all outstanding tokens for the user.

## Security findings

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| high | security / temporary-credential integrity | `mustChangePassword` is only a response flag. Tenant onboarding and support resets set it, but login still issues full bearer tokens and no filter blocks non-change operations until the client cooperates. | `TenantAdminProvisioningService.resetTenantAdminPassword(...)`, `PasswordService.changePassword(...)`, `AuthService.login(...)`, `UserAccount.mustChangePassword`, `AuthResponse`, `MeResponse` | Temporary credentials are not actually forced into a password-change-only corridor; a leaked temp password can be used for normal API work until changed. |
| medium | contract / identity model | Public forgot-password is intentionally global by email rather than tenant-scoped by `companyCode`. | `UserAccount.email`, `AuthService.resolveCompanyForUser(...)`, `PasswordResetService.requestReset(...)`, `AuthPasswordResetPublicContractIT` | Frontend and support flows must not promise tenant-specific password selection during self-service recovery; resetting a shared email identity changes that one account password across all memberships. |
| medium | privacy / audit | Support hard-reset audit metadata records `resetEmail` in plain text. | `CompanyService.resetTenantAdminPassword(...)`, `AuditService.logSuccess(...)` | Assisted recovery is auditable, but the audit trail keeps the reset target email unredacted. |
| medium | delivery integrity | Generic forgot-password and admin link-reset flows are fail-open on email delivery. They can persist reset tokens and return success even when mail delivery is disabled or SMTP fails. | `PasswordResetService.requestReset(...)`, `PasswordResetService.requestResetByAdmin(...)`, `EmailService.sendPasswordResetEmail(...)`, `AdminUserController.forceResetPassword(...)` | Users/support can believe a reset link was sent when nothing left the system, and valid reset tokens still sit in storage until expiry or replacement. |
| medium | schema drift / MFA | The live MFA implementation ignores the relational `mfa_recovery_codes` table/repository and instead stores comma-joined hashes on `app_users.mfa_recovery_codes`. | `MfaService`, `UserAccount.getMfaRecoveryCodeHashes()`, `MfaRecoveryCode.java`, `MfaRecoveryCodeRepository.java`, `V32__mfa_recovery_codes_table.sql` | DB uniqueness, used-at tracking, cleanup jobs, and the intended relational audit trail are bypassed, leaving a second unused persistence model behind. |
| low | compatibility / migration | The deprecated `/api/v1/auth/password/forgot/superadmin` alias is intentionally retired and now returns `410 Gone` with canonical migration pointers. | `AuthController.forgotPasswordForSuperAdmin(...)`, `AuthPasswordResetPublicContractIT`, `openapi.json` | Any client or runbook still targeting the alias must migrate to the canonical self-service or support-reset route. |
| medium | lockout / protocol | Lockout only blocks login/refresh, existing JWTs survive, and `LockedException` falls into the generic authentication handler instead of the dedicated `AUTH_ACCOUNT_LOCKED` code. | `AuthService.enforceLock(...)`, `AuthService.registerFailure(...)`, `UserPrincipal.isAccountNonLocked()`, `JwtAuthenticationFilter`, `ErrorCode.AUTH_ACCOUNT_LOCKED`, `CoreFallbackExceptionHandler.handleAuthenticationException(...)` | The system looks locked from the login surface but does not evict active sessions, and clients cannot reliably distinguish a lockout from other auth failures. |
| low | API drift | Auth/session clients must still handle the legacy JWT `cid` claim in bearer tokens even though the public `/me` contract is now `companyCode`-only. | `JwtTokenService`, `CompanyContextFilter`, `AuthController.me(...)`, `openapi.json` | Frontend or support tooling that conflates token-claim compatibility with public response shape can still drift if it expects claim aliases to remain mirrored into API payloads. |

## Data privacy

- `app_users` stores identity and profile data in plaintext columns: email, display name, preferred name, job title, profile image URL, secondary phone, and secondary email. `ProfileResponse` exposes most of that data back to any bearer with access to the identity surface.
- `/me` returns role and permission expansions, which is useful for UI bootstrapping but widens the amount of authorization state exposed to every authenticated caller.
- MFA setup intentionally returns raw recovery codes and the raw TOTP secret once; at rest, the secret is encrypted with AES-256-GCM and the recovery codes are hashed, but the live code stores those hashes on `app_users` rather than in the dedicated recovery-code table.
- `CompanyService.resetTenantAdminPassword(...)` records `resetEmail` in audit metadata as plain text.
- Profile updates are durable but not explicitly audited in the same way as login, reset, disable, or support-reset actions, which weakens forensic reconstruction for identity-profile changes.

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
- `ProfileControllerIT` proves `/auth/profile` reads and persists identity fields.
- `AuthPasswordResetPublicContractIT` and `CompanyContextFilterPasswordResetBypassTest` prove public forgot/reset flows are intentionally tenant-context agnostic.
- `AuthDisabledUserTokenIT` proves disabled users cannot log in and that tokens for now-disabled users stop authenticating.
- `AuthHardeningIT` proves five failed logins set `locked_until` and that successful login works again after the lock is cleared.
- `TenantRuntimeEnforcementAuthIT` proves tenant runtime hold/block/quota policy can reject login and authenticated requests with audit-chain evidence.
- `AdminUserServiceTest` proves admin force-reset delegates to `PasswordResetService.requestResetByAdmin(...)` and that the action is audited as `PASSWORD_RESET_REQUESTED`.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| medium | contract / identity model | Public forgot-password is intentionally global by email rather than tenant-scoped by `companyCode`. | `UserAccount.email`, `AuthService.resolveCompanyForUser(...)`, `PasswordResetService.requestReset(...)`, `AuthPasswordResetPublicContractIT` | Frontend and support flows must not promise tenant-specific password selection during self-service recovery; resetting a shared email identity changes that one account password across all memberships. |
| medium | privacy / audit | Support hard-reset audit metadata records `resetEmail` in plain text. | `CompanyService.resetTenantAdminPassword(...)`, `AuditService.logSuccess(...)` | Assisted recovery is auditable, but the audit trail keeps the reset target email unredacted. |
| medium | delivery integrity | Generic forgot-password and admin link-reset flows are fail-open on email delivery. They can persist reset tokens and return success even when mail delivery is disabled or SMTP fails. | `PasswordResetService.requestReset(...)`, `PasswordResetService.requestResetByAdmin(...)`, `EmailService.sendPasswordResetEmail(...)`, `AdminUserController.forceResetPassword(...)` | Users/support can believe a reset link was sent when nothing left the system, and valid reset tokens still sit in storage until expiry or replacement. |
| medium | persistence error masking | Public forgot-password currently masks token-persistence failures together with delivery failures, because `requestReset()` swallows any `RuntimeException` thrown while token issuance and email dispatch share the same try block. | PR review on `PasswordResetService.requestReset(...)` / `issueResetToken(...)`, live code path for `/api/v1/auth/password/forgot` | Database outages or deadlocks can return the normal success contract even though no reset token exists, leaving users unable to recover passwords while monitoring only sees 200s. |
| low | compatibility / migration | The deprecated `/api/v1/auth/password/forgot/superadmin` alias is intentionally retired and now returns `410 Gone` with canonical migration pointers. | `AuthController.forgotPasswordForSuperAdmin(...)`, `AuthPasswordResetPublicContractIT`, `openapi.json` | Any client or runbook still targeting the alias must migrate to the canonical self-service or support-reset route. |

## Additional regression note

- Current PR review also flagged that storage failures in the public forgot-password path should still surface internally as real failures even if delivery/config failures are intentionally masked to preserve the no-enumeration contract.
