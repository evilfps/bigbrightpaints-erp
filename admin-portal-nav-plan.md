# Admin Portal Navigation Plan (Enterprise, Minimal Sidebar)

Goal: give `ROLE_ADMIN` a clean, low-menu admin portal that still covers everything the backend exposes for admin operations (users, roles, settings, approvals) and provides safe jump-off links to the other portals.

## Sidebar (Keep It To 5)

1) Overview
2) Access
3) Approvals
4) Settings
5) Diagnostics

Everything else lives as **tabs** or **drawers** inside these pages (to avoid sidebar sprawl).

## Top Bar (Global)

- Company selector (tenant switch)
  - API: `POST /api/v1/multi-company/companies/switch`
  - UX: search by company code, show current company badge.
- Quick links (opens in same app or redirects to portal routes)
  - Sales Portal, Accounting Portal, Manufacturing Portal, Dealer Portal (for support), Reports.
- Current user menu
  - API: `GET /api/v1/auth/me` (roles + permissions)
  - API: `GET/PUT /api/v1/auth/profile` (profile)
  - API: `POST /api/v1/auth/mfa/*` (MFA self-service)

---

## 1) Overview

Route: `/admin`

Purpose: show admin-only insights and the most urgent actions.

### Sections

- Admin Insights
  - API: `GET /api/v1/portal/dashboard`
  - Show: high-level metrics snapshot.

- Operations Insights
  - API: `GET /api/v1/portal/operations`
  - Show: operational summary (read-only).

- Workforce Insights
  - API: `GET /api/v1/portal/workforce`
  - Show: workforce summary (read-only).

- Pending Approvals (1 glance)
  - API: `GET /api/v1/admin/approvals`
  - Show: counts + latest items (credit requests, payroll runs).
  - Action: “Open approvals”.

- System Health (quick)
  - API: `GET /api/v1/accounting/configuration/health`
  - Show: pass/warn/fail summary and top issues.

---

## 2) Access

Route: `/admin/access`

Tabs:
- Users
- Roles

### 2.1 Users

Route: `/admin/access/users`

#### List + Search + Filters
- API: `GET /api/v1/admin/users`
- UI fields (from `UserDto`): email, displayName, enabled, mfaEnabled, roles[], companies[]
- Filters (client-side unless backend adds query params): enabled, role, company.

#### Row Actions (recommended)

- Disable MFA (forces re-auth; clears MFA secret)
  - API: `PATCH /api/v1/admin/users/{id}/mfa/disable`
- Suspend / Unsuspend (forces re-auth on suspend)
  - API: `PATCH /api/v1/admin/users/{id}/suspend`
  - API: `PATCH /api/v1/admin/users/{id}/unsuspend`
- Send Password Reset Link (recommended “safe resend”)
  - API: `POST /api/v1/auth/password/forgot`
  - Body: `{ "email": "user@company.com" }`
  - Notes:
    - Endpoint intentionally returns success even if the email does not exist (anti-enumeration).
    - Email delivery depends on admin settings: mail enabled + `sendPasswordReset=true`.

#### Create User (modal)
- API: `POST /api/v1/admin/users`
- Request (`CreateUserRequest`):
  - email
  - password (optional)
  - displayName
  - companyIds[]
  - roles[]
- UX:
  - “Auto-generate password” toggle (sends empty/null password).
  - Company multi-select (needs company list; see below).
  - Roles multi-select.
 - Behavior:
   - If password is empty/null, backend generates a temporary password and sets `mustChangePassword=true`.
   - Backend attempts to send a credentials email on create (admin settings: mail enabled + `sendCredentials=true`).

#### Edit User (drawer)
- API: `PUT /api/v1/admin/users/{id}`
- Request (`UpdateUserRequest`): displayName, companyIds?, roles?, enabled?

#### Suspend / Unsuspend
- API: `PATCH /api/v1/admin/users/{id}/suspend`
- API: `PATCH /api/v1/admin/users/{id}/unsuspend`

#### Disable MFA
- API: `PATCH /api/v1/admin/users/{id}/mfa/disable`

#### Delete User
- API: `DELETE /api/v1/admin/users/{id}`

#### Dependencies
- Company list for assigning companyIds:
  - API: `GET /api/v1/companies` (admin has access)

### 2.2 Roles

Route: `/admin/access/roles`

#### Roles list
- API: `GET /api/v1/admin/roles`
- UI: role name, description, permissions count, expand to show permissions.

#### Role detail
- API: `GET /api/v1/admin/roles/{roleKey}`
- UI: permissions list (code + description).

#### Create Role (modal)
- API: `POST /api/v1/admin/roles`
- Request (`CreateRoleRequest`): name, description, permissions[] (permission codes)

#### Permission picker strategy (backend reality)
There is no “list all permissions” endpoint. For the UI:
- Build a picker by taking the union of permissions returned by `GET /api/v1/admin/roles`, plus any known static permissions.
- Known named permission used in code: `dispatch.confirm` (required by `POST /api/v1/dispatch/confirm`).

---

## 3) Approvals

Route: `/admin/approvals`

Purpose: a single place to action admin-only approvals and hand off to accounting/sales portals where needed.

### Sections

- Credit Requests Approval Queue
  - API: `GET /api/v1/admin/approvals`
  - Source: `creditRequests[]` items
  - Item contract (important):
    - `type="CREDIT_REQUEST"`: classic dealer credit-limit increase request.
      - approve/reject via legacy credit-request workflow.
      - `reference` format: `CR-<id>`.
    - `type="CREDIT_LIMIT_OVERRIDE_REQUEST"`: dispatch/sales override request to exceed credit during fulfillment.
      - approve: `POST /api/v1/credit/override-requests/{id}/approve`
      - reject: `POST /api/v1/credit/override-requests/{id}/reject`
      - `reference` preference: slip number -> sales order number -> `CLO-<id>` fallback.
    - UI must route actions by `type` and not assume all `creditRequests[]` rows share one approval endpoint.
    - Use `summary` as the admin-facing action sentence (it now states the exact approval intent and target reference).
    - `reference` is display-only; action APIs must use `item.id` path params for approve/reject calls.
    - New explicit action-routing fields on every item:
      - `actionType`: immutable action code (`APPROVE_DEALER_CREDIT_REQUEST`, `APPROVE_DISPATCH_CREDIT_OVERRIDE`, `APPROVE_PAYROLL_RUN`).
      - `actionLabel`: human action caption for buttons.
      - `sourcePortal`: origin context (`DEALER_PORTAL`, `SALES_PORTAL`, `FACTORY_PORTAL`, `HR_PORTAL`).
      - `approveEndpoint` / `rejectEndpoint`: server-declared API route templates (`{id}` path placeholder).
    - For `CREDIT_REQUEST` rows:
      - approve: `POST /api/v1/sales/credit-requests/{id}/approve`
      - reject: `POST /api/v1/sales/credit-requests/{id}/reject`
  - Actions depend on where the approval is implemented:
    - Credit limit overrides:
      - API: `GET /api/v1/credit/override-requests`
      - API: `POST /api/v1/credit/override-requests/{id}/approve`
      - API: `POST /api/v1/credit/override-requests/{id}/reject`
  - Note: `AdminSettingsController` approvals list is read-only and aggregates multiple types; some actions live in their own controllers.

- Payroll Runs Approval/Posting Shortcuts
  - Payroll workflow actions:
    - API: `POST /api/v1/payroll/runs/{id}/approve`
    - API: `POST /api/v1/payroll/runs/{id}/post`
    - API: `POST /api/v1/payroll/runs/{id}/mark-paid`

---

## 4) Settings

Route: `/admin/settings`

Tabs:
- System
- Notifications
- Company

### 4.1 System

- Runtime system settings
  - API: `GET /api/v1/admin/settings`
  - API: `PUT /api/v1/admin/settings`
  - Fields:
    - CORS allowed origins
    - Auto-approval enabled
    - Period lock enforced
    - Mail enabled/from/baseUrl
    - sendCredentials, sendPasswordReset

### 4.2 Notifications

- Send manual email
  - API: `POST /api/v1/admin/notify`
  - Request: AdminNotifyRequest (to/subject/body)
  - UX: template dropdown (optional on frontend), preview, send.

### 4.3 Company

- Company directory
  - API: `GET /api/v1/companies`
  - UI: list company name/code/timezone/defaultGstRate.

- Edit company (admin only)
  - API: `PUT /api/v1/companies/{id}`

- Company switch
  - API: `POST /api/v1/multi-company/companies/switch`

---

## 5) Diagnostics

Route: `/admin/diagnostics`

Purpose: fast troubleshooting without giving admin a massive accounting sidebar.

### Sections

- Configuration Health
  - API: `GET /api/v1/accounting/configuration/health`

- Quick Financial Reports (read-only shortcuts)
  - API: `GET /api/v1/reports/balance-sheet`
  - API: `GET /api/v1/reports/profit-loss`
  - API: `GET /api/v1/reports/cash-flow`
  - API: `GET /api/v1/reports/trial-balance`
  - API: `GET /api/v1/reports/inventory-valuation`
  - API: `GET /api/v1/reports/inventory-reconciliation`
  - UX: “Open in Accounting Portal” deep-link for the full experience.

- Audit Digest (exports)
  - API: `GET /api/v1/accounting/audit/digest`
  - API: `GET /api/v1/accounting/audit/digest.csv`

- Dispatch Confirm Permission Check
  - Show whether current admin user has `dispatch.confirm` authority.
  - Notes:
    - Backend requires both: role (`ROLE_ADMIN` or `ROLE_FACTORY`) AND authority (`dispatch.confirm`).

---

## Minimal Route Map

- `/admin` (Overview)
- `/admin/access` → `/admin/access/users`, `/admin/access/roles`
- `/admin/approvals`
- `/admin/settings` → `/admin/settings/system`, `/admin/settings/notifications`, `/admin/settings/company`
- `/admin/diagnostics`

---

## Frontend Route Blueprint (Copy/Paste)

Use this as the canonical frontend route map for the Admin portal. It matches the “minimal sidebar” structure and lists the primary APIs each route needs.

| Route | Nav Group | Page | Required Role | Primary APIs (on load) | Primary APIs (actions) |
|---|---|---|---|---|---|
| `/admin` | Overview | Overview | `ROLE_ADMIN` | `GET /api/v1/portal/dashboard`, `GET /api/v1/portal/operations`, `GET /api/v1/portal/workforce`, `GET /api/v1/admin/approvals`, `GET /api/v1/accounting/configuration/health` | — |
| `/admin/access/users` | Access | Users | `ROLE_ADMIN` | `GET /api/v1/admin/users`, `GET /api/v1/companies`, `GET /api/v1/admin/roles` | `POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}`, `PATCH /api/v1/admin/users/{id}/suspend`, `PATCH /api/v1/admin/users/{id}/unsuspend`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, `DELETE /api/v1/admin/users/{id}`, `POST /api/v1/auth/password/forgot` |
| `/admin/access/roles` | Access | Roles | `ROLE_ADMIN` | `GET /api/v1/admin/roles` | `POST /api/v1/admin/roles`, `GET /api/v1/admin/roles/{roleKey}` |
| `/admin/approvals` | Approvals | Approvals | `ROLE_ADMIN` | `GET /api/v1/admin/approvals` | `POST /api/v1/credit/override-requests/{id}/approve`, `POST /api/v1/credit/override-requests/{id}/reject`, `POST /api/v1/payroll/runs/{id}/approve`, `POST /api/v1/payroll/runs/{id}/post`, `POST /api/v1/payroll/runs/{id}/mark-paid` |
| `/admin/settings/system` | Settings | System | `ROLE_ADMIN` | `GET /api/v1/admin/settings` | `PUT /api/v1/admin/settings` |
| `/admin/settings/notifications` | Settings | Notifications | `ROLE_ADMIN` | — | `POST /api/v1/admin/notify` |
| `/admin/settings/company` | Settings | Company | `ROLE_ADMIN` | `GET /api/v1/companies` | `PUT /api/v1/companies/{id}`, `POST /api/v1/multi-company/companies/switch` |
| `/admin/diagnostics` | Diagnostics | Diagnostics | `ROLE_ADMIN` | `GET /api/v1/accounting/configuration/health` | optional shortcuts: `GET /api/v1/reports/*`, `GET /api/v1/accounting/audit/digest*` |

### Global bootstrap (recommended)

On app start (or after login), fetch once and cache:
- `GET /api/v1/auth/me` (roles + permissions)

### Suggested route structure (framework-agnostic)

- Root layout: `/admin` is a layout route with:\n  - sidebar (5 items)\n  - top bar (company switch, user menu)\n  - nested content outlet\n+
- Nested pages:\n  - Overview: `/admin`\n  - Access: `/admin/access/users`, `/admin/access/roles`\n  - Approvals: `/admin/approvals`\n  - Settings: `/admin/settings/system`, `/admin/settings/notifications`, `/admin/settings/company`\n  - Diagnostics: `/admin/diagnostics`\n+
### React Router example (route config)

```ts
// Example: react-router route objects
export const adminRoutes = [
  { path: "/admin", element: "<AdminLayout/>", children: [
    { index: true, element: "<AdminOverview/>" },
    { path: "access/users", element: "<AdminUsers/>" },
    { path: "access/roles", element: "<AdminRoles/>" },
    { path: "approvals", element: "<AdminApprovals/>" },
    { path: "settings/system", element: "<AdminSettingsSystem/>" },
    { path: "settings/notifications", element: "<AdminSettingsNotifications/>" },
    { path: "settings/company", element: "<AdminSettingsCompany/>" },
    { path: "diagnostics", element: "<AdminDiagnostics/>" },
  ]},
];
```

## UI Rules (Frontend Dev Friendly)

- Hide nav items if user lacks required role.
- If a user can open a page but fails an endpoint call, show an “Access denied” state with the failing endpoint and required role.
- Prefer tabs/drawers for sub-features rather than expanding sidebar.

## Known Backend Constraints / Notes

- Roles UI: no dedicated endpoint to list all permissions; build the permission picker from existing roles’ permission codes.
- Approvals: `GET /api/v1/admin/approvals` aggregates items; some approval actions live in module-specific controllers.

---

## Admin Portal API Wiring (Concrete Contracts)

This section is meant for frontend devs: exact endpoints + minimal request payloads.

### Identity / Tenant Context

- Get current user + roles + permissions
  - `GET /api/v1/auth/me`
  - Response: `ApiResponse<MeResponse>` (includes `roles[]` and `permissions[]`)

- Switch active company
  - `POST /api/v1/multi-company/companies/switch`
  - Body:
    ```json
    { "companyCode": "ACME" }
    ```

### Users (`ROLE_ADMIN`)

- List users
  - `GET /api/v1/admin/users`

- Create user
  - `POST /api/v1/admin/users`
  - Body:
    ```json
    {
      "email": "user@company.com",
      "password": "",
      "displayName": "User Name",
      "companyIds": [1],
      "roles": ["ROLE_ACCOUNTING"]
    }
    ```
  - Notes:
    - Backend enforces assignment to the *active company only* (even though `companyIds` is a list). Frontend should send `[currentCompanyId]`.
    - If `password` is empty/null, backend generates a temporary password and sets `mustChangePassword=true`.

- Update user
  - `PUT /api/v1/admin/users/{id}`
  - Body:
    ```json
    {
      "displayName": "Updated Name",
      "companyIds": [1],
      "roles": ["ROLE_SALES"],
      "enabled": true
    }
    ```

- Suspend user
  - `PATCH /api/v1/admin/users/{id}/suspend`

- Unsuspend user
  - `PATCH /api/v1/admin/users/{id}/unsuspend`

- Disable MFA
  - `PATCH /api/v1/admin/users/{id}/mfa/disable`

- Delete user
  - `DELETE /api/v1/admin/users/{id}`

### Password Reset (used by Admin UI as “Send Reset Link”)

- Request password reset email
  - `POST /api/v1/auth/password/forgot`
  - Body:
    ```json
    { "email": "user@company.com" }
    ```
  - Notes:
    - Always responds with success message to avoid user enumeration.
    - Email sending is controlled by settings: mail enabled + `sendPasswordReset=true`.
    - Reset link is built from admin setting `mailBaseUrl`; if blank/misconfigured the link in the email will be wrong.

### Roles (`ROLE_ADMIN`)

- List roles
  - `GET /api/v1/admin/roles`

- Get role details (by key)
  - `GET /api/v1/admin/roles/{roleKey}`
  - Example: `/api/v1/admin/roles/role_accounting` or `/api/v1/admin/roles/ROLE_ACCOUNTING`

- Create role
  - `POST /api/v1/admin/roles`
  - Body:
    ```json
    {
      "name": "ROLE_CUSTOM",
      "description": "Custom role",
      "permissions": ["dispatch.confirm"]
    }
    ```

### Admin Settings (`ROLE_ADMIN`)

- Get settings
  - `GET /api/v1/admin/settings`

- Update settings
  - `PUT /api/v1/admin/settings`
  - Body (all optional):
    ```json
    {
      "allowedOrigins": ["http://localhost:3002"],
      "autoApprovalEnabled": true,
      "periodLockEnforced": true,
      "mailEnabled": true,
      "mailFromAddress": "noreply@company.com",
      "mailBaseUrl": "https://erp.company.com",
      "sendCredentials": true,
      "sendPasswordReset": true
    }
    ```

- Send manual email
  - `POST /api/v1/admin/notify`
  - Body:
    ```json
    { "to": "user@company.com", "subject": "Subject", "body": "Message" }
    ```

### Approvals / Insights (`ROLE_ADMIN`)

- Aggregated approvals list
  - `GET /api/v1/admin/approvals`

- Admin insights
  - `GET /api/v1/portal/dashboard`
  - `GET /api/v1/portal/operations`
  - `GET /api/v1/portal/workforce`

### Company Directory (`ROLE_ADMIN`)

- List companies (available to assign users)
  - `GET /api/v1/companies`

- Update company
  - `PUT /api/v1/companies/{id}`

### Diagnostics (Admin shortcuts)

- Accounting configuration health
  - `GET /api/v1/accounting/configuration/health`
