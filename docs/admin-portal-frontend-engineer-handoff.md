# Admin Portal Frontend Handoff (Tasks 1 + 2 + 3)

Source of truth: `openapi.json` (OpenAPI 3.0.1), analyzed via `.claude/skills/openapi-frontend-endpoint-map/scripts/map_openapi_frontend.py`.


---

## Task 1: Admin Portal Endpoints + Expectations

Core modules: `auth-controller`, `mfa-controller`, `admin-user-controller`, `role-controller`, `admin-settings-controller`, `company-controller`, `multi-company-controller`, `user-profile-controller`.

### auth-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/auth/login` | create-form; req: companyCode (body), email (body), password (body); opt: mfaCode (body), recoveryCode (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/logout` | create-form; req: -; opt: refreshToken (query); states: loading, error, success | path=-; query=refreshToken; body=none; ct=- | ok 200; err - |
| `GET /api/v1/auth/me` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/auth/password/change` | create-form; req: confirmPassword (body), currentPassword (body), newPassword (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/password/forgot` | create-form; req: email (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/password/reset` | create-form; req: confirmPassword (body), newPassword (body), token (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/refresh-token` | create-form; req: companyCode (body), refreshToken (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### mfa-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/auth/mfa/activate` | create-form; req: code (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/mfa/disable` | create-form; req: -; opt: code (body), recoveryCode (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `POST /api/v1/auth/mfa/setup` | create-form; req: -; opt: -; states: loading, error, success | path=-; query=-; body=none; ct=- | ok 200; err - |

### admin-user-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/admin/users` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/admin/users` | create-form; req: companyIds (body), displayName (body), email (body), roles (body); opt: password (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `DELETE /api/v1/admin/users/{id}` | delete-action; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/admin/users/{id}` | edit-form; req: displayName (body), id (path); opt: companyIds (body), enabled (body), roles (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |
| `PATCH /api/v1/admin/users/{id}/mfa/disable` | edit-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PATCH /api/v1/admin/users/{id}/suspend` | edit-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PATCH /api/v1/admin/users/{id}/unsuspend` | edit-form; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |

### role-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/admin/roles` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/admin/roles` | create-form; req: description (body), name (body), permissions (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/admin/roles/{roleKey}` | detail-view; req: roleKey (path); opt: -; states: loading, error, success, empty | path=roleKey; query=-; body=none; ct=- | ok 200; err - |

### admin-settings-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/admin/approvals` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/admin/notify` | create-form; req: body (body), subject (body), to (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `GET /api/v1/admin/settings` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/admin/settings` | edit-form; req: -; opt: allowedOrigins (body), autoApprovalEnabled (body), mailBaseUrl (body), mailEnabled (body), mailFromAddress (body), periodLockEnforced (body), sendCredentials (body), sendPasswordReset (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### company-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/companies` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `POST /api/v1/companies` | create-form; req: code (body), name (body), timezone (body); opt: defaultGstRate (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |
| `DELETE /api/v1/companies/{id}` | delete-action; req: id (path); opt: -; states: loading, error, success | path=id; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/companies/{id}` | edit-form; req: code (body), id (path), name (body), timezone (body); opt: defaultGstRate (body); states: loading, error, success | path=id; query=-; body=required; ct=application/json | ok 200; err - |

### multi-company-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `POST /api/v1/multi-company/companies/switch` | create-form; req: companyCode (body); opt: -; states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### user-profile-controller

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/auth/profile` | list-view; req: -; opt: -; states: loading, error, success, empty | path=-; query=-; body=none; ct=- | ok 200; err - |
| `PUT /api/v1/auth/profile` | edit-form; req: -; opt: displayName (body), jobTitle (body), phoneSecondary (body), preferredName (body), profilePictureUrl (body), secondaryEmail (body); states: loading, error, success | path=-; query=-; body=required; ct=application/json | ok 200; err - |

### Optional Admin Dashboard/System Endpoints

| Endpoint | Frontend should put | Backend expects | Returns |
|---|---|---|---|
| `GET /api/v1/orchestrator/dashboard/admin` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/v1/orchestrator/dashboard/factory` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/v1/orchestrator/dashboard/finance` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/integration/health` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/v1/portal/dashboard` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/v1/portal/operations` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |
| `GET /api/v1/portal/workforce` | list-view; states: loading, error, success, empty | path=-; query=-; body=none | ok 200; err - |

OpenAPI gaps to resolve before UI freeze:
- Security is mostly unspecified in spec; finalize auth + RBAC matrix outside OpenAPI.
- Most endpoints document only `200`; define standard `4xx/5xx` error body for frontend.
- List endpoints do not document pagination/search/filter params.

---

## Task 2: Frontend API Inventory (Grouped by Domain)

### Auth / Session

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `authLogin` | POST | `/api/v1/auth/login` | companyCode (body), email (body), password (body) | mfaCode (body), recoveryCode (body) | No | No | No |
| `authLogout` | POST | `/api/v1/auth/logout` | - | refreshToken (query) | No | No | No |
| `authGetMe` | GET | `/api/v1/auth/me` | - | - | Yes | No | Yes |
| `authChangePassword` | POST | `/api/v1/auth/password/change` | confirmPassword (body), currentPassword (body), newPassword (body) | - | No | No | No |
| `authForgotPassword` | POST | `/api/v1/auth/password/forgot` | email (body) | - | No | No | No |
| `authResetPassword` | POST | `/api/v1/auth/password/reset` | confirmPassword (body), newPassword (body), token (body) | - | No | No | No |
| `authRefreshToken` | POST | `/api/v1/auth/refresh-token` | companyCode (body), refreshToken (body) | - | No | No | No |

### MFA / Security

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `mfaActivate` | POST | `/api/v1/auth/mfa/activate` | code (body) | - | No | No | No |
| `mfaDisable` | POST | `/api/v1/auth/mfa/disable` | - | code (body), recoveryCode (body) | No | No | No |
| `mfaSetup` | POST | `/api/v1/auth/mfa/setup` | - | - | No | No | No |

### Users

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `adminUsersList` | GET | `/api/v1/admin/users` | - | - | Yes | No | Yes |
| `adminUsersCreate` | POST | `/api/v1/admin/users` | companyIds (body), displayName (body), email (body), roles (body) | password (body) | No | No | No |
| `adminUsersDelete` | DELETE | `/api/v1/admin/users/{id}` | id (path) | - | No | No | Yes |
| `adminUsersUpdate` | PUT | `/api/v1/admin/users/{id}` | displayName (body), id (path) | companyIds (body), enabled (body), roles (body) | No | No | Yes |
| `adminUsersMfaDisable` | PATCH | `/api/v1/admin/users/{id}/mfa/disable` | id (path) | - | No | No | Conditional |
| `adminUsersSuspend` | PATCH | `/api/v1/admin/users/{id}/suspend` | id (path) | - | No | No | Conditional |
| `adminUsersUnsuspend` | PATCH | `/api/v1/admin/users/{id}/unsuspend` | id (path) | - | No | No | Conditional |

### Roles / Permissions

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `adminRolesList` | GET | `/api/v1/admin/roles` | - | - | Yes | No | Yes |
| `adminRolesCreate` | POST | `/api/v1/admin/roles` | description (body), name (body), permissions (body) | - | No | No | No |
| `adminRolesGetByKey` | GET | `/api/v1/admin/roles/{roleKey}` | roleKey (path) | - | Yes | No | Yes |

### Settings / Approvals

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `adminApprovalsList` | GET | `/api/v1/admin/approvals` | - | - | Yes | No | Yes |
| `adminNotifyUser` | POST | `/api/v1/admin/notify` | body (body), subject (body), to (body) | - | No | No | No |
| `adminSettingsGet` | GET | `/api/v1/admin/settings` | - | - | Yes | No | Yes |
| `adminSettingsUpdate` | PUT | `/api/v1/admin/settings` | - | allowedOrigins (body), autoApprovalEnabled (body), mailBaseUrl (body), mailEnabled (body), mailFromAddress (body), periodLockEnforced (body), sendCredentials (body), sendPasswordReset (body) | No | No | Yes |

### Masters (Company / Tenancy)

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `companiesList` | GET | `/api/v1/companies` | - | - | Yes | No | Yes |
| `companiesCreate` | POST | `/api/v1/companies` | code (body), name (body), timezone (body) | defaultGstRate (body) | No | No | No |
| `companiesDelete` | DELETE | `/api/v1/companies/{id}` | id (path) | - | No | No | Yes |
| `companiesUpdate` | PUT | `/api/v1/companies/{id}` | code (body), id (path), name (body), timezone (body) | defaultGstRate (body) | No | No | Yes |
| `companiesSwitch` | POST | `/api/v1/multi-company/companies/switch` | companyCode (body) | - | No | No | No |

### Profile

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `profileGet` | GET | `/api/v1/auth/profile` | - | - | Yes | No | Yes |
| `profileUpdate` | PUT | `/api/v1/auth/profile` | - | displayName (body), jobTitle (body), phoneSecondary (body), preferredName (body), profilePictureUrl (body), secondaryEmail (body) | No | No | Yes |

### Admin Observability (Optional)

| Function | Method | Path | Required params | Optional params | Cache | Debounce | Idempotent |
|---|---|---|---|---|---|---|---|
| `dashboardAdminGet` | GET | `/api/v1/orchestrator/dashboard/admin` | - | - | Yes | No | Yes |
| `dashboardFactoryGet` | GET | `/api/v1/orchestrator/dashboard/factory` | - | - | Yes | No | Yes |
| `dashboardFinanceGet` | GET | `/api/v1/orchestrator/dashboard/finance` | - | - | Yes | No | Yes |
| `integrationHealthGet` | GET | `/api/integration/health` | - | - | Yes | No | Yes |
| `portalDashboardGet` | GET | `/api/v1/portal/dashboard` | - | - | Yes | No | Yes |
| `portalOperationsGet` | GET | `/api/v1/portal/operations` | - | - | Yes | No | Yes |
| `portalWorkforceGet` | GET | `/api/v1/portal/workforce` | - | - | Yes | No | Yes |

Unsafe / inconsistent endpoint signals:
- `POST /api/v1/auth/logout` uses `refreshToken` in query string (token exposure risk in logs/history/referrers).
- Generated operationIds exist (`list_2`, `create_2`, `update_2`, etc.), unstable for SDK naming.
- `PUT /api/v1/admin/settings` body is required but schema has no required fields (looks PATCH-like).
- Most mutating endpoints document only `200`; no explicit `400/401/403/404/409/500` contracts.
- Security declarations are mostly absent in OpenAPI for admin endpoints.

---

## Task 3: Users & Access Management Route Plan

### Assumptions (Explicit)
1. Use `GET /api/v1/auth/me` as session + permission source (`data.permissions`).
2. Permission codes are not documented; route gates below are proposed names.
3. No `GET /api/v1/admin/users/{id}` exists; edit page must hydrate from users list cache or route payload.
4. No dedicated permission-catalog endpoint exists; role permission options need config/seed/static list.
5. Error response schema is not standardized in spec; UI should support generic fallback errors.

### Route Map (Admin UI URLs)
| Route | Purpose | Suggested gate |
|---|---|---|
| `/auth/login` | Admin sign-in | Public |
| `/auth/forgot-password` | Start password reset | Public |
| `/auth/reset-password` | Complete password reset | Public |
| `/admin/access/users` | Users list + actions | `users.read` |
| `/admin/access/users/new` | Create user | `users.write` (+ `roles.read`, `companies.read`) |
| `/admin/access/users/:id/edit` | Edit user + suspend/unsuspend/MFA disable/delete | `users.write` (+ action-specific gates) |
| `/admin/access/roles` | Roles list | `roles.read` |
| `/admin/access/roles/new` | Create role | `roles.write` |
| `/admin/access/roles/:roleKey` | Role detail | `roles.read` |
| `/admin/access/profile` | My profile + password change | Authenticated |
| `/admin/access/security/mfa` | MFA setup/activate/disable (self) | Authenticated |

### Universal Profile Controls (Global Header Profile Menu)

Apply these controls on all authenticated admin routes (users, roles, settings, masters, dashboards).

| UI control label | Target | API calls | Loading state | Error state | Gate | Notes |
|---|---|---|---|---|---|---|
| `My Profile` | `/admin/access/profile` | `profileGet` | Route skeleton + form skeleton | Inline form errors + toast | Authenticated | Canonical profile entry point |
| `Change Password` | `/admin/access/profile?tab=password` | `authChangePassword` on submit | Submit button spinner + disabled submit | Inline validation + toast | Authenticated | Keep inside profile page, password tab/section |
| `Security & MFA` | `/admin/access/security/mfa` | `mfaSetup`, `mfaActivate`, `mfaDisable` | Setup payload loader + action button spinners | Invalid code/recovery errors inline + toast | Authenticated | Require at least one of `code` or `recoveryCode` for disable flow |
| `Switch Company` | Company switch modal (stays on current route) | `companiesList`, `companiesSwitch` | Modal list loader + confirm spinner | Modal error + toast | Authenticated + multi-company access | On success, refresh auth/session context and invalidate user-scoped caches |
| `Sign Out` | Redirect to `/auth/login` | `authLogout` | Immediate button spinner | Fallback to local sign-out if API fails | Authenticated | Backend takes `refreshToken` via query in current spec |

Button naming standard for frontend text (use exactly these labels):
- `My Profile`
- `Change Password`
- `Security & MFA`
- `Switch Company`
- `Sign Out`

### Per-Route Delivery Spec

#### `/admin/access/users`
- Required API calls: `authGetMe`, `adminUsersList`
- Loading: page skeleton + table skeleton rows
- Empty: no users row state + “Create user” CTA
- Error: retry block + toast
- Suggested table columns (from `UserDto`): `displayName`, `email`, `enabled`, `mfaEnabled`, `roles`, `companies`, `publicId`
- Gate: `users.read` (row actions additionally gated)

#### `/admin/access/users/new`
- Required API calls: `authGetMe`, `adminRolesList`, `companiesList`, `adminUsersCreate`
- Loading: form disabled until roles/companies load; submit spinner
- Empty: no roles/companies available blocks submit
- Error: inline validation + submission toast
- Form fields (from `CreateUserRequest`):
  - Required: `email`, `displayName`, `companyIds[]`, `roles[]`
  - Optional: `password`
- Gate: `users.write`

#### `/admin/access/users/:id/edit`
- Required API calls: `authGetMe`, `adminUsersList` (hydrate selected user), `adminRolesList`, `companiesList`, `adminUsersUpdate`, `adminUsersSuspend`, `adminUsersUnsuspend`, `adminUsersMfaDisable`, `adminUsersDelete`
- Loading: initial skeleton + action-level button spinners
- Empty: user not found
- Error: action failure toast + optimistic rollback
- Form fields (from `UpdateUserRequest`):
  - Required: `displayName`
  - Optional: `companyIds[]`, `roles[]`, `enabled`
- Gate: `users.write` + action-specific gates (`users.suspend`, `users.mfa.disable`, `users.delete`)

#### `/admin/access/roles`
- Required API calls: `authGetMe`, `adminRolesList`
- Loading: table skeleton
- Empty: no roles configured
- Error: table-level retry
- Suggested table columns (from `RoleDto`): `name`, `description`, `permissionsCount`, `permissionCodesPreview`
- Gate: `roles.read`

#### `/admin/access/roles/new`
- Required API calls: `authGetMe`, `adminRolesList` (optional source), `adminRolesCreate`
- Loading: permission options load state
- Empty: no permission catalog; fallback to manual permission-code entry
- Error: submit toast, preserve dirty form
- Form fields (from `CreateRoleRequest`): required `name`, `description`, `permissions[]`
- Gate: `roles.write`

#### `/admin/access/roles/:roleKey`
- Required API calls: `authGetMe`, `adminRolesGetByKey`
- Loading: detail skeleton
- Empty: role not found
- Error: retry state
- Suggested detail sections (from `RoleDto` + `PermissionDto`):
  - Role header: `name`, `description`
  - Permissions table columns: `code`, `description`
- Gate: `roles.read`
- Note: no update/delete role endpoints in current OpenAPI.

#### `/admin/access/profile`
- Required API calls: `authGetMe`, `profileGet`, `profileUpdate`, `authChangePassword`
- Loading: form skeleton + submit spinner
- Empty: not applicable
- Error: inline field errors + toast
- Form fields (from `UpdateProfileRequest`): `displayName`, `preferredName`, `jobTitle`, `profilePictureUrl`, `phoneSecondary`, `secondaryEmail`
- Password form fields: `currentPassword`, `newPassword`, `confirmPassword`
- Gate: authenticated user

#### `/admin/access/security/mfa`
- Required API calls: `authGetMe`, `mfaSetup`, `mfaActivate`, `mfaDisable`
- Loading: setup payload load + submit spinners
- Empty: not applicable
- Error: invalid code/recovery handling with inline errors
- Form fields:
  - Activate: required `code`
  - Disable: optional `code` or `recoveryCode` (at least one required by UI rule)
- Gate: authenticated user

## Delta Update (2026-02-13): Explicit Admin Approval Payload Contract

Admin approvals are now explicit action items; frontend must render action metadata from payload (not hardcoded by type only).

Queue endpoint:
- `GET /api/v1/admin/approvals`

Response shape:
- `data.creditRequests[]` and `data.payrollRuns[]` (`AdminApprovalItemDto` rows).

`AdminApprovalItemDto` fields to use directly:
- `type`, `id`, `publicId`, `reference`, `status`, `summary`,
- `actionType`, `actionLabel`, `sourcePortal`,
- `approveEndpoint`, `rejectEndpoint`, `createdAt`.

### Action-Type Matrix (Current Backend Contract)

- `type=CREDIT_REQUEST`
  - `actionType=APPROVE_DEALER_CREDIT_REQUEST`
  - `sourcePortal=DEALER_PORTAL`
  - `approveEndpoint=/api/v1/sales/credit-requests/{id}/approve`
  - `rejectEndpoint=/api/v1/sales/credit-requests/{id}/reject`

- `type=CREDIT_LIMIT_OVERRIDE_REQUEST`
  - `actionType=APPROVE_DISPATCH_CREDIT_OVERRIDE`
  - `sourcePortal=SALES_PORTAL` or `FACTORY_PORTAL` (derived by backend from source context)
  - `approveEndpoint=/api/v1/credit/override-requests/{id}/approve`
  - `rejectEndpoint=/api/v1/credit/override-requests/{id}/reject`

- `type=PAYROLL_RUN`
  - `actionType=APPROVE_PAYROLL_RUN`
  - `sourcePortal=HR_PORTAL`
  - `approveEndpoint=/api/v1/payroll/runs/{id}/approve`
  - `rejectEndpoint=null` (do not show reject button)

### FE Rendering Rules

- Group tabs by response arrays:
  - Credit approvals: `data.creditRequests`
  - Payroll approvals: `data.payrollRuns`
- Primary CTA label must use `actionLabel` from payload.
- Secondary reject CTA only when `rejectEndpoint` is non-null.
- Show `summary` as canonical “what admin is approving” line in row + detail drawer.
- Use optimistic row lock while action call in-flight; refresh queue immediately after action completion.
