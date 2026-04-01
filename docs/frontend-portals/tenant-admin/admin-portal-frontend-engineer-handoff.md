# Admin Portal Frontend Engineer Handoff

This document is the concrete backend-to-frontend contract for the **tenant admin portal**.

It is intentionally focused on the **admin portal only**.

## Portal role

- Primary role: `ROLE_ADMIN`
- Optional elevated role in same shell: `ROLE_SUPER_ADMIN`

Use `GET /api/v1/auth/me` as the source of truth for:

- role gating
- sidebar visibility
- screen access
- action/button visibility

---

## What frontend owns

Frontend owns:

- layout
- information architecture
- grouping
- tabs
- sidebar
- naming
- UX polish
- loading/empty/error states

Backend defines:

- accessible routes
- request payloads
- response payloads
- permission boundaries

---

## Session and auth rules

Frontend should provide basic self-service UX only:

- login
- logout
- profile
- change password
- forgot password
- reset password
- MFA setup / enable / disable

Important:

- refresh token handling should be automatic in browser/app code
- user should not manually manage refresh tokens
- after login, frontend should immediately bootstrap with `GET /api/v1/auth/me`

---

## Global response contract

Most endpoints return:

```ts
type ApiResponse<T> = {
  success: boolean
  message: string | null
  data: T | null
  timestamp: string
}
```

Most list screens should unwrap `data` and ignore `timestamp` except for diagnostics.

Exceptions:

- `POST /api/v1/auth/login` returns plain `AuthResponse`
- `POST /api/v1/auth/refresh-token` returns plain `AuthResponse`
- some endpoints return `204 No Content`

---

## Auth / self-service contract

## 1. Login

### Call

`POST /api/v1/auth/login`

### Request

```json
{
  "email": "admin@example.com",
  "password": "secret",
  "companyCode": "BBP",
  "mfaCode": "123456",
  "recoveryCode": null
}
```

```ts
type LoginRequest = {
  email: string
  password: string
  companyCode: string
  mfaCode?: string | null
  recoveryCode?: string | null
}
```

### Response

```ts
type AuthResponse = {
  tokenType: string
  accessToken: string
  refreshToken: string
  expiresIn: number
  companyCode: string
  displayName: string
  mustChangePassword: boolean
}
```

### Frontend behavior

- store access token
- store refresh token
- bootstrap current user via `/api/v1/auth/me`
- if `mustChangePassword === true`, redirect to force-change-password flow

---

## 2. Refresh

### Call

`POST /api/v1/auth/refresh-token`

### Request

```json
{
  "refreshToken": "refresh-token",
  "companyCode": "BBP"
}
```

### Response

Same `AuthResponse` as login.

### Frontend behavior

- automatic token refresh only
- no user-facing refresh UI

---

## 3. Logout

### Call

`POST /api/v1/auth/logout?refreshToken=<optional>`

### Response

`204 No Content`

### Frontend behavior

- clear local auth state
- clear tokens
- redirect to login

---

## 4. Current user bootstrap

### Call

`GET /api/v1/auth/me`

### Response

```ts
type MeResponse = {
  email: string
  displayName: string
  companyCode: string
  mfaEnabled: boolean
  mustChangePassword: boolean
  roles: string[]
  permissions: string[]
}
```

### Frontend uses

- initial redirect rules
- role-based sidebar
- admin vs superadmin mode
- button/action access

---

## 5. Profile

### Read profile

`GET /api/v1/auth/profile`

```ts
type ProfileResponse = {
  email: string
  displayName: string
  preferredName: string | null
  jobTitle: string | null
  profilePictureUrl: string | null
  phoneSecondary: string | null
  secondaryEmail: string | null
  mfaEnabled: boolean
  companyCode: string
  createdAt: string
  publicId: string
}
```

### Update profile

`PUT /api/v1/auth/profile`

```ts
type UpdateProfileRequest = {
  displayName?: string | null
  preferredName?: string | null
  jobTitle?: string | null
  profilePictureUrl?: string | null
  phoneSecondary?: string | null
  secondaryEmail?: string | null
}
```

### Frontend behavior

- use same page for read/edit
- save should refresh profile view after success

---

## 6. Password flows

### Change password

`POST /api/v1/auth/password/change`

```ts
type ChangePasswordRequest = {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}
```

### Forgot password

`POST /api/v1/auth/password/forgot`

```ts
type ForgotPasswordRequest = {
  email: string
  companyCode: string
}
```

### Reset password

`POST /api/v1/auth/password/reset`

```ts
type ResetPasswordRequest = {
  token: string
  newPassword: string
  confirmPassword: string
}
```

---

## 7. MFA flows

### Setup

`POST /api/v1/auth/mfa/setup`

```ts
type MfaSetupResponse = {
  secret: string
  qrUri: string
  recoveryCodes: string[]
}
```

### Activate

`POST /api/v1/auth/mfa/activate`

```ts
type MfaActivateRequest = {
  code: string
}
```

Response data:

```ts
{ enabled: true }
```

### Disable

`POST /api/v1/auth/mfa/disable`

```ts
type MfaDisableRequest = {
  code?: string | null
  recoveryCode?: string | null
}
```

Response data:

```ts
{ enabled: false }
```

---

## Admin portal page map

The admin portal should be organized around these pages/sections.

---

## 8. Company context

### Screen

- tenant/company badge/header context

### Call

`GET /api/v1/companies`

### Response

```ts
type CompanyDto = {
  id: number
  publicId: string
  name: string
  code: string
  timezone: string
  stateCode: string | null
  defaultGstRate: number
}
```

### Frontend behavior

- call once after auth bootstrap
- use for header / tenant context
- admin will typically only see current tenant

---

## 9. Dashboard

These are `ROLE_ADMIN` views.

### 9.1 Overview

#### Call

`GET /api/v1/portal/dashboard`

#### Response

```ts
type DashboardInsights = {
  highlights: {
    label: string
    value: string
    detail: string
  }[]
  pipeline: {
    label: string
    count: number
  }[]
  hrPulse: {
    label: string
    score: string
    context: string
  }[]
}
```

### 9.2 Operations

#### Call

`GET /api/v1/portal/operations`

#### Response

```ts
type OperationsInsights = {
  summary: {
    productionVelocity: number
    logisticsSla: number
    workingCapital: string
  }
  supplyAlerts: {
    material: string
    status: string
    detail: string
  }[]
  automationRuns: {
    name: string
    state: string
    description: string
  }[]
}
```

### 9.3 Workforce

#### Call

`GET /api/v1/portal/workforce`

#### Response

```ts
type WorkforceInsights = {
  squads: {
    name: string
    capacity: string
    detail: string
  }[]
  moments: {
    title: string
    schedule: string
    description: string
  }[]
  leaders: {
    name: string
    role: string
    highlight: string
  }[]
}
```

### Frontend behavior

- dashboard landing page can be tabbed: `Overview | Operations | Workforce`
- fetch each tab lazily or all at once, FE choice
- design/grouping is FE-owned

---

## 10. Users

Base path:

`/api/v1/admin/users`

### DTO

```ts
type UserDto = {
  id: number
  publicId: string
  email: string
  displayName: string
  enabled: boolean
  mfaEnabled: boolean
  roles: string[]
  companyCode: string | null
  lastLoginAt: string | null
}
```

### 10.1 User list

#### Call

`GET /api/v1/admin/users`

#### Expected FE state

- table/list
- filters/search optional FE-only
- columns recommended:
  - displayName
  - email
  - roles
  - enabled
  - mfaEnabled
  - companyCode
  - lastLoginAt
  - actions

### 10.2 Create user

#### Call

`POST /api/v1/admin/users`

#### Request

```ts
type CreateUserRequest = {
  email: string
  displayName: string
  companyId?: number | null
  roles: string[]
}
```

#### Success behavior

- close modal/page
- refresh list

### 10.3 Update user

#### Call

`PUT /api/v1/admin/users/{id}`

#### Request

```ts
type UpdateUserRequest = {
  displayName: string
  companyId?: number | null
  roles?: string[] | null
  enabled?: boolean | null
}
```

#### Success behavior

- refresh detail/list row

### 10.4 Update user status

#### Call

`PUT /api/v1/admin/users/{userId}/status`

#### Request

```ts
type UpdateUserStatusRequest = {
  enabled: boolean
}
```

### 10.5 Force password reset

#### Call

`POST /api/v1/admin/users/{userId}/force-reset-password`

#### Body

No body

### 10.6 Suspend

#### Call

`PATCH /api/v1/admin/users/{id}/suspend`

#### Response

`204 No Content`

### 10.7 Unsuspend

#### Call

`PATCH /api/v1/admin/users/{id}/unsuspend`

#### Response

`204 No Content`

### 10.8 Disable MFA

#### Call

`PATCH /api/v1/admin/users/{id}/mfa/disable`

#### Response

`204 No Content`

### 10.9 Delete user

#### Call

`DELETE /api/v1/admin/users/{id}`

#### Response

`204 No Content`

### Frontend behavior

- destructive actions must use confirmation modals
- for all 204 actions, refresh the list after success
- FE should not attempt to parse JSON for 204 responses

---

## 11. Settings

Base path:

`/api/v1/admin/settings`

### DTO

```ts
type SystemSettingsDto = {
  allowedOrigins: string[]
  autoApprovalEnabled: boolean
  periodLockEnforced: boolean
  exportApprovalRequired: boolean
  platformAuthCode: string
  mailEnabled: boolean
  mailFromAddress: string
  mailBaseUrl: string
  sendCredentials: boolean
  sendPasswordReset: boolean
}
```

### 11.1 Read settings

#### Call

`GET /api/v1/admin/settings`

### 11.2 Update settings

#### Call

`PUT /api/v1/admin/settings`

#### Request

```ts
type SystemSettingsUpdateRequest = {
  allowedOrigins?: string[] | null
  autoApprovalEnabled?: boolean | null
  periodLockEnforced?: boolean | null
  exportApprovalRequired?: boolean | null
  platformAuthCode?: string | null
  mailEnabled?: boolean | null
  mailFromAddress?: string | null
  mailBaseUrl?: string | null
  sendCredentials?: boolean | null
  sendPasswordReset?: boolean | null
}
```

### Frontend behavior

- render read-only for `ROLE_ADMIN`
- render editable only if `ROLE_SUPER_ADMIN`
- if same portal shell supports superadmin, gate edit controls using `/auth/me`

---

## 12. Approvals

### Inbox fetch

#### Call

`GET /api/v1/admin/approvals`

#### Response

```ts
type AdminApprovalsResponse = {
  creditRequests: AdminApprovalItemDto[]
  payrollRuns: AdminApprovalItemDto[]
  periodCloseRequests: AdminApprovalItemDto[]
  exportRequests: AdminApprovalItemDto[]
}

type AdminApprovalItemDto = {
  originType:
    | "CREDIT_REQUEST"
    | "CREDIT_LIMIT_OVERRIDE_REQUEST"
    | "PAYROLL_RUN"
    | "PERIOD_CLOSE_REQUEST"
    | "EXPORT_REQUEST"
  ownerType:
    | "SALES"
    | "FACTORY"
    | "HR"
    | "ACCOUNTING"
    | "REPORTS"
  id: number | null
  publicId: string | null
  reference: string
  status: string
  summary: string
  reportType?: string | null
  parameters?: string | null
  requesterUserId?: number | null
  requesterEmail?: string | null
  actionType?: string | null
  actionLabel?: string | null
  approveEndpoint?: string | null
  rejectEndpoint?: string | null
  createdAt: string | null
}
```

### Frontend rendering rule

Do **not** hardcode approvals only by group name.

Use:

- `originType`
- `ownerType`
- `summary`
- `approveEndpoint`
- `rejectEndpoint`

If action endpoints are present, render actions.  
If missing/null, render read-only.

### Approval actions used by admin portal

#### Export request approve

`PUT /api/v1/admin/exports/{requestId}/approve`

No body

#### Export request reject

`PUT /api/v1/admin/exports/{requestId}/reject`

```ts
type ExportRequestDecisionRequest = {
  reason?: string | null
}
```

#### Credit request approve/reject

- `POST /api/v1/credit/limit-requests/{id}/approve`
- `POST /api/v1/credit/limit-requests/{id}/reject`

```ts
type CreditLimitRequestDecisionRequest = {
  reason: string
}
```

#### Credit override list/approve/reject

- `GET /api/v1/credit/override-requests`
- `POST /api/v1/credit/override-requests/{id}/approve`
- `POST /api/v1/credit/override-requests/{id}/reject`

```ts
type CreditLimitOverrideDecisionRequest = {
  reason?: string | null
  expiresAt?: string | null
}
```

#### Period close approve/reject

- `POST /api/v1/accounting/periods/{periodId}/approve-close`
- `POST /api/v1/accounting/periods/{periodId}/reject-close`

```ts
type PeriodCloseRequestActionRequest = {
  note?: string | null
  force?: boolean | null
}
```

### Important admin rules

- period close **approve/reject** is admin-only
- payroll approvals are conditional
- export approvals are handled directly in admin portal

---

## 13. Roles / RBAC

Base path:

`/api/v1/admin/roles`

### DTOs

```ts
type PermissionDto = {
  id: number
  code: string
  description: string
}

type RoleDto = {
  id: number | null
  name: string
  description: string
  permissions: PermissionDto[]
}
```

### 13.1 List roles

`GET /api/v1/admin/roles`

### 13.2 Get role detail

`GET /api/v1/admin/roles/{roleKey}`

### 13.3 Create role

`POST /api/v1/admin/roles`

```ts
type CreateRoleRequest = {
  name: string
  description: string
  permissions: string[]
}
```

### Frontend behavior

- show list + detail view
- role create can be modal or page
- do not assume fully arbitrary enterprise role designer; backend is system-role oriented

---

## 14. Notification tool

### Call

`POST /api/v1/admin/notify`

### Request

```ts
type AdminNotifyRequest = {
  to: string
  subject: string
  body: string
}
```

### Frontend behavior

- simple send-email utility form
- success toast after send

---

## 15. Audit trail

Admin portal should expose audit as its own area.

## 15.1 Business audit events

### Call

`GET /api/v1/audit/business-events`

### Query params

- `from`
- `to`
- `module`
- `action`
- `status`
- `actorUserId`
- `referenceNumber`
- `page`
- `size`

### Response

```ts
type PageResponse<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  page: number
  size: number
}

type BusinessAuditEventResponse = {
  id: number
  occurredAt: string
  source: string
  module: string
  action: string
  entityType: string
  entityId: string
  referenceNumber: string | null
  status: string
  failureReason: string | null
  amount: number | null
  currency: string | null
  correlationId: string | null
  requestId: string | null
  traceId: string | null
  actorUserId: number | null
  actorIdentifier: string | null
  metadata: Record<string, string>
}
```

## 15.2 Accounting audit trail

### Call

`GET /api/v1/accounting/audit-trail`

### Query params

- `from`
- `to`
- `user`
- `actionType`
- `entityType`
- `page`
- `size`

### Response

```ts
type AccountingAuditTrailEntryDto = {
  id: number
  timestamp: string
  companyId: number
  companyCode: string
  actorUserId: number | null
  actorIdentifier: string | null
  actionType: string
  entityType: string
  entityId: string
  referenceNumber: string | null
  traceId: string | null
  ipAddress: string | null
  beforeState: string | null
  afterState: string | null
  sensitiveOperation: boolean
  metadata: Record<string, string>
}
```

### Recommended FE structure

- tab 1: Business Events
- tab 2: Accounting Audit Trail

---

## 16. Finance lookup

These are shared admin/accounting helper views.

## 16.1 Dealer ledger

### Call

`GET /api/v1/portal/finance/ledger?dealerId=<id>`

### Response payload shape

```ts
type PortalDealerLedgerResponse = {
  dealerId: number
  dealerName: string
  currentBalance: number
  entries: {
    date: string
    reference: string | null
    memo: string | null
    debit: number
    credit: number
    runningBalance: number
  }[]
}
```

## 16.2 Dealer invoices

### Call

`GET /api/v1/portal/finance/invoices?dealerId=<id>`

### Response payload shape

```ts
type PortalDealerInvoicesResponse = {
  dealerId: number
  dealerName: string
  totalOutstanding: number
  invoiceCount: number
  invoices: {
    id: number
    invoiceNumber: string
    issueDate: string
    dueDate: string | null
    totalAmount: number
    outstandingAmount: number
    status: string
    currency: string | null
  }[]
}
```

## 16.3 Dealer aging

### Call

`GET /api/v1/portal/finance/aging?dealerId=<id>`

### Response payload shape

```ts
type PortalDealerAgingResponse = {
  dealerId: number
  dealerName: string
  creditLimit: number
  totalOutstanding: number
  pendingOrderCount: number
  pendingOrderExposure: number
  creditUsed: number
  availableCredit: number
  agingBuckets: {
    current: number
    "1-30 days": number
    "31-60 days": number
    "61-90 days": number
    "90+ days": number
  }
  overdueInvoices: {
    invoiceNumber: string
    issueDate: string
    dueDate: string | null
    daysOverdue: number
    outstandingAmount: number
  }[]
}
```

### Frontend behavior

- recommended finance tabs:
  - Ledger
  - Invoices
  - Aging
- `dealerId` is required query input

---

## 17. Support tickets

Base path:

`/api/v1/portal/support/tickets`

### DTOs

```ts
type SupportTicketCreateRequest = {
  category: string
  subject: string
  description: string
}

type SupportTicketResponse = {
  id: number
  publicId: string
  companyCode: string
  userId: number
  requesterEmail: string
  category: "BUG" | "FEATURE_REQUEST" | "SUPPORT"
  subject: string
  description: string
  status: "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED"
  githubIssueNumber: number | null
  githubIssueUrl: string | null
  githubIssueState: string | null
  githubSyncedAt: string | null
  githubLastError: string | null
  resolvedAt: string | null
  resolvedNotificationSentAt: string | null
  createdAt: string
  updatedAt: string
}

type SupportTicketListResponse = {
  tickets: SupportTicketResponse[]
}
```

### 17.1 Create

`POST /api/v1/portal/support/tickets`

### 17.2 List

`GET /api/v1/portal/support/tickets`

### 17.3 Detail

`GET /api/v1/portal/support/tickets/{ticketId}`

### Frontend behavior

- list screen
- detail drawer/page
- create modal/page
- category values should come from backend enum assumptions:
  - `BUG`
  - `FEATURE_REQUEST`
  - `SUPPORT`

---

## 18. Changelog

## 18.1 List changelog

### Call

`GET /api/v1/changelog?page=0&size=20`

### Response

```ts
type ChangelogEntryResponse = {
  id: number
  version: string
  title: string
  body: string
  publishedAt: string
  createdBy: string
  isHighlighted: boolean
}
```

Wrapped in `ApiResponse<PageResponse<ChangelogEntryResponse>>`.

## 18.2 Latest highlighted

### Call

`GET /api/v1/changelog/latest-highlighted`

### Response

`ApiResponse<ChangelogEntryResponse>`

### Frontend behavior

- can be used as a dashboard announcement card
- list page can paginate using returned `PageResponse`

---

## Recommended sidebar structure

Frontend can design freely, but backend-aligned grouping is:

- Dashboard
  - Overview
  - Operations
  - Workforce
- Users
- Approvals
- Settings
- Roles
- Audit Trail
  - Business Events
  - Accounting Audit Trail
- Finance
  - Dealer Ledger
  - Dealer Invoices
  - Dealer Aging
- Support Tickets
- Changelog
- Profile

If same shell supports `ROLE_SUPER_ADMIN`, superadmin-only items should be added separately and gated by `/auth/me`.

---

## Loading / empty / error expectations

Frontend should implement standard UX states for every screen:

### Loading

- show skeleton/table loader/spinner per page

### Empty

- approvals: “No pending approvals”
- users: “No users found”
- finance: “No entries/invoices available”
- support: “No tickets yet”
- changelog: “No changelog entries”

### Error

- show backend `message` where available
- for 401: redirect to login
- for 403: show access denied page/toast
- for 204 endpoints: success toast + local refresh

---

## Concrete FE call sequence by page

## App init

1. `POST /api/v1/auth/login`
2. `GET /api/v1/auth/me`
3. `GET /api/v1/companies`

## Dashboard landing

1. `GET /api/v1/portal/dashboard`
2. optionally lazy-load:
   - `GET /api/v1/portal/operations`
   - `GET /api/v1/portal/workforce`

## Users page

1. `GET /api/v1/admin/users`
2. on create/update/delete/suspend/etc → refetch `GET /api/v1/admin/users`

## Approvals page

1. `GET /api/v1/admin/approvals`
2. execute action endpoint from item
3. refetch `GET /api/v1/admin/approvals`

## Settings page

1. `GET /api/v1/admin/settings`
2. if editable and saved → `PUT /api/v1/admin/settings`
3. refetch `GET /api/v1/admin/settings`

## Roles page

1. `GET /api/v1/admin/roles`
2. role detail → `GET /api/v1/admin/roles/{roleKey}`
3. create → `POST /api/v1/admin/roles`
4. refresh list

## Audit page

1. `GET /api/v1/audit/business-events?...`
2. `GET /api/v1/accounting/audit-trail?...`

## Finance page

1. user chooses dealer
2. call one of:
   - `GET /api/v1/portal/finance/ledger?dealerId=...`
   - `GET /api/v1/portal/finance/invoices?dealerId=...`
   - `GET /api/v1/portal/finance/aging?dealerId=...`

## Support tickets

1. `GET /api/v1/portal/support/tickets`
2. create → `POST /api/v1/portal/support/tickets`
3. detail → `GET /api/v1/portal/support/tickets/{ticketId}`
4. refresh list after create

## Changelog

1. `GET /api/v1/changelog?page=0&size=20`
2. optional dashboard card → `GET /api/v1/changelog/latest-highlighted`

---

## Important boundaries

- `PUT /api/v1/admin/settings` is not for plain admin; superadmin only
- dashboard endpoints under `/api/v1/portal/*` are admin-only
- roles/users/settings/notify are admin surfaces
- finance/support are shared admin/accounting helper surfaces
- most APIs return wrapped `ApiResponse<T>`
- login/refresh do not
- 204 responses must not be JSON-parsed
