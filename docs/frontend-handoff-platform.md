# Frontend Handoff — Platform Surfaces

Last reviewed: 2026-03-30

This packet documents the frontend contract for **platform surfaces** — auth/session flows, tenant/company lifecycle and runtime gating, admin/control-plane host ownership, and shared DTO families. It explains canonical hosts, payload families, RBAC assumptions, read/write boundaries, and the non-uniform RBAC splits within portal surfaces.

This packet defers to the canonical module and flow docs for implementation truth and is not a second source of truth.

---

## 1. Scope Overview

| Surface | Module | Canonical Doc |
| --- | --- | --- |
| Auth/Session | `auth` (AuthController, MfaController) | [docs/modules/auth.md](modules/auth.md) |
| Company/Tenant | `company` (CompanyController, SuperAdminController) | [docs/modules/company.md](modules/company.md) |
| Admin/Portal/RBAC | `admin`, `portal`, `rbac`, `sales` (dealer-portal) | [docs/modules/admin-portal-rbac.md](modules/admin-portal-rbac.md) |

---

## 2. Canonical Host Prefixes

All platform endpoints use the same host prefix:

```
/api/v1/
```

### 2.1 Auth Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/auth/login` | POST | Public | Write (credential verification) |
| `/api/v1/auth/refresh-token` | POST | Public | Write (token rotation) |
| `/api/v1/auth/logout` | POST | Authenticated | Write (revoke sessions) |
| `/api/v1/auth/me` | GET | Authenticated | Read (current user identity) |
| `/api/v1/auth/password/change` | POST | Authenticated | Write (password change) |
| `/api/v1/auth/password/forgot` | POST | Public | Write (reset request) |
| `/api/v1/auth/password/reset` | POST | Public | Write (password reset) |
| `/api/v1/auth/mfa/setup` | POST | Authenticated | Write (MFA enrollment start) |
| `/api/v1/auth/mfa/activate` | POST | Authenticated | Write (MFA enrollment confirm) |
| `/api/v1/auth/mfa/disable` | POST | Authenticated | Write (MFA disable) |
| `/api/v1/auth/profile` | GET/PUT | Authenticated | Read/Write (profile) |

### 2.2 Company/Tenant Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/companies` | GET | ADMIN, SALES, ACCOUNTING | Read (tenant-scoped) |
| `/api/v1/superadmin/dashboard` | GET | SUPER_ADMIN | Read |
| `/api/v1/superadmin/tenants` | GET | SUPER_ADMIN | Read |
| `/api/v1/superadmin/tenants/{id}` | GET | SUPER_ADMIN | Read |
| `/api/v1/superadmin/tenants/{id}/lifecycle` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/limits` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/modules` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/support/warnings` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/support/context` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/admins/main` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/{id}/force-logout` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/onboard` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/tenants/coa-templates` | GET | SUPER_ADMIN | Read |

### 2.3 Admin Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/admin/users` | GET | ADMIN, SUPER_ADMIN | Read |
| `/api/v1/admin/users` | POST | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}` | PUT | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}/status` | PUT | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}/force-reset-password` | POST | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}/suspend` | PATCH | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}/unsuspend` | PATCH | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}/mfa/disable` | PATCH | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/users/{id}` | DELETE | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/settings` | GET | ADMIN | Read |
| `/api/v1/admin/settings` | PUT | SUPER_ADMIN | Write (SUPER_ADMIN required for `periodLockEnforced` changes) |
| `/api/v1/admin/exports/{id}/approve` | PUT | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/exports/{id}/reject` | PUT | ADMIN, SUPER_ADMIN | Write |
| `/api/v1/admin/notify` | POST | ADMIN | Write |
| `/api/v1/admin/approvals` | GET | ADMIN, ACCOUNTING, SUPER_ADMIN | Read |
| `/api/v1/admin/roles` | GET | ADMIN, SUPER_ADMIN | Read |
| `/api/v1/admin/roles/{roleKey}` | GET | ADMIN, SUPER_ADMIN | Read |
| `/api/v1/admin/roles` | POST | ADMIN, SUPER_ADMIN | Write |

### 2.4 Portal Routes (Internal Admin Views)

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/portal/dashboard` | GET | ADMIN | Read (insights) |
| `/api/v1/portal/operations` | GET | ADMIN | Read (insights) |
| `/api/v1/portal/workforce` | GET | ADMIN | Read (insights) |
| `/api/v1/portal/finance/ledger` | GET | ADMIN, ACCOUNTING | Read (requires dealerId param) |
| `/api/v1/portal/finance/invoices` | GET | ADMIN, ACCOUNTING | Read (requires dealerId param) |
| `/api/v1/portal/finance/aging` | GET | ADMIN, ACCOUNTING | Read (requires dealerId param) |
| `/api/v1/portal/support/tickets` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/portal/support/tickets` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/portal/support/tickets/{ticketId}` | GET | ADMIN, ACCOUNTING | Read |

### 2.5 Dealer Portal Routes (Self-Service)

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/dealer-portal/dashboard` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/ledger` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/invoices` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | GET | DEALER | Read (own invoice only) |
| `/api/v1/dealer-portal/aging` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/orders` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/credit-limit-requests` | POST | DEALER | Write (self) |
| `/api/v1/dealer-portal/support/tickets` | GET | DEALER | Read (auto-scoped) |
| `/api/v1/dealer-portal/support/tickets` | POST | DEALER | Write (self) |
| `/api/v1/dealer-portal/support/tickets/{ticketId}` | GET | DEALER | Read (own ticket only) |

### 2.6 Changelog Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/changelog` | GET | Authenticated | Read |
| `/api/v1/changelog/latest-highlighted` | GET | Authenticated | Read |
| `/api/v1/superadmin/changelog` | POST | SUPER_ADMIN | Write |
| `/api/v1/superadmin/changelog/{id}` | PUT | SUPER_ADMIN | Write |
| `/api/v1/superadmin/changelog/{id}` | DELETE | SUPER_ADMIN | Write |

---

## 3. RBAC Summary

### 3.1 Role Permissions by Surface

| Role | Auth | Company (Tenant) | Admin | Portal (Insights) | Portal (Finance) | Dealer Portal |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| `ROLE_SUPER_ADMIN` | Full | Full (control-plane) | Full (settings write) | — | — | — |
| `ROLE_ADMIN` | Full | Read (own tenant) | Full | Full | — | — |
| `ROLE_ACCOUNTING` | Full | Read (own tenant) | Approvals only | — | Full | — |
| `ROLE_SALES` | Full | Read (own tenant) | — | — | — | — |
| `ROLE_FACTORY` | Full | Read (own tenant) | — | — | — | — |
| `ROLE_DEALER` | Limited | — | — | — | — | Full (self-service) |

### 3.2 Key RBAC Boundaries

1. **Super-admin is control-plane only** — Super-admin users authenticate with platform scope (`PLATFORM`) and are restricted to `/api/v1/superadmin/**`, `/api/v1/auth/**`, `/api/v1/companies`, and `/api/v1/admin/settings`. They cannot access tenant business endpoints.

2. **Super-admin does NOT inherit tenant-level ADMIN permissions** — `ROLE_SUPER_ADMIN` is platform-scoped and operates at the control-plane level. It does NOT inherit or include the permissions of `ROLE_ADMIN` within a tenant. Super-admin users cannot access tenant-scoped admin endpoints like `/api/v1/admin/roles`, `/api/v1/admin/approvals`, or portal insights (`/api/v1/portal/dashboard/**`). Note that `/api/v1/admin/users` IS accessible by SUPER_ADMIN (via explicit grant in the controller), but this is a special case — most tenant-scoped admin endpoints are NOT accessible. This is a strict separation: platform scope (`PLATFORM`) vs tenant scope (`TENANT`).

3. **Portal insights are admin-only** — Dashboard, operations, and workforce insights require `ROLE_ADMIN` exclusively. Accounting and other roles cannot access these endpoints.

4. **Portal finance is admin/accounting only** — Ledger, invoices, and aging require `ADMIN_OR_ACCOUNTING` and need an explicit `dealerId` query parameter.

5. **Dealer portal is self-service only** — All dealer-portal endpoints require `ROLE_DEALER` and auto-scope to the authenticated dealer's own data. No `dealerId` parameter is needed or allowed.

6. **Admin settings have split read/write** — Reading settings requires `ROLE_ADMIN`; writing settings requires `ROLE_SUPER_ADMIN`.

7. **Export approval is admin/super-admin only** — Both approve and reject require `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.

---

## 4. Non-Uniform RBAC Split in `/api/v1/portal/*`

The `/api/v1/portal/*` host family has **non-uniform RBAC** with different predicates for different sub-paths:

| Sub-Path | Required Role | Notes |
| --- | :---: | :--- |
| `/api/v1/portal/dashboard` | `ROLE_ADMIN` | Admin-only insights |
| `/api/v1/portal/operations` | `ROLE_ADMIN` | Admin-only insights |
| `/api/v1/portal/workforce` | `ROLE_ADMIN` | Admin-only insights |
| `/api/v1/portal/finance/*` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Finance surfaces - broader than insights |
| `/api/v1/portal/support/tickets` | `ROLE_ADMIN` or `ROLE_ACCOUNTING` | Support ticket management |

**Frontend implications:**
- The same host prefix (`/api/v1/portal/*`) does NOT imply the same access level
- Insights endpoints will return 403 for ACCOUNTING users
- Finance endpoints will work for both ADMIN and ACCOUNTING
- Always check the specific role requirements per endpoint

---

## 5. Payload Families

### 5.1 Auth Payloads

**Login:**
- `LoginRequest` — `email`, `password`, `companyCode`, optional `mfaCode`
- `LoginResponse` — `accessToken`, `refreshToken`, `expiresIn`, `user` (id, name, email, roles, mustChangePassword)

**Refresh:**
- `RefreshTokenRequest` — `refreshToken`, `companyCode`
- `RefreshTokenResponse` — `accessToken`, `refreshToken`, `expiresIn`

**Password reset:**
- `ForgotPasswordRequest` — `email`, `companyCode`
- `ResetPasswordRequest` — `token`, `newPassword`, `confirmPassword`

**MFA:**
- `MfaSetupResponse` — `secret`, `qrUri`, `recoveryCodes[]` (one-time, store securely)
- `MfaActivateRequest` — `code`
- `MfaDisableRequest` — `code` or `recoveryCode`

**Profile:**
- `UserProfileResponse` — `publicId`, `name`, `email`, `companyCode`, `roles`, `mfaEnabled`, `mustChangePassword`, `createdAt`, `lastLoginAt`

### 5.2 Company/Tenant Payloads

**Tenant list/detail (super-admin):**
- `TenantSummaryDto` — `id`, `code`, `name`, `lifecycleState`, `runtimeState`, `activeUsers`, `maxUsers`, `modules[]`
- `TenantDetailDto` — extends with `createdAt`, `onboardingDate`, `supportWarnings[]`, `recentAdmins[]`

**Tenant lifecycle:**
- `TenantLifecycleRequest` — `targetState` (ACTIVE, SUSPENDED, DEACTIVATED), `reason`

**Tenant limits:**
- `TenantLimitsRequest` — `maxConcurrentRequests`, `maxRequestsPerMinute`, `maxActiveUsers`

**Module gating:**
- `TenantModulesRequest` — `enabledModules[]` (MANUFACTURING, HR_PAYROLL, PURCHASING, PORTAL, REPORTS_ADVANCED)

**Onboarding:**
- `TenantOnboardingRequest` — `companyCode`, `companyName`, `adminEmail`, `adminPassword`, `coaTemplateId`

### 5.3 Admin User Payloads

**User CRUD:**
- `CreateUserRequest` — `email`, `name`, `password`, `role`, `enabled`
- `UpdateUserRequest` — `name`, `role`, `enabled`
- `UserDto` — `publicId`, `email`, `name`, `role`, `enabled`, `mfaEnabled`, `lockedUntil`, `createdAt`

**User status:**
- `UpdateUserStatusRequest` — `enabled` (boolean)

**Force reset:**
- `ForceResetPasswordResponse` — success message (reset link sent)

### 5.4 System Settings Payloads

**Read (admin):**
- `SystemSettingsDto` — key-value map of all settings

**Write (super-admin):**
- `SystemSettingsUpdateRequest` — `settings` (key-value map), optional `lockPeriod` date

> ⚠️ **Super-admin guard on period lock**: When updating `/api/v1/admin/settings`, changing the `periodLockEnforced` setting requires `ROLE_SUPER_ADMIN`. A non-super-admin actor (including `ROLE_ADMIN`) attempting to change this specific setting will receive `AUTH_INSUFFICIENT_PERMISSIONS`.

### 5.5 Support Ticket Payloads (Shared DTO Contract)

> ⚠️ **Key Contract**: Support ticket DTOs are **shared** between admin and dealer surfaces. Both use the same `SupportTicketCreateRequest`, `SupportTicketResponse`, and `SupportTicketListResponse` from the `admin` module.

**Create (admin side):**
- `SupportTicketCreateRequest` — `category`, `subject`, `description`
- Response: `SupportTicketResponse` with `ticketId`, `status`, `createdAt`

> ⚠️ **Note**: The admin-side `SupportTicketCreateRequest` does NOT include a `dealerId` field. Support tickets are created within the tenant's company context and scoped to the authenticated user's company.

**Create (dealer side):**
- `SupportTicketCreateRequest` — `category`, `subject`, `description`
- Response: `SupportTicketResponse` — same structure, but auto-scoped to authenticated dealer

**List (admin side):**
- `SupportTicketListResponse` — `tickets[]`, `total`, `page`, `size`

**List (dealer side):**
- `SupportTicketListResponse` — same structure, filtered to dealer's own tickets

**Detail:**
- `SupportTicketResponse` — `ticketId`, `category`, `subject`, `description`, `status`, `createdAt`, `updatedAt`, `resolvedAt`, `createdBy`, `assignedTo`, `comments[]`

### 5.6 Approval Payloads

**Admin approvals list:**
- `AdminApprovalsResponse` — `items[]` (credit requests, credit overrides, payroll runs, period closes, export requests)
- `AdminApprovalItemDto` — `id`, `type`, `originType`, `ownerType`, `status`, `createdAt`, `createdBy`, `requesterUserId`, `requesterEmail`, `approveEndpoint`, `rejectEndpoint`

**Approval decision:**
- `ExportDecisionRequest` — `approved` (boolean), optional `rejectionReason`

### 5.7 Changelog Payloads

**List:**
- `ChangelogEntryResponse` — `id`, `title`, `content`, `highlighted`, `publishedAt`, `createdBy`

**Create/Update (super-admin):**
- `ChangelogEntryRequest` — `title`, `content`, `highlighted`, `publishNow`

---

## 6. Read/Write Boundaries

### 6.1 Auth/Session

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| Login | Public | Requires email+password+companyCode; may require MFA |
| Refresh token | Public (with valid refresh token) | Single-use rotation model |
| Logout | Authenticated | Revokes all user tokens and refresh tokens |
| View current user | Authenticated | Returns identity, roles, mustChangePassword flag |
| Change password | Authenticated | Requires current password (unless mustChangePassword=true) |
| Forgot password | Public | Rate-limited; scope-aware (requires companyCode) |
| Reset password | Public (with valid token) | Single-use; expires after 1 hour |
| MFA setup | Authenticated | Generates secret + recovery codes (one-time) |
| MFA activate | Authenticated | Confirms enrollment with valid TOTP code |
| MFA disable | Authenticated | Requires TOTP code or recovery code |

### 6.2 Company/Tenant

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| List companies | ADMIN, SALES, ACCOUNTING | Tenant-scoped (own company only) |
| Super-admin dashboard | SUPER_ADMIN | Global tenant metrics |
| List tenants | SUPER_ADMIN | Optional status filter |
| Tenant detail | SUPER_ADMIN | Full tenant state |
| Lifecycle transition | SUPER_ADMIN | ACTIVE ↔ SUSPENDED ↔ DEACTIVATED |
| Update limits | SUPER_ADMIN | Concurrent requests, rate limit, user quota |
| Module gating | SUPER_ADMIN | HR_PAYROLL defaults to disabled |
| Force logout | SUPER_ADMIN | Revokes all tenant user sessions |
| Onboard tenant | SUPER_ADMIN | Creates company + admin + CoA + period |

### 6.3 Admin User Management

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| List users | ADMIN, SUPER_ADMIN | Tenant-scoped |
| Create user | ADMIN, SUPER_ADMIN | Requires role assignment |
| Update user | ADMIN, SUPER_ADMIN | Name and role |
| Enable/disable user | ADMIN, SUPER_ADMIN | Status toggle |
| Force reset password | ADMIN, SUPER_ADMIN | Sends reset email |
| Suspend/unsuspend | ADMIN, SUPER_ADMIN | Temporary lock |
| Disable MFA | ADMIN, SUPER_ADMIN | Admin override |
| Delete user | ADMIN, SUPER_ADMIN | Hard delete |

### 6.4 Portal Insights

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| Dashboard insights | ADMIN only | Operations, sales, inventory summary |
| Operations insights | ADMIN only | Factory and production metrics |
| Workforce insights | ADMIN only | HR/Payroll metrics |

### 6.5 Portal Finance

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| Dealer ledger | ADMIN, ACCOUNTING | Requires `dealerId` query param |
| Dealer invoices | ADMIN, ACCOUNTING | Requires `dealerId` query param |
| Dealer aging | ADMIN, ACCOUNTING | Requires `dealerId` query param |

### 6.6 Dealer Portal

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| Dashboard | DEALER | Auto-scoped to authenticated dealer |
| Ledger | DEALER | Auto-scoped |
| Invoices | DEALER | Auto-scoped |
| Invoice PDF | DEALER | Own invoice only |
| Aging | DEALER | Auto-scoped |
| Orders | DEALER | Auto-scoped |
| Credit limit request | DEALER | Create for self |
| Support tickets | DEALER | Auto-scoped |

---

## 7. Company Lifecycle and Runtime Gating

### 7.1 Lifecycle States (Enforced on Every Request)

| State | GET/HEAD/OPTIONS | POST/PUT/DELETE/PATCH |
| --- | :---: | :---: |
| `ACTIVE` | Allowed | Allowed |
| `SUSPENDED` | Allowed | Denied (HTTP 403) |
| `DEACTIVATED` | Denied | Denied (HTTP 403) |

**Frontend impact:** When a tenant is suspended, mutating operations (create, update, delete, patch) will fail with 403. Read operations continue to work.

### 7.2 Runtime States (Separate from Lifecycle)

| Runtime State | Effect |
| --- | --- |
| `ACTIVE` | Normal operation |
| `HOLD` | Read-only — mutating requests rejected with HTTP 423 |
| `BLOCKED` | All requests rejected with HTTP 403 |

**Frontend impact:** Even when lifecycle is ACTIVE, runtime HOLD causes mutating requests to fail with 423 LOCKED.

### 7.3 Must-Change-Password Corridor

When `mustChangePassword=true` on a user, the corridor filter blocks all requests except:

| Method | Allowed Paths |
| --- | :---: |
| GET/HEAD | `/api/v1/auth/me`, `/api/v1/auth/profile` |
| POST | `/api/v1/auth/password/change`, `/api/v1/auth/logout`, `/api/v1/auth/refresh-token` |
| OPTIONS | All paths (CORS) |

All other requests receive HTTP 403 with `PASSWORD_CHANGE_REQUIRED` and `mustChangePassword: true`.

**Frontend impact:** When the user has `mustChangePassword=true`, redirect them to a password-change flow and block access to other features until they change their password.

### 7.4 Quota Enforcement at Login/Refresh

| Limit | Default | Enforcement Point |
| --- | :---: | :--- |
| `maxActiveUsers` | 500 | Login/refresh admission |
| `maxConcurrentRequests` | 200 | Per-request in-flight tracking |
| `maxRequestsPerMinute` | 5000 | Per-request rate limiting |

**Frontend impact:** If the tenant exceeds active-user quota, login is rejected. Display a meaningful error message to the user.

---

## 8. Host/Path Ownership Summary

| Surface | Host Family | Ownership |
| --- | :--- | :--- |
| Auth | `/api/v1/auth/**` | Auth module |
| Company/Tenant | `/api/v1/companies/**`, `/api/v1/superadmin/**` | Company module |
| Admin users | `/api/v1/admin/users/**` | Admin module |
| Admin settings | `/api/v1/admin/settings/**` | Admin module |
| Admin approvals | `/api/v1/admin/approvals/**`, `/api/v1/admin/exports/**` | Admin module |
| Admin roles | `/api/v1/admin/roles/**` | RBAC module |
| Portal insights | `/api/v1/portal/dashboard/**`, `/api/v1/portal/operations/**`, `/api/v1/portal/workforce/**` | Portal module |
| Portal finance | `/api/v1/portal/finance/**` | Portal module |
| Portal support tickets | `/api/v1/portal/support/tickets/**` | Admin module |
| Dealer portal | `/api/v1/dealer-portal/**` | Sales module (dealer-portal) |
| Dealer support tickets | `/api/v1/dealer-portal/support/tickets/**` | Sales module |
| Changelog | `/api/v1/changelog/**`, `/api/v1/superadmin/changelog/**` | Admin module |

---

## 9. Cross-Module Seams for Frontend Awareness

### 9.1 Auth → Company Handoff

- Login and refresh both call `TenantRuntimeRequestAdmissionService` to check tenant state and quota before issuing tokens
- If tenant is SUSPENDED, DEACTIVATED, or on HOLD/BLOCKED, login fails with appropriate error
- Frontend should handle tenant-state errors gracefully

### 9.2 Company → Auth Handoff

- Super-admin force-logout calls `TokenBlacklistService` and `RefreshTokenService` to revoke all user sessions
- Admin force-reset-password calls `PasswordResetService` and revokes all active tokens
- Frontend should handle session revocation (re-login prompt)

### 9.3 Portal → Sales Handoff

- Portal finance controller uses `DealerPortalService` to fetch dealer finance data
- Dealer-scoped data is fetched via dealer ID passed in query param (admin view) or via auth context (self-service)

### 9.4 Admin → RBAC Handoff

- Role management endpoints interact with `RoleService` for synchronization
- System roles are seeded at startup; custom roles are not supported
- Frontend cannot create arbitrary roles — only manage existing system roles

---

## 10. Known Safety Gaps for Frontend

| Gap | Description | Mitigation |
| --- | :--- | :--- |
| Password reset email delivery depends on mail config | If `erp.mail.enabled=false` or `erp.mail.send-password-reset=false`, forgot-password requests silently fail to deliver emails | Check mail configuration before relying on password reset flow |
| Security monitoring is in-memory | Failed login tracking, IP/user blocking, and suspicious activity scoring are lost on restart | Primary protection is database-backed lockout (5 attempts, 15 min) |
| Runtime counters reset on restart | In-flight and rate-limit counters do not survive restart | Quota enforcement may be temporarily bypassed after restart |
| Policy cache TTL is 15 seconds | Runtime policy changes take up to 15 seconds to propagate | Cache invalidation happens on policy update via API |
| MFA disable requires valid code | If user loses authenticator and recovery codes, admin must disable MFA | Admin can disable MFA via `/api/v1/admin/users/{id}/mfa/disable` |
| Must-change-password is not auto-expiring | No automatic password expiration that sets the flag | Flag must be set explicitly by admin or provisioning |

---

## 11. Deprecation Notes

| Surface | Status | Replacement |
| --- | :--- | :--- |
| Legacy `X-Company-Id` header | Rejected | Use `companyCode` in JWT claims or `X-Company-Code` header |
| Legacy `X-Idempotency-Key` (auth) | Rejected | Use canonical `Idempotency-Key` header |
| SALES role dispatch.confirm permission | Retired | Factory and admin handle dispatch confirmation |
| Custom role creation | Not supported | Only system roles (SystemRole enum) can be managed |

---

## Cross-References

- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/modules/auth.md](modules/auth.md) — auth module doc (login, refresh, logout, MFA, password reset, token revocation)
- [docs/modules/company.md](modules/company.md) — company module doc (tenant lifecycle, runtime admission, module gating, super-admin control plane)
- [docs/modules/admin-portal-rbac.md](modules/admin-portal-rbac.md) — admin/portal/RBAC module doc (user management, settings, approvals, portal insights/finance, dealer self-service, RBAC)
- [docs/flows/auth-identity.md](flows/auth-identity.md) — auth identity flow
- [docs/flows/tenant-admin-management.md](flows/tenant-admin-management.md) — tenant/admin management flow
- [docs/adrs/ADR-002-multi-tenant-auth-scoping.md](adrs/ADR-002-multi-tenant-auth-scoping.md) — ADR for multi-tenant auth scoping
- [docs/adrs/ADR-006-portal-and-host-boundary-separation.md](adrs/ADR-006-portal-and-host-boundary-separation.md) — ADR for portal/host boundary separation
- [docs/frontend-handoff-operations.md](frontend-handoff-operations.md) — operations handoff
- [docs/frontend-handoff-commercial.md](frontend-handoff-commercial.md) — commercial handoff
