# Frontend Handoff

API contracts, flow documentation, and design guidance for frontend developers.

**What belongs here:** Endpoint maps per module, request/response schemas, user flow descriptions, state machines, error codes, and UI hints.
**Updated by:** Backend workers after implementing/refactoring each module.

## Mainline Catch-Up Override

This file reflects the earlier branch-era handoff model. During catch-up to `origin/main`, workers should treat it as **legacy reference material**, not the final canonical target by itself.

The mainline canonical frontend-facing docs model is now:

- `docs/frontend-portals/` for per-portal ownership docs
- `docs/frontend-api/` for shared cross-portal API rules

If information from this file is reused, it must be migrated into that mainline structure or clearly marked as non-canonical/reference-only so both models do not compete silently.

---

## Documentation Format Per Module

Each module section should include:
1. **Endpoint Map** - All REST endpoints with HTTP method, path, auth requirements, request/response types
2. **User Flows** - Step-by-step flows a frontend would implement (e.g., "Create Sales Order" flow with all API calls in sequence)
3. **State Machines** - Entity lifecycle states and valid transitions (e.g., Order: Draft -> Confirmed -> Dispatched -> Invoiced)
4. **Error Codes** - Module-specific error codes the frontend should handle with suggested UX behavior
5. **Data Contracts** - Key request/response DTOs with field descriptions and validation rules
6. **UI Hints** - Suggested form fields, required vs optional, dropdowns vs free text, dependent fields

---

## Modules (populated by workers as features complete)

### Auth

#### Current mission note

- Dedicated review tracker: see `docs/frontend-update-v2/README.md` for the per-feature frontend follow-up matrix and explicit no-op entries for this mission.
- 2026-03-06 `auth-token-secret-storage-hardening`: no auth/admin request or response shape changes were required. Login, refresh-token, logout, forgot-password, and reset-password payloads stay the same; only backend persistence changed so refresh-token and password-reset secrets are now stored as digests with no legacy-row backfill or fallback path.
- 2026-03-06 `auth-session-revocation-hardening`: no auth/admin request or response shape changes were required. Logout now invalidates all previously issued access and refresh sessions for the authenticated user, and password change, password reset, disablement, lockout, and support hard-reset now consistently reject old tokens instead of letting prior sessions remain usable.
- 2026-03-26 `auth-v2-hard-cut-canonicalization`: auth identity is now scoped to `(normalized_email, auth_scope_code)` with no email-only recovery path, no superadmin forgot-password alias, no tenant-switch session route, no caller-visible temporary-password payloads, and no compatibility DTO aliases. Public forgot-password requires `{ email, companyCode }`, reset tokens are scoped to that account only, admin/support resets use the same reset-link issuance path, and onboarding or user creation only reports provisioning status while emailing temporary credentials directly to the target user.
- 2026-03-14 `remove-orchestrator-dispatch-journal`: `POST /api/v1/orchestrator/factory/dispatch/{batchId}` is now a fail-closed compatibility surface only. Valid requests receive `410 Gone` with `canonicalPath=/api/v1/dispatch/confirm`, and orchestrator fulfillment requests for `SHIPPED`/`DISPATCHED`/`FULFILLED`/`COMPLETED` now return `409 Conflict` (`BUS_001`) instead of acknowledging or posting dispatch accounting truth.
- 2026-03-06 `reset-token-issuance-race-hardening`: no auth/admin request or response shape changes were required. Public forgot-password and admin force-reset now serialize reset-token issuance per scoped account so duplicate or overlapping requests deterministically leave only the latest reset link usable instead of cross-deleting every valid token.
- 2026-03-06 `must-change-password-corridor-hardening`: login, refresh-token, `/auth/me`, password-change, and logout success payloads stay the same. While `mustChangePassword=true`, the backend now confines the bearer session to that corridor, denies normal protected work with a `403` `ApiResponse` carrying `reason=PASSWORD_CHANGE_REQUIRED` and `mustChangePassword=true`, and still preserves company binding on the allowed corridor endpoints while the retired profile surface stays absent.
- 2026-03-06 `controlled-auth-error-contracts`: supported auth/admin success payloads stay the same, but previously raw framework/servlet failure paths are now normalized into `ApiResponse` contracts. Lockout now returns `401` with `AUTH_005`, authenticated tenant-binding mismatches now return `403` `ApiResponse` envelopes with `AUTH_004` plus `reason` / `reasonDetail`, and tenant runtime hold/block/quota denials on login or authenticated auth requests now return controlled `ApiResponse` error bodies carrying their runtime denial codes (for example `TENANT_ON_HOLD`, `TENANT_BLOCKED`, `TENANT_REQUEST_RATE_EXCEEDED`).
- 2026-03-06 `auth-compatibility-regression-handoff`: no auth/admin request or response shape changes were required. Login, refresh-token, logout, `/auth/me`, password-change, forgot/reset, admin user-control, and admin settings payloads remain frontend-safe; contract regression coverage was refreshed across those surfaces, and the published OpenAPI contract now matches the live `204 No Content` logout response so the `AuthControllerIT.refresh_token_revoked_after_logout` regression stays aligned with runtime behavior.
- 2026-03-26 `auth-v2-hard-cut-canonicalization`: `POST /api/v1/auth/password/forgot` still masks unknown-user and disabled-user cases behind the generic success contract, but known scoped accounts now fail closed when reset-token storage or reset-email delivery/configuration fails. Frontend callers must treat forgot-password as a scoped recovery request that can return a real error instead of assuming `200 OK`.
- 2026-03-07 `masked-admin-lock-scope-regression-fix`: admin user-management request and success-response payload shapes still did not change for `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, or `DELETE /api/v1/admin/users/{id}`. Tenant-admin foreign-target attempts on those paths still return the same masked `400 User not found` validation envelope as truly missing ids, but the backend now acquires pessimistic locks only through company-scoped lookup before falling back to a non-locking existence check for internal denial auditing, so no frontend code change is required.

#### Endpoint Map

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/api/v1/auth/login` | Public | `LoginRequest` | `AuthResponse` |
| POST | `/api/v1/auth/refresh-token` | Public | `RefreshTokenRequest` | `AuthResponse` |
| POST | `/api/v1/auth/logout` | `isAuthenticated()` | Query param: `refreshToken?` (optional) | `204 No Content` |
| GET | `/api/v1/auth/me` | `isAuthenticated()` | None | `ApiResponse<MeResponse>` |
| POST | `/api/v1/auth/password/change` | `isAuthenticated()` | `ChangePasswordRequest` | `ApiResponse<String>` |
| POST | `/api/v1/auth/password/forgot` | Public | `ForgotPasswordRequest` | `ApiResponse<String>` |
| POST | `/api/v1/auth/password/reset` | Public | `ResetPasswordRequest` | `ApiResponse<String>` |
| POST | `/api/v1/auth/mfa/setup` | `isAuthenticated()` | None | `ApiResponse<MfaSetupResponse>` |
| POST | `/api/v1/auth/mfa/activate` | `isAuthenticated()` | `MfaActivateRequest` | `ApiResponse<{ enabled: true }>` |
| POST | `/api/v1/auth/mfa/disable` | `isAuthenticated()` | `MfaDisableRequest` | `ApiResponse<{ enabled: false }>` |

Notes:
- Login/refresh return raw `AuthResponse` (not wrapped in `ApiResponse`).
- Most other auth endpoints return `ApiResponse<T>`.
- MFA verification during login is done by `POST /api/v1/auth/login` using `mfaCode` or `recoveryCode` in `LoginRequest`.
- When `mustChangePassword=true`, only login, refresh-token, `/auth/me`, `POST /auth/password/change`, and `POST /auth/logout` remain usable until the password is updated.

#### Auth Flows

1. **Login flow (without MFA)**
   1. Submit `POST /api/v1/auth/login` with `{ email, password, companyCode }`.
   2. Receive `AuthResponse` with `accessToken`, `refreshToken`, `expiresIn`, `mustChangePassword`.
   3. Call `GET /api/v1/auth/me` to hydrate the UI shell and permission state.

2. **Login flow (with MFA challenge)**
   1. Submit `POST /api/v1/auth/login` without MFA verifier.
   2. If MFA is required, backend returns `428 Precondition Required` with `AUTH_007` (`AUTH_MFA_REQUIRED`) and challenge payload.
   3. Prompt user for 6-digit TOTP or recovery code.
   4. Re-submit `POST /api/v1/auth/login` with same credentials + `mfaCode` or `recoveryCode`.
   5. On success, proceed exactly like non-MFA login.

3. **MFA enrollment flow**
   1. Authenticated user calls `POST /api/v1/auth/mfa/setup`.
   2. Backend returns `secret`, `qrUri`, and one-time `recoveryCodes`.
   3. UI displays QR code + manual secret fallback.
   4. User enters authenticator app code; UI calls `POST /api/v1/auth/mfa/activate` with `{ code }`.
   5. Backend marks MFA as enabled.

4. **Password reset flow**
   1. User submits `POST /api/v1/auth/password/forgot` with `{ email, companyCode }`.
   2. Backend responds with the generic success message for unknown or disabled identities, but known scoped accounts can return a controlled failure if reset-token storage or reset-email delivery fails.
   3. User opens emailed reset link and submits `POST /api/v1/auth/password/reset` with `{ token, newPassword, confirmPassword }`.
   4. Backend revokes existing sessions/tokens; user must log in again.
   5. Recovery is scope-bound: `same.email@example.com + MOCK` and `same.email@example.com + BBB` are separate reset targets, so a reset issued for one scope never changes the other.

5. **Password change flow**
   1. Authenticated user submits `POST /api/v1/auth/password/change` with current + new password fields.
   2. Backend validates policy/history, updates the stored password, and revokes all previously issued access/refresh tokens for that user.
   3. UI should clear stored tokens and force a fresh login with the new password after success.
   4. If login or `/auth/me` reports `mustChangePassword = true`, route the user directly into this flow and avoid calling normal protected APIs until the change succeeds.

6. **Token refresh flow**
   1. Before access token expiry (or after 401), call `POST /api/v1/auth/refresh-token` with `{ refreshToken, companyCode }`.
   2. Backend consumes old refresh token and returns a new access + refresh token pair (rotation).
   3. Client must atomically replace both tokens.

#### JWT Token Handling

- Access token contains tenant and identity claims (`sub`, `companyCode`, `name`, `jti`, `iat`, `exp`).
- Refresh tokens are one-time-use and rotated on every refresh.
- `logout` behavior:
  - When the current bearer token can be parsed, backend revokes all refresh tokens for that authenticated user and marks all previously issued access tokens invalid.
  - The current access token `jti` is still blacklisted when parseable for immediate single-token rejection.
  - If token subject recovery fails but `refreshToken` query param is supplied, backend falls back to revoking that refresh token only.
- Token/company consistency:
  - If request carries `X-Company-Code`, it must match token `companyCode` claim.
  - Mismatch is rejected with `403` by company-context enforcement.
- Recommended frontend storage strategy:
  - Keep access token in memory (preferred) and refresh token in secure storage with shortest feasible lifetime.
  - Never expose or persist any caller-visible temporary password. New-account credentials are emailed directly to the target user.
- Expiry handling:
  - Use `expiresIn` from `AuthResponse` for refresh scheduling.
  - On refresh failure, clear tokens and route to login.

#### Error Codes / Error Handling

| Error code | Typical surface | Meaning | Suggested frontend behavior |
|---|---|---|---|
| `AUTH_001` (`AUTH_INVALID_CREDENTIALS`) | Login | Invalid email/password | Inline credential error, keep form editable. |
| `AUTH_003` (`AUTH_TOKEN_INVALID`) | Authenticated APIs | Invalid/expired/malformed token context | Clear session and force login. |
| `AUTH_004` (`AUTH_INSUFFICIENT_PERMISSIONS`) | Protected APIs | Missing required authority | Show access denied page/CTA. |
| `AUTH_005` (`AUTH_ACCOUNT_LOCKED`) | Login | Account temporarily locked after repeated failures | Disable submit temporarily and show lockout timer/message. |
| `AUTH_007` (`AUTH_MFA_REQUIRED`) | Login | MFA challenge required | Open MFA challenge UI immediately. |
| `AUTH_008` (`AUTH_MFA_INVALID`) | Login/MFA endpoints | Wrong TOTP/recovery verifier | Show retryable MFA error, keep user in challenge state. |
| `VAL_001` (`VALIDATION_INVALID_INPUT`) | Password change/reset, token validation | Invalid payload or business validation message | Show field-level or inline validation text from `message`. |
| `VAL_002` (`VALIDATION_MISSING_REQUIRED_FIELD`) | Request validation | Missing required field | Highlight missing fields and block submit. |
| `VAL_007` (`VALIDATION_INVALID_STATE`) | Auth runtime constraints | Operation not allowed in current state | Show non-retryable state banner. |

Password-policy failures currently surface as `VAL_001` with message prefix `Password does not meet policy: ...`.

#### Data Contracts

- `LoginRequest`
  - `email: string` (required, email)
  - `password: string` (required)
  - `companyCode: string` (required)
  - `mfaCode?: string` (optional, 6-digit code when challenged)
  - `recoveryCode?: string` (optional alternative to `mfaCode`)

- `AuthResponse`
  - `tokenType: "Bearer"`
  - `accessToken: string`
  - `refreshToken: string`
  - `expiresIn: number` (seconds)
  - `companyCode: string`
  - `displayName: string`
  - `mustChangePassword: boolean`

- `RefreshTokenRequest`
  - `refreshToken: string` (required)
  - `companyCode: string` (required; used to mint tenant-scoped access token)

- `ChangePasswordRequest`
  - `currentPassword: string` (required)
  - `newPassword: string` (required)
  - `confirmPassword: string` (required)

- `ForgotPasswordRequest`
  - `email: string` (required, email)
  - `companyCode: string` (required)

- `ResetPasswordRequest`
  - `token: string` (required)
  - `newPassword: string` (required)
  - `confirmPassword: string` (required)

- `MeResponse`
  - `email: string`
  - `displayName: string`
  - `companyCode: string`
  - `mfaEnabled: boolean`
  - `mustChangePassword: boolean`
  - `roles: string[]`
  - `permissions: string[]`

- `MfaSetupResponse`
  - `secret: string`
  - `qrUri: string` (otpauth URI)
  - `recoveryCodes: string[]` (show once)

- `MfaActivateRequest`
  - `code: string` (required, exactly 6 digits)

- `MfaDisableRequest`
  - `code?: string`
  - `recoveryCode?: string`
  - At least one of `code` or `recoveryCode` must be present.

#### UI Hints

- Implement password strength indicator using backend policy:
  - min length 10, uppercase, lowercase, digit, special char, no whitespace.
- For change/reset forms, show live “new password == confirm password” validation.
- On login success where `mustChangePassword = true`, force redirect to password-change screen before showing main app.
- MFA setup UI:
  - Render `qrUri` as QR code.
  - Show copyable manual secret fallback.
  - Force user to download/store recovery codes before closing setup modal.
- Keep forgot-password UX generic (“If the email exists...”) regardless of account existence.
- Always include a tenant selector input bound to `companyCode` in login form.

### Tenant & Admin

#### Current mission note

- Dedicated review tracker: see `docs/frontend-update-v2/README.md` for the per-feature frontend follow-up matrix and explicit no-op entries for this mission.
- 2026-03-06 `privileged-user-boundary-hardening`: no admin user-management request or response shape changes were required for `POST /api/v1/admin/users/{id}/force-reset-password`, `PUT /api/v1/admin/users/{id}/status`, `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, or `DELETE /api/v1/admin/users/{id}`. The feature aligned tenant-boundary authorization and audit behavior while preserving the existing frontend payloads for tenant-admin operators; platform super-admins stay on the canonical `/api/v1/superadmin/tenants/{id}/...` control plane instead of tenant-admin workflow prefixes.
- 2026-03-06 `global-security-settings-authorization`: no admin settings request or response payload shapes changed, but `PUT /api/v1/admin/settings` requires `ROLE_SUPER_ADMIN` because it mutates platform-wide CORS, mail, export, and related global settings. Legacy admin/company runtime-policy aliases are retired from the published contract; tenant lifecycle and quota control now live on the superadmin tenant routes below.
- 2026-03-06 `auth-compatibility-regression-handoff`: no admin request or response payload shapes changed for `POST /api/v1/admin/users/{id}/force-reset-password`, `PUT /api/v1/admin/users/{id}/status`, `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, `DELETE /api/v1/admin/users/{id}`, `GET /api/v1/admin/settings`, and `PUT /api/v1/admin/settings`; the refreshed OpenAPI snapshot also documents the user-control no-content endpoints as `204 No Content` instead of stale `200` responses.
- 2026-03-15 `lane01-canonicalize-company-runtime-writer`: both legacy public runtime-policy writers are retired from the published contract. Superadmin tenant quota/runtime control now lives on `PUT /api/v1/superadmin/tenants/{id}/limits`, and tenant detail/limits reads come from the superadmin tenant detail routes.
- 2026-03-29 `erp-11-12-13-26-29-contract-closure`: the current backend does not expose dedicated UI fields for `sessionTimeoutMinutes`, `passwordMinLength`, `maxLoginAttempts`, or `mfaRequired`. Frontend/UAT should treat those as unsupported until a real backend contract lands, use the support endpoints under `/api/v1/superadmin/tenants/{id}/support/*`, create catalog records through `/api/v1/catalog/items`, and treat `/api/v1/raw-materials/intake` plus bulk-variant catalog routes as retired.
- 2026-03-06 `tenant-lifecycle-rollout-safety-hardening`: no auth/admin/lifecycle request or response payload shapes changed. The current public lifecycle control plane is `PUT /api/v1/superadmin/tenants/{id}/lifecycle`; the backend continues to persist Flyway-v2-compatible lifecycle storage values (`ACTIVE`, `HOLD`, `BLOCKED`), and corrupted or unrecognized stored lifecycle values now fail closed instead of being treated as active.
- 2026-03-07 `masked-admin-target-lookup-hardening`: admin user-management request and success-response payload shapes still did not change, but tenant-admin attempts to `POST /api/v1/admin/users/{id}/force-reset-password`, `PUT /api/v1/admin/users/{id}/status`, `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, or `DELETE /api/v1/admin/users/{id}` against a foreign-tenant user id now return the same `400 User not found` validation envelope as a truly missing id. This masks foreign targets from enumeration while preserving internal `ACCESS_DENIED` audit evidence, and `POST /api/v1/admin/roles` remains request/response compatible while now enforcing the super-admin mutation boundary directly at the controller guard.
- 2026-03-07 `masked-admin-lock-scope-regression-fix`: no admin request or response payload shapes changed. The masked foreign-target behavior from `masked-admin-target-lookup-hardening` remains the same for tenant-admin `suspend`, `unsuspend`, `mfa/disable`, and `delete` actions, but those paths no longer take cross-tenant pessimistic locks before scope checks, so the frontend should continue treating foreign and missing targets identically and needs no migration.

#### Endpoint Map

**A) Superadmin tenant control endpoints**

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/superadmin/dashboard` | `ROLE_SUPER_ADMIN` | None | `SuperAdminDashboardDto` |
| GET | `/api/v1/superadmin/audit/platform-events` | `ROLE_SUPER_ADMIN` | Query: `from?`, `to?`, `action?`, `status?`, `actor?`, `entityType?`, `reference?`, `page?`, `size?` | `PageResponse<AuditFeedItemDto>` |
| GET | `/api/v1/superadmin/tenants` | `ROLE_SUPER_ADMIN` | Optional query: `status` | `List<SuperAdminTenantSummaryDto>` |
| GET | `/api/v1/superadmin/tenants/coa-templates` | `ROLE_SUPER_ADMIN` | None | `List<CoATemplateDto>` |
| POST | `/api/v1/superadmin/tenants/onboard` | `ROLE_SUPER_ADMIN` | `TenantOnboardingRequest` | `TenantOnboardingResponse` |
| GET | `/api/v1/superadmin/tenants/{id}` | `ROLE_SUPER_ADMIN` | None | `SuperAdminTenantDetailDto` |
| PUT | `/api/v1/superadmin/tenants/{id}/lifecycle` | `ROLE_SUPER_ADMIN` | `CompanyLifecycleStateRequest` | `CompanyLifecycleStateDto` |
| PUT | `/api/v1/superadmin/tenants/{id}/limits` | `ROLE_SUPER_ADMIN` | `TenantLimitsUpdateRequest` | `SuperAdminTenantLimitsDto` |
| PUT | `/api/v1/superadmin/tenants/{id}/modules` | `ROLE_SUPER_ADMIN` | `TenantModulesUpdateRequest` | `CompanyEnabledModulesDto` |
| POST | `/api/v1/superadmin/tenants/{id}/support/warnings` | `ROLE_SUPER_ADMIN` | `TenantSupportWarningRequest` | `CompanySupportWarningDto` |
| POST | `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | `ROLE_SUPER_ADMIN` | `TenantAdminPasswordResetRequest` | `CompanyAdminCredentialResetDto` |
| PUT | `/api/v1/superadmin/tenants/{id}/support/context` | `ROLE_SUPER_ADMIN` | `TenantSupportContextUpdateRequest` | `SuperAdminTenantSupportContextDto` |
| POST | `/api/v1/superadmin/tenants/{id}/force-logout` | `ROLE_SUPER_ADMIN` | Optional `TenantForceLogoutRequest` | `SuperAdminTenantForceLogoutDto` |
| PUT | `/api/v1/superadmin/tenants/{id}/admins/main` | `ROLE_SUPER_ADMIN` | `TenantMainAdminUpdateRequest` | `MainAdminSummaryDto` |
| POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request` | `ROLE_SUPER_ADMIN` | `TenantAdminEmailChangeRequest` | `SuperAdminTenantAdminEmailChangeRequestDto` |
| POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | `ROLE_SUPER_ADMIN` | `TenantAdminEmailChangeConfirmRequest` | `SuperAdminTenantAdminEmailChangeConfirmationDto` |

**B) Tenant admin user-management endpoints**

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/admin/users` | `ROLE_ADMIN` | None | `List<UserDto>` |
| POST | `/api/v1/admin/users` | `ROLE_ADMIN` | `CreateUserRequest` | `UserDto` |
| PUT | `/api/v1/admin/users/{id}` | `ROLE_ADMIN` | `UpdateUserRequest` | `UserDto` |
| PUT | `/api/v1/admin/users/{id}/status` | `ROLE_ADMIN` | `UpdateUserStatusRequest` | `UserDto` |
| POST | `/api/v1/admin/users/{id}/force-reset-password` | `ROLE_ADMIN` | None | `ApiResponse<String>` (`"OK"`) |
| PATCH | `/api/v1/admin/users/{id}/suspend` | `ROLE_ADMIN` | None | `204 No Content` |
| PATCH | `/api/v1/admin/users/{id}/unsuspend` | `ROLE_ADMIN` | None | `204 No Content` |
| PATCH | `/api/v1/admin/users/{id}/mfa/disable` | `ROLE_ADMIN` | None | `204 No Content` |
| DELETE | `/api/v1/admin/users/{id}` | `ROLE_ADMIN` | None | `204 No Content` |
| GET | `/api/v1/admin/audit/events` | `ROLE_ADMIN` (tenant-scoped only) | Query: `from?`, `to?`, `module?`, `action?`, `status?`, `actor?`, `entityType?`, `reference?`, `page?`, `size?` | `PageResponse<AuditFeedItemDto>` |
| GET | `/api/v1/admin/roles` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | None | `List<RoleDto>` |
| GET | `/api/v1/admin/roles/{roleKey}` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | None | `RoleDto` |
| POST | `/api/v1/admin/roles` | `ROLE_SUPER_ADMIN` | `CreateRoleRequest` | `RoleDto` |

**C) Company context endpoints**

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/companies` | `ROLE_SUPER_ADMIN` \/ `ROLE_ADMIN` \/ `ROLE_ACCOUNTING` \/ `ROLE_SALES` | None | `List<CompanyDto>` |

All non-204 responses use `ApiResponse<T>` wrappers.

#### Module Feature Gating (tenant runtime behavior)

Module access is enforced per tenant using `companies.enabled_modules`.

- **Gatable modules**: `MANUFACTURING`, `HR_PAYROLL`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED`
- **Core modules (always enabled, cannot be disabled)**: `AUTH`, `ACCOUNTING`, `SALES`, `INVENTORY`

Disabled module requests return `403` with `BUS_010` (`MODULE_DISABLED`). Runtime path mapping:
- `MANUFACTURING`: `/api/v1/factory/**`
- `HR_PAYROLL`: `/api/v1/hr/**`, `/api/v1/payroll/**`
- `PURCHASING`: `/api/v1/purchasing/**`, `/api/v1/suppliers/**`
- `PORTAL`: `/api/v1/portal/**`, `/api/v1/dealer-portal/**`
- `REPORTS_ADVANCED`: `/api/v1/reports/**`, `/api/v1/accounting/reports/**`

#### User Flows

1. **Scoped login -> module access**
   1. User signs in via `POST /api/v1/auth/login` with `{ email, password, companyCode }`.
   2. Backend authenticates exactly one scoped account for that `companyCode`.
   3. Frontend loads identity via `GET /api/v1/auth/me` and company metadata via `GET /api/v1/companies` when needed for admin or support surfaces.
   4. Set `X-Company-Code` to the authenticated scope on requests.
   5. Navigate to module surfaces; if a module is disabled (`BUS_010`), show module-disabled state.

2. **Superadmin tenant creation with CoA template**
   1. Load templates with `GET /api/v1/superadmin/tenants/coa-templates`.
   2. Submit `POST /api/v1/superadmin/tenants/onboard` with selected `coaTemplateCode`.
   3. Backend creates company, admin user, default accounting period, and 50-100 CoA accounts.
   4. Show bootstrap success details only; credentials are emailed directly to the tenant admin and are never returned in the API payload.
   5. Optionally configure enabled modules via `PUT /api/v1/superadmin/tenants/{id}/modules`.

3. **User creation with role assignment**
   1. Load assignable roles with `GET /api/v1/admin/roles`.
   2. (Optional) load target companies with `GET /api/v1/companies`.
   3. Submit `POST /api/v1/admin/users` with `{ email, displayName, companyId, roles }`.
   4. Backend creates one scoped account, assigns roles/company scope, and emails temporary credentials directly to the target user.
   5. Use `PUT /api/v1/admin/users/{id}` for role/company updates.
   6. Use `PUT /api/v1/admin/users/{id}/status` with `{ enabled: boolean }` for explicit activation/deactivation.
   7. Use `POST /api/v1/admin/users/{id}/force-reset-password` to trigger a reset link for a target user.
   8. Suspend/unsuspend and MFA-disable PATCH endpoints remain available for direct account operations.

4. **Tenant lifecycle operations (superadmin)**
   1. Review status in `GET /api/v1/superadmin/tenants`.
   2. Transition tenant state through `PUT /api/v1/superadmin/tenants/{id}/lifecycle`.
   3. Runtime enforcement: `SUSPENDED` blocks writes, `DEACTIVATED` blocks all access.

#### State Machines

1. **Tenant lifecycle**
   - `ACTIVE` -> `SUSPENDED` via `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
   - `SUSPENDED` -> `ACTIVE` via `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
   - `ACTIVE` -> `DEACTIVATED` via `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
   - `SUSPENDED` -> `DEACTIVATED` via `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
   - `DEACTIVATED` is terminal.

2. **Admin user lifecycle**
   - `enabled=true` -> `enabled=false` via `PUT /api/v1/admin/users/{id}/status` with `{ enabled: false }`
   - `enabled=false` -> `enabled=true` via `PUT /api/v1/admin/users/{id}/status` with `{ enabled: true }`
   - `enabled=true` -> `enabled=false` via `PATCH /api/v1/admin/users/{id}/suspend`
   - `enabled=false` -> `enabled=true` via `PATCH /api/v1/admin/users/{id}/unsuspend`
   - `POST /api/v1/admin/users/{id}/force-reset-password` triggers reset email/token issuance for the target account without changing the enabled flag
   - `enabled=true|false` -> `DELETED` via `DELETE /api/v1/admin/users/{id}`
   - `MFA_ENABLED` -> `MFA_DISABLED` via `PATCH /api/v1/admin/users/{id}/mfa/disable`

#### Error Codes / Error Handling

| Error code / status | Meaning | Suggested frontend behavior |
|---|---|---|
| `AUTH_004` (`AUTH_INSUFFICIENT_PERMISSIONS`) / 403 | Caller lacks role (e.g., non-superadmin on control plane) | Route to access-denied state and hide privileged actions. |
| `AUTH_006` (`AUTH_ACCOUNT_DISABLED`) / 401 | Disabled user attempted auth/login or token refresh | Show account-disabled screen; block retry loops and route to support/admin contact. |
| `BUS_010` (`MODULE_DISABLED`) / 403 | Tenant module disabled by gating policy | Show module-disabled empty state; hide create/write CTA. |
| `VAL_001` (`VALIDATION_INVALID_INPUT`) / 400 | Invalid status filter, unknown tenant/user/company, missing lifecycle reason, invalid enabled modules payload | Inline validation + toast with backend message. |
| `VAL_007` (`VALIDATION_INVALID_STATE`) / 400 or 409 | Invalid tenant lifecycle transition | Show state-transition-specific error and refresh tenant row status. |
| 403 (`Suspended tenants are read-only`) | Tenant is suspended and write operation attempted | Keep read-only mode banner and disable writes. |
| 403 (`Tenant is deactivated`) | Tenant fully deactivated | Block workflow and show deactivated notice. |
| 401 | Unauthenticated session | Redirect to login and clear local session state. |

#### Data Contracts

- `SuperAdminDashboardDto`
  - `totalTenants`, `activeTenants`, `suspendedTenants`, `deactivatedTenants`
  - `totalUsers`, `totalApiCalls`, `totalStorageBytes`
  - `recentActivityAt: string | null`

- `SuperAdminTenantDto`
  - `companyId`, `companyCode`, `companyName`
  - `status: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"`
  - `activeUsers`, `apiCallCount`, `storageBytes`
  - `lastActivityAt: string | null`

- `SuperAdminTenantUsageDto`
  - `companyId`, `companyCode`, `status`
  - `apiCallCount`, `activeUsers`, `storageBytes`
  - `lastActivityAt: string | null`

- `CompanyLifecycleStateRequest`
  - `state: "ACTIVE" | "SUSPENDED" | "DEACTIVATED"` (required)
  - `reason: string` (required, max 1024)

- `CompanyLifecycleStateDto`
  - `companyId`, `companyCode`
  - `previousLifecycleState`, `lifecycleState`
  - `reason`

- `TenantModulesUpdateRequest`
  - `enabledModules: string[]` (required; normalized to gatable module keys only)

- `CompanyEnabledModulesDto`
  - `companyId`, `companyCode`
  - `enabledModules: string[]`

- `CoATemplateDto`
  - `code: "GENERIC" | "INDIAN_STANDARD" | "MANUFACTURING"`
  - `name`, `description`
  - `accountCount` (50-100)

- `TenantOnboardingRequest`
  - `name`, `code`, `timezone` (required)
  - `defaultGstRate?: number (0..100)`
  - `maxActiveUsers?`, `maxApiRequests?`, `maxStorageBytes?`, `maxConcurrentUsers?` (>=0)
  - `softLimitEnabled?`, `hardLimitEnabled?`
  - `firstAdminEmail` (required email), `firstAdminDisplayName?`
  - `coaTemplateCode` (required)

- `TenantOnboardingResponse`
  - `companyId`, `companyCode`, `templateCode`
  - `bootstrapMode`, `seededChartOfAccounts`
  - `accountsCreated`, `accountingPeriodId`, `defaultAccountingPeriodCreated`
  - `adminEmail`, `tenantAdminProvisioned`, `systemSettingsInitialized`

- `CreateUserRequest`
  - `email` (required email)
  - `displayName` (required)
  - `companyId: number` (required; exactly one company scope per account)
  - `roles: string[]` (required)

- `UpdateUserRequest`
  - `displayName` (required)
  - `companyId?: number`
  - `roles?: string[]`
  - `enabled?: boolean`

- `UpdateUserStatusRequest`
  - `enabled: boolean` (required)

- `UserDto`
  - `id`, `publicId`, `email`, `displayName`
  - `enabled`, `mfaEnabled`
  - `roles: string[]`, `companyCode?: string`
  - `lastLoginAt?: string` (ISO-8601 instant, nullable when user never logged in)

- `CreateRoleRequest`
  - `name` (required)
  - `description` (required)
  - `permissions: string[]` (required, non-empty)

- `RoleDto`
  - `id`, `name`, `description`
  - `permissions: PermissionDto[]`

- `PermissionDto`
  - `id`, `code`, `description`

- `CompanyDto`
  - `id`, `publicId`, `name`, `code`, `timezone`, `stateCode?`, `defaultGstRate?`

#### UI Hints

- Keep the authenticated `companyCode` visible in the app shell; there is no post-login tenant-switch route in the canonical flow.
- For module configuration, render checkboxes only for gatable modules (`MANUFACTURING`, `HR_PAYROLL`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED`).
- User-creation form:
  - role multi-select from `GET /api/v1/admin/roles`
  - company selector from `GET /api/v1/companies`
  - show helper text that temporary credentials are emailed directly to the new user and never revealed to the caller.
- Superadmin onboarding UI should present CoA templates as cards (`name`, `description`, `accountCount`).
- After onboarding, show the provisioned admin email and next-step guidance only; do not render or copy any password.
- On lifecycle mutations, use confirmation dialogs and refresh dashboard + tenant list metrics after mutation.

#### Versioned changelog system (VAL-ADMIN-005)

##### Endpoint map

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `POST` | `/api/v1/admin/changelog` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | `ChangelogEntryRequest` | `ChangelogEntryResponse` |
| `PUT` | `/api/v1/admin/changelog/{id}` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | `ChangelogEntryRequest` | `ChangelogEntryResponse` |
| `DELETE` | `/api/v1/admin/changelog/{id}` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | none | `204 No Content` (soft-delete) |
| `GET` | `/api/v1/changelog?page={page}&size={size}` | Public (no auth) | query params | `PageResponse<ChangelogEntryResponse>` |
| `GET` | `/api/v1/changelog/latest-highlighted` | Public (no auth) | none | `ChangelogEntryResponse` |

##### User flows

1. **Admin publishes release notes**
   1. Admin opens changelog composer and submits `POST /api/v1/admin/changelog`.
   2. Backend persists semver-tagged markdown entry and returns `ChangelogEntryResponse`.

2. **Admin edits existing entry**
   1. Admin submits `PUT /api/v1/admin/changelog/{id}` with updated semver/title/body/highlight flag.
   2. Backend updates the same entry, refreshes `publishedAt`, and returns updated payload.

3. **Admin soft-deletes an entry**
   1. Admin invokes `DELETE /api/v1/admin/changelog/{id}`.
   2. Backend marks record deleted (`deleted=true`, `deletedAt` set) and returns `204`.
   3. Deleted entries are excluded from all public feeds.

4. **Public release-notes feed**
   1. Frontend (authenticated or anonymous) calls `GET /api/v1/changelog?page=0&size=20`.
   2. Backend returns newest-first page ordered by `publishedAt DESC, id DESC`.

5. **“What’s New” banner fetch**
   1. Frontend calls `GET /api/v1/changelog/latest-highlighted` on app load.
   2. Backend returns most recent non-deleted highlighted entry.
   3. If none exists, backend returns `404` with `BUS_003`.

##### State machine

- `PUBLISHED` -> `PUBLISHED` via `PUT /api/v1/admin/changelog/{id}` (editable update)
- `PUBLISHED` -> `DELETED` via `DELETE /api/v1/admin/changelog/{id}` (soft-delete)
- Highlight visibility is controlled by `isHighlighted` boolean inside `PUBLISHED` state.

##### Error codes / handling

| Error code / status | Meaning | Suggested frontend behavior |
|---|---|---|
| `VAL_001` / 400 | Invalid semver/title/body payload | Show inline validation and block submit. |
| `BUS_003` / 404 | Entry not found or no highlighted entry available | For latest-highlighted, hide banner gracefully; for admin edit/delete show stale-record notice. |
| 403 | Caller lacks admin role for admin endpoints | Show access denied and hide admin controls. |

##### Data contracts

- `ChangelogEntryRequest`
  - `version: string` (required semver, max 32 chars)
  - `title: string` (required, max 255 chars)
  - `body: string` (required markdown)
  - `isHighlighted?: boolean` (optional, defaults to `false`)

- `ChangelogEntryResponse`
  - `id: number`
  - `version: string`
  - `title: string`
  - `body: string` (markdown)
  - `publishedAt: string (ISO-8601 instant)`
  - `createdBy: string`
  - `isHighlighted: boolean`

- `PageResponse<ChangelogEntryResponse>`
  - `content: ChangelogEntryResponse[]`
  - `totalElements: number`
  - `totalPages: number`
  - `page: number`
  - `size: number`

##### UI hints

- Render `body` as markdown in both admin preview and public feed cards.
- Validate semver client-side using `major.minor.patch` format before submit for immediate feedback.
- For “What’s New”, cache latest-highlighted response briefly and dismiss banner per user preference in frontend state.
- Admin table should include soft-delete action and highlight toggle (via update payload).

#### Support tickets + GitHub integration (VAL-ADMIN-006, VAL-ADMIN-007)

##### Endpoint map

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `POST` | `/api/v1/portal/support/tickets` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `SupportTicketCreateRequest` | `SupportTicketResponse` |
| `GET` | `/api/v1/portal/support/tickets` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | none | `SupportTicketListResponse` |
| `GET` | `/api/v1/portal/support/tickets/{ticketId}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | none | `SupportTicketResponse` |
| `POST` | `/api/v1/dealer-portal/support/tickets` | `ROLE_DEALER` | `SupportTicketCreateRequest` | `SupportTicketResponse` |
| `GET` | `/api/v1/dealer-portal/support/tickets` | `ROLE_DEALER` | none | `SupportTicketListResponse` |
| `GET` | `/api/v1/dealer-portal/support/tickets/{ticketId}` | `ROLE_DEALER` | none | `SupportTicketResponse` |

All endpoints return `ApiResponse<T>` envelopes.

##### User flows

1. **Create support ticket from the canonical host**
   1. Internal support operators submit `POST /api/v1/portal/support/tickets`; dealer users submit `POST /api/v1/dealer-portal/support/tickets`.
   2. Backend persists ticket locally as `OPEN` and returns ticket payload immediately.
   3. Backend asynchronously attempts GitHub issue creation (`erp.github.*` driven).
   4. If GitHub integration is disabled/misconfigured/down, local ticket remains visible and carries `githubLastError`.

2. **List support tickets on the host-bound surface**
   1. Internal support operators call `GET /api/v1/portal/support/tickets` and see tenant-scoped tickets for their company.
   2. Dealer users call `GET /api/v1/dealer-portal/support/tickets` and see only self-created tickets (`userId` match).
   3. Dealer users are forbidden on the portal host, admin/accounting users are forbidden on the dealer host, and the shared `/api/v1/support/**` surface is retired.

3. **Get ticket details**
   1. Internal support operators call `GET /api/v1/portal/support/tickets/{ticketId}`.
   2. Dealer users call `GET /api/v1/dealer-portal/support/tickets/{ticketId}`.
   3. Out-of-scope tickets return `BUS_003` (not found) to avoid tenant, peer-dealer, or cross-host leakage.

4. **Background sync + resolution notification**
   1. Scheduler runs every 5 minutes and polls GitHub state for open/in-progress tickets with linked issue numbers.
   2. When GitHub issue state becomes `closed`, backend transitions local ticket to `RESOLVED`, stamps `resolvedAt`, and emails requester via `mail/ticket-resolved` (one-shot tracked by `resolvedNotificationSentAt`).
   3. If issue reopens, local status transitions back to `IN_PROGRESS` and resolution markers are cleared.

##### State machine

- Initial: `OPEN` (on local creation)
- `OPEN` -> `IN_PROGRESS` when GitHub sync sees `OPEN` state after linkage
- `OPEN|IN_PROGRESS` -> `RESOLVED` when GitHub issue state becomes `CLOSED`
- `RESOLVED|CLOSED` -> `IN_PROGRESS` when GitHub issue reopens (`OPEN`)
- `CLOSED` is supported in data model for future terminal/manual workflows; current automated flow uses `RESOLVED` on GitHub closure.

##### Error codes / handling

| Error code / status | Meaning | Suggested frontend behavior |
|---|---|---|
| `VAL_002` / 400 | Missing required field (category/subject/description) | Inline field errors; keep form editable. |
| `VAL_001` / 400 | Invalid category value | Show dropdown validation and block submit. |
| `VAL_004` / 400 | Field length exceeded | Show max-length helper and prevent submit. |
| `AUTH_004` / 401/403 | Unauthenticated or unauthorized context | Redirect to login / show access denied state. |
| `BUS_003` / 404 | Ticket not found or out of visibility scope | Show “Ticket not found” page and return to list. |

##### Data contracts

- `SupportTicketCreateRequest`
  - `category: string` (required, max 32; enum: `BUG`, `FEATURE_REQUEST`, `SUPPORT`)
  - `subject: string` (required, max 255)
  - `description: string` (required, max 4000)

- `SupportTicketResponse`
  - `id: number`
  - `publicId: string (UUID)`
  - `companyCode: string`
  - `userId: number`
  - `requesterEmail: string | null`
  - `category: "BUG" | "FEATURE_REQUEST" | "SUPPORT"`
  - `subject: string`
  - `description: string`
  - `status: "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED"`
  - `githubIssueNumber: number | null`
  - `githubIssueUrl: string | null`
  - `githubIssueState: string | null`
  - `githubSyncedAt: string (ISO-8601 instant) | null`
  - `githubLastError: string | null`
  - `resolvedAt: string (ISO-8601 instant) | null`
  - `resolvedNotificationSentAt: string (ISO-8601 instant) | null`
  - `createdAt: string (ISO-8601 instant)`
  - `updatedAt: string (ISO-8601 instant)`

- `SupportTicketListResponse`
  - `tickets: SupportTicketResponse[]`

##### UI hints

- Category should be a fixed dropdown (`BUG`, `FEATURE_REQUEST`, `SUPPORT`) to avoid invalid enum submissions.
- In ticket list/detail, show GitHub sync chip states:
  - `Linked #<number>` when `githubIssueNumber` exists,
  - warning badge when `githubLastError` non-null,
  - resolved indicator when `status == RESOLVED` with `resolvedAt`.
- `requesterEmail` is nullable for privacy/fallback contexts; UI should handle missing value gracefully.
- For non-admin users, hide team/global filters because backend already enforces self-scope.

### Accounting

Comprehensive frontend handoff for `VAL-DOC-003` (chart of accounts, journals, settlement, period controls, reconciliation, GST, audit, and temporal/reporting endpoints).

Catalog note (2026-03-21): accounting-facing stock-bearing setup now uses the canonical catalog endpoints documented in the **Product Catalog & Inventory** section (`GET/POST /api/v1/catalog/brands`, `GET/POST /api/v1/catalog/items`, `GET/PUT/DELETE /api/v1/catalog/items/{itemId}`).

> Response envelope convention: almost all endpoints return `ApiResponse<T>` where payload is in `data`; PDF endpoints return raw `byte[]`; CSV endpoint returns `text/csv` string.

#### Complete Endpoint Map (all accounting controllers)

| Method | Path | Auth | Request body | Response `data` type |
|---|---|---|---|---|
| `GET` | `/api/v1/accounting/accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountDto>` |
| `POST` | `/api/v1/accounting/accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountRequest` | `AccountDto` |
| `GET` | `/api/v1/accounting/accounts/tree` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountHierarchyService.AccountNode>` |
| `GET` | `/api/v1/accounting/accounts/tree/{type}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountHierarchyService.AccountNode>` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/activity` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.AccountActivityReport` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/balance/as-of` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `BigDecimal` |
| `GET` | `/api/v1/accounting/accounts/{accountId}/balance/compare` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.BalanceComparison` |
| `POST` | `/api/v1/accounting/accruals` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccrualRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/portal/finance/ledger` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `GET` | `/api/v1/portal/finance/invoices` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `GET` | `/api/v1/portal/finance/aging` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `GET` | `/api/v1/accounting/aging/suppliers/{supplierId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingSummaryResponse` |
| `GET` | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `GET` | `/api/v1/accounting/audit/events` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PageResponse<AuditFeedItemDto>` |
| `GET` | `/api/v1/accounting/audit/transactions` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PageResponse<AccountingTransactionAuditListItemDto>` |
| `GET` | `/api/v1/accounting/audit/transactions/{journalEntryId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountingTransactionAuditDetailDto` |
| `POST` | `/api/v1/accounting/bad-debts/write-off` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BadDebtWriteOffRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/configuration/health` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `ConfigurationHealthService.ConfigurationHealthReport` |
| `POST` | `/api/v1/accounting/credit-notes` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `CreditNoteRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/date-context` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `Map<String, Object>` |
| `POST` | `/api/v1/accounting/dealers/{dealerId}/auto-settle` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| `POST` | `/api/v1/accounting/debit-notes` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DebitNoteRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/default-accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `CompanyDefaultAccountsResponse` |
| `PUT` | `/api/v1/accounting/default-accounts` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `CompanyDefaultAccountsRequest` | `CompanyDefaultAccountsResponse` |
| `GET` | `/api/v1/accounting/gst/reconciliation` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `GstReconciliationDto` |
| `GET` | `/api/v1/accounting/gst/return` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `GstReturnDto` |
| `POST` | `/api/v1/accounting/inventory/landed-cost` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `LandedCostRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/inventory/revaluation` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `InventoryRevaluationRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/inventory/wip-adjustment` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `WipAdjustmentRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/journal-entries` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/journal-entries` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/journal-entries/{entryId}/reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/journals` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<JournalListItemDto>` |
| `POST` | `/api/v1/accounting/journals/{entryId}/reverse` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `JournalEntryReversalRequest` | `JournalEntryDto` |
| `GET` | `/api/v1/accounting/month-end/checklist` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `MonthEndChecklistDto` |
| `POST` | `/api/v1/accounting/month-end/checklist/{periodId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `MonthEndChecklistUpdateRequest` | `MonthEndChecklistDto` |
| `POST` | `/api/v1/accounting/opening-balances` | `hasAuthority('ROLE_ADMIN')` | `multipart/form-data` (`file`: CSV) | `OpeningBalanceImportResponse` |
| `POST` | `/api/v1/migration/tally-import` | `hasAuthority('ROLE_ADMIN')` | `multipart/form-data` (`file`: Tally XML) | `TallyImportResponse` |
| `POST` | `/api/v1/accounting/payroll/payments` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `PayrollPaymentRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/payroll/payments/batch` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `PayrollBatchPaymentRequest` | `PayrollBatchPaymentResponse` |
| `GET` | `/api/v1/accounting/periods` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `List<AccountingPeriodDto>` |
| `POST` | `/api/v1/accounting/periods` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodUpsertRequest` | `AccountingPeriodDto` |
| `PUT` | `/api/v1/accounting/periods/{periodId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodUpdateRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/close` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodCloseRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/lock` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodLockRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/periods/{periodId}/reopen` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AccountingPeriodReopenRequest` | `AccountingPeriodDto` |
| `POST` | `/api/v1/accounting/receipts/dealer` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerReceiptRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/receipts/dealer/hybrid` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerReceiptSplitRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/reconciliation/bank` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BankReconciliationRequest` | `BankReconciliationSummaryDto` |
| `POST` | `/api/v1/accounting/reconciliation/bank/sessions` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BankReconciliationSessionCreateRequest` | `BankReconciliationSessionSummaryDto` |
| `PUT` | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BankReconciliationSessionItemsUpdateRequest` | `BankReconciliationSessionDetailDto` |
| `POST` | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `BankReconciliationSessionCompletionRequest` (optional body) | `BankReconciliationSessionDetailDto` |
| `GET` | `/api/v1/accounting/reconciliation/bank/sessions` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `page`, `size` | `PageResponse<BankReconciliationSessionSummaryDto>` |
| `GET` | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `BankReconciliationSessionDetailDto` |
| `GET` | `/api/v1/accounting/reconciliation/subledger` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `ReconciliationService.SubledgerReconciliationReport` |
| `GET` | `/api/v1/accounting/reconciliation/discrepancies` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `status?`, `type?` | `ReconciliationDiscrepancyListResponse` |
| `POST` | `/api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `ReconciliationDiscrepancyResolveRequest` | `ReconciliationDiscrepancyDto` |
| `GET` | `/api/v1/accounting/reconciliation/inter-company` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | Query: `companyA`, `companyB` | `ReconciliationService.InterCompanyReconciliationReport` |
| `GET` | `/api/v1/accounting/reports/aging/receivables` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AgingReportService.AgedReceivablesReport` |
| `GET` | `/api/v1/accounting/reports/balance-sheet/hierarchy` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountHierarchyService.BalanceSheetHierarchy` |
| `GET` | `/api/v1/accounting/reports/income-statement/hierarchy` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `AccountHierarchyService.IncomeStatementHierarchy` |
| `GET` | `/api/v1/accounting/sales/returns` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES')` | `—` | `List<JournalEntryDto>` |
| `POST` | `/api/v1/accounting/sales/returns` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `SalesReturnRequest` | `JournalEntryDto` |
| `POST` | `/api/v1/accounting/settlements/dealers` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `DealerSettlementRequest` | `PartnerSettlementResponse` |
| `POST` | `/api/v1/accounting/settlements/suppliers` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `SupplierSettlementRequest` | `PartnerSettlementResponse` |
| `GET` | `/api/v1/accounting/statements/suppliers/{supplierId}` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `PartnerStatementResponse` |
| `GET` | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | `hasAuthority('ROLE_ADMIN')` | `—` | `byte[]` |
| `POST` | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `AutoSettlementRequest` | `PartnerSettlementResponse` |
| `GET` | `/api/v1/accounting/trial-balance/as-of` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `—` | `TemporalBalanceService.TrialBalanceSnapshot` |

_Total documented accounting endpoints: **81**._

#### Required User Flows (API call sequences)

1. **Chart of accounts setup**
   1. `GET /api/v1/accounting/accounts` (bootstrap account list)
   2. `GET /api/v1/accounting/accounts/tree` (hierarchy rendering)
   3. `POST /api/v1/accounting/accounts` (create account)
   4. `GET /api/v1/accounting/default-accounts` + `PUT /api/v1/accounting/default-accounts` (default mappings)
   5. Optional typed tree views: `GET /api/v1/accounting/accounts/tree/{type}`

2. **Manual journal entry**
   1. Dropdown preload: `GET /api/v1/accounting/accounts`, `GET /api/v1/dealers`, `GET /api/v1/suppliers`
   2. Create manual journal: `POST /api/v1/accounting/journal-entries` (canonical manual write path)
   3. List and filter: `GET /api/v1/accounting/journals?fromDate&toDate&type&sourceModule`
   4. Reverse (single): `POST /api/v1/accounting/journals/{entryId}/reverse` or `/journal-entries/{entryId}/reverse`
   5. Reverse (cascade): `POST /api/v1/accounting/journal-entries/{entryId}/cascade-reverse`

3. **Auto-settlement (hands-off)**
   1. Dealer lookup + outstanding context: `GET /api/v1/dealers`, statement/aging endpoints as needed
   2. Dealer auto-settle: `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`
   3. Supplier auto-settle: `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`
   4. For explicit allocation flows use `POST /settlements/dealers`, `POST /settlements/suppliers`, `POST /receipts/dealer`

4. **Period close / reopen**
   1. Load periods: `GET /api/v1/accounting/periods`
   2. Validate readiness: `GET /api/v1/accounting/month-end/checklist?periodId={id}`
   3. Optionally mark checklist controls: `POST /api/v1/accounting/month-end/checklist/{periodId}`
   4. Close: `POST /api/v1/accounting/periods/{periodId}/close` (requires non-empty `note`; `force` optional)
   5. Reopen: `POST /api/v1/accounting/periods/{periodId}/reopen` (requires `reason`; auto-reverses closing journal when applicable)

5. **Bank reconciliation (persistent session workflow)**
   1. Account source: `GET /api/v1/accounting/accounts` (ASSET/bank account selection)
   2. Start draft session: `POST /api/v1/accounting/reconciliation/bank/sessions` with statement metadata
   3. Incrementally clear/un-clear lines across visits: `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`
   4. Refresh draft/full view at any time: `GET /api/v1/accounting/reconciliation/bank/sessions/{sessionId}`
   5. List previous sessions for resume/history UX: `GET /api/v1/accounting/reconciliation/bank/sessions?page={page}&size={size}`
   6. Complete and optionally link accounting period: `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`
   7. Legacy compatibility path (stateless one-shot): `POST /api/v1/accounting/reconciliation/bank` (internally uses same session service)
   8. Cross-check AR/AP controls: `GET /api/v1/accounting/reconciliation/subledger`

6. **Reconciliation discrepancy review + resolution**
   1. Trigger a fresh subledger snapshot: `GET /api/v1/accounting/reconciliation/subledger`.
   2. List discrepancies with optional filters: `GET /api/v1/accounting/reconciliation/discrepancies?status=OPEN&type=AR`.
   3. Resolve item by action:
      - acknowledge: `POST /api/v1/accounting/reconciliation/discrepancies/{id}/resolve` with `{ "resolution": "ACKNOWLEDGED", "note": "..." }`
      - adjustment journal: same endpoint with `{ "resolution": "ADJUSTMENT_JOURNAL", "adjustmentAccountId": <id>, "note": "..." }`
      - write-off: same endpoint with `{ "resolution": "WRITE_OFF", "adjustmentAccountId": <id>, "note": "..." }`
   4. Refresh list and period checklist (`GET /api/v1/accounting/month-end/checklist?periodId={id}`) to confirm `reconciliationDiscrepanciesResolved` is complete.

7. **GST return preparation**
   1. Run tax return: `GET /api/v1/accounting/gst/return?period=YYYY-MM`
   2. Run component reconciliation: `GET /api/v1/accounting/gst/reconciliation?period=YYYY-MM`
   3. Optional diagnostics for audit period: `GET /api/v1/accounting/audit/events`

7. **Opening balance CSV bootstrap (migration/import flow)**
   1. Collect opening-trial data in CSV with exact header order: `account_code,account_name,account_type,debit_amount,credit_amount,narration`
   2. Upload CSV: `POST /api/v1/accounting/opening-balances` (`multipart/form-data`, field name `file`)
   3. Handle response summary (`rowsProcessed`, `accountsCreated`, `errors[]`) and surface row-level errors inline for correction/retry
   4. Treat identical file re-uploads as replay-safe: backend deduplicates by file hash idempotency key and returns same response payload

8. **Tally XML bootstrap (migration/import flow)**
   1. Export opening data from Tally Prime XML containing `LEDGER` masters and opening `VOUCHER` entries.
   2. Upload XML via `POST /api/v1/migration/tally-import` (`multipart/form-data`, field name `file`).
   3. Render response counters (`ledgersProcessed`, `mappedLedgers`, `accountsCreated`, `openingVoucherEntriesProcessed`, `openingBalanceRowsProcessed`).
   4. Surface `unmappedGroups[]`, `unmappedItems[]`, and `errors[]` as actionable remediation list before re-upload.
   5. Treat byte-identical XML re-uploads as replay-safe: backend deduplicates by file-content SHA-256 idempotency key.

#### State Machines

1. **Journal lifecycle** (`JournalEntry.status`)
   - `DRAFT/PENDING` -> `POSTED` on successful creation/posting
   - `POSTED` -> `REVERSED` via reversal endpoints
   - `POSTED` -> `VOIDED` when reversal request uses `voidOnly=true` (still creates correction linkage)
   - Guardrails: entry must balance, period must be open, already-reversed/voided entries are rejected

2. **Accounting period lifecycle** (`AccountingPeriodStatus`)
   - `OPEN` -> `LOCKED` via `/periods/{id}/lock`
   - `OPEN` or `LOCKED` -> `CLOSED` via `/periods/{id}/close` (checklist + reconciliation + balancing validations)
   - `LOCKED/CLOSED` -> `OPEN` via `/periods/{id}/reopen` (reason required; closes snapshot + reverses closing journal when present)

3. **Reconciliation discrepancy lifecycle** (`ReconciliationDiscrepancyStatus`)
   - Created as `OPEN` when AR/AP/inventory/GST variance exceeds tolerance for the open period (triggered by subledger reconciliation sync).
   - `OPEN` -> `ACKNOWLEDGED` via resolve endpoint with `resolution=ACKNOWLEDGED` (no journal entry generated).
   - `OPEN` -> `ADJUSTED` via resolve endpoint with `resolution=ADJUSTMENT_JOURNAL` + `adjustmentAccountId` (journal entry required and linked as `resolutionJournalId`).
   - `OPEN` -> `RESOLVED` via resolve endpoint with `resolution=WRITE_OFF` + `adjustmentAccountId` (journal entry required and linked).
   - Any non-`OPEN` discrepancy is terminal for this API and cannot be resolved again.

4. **Settlement lifecycle** (frontend orchestration state)
   - `INITIATED` (draft UI form)
   - `VALIDATED` (allocations/payments pass amount/account checks)
   - `POSTED` (journal + allocation rows persisted, returns `PartnerSettlementResponse`)
   - `PARTIALLY_SETTLED` / `FULLY_SETTLED` determined by outstanding balance after allocation
   - `REVERSED` when the settlement-linked journal is reversed

5. **Bank reconciliation session lifecycle** (`BankReconciliationSessionStatus`)
   - `DRAFT` -> `DRAFT` via `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` (incremental clear/un-clear updates)
   - `DRAFT` -> `COMPLETED` via `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`
   - Terminal state: `COMPLETED` cannot be updated/edited; item updates on completed session return `BUS_001` (`BUSINESS_INVALID_STATE`)
   - Legacy endpoint `POST /api/v1/accounting/reconciliation/bank` executes the same lifecycle server-side (start -> update -> optional complete) in one request.

#### Accounting ErrorCodes (all referenced in accounting module)

| ErrorCode enum | Wire code | Description | Suggested frontend behavior |
|---|---|---|---|
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | Business rule violation | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_DUPLICATE_ENTRY` | `BUS_002` | Duplicate entry found (including migration reference replay conflicts for import flows) | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_ENTITY_NOT_FOUND` | `BUS_003` | Requested resource not found | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `BUSINESS_INVALID_STATE` | `BUS_001` | Operation not allowed in current state | Show business-rule toast/banner; do not auto-retry; keep action disabled until user changes input/state. |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Resource was modified by another user | Show stale-data dialog and force refresh before retry. |
| `INTERNAL_CONCURRENCY_FAILURE` | `CONC_003` | Internal concurrency failure | Show stale-data dialog and force refresh before retry. |
| `SYSTEM_CONFIGURATION_ERROR` | `SYS_005` | System configuration error | Show non-field error with retry option; log traceId for support. |
| `SYSTEM_DATABASE_ERROR` | `SYS_003` | Database operation failed | Show non-field error with retry option; log traceId for support. |
| `SYSTEM_INTERNAL_ERROR` | `SYS_001` | An internal error occurred | Show non-field error with retry option; log traceId for support. |
| `VALIDATION_INVALID_DATE` | `VAL_005` | Invalid date or time value | Show blocking validation message and keep form editable. |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Invalid input provided | Show blocking validation message and keep form editable. |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Invalid reference to another resource | Mark referenced selector invalid and refresh dropdown source. |
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Required field is missing | Inline field validation + prevent submit. |

#### Request DTO Contracts (all endpoint request bodies)

- **`AccountRequest`**
  - `code`: `String` — validation `@NotBlank`
  - `name`: `String` — validation `@NotBlank`
  - `type`: `AccountType` — validation `@NotNull`
  - `parentId`: `Long` — validation `—`
- **`AccrualRequest`**
  - `debitAccountId`: `Long` — validation `@NotNull`
  - `creditAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `autoReverseDate`: `LocalDate` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`BadDebtWriteOffRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- Use `CatalogBrandRequest` and `CatalogItemRequest` from the **Product Catalog & Inventory** section for current item-entry and maintenance contracts.
- **`CreditNoteRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`AutoSettlementRequest`**
  - `cashAccountId`: `Long` — validation `—`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - Replay protection is header-only: send optional `Idempotency-Key`; body `idempotencyKey` is rejected.
- **`DebitNoteRequest`**
  - `purchaseId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@DecimalMin(value = "0.01")`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`CompanyDefaultAccountsRequest`**
  - `inventoryAccountId`: `Long` — validation `—`
  - `cogsAccountId`: `Long` — validation `—`
  - `revenueAccountId`: `Long` — validation `—`
  - `discountAccountId`: `Long` — validation `—`
  - `taxAccountId`: `Long` — validation `—`
- **`LandedCostRequest`**
  - `rawMaterialPurchaseId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull`
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `offsetAccountId`: `Long` — validation `@NotNull`
  - `entryDate`: `LocalDate` — validation `—`
  - `memo`: `String` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`InventoryRevaluationRequest`**
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `revaluationAccountId`: `Long` — validation `@NotNull`
  - `deltaAmount`: `BigDecimal` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`WipAdjustmentRequest`**
  - `productionLogId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull`
  - `wipAccountId`: `Long` — validation `@NotNull`
  - `inventoryAccountId`: `Long` — validation `@NotNull`
  - `direction`: `Direction` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
- **`JournalEntryRequest`**
  - `referenceNumber`: `String` — validation `—`
  - `entryDate`: `LocalDate` — validation `@NotNull`
  - `memo`: `String` — validation `—`
  - `dealerId`: `Long` — validation `—`
  - `supplierId`: `Long` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `lines`: `List<JournalLineRequest>` — validation `@NotEmpty; @Valid`
  - `currency`: `String` — validation `—`
  - `fxRate`: `BigDecimal` — validation `—`
  - `sourceModule`: `String` — validation `—`
  - `sourceReference`: `String` — validation `—`
  - `journalType`: `String` — validation `—`
- **`JournalEntryReversalRequest`**
  - `reversalDate`: `LocalDate` — validation `—`
  - `voidOnly`: `boolean` — validation `—`
  - `reason`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `reversalPercentage`: `BigDecimal` — validation `@DecimalMin("0.01"); @DecimalMax("100.00")`
  - `cascadeRelatedEntries`: `boolean` — validation `—`
  - `relatedEntryIds`: `List<Long>` — validation `—`
  - `reasonCode`: `ReversalReasonCode` — validation `—`
  - `approvedBy`: `String` — validation `—`
  - `supportingDocumentRef`: `String` — validation `—`
- **`ManualJournalRequest`**
  - `entryDate`: `LocalDate` — validation `—`
  - `narration`: `String` — validation `—`
  - `idempotencyKey`: `String` — validation `—`
  - `adminOverride`: `Boolean` — validation `—`
  - `lines`: `List<LineRequest>` — validation `—`
- **`MonthEndChecklistUpdateRequest`**
  - `bankReconciled`: `Boolean` — validation `—`
  - `inventoryCounted`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`PayrollPaymentRequest`**
  - `payrollRunId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
- **`PayrollBatchPaymentRequest`**
  - `runDate`: `LocalDate` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `expenseAccountId`: `Long` — validation `@NotNull`
  - `taxPayableAccountId`: `Long` — validation `—`
  - `pfPayableAccountId`: `Long` — validation `—`
  - `employerTaxExpenseAccountId`: `Long` — validation `—`
  - `employerPfExpenseAccountId`: `Long` — validation `—`
  - `defaultTaxRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `defaultPfRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `employerTaxRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `employerPfRate`: `BigDecimal` — validation `@DecimalMin("0.00")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - `lines`: `List<PayrollLine>` — validation `@NotEmpty; @Valid`
- **`AccountingPeriodUpsertRequest`**
  - `year`: `int` — validation `@Min(1900); @Max(9999)`
  - `month`: `int` — validation `@Min(1); @Max(12)`
  - `costingMethod`: `CostingMethod` — validation `—`
- **`AccountingPeriodUpdateRequest`**
  - `costingMethod`: `CostingMethod` — validation `@NotNull`
- **`AccountingPeriodCloseRequest`**
  - `force`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`AccountingPeriodLockRequest`**
  - `reason`: `String` — validation `—`
- **`AccountingPeriodReopenRequest`**
  - `reason`: `String` — validation `—`
- **`DealerReceiptRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `amount`: `BigDecimal` — validation `@NotNull; @DecimalMin(value = "0.01")`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - Replay protection is header-only: send optional `Idempotency-Key`; body `idempotencyKey` is rejected.
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty(message = "Allocations are required for dealer receipts; use settlement endpoints or include allocations"); @Valid`
- **`DealerReceiptSplitRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `incomingLines`: `List<IncomingLine>` — validation `@NotEmpty; @Valid`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - Replay protection is header-only: send optional `Idempotency-Key`; body `idempotencyKey` is rejected.
- **`BankReconciliationRequest`**
  - `bankAccountId`: `Long` — validation `@NotNull`
  - `statementDate`: `LocalDate` — validation `@NotNull`
  - `statementEndingBalance`: `BigDecimal` — validation `@NotNull`
  - `startDate`: `LocalDate` — validation `—`
  - `endDate`: `LocalDate` — validation `—`
  - `clearedReferences`: `List<String>` — validation `—`
  - `accountingPeriodId`: `Long` — validation `—`
  - `markAsComplete`: `Boolean` — validation `—`
  - `note`: `String` — validation `—`
- **`BankReconciliationSessionCreateRequest`**
  - `bankAccountId`: `Long` — validation `@NotNull`
  - `statementDate`: `LocalDate` — validation `@NotNull`
  - `statementEndingBalance`: `BigDecimal` — validation `@NotNull`
  - `startDate`: `LocalDate` — validation `—` (defaults to first day of statement month)
  - `endDate`: `LocalDate` — validation `—` (defaults to statement date)
  - `accountingPeriodId`: `Long` — validation `—` (when provided must belong to same company and statement month)
  - `note`: `String` — validation `—`
- **`BankReconciliationSessionItemsUpdateRequest`**
  - `addJournalLineIds`: `List<Long>` — validation `—` (ids must be positive and belong to same company + session bank account)
  - `removeJournalLineIds`: `List<Long>` — validation `—` (ids removed from cleared set if present)
  - `note`: `String` — validation `—` (trimmed and stored on session)
- **`BankReconciliationSessionCompletionRequest`**
  - `note`: `String` — validation `—`
  - `accountingPeriodId`: `Long` — validation `—` (optional override; must be OPEN and month-matching statement date)
- **`ReconciliationDiscrepancyResolveRequest`**
  - `resolution`: `ReconciliationDiscrepancyResolution` — validation `@NotNull`; enum values `ACKNOWLEDGED | ADJUSTMENT_JOURNAL | WRITE_OFF`
  - `note`: `String` — validation `—`
  - `adjustmentAccountId`: `Long` — validation `—`; required for `ADJUSTMENT_JOURNAL` and `WRITE_OFF`, ignored for `ACKNOWLEDGED`
- **`SalesReturnRequest`**
  - `invoiceId`: `Long` — validation `@NotNull`
  - `reason`: `String` — validation `@NotBlank`
  - `lines`: `List<ReturnLine>` — validation `@NotEmpty; @Valid`
- **`DealerSettlementRequest`**
  - `dealerId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `—`
  - `discountAccountId`: `Long` — validation `—`
  - `writeOffAccountId`: `Long` — validation `—`
  - `fxGainAccountId`: `Long` — validation `—`
  - `fxLossAccountId`: `Long` — validation `—`
  - `settlementDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - Replay protection is header-only: send optional `Idempotency-Key`; body `idempotencyKey` is rejected.
  - `adminOverride`: `Boolean` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty; @Valid`
  - `payments`: `List<SettlementPaymentRequest>` — validation `@Valid`
- **`SupplierSettlementRequest`**
  - `supplierId`: `Long` — validation `@NotNull`
  - `cashAccountId`: `Long` — validation `@NotNull`
  - `discountAccountId`: `Long` — validation `—`
  - `writeOffAccountId`: `Long` — validation `—`
  - `fxGainAccountId`: `Long` — validation `—`
  - `fxLossAccountId`: `Long` — validation `—`
  - `settlementDate`: `LocalDate` — validation `—`
  - `referenceNumber`: `String` — validation `—`
  - `memo`: `String` — validation `—`
  - Replay protection is header-only: send optional `Idempotency-Key`; body `idempotencyKey` is rejected.
  - `adminOverride`: `Boolean` — validation `—`
  - `allocations`: `List<SettlementAllocationRequest>` — validation `@NotEmpty; @Valid`
- **`multipart/form-data` (`POST /api/v1/accounting/opening-balances`)**
  - `file`: `MultipartFile` — required CSV upload part (`text/csv` recommended, accepted as multipart binary)
  - CSV required headers: `account_code,account_name,account_type,debit_amount,credit_amount,narration`
  - `account_type` enum domain: `ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE`
  - Row validations: either debit or credit must be positive (not both/non-zero, not both zero), `account_code` required, `account_name` required for auto-created accounts
- **`multipart/form-data` (`POST /api/v1/migration/tally-import`)**
  - `file`: `MultipartFile` — required Tally XML upload part (`application/xml` or multipart binary)
  - XML content expectations: includes `LEDGER` masters (`NAME`, `PARENT`) and optional opening `VOUCHER` blocks with `ALLLEDGERENTRIES.LIST` + `AMOUNT`
  - Backend mapping: known Tally ledger groups map to ERP `AccountType`; unknown groups are reported via `unmappedGroups[]`
  - Re-upload behavior: file-content hash is used as idempotency key; identical file bytes replay previous result payload

#### Response DTO Contracts (all endpoint `data` types)

- **`List<AccountDto>`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `code`: `String`
  - `name`: `String`
  - `type`: `AccountType`
  - `balance`: `BigDecimal`
- **`AccountDto`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `code`: `String`
  - `name`: `String`
  - `type`: `AccountType`
  - `balance`: `BigDecimal`
- **`List<AccountHierarchyService.AccountNode>`**
  - `id`: `Long`
  - `code`: `String`
  - `name`: `String`
  - `type`: `String`
  - `balance`: `BigDecimal`
  - `level`: `Integer`
  - `parentId`: `Long`
  - `children`: `List<AccountNode>`
- **`TemporalBalanceService.AccountActivityReport`**
  - `accountCode`: `String`
  - `accountName`: `String`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `openingBalance`: `BigDecimal`
  - `closingBalance`: `BigDecimal`
  - `totalDebits`: `BigDecimal`
  - `totalCredits`: `BigDecimal`
  - `movements`: `List<AccountMovement>`
- **`BigDecimal`**
  - Primitive/raw payload type (no DTO field list).
- **`TemporalBalanceService.BalanceComparison`**
  - `accountId`: `Long`
  - `date1`: `LocalDate`
  - `balance1`: `BigDecimal`
  - `date2`: `LocalDate`
  - `balance2`: `BigDecimal`
  - `change`: `BigDecimal`
- **`JournalEntryDto`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfEntryId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `lines`: `List<JournalLineDto>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- **`AgingSummaryResponse`**
  - `partnerId`: `Long`
  - `partnerName`: `String`
  - `totalOutstanding`: `BigDecimal`
  - `buckets`: `List<AgingBucketDto>`
- **`byte[]`**
  - Primitive/raw payload type (no DTO field list).
- **`PageResponse<AuditFeedItemDto>`**
  - `content`: `List<AuditFeedItemDto>`
    - `sourceId`: `Long`
    - `sourceKind`: `String`
    - `category`: `String`
    - `occurredAt`: `Instant`
    - `companyId`: `Long`
    - `companyCode`: `String`
    - `module`: `String`
    - `action`: `String`
    - `status`: `String`
    - `actorUserId`: `Long`
    - `actorIdentifier`: `String`
    - `subjectUserId`: `Long`
    - `subjectIdentifier`: `String`
    - `entityType`: `String`
    - `entityId`: `String`
    - `referenceNumber`: `String`
    - `requestMethod`: `String`
    - `requestPath`: `String`
    - `traceId`: `String`
    - `metadata`: `Map<String, String>`
  - `totalElements`: `long`
  - `totalPages`: `int`
  - `page`: `int` (0-based)
  - `size`: `int`
- **`String`**
  - Primitive/raw payload type (no DTO field list).
- **`PageResponse<AccountingTransactionAuditListItemDto>`**
  - `content`: `List<AccountingTransactionAuditListItemDto>`
    - `journalEntryId`: `Long`
    - `referenceNumber`: `String`
    - `entryDate`: `LocalDate`
    - `status`: `String`
    - `module`: `String`
    - `transactionType`: `String`
    - `memo`: `String`
    - `dealerId`: `Long`
    - `dealerName`: `String`
    - `supplierId`: `Long`
    - `supplierName`: `String`
    - `totalDebit`: `BigDecimal`
    - `totalCredit`: `BigDecimal`
    - `reversalOfId`: `Long`
    - `reversalEntryId`: `Long`
    - `correctionType`: `String`
    - `consistencyStatus`: `String`
    - `postedAt`: `Instant`
  - `totalElements`: `long`
  - `totalPages`: `int`
  - `page`: `int` (0-based)
  - `size`: `int`
- **`AccountingTransactionAuditDetailDto`**
  - `journalEntryId`: `Long`
  - `journalPublicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `status`: `String`
  - `module`: `String`
  - `transactionType`: `String`
  - `memo`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `totalDebit`: `BigDecimal`
  - `totalCredit`: `BigDecimal`
  - `consistencyStatus`: `String`
  - `consistencyNotes`: `List<String>`
  - `lines`: `List<JournalLineDto>`
  - `linkedDocuments`: `List<LinkedDocument>`
  - `settlementAllocations`: `List<SettlementAllocation>`
  - `eventTrail`: `List<EventTrailItem>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- No public accounting-specific catalog response DTOs remain.
- Use `CatalogBrandDto`, `CatalogItemDto`, `CatalogItemStockDto`, and `SkuReadinessDto` from the **Product Catalog & Inventory** section for the live catalog contract.
- **`ConfigurationHealthService.ConfigurationHealthReport`**
  - `healthy`: `boolean` (true when no issues are present)
  - `issues`: `List<ConfigurationIssue>`
    - `companyCode`: `String` (tenant/company code where issue was detected)
    - `domain`: `String` (issue category, e.g. `DEFAULT_ACCOUNTS`, `TAX_ACCOUNT`, `PRODUCTION_METADATA`)
    - `reference`: `String` (entity reference such as SKU, `BASE`, or `COMPANY_DEFAULTS`)
    - `message`: `String` (human-readable remediation hint)
- **`Map<String, Object>`**
  - Primitive/raw payload type (no DTO field list).
- **`PartnerSettlementResponse`**
  - `journalEntry`: `JournalEntryDto`
  - `totalApplied`: `BigDecimal`
  - `cashAmount`: `BigDecimal`
  - `totalDiscount`: `BigDecimal`
  - `totalWriteOff`: `BigDecimal`
  - `totalFxGain`: `BigDecimal`
  - `totalFxLoss`: `BigDecimal`
  - `allocations`: `List<Allocation>`
- **`CompanyDefaultAccountsResponse`**
  - `inventoryAccountId`: `Long`
  - `cogsAccountId`: `Long`
  - `revenueAccountId`: `Long`
  - `discountAccountId`: `Long`
  - `taxAccountId`: `Long`
- **`GstReconciliationDto`**
  - `period`: `YearMonth`
  - `periodStart`: `LocalDate`
  - `periodEnd`: `LocalDate`
  - `collected`: `GstComponentSummary`
  - `inputTaxCredit`: `GstComponentSummary`
  - `netLiability`: `GstComponentSummary`
  - `cgst`: `BigDecimal`
  - `sgst`: `BigDecimal`
  - `igst`: `BigDecimal`
  - `total`: `BigDecimal`
- **`GstReturnDto`**
  - `period`: `YearMonth`
  - `periodStart`: `LocalDate`
  - `periodEnd`: `LocalDate`
  - `outputTax`: `BigDecimal`
  - `inputTax`: `BigDecimal`
  - `netPayable`: `BigDecimal`
- **`List<JournalEntryDto>`**
  - `id`: `Long`
  - `publicId`: `UUID`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `supplierId`: `Long`
  - `supplierName`: `String`
  - `accountingPeriodId`: `Long`
  - `accountingPeriodLabel`: `String`
  - `accountingPeriodStatus`: `String`
  - `reversalOfEntryId`: `Long`
  - `reversalEntryId`: `Long`
  - `correctionType`: `String`
  - `correctionReason`: `String`
  - `voidReason`: `String`
  - `lines`: `List<JournalLineDto>`
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
  - `postedAt`: `Instant`
  - `createdBy`: `String`
  - `postedBy`: `String`
  - `lastModifiedBy`: `String`
- **`List<JournalListItemDto>`**
  - `id`: `Long`
  - `referenceNumber`: `String`
  - `entryDate`: `LocalDate`
  - `memo`: `String`
  - `status`: `String`
  - `journalType`: `String`
  - `sourceModule`: `String`
  - `sourceReference`: `String`
  - `totalDebit`: `BigDecimal`
  - `totalCredit`: `BigDecimal`
- **`MonthEndChecklistDto`**
  - `period`: `AccountingPeriodDto`
  - `items`: `List<MonthEndChecklistItemDto>`
    - canonical reconciliation/status control keys surfaced in `items[].key`: `inventoryReconciled`, `arReconciled`, `apReconciled`, `gstReconciled`, `reconciliationDiscrepanciesResolved`, `unbalancedJournals`, `unlinkedDocuments`, `uninvoicedReceipts`, `unpostedDocuments`, `trialBalanceBalanced`, `bankReconciled`, `inventoryCounted`
  - `readyToClose`: `boolean`
- **`PayrollBatchPaymentResponse`**
  - `payrollRunId`: `Long`
  - `runDate`: `LocalDate`
  - `grossAmount`: `BigDecimal`
  - `totalTaxWithholding`: `BigDecimal`
  - `totalPfWithholding`: `BigDecimal`
  - `totalAdvances`: `BigDecimal`
  - `netPayAmount`: `BigDecimal`
  - `employerTaxAmount`: `BigDecimal`
  - `employerPfAmount`: `BigDecimal`
  - `totalEmployerCost`: `BigDecimal`
  - `payrollJournalId`: `Long`
  - `employerContribJournalId`: `Long`
  - `lines`: `List<LineTotal>`
- **`List<AccountingPeriodDto>`**
  - `id`: `Long`
  - `year`: `int`
  - `month`: `int`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `label`: `String`
  - `status`: `String`
  - `bankReconciled`: `boolean`
  - `bankReconciledAt`: `Instant`
  - `bankReconciledBy`: `String`
  - `inventoryCounted`: `boolean`
  - `inventoryCountedAt`: `Instant`
  - `inventoryCountedBy`: `String`
  - `closedAt`: `Instant`
  - `closedBy`: `String`
  - `closedReason`: `String`
  - `lockedAt`: `Instant`
  - `lockedBy`: `String`
  - `lockReason`: `String`
  - `reopenedAt`: `Instant`
  - `reopenedBy`: `String`
  - `reopenReason`: `String`
  - `closingJournalEntryId`: `Long`
  - `checklistNotes`: `String`
  - `costingMethod`: `String`
- **`AccountingPeriodDto`**
  - `id`: `Long`
  - `year`: `int`
  - `month`: `int`
  - `startDate`: `LocalDate`
  - `endDate`: `LocalDate`
  - `label`: `String`
  - `status`: `String`
  - `bankReconciled`: `boolean`
  - `bankReconciledAt`: `Instant`
  - `bankReconciledBy`: `String`
  - `inventoryCounted`: `boolean`
  - `inventoryCountedAt`: `Instant`
  - `inventoryCountedBy`: `String`
  - `closedAt`: `Instant`
  - `closedBy`: `String`
  - `closedReason`: `String`
  - `lockedAt`: `Instant`
  - `lockedBy`: `String`
  - `lockReason`: `String`
  - `reopenedAt`: `Instant`
  - `reopenedBy`: `String`
  - `reopenReason`: `String`
  - `closingJournalEntryId`: `Long`
  - `checklistNotes`: `String`
  - `costingMethod`: `String`
- **`BankReconciliationSummaryDto`**
  - `accountId`: `Long`
  - `accountCode`: `String`
  - `accountName`: `String`
  - `statementDate`: `LocalDate`
  - `ledgerBalance`: `BigDecimal`
  - `statementEndingBalance`: `BigDecimal`
  - `outstandingDeposits`: `BigDecimal`
  - `outstandingChecks`: `BigDecimal`
  - `difference`: `BigDecimal`
  - `balanced`: `boolean`
  - `unclearedDeposits`: `List<BankReconciliationItemDto>`
  - `unclearedChecks`: `List<BankReconciliationItemDto>`
- **`PageResponse<BankReconciliationSessionSummaryDto>`**
  - `content`: `List<BankReconciliationSessionSummaryDto>`
    - `sessionId`: `Long`
    - `referenceNumber`: `String`
    - `bankAccountId`: `Long`
    - `bankAccountCode`: `String`
    - `bankAccountName`: `String`
    - `statementDate`: `LocalDate`
    - `statementEndingBalance`: `BigDecimal`
    - `status`: `String` (`DRAFT` or `COMPLETED`)
    - `createdBy`: `String`
    - `createdAt`: `Instant`
    - `completedAt`: `Instant`
    - `summary`: `BankReconciliationSummaryDto`
    - `clearedItemCount`: `int`
  - `totalElements`: `long`
  - `totalPages`: `int`
  - `page`: `int` (0-based)
  - `size`: `int`
- **`BankReconciliationSessionSummaryDto`**
  - `sessionId`: `Long`
  - `referenceNumber`: `String`
  - `bankAccountId`: `Long`
  - `bankAccountCode`: `String`
  - `bankAccountName`: `String`
  - `statementDate`: `LocalDate`
  - `statementEndingBalance`: `BigDecimal`
  - `status`: `String` (`DRAFT` or `COMPLETED`)
  - `createdBy`: `String`
  - `createdAt`: `Instant`
  - `completedAt`: `Instant`
  - `summary`: `BankReconciliationSummaryDto`
  - `clearedItemCount`: `int`
- **`BankReconciliationSessionDetailDto`**
  - `sessionId`: `Long`
  - `referenceNumber`: `String`
  - `bankAccountId`: `Long`
  - `bankAccountCode`: `String`
  - `bankAccountName`: `String`
  - `statementDate`: `LocalDate`
  - `statementEndingBalance`: `BigDecimal`
  - `status`: `String` (`DRAFT` or `COMPLETED`)
  - `accountingPeriodId`: `Long`
  - `note`: `String`
  - `createdBy`: `String`
  - `createdAt`: `Instant`
  - `completedBy`: `String`
  - `completedAt`: `Instant`
  - `clearedItems`: `List<ClearedItemDto>`
    - `itemId`: `Long`
    - `journalLineId`: `Long`
    - `journalEntryId`: `Long`
    - `referenceNumber`: `String`
    - `entryDate`: `LocalDate`
    - `memo`: `String`
    - `debit`: `BigDecimal`
    - `credit`: `BigDecimal`
    - `netAmount`: `BigDecimal`
    - `clearedAt`: `Instant`
    - `clearedBy`: `String`
  - `unclearedDeposits`: `List<BankReconciliationItemDto>`
  - `unclearedChecks`: `List<BankReconciliationItemDto>`
  - `summary`: `BankReconciliationSummaryDto`
- **`ReconciliationService.SubledgerReconciliationReport`**
  - `dealerReconciliation`: `ReconciliationResult`
  - `supplierReconciliation`: `SupplierReconciliationResult`
  - `combinedVariance`: `BigDecimal`
  - `reconciled`: `boolean`
- **`ReconciliationDiscrepancyListResponse`**
  - `items`: `List<ReconciliationDiscrepancyDto>`
  - `openCount`: `long`
  - `resolvedCount`: `long` (count of statuses `ACKNOWLEDGED`, `ADJUSTED`, `RESOLVED`)
- **`ReconciliationDiscrepancyDto`**
  - `id`: `Long`
  - `accountingPeriodId`: `Long`
  - `periodStart`: `LocalDate`
  - `periodEnd`: `LocalDate`
  - `type`: `String` (`AR`, `AP`, `INVENTORY`, `GST`)
  - `partnerType`: `String` (`DEALER`, `SUPPLIER`, nullable)
  - `partnerId`: `Long` (nullable)
  - `partnerCode`: `String` (nullable)
  - `partnerName`: `String` (nullable)
  - `expectedAmount`: `BigDecimal`
  - `actualAmount`: `BigDecimal`
  - `variance`: `BigDecimal`
  - `status`: `String` (`OPEN`, `ACKNOWLEDGED`, `ADJUSTED`, `RESOLVED`)
  - `resolution`: `String` (`ACKNOWLEDGED`, `ADJUSTMENT_JOURNAL`, `WRITE_OFF`, nullable)
  - `resolutionNote`: `String` (nullable)
  - `resolutionJournalId`: `Long` (nullable)
  - `resolvedBy`: `String` (nullable)
  - `resolvedAt`: `Instant` (nullable)
  - `createdAt`: `Instant`
  - `updatedAt`: `Instant`
- **`AgingReportService.DealerAgingDetail`**
  - `dealerId`: `Long`
  - `dealerCode`: `String`
  - `dealerName`: `String`
  - `buckets`: `AgingBuckets`
  - `totalOutstanding`: `BigDecimal`
- **`AgingReportService.DealerAgingDetailedReport`**
  - `dealerId`: `Long`
  - `dealerCode`: `String`
  - `dealerName`: `String`
  - `lineItems`: `List<AgingLineItem>`
  - `buckets`: `AgingBuckets`
  - `totalOutstanding`: `BigDecimal`
  - `averageDSO`: `double`
- **`AgingReportService.AgedReceivablesReport`**
  - `asOfDate`: `LocalDate`
  - `dealers`: `List<DealerAgingDetail>`
  - `totalBuckets`: `AgingBuckets`
  - `grandTotal`: `BigDecimal`
- **`AccountHierarchyService.BalanceSheetHierarchy`**
  - `assets`: `List<AccountNode>`
  - `totalAssets`: `BigDecimal`
  - `liabilities`: `List<AccountNode>`
  - `totalLiabilities`: `BigDecimal`
  - `equity`: `List<AccountNode>`
  - `totalEquity`: `BigDecimal`
- **`AgingReportService.DSOReport`**
  - `dealerId`: `Long`
  - `dealerName`: `String`
  - `averageDSO`: `double`
  - `totalOutstanding`: `BigDecimal`
  - `openInvoices`: `int`
  - `overdueInvoices`: `long`
- **`AccountHierarchyService.IncomeStatementHierarchy`**
  - `revenue`: `List<AccountNode>`
  - `totalRevenue`: `BigDecimal`
  - `cogs`: `List<AccountNode>`
  - `totalCogs`: `BigDecimal`
  - `grossProfit`: `BigDecimal`
  - `expenses`: `List<AccountNode>`
  - `totalExpenses`: `BigDecimal`
  - `netIncome`: `BigDecimal`
- **`PartnerStatementResponse`**
  - `partnerId`: `Long`
  - `partnerName`: `String`
  - `fromDate`: `LocalDate`
  - `toDate`: `LocalDate`
  - `openingBalance`: `BigDecimal`
  - `closingBalance`: `BigDecimal`
  - `transactions`: `List<StatementTransactionDto>`
- **`TemporalBalanceService.TrialBalanceSnapshot`**
  - `asOfDate`: `LocalDate`
  - `entries`: `List<TrialBalanceEntry>`
  - `totalDebits`: `BigDecimal`
  - `totalCredits`: `BigDecimal`
- **`OpeningBalanceImportResponse`**
  - `rowsProcessed`: `int` (count of rows successfully mapped into journal lines)
  - `accountsCreated`: `int` (new accounts auto-created from CSV rows)
  - `errors`: `List<ImportError>`
    - `rowNumber`: `long` (`0` used for file-level errors like debit/credit imbalance)
    - `message`: `String` (row-level validation or mapping failure; import continues for other rows)
- **`TallyImportResponse`**
  - `ledgersProcessed`: `int` (total `LEDGER` records parsed)
  - `mappedLedgers`: `int` (ledgers successfully mapped to known `AccountType` groups)
  - `accountsCreated`: `int` (new accounts created from mapped ledgers or opening rows)
  - `openingVoucherEntriesProcessed`: `int` (opening voucher ledger rows parsed)
  - `openingBalanceRowsProcessed`: `int` (rows submitted to opening-balance posting path)
  - `unmappedGroups`: `List<String>` (group names with no mapping)
  - `unmappedItems`: `List<String>` (ledger names from opening rows that could not be mapped)
  - `errors`: `List<ImportError>`
    - `context`: `String` (e.g., `ledger:<name>`, `opening-row:<name>`, `opening-balance-row-<n>`)
    - `message`: `String` (mapping/parsing or posting error detail)

#### UI Hints (accounting screens)

- **Dropdown sources**
  - Account dropdowns: `GET /api/v1/accounting/accounts`
  - Dealer dropdowns/search: `GET /api/v1/dealers`, `GET /api/v1/dealers/search?query=`
  - Supplier dropdowns: `GET /api/v1/suppliers`
  - Catalog item selection in accounting context: `GET /api/v1/catalog/items?itemClass=...&includeReadiness=true`
- **Computed fields**
  - GST component split is computed server-side: `taxType=INTRA_STATE` => `cgst+sgst`; `INTER_STATE` => `igst`
  - Settlement totals (`totalApplied`, `totalDiscount`, `totalFxGain/loss`) are computed; render read-only summary cards
  - Statement running balances and period checklist readiness are server computed; never recompute from partial UI data
- **Dependent fields**
  - `sourceState`/`destState` (dealer/supplier/company state codes) decide GST type and tax split
  - In settlement requests, non-zero discount/write-off/fx values require corresponding account IDs (`discountAccountId`, `writeOffAccountId`, etc.)
  - Period close requires checklist controls satisfied unless `force=true` is explicitly used
  - Month-end checklist mutations (`confirmBankReconciliation`, `confirmInventoryCount`, `updateMonthEndChecklist`) are rejected when period status is `LOCKED` or `CLOSED`.
  - Bank reconciliation session completion with `accountingPeriodId` requires an OPEN period matching `statementDate` month/year.
  - Reconciliation discrepancy resolutions require `adjustmentAccountId` for journal actions and only accept `OPEN` records.
- **Bank reconciliation session UX**
  - Keep draft reconciliation state client-side by `sessionId`; users can leave/return and continue via `GET /reconciliation/bank/sessions/{sessionId}`.
  - For line-level toggles, send incremental diffs (`addJournalLineIds`, `removeJournalLineIds`) instead of replacing whole sets.
  - Treat `BUS_001` on update as immutable-completed-session signal; disable line selection and show read-only completed snapshot.
  - Use `GET /reconciliation/bank/sessions` for history drawer/table and show `status`, `createdAt`, `completedAt`, and `clearedItemCount` badges.
- **Idempotency**
  - For accounting receipt/settlement/auto-settle mutations, send only `Idempotency-Key` when providing a replay key. `X-Idempotency-Key` and body `idempotencyKey` are rejected, and replay drift returns `409 CONCURRENCY_CONFLICT` with partner/idempotency metadata.
  - Opening balance import uses file-content hash idempotency internally; frontend does **not** need to send idempotency header, but should preserve identical file bytes when expecting replay behavior.
  - Tally XML import also uses file-content hash idempotency internally; frontend should preserve exact file bytes for replay expectations.
- **Opening balance import UX**
  - Restrict action to admin role surfaces only; accounting-role users receive `403`.
  - Show downloadable CSV template with exact headers/order to reduce row errors.
  - Render `errors[]` as row-level table (row number + message) while still showing partial success counters.
  - After successful import with `rowsProcessed > 0` and empty `errors`, refresh journal list filtered by `sourceModule=OPENING_BALANCE` and recent date.
- **Tally import UX**
  - Restrict action to admin role surfaces only; accounting-role users receive `403`.
  - Display `unmappedGroups[]` and `unmappedItems[]` prominently so users can fix Tally group/ledger mapping before retry.
  - Render `errors[]` grouped by `context` to separate ledger-mapping issues from posting-row issues.
  - After successful import, refresh chart-of-accounts and journal list filtered by `sourceModule=OPENING_BALANCE` because import posts through opening-balance journal path.

### Product Catalog & Inventory

Comprehensive handoff for `VAL-DOC-004` covering catalog, inventory, dispatch, and manufacturing API surfaces.

> Response convention: endpoints below return `ApiResponse<T>` unless explicitly noted (`DELETE /api/v1/factory/production-plans/{id}` returns `204`).

##### Implementation note (inventory-engine-decomposition)

- Inventory backend internals were decomposed into focused services (`FinishedGoodsReservationEngine`, `FinishedGoodsDispatchEngine`, `PackagingSlipService`, `InventoryValuationService`, `InventoryMovementRecorder`) while preserving external API contracts.
- Frontend endpoint paths, request/response DTOs, and dispatch/reservation/backorder state transitions are unchanged.
- Existing UI flows in this section remain valid; no client migration is required for this refactor.

#### Endpoint Map — Catalog (brands/items/search)

Auth default: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SALES','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/catalog/brands` | `CatalogBrandRequest` | `CatalogBrandDto` |
| GET | `/api/v1/catalog/brands` | Query: `active?` | `List<CatalogBrandDto>` |
| GET | `/api/v1/catalog/brands/{brandId}` | — | `CatalogBrandDto` |
| PUT | `/api/v1/catalog/brands/{brandId}` | `CatalogBrandRequest` | `CatalogBrandDto` |
| DELETE | `/api/v1/catalog/brands/{brandId}` | — | `CatalogBrandDto` (deactivated) |
| GET | `/api/v1/catalog/items` | Query: `q?`, `itemClass?`, `includeStock?`, `includeReadiness?`, `page`, `pageSize` | `PageResponse<CatalogItemDto>` |
| POST | `/api/v1/catalog/items` | `CatalogItemRequest` | `CatalogItemDto` |
| GET | `/api/v1/catalog/items/{itemId}` | Query: `includeStock?`, `includeReadiness?` | `CatalogItemDto` |
| PUT | `/api/v1/catalog/items/{itemId}` | `CatalogItemRequest` | `CatalogItemDto` |
| DELETE | `/api/v1/catalog/items/{itemId}` | — | `CatalogItemDto` (deactivated) |

Catalog contract rules:

- create a new brand on `POST /api/v1/catalog/brands`, then pass the returned active `brandId` into single-item create/update on `/api/v1/catalog/items`
- use `GET /api/v1/catalog/items` and `GET /api/v1/catalog/items/{itemId}` with `includeReadiness=true` when setup or factory-adjacent users need readiness visibility before execution
- UI may label related sellable sizes as a `Product Family`, but the public setup contract does not ask operators for raw family/group identifiers
- frontend item entry and browse flows should call only the catalog endpoints listed in this section

#### Endpoint Map — Inventory (stock, batches, movement history, adjustments, dispatch)

##### Finished goods stock + batch APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/finished-goods` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `List<FinishedGoodDto>` |
| GET | `/api/v1/finished-goods/{id}` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `FinishedGoodDto` |
| POST | `/api/v1/finished-goods` | ADMIN/FACTORY | `FinishedGoodRequest` | `FinishedGoodDto` |
| PUT | `/api/v1/finished-goods/{id}` | ADMIN/FACTORY | `FinishedGoodRequest` | `FinishedGoodDto` |
| GET | `/api/v1/finished-goods/{id}/batches` | ADMIN/FACTORY/SALES | — | `List<FinishedGoodBatchDto>` |
| POST | `/api/v1/finished-goods/{id}/batches` | ADMIN/FACTORY | `FinishedGoodBatchRequest` | `FinishedGoodBatchDto` |
| GET | `/api/v1/finished-goods/stock-summary` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `List<StockSummaryDto>` |
| GET | `/api/v1/finished-goods/low-stock` | ADMIN/FACTORY/SALES | Query: `threshold?` | `List<FinishedGoodDto>` |
| GET | `/api/v1/finished-goods/{id}/low-stock-threshold` | ADMIN/FACTORY/SALES/ACCOUNTING | — | `FinishedGoodLowStockThresholdDto` |
| PUT | `/api/v1/finished-goods/{id}/low-stock-threshold` | ADMIN/FACTORY/ACCOUNTING | `FinishedGoodLowStockThresholdRequest` | `FinishedGoodLowStockThresholdDto` |

##### Raw material stock + batch APIs

Auth default for controller: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/catalog/items` | Query: `q`, `itemClass`, `includeStock`, `includeReadiness`, `page`, `pageSize` | `PageResponse<CatalogItemDto>` |
| POST | `/api/v1/catalog/items` | `CatalogItemRequest` (`itemClass=FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL`) | `CatalogItemDto` |
| PUT | `/api/v1/catalog/items/{itemId}` | `CatalogItemRequest` | `CatalogItemDto` |
| DELETE | `/api/v1/catalog/items/{itemId}` | — | `CatalogItemDto` |
| GET | `/api/v1/raw-materials/stock` | — | `StockSummaryDto` |
| GET | `/api/v1/raw-materials/stock/inventory` | — | `List<InventoryStockSnapshot>` |
| GET | `/api/v1/raw-materials/stock/low-stock` | — | `List<InventoryStockSnapshot>` |
| POST | `/api/v1/inventory/raw-materials/adjustments` | Header/body idempotency + `RawMaterialAdjustmentRequest` | `RawMaterialAdjustmentDto` |

Notes:
- Retired catalog routes such as `/api/v1/catalog/products`, `/api/v1/catalog/products/single`, and `/api/v1/catalog/products/bulk-variants` are not part of the current contract. Frontend product creation should target `/api/v1/catalog/items` one item at a time.
- `/api/v1/raw-materials/intake` is retired and returns `404`; raw-material stock changes should use `/api/v1/inventory/raw-materials/adjustments`.

##### Inventory adjustment + traceability APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/inventory/adjustments` | ADMIN/ACCOUNTING | — | `List<InventoryAdjustmentDto>` |
| POST | `/api/v1/inventory/adjustments` | ADMIN/ACCOUNTING | Header/body idempotency + `InventoryAdjustmentRequest` | `InventoryAdjustmentDto` |
| POST | `/api/v1/inventory/raw-materials/adjustments` | ADMIN/ACCOUNTING | Header/body idempotency + `RawMaterialAdjustmentRequest` | `RawMaterialAdjustmentDto` |
| GET | `/api/v1/inventory/batches/expiring-soon` | ADMIN/ACCOUNTING/FACTORY/SALES | Query: `days` (default `30`, negatives clamped to `0`) | `List<InventoryExpiringBatchDto>` |
| GET | `/api/v1/inventory/batches/{id}/movements` | ADMIN/FACTORY/ACCOUNTING/SALES | Query: `batchType?` | `InventoryBatchTraceabilityDto` |
| POST | `/api/v1/inventory/opening-stock` | ADMIN/ACCOUNTING/FACTORY | `multipart/form-data` (`file`) + idempotency header | `OpeningStockImportResponse` |
| GET | `/api/v1/inventory/opening-stock` | ADMIN/ACCOUNTING/FACTORY | Query: `page` (default `0`), `size` (default `20`) | `PageResponse<OpeningStockImportHistoryItem>` |

##### Dispatch APIs

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/dispatch/pending` | ADMIN/FACTORY/SALES | — | `List<PackagingSlipDto>` |
| GET | `/api/v1/dispatch/preview/{slipId}` | ADMIN/FACTORY | — | `DispatchPreviewDto` |
| GET | `/api/v1/dispatch/slip/{slipId}` | ADMIN/FACTORY/SALES | — | `PackagingSlipDto` |
| GET | `/api/v1/dispatch/order/{orderId}` | ADMIN/FACTORY/SALES | — | `PackagingSlipDto` |
| GET | `/api/v1/dispatch/slip/{slipId}/challan/pdf` | ADMIN/FACTORY | — | `application/pdf` |

#### Endpoint Map — Manufacturing (plans, logs, packing, wastage)

##### Core factory endpoints (`/api/v1/factory`)

Default auth: `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')` unless noted.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/factory/production-plans` | — | `List<ProductionPlanDto>` |
| POST | `/api/v1/factory/production-plans` | `ProductionPlanRequest` | `ProductionPlanDto` |
| PUT | `/api/v1/factory/production-plans/{id}` | `ProductionPlanRequest` | `ProductionPlanDto` |
| PATCH | `/api/v1/factory/production-plans/{id}/status` | Body `{ status }` | `ProductionPlanDto` |
| DELETE | `/api/v1/factory/production-plans/{id}` | — | `204 No Content` |
| GET | `/api/v1/factory/tasks` | — | `List<FactoryTaskDto>` |
| POST | `/api/v1/factory/tasks` | `FactoryTaskRequest` | `FactoryTaskDto` |
| PUT | `/api/v1/factory/tasks/{id}` | `FactoryTaskRequest` | `FactoryTaskDto` |
| GET | `/api/v1/factory/dashboard` | — | `FactoryDashboardDto` |
| POST | `/api/v1/factory/cost-allocation` | `CostAllocationRequest` | `CostAllocationResponse` |

##### Production logs (`/api/v1/factory/production/logs`)

Auth: `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/factory/production/logs` | `ProductionLogRequest` | `ProductionLogDetailDto` |
| GET | `/api/v1/factory/production/logs` | — | `List<ProductionLogDto>` |
| GET | `/api/v1/factory/production/logs/{id}` | — | `ProductionLogDetailDto` |

##### Packing + bulk-to-size packing (`/api/v1/factory`)

Auth default: `hasAnyAuthority('ROLE_FACTORY','ROLE_ACCOUNTING','ROLE_ADMIN')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| POST | `/api/v1/factory/packing-records` | Header: required `Idempotency-Key`, body `PackingRequest` | `ProductionLogDetailDto` |
| GET | `/api/v1/factory/unpacked-batches` | — | `List<UnpackedBatchDto>` |
| GET | `/api/v1/factory/production-logs/{productionLogId}/packing-history` | — | `List<PackingRecordDto>` |
| GET | `/api/v1/factory/bulk-batches/{finishedGoodId}` | — | `List<BulkPackResponse.ChildBatchDto>` |
| GET | `/api/v1/factory/bulk-batches/{parentBatchId}/children` | — | `List<BulkPackResponse.ChildBatchDto>` |

Packing / traceability API notes:
- `POST /api/v1/factory/packing-records` accepts only `Idempotency-Key`. Do not send body `idempotencyKey`, `X-Idempotency-Key`, or `X-Request-Id`; requests without the canonical header fail closed.
- Re-submit the same `POST /api/v1/factory/packing-records` route to continue packing until the batch reaches `FULLY_PACKED`; there is no public `/complete` mutation.
- Packing history/detail payloads now surface size-variant identifiers (`sizeVariantId`, `sizeVariantLabel`) to support size-level filtering and chips in UI.

##### Packaging Setup / Rules (`/api/v1/factory/packaging-mappings`)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| GET | `/api/v1/factory/packaging-mappings` | ADMIN/FACTORY | — | `List<PackagingSizeMappingDto>` |
| GET | `/api/v1/factory/packaging-mappings/active` | ADMIN/FACTORY | — | `List<PackagingSizeMappingDto>` |
| POST | `/api/v1/factory/packaging-mappings` | ADMIN | `PackagingSizeMappingRequest` | `PackagingSizeMappingDto` |
| PUT | `/api/v1/factory/packaging-mappings/{id}` | ADMIN | `PackagingSizeMappingRequest` | `PackagingSizeMappingDto` |
| DELETE | `/api/v1/factory/packaging-mappings/{id}` | ADMIN | — | `ApiResponse<Void>` |

Frontend note: treat this surface as Packaging Setup / Rules. Packing fails closed when a size is missing active, usable packaging setup, so direct users back to Packaging Setup instead of retrying pack blindly.

##### Wastage/cost analytics endpoints

Auth for report controller endpoints: `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')`.

| Method | Path | Request | Response `data` |
|---|---|---|---|
| GET | `/api/v1/reports/wastage` | — | `List<WastageReportDto>` |
| GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | — | `CostBreakdownDto` |
| GET | `/api/v1/reports/monthly-production-costs` | Query: `year`, `month` | `MonthlyProductionCostDto` |

#### Required User Flows

1. **Item setup flow (`create brand -> create item -> confirm readiness`)**
   1. `GET /api/v1/catalog/brands?active=true` to populate the brand picker.
   2. If needed, create a new brand with `POST /api/v1/catalog/brands` and capture the returned `brandId`.
   3. `POST /api/v1/catalog/items` with `brandId`, `itemClass`, `name`, optional `color`/`size`, and pricing/tax metadata.
   4. Refresh browse/search via `GET /api/v1/catalog/items?itemClass={itemClass}&includeStock=true&includeReadiness=true&page=0&pageSize=20`.
   5. Open detail via `GET /api/v1/catalog/items/{itemId}?includeStock=true&includeReadiness=true` before production or pack setup depends on the item.
   6. Optional maintenance path: `PUT /api/v1/catalog/items/{itemId}` for single-item metadata updates after creation.
   7. If the UI groups related sellable sizes, label them as `Product Family` only; do not expose raw family/group ids as editable inputs.

2. **Production flow (`plan -> log -> pack -> stock`)**
   1. Create plan: `POST /api/v1/factory/production-plans`.
   2. Log production with consumed materials and costs: `POST /api/v1/factory/production/logs`.
   3. Record packing sessions (repeat as needed, always with `Idempotency-Key`): `POST /api/v1/factory/packing-records`.
   4. Verify stock: `GET /api/v1/finished-goods/stock-summary` + `GET /api/v1/finished-goods/{id}/batches`.

3. **Dispatch flow (`reserve -> operational preview -> canonical posting`)**
   1. Inventory reservation is created during sales order create/update flows (`POST/PUT /api/v1/sales/orders...`) via `SalesService.reserveForOrder`; there is no standalone reserve endpoint.
   2. Resolve slip: `GET /api/v1/dispatch/order/{orderId}` (or list via `/pending`).
   3. Show the operational preview modal with `GET /api/v1/dispatch/preview/{slipId}` and expect redacted pricing/accounting fields.
   4. Use the read-only dispatch workspace for slip detail and challan access (`GET /api/v1/dispatch/slip/{slipId}` plus `/challan/pdf`).
   5. Factory/admin use `POST /api/v1/dispatch/confirm` for the canonical shipment posting. Pure factory callers should expect the redacted operational response; admin/elevated callers may retain the response's amount and journal fields, but this contract does not return invoice ids.

4. **Inventory adjustment flow (finished goods)**
   1. Build adjustment payload with explicit type: `DAMAGED`, `SHRINKAGE`, `OBSOLETE`, or `RECOUNT_UP`.
   2. Send `POST /api/v1/inventory/adjustments` with a stable idempotency key (header or body).
   3. On retry/timeouts, resend the same idempotency key; backend replays or rejects mismatch.
   4. Refresh adjustment list via `GET /api/v1/inventory/adjustments`.

5. **Raw material adjustment flow (`increase/decrease recount`)**
   1. Build `RawMaterialAdjustmentRequest` with explicit `direction` (`INCREASE` or `DECREASE`) and line-level quantities/costs.
   2. Send `POST /api/v1/inventory/raw-materials/adjustments` with stable idempotency key (header/body).
   3. For `DECREASE`, expect FIFO batch consumption and per-batch movement traces; for `INCREASE`, expect an adjustment batch.
   4. Refresh stock/batches via raw-material stock/batch endpoints and show posted `journalEntryId` from response.

6. **Expiring inventory watchlist flow**
   1. Query `GET /api/v1/inventory/batches/expiring-soon?days={N}` from inventory dashboards.
   2. Backend clamps negative `days` to `0` (same-day expiries only), so UI can safely pass user-entered values after numeric validation.
   3. Group rows by `batchType` and sort/label using `daysUntilExpiry` for urgency badges.

#### State Machines

##### Production log lifecycle (`ProductionLogStatus`)

- `MIXED` (domain default/internal) -> `READY_TO_PACK` on `POST /api/v1/factory/production/logs`.
- `READY_TO_PACK` -> `PARTIAL_PACKED` when `POST /api/v1/factory/packing-records` packs only part of quantity.
- `READY_TO_PACK`/`PARTIAL_PACKED` -> `FULLY_PACKED` when `POST /api/v1/factory/packing-records` brings total packed quantity up to the mixed quantity.
- No public API transition moves `PARTIAL_PACKED -> READY_TO_PACK`; frontend should treat packing progress as monotonic until completion.
- `wastageQuantity` remains the mixed-minus-packed remainder on the production log; there is no second public completion mutation to finalize it.

##### Dispatch slip lifecycle (`PackagingSlip.status`)

Operational statuses: `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`, `BACKORDER`, `DISPATCHED`, `CANCELLED`.

- Auto reservation path: shortages -> `PENDING_PRODUCTION`; no shortages -> `RESERVED`.
- `PENDING_STOCK` is not an initial reservation state; it is used when dispatch confirmation results in zero shipped quantity while stock is still pending.
- Manual status endpoint (`PATCH /dispatch/slip/{id}/status`) only allows transitions among: `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`.
- `POST /api/v1/dispatch/confirm`:
  - if any quantity shipped -> current slip `DISPATCHED`.
  - if partial shipment -> backorder slip is created in `BACKORDER`.
  - if no shipment and shortage persists -> `PENDING_STOCK`.
- `POST /dispatch/backorder/{id}/cancel` moves `BACKORDER -> CANCELLED` and releases reservations.
- Terminal states: `DISPATCHED`, `CANCELLED`.

#### Error Codes (catalog/inventory/manufacturing)

| ErrorCode enum | Wire code | Typical trigger in this module area | Frontend behavior |
|---|---|---|---|
| `BUSINESS_INVALID_STATE` | `BUS_001` | Inactive brand used for item mutation, invalid dispatch/backorder state transition | Show non-retryable state error; reload entity state |
| `BUSINESS_DUPLICATE_ENTRY` | `BUS_002` | Duplicate brand name/item code | Inline field error + keep form open |
| `BUSINESS_ENTITY_NOT_FOUND` | `BUS_003` | Brand/item/batch/mapping not found | Show not-found toast and navigate back to list |
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | Insufficient stock/business guardrails | Block submit and surface corrective action |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Missing/invalid sizes-carton mapping, invalid quantities/status | Inline validation + do not retry automatically |
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Missing idempotency key or required payload fields | Highlight required fields/headers |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Raw material/account/reference IDs invalid | Refresh dropdown data and force re-selection |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Race during reservation/dispatch/stock update | Refetch latest slip/stock and allow one retry |
| `INTERNAL_CONCURRENCY_FAILURE` | `CONC_003` | Internal lock/retry exhaustion in packing flows | Show retry CTA with trace ID |
| `DUPLICATE_ENTITY` | `DATA_001` | Duplicate packaging-size mapping | Inline duplicate warning on mapping screen |

#### Data Contracts (DTOs)

##### Catalog DTOs

- `CatalogBrandRequest`: `name*`, `logoUrl`, `description`, `active`.
- `CatalogBrandDto`: `id`, `publicId`, `name`, `code`, `logoUrl`, `description`, `active`.
- `CatalogItemRequest`: `brandId*`, `name*`, `itemClass*`, optional `color`, optional `size`, `unitOfMeasure*`, `hsnCode*`, `gstRate* (0..100)`, optional `basePrice`, optional `minDiscountPercent`, optional `minSellingPrice`, optional `metadata`, optional `active`.
- `CatalogItemDto`: browse/search and maintenance DTO with `id`, `publicId`, optional `rawMaterialId`, `brandId`, `brandName`, `brandCode`, `name`, `code`, `itemClass`, optional `color`, optional `size`, `unitOfMeasure`, `hsnCode`, `basePrice`, `gstRate`, `minDiscountPercent`, `minSellingPrice`, `metadata`, `active`, optional `stock`, optional `readiness`.
- `CatalogItemStockDto`: `onHandQuantity`, `reservedQuantity`, `availableQuantity`, `unitOfMeasure`.
- `SkuReadinessDto`: `sku`, `masterReady`, `inventoryReady`, `productionReady`, `packingReady`, `salesReady`, `accountingReady`.
- `SkuReadinessDto.Stage`: `ready`, `blockers[]`.
- `PageResponse<CatalogItemDto>`: `content`, `totalElements`, `totalPages`, `page`, `size`.
- Product Family is explanatory vocabulary only; no public `familyId`, `groupId`, or `variantGroupId` field is required for setup screens.

##### Inventory + dispatch DTOs

- `FinishedGoodRequest`: `productCode*`, `name*`, `unit`, `costingMethod`, account IDs.
- `FinishedGoodDto`: identity + stock totals + costing/account fields.
- `FinishedGoodBatchRequest`: `finishedGoodId*`, `batchCode`, `quantity* (>0)`, `unitCost* (>=0)`, `manufacturedAt`, `expiryDate`.
- `FinishedGoodBatchDto`: identity + `batchCode`, quantities, cost, manufacture/expiry dates.
- `FinishedGoodLowStockThresholdRequest`: `threshold* (>=0)`.
- `FinishedGoodLowStockThresholdDto`: `finishedGoodId`, `productCode`, `threshold`.
- `StockSummaryDto`: shared stock rollup (`currentStock`, `reservedStock`, `availableStock`, `weightedAverageCost`, batch/material counters).
- `RawMaterialRequest`: `name*`, `sku`, `unitType*`, `reorderLevel*`, `minStock*`, `maxStock*`, `inventoryAccountId`, `costingMethod`.
- `RawMaterialDto`: identity + stock levels + status + accounting/costing metadata.
- `RawMaterialBatchRequest`: `batchCode`, `quantity*`, `unit*`, `costPerUnit*`, `supplierId*`, `notes`.
- `RawMaterialBatchDto`: identity + batch/supplier/quantity/cost fields.
- `RawMaterialIntakeRequest`: `rawMaterialId*`, `batchCode`, `quantity*`, `unit*`, `costPerUnit*`, `supplierId*`, `notes`.
- `InventoryStockSnapshot`: `name`, `sku`, `currentStock`, `reorderLevel`, `status`.
- `InventoryAdjustmentRequest`: `adjustmentDate`, `type* (DAMAGED|SHRINKAGE|OBSOLETE|RECOUNT_UP)`, `adjustmentAccountId*`, `reason`, `adminOverride`, `idempotencyKey*`, `lines*`.
- `InventoryAdjustmentRequest.LineRequest`: `finishedGoodId*`, `quantity*`, `unitCost*`, `note`.
- `InventoryAdjustmentDto`: identity + `referenceNumber`, `adjustmentDate`, `type`, `status`, `reason`, `totalAmount`, `journalEntryId`, `lines[]`.
- `InventoryAdjustmentLineDto`: `finishedGoodId`, `finishedGoodName`, `quantity`, `unitCost`, `amount`, `note`.
- `RawMaterialAdjustmentRequest`: `adjustmentDate`, `direction* (INCREASE|DECREASE)`, `adjustmentAccountId*`, `reason`, `adminOverride`, `idempotencyKey*`, `lines*`.
- `RawMaterialAdjustmentRequest.LineRequest`: `rawMaterialId*`, `quantity*`, `unitCost*`, `note`.
- `RawMaterialAdjustmentDto`: identity + `referenceNumber`, `adjustmentDate`, `status`, `reason`, `totalAmount`, `journalEntryId`, `lines[]`.
- `RawMaterialAdjustmentLineDto`: `rawMaterialId`, `rawMaterialName`, `quantity` (positive for INCREASE, negative for DECREASE), `unitCost`, `amount`, `note`.
- `InventoryExpiringBatchDto`: `batchType (RAW_MATERIAL|FINISHED_GOOD)`, `batchId`, `publicId`, `itemCode`, `itemName`, `batchCode`, `quantityAvailable`, `unitCost`, `manufacturedAt`, `expiryDate`, `daysUntilExpiry`.
- `InventoryBatchTraceabilityDto`: batch identity/type/item/source + quantity/cost + `movements[]`.
- `InventoryBatchMovementDto`: movement identity/type/qty/cost + `referenceType/referenceId` + linked journal/slip IDs.
- `OpeningStockImportResponse`: created counts + `errors[]` (`rowNumber`, `message`). Duplicate `sku` rows in a single CSV are treated as per-row validation errors and reported without aborting the rest of the file.
- `OpeningStockImportHistoryItem`: `id`, `idempotencyKey`, `referenceNumber`, `fileName`, `journalEntryId`, creation counters, `errorCount`, `createdAt`.
- `PageResponse<OpeningStockImportHistoryItem>`: paginated import history payload for `GET /api/v1/inventory/opening-stock`.
- `PackagingSlipDto`: slip identity + order/dealer + status/timestamps + journal links + `lines[]`.
- `PackagingSlipLineDto`: line batch/product/ordered/shipped/backorder/qty/cost/notes fields.
- `DispatchPreviewDto`: slip/order/dealer summary + `lines[]` with availability/suggested ship quantities; on the operational factory/admin surface, pricing/tax fields are redacted and `gstBreakdown` is `null`.
- `DispatchConfirmationRequest`: `packagingSlipId*`, `lines*`, `notes`, `confirmedBy`, `overrideRequestId`.
- `DispatchConfirmationRequest.LineConfirmation`: `lineId*`, `shippedQuantity*`, `notes`.
- `DispatchConfirmationResponse`: `packagingSlipId`, `slipNumber`, `status`, `confirmedAt`, `confirmedBy`, `totalOrderedAmount`, `totalShippedAmount`, `totalBackorderAmount`, `journalEntryId`, `cogsJournalEntryId`, `lines[]`, optional `backorderSlipId`, `transporterName`, `driverName`, `vehicleNumber`, `challanReference`, `deliveryChallanNumber`, and `deliveryChallanPdfPath`. Pure factory operational views redact the amount and journal fields by returning `null`.
- `DispatchConfirmationResponse.LineResult`: `lineId`, `productCode`, `productName`, `orderedQuantity`, `shippedQuantity`, `backorderQuantity`, `unitCost`, `lineTotal`, and `notes`. Pure factory operational views redact `unitCost` and `lineTotal`.

##### Manufacturing DTOs

- `ProductionPlanRequest`: `planNumber*`, `productName*`, `quantity*`, `plannedDate*`, `notes`.
- `ProductionPlanDto`: identity + plan/product/qty/date/status/notes.
- `ProductionLogRequest`: `brandId*`, `productId*`, `batchColour`, `batchSize*`, `unitOfMeasure`, `mixedQuantity*`, `producedAt`, `notes`, `createdBy`, `salesOrderId`, `laborCost`, `overheadCost`, `materials*`.
- `ProductionLogRequest.MaterialUsageRequest`: `rawMaterialId*`, `quantity* (>0)`, `unitOfMeasure`.
- `ProductionLogDto`: lifecycle summary with output, packed quantity, wastage, status, cost totals.
- `ProductionLogDetailDto`: `ProductionLogDto` fields + notes + `materials[]` + `packingRecords[]` + `productFamilyName` + `allowedSellableSizes[]`.
- `ProductionLogMaterialDto`: raw material batch and movement linkage + quantity/cost fields.
- `ProductionLogPackingRecordDto`: packing output linkage (`finishedGoodId/batchId`, packaging size, packed quantity, packed metadata) plus resolved size-variant fields:
  - `sizeVariantId`: FK of the explicit sellable-size target chosen for the pack line.
  - `sizeVariantLabel`: normalized size label used for UI chips/filters.
- `AllowedSellableSizeDto`: `childFinishedGoodId`, `childSkuCode`, `childFinishedGoodName`, `sizeVariantId`, `sizeLabel`, `piecesPerBox`, `litersPerUnit`, `productFamilyName`.
- `PackingRequest`: `productionLogId*`, `packedDate`, `packedBy`, `idempotencyKey`, `lines*`.
- `PackingLineRequest`: `childFinishedGoodId*`, `childBatchCount`, `packagingSize*`, `quantityLiters`, `piecesCount`, `boxesCount`, `piecesPerBox` (all positive when provided).
- `PackingRecordDto`: persisted packing record with line-level box/piece metadata + `sizeVariantId`/`sizeVariantLabel`.
- `UnpackedBatchDto`: production log quantities (`mixed`, `packed`, `remaining`) and status + `productFamilyName` + `allowedSellableSizes[]`.
- `CostBreakdownDto` (`GET /api/v1/reports/production-logs/{id}/cost-breakdown`): production cost trace payload used by manufacturing analytics pages.
  - `costComponents: CostComponentTraceDto` => `productionMaterialCost`, `laborCost`, `overheadCost`, `packagingCost`, `totalCost`, `mixedQuantity`, `packedQuantity`, `blendedUnitCost`.
  - `packedBatches: PackedBatchTraceDto[]` => packing record + FG batch references (`packingRecordId`, `finishedGoodBatchId/publicId/batchCode`, `finishedGoodCode/name`, `sizeLabel`, `packedQuantity`, `unitCost`, `totalValue`, `accountingReference`, `journalEntryId`).
  - `rawMaterialTrace: RawMaterialTraceDto[]` => raw-material movement-level traceability (`movementId`, material/batch identity, `quantity`, `unitCost`, `totalCost`, `movementType`, `referenceType`, `referenceId`, `movedAt`, `journalEntryId`).
- `PackagingSizeMappingRequest`: `packagingSize`, `rawMaterialId`, `unitsPerPack`, `cartonSize`, `litersPerUnit`.
- `PackagingSizeMappingDto`: identity + mapping, raw material descriptors, activity flag.
- `FactoryTaskRequest` / `FactoryTaskDto`: task metadata (`title`, `assignee`, `status`, due/sales/slip linkage).
- `FactoryDashboardDto`: `productionEfficiency`, `completedPlans`, `batchesLogged`, `alerts`.
- `CostAllocationRequest`: period + labor/overhead + target account IDs + notes.
- `CostAllocationResponse`: allocation totals, affected journals, summary.
- `WastageReportDto`: wastage quantity/percentage/value by production log.
- `CostBreakdownDto`: per-log material/labor/overhead/unit cost split.
- `MonthlyProductionCostDto`: monthly totals (`liters`, costs, average/loss metrics).

#### UI Hints (frontend implementation)

- **Brand dropdown**: source from `GET /api/v1/catalog/brands?active=true`; store/use `brandId` only. If the brand does not exist, create it first via `POST /api/v1/catalog/brands` and then reuse the returned `brandId`.
- **Color input**: optional single value on `CatalogItemRequest.color`; if the UI groups related sellable sizes, derive the Product Family label in the client without asking for raw family/group ids.
- **Size input**: optional single value on `CatalogItemRequest.size`; create one item per sellable size variant instead of sending matrix arrays.
- **Item readiness UX**: call `GET /api/v1/catalog/items` or `GET /api/v1/catalog/items/{itemId}` with `includeReadiness=true` and render returned blocker codes directly before production, pack, or opening-stock steps depend on the item.
- **HSN lookup**:
  - Backend currently validates/persists `hsnCode` but does not expose a dedicated HSN master endpoint.
  - Recommended UX: local searchable HSN dataset/autocomplete in UI + final backend validation on submit.
- **Item search/filter**: always use server pagination (`page`, `pageSize`); backend caps `pageSize` at 100. Use `q`, `itemClass`, `includeStock`, and `includeReadiness` for setup surfaces.
- **Catalog route guard**: surface only the canonical catalog endpoints listed in this section for brand selection/create, single-item setup, and catalog browse/detail.
- **Dispatch confirm UI**: force explicit per-line shipped quantity entry (cannot exceed ordered quantity).
- **Slip status controls**: only expose `PENDING`, `PENDING_STOCK`, `PENDING_PRODUCTION`, `RESERVED`; do not expose direct set to `DISPATCHED/BACKORDER/CANCELLED`.
- **Idempotency-sensitive screens**: send stable idempotency keys for finished-good adjustments, raw-material adjustments, opening-stock import, raw-material intake, and packing records.
- **Expiring inventory widget**: consume `/api/v1/inventory/batches/expiring-soon` and badge rows with `daysUntilExpiry` (`0` = expires today, higher numbers = less urgent).
- **Opening stock import history screen**: use `GET /api/v1/inventory/opening-stock?page={n}&size={m}` for a company-scoped audit table (newest first) and show `errorCount` as a badge linking to stored import error details.
- **Wastage dashboard**: combine `/reports/wastage` with `/reports/monthly-production-costs` for trend + variance cards.
- **Packing history screens**: prefer `sizeVariantLabel` for display badges and keep `packagingSize` as a fallback for legacy records.
- **Cost traceability UI**: for per-batch drilldown, call `/reports/production-logs/{id}/cost-breakdown` and group `rawMaterialTrace[]` by `referenceType/referenceId` to separate production consumption vs packaging consumption lines.
- **Bulk-pack retry UX**: on timeout/retry, resend the same idempotency key so backend can replay existing `BulkPackResponse` (including prior `journalEntryId`) instead of creating duplicate postings.

### Sales & Dealers

#### Endpoint Map (sales + dealer management + dispatch)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/sales/orders` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | Query: `status?`, `dealerId?`, `page=0..`, `size=1..200` | `List<SalesOrderDto>` |
| `GET` | `/api/v1/sales/orders/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | Query: `status?`, `dealerId?`, `orderNumber?`, `fromDate?`, `toDate?`, `page=0..`, `size=1..200` (dates are ISO-8601 instants) | `PageResponse<SalesOrderDto>` |
| `POST` | `/api/v1/sales/orders` | `ROLE_SALES`/`ROLE_ADMIN` | `SalesOrderRequest` (+ optional `Idempotency-Key`/`X-Idempotency-Key`) | `SalesOrderDto` |
| `PUT` | `/api/v1/sales/orders/{id}` | `ROLE_SALES`/`ROLE_ADMIN` | `SalesOrderRequest` | `SalesOrderDto` |
| `DELETE` | `/api/v1/sales/orders/{id}` | `ROLE_SALES`/`ROLE_ADMIN` | — | `204 No Content` |
| `POST` | `/api/v1/sales/orders/{id}/confirm` | `ROLE_SALES`/`ROLE_ADMIN` | — | `SalesOrderDto` |
| `POST` | `/api/v1/sales/orders/{id}/cancel` | `ROLE_SALES`/`ROLE_ADMIN` | `CancelRequest { reasonCode, reason }` (`reasonCode` required by business rule) | `SalesOrderDto` |
| `PATCH` | `/api/v1/sales/orders/{id}/status` | `ROLE_SALES`/`ROLE_ADMIN` | `StatusRequest { status }` (manual statuses only) | `SalesOrderDto` |
| `GET` | `/api/v1/sales/orders/{id}/timeline` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_FACTORY`/`ROLE_ACCOUNTING` | — | `List<SalesOrderStatusHistoryDto>` |
| `GET` | `/api/v1/dealers` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | — | `List<DealerResponse>` |
| `POST` | `/api/v1/dealers` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | `CreateDealerRequest` | `DealerResponse` |
| `PUT` | `/api/v1/dealers/{dealerId}` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | `CreateDealerRequest` | `DealerResponse` |
| `GET` | `/api/v1/dealers/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `query?`, `status?`, `region?`, `creditStatus?` | `List<DealerLookupResponse>` |
| `GET` | `/api/v1/sales/dealers/search` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `query?`, `status?`, `region?`, `creditStatus?` | `List<DealerLookupResponse>` |
| `GET` | `/api/v1/portal/finance/ledger` | `ROLE_ADMIN`/`ROLE_ACCOUNTING` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `GET` | `/api/v1/portal/finance/invoices` | `ROLE_ADMIN`/`ROLE_ACCOUNTING` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `GET` | `/api/v1/portal/finance/aging` | `ROLE_ADMIN`/`ROLE_ACCOUNTING` | Query: `dealerId` | `ApiResponse<Map<String,Object>>` |
| `POST` | `/api/v1/dealers/{dealerId}/dunning/hold` | `ROLE_ADMIN`/`ROLE_SALES`/`ROLE_ACCOUNTING` | Query: `overdueDays` (default `45`), `minAmount` (default `0`) | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/dashboard` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/ledger` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/invoices` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/aging` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `GET` | `/api/v1/dealer-portal/orders` | `ROLE_DEALER` | — | `Map<String,Object>` |
| `POST` | `/api/v1/dealer-portal/credit-limit-requests` | `ROLE_DEALER` | `DealerPortalCreditLimitRequestCreateRequest` | `CreditLimitRequestDto` for a new pending permanent credit-limit request scoped to the authenticated dealer |
| `GET` | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | `ROLE_DEALER` | — | `application/pdf` |
| `GET` | `/api/v1/dispatch/preview/{slipId}` | `ROLE_ADMIN`/`ROLE_FACTORY` | — | `DispatchPreviewDto` |
| `GET` | `/api/v1/dispatch/slip/{slipId}/challan/pdf` | `ROLE_ADMIN`/`ROLE_FACTORY` | — | `application/pdf` |
| `POST` | `/api/v1/dispatch/confirm` | `ROLE_ADMIN`/`ROLE_FACTORY` + `dispatch.confirm` | `DispatchConfirmationRequest` | `DispatchConfirmationResponse` |
| `POST` | `/api/v1/sales/dispatch/reconcile-order-markers` | `ROLE_SALES`/`ROLE_ADMIN` + `dispatch.confirm` | Query: `limit?` (default `200`) | `DispatchMarkerReconciliationResponse` |

#### Portal boundary notes (2026-03-08)

- `/api/v1/dispatch/**` is now the canonical dispatch workspace for factory/operator lookup, preview, slip detail, order lookup, challan download, and the single public dispatch-confirm write.
- `/api/v1/dispatch/confirm` is the only surviving dispatch-confirm write surface. Factory/admin own the action; pure factory callers receive the redacted operational view, while admin/elevated callers retain only the DTO's permitted amount and journal fields.
- Historical orchestrator dispatch shortcuts are retired from the active contract. Frontend shipment posting must use `/api/v1/dispatch/confirm` only.
- `/api/v1/orchestrator/orders/{orderId}/fulfillment` still handles non-dispatch workflow states like `PROCESSING`, but dispatch-like target states (`SHIPPED`, `DISPATCHED`, `FULFILLED`, `COMPLETED`) now fail closed with `BUS_001` and instruct callers to use `/api/v1/dispatch/confirm`.
- Credit override requests can still be created by sales/factory/admin on `/api/v1/credit/override-requests`, but approve/reject review is now limited to admin/accounting.
- Dealer portal routes remain dealer-scoped for reads, but dealers can now submit permanent credit-limit requests on `/api/v1/dealer-portal/credit-limit-requests`. Do not surface dispatch-override actions in the dealer portal.
- Dealer invoice PDF export stays dealer-scoped and audited; cross-dealer invoice-id guessing returns `404`, and token/header company mismatches return `403`.
- Super admin is platform-only in tenant-facing UX: do not route `ROLE_SUPER_ADMIN` users into tenant portal dashboards or tenant workflow execution screens.

#### User Flows

1. **Create + reserve order**
   1. `POST /api/v1/sales/orders` with line items and totals.
   2. Backend creates order in `DRAFT`, attempts reservation, then transitions to:
      - `RESERVED` when reservation has no shortages, or
      - `PENDING_PRODUCTION` when shortages exist.
   3. UI should refresh with `GET /api/v1/sales/orders/search` and show resulting status.

2. **Confirm order (credit + stock checks)**
   1. `POST /api/v1/sales/orders/{id}/confirm`.
   2. Backend enforces credit limit and requires at least partial reserved stock.
   3. Success transitions to `CONFIRMED`; timeline records reason code `ORDER_CONFIRMED`.

3. **Dealer onboarding + credit visibility**
   1. Create/update dealer using `POST /api/v1/dealers` or `PUT /api/v1/dealers/{dealerId}` with GST + payment terms + region fields.
   2. Search from sales screen with `GET /api/v1/sales/dealers/search?query=&status=&region=&creditStatus=`.
   3. For dealer risk cards load `GET /api/v1/portal/finance/ledger?dealerId=` and `GET /api/v1/portal/finance/aging?dealerId=`.
   4. Trigger manual hold guardrail using `POST /api/v1/dealers/{dealerId}/dunning/hold?overdueDays=45&minAmount=0`.

4. **Dealer portal self-service (dealer-authenticated)**
   1. Load summary from `GET /api/v1/dealer-portal/dashboard` (includes `creditStatus`, `pendingOrderExposure`, aging buckets).
   2. Load detailed ledgers/invoices/orders from `/ledger`, `/invoices`, `/orders`.
   3. Load overdue details from `GET /api/v1/dealer-portal/aging`.
   4. Expose a dealer CTA only for permanent credit-limit requests. Keep dispatch overrides and other tenant-internal workflow actions out of the dealer portal. Dealers can still download invoice PDFs via `/invoices/{invoiceId}/pdf`.

5. **Dispatch reserve -> operational preview -> canonical confirm**
   1. Reserve inventory during order creation/confirmation.
   2. Open modal with `GET /api/v1/dispatch/preview/{slipId}` and render operational shipment context only; do not expect price totals or GST breakdown on this factory/admin preview.
   3. Factory/admin use the read-only dispatch workspace for slip lookup, preview, challan download, and operator context.
   4. Factory/admin complete shipment posting with `POST /api/v1/dispatch/confirm`, including transporter/driver, vehicle number, challan reference, and the role-appropriate dispatch result payload.
   5. Keep sales and accounting users away from `POST /api/v1/dispatch/confirm`, and surface backend deny text verbatim if a stale or unauthorized route is hit.

6. **Cancel order with reason code**
   1. UI collects structured reason code + optional free-text reason.
   2. `POST /api/v1/sales/orders/{id}/cancel` with `{ reasonCode, reason }`.
   3. Backend allows cancellation only from `DRAFT`/`CONFIRMED` and records timeline entry with supplied reason code.

7. **Track lifecycle timeline**
   1. `GET /api/v1/sales/orders/{id}/timeline`.
   2. Render chronological transition history (`fromStatus`, `toStatus`, `reasonCode`, `reason`, `changedBy`, `changedAt`).

8. **Search/filter orders**
   1. `GET /api/v1/sales/orders/search` with any combination of `status`, `dealerId`, `orderNumber`, date range, page/size.
   2. Use `PageResponse.totalElements/totalPages/page/size` for pagination controls.

#### Sales Order State Machine

Canonical order lifecycle exposed to frontend:

- `DRAFT` -> `RESERVED` (auto, stock fully reserved on create)
- `DRAFT` -> `PENDING_PRODUCTION` (auto, shortage on create)
- `DRAFT`/`RESERVED`/`PENDING_PRODUCTION`/`PENDING_INVENTORY`/`READY_TO_SHIP`/`PROCESSING` -> `CONFIRMED` (confirm endpoint)
- `CONFIRMED`/`PROCESSING`/`RESERVED`/`PENDING_PRODUCTION`/`PENDING_INVENTORY`/`READY_TO_SHIP` -> `DISPATCHED` (dispatch progression)
- `DISPATCHED` -> `INVOICED` (invoice marker present after dispatch)
- `INVOICED` -> `SETTLED` -> `CLOSED` (downstream finance lifecycle)
- `DRAFT`/`CONFIRMED` -> `CANCELLED` (cancel endpoint, reason code required)

Legacy compatibility mapping still accepted in responses/queries:
- `BOOKED` => `DRAFT`
- `SHIPPED`/`FULFILLED` => `DISPATCHED`
- `COMPLETED` => `SETTLED`
- `PENDING` => `DRAFT`
- `APPROVED` => `CONFIRMED`

#### Error Codes (sales + dealer + dispatch relevant)

- `VAL_001` (`VALIDATION_INVALID_INPUT`)
  - Invalid search date format, unknown/unsupported status inputs, invalid manual transition requests.
  - Invalid dealer credit filter (`creditStatus` must be one of `WITHIN_LIMIT | NEAR_LIMIT | OVER_LIMIT`).
  - Invalid GST/state validation (`gstNumber` not GSTIN-compliant, `stateCode` not 2-char code).
- `VAL_002` (`VALIDATION_MISSING_REQUIRED_FIELD`)
  - Missing cancellation reason code for cancel request.
- `BUS_001` (`BUSINESS_INVALID_STATE`)
  - Invalid transition (e.g., cancel from dispatched/invoiced states, illegal lifecycle jumps).
- `VAL_007` (`VALIDATION_INVALID_STATE`)
  - Operation blocked due to immutable/posting-locked order state.
  - Dispatch preview requested for an already dispatched slip.
- `VAL_003` (`VALIDATION_INVALID_REFERENCE`)
  - Dealer/sales-order/slip linkage missing for credit or dispatch operations.

Frontend behavior: treat these as non-retryable user/action-state errors; surface message inline and refresh entity state.

#### Data Contracts

- `CreateDealerRequest`
  - `name: string` (required)
  - `companyName: string` (required)
  - `contactEmail: string` (required, valid email)
  - `contactPhone: string` (required)
  - `address?: string`
  - `creditLimit?: decimal` (>=0)
  - `gstNumber?: string` (15-char GSTIN format)
  - `stateCode?: string` (2-char state code)
  - `gstRegistrationType?: REGULAR | COMPOSITION | UNREGISTERED` (default `UNREGISTERED`)
  - `paymentTerms?: NET_30 | NET_60 | NET_90` (default `NET_30`)
  - `region?: string` (normalized uppercase)

- `DealerResponse`
  - Existing dealer identity/contact/balance fields plus:
  - `gstNumber?: string`
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `region?: string`

- `DealerLookupResponse`
  - Existing lightweight dealer lookup fields plus:
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `region?: string`
  - `creditStatus: WITHIN_LIMIT | NEAR_LIMIT | OVER_LIMIT`

- `Portal finance ledger payload` (`GET /api/v1/portal/finance/ledger?dealerId=` and `/api/v1/dealer-portal/ledger`)
  - `dealerId`, `dealerName`
  - `currentBalance`
  - `entries[]` (`date`, `reference`, `memo`, `debit`, `credit`, `runningBalance`)

- `Portal finance invoices payload` (`GET /api/v1/portal/finance/invoices?dealerId=` and `/api/v1/dealer-portal/invoices`)
  - `dealerId`, `dealerName`
  - `totalOutstanding`, `invoiceCount`
  - `invoices[]` (`id`, `invoiceNumber`, `issueDate`, `dueDate`, `totalAmount`, `outstandingAmount`, `status`, `currency`)

- `Portal finance aging payload` (`GET /api/v1/portal/finance/aging?dealerId=` and `/api/v1/dealer-portal/aging`)
  - `dealerId`, `dealerName`
  - `creditLimit`, `pendingOrderCount`, `pendingOrderExposure`, `creditUsed`, `availableCredit`
  - `totalOutstanding`
  - `agingBuckets` (`current`, `1-30 days`, `31-60 days`, `61-90 days`, `90+ days`)
  - `overdueInvoices[]`

- `DispatchPreviewDto`
  - Existing slip/order summary + `lines[]`
  - On the operational `/api/v1/dispatch/preview/{slipId}` surface, `unitPrice`, `lineSubtotal`, `lineTax`, and `lineTotal` are redacted and `gstBreakdown` is `null`

- `PackagingSlipDto` (`GET /api/v1/dispatch/slip/{slipId}`)
  - Operational read response includes shipment/challan metadata, `deliveryChallanNumber`, and `deliveryChallanPdfPath`
  - `journalEntryId`, `cogsJournalEntryId`, and pricing/accounting fields remain intentionally redacted on this surface

- `DispatchConfirmationResponse` (`POST /api/v1/dispatch/confirm`)
  - Fields present in the checked-in DTO/OpenAPI schema are: `packagingSlipId`, `slipNumber`, `status`, `confirmedAt`, `confirmedBy`, `totalOrderedAmount`, `totalShippedAmount`, `totalBackorderAmount`, `journalEntryId`, `cogsJournalEntryId`, `lines[]`, optional `backorderSlipId`, `transporterName`, `driverName`, `vehicleNumber`, `challanReference`, `deliveryChallanNumber`, and `deliveryChallanPdfPath`
  - `lines[]` entries carry `lineId`, `productCode`, `productName`, `orderedQuantity`, `shippedQuantity`, `backorderQuantity`, `unitCost`, `lineTotal`, and `notes`
  - Do not expect `salesOrderId`, `finalInvoiceId`, `arJournalEntryId`, or other shadow invoice-link fields on this response

- `SalesOrderSearchFilters` (query-model used by backend)
  - `status?: string` (canonicalized on backend)
  - `dealerId?: number`
  - `orderNumber?: string` (contains search)
  - `fromDate?: string` (ISO-8601 instant)
  - `toDate?: string` (ISO-8601 instant)
  - `page: number` (>=0)
  - `size: number` (1..200)

- `SalesOrderDto`
  - `id: number`
  - `publicId: uuid`
  - `orderNumber: string`
  - `status: string` (canonical lifecycle state)
  - `totalAmount: decimal`
  - `subtotalAmount: decimal`
  - `gstTotal: decimal`
  - `gstRate: decimal`
  - `gstTreatment: string`
  - `gstInclusive: boolean`
  - `gstRoundingAdjustment: decimal`
  - `currency: string`
  - `dealerName?: string`
  - `traceId?: string`
  - `createdAt: instant`
  - `items: SalesOrderItemDto[]`
  - `timeline: SalesOrderStatusHistoryDto[]` (currently empty on list/detail payloads; use timeline endpoint for canonical history)

- `SalesOrderStatusHistoryDto`
  - `id: number`
  - `fromStatus?: string`
  - `toStatus: string`
  - `reasonCode?: string`
  - `reason?: string`
  - `changedBy: string`
  - `changedAt: instant`

- `CancelRequest`
  - `reasonCode?: string` (**required by business logic**)
  - `reason?: string`

#### UI Hints

- Use a dedicated cancel-reason-code dropdown (e.g., `CUSTOMER_REQUEST`, `CREDIT_BLOCK`, `PRICING_ISSUE`, etc.) + optional free-text details.
- Always call `/sales/orders/{id}/timeline` when opening an order detail drawer/page; do not rely on `SalesOrderDto.timeline` from list API.
- For search date filters, submit UTC ISO instants (`2026-01-01T00:00:00Z` format) to avoid timezone ambiguity.
- Treat `RESERVED`, `PENDING_PRODUCTION`, `PENDING_INVENTORY`, `READY_TO_SHIP`, and `PROCESSING` as in-progress operational states in UI badges.
- Dealer forms must include payment terms + region dropdown/input and normalize state code/GST client-side before submit for better UX.
- Dealer search table should expose independent filters: `status`, `region`, and `creditStatus`; do not derive `creditStatus` client-side.
- Dealer portal dashboard should highlight `creditStatus` using thresholds from backend response and show `pendingOrderExposure` alongside outstanding dues.
- Render only the permanent credit-limit request CTA in the dealer portal. Do not render dispatch-override or other tenant-internal workflow CTAs there.
- Dispatch confirmation modal for the factory/admin operational surface should not expect price/tax cards from preview; use an admin/elevated session on the same canonical route when finance-linked fields are required after confirm.

#### GST Fields

- Dealer create/update payloads support:
  - `gstNumber` (15-char GSTIN, optional)
  - `stateCode` (2-char Indian state code, optional)
  - `gstRegistrationType` (`REGULAR | COMPOSITION | UNREGISTERED`, optional; defaults to `UNREGISTERED`)

#### Sales Invoice GST Component Exposure

- Invoice line DTO now includes component fields:
  - `cgstAmount`
  - `sgstAmount`
  - `igstAmount`

These values are populated during dispatch confirmation and returned in invoice APIs.

### Purchasing & Suppliers

Comprehensive frontend handoff for `VAL-DOC-006` (supplier management, purchase orders, GRN lifecycle, purchase invoices, and purchase returns).

> Envelope convention: endpoints return `ApiResponse<T>` with payload under `data`.

#### Endpoint Map

##### Supplier endpoints (CRUD + approval + list/search)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/suppliers` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` or `ROLE_FACTORY` | none | `List<SupplierResponse>` |
| `GET` | `/api/v1/suppliers/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` or `ROLE_FACTORY` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `SupplierRequest` | `SupplierResponse` |
| `PUT` | `/api/v1/suppliers/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` + `SupplierRequest` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/approve` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/activate` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |
| `POST` | `/api/v1/suppliers/{id}/suspend` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `SupplierResponse` |

2026-03-08 lifecycle/provisioning note:
- `POST /api/v1/suppliers` now provisions the linked payable account before the supplier response returns; treat `payableAccountId` / `payableAccountCode` as immediately available onboarding output.
- `GET /api/v1/suppliers` and `GET /api/v1/suppliers/{id}` continue to return non-active suppliers for reference workflows; the frontend should not hide `PENDING`, `APPROVED`, or `SUSPENDED` rows.

Search behavior today:
- Server-side search query params are not exposed on supplier endpoints.
- Frontend search should call `GET /api/v1/suppliers` and filter client-side by `code`, `name`, `status`, GST fields, etc.

##### Purchase order endpoints (current API coverage)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/purchase-orders?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<PurchaseOrderResponse>` |
| `GET` | `/api/v1/purchasing/purchase-orders/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PurchaseOrderRequest` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/approve` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/void` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` + `PurchaseOrderVoidRequest` | `PurchaseOrderResponse` |
| `POST` | `/api/v1/purchasing/purchase-orders/{id}/close` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `PurchaseOrderResponse` |
| `GET` | `/api/v1/purchasing/purchase-orders/{id}/timeline` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `List<PurchaseOrderStatusHistoryResponse>` |

Notes:
- PO lifecycle is now explicit: creation writes `DRAFT` only.
- Approval, void, and close are explicit transition endpoints.
- `POST /void` requires a reason code payload.

##### Goods receipt (GRN) endpoints

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/goods-receipts?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<GoodsReceiptResponse>` |
| `GET` | `/api/v1/purchasing/goods-receipts/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `GoodsReceiptResponse` |
| `POST` | `/api/v1/purchasing/goods-receipts` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `GoodsReceiptRequest` + `Idempotency-Key` header (or body key) | `GoodsReceiptResponse` |

Idempotency contract for GRN creation:
- Canonical header: `Idempotency-Key`
- Legacy header `X-Idempotency-Key` is explicitly rejected
- If both header/body keys exist, they must match

##### Purchase invoice + return endpoints (needed for full P2P and return flow)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `GET` | `/api/v1/purchasing/raw-material-purchases?supplierId={id?}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | optional query `supplierId` | `List<RawMaterialPurchaseResponse>` |
| `GET` | `/api/v1/purchasing/raw-material-purchases/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | path `id` | `RawMaterialPurchaseResponse` |
| `POST` | `/api/v1/purchasing/raw-material-purchases` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `RawMaterialPurchaseRequest` | `RawMaterialPurchaseResponse` |
| `POST` | `/api/v1/purchasing/raw-material-purchases/returns` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PurchaseReturnRequest` | `JournalEntryDto` |

#### User Flows (Frontend API sequences)

1. **Supplier onboarding flow**
   1. `POST /api/v1/suppliers` with supplier master + optional GST/bank/payment data
   2. Use returned `payableAccountId` / `payableAccountCode` immediately for finance-facing confirmation UI
   3. `POST /api/v1/suppliers/{id}/approve` (`PENDING -> APPROVED`)
   4. `POST /api/v1/suppliers/{id}/activate` (`APPROVED -> ACTIVE`)
   5. Refresh list/detail: `GET /api/v1/suppliers` or `GET /api/v1/suppliers/{id}`

2. **Create PO flow (select supplier -> add items -> approve)**
   1. Load suppliers: `GET /api/v1/suppliers`
   2. Enforce active-only supplier selection in UI, but keep non-active suppliers visible as reference-only rows/badges
   3. Build PO lines (raw material, qty, unit, cost)
   4. Submit draft PO: `POST /api/v1/purchasing/purchase-orders`
   5. Backend persists PO in `DRAFT`
   6. Approve explicitly: `POST /api/v1/purchasing/purchase-orders/{id}/approve` (`DRAFT -> APPROVED`)
   7. Read back: `GET /api/v1/purchasing/purchase-orders/{id}`
   8. Optional timeline render: `GET /api/v1/purchasing/purchase-orders/{id}/timeline`

3. **Receive goods flow (GRN)**
   1. Load PO: `GET /api/v1/purchasing/purchase-orders/{id}`
   2. Ensure PO is `APPROVED` and supplier is still `ACTIVE` before showing GRN submit action
   3. Determine remaining per line (ordered - already received from prior GRNs)
   4. Submit GRN: `POST /api/v1/purchasing/goods-receipts` with `Idempotency-Key`
   5. Refresh GRN list/detail: `GET /api/v1/purchasing/goods-receipts` / `{id}`
   6. Observe PO status auto-transition to `PARTIALLY_RECEIVED` or `FULLY_RECEIVED`
   7. Optionally render PO timeline to explain transition reason codes (`GOODS_RECEIPT_PARTIAL`, `GOODS_RECEIPT_COMPLETED`)

4. **Post purchase invoice flow (required before final close)**
   1. Select supplier + GRN to invoice; fail closed if supplier status is no longer `ACTIVE`
   2. Submit: `POST /api/v1/purchasing/raw-material-purchases`
   3. Backend links GRN + PO + journal entry and sets GRN status to `INVOICED`
   4. PO becomes:
      - `INVOICED` when some GRNs remain uninvoiced
      - `CLOSED` automatically when all GRNs under the PO are invoiced (via internal `INVOICED -> CLOSED` transition)
   5. Manual close endpoint `POST /api/v1/purchasing/purchase-orders/{id}/close` is available for the canonical `INVOICED -> CLOSED` transition and rejects non-`INVOICED` states

5. **Process return flow**
   1. Load purchases to pick return candidate: `GET /api/v1/purchasing/raw-material-purchases?supplierId={id}`
   2. Submit return: `POST /api/v1/purchasing/raw-material-purchases/returns` (backend now rejects non-active suppliers with explicit reference-only blocker text)
   3. Backend validates returnable qty + outstanding payable, creates corrective journal, and reverses inventory movement
   4. Refresh purchase to show updated `outstandingAmount` / status: `GET /api/v1/purchasing/raw-material-purchases/{id}`

#### State Machines

##### Supplier lifecycle

- `PENDING -> APPROVED` via `POST /api/v1/suppliers/{id}/approve`
- `APPROVED -> ACTIVE` via `POST /api/v1/suppliers/{id}/activate`
- `ACTIVE -> SUSPENDED` via `POST /api/v1/suppliers/{id}/suspend`
- `SUSPENDED -> ACTIVE` via `POST /api/v1/suppliers/{id}/activate`

Guards:
- Approve allowed only from `PENDING`
- Suspend allowed only from `ACTIVE`
- Activate allowed only from `APPROVED` or `SUSPENDED`
- Non-active supplier records remain visible for lookup/reference, but purchase-order creation, goods-receipt progression, purchase invoice posting, purchase return posting, and supplier settlement posting now all fail closed until the supplier returns to `ACTIVE`.

##### Purchase order lifecycle

Persisted status enum: `DRAFT`, `APPROVED`, `PARTIALLY_RECEIVED`, `FULLY_RECEIVED`, `INVOICED`, `CLOSED`, `VOID`

Canonical transition graph (enforced server-side):
- `DRAFT -> APPROVED` via `POST /api/v1/purchasing/purchase-orders/{id}/approve`
- `DRAFT -> VOID` via `POST /api/v1/purchasing/purchase-orders/{id}/void`
- `APPROVED -> PARTIALLY_RECEIVED` automatically on partial GRN
- `APPROVED -> FULLY_RECEIVED` automatically when first GRN fully satisfies PO quantity
- `APPROVED -> VOID` via `POST /api/v1/purchasing/purchase-orders/{id}/void`
- `PARTIALLY_RECEIVED -> FULLY_RECEIVED` automatically when cumulative GRNs satisfy PO quantity
- `FULLY_RECEIVED -> INVOICED` automatically when invoice posting begins
- `INVOICED -> CLOSED` automatically when all GRNs are invoiced, or explicitly via `POST /api/v1/purchasing/purchase-orders/{id}/close`

Rejected transitions (non-exhaustive):
- `DRAFT -> PARTIALLY_RECEIVED/FULLY_RECEIVED/INVOICED/CLOSED`
- `APPROVED -> INVOICED/CLOSED`
- `PARTIALLY_RECEIVED -> VOID`
- Any transition from `VOID` or `CLOSED`
- No-op transitions (same state to same state)

History/timeline:
- Every status change is persisted in `purchase_order_status_history`.
- Query via `GET /api/v1/purchasing/purchase-orders/{id}/timeline`.
- Timeline fields: `fromStatus`, `toStatus`, `reasonCode`, `reason`, `changedBy`, `changedAt`.

##### Goods receipt lifecycle

Persisted status enum: `PARTIAL`, `RECEIVED`, `INVOICED`

Transitions:
- On GRN create:
  - `PARTIAL` when any PO line remains pending
  - `RECEIVED` when GRN completes all remaining PO quantities
- `PARTIAL/RECEIVED -> INVOICED` when GRN is linked to posted purchase invoice

#### Error Codes (Purchasing/Supplier) + Frontend Handling

| ErrorCode enum | Wire code | Typical purchasing trigger | Suggested frontend behavior |
|---|---|---|---|
| `VALIDATION_MISSING_REQUIRED_FIELD` | `VAL_002` | Missing GRN idempotency key, missing receipt date/request fields | Block submit, show inline field validation, keep form editable |
| `VALIDATION_INVALID_INPUT` | `VAL_001` | Duplicate lines, quantity/unit mismatch, over-receipt, invalid GST/tax contract, unsupported legacy JSON aliases | Highlight offending rows/fields using error details (`rawMaterialId`, quantities, units, alias names) |
| `VALIDATION_INVALID_REFERENCE` | `VAL_006` | Supplier/PO/GRN linkage mismatch or missing referenced entity | Refresh dependent selectors and force reselection |
| `BUSINESS_INVALID_STATE` | `BUS_001` | Creating PO for non-`ACTIVE` supplier, invalid supplier transition, invalid PO lifecycle transition (including no-op) | Disable invalid action buttons based on current status and refresh timeline |
| `BUSINESS_CONSTRAINT_VIOLATION` | `BUS_004` | PO non-receivable (`CLOSED`/`VOID`), already-invoiced GRN, duplicate lock/linkage rules | Show non-retryable toast/banner and reload latest entity state |
| `CONCURRENCY_CONFLICT` | `CONC_001` | Idempotency key reused with different payload; duplicate invoice/GRN linking race | Show stale/conflict dialog and ask user to refresh before retry |
| `RETURN_EXCEEDS_OUTSTANDING` | `BUS_009` | Return amount would drop purchase outstanding below zero | Keep return form open and display max returnable/outstanding guidance |

Reference-only supplier blocker message contract:
- `PENDING`: backend message contains `pending approval` + `reference only`
- `APPROVED`: backend message contains `approved but not yet active` + `reference only`
- `SUSPENDED`: backend message contains `suspended` + `reference only`
- Frontend should surface the backend message verbatim and refresh supplier status before allowing retry.

#### Data Contracts (DTOs)

##### Supplier DTOs

- **`SupplierRequest`**
  - `name: string` *(required, max 64)*
  - `code?: string` *(max 64; auto-generated from name if missing)*
  - `contactEmail?: email`
  - `contactPhone?: string` *(max 32)*
  - `address?: string` *(max 512)*
  - `creditLimit?: decimal` *(>= 0)*
  - `gstNumber?: string` *(GSTIN pattern: 15 chars, `^[0-9]{2}[A-Za-z0-9]{13}$`)*
  - `stateCode?: string` *(2 chars)*
  - `gstRegistrationType?: REGULAR | COMPOSITION | UNREGISTERED` *(defaults to `UNREGISTERED`)*
  - `paymentTerms?: NET_30 | NET_60 | NET_90` *(defaults to `NET_30`)*
  - `bankAccountName?: string` *(max 128)*
  - `bankAccountNumber?: string` *(max 64)*
  - `bankIfsc?: string` *(max 32)*
  - `bankBranch?: string` *(max 128)*

- **`SupplierResponse`**
  - `id: number`
  - `publicId: uuid`
  - `code: string`
  - `name: string`
  - `status: PENDING | APPROVED | ACTIVE | SUSPENDED`
  - `email?: string`
  - `phone?: string`
  - `address?: string`
  - `creditLimit: decimal`
  - `balance: decimal` *(ledger-derived read-model value)*
  - `payableAccountId?: number`
  - `payableAccountCode?: string`
  - `gstNumber?: string`
  - `stateCode?: string`
  - `gstRegistrationType: REGULAR | COMPOSITION | UNREGISTERED`
  - `paymentTerms: NET_30 | NET_60 | NET_90`
  - `bankAccountName?: string` *(decrypted for response)*
  - `bankAccountNumber?: string` *(decrypted for response)*
  - `bankIfsc?: string` *(decrypted for response)*
  - `bankBranch?: string` *(decrypted for response)*

##### Purchase order DTOs

- **`PurchaseOrderRequest`**
  - `supplierId: number` *(required)*
  - `orderNumber: string` *(required, non-blank)*
  - `orderDate: date` *(required)*
  - `memo?: string`
  - `lines: PurchaseOrderLineRequest[]` *(required, non-empty)*

- **`PurchaseOrderLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `quantity: decimal` *(required, > 0)*
  - `unit?: string`
  - `costPerUnit: decimal` *(required, > 0)*
  - `notes?: string`

- **`PurchaseOrderVoidRequest`**
  - `reasonCode: string` *(required, non-blank)*
  - `reason?: string`

- **`PurchaseOrderResponse`**
  - `id, publicId, orderNumber, orderDate`
  - `totalAmount: decimal`
  - `status: DRAFT | APPROVED | PARTIALLY_RECEIVED | FULLY_RECEIVED | INVOICED | CLOSED | VOID`
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `createdAt: instant`
  - `lines: PurchaseOrderLineResponse[]`

- **`PurchaseOrderLineResponse`**
  - `rawMaterialId, rawMaterialName, quantity, unit, costPerUnit, lineTotal, notes`

- **`PurchaseOrderStatusHistoryResponse`**
  - `id: number`
  - `fromStatus?: string`
  - `toStatus: string`
  - `reasonCode?: string`
  - `reason?: string`
  - `changedBy: string`
  - `changedAt: instant`

##### Goods receipt DTOs

- **`GoodsReceiptRequest`**
  - `purchaseOrderId: number` *(required)*
  - `receiptNumber: string` *(required, non-blank)*
  - `receiptDate: date` *(required)*
  - `memo?: string`
  - `idempotencyKey?: string` *(can be supplied in body; header is canonical)*
  - `lines: GoodsReceiptLineRequest[]` *(required, non-empty)*

- **`GoodsReceiptLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `batchCode?: string`
  - `quantity: decimal` *(required, > 0)*
  - `unit?: string`
  - `costPerUnit: decimal` *(required, > 0)*
  - `manufacturingDate?: date`
  - `expiryDate?: date`
  - `notes?: string`

- **`GoodsReceiptResponse`**
  - `id, publicId, receiptNumber, receiptDate`
  - `totalAmount: decimal`
  - `status: PARTIAL | RECEIVED | INVOICED`
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `purchaseOrderId, purchaseOrderNumber`
  - `createdAt: instant`
  - `lines: GoodsReceiptLineResponse[]`

- **`GoodsReceiptLineResponse`**
  - `rawMaterialId, rawMaterialName, batchCode, quantity, unit, costPerUnit, lineTotal, notes`

##### Purchase invoice + return DTOs

- **`RawMaterialPurchaseRequest`**
  - `supplierId: number` *(required)*
  - `invoiceNumber: string` *(required)*
  - `invoiceDate: date` *(required)*
  - `memo?: string`
  - `purchaseOrderId?: number`
  - `goodsReceiptId: number` *(required)*
  - `taxAmount?: decimal` *(>= 0; mutually exclusive with line-level tax declarations)*
  - `lines: RawMaterialPurchaseLineRequest[]` *(required, non-empty)*

  Canonical JSON keys only:
  - Supported: `invoiceNumber`, `goodsReceiptId`
  - Explicitly rejected legacy aliases: `invoiceNo`, `invoice_no`, `goodsReceiptID`, `goods_receipt_id`, `goodsReceipt`, `grnId`

- **`RawMaterialPurchaseLineRequest`**
  - `rawMaterialId: number` *(required)*
  - `batchCode?: string`
  - `quantity: decimal` *(required, > 0 and must match GRN qty for same material)*
  - `unit?: string` *(must match GRN unit if GRN line exists)*
  - `costPerUnit: decimal` *(required, > 0 and must match GRN cost within tolerance)*
  - `taxRate?: decimal`
  - `taxInclusive?: boolean`
  - `notes?: string`

- **`RawMaterialPurchaseResponse`**
  - `id, publicId, invoiceNumber, invoiceDate`
  - `totalAmount, taxAmount, outstandingAmount`
  - `status` *(runtime values include `POSTED`, `PARTIAL`, `PAID`, `VOID`, `REVERSED` depending on settlement/returns)*
  - `memo?: string`
  - `supplierId, supplierCode, supplierName`
  - `purchaseOrderId, purchaseOrderNumber`
  - `goodsReceiptId, goodsReceiptNumber`
  - `journalEntryId?: number`
  - `createdAt: instant`
  - `lines: RawMaterialPurchaseLineResponse[]`

- **`RawMaterialPurchaseLineResponse`**
  - `rawMaterialId, rawMaterialName`
  - `rawMaterialBatchId?, batchCode?`
  - `quantity, unit, costPerUnit, lineTotal`
  - `taxRate?, taxAmount?`
  - `cgstAmount?, sgstAmount?, igstAmount?`
  - `notes?`

- **`PurchaseReturnRequest`**
  - `supplierId: number` *(required)*
  - `purchaseId: number` *(required)*
  - `rawMaterialId: number` *(required)*
  - `quantity: decimal` *(required, > 0)*
  - `unitCost: decimal` *(required, > 0)*
  - `referenceNumber?: string` *(optional idempotent reference for replay-safe requests)*
  - `returnDate?: date` *(defaults to company date if omitted)*
  - `reason?: string`

Return response:
- `POST /raw-material-purchases/returns` returns `JournalEntryDto` (see Accounting section for full schema).

#### UI Hints

- **Supplier onboarding UI**
  - Use staged actions: `Create -> Approve -> Activate`.
  - Disable invalid transition buttons based on current `status`.
  - Show payable account code from response so finance team can verify ledger linkage.
  - Treat `PENDING`, `APPROVED`, and `SUSPENDED` as reference-only states: visible in lookup UI, but no create/progress/post CTA should remain enabled.

- **Supplier search/list UI**
  - Since backend has no dedicated query endpoint, implement client-side filtering on top of `GET /suppliers`.
  - Recommended quick filters: `status`, `paymentTerms`, `gstRegistrationType`, and text match over `code/name`.

- **PO creation UI**
  - Allow PO creation only for `ACTIVE` suppliers.
  - Use line-level validations before submit (positive qty/cost, no duplicate raw material lines).
  - New POs are `DRAFT`; show explicit **Approve** action wired to `POST /purchase-orders/{id}/approve`.
  - Show **Void** action only for `DRAFT` and `APPROVED`; require reason-code selection before submit.
  - Show **Close** action only when PO is `INVOICED`.
  - Render timeline drawer/tab from `GET /purchase-orders/{id}/timeline` so users can audit every transition.

- **GRN UI**
  - Always attach `Idempotency-Key` header on create.
  - Never send `X-Idempotency-Key`; show developer-facing warning if legacy integration attempts it.
  - Show per-line remaining quantity and hard-block over-receipt client-side.

- **Purchase invoice UI**
  - Invoice lines should mirror GRN lines 1:1 in material/qty/unit/cost.
  - Enforce tax-mode consistency (GST vs non-GST) in line editor before submit.
  - If using top-level `taxAmount`, disable line `taxRate`/`taxInclusive` inputs.

- **Return UI**
  - Show both *remaining returnable quantity* and *current outstanding amount* before submit.
  - Recompute max returnable amount client-side to reduce BUS_009 errors.
  - On success, refresh purchase detail to show reduced `outstandingAmount` and updated status.

- **GST display hints**
  - Render CGST/SGST/IGST columns in purchase line tables where available.
  - Split display by interstate rule:
    - same-state supplier/company -> CGST + SGST
    - cross-state -> IGST

### HR & Payroll

Comprehensive frontend handoff for the HR + payroll redesign. This section covers employee CRUD/search + salary assignment, leave workflow, attendance workflow, payroll lifecycle (create -> calculate -> approve -> post -> pay), and payslip data contracts.

All responses are wrapped in `ApiResponse<T>` with payload in `data`.

#### Endpoint Map

##### Employee management, search, and salary structure assignment

| Method | Path | Auth | Request | Response `data` | Frontend notes |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/employees` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<EmployeeDto>` | Canonical employee list + search source. Backend currently returns full list; do client-side/server-proxy filtering by `firstName`, `lastName`, `email`, `department`, `designation`, `status`, `employeeType`. |
| POST | `/api/v1/hr/employees` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `EmployeeRequest` | `EmployeeDto` | Employee create. Can include payroll fields in same request. |
| PUT | `/api/v1/hr/employees/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `EmployeeRequest` | `EmployeeDto` | Employee update, including salary template assignment (`salaryStructureTemplateId`) and tax regime (`taxRegime`). |
| DELETE | `/api/v1/hr/employees/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | empty body (`204`) | Hard delete in current API. Confirm before delete. |
| GET | `/api/v1/hr/salary-structures` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<SalaryStructureTemplateDto>` | Salary structure picker source. |
| POST | `/api/v1/hr/salary-structures` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `SalaryStructureTemplateRequest` | `SalaryStructureTemplateDto` | Create reusable Indian salary template. |
| PUT | `/api/v1/hr/salary-structures/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `SalaryStructureTemplateRequest` | `SalaryStructureTemplateDto` | Edit template components/rates/active flag. |

##### Leave endpoints

| Method | Path | Auth | Request | Response `data` | Frontend notes |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/leave-types` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<LeaveTypePolicyDto>` | Drives leave-type dropdown + entitlement tooltips. |
| GET | `/api/v1/hr/employees/{employeeId}/leave-balances` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query: `year?` | `List<LeaveBalanceDto>` | Balance panel; default year is current year when omitted. |
| GET | `/api/v1/hr/leave-requests` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<LeaveRequestDto>` | Request inbox/history. |
| POST | `/api/v1/hr/leave-requests` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `LeaveRequestRequest` | `LeaveRequestDto` | Leave request create. |
| PATCH | `/api/v1/hr/leave-requests/{id}/status` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `LeaveStatusUpdateRequest` | `LeaveRequestDto` | Approve/reject/cancel transition endpoint. |

##### Attendance endpoints

| Method | Path | Auth | Request | Response `data` | Frontend notes |
|---|---|---|---|---|---|
| GET | `/api/v1/hr/attendance/date/{date}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Path `date` (`yyyy-MM-dd`) | `List<AttendanceDto>` | Daily register view. |
| GET | `/api/v1/hr/attendance/today` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<AttendanceDto>` | Quick current-day register (company timezone aware). |
| GET | `/api/v1/hr/attendance/summary` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `AttendanceSummaryDto` | Dashboard card counters for today. |
| GET | `/api/v1/hr/attendance/summary/monthly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query: `year`, `month` | `List<MonthlyAttendanceSummaryDto>` | Monthly employee-level aggregates. |
| GET | `/api/v1/hr/attendance/employee/{employeeId}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query: `startDate`, `endDate` | `List<AttendanceDto>` | Employee-specific timeline. |
| POST | `/api/v1/hr/attendance/mark/{employeeId}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `MarkAttendanceRequest` | `AttendanceDto` | Single-employee marking/upsert for date. |
| POST | `/api/v1/hr/attendance/bulk-mark` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `BulkMarkAttendanceRequest` | `List<AttendanceDto>` | Team/day bulk mark. |
| POST | `/api/v1/hr/attendance/bulk-import` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `AttendanceBulkImportRequest` | `List<AttendanceDto>` | Multi-record import (batch of bulk requests). |

##### Payroll endpoints (run lifecycle, payment, payslip)

| Method | Path | Auth | Request | Response `data` | Frontend notes |
|---|---|---|---|---|---|
| GET | `/api/v1/payroll/runs` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<PayrollRunDto>` | All runs, latest first. |
| GET | `/api/v1/payroll/runs/weekly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<PayrollRunDto>` | Weekly labour runs only. |
| GET | `/api/v1/payroll/runs/monthly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `List<PayrollRunDto>` | Monthly staff runs only. |
| GET | `/api/v1/payroll/runs/{id}` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Path `id` | `PayrollRunDto` | Run header (status, totals, posting/payment refs). |
| GET | `/api/v1/payroll/runs/{id}/lines` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Path `id` | `List<PayrollRunLineDto>` | Line-level breakup used for review and payslip rendering. |
| POST | `/api/v1/payroll/runs` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `CreatePayrollRunRequest` | `PayrollRunDto` | Generic run create (`runType`, `periodStart`, `periodEnd`). |
| POST | `/api/v1/payroll/runs/weekly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query `weekEndingDate` (`yyyy-MM-dd`) | `PayrollRunDto` | Convenience weekly create. |
| POST | `/api/v1/payroll/runs/monthly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query `year`, `month` | `PayrollRunDto` | Convenience monthly create. |
| POST | `/api/v1/payroll/runs/{id}/calculate` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `PayrollRunDto` | Moves `DRAFT -> CALCULATED`. |
| POST | `/api/v1/payroll/runs/{id}/approve` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `PayrollRunDto` | Moves `CALCULATED -> APPROVED`. |
| POST | `/api/v1/payroll/runs/{id}/post` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `PayrollRunDto` | Posts accounting journal and moves `APPROVED -> POSTED`. |
| POST | `/api/v1/accounting/payroll/payments` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PayrollPaymentRequest` | `JournalEntryDto` | Single payroll payment journal (liability clearing). |
| POST | `/api/v1/accounting/payroll/payments/batch` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `PayrollBatchPaymentRequest` | `PayrollBatchPaymentResponse` | Batch payment with withholding/contribution breakdown. |
| POST | `/api/v1/payroll/runs/{id}/mark-paid` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `{ "paymentReference": "..." }` (optional override) | `PayrollRunDto` | Requires payment journal link; marks lines + run paid. |
| GET | `/api/v1/payroll/summary/weekly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query `weekEndingDate` | `WeeklyPaySummaryDto` | Weekly payout preview. |
| GET | `/api/v1/payroll/summary/monthly` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Query `year`, `month` | `MonthlyPaySummaryDto` | Monthly payout preview. |
| GET | `/api/v1/payroll/summary/current-week` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `WeeklyPaySummaryDto` | Current week shortcut. |
| GET | `/api/v1/payroll/summary/current-month` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | None | `MonthlyPaySummaryDto` | Current month shortcut. |

**Payslip note:** There is no dedicated `/payslips` endpoint yet. Build payslip UI from `GET /api/v1/payroll/runs/{id}` (header) + `GET /api/v1/payroll/runs/{id}/lines` (employee line), then filter by `employeeId`.

Legacy aliases:
- `GET /api/v1/hr/payroll-runs` and `POST /api/v1/hr/payroll-runs` return `410/GONE` payload with canonical path `/api/v1/payroll/runs`.

#### User Flows (API call sequences)

1. **Employee onboarding (create -> assign salary structure -> set tax regime)**
   1. `GET /api/v1/hr/salary-structures` (optional, for template picker).
   2. `POST /api/v1/hr/employees` with minimum identity + employment fields.
   3. `PUT /api/v1/hr/employees/{id}` with `salaryStructureTemplateId` (assignment step).
   4. `PUT /api/v1/hr/employees/{id}` with `taxRegime` (`OLD` or `NEW`) and statutory/bank details.
   5. `GET /api/v1/hr/employees` and run UI search filter to confirm onboarding record.

2. **Monthly payroll flow (create run -> calculate -> review -> approve -> post -> pay)**
   1. `POST /api/v1/payroll/runs/monthly?year=YYYY&month=MM`.
   2. `POST /api/v1/payroll/runs/{id}/calculate`.
   3. Review run: `GET /api/v1/payroll/runs/{id}` + `GET /api/v1/payroll/runs/{id}/lines`.
   4. `POST /api/v1/payroll/runs/{id}/approve`.
   5. `POST /api/v1/payroll/runs/{id}/post` (creates accounting posting journal).
   6. Pay liabilities:
      - Single: `POST /api/v1/accounting/payroll/payments`, or
      - Batch: `POST /api/v1/accounting/payroll/payments/batch`.
   7. `POST /api/v1/payroll/runs/{id}/mark-paid`.
   8. Payslip render/export: `GET /api/v1/payroll/runs/{id}` + `GET /api/v1/payroll/runs/{id}/lines` and filter target employee.

3. **Leave request and approval flow**
   1. `GET /api/v1/hr/leave-types`.
   2. `GET /api/v1/hr/employees/{employeeId}/leave-balances?year=YYYY`.
   3. `POST /api/v1/hr/leave-requests`.
   4. Approver inbox refresh: `GET /api/v1/hr/leave-requests`.
   5. Approve/reject/cancel: `PATCH /api/v1/hr/leave-requests/{id}/status`.
   6. Re-read balances: `GET /api/v1/hr/employees/{employeeId}/leave-balances?year=YYYY`.

#### State Machines

##### Payroll run lifecycle

| From | To | Endpoint | Guard |
|---|---|---|---|
| `DRAFT` | `CALCULATED` | `POST /api/v1/payroll/runs/{id}/calculate` | Run exists, valid period + employees. |
| `CALCULATED` | `APPROVED` | `POST /api/v1/payroll/runs/{id}/approve` | Calculated lines must exist. |
| `APPROVED` | `POSTED` | `POST /api/v1/payroll/runs/{id}/post` | Required payroll GL accounts available, posting journal can be created. |
| `POSTED` | `PAID` | `POST /api/v1/payroll/runs/{id}/mark-paid` | Payment journal must already be linked (`paymentJournalEntryId`). |
| `POSTED` | `POSTED` | `POST /api/v1/payroll/runs/{id}/post` | Idempotent replay allowed only when same journal linkage exists. |
| `PAID` | `PAID` | `POST /api/v1/payroll/runs/{id}/mark-paid` | Idempotent replay allowed; no duplicate recovery deduction. |

##### Leave request lifecycle

| From | To | Endpoint | Rule |
|---|---|---|---|
| `PENDING` | `APPROVED` | `PATCH /api/v1/hr/leave-requests/{id}/status` | Balance check enforced before approval. |
| `PENDING` | `REJECTED` | same | Decision metadata stored. |
| `PENDING` | `CANCELLED` | same | Cancellation allowed. |
| `APPROVED` | `REJECTED` / `CANCELLED` | same | Used balance is reverted. |
| `APPROVED` | `PENDING` | same | Not allowed (`BUS_001`). |

#### DTO Contracts, Validation Rules, and Indian Payroll Field Semantics

##### Employee + salary structure DTOs

- **`EmployeeRequest`**
  - Bean validation: `firstName` required, `lastName` required, `email` required + valid email.
  - Payroll fields:
    - `employeeType`: `STAFF|LABOUR`
    - `paymentSchedule`: `MONTHLY|WEEKLY`
    - `salaryStructureTemplateId`: assignment hook for reusable salary breakup
    - `monthlySalary` (staff), `dailyWage` (labour), `workingDaysPerMonth`, `standardHoursPerDay`, OT multipliers
    - statutory: `pfNumber`, `esiNumber`, `panNumber`, `taxRegime`
    - bank: account/name/IFSC/branch (persisted encrypted server-side)
  - Service-level validation:
    - STAFF requires either positive `monthlySalary` or `salaryStructureTemplateId`.
    - LABOUR requires positive `dailyWage`.
    - `panNumber` must match `^[A-Z]{5}[0-9]{4}[A-Z]$`.
    - `dateOfJoining` cannot be before `dateOfBirth`.

- **`EmployeeDto`**
  - Returns personal + employment + payroll projection in one payload.
  - Salary-template projection fields: `salaryStructureTemplateCode`, `basicPay`, `hra`, `da`, `specialAllowance`, `esiEligibilityThreshold`, `professionalTax`.
  - Returned enums are upper-case string values (`employeeType`, `paymentSchedule`, `taxRegime`, etc.).

- **`SalaryStructureTemplateRequest`**
  - Bean validation: `code` required, `name` required, all numeric components `>= 0`.
  - Service-level validation:
    - `code` unique per company (`BUS_002` on duplicate).
    - `basicPay + hra + da + specialAllowance > 0`.

- **`SalaryStructureTemplateDto`**
  - Includes Indian payroll template fields: component values, `employeePfRate`, `employeeEsiRate`, `esiEligibilityThreshold`, `professionalTax`, `totalEarnings`, `active`.

##### Leave DTOs

- **`LeaveRequestRequest`**
  - Bean validation: `leaveType`, `startDate`, `endDate` required.
  - Service-level validation: `employeeId` required, no overlapping leave range, `endDate >= startDate`.

- **`LeaveStatusUpdateRequest`**
  - Bean validation: `status` required; optional `decisionReason`.

- **`LeaveRequestDto`**
  - Includes `totalDays`, `status`, `decisionReason`, approver/rejector metadata (`approvedBy/approvedAt`, `rejectedBy/rejectedAt`).

- **`LeaveTypePolicyDto`**
  - Policy contract: `leaveType`, `annualEntitlement`, `carryForwardLimit`, `active`.

- **`LeaveBalanceDto`**
  - Year-wise ledger fields: `openingBalance`, `accrued`, `used`, `remaining`, `carryForwardApplied`.

##### Attendance DTOs

- **`MarkAttendanceRequest`**
  - Bean validation: `status` required.
  - Optional: `date` (defaults to company today), check-in/out, regular/OT hours, holiday/weekend flags, remarks.

- **`BulkMarkAttendanceRequest`**
  - Bean validation: `employeeIds` non-empty, `date` required, `status` required.

- **`AttendanceBulkImportRequest`**
  - Bean validation: `records` non-empty and each record validated.

- **`AttendanceDto`**
  - Returns per-day status, worked hours (`regularHours`, `overtimeHours`, `doubleOvertimeHours`), flags, and marker audit fields.

- **`AttendanceSummaryDto` / `MonthlyAttendanceSummaryDto`**
  - Daily counters and monthly per-employee aggregates used for payroll preview and HR dashboards.

##### Payroll run + summary DTOs

- **`CreatePayrollRunRequest`**
  - Required logically by service: `runType`, `periodStart`, `periodEnd`.
  - Validation: `periodEnd` cannot be before `periodStart`.

- **`PayrollRunDto`**
  - Header/lifecycle contract: `runNumber`, `runType`, `periodStart`, `periodEnd`, `status`, totals (`totalBasePay`, `totalOvertimePay`, `totalDeductions`, `totalNetPay`), posting/payment references (`journalEntryId`, `paymentReference`, `paymentDate`), audit (`createdBy/approvedBy/postedBy`, timestamps).

- **`PayrollRunLineDto`**
  - Earnings fields: `basePay`, `overtimePay`, `holidayPay`, `grossPay`, component breakup (`basicSalaryComponent`, `hraComponent`, `daComponent`, `specialAllowanceComponent`).
  - Deduction fields: `loanDeduction`, `pfDeduction`, `esiDeduction`, `taxDeduction`, `professionalTaxDeduction`, `leaveWithoutPayDeduction`, `otherDeductions`, `totalDeductions`.
  - Attendance + rate fields: `presentDays`, `halfDays`, `absentDays`, `leaveDays`, `holidayDays`, `regularHours`, `overtimeHours`, `doubleOtHours`, `dailyRate`, `hourlyRate`.
  - Payment fields: `paymentStatus`, `paymentReference`.

- **`WeeklyPaySummaryDto` / `EmployeeWeeklyPayDto`**
  - Weekly labour payout snapshot (`weekStart/weekEnd`, totals, employee-level daily-rate math).

- **`MonthlyPaySummaryDto` / `EmployeeMonthlyPayDto`**
  - Monthly staff payout snapshot (`totalGrossPay`, `totalDeductions`, `totalNetPay`, employee-level gross/PF/net).

##### Payroll payment DTOs (accounting module used by payroll flow)

- **`PayrollPaymentRequest`** (`POST /api/v1/accounting/payroll/payments`)
  - Bean validation: `payrollRunId`, `cashAccountId`, `expenseAccountId`, `amount` required; `amount >= 0.01`.
  - Server invariant: `amount` must match salary-payable amount from posted payroll journal.

- **`PayrollBatchPaymentRequest`** (`POST /api/v1/accounting/payroll/payments/batch`)
  - Bean validation: `runDate`, `cashAccountId`, `expenseAccountId`, `lines` required; rates/amounts `>= 0`.
  - `PayrollLine`: required `name`, `days`, `dailyWage`; optional per-line withholdings/advances/notes.

- **`PayrollBatchPaymentResponse`**
  - Aggregates gross, withholdings, net pay, employer contribution, and created journal IDs.

#### Indian Payroll Calculation Formulas (implemented backend rules)

These formulas are what the backend computes today. UI previews should mirror these to avoid approval surprises.

1. **Daily rate (staff)**
   - `dailyRate = monthlySalary / workingDaysPerMonth`

2. **Effective workdays**
   - `effectiveDays = presentDays + (halfDays * 0.5)`

3. **Hourly rate / overtime**
   - `hourlyRate = dailyRate / standardHoursPerDay`
   - `overtimePay = (hourlyRate * overtimeRateMultiplier * overtimeHours) + (hourlyRate * doubleOtRateMultiplier * doubleOtHours)`

4. **Monthly template prorating (staff with salary template)**
   - `leaveWithoutPayDays = absentDays + leaveDays + (halfDays * 0.5)`
   - `payableRatio = max(0, 1 - (leaveWithoutPayDays / workingDaysPerMonth))`
   - `basicSalaryComponent = template.basicPay * payableRatio`
   - `hraComponent = template.hra * payableRatio`
   - `daComponent = template.da * payableRatio`
   - `specialAllowanceComponent = template.specialAllowance * payableRatio`
   - `basePay = basic + hra + da + specialAllowance`

5. **Gross pay**
   - `grossPay = basePay + overtimePay + holidayPay`

6. **Advance/loan recovery cap**
   - `loanDeduction = min(advanceBalance, grossPay * 0.20)`

7. **PF deduction (employee side)**
   - `pfDeduction = basicSalaryComponent * (employeePfRate / 100)`
   - Default `employeePfRate = 12%` when template rate is missing.

8. **ESI deduction (employee side)**
   - `esiDeduction = grossPay * (employeeEsiRate / 100)` only when `grossPay <= esiEligibilityThreshold`
   - Defaults: `employeeEsiRate = 0.75%`, `esiEligibilityThreshold = 21000`.

9. **TDS deduction (simplified projected tax model in current implementation)**
   - `periodsPerYear = 12 (monthly) or 52 (weekly)`
   - `projectedAnnualGross = grossPay * periodsPerYear`
   - `annualExemption = 250000 (OLD regime) or 300000 (NEW regime)`
   - `taxableAnnual = max(0, projectedAnnualGross - annualExemption)`
   - `annualTax = taxableAnnual * 10%`
   - `taxDeduction = annualTax / periodsPerYear`

10. **Professional tax**
   - Applied only for monthly runs: `professionalTaxDeduction = template.professionalTax`.

11. **Total deductions and net pay**
   - `totalDeductions = loan + pf + esi + tds + professionalTax + otherDeductions`
   - `netPay = max(0, grossPay - totalDeductions)`

12. **Posting equation (accounting)**
   - `salaryPayable = grossPay - loan - pf - esi - tds - professionalTax`
   - Posting journal pattern:
     - Dr `SALARY-EXP` / `WAGE-EXP` (gross)
     - Cr `SALARY-PAYABLE`
     - Cr `PF-PAYABLE`, `ESI-PAYABLE`, `TDS-PAYABLE`, `PROFESSIONAL-TAX-PAYABLE`
     - Cr `EMP-ADV` (if loan recovery exists)

#### Error Codes and Frontend Handling

| Code | Enum | Typical trigger | Frontend behavior |
|---|---|---|---|
| `VAL_001` | `VALIDATION_INVALID_INPUT` | Invalid enum text (`employeeType`, `paymentSchedule`, leave/attendance status), invalid compensation combinations | Show inline validation and keep form editable. |
| `VAL_002` | `VALIDATION_MISSING_REQUIRED_FIELD` | Missing required payload fields/body/import records | Block submit and focus missing controls. |
| `VAL_003` | `VALIDATION_INVALID_FORMAT` | PAN format mismatch (`AAAAA9999A`) | Show field-level format helper/error. |
| `VAL_004` | `VALIDATION_OUT_OF_RANGE` | Negative salary component/rate | Range validation message near numeric control. |
| `VAL_005` | `VALIDATION_INVALID_DATE` | Invalid date ranges (leave end < start, joining < DOB) | Show date-range error and prevent submit. |
| `VAL_006` | `VALIDATION_INVALID_REFERENCE` | Unknown employee/template/account references | Refresh dropdowns/options and require re-selection. |
| `BUS_001` | `BUSINESS_INVALID_STATE` | Invalid lifecycle transition (payroll or leave) | Disable forbidden actions by state; show non-retryable toast. |
| `BUS_002` | `BUSINESS_DUPLICATE_ENTRY` | Duplicate salary template code | Keep form open; highlight duplicate field. |
| `BUS_004` | `BUSINESS_CONSTRAINT_VIOLATION` | Deductions exceed gross, uncategorized deductions, legacy endpoint usage | Show blocking modal and remediation hint. |
| `BUS_006` | `BUSINESS_LIMIT_EXCEEDED` | Insufficient leave balance on approval | Show available vs requested leave and block action. |
| `SYS_005` | `SYSTEM_CONFIGURATION_ERROR` | Missing required payroll GL account (e.g., `SALARY-PAYABLE`) | Route accounting admin to CoA provisioning screen. |
| `CONC_001` | `CONCURRENCY_CONFLICT` | Idempotency mismatch / already linked to different journal | Refresh run, show immutable conflict warning, avoid blind retry. |

#### UI Hints

- **Employee search UX:** Use `GET /api/v1/hr/employees` as the search source and filter client-side by name/email/department/designation/status. Persist search chips in URL for deep links.
- **Salary assignment UX:** Keep salary template selector and tax-regime selector in the onboarding wizard; both write through `PUT /api/v1/hr/employees/{id}`.
- **Enum controls:** Use dropdowns (no free text) for `employeeType`, `paymentSchedule`, `gender`, `employmentType`, `taxRegime`, leave status, and attendance status.
- **Leave screen:** Always show live leave balances beside request/approval actions and refresh balances after status updates.
- **Attendance UX:** `bulk-mark` for same-day team operations; `bulk-import` for staged multi-day/multi-team uploads.
- **Payroll action gating:** Enable buttons strictly by state (`DRAFT`, `CALCULATED`, `APPROVED`, `POSTED`, `PAID`).
- **Payslip generation:** Build payslip previews from run header + run lines; no dedicated payslip endpoint currently.
- **Legacy endpoint handling:** Hide `/api/v1/hr/payroll-runs` from navigation; route all payroll run actions to `/api/v1/payroll/runs`.

### Reports

#### Endpoint map

All report APIs are under `/api/v1` and require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.

- **GET `/api/v1/reports/trial-balance`**
  - Query params:
    - `date` (optional, ISO date; legacy single-date mode)
    - `periodId` (optional)
    - `startDate`, `endDate` (optional, must be supplied together)
    - `comparativeStartDate`, `comparativeEndDate` (optional, must be supplied together)
    - `comparativePeriodId` (optional)
    - `exportFormat` (optional: `PDF` or `CSV`)
  - Behavior:
    - If `date` is provided, backend uses as-of behavior.
    - Otherwise backend resolves range/period request and can return comparative payload.

- **GET `/api/v1/reports/profit-loss`**
  - Query params and behavior are identical in structure to trial balance (`date`, period/range, comparative, export hints).

- **GET `/api/v1/reports/balance-sheet`**
  - Query params and behavior are identical in structure to trial balance (`date`, period/range, comparative, export hints).

- **GET `/api/v1/reports/aged-debtors`** (new canonical path)
  - Query params:
    - `periodId` (optional)
    - `startDate`, `endDate` (optional, pair)
    - `exportFormat` (optional: `PDF` or `CSV`)
  - Uses report query window resolution and returns enriched bucketed debtor rows + metadata/export hints.

- **GET `/api/v1/accounting/reports/aged-debtors`** (legacy compatibility path)
  - No query params; retained for backward compatibility while frontend migrates to `/reports/aged-debtors`.

- **GET `/api/v1/reports/inventory-valuation`**
  - Query params:
    - `date` (optional, ISO date, enables AS_OF valuation using movement adjustments after this date)
  - Behavior:
    - Returns item-level valuation lines for raw materials and finished goods.
    - Applies accounting-period costing method when available (`FIFO`, `LIFO`, `WEIGHTED_AVERAGE`), otherwise falls back to item costing defaults.
    - Supports grouped aggregates for category and brand in the same payload.

- **GET `/api/v1/reports/gst-return`**
  - Query params:
    - `periodId` (optional; if omitted backend resolves current accounting period, falling back to latest period)
  - Behavior:
    - Aggregates output GST from sales invoices and input GST credit from purchase invoices.
    - Groups values by `taxRate` and includes transaction-level details for each contributing line.
    - Computes net liability component-wise: `outputTax - inputTaxCredit` (CGST/SGST/IGST + total).

#### Complete endpoint inventory (parameters, response schema, pagination)

All endpoints below are implemented in `ReportController` and return `ApiResponse<T>` where `T` is the schema listed.

| Endpoint | Query params | Response `data` schema | Pagination |
|---|---|---|---|
| `GET /api/v1/reports/trial-balance` | `date?`, `periodId?`, `startDate?`, `endDate?`, `comparativeStartDate?`, `comparativeEndDate?`, `comparativePeriodId?`, `exportFormat?` | `TrialBalanceDto` | None |
| `GET /api/v1/reports/profit-loss` | `date?`, `periodId?`, `startDate?`, `endDate?`, `comparativeStartDate?`, `comparativeEndDate?`, `comparativePeriodId?`, `exportFormat?` | `ProfitLossDto` | None |
| `GET /api/v1/reports/balance-sheet` | `date?`, `periodId?`, `startDate?`, `endDate?`, `comparativeStartDate?`, `comparativeEndDate?`, `comparativePeriodId?`, `exportFormat?` | `BalanceSheetDto` | None |
| `GET /api/v1/reports/aged-debtors` | `periodId?`, `startDate?`, `endDate?`, `exportFormat?` | `List<AgedDebtorDto>` | None |
| `GET /api/v1/accounting/reports/aged-debtors` (legacy) | none | `List<AgedDebtorDto>` | None |
| `GET /api/v1/reports/inventory-valuation` | `date?` | `InventoryValuationDto` | None |
| `GET /api/v1/reports/gst-return` | `periodId?` | `GstReturnReportDto` | None |
| `GET /api/v1/reports/cash-flow` | none | `CashFlowDto` | None |
| `GET /api/v1/reports/account-statement` | none | `List<AccountStatementEntryDto>` | None |
| `GET /api/v1/reports/inventory-reconciliation` | none | `ReconciliationSummaryDto` | None |
| `GET /api/v1/reports/balance-warnings` | none | `List<BalanceWarningDto>` | None |
| `GET /api/v1/reports/reconciliation-dashboard` | `bankAccountId` (required), `statementBalance?` | `ReconciliationDashboardDto` | None |
| `GET /api/v1/reports/wastage` | none | `List<WastageReportDto>` | None |
| `GET /api/v1/reports/production-logs/{id}/cost-breakdown` | path `id` | `CostBreakdownDto` | None |
| `GET /api/v1/reports/monthly-production-costs` | `year` (required), `month` (required) | `MonthlyProductionCostDto` | None |

#### User flows (frontend orchestration)

1. **Trial Balance (period/range with optional comparative)**
   1. User selects either accounting period or explicit date range.
   2. Optional comparative period/range is selected.
   3. UI calls `GET /reports/trial-balance` with chosen params.
   4. Render primary rows/totals from root payload.
   5. If `comparative != null`, render side-by-side comparative table.
   6. Use `metadata.balanced` equivalent from totals (`totalDebit` vs `totalCredit`) to show pass/fail state.

2. **Profit & Loss (comparative view)**
   1. User picks window and optional comparative.
   2. UI calls `GET /reports/profit-loss`.
   3. Render KPI cards: revenue, COGS, gross profit, operating expenses, net income.
   4. Render categorized expense breakdown using `operatingExpenseCategories`.
   5. If present, render `comparative` KPI deltas.

3. **Balance Sheet (sectioned rendering + equation check)**
   1. User selects period/range and optional comparative.
   2. UI calls `GET /reports/balance-sheet`.
   3. Render sections independently:
      - `currentAssets`, `fixedAssets`
      - `currentLiabilities`, `longTermLiabilities`
      - `equityLines`
   4. Display accounting equation status from `balanced` and totals (`totalAssets` vs `totalLiabilities + totalEquity`).
   5. If comparative present, render same section groups from `comparative`.

4. **Aged Debtors (bucket analysis)**
   1. User opens aging report and optionally narrows by date window.
   2. UI calls canonical path `GET /reports/aged-debtors`.
   3. Render columns per dealer: `current`, `oneToThirtyDays`, `thirtyOneToSixtyDays`, `sixtyOneToNinetyDays`, `ninetyPlusDays`, `totalOutstanding`.
   4. Surface export affordances using `exportHints`/metadata.

5. **Inventory Valuation (stock worth + costing method aware)**
   1. User opens inventory valuation and optionally selects `date` for an as-of snapshot.
   2. UI calls `GET /reports/inventory-valuation` with optional `date`.
   3. Render `items[]` table with `inventoryType`, `code`, `name`, `category`, `brand`, `quantityOnHand`, `reservedQuantity`, `availableQuantity`, `unitCost`, `totalValue`, `lowStock`.
   4. Render summary tiles from root fields: `totalValue`, `lowStockItems`, `costingMethod`.
   5. Render grouped widgets from `groupByCategory[]` and `groupByBrand[]`.

6. **GST Return (period filing worksheet)**
   1. User selects accounting period (or uses default current period).
   2. UI calls `GET /reports/gst-return?periodId={id}` (or omits `periodId` for default behavior).
   3. Render section totals from `outputTax`, `inputTaxCredit`, `netLiability`.
   4. Render tax-rate summary grid from `rateSummaries[]`.
   5. Render transaction detail ledger from `transactionDetails[]` for reconciliation drill-down.

#### State/range resolution model used by backend

Report query APIs resolve one of these data sources and expose it via `ReportMetadata.source`:

- `LIVE`: open/current data without snapshot lock.
- `AS_OF`: explicit as-of mode (typically when `date` is provided).
- `SNAPSHOT`: closed accounting period with required snapshot backing.

Resolution rules used by backend query support:

1. `startDate/endDate` must be supplied together; same for comparative range.
2. If `periodId` is supplied and range is omitted, period start/end are used.
3. If period is closed, backend requires accounting-period snapshot; missing snapshot yields business constraint error.
4. If neither period nor range supplied, backend defaults to current-month-to-date window.
5. `exportFormat` is normalized to `PDF` or `CSV`; anything else is rejected.

#### Error codes and frontend handling (reports)

| Code | Enum | Typical trigger | Frontend behavior |
|---|---|---|---|
| `VAL_001` | `VALIDATION_INVALID_INPUT` | Unsupported `exportFormat`, malformed query combinations flagged as invalid input | Show non-retryable validation message; keep filters editable. |
| `VAL_005` | `VALIDATION_INVALID_DATE` | `startDate > endDate`, only one side of a date pair provided, invalid comparative date pair | Highlight date controls and block submit. |
| `BUS_003` | `BUSINESS_ENTITY_NOT_FOUND` | `periodId` / `comparativePeriodId` / GST `periodId` does not exist in current company scope | Prompt user to refresh and reselect a valid accounting period option. |
| `BUS_004` | `BUSINESS_CONSTRAINT_VIOLATION` | Closed period requested but snapshot missing | Show blocking banner/modal and suggest accounting snapshot remediation. |
| `AUTH_004` | `AUTH_INSUFFICIENT_PERMISSIONS` | `companyCode` mismatch against authenticated company context | Force logout and re-login into the correct company scope; there is no tenant re-selection flow. |

#### Data contracts

- **Envelope + pagination contract**
  - All report endpoints in this section return `ApiResponse<T>` with payload under `data`.
  - None of the report endpoints currently return `PageResponse`; all are aggregate payloads or unpaged lists.
  - `T` per endpoint is defined in the endpoint inventory table above.

- **`FinancialReportQueryRequest`** (backend query object; represented by endpoint query params)
  - `periodId: Long?`
  - `startDate: LocalDate?`
  - `endDate: LocalDate?`
  - `asOfDate: LocalDate?` (used by legacy `date` mode)
  - `companyId: Long?` (normally omitted by UI; backend enforces active company)
  - `comparativeStartDate: LocalDate?`
  - `comparativeEndDate: LocalDate?`
  - `comparativePeriodId: Long?`
  - `exportFormat: String?` (`PDF`/`CSV`)

- **`ReportMetadata`**
  - `asOfDate`, `startDate`, `endDate`
  - `source` (`LIVE`/`AS_OF`/`SNAPSHOT`)
  - `accountingPeriodId`, `accountingPeriodStatus`, `snapshotId`
  - `pdfReady`, `csvReady`, `requestedExportFormat`

- **`TrialBalanceDto`**
  - Root: `rows[]`, `totalDebit`, `totalCredit`, `balanced`, `metadata`, `comparative?`
  - `rows[]`: `accountId`, `code`, `name`, `type`, `debit`, `credit`, `net`
  - `comparative`: same shape for side-by-side window

- **`ProfitLossDto`**
  - Root: `revenue`, `costOfGoodsSold`, `grossProfit`, `operatingExpenses`, `operatingExpenseCategories[]`, `netIncome`, `metadata`, `comparative?`
  - `operatingExpenseCategories[]`: `{ category, amount }`
  - `comparative`: parallel metrics + categorized expenses + metadata

- **`BalanceSheetDto`**
  - Root: `totalAssets`, `totalLiabilities`, `totalEquity`, `balanced`, `metadata`
  - Section arrays: `currentAssets[]`, `fixedAssets[]`, `currentLiabilities[]`, `longTermLiabilities[]`, `equityLines[]`
  - Section element: `{ accountId, accountCode, accountName, amount }`
  - `comparative`: same totals/sections + own metadata

- **`AgedDebtorDto`**
  - `dealerId`, `dealerCode`, `dealerName`
  - Buckets: `current`, `oneToThirtyDays`, `thirtyOneToSixtyDays`, `sixtyOneToNinetyDays`, `ninetyPlusDays`
  - `totalOutstanding`
  - `metadata`
  - `exportHints`: `{ pdfReady, csvReady, requestedFormat }`

- **`InventoryValuationDto`**
  - Root fields:
    - `totalValue: BigDecimal`
    - `lowStockItems: long`
    - `costingMethod: String` (`FIFO` / `LIFO` / `WEIGHTED_AVERAGE`)
    - `metadata: ReportMetadata`
  - `items[]` (`InventoryValuationItemDto`):
    - `inventoryItemId: Long`
    - `inventoryType: String` (`RAW_MATERIAL` / `FINISHED_GOOD`)
    - `code`, `name`, `category`, `brand`
    - `quantityOnHand`, `reservedQuantity`, `availableQuantity`
    - `unitCost`, `totalValue`
    - `lowStock: boolean`
  - `groupByCategory[]` / `groupByBrand[]` (`InventoryValuationGroupDto`):
    - `groupType` (`CATEGORY` or `BRAND`)
    - `groupKey`
    - `totalValue`
    - `itemCount`
    - `lowStockItems`

- **`GstReturnReportDto`**
  - Root fields:
    - `periodId`, `periodLabel`, `periodStart`, `periodEnd`
    - `outputTax`, `inputTaxCredit`, `netLiability` (`GstComponentSummary`)
    - `rateSummaries[]` (`GstRateSummary`)
    - `transactionDetails[]` (`GstTransactionDetail`)
    - `metadata: ReportMetadata`
  - `GstComponentSummary`:
    - `cgst`, `sgst`, `igst`, `total`
  - `GstRateSummary`:
    - `taxRate`, `taxableAmount`
    - `outputTax`, `inputTaxCredit`, `netTax`
    - Component split fields: `outputCgst`, `outputSgst`, `outputIgst`, `inputCgst`, `inputSgst`, `inputIgst`
  - `GstTransactionDetail`:
    - `sourceType` (`SALES_INVOICE` / `PURCHASE_INVOICE`)
    - `sourceId`, `referenceNumber`, `transactionDate`, `partyName`
    - `taxRate`, `taxableAmount`
    - `cgst`, `sgst`, `igst`, `totalTax`
    - `direction` (`OUTPUT` / `INPUT`)

#### Export support matrix (PDF/CSV)

Export readiness is surfaced via metadata/hints, not separate export-only endpoints in the reports module.

| Report endpoint | Export indicator fields | PDF support | CSV support | Frontend behavior |
|---|---|---|---|---|
| `GET /api/v1/reports/trial-balance` | `metadata.pdfReady`, `metadata.csvReady`, `metadata.requestedExportFormat` | Yes | Yes | Show export buttons when `pdfReady/csvReady=true`; pass `exportFormat=PDF|CSV` in same endpoint call. |
| `GET /api/v1/reports/profit-loss` | `metadata.pdfReady`, `metadata.csvReady`, `metadata.requestedExportFormat` | Yes | Yes | Same pattern as trial balance. |
| `GET /api/v1/reports/balance-sheet` | `metadata.pdfReady`, `metadata.csvReady`, `metadata.requestedExportFormat` | Yes | Yes | Same pattern as trial balance. |
| `GET /api/v1/reports/aged-debtors` | `exportHints.pdfReady`, `exportHints.csvReady`, `exportHints.requestedFormat` + `metadata` | Yes | Yes | Drive export buttons from `exportHints` at report level and `metadata` at window level. |
| `GET /api/v1/reports/inventory-valuation` | `metadata.pdfReady`, `metadata.csvReady`, `metadata.requestedExportFormat` | Ready via metadata | Ready via metadata | Enable generic export action; backend marks readiness through metadata. |
| `GET /api/v1/reports/gst-return` | `metadata.pdfReady`, `metadata.csvReady`, `metadata.requestedExportFormat` | Ready via metadata | Ready via metadata | Use metadata flags and map output to filing worksheet/export UI. |

#### UI hints

- Use period/date controls that enforce paired range fields (disable submit until both dates are present).
- Treat `/api/v1/reports/aged-debtors` as canonical; keep legacy path only for backward compatibility/testing.
- Prefer metadata-driven badges in UI:
  - Data source chip from `metadata.source`
  - Snapshot lock indicator when `metadata.snapshotId != null`
  - Export format/status from metadata/export hints
- In comparative mode, show clearly labeled "Primary" vs "Comparative" windows using each payload’s metadata dates.
- For Balance Sheet and Trial Balance, show explicit accounting integrity badges (`balanced` + totals) to aid finance review.

#### Export approval gate (VAL-ADMIN-003)

##### Endpoint map (new/changed)

| Method | Path | Auth | Request | Response `data` |
|---|---|---|---|---|
| `POST` | `/api/v1/exports/request` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | `ExportRequestCreateRequest` | `ExportRequestDto` |
| `GET` | `/api/v1/exports/{requestId}/download` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | none | `ExportRequestDownloadResponse` |
| `PUT` | `/api/v1/admin/exports/{requestId}/approve` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | none | `ExportRequestDto` |
| `PUT` | `/api/v1/admin/exports/{requestId}/reject` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | `ExportRequestDecisionRequest` (optional body) | `ExportRequestDto` |
| `GET` | `/api/v1/admin/approvals` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | none | `AdminApprovalsResponse` (single tenant-scoped inbox including `exportRequests[]`) |
| `GET` | `/api/v1/admin/settings` | `ROLE_ADMIN` | none | `SystemSettingsDto` (now includes `exportApprovalRequired`) |
| `PUT` | `/api/v1/admin/settings` | `ROLE_ADMIN` | `SystemSettingsUpdateRequest` (now accepts `exportApprovalRequired`) | `SystemSettingsDto` |

##### User flows

1. **Request export (reporting user)**
   1. User chooses report + export parameters in reports UI.
   2. Frontend calls `POST /api/v1/exports/request` with `{ reportType, parameters }`.
   3. Persist returned `ExportRequestDto.id` and show status chip from `status` (`PENDING` initially).

2. **Admin review queue**
   1. Tenant-scoped admin or accounting user opens approvals center and calls unified `GET /api/v1/admin/approvals`.
   2. For each export row in `exportRequests[]`, use `originType="EXPORT_REQUEST"`, `ownerType="REPORTS"`, `reference`, `reportType`, `approveEndpoint`, and `rejectEndpoint` from the payload.
   3. Treat export rows as inbox-only when `actionType`, `actionLabel`, `approveEndpoint`, and `rejectEndpoint` are `null`.

3. **Approve export**
   1. Admin clicks approve.
   2. Frontend calls `PUT /api/v1/admin/exports/{requestId}/approve`.
   3. UI updates request row to `APPROVED` and enables download attempt for requester.

4. **Reject export**
   1. Admin optionally captures rejection reason in modal.
   2. Frontend calls `PUT /api/v1/admin/exports/{requestId}/reject` with optional `{ reason }`.
   3. UI surfaces terminal `REJECTED` status and `rejectionReason` (if present).

5. **Download gate check**
   1. Requester attempts download and frontend calls `GET /api/v1/exports/{requestId}/download`.
   2. If gate is enabled and request not approved, backend returns auth error (handled by global error envelope; in current flow this may surface as `AUTH_004` with 401/403 depending on security chain).
   3. If approved (or gate disabled), backend returns `ExportRequestDownloadResponse` with gate message and status.

6. **Toggle gate at runtime**
   1. Admin loads `GET /api/v1/admin/settings` and reads `exportApprovalRequired`.
   2. Admin toggles flag and submits `PUT /api/v1/admin/settings` with `exportApprovalRequired`.
   3. Behavior flips immediately for subsequent `/exports/{requestId}/download` requests.

##### State machine

- `PENDING` -> `APPROVED` via `PUT /api/v1/admin/exports/{requestId}/approve`
- `PENDING` -> `REJECTED` via `PUT /api/v1/admin/exports/{requestId}/reject`
- `APPROVED` and `REJECTED` are terminal for current API (repeat approve/reject returns invalid-state error).
- `EXPIRED` is reserved in enum/DB constraint for future lifecycle policy.

##### Error codes and frontend behavior

| Error code | Typical trigger | Suggested frontend behavior |
|---|---|---|
| `AUTH_004` (`AUTH_INSUFFICIENT_PERMISSIONS`) | Download check while gate enabled and request not approved, or actor lacks role/scope | Show blocked-download banner with current status and route to approvals/help CTA. |
| `BUS_003` (`BUSINESS_ENTITY_NOT_FOUND`) | Unknown export request id or cross-company request lookup | Show not-found toast and refresh request list. |
| `BUS_001` (`BUSINESS_INVALID_STATE`) | Approve/reject attempted on non-`PENDING` request | Refresh row and show immutable state message. |
| `VAL_001` (`VALIDATION_INVALID_INPUT`) | Blank `reportType` on create request | Inline validation for report selection before submit. |

##### Data contracts

- `ExportRequestCreateRequest`
  - `reportType: string` (**required**, non-blank)
  - `parameters: string?` (optional serialized filter payload)

- `ExportRequestDecisionRequest`
  - `reason: string?` (optional; stored as `rejectionReason` when rejecting)

- `ExportRequestDto`
  - `id: number`
  - `userId: number`
  - `userEmail: string?`
  - `reportType: string`
  - `parameters: string?`
  - `status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED"`
  - `rejectionReason: string?`
  - `createdAt: string (ISO-8601 instant)`
  - `approvedBy: string?`
  - `approvedAt: string? (ISO-8601 instant)`

- `ExportRequestDownloadResponse`
  - `requestId: number`
  - `status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED"`
  - `reportType: string`
  - `parameters: string?`
  - `message: string` (explicit gate outcome text)

- `SystemSettingsDto` / `SystemSettingsUpdateRequest` additions
  - `exportApprovalRequired: boolean` (runtime gate toggle)

- `AdminApprovalsResponse` addition
  - `exportRequests: AdminApprovalItemDto[]` (same shape/pattern as other approval queues)
  - export approval rows now use typed `originType` / `ownerType`; do not read legacy `type` or `sourcePortal`

##### UI hints

- Add **Export approval required** toggle in admin settings, bound to `exportApprovalRequired`.
- In report export modals, switch from immediate file download to request lifecycle UI (`Requested` / `Approved` / `Rejected`).
- In unified approvals screen, render export rows alongside credit/payroll using `originType === "EXPORT_REQUEST"` and `ownerType === "REPORTS"`, and only show decision controls when the action endpoints are non-null.
- Keep polling/refresh affordance for request status after submission and after admin actions.

### Cross-module flow playbooks for report-driven frontend guidance

These flows map complete API sequences across modules. Use them to drive wizard-style UX and to decide when to refresh report screens.

#### 1) O2C (Order-to-Cash) -> report touchpoints

1. Dealer onboarding: `POST /api/v1/dealers`.
2. Create sales order: `POST /api/v1/sales/orders`.
3. Confirm order: `POST /api/v1/sales/orders/{id}/confirm`.
4. Dispatch + invoice creation: use the factory/admin dispatch workspace for preview, slip lookup, and challan output, then use `POST /api/v1/dispatch/confirm` for the canonical shipment posting. Pure factory sessions stay redacted; admin/elevated sessions may use the returned amount and journal fields when permitted, but invoice follow-up should come from invoice/slip reads rather than a `finalInvoiceId` on the dispatch response.
5. Receive/allocate payment: `POST /api/v1/accounting/settlements/dealers` (or auto-settle endpoint if used).
6. Operational reconciliation checks:
   - `GET /api/v1/portal/finance/aging?dealerId=`
   - `GET /api/v1/accounting/reconciliation/subledger`
7. Reporting refresh sequence after settlement:
   - `GET /api/v1/reports/aged-debtors`
   - `GET /api/v1/reports/trial-balance`
   - `GET /api/v1/reports/profit-loss`

Frontend orchestration notes:
- Keep order/dispatch/invoice/settlement in one guided timeline view.
- Refresh aged debtors immediately after settlement to reflect bucket movement and outstanding reduction.

#### 2) P2P (Procure-to-Pay) -> report touchpoints

1. Supplier onboarding + activation:
   - `POST /api/v1/suppliers`
   - `POST /api/v1/suppliers/{id}/approve`
   - `POST /api/v1/suppliers/{id}/activate`
2. Create PO: `POST /api/v1/purchasing/purchase-orders`.
3. Approve PO: `POST /api/v1/purchasing/purchase-orders/{id}/approve`.
4. Receive goods (GRN): `POST /api/v1/purchasing/goods-receipts`.
5. Create purchase invoice: `POST /api/v1/purchasing/raw-material-purchases`.
6. Settle AP:
   - `POST /api/v1/accounting/settlements/suppliers`
7. Reporting refresh sequence after posting/payment:
   - `GET /api/v1/reports/inventory-valuation`
   - `GET /api/v1/reports/gst-return`
   - `GET /api/v1/reports/trial-balance`

Frontend orchestration notes:
- Gate GRN and invoice actions by PO state (`APPROVED`, then received state).
- Surface GST impact from purchase invoice posting using GST report drill-down.

#### 3) Manufacturing-to-stock -> report touchpoints

1. Create production plan: `POST /api/v1/factory/production-plans`.
2. Log production (consumption + costs): `POST /api/v1/factory/production/logs`.
3. Pack output:
   - `POST /api/v1/factory/packing-records` with `Idempotency-Key`
4. Validate stock availability:
   - `GET /api/v1/finished-goods/stock-summary`
   - `GET /api/v1/finished-goods/{id}/batches`
5. Reporting refresh sequence:
   - `GET /api/v1/reports/inventory-valuation`
   - `GET /api/v1/reports/wastage`
   - `GET /api/v1/reports/production-logs/{id}/cost-breakdown`
   - `GET /api/v1/reports/monthly-production-costs?year=YYYY&month=MM`

Frontend orchestration notes:
- Keep production/packing and inventory valuation in a unified manufacturing console.
- Use monthly production costs for period-close variance cards.

#### 4) Payroll-to-accounting -> report touchpoints

1. Create payroll run:
   - Monthly: `POST /api/v1/payroll/runs/monthly?year=YYYY&month=MM`
   - or generic: `POST /api/v1/payroll/runs`
2. Calculate: `POST /api/v1/payroll/runs/{id}/calculate`.
3. Approve: `POST /api/v1/payroll/runs/{id}/approve`.
4. Post accounting journal: `POST /api/v1/payroll/runs/{id}/post`.
5. Execute payment journal:
   - `POST /api/v1/accounting/payroll/payments`
   - or `POST /api/v1/accounting/payroll/payments/batch`
6. Mark run paid: `POST /api/v1/payroll/runs/{id}/mark-paid`.
7. Reporting refresh sequence:
   - `GET /api/v1/reports/trial-balance`
   - `GET /api/v1/reports/profit-loss`
   - `GET /api/v1/reports/balance-sheet`

Frontend orchestration notes:
- Enforce action buttons by payroll state (`DRAFT -> CALCULATED -> APPROVED -> POSTED -> PAID`).
- After post/pay, refresh financial reports to reflect payroll expense, liabilities, and cash movement.

#### Visualization hints

- **Trial Balance:** primary representation is a sortable tabular grid (`accountCode`, `accountName`, `debit`, `credit`, `net`) with a totals footer and a balance badge (`totalDebit == totalCredit`).
- **Profit & Loss:** render as a hierarchical statement tree:
  - Revenue
  - COGS
  - Gross Profit
  - Operating Expenses (expand from `operatingExpenseCategories`)
  - Net Income
  In comparative mode, render side-by-side trees with delta badges per node.
- **Balance Sheet:** render as a T-account style layout:
  - Left: `currentAssets` + `fixedAssets`
  - Right: `currentLiabilities` + `longTermLiabilities` + `equityLines`
  Surface equation chip for `Assets = Liabilities + Equity` from `balanced`.
- **Aged Debtors:** use a stacked bar chart per dealer (segments: `current`, `oneToThirtyDays`, `thirtyOneToSixtyDays`, `sixtyOneToNinetyDays`, `ninetyPlusDays`) with a table fallback for export/print workflows.
- **Inventory Valuation:** use a sortable table as primary visualization (`inventoryType`, `code`, `name`, `category`, `brand`, `quantityOnHand`, `reservedQuantity`, `availableQuantity`, `unitCost`, `totalValue`, `lowStock`) with summary chips (`costingMethod`, `totalValue`, `lowStockItems`) and optional grouped side-panels (`groupByCategory`, `groupByBrand`).
- **GST Return:** render section-wise cards aligned to government filing structure:
  - `Outward supplies / output tax` -> `outputTax.cgst`, `outputTax.sgst`, `outputTax.igst`, `outputTax.total`
  - `Input tax credit` -> `inputTaxCredit.cgst`, `inputTaxCredit.sgst`, `inputTaxCredit.igst`, `inputTaxCredit.total`
  - `Net tax liability` -> `netLiability.cgst`, `netLiability.sgst`, `netLiability.igst`, `netLiability.total`
  Then render rate-wise table from `rateSummaries[]` and invoice-level annexure from `transactionDetails[]` for reconciliation.
