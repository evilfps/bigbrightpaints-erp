# Auth / identity

## Scope and evidence

This review covers login, refresh, logout, `/me`, profile, password change, public forgot/reset, tenant-admin reset paths, superadmin support reset, MFA, and the `mustChangePassword` corridor enforced in the live auth stack.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/controller/{AuthController,MfaController,UserProfileController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/{AuthService,PasswordResetService,PasswordService,RefreshTokenService,TenantAdminProvisioningService,MfaService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{SecurityConfig,CompanyContextFilter,MustChangePasswordCorridorFilter,TokenBlacklistService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminUserController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/SuperAdminController.java`
- `openapi.json`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/{AuthControllerIT,AuthDisabledUserTokenIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,MfaControllerIT,ProfileControllerIT,TenantRuntimeEnforcementAuthIT}.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Session bootstrap | `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh-token`, `POST /api/v1/auth/logout` | `AuthController` | Stateless bearer-token auth with rotating refresh tokens and `204` logout. |
| Identity and profile | `GET /api/v1/auth/me`, `GET/PUT /api/v1/auth/profile`, `POST /api/v1/auth/password/change` | `AuthController`, `UserProfileController` | `/me` remains claim/context-derived and still emits the deprecated `companyId` alias mirroring `companyCode`. |
| Public password reset | `POST /api/v1/auth/password/forgot`, `POST /api/v1/auth/password/reset` | `AuthController` | Public and tenant-context-agnostic. |
| Retired superadmin forgot alias | `POST /api/v1/auth/password/forgot/superadmin` | `AuthController` | Still public, but intentionally returns `410 Gone` with migration pointers. |
| Tenant-admin reset | `POST /api/v1/admin/users/{userId}/force-reset-password` | `AdminUserController` | Link-based reset path for a scoped target user. |
| Superadmin support reset | `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `SuperAdminController` | Hard reset that rewrites the password, enforces `mustChangePassword`, revokes sessions, and requires credential-email delivery. |
| MFA | `POST /api/v1/auth/mfa/setup`, `POST /api/v1/auth/mfa/activate`, `POST /api/v1/auth/mfa/disable` | `MfaController` | Self-service setup/activation/disable with recovery-code support during login challenges. |

## Current behavior

### Login, refresh, and logout

`AuthService` remains the single token-issuance path.

Current behavior:

- login validates credentials, resolves the requested tenant, applies tenant runtime admission, then issues access + refresh tokens
- refresh is rotating and one-time-use
- logout returns `204 No Content`, revokes refresh tokens, and blacklists the current access token when parseable
- `TenantRuntimeEnforcementAuthIT` still proves lifecycle/quota enforcement can reject login and authenticated auth requests before business controllers run

### `mustChangePassword` corridor

`MustChangePasswordCorridorFilter` now actively enforces the temporary-credential corridor.

Allowed while `mustChangePassword=true`:

- `GET /api/v1/auth/me`
- `GET /api/v1/auth/profile`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh-token`

Everything else is rejected with controlled `403` JSON carrying:

- `reason=PASSWORD_CHANGE_REQUIRED`
- `mustChangePassword=true`

### Public reset and recovery

`PasswordResetService` now cleanly separates public reset masking from real storage failures.

Current behavior:

- `POST /api/v1/auth/password/forgot` remains no-enumeration and generic on the user-facing success path
- reset-token persistence failures are no longer silently treated as success
- `POST /api/v1/auth/password/reset` revokes access + refresh sessions after a successful password reset
- the deprecated `/api/v1/auth/password/forgot/superadmin` route is explicitly retired with `410 Gone`

### Admin and support resets

There are now two distinct privileged reset paths:

- tenant-admin link reset: `POST /api/v1/admin/users/{userId}/force-reset-password`
- superadmin hard reset: `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`

The superadmin hard reset path:

- targets an admin inside the selected tenant
- rewrites the password immediately
- sets `mustChangePassword=true`
- revokes access and refresh sessions
- requires email delivery to succeed before returning success

### MFA

The MFA flow remains:

- setup returns secret, otpauth URI, and one-time recovery codes
- activation requires a valid TOTP
- login can require MFA and returns a controlled `428` challenge contract
- disable supports TOTP or recovery-code proof

## Security and protocol boundaries

- `SecurityConfig` permits only the public auth bootstrap/reset routes plus health and optional swagger.
- Everything else requires authentication and is then filtered by `CompanyContextFilter` and `MustChangePasswordCorridorFilter`.
- `CompanyContextFilter` still treats password-reset routes as tenant-context-agnostic.
- `/api/v1/changelog*` is no longer public and now sits behind normal authentication.
- The superadmin support-reset path advertised by the retired forgot alias now points to `/api/v1/superadmin/tenants/{id}/support/admin-password-reset`.

## Invariants

- Logout is documented and served as `204 No Content`.
- `mustChangePassword` is an enforced runtime boundary, not just an advisory response flag.
- Public forgot/reset remains tenant-context-agnostic.
- The deprecated superadmin forgot alias stays fail-closed as a `410 Gone` migration surface.
- Superadmin support resets now live under the canonical superadmin tenant-control plane, not `/api/v1/companies/{id}/...`.

## Residual risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| low | compatibility surface | `POST /api/v1/auth/password/forgot/superadmin` still exists as a public `410 Gone` compatibility route. | `AuthController.forgotPasswordForSuperAdmin(...)`, `SecurityConfig` | Old clients can still hit it, but only to receive migration pointers. |
| low | API drift tolerance | `/api/v1/auth/me` still emits the deprecated `companyId` alias mapped from `companyCode`. | `MeResponse.legacyCompanyId()` | Older clients remain supported, but new clients should treat `companyCode` as canonical. |

## Evidence notes

- `AuthControllerIT` proves login, refresh rotation, and logout token revocation.
- `AuthPasswordResetPublicContractIT` proves the public forgot/reset contract and the retired superadmin alias behavior.
- `AuthDisabledUserTokenIT` proves disabled users cannot continue authenticating.
- `TenantRuntimeEnforcementAuthIT` proves lifecycle/quota rules can deny auth operations before business controllers run.
