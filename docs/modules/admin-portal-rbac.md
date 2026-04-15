# Admin, Portal, and RBAC Surfaces

Last reviewed: 2026-04-15

This packet is the canonical backend ownership map for tenant-admin, portal, and RBAC surfaces after the tenant-admin hard-cut refactor.

## Hard-Cut Scope

- Tenant-admin product owns only tenant-scoped admin workflows.
- Superadmin control-plane ownership stays on `/api/v1/superadmin/**` and is out of tenant-admin scope.
- Tenant-admin no longer depends on role-creation APIs.
- Tenant-admin support contracts are owned at `/api/v1/admin/support/tickets/**`.
- Tenant-admin approvals use one inbox + one generic decision endpoint.

## Ownership Summary

| Module | Owns |
| --- | --- |
| `admin` | Tenant-admin dashboard, users, approval inbox/decisions, tenant-admin audit feed, admin support host, self-settings, utility notify, tenant changelog reads |
| `portal` | Shared internal read models (`/api/v1/portal/**`), including legacy admin insights reads plus accounting-owned internal support host |
| `rbac` | Platform role catalog APIs and role synchronization (`/api/v1/admin/roles/**`) |
| `company` | Tenant lifecycle, module gating, limits, support recovery, platform dashboard (`/api/v1/superadmin/**`) |

## Actor Boundaries

| Actor | Role | Boundary |
| --- | --- | --- |
| Tenant admin | `ROLE_ADMIN` | Tenant-scoped admin product under `/api/v1/admin/**` tenant workflows |
| Accounting | `ROLE_ACCOUNTING` | Accounting + portal finance + accounting-hosted internal support |
| Superadmin | `ROLE_SUPER_ADMIN` | Control plane only (`/api/v1/superadmin/**`) |
| Dealer | `ROLE_DEALER` | Dealer self-service only (`/api/v1/dealer-portal/**`) |

Boundary rules:

1. Tenant-admin workflow prefixes are blocked for platform superadmin callers by `CompanyContextFilter`.
2. Approval decisions in the tenant-admin inbox are tenant-admin only.
3. Tenant-admin role assignment is fixed to `ROLE_ACCOUNTING`, `ROLE_FACTORY`, `ROLE_SALES`, `ROLE_DEALER`.
4. Tenant-admin cannot assign `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`, or unknown/custom roles.

## Tenant-Admin Product Surface

### AdminDashboardController

- `GET /api/v1/admin/dashboard`
- Auth: `ROLE_ADMIN`
- Returns tenant-scoped dashboard read model: recent activity, approval summary, user summary, support summary, runtime usage, session/security summary.

### AdminUserController

- `GET /api/v1/admin/users`
- `GET /api/v1/admin/users/{id}`
- `POST /api/v1/admin/users`
- `PUT /api/v1/admin/users/{id}`
- `PUT /api/v1/admin/users/{userId}/status`
- `PATCH /api/v1/admin/users/{id}/suspend`
- `PATCH /api/v1/admin/users/{id}/unsuspend`
- `PATCH /api/v1/admin/users/{id}/mfa/disable`
- `POST /api/v1/admin/users/{userId}/force-reset-password`
- `DELETE /api/v1/admin/users/{id}`
- Auth: `ROLE_ADMIN`

### AdminApprovalController

- `GET /api/v1/admin/approvals`
- `POST /api/v1/admin/approvals/{originType}/{id}/decisions`
- Auth: `ROLE_ADMIN`

Supported `originType` values:

- `EXPORT_REQUEST`
- `CREDIT_REQUEST`
- `CREDIT_LIMIT_OVERRIDE_REQUEST`
- `PAYROLL_RUN`
- `PERIOD_CLOSE_REQUEST`

Decision body (`AdminApprovalDecisionRequest`):

- `decision`: `APPROVE` or `REJECT` (required)
- `reason`: required for credit, credit-override, and period-close decisions; optional for export
- `expiresAt`: optional (used by credit override approval windows)

Origin-specific rules:

- `PAYROLL_RUN` only supports `APPROVE`; reject fails validation.
- `PAYROLL_RUN` inbox items are approve-only (`rejectEndpoint = null`).
- `CREDIT_REQUEST` requires nonblank `reason` for approve/reject.
- `CREDIT_LIMIT_OVERRIDE_REQUEST` requires nonblank `reason`.
- `PERIOD_CLOSE_REQUEST` generic decisions preserve the pending request force posture (`forceRequested`) instead of forcing `false`.

### AdminSupportController

- `POST /api/v1/admin/support/tickets`
- `GET /api/v1/admin/support/tickets`
- `GET /api/v1/admin/support/tickets/{ticketId}`
- Auth: `ROLE_ADMIN`

### AdminSelfController

- `GET /api/v1/admin/self/settings`
- Auth: `ROLE_ADMIN`
- Tenant-admin self settings read model backed by auth/session/runtime context.

### AdminAuditController

- `GET /api/v1/admin/audit/events`
- Auth: `ROLE_ADMIN`
- Tenant-admin audit feed (tenant-scoped only).

### AdminSettingsController

- `GET /api/v1/admin/settings` (`ROLE_ADMIN`)
- `PUT /api/v1/admin/settings` (`ROLE_SUPER_ADMIN`)

Notes:

- This is a governance/system settings surface.
- Tenant-admin portal settings UX should use `/api/v1/admin/self/settings` plus auth-owned self-service routes.

### AdminUtilityController

- `POST /api/v1/admin/notify`
- Auth: `ROLE_ADMIN`
- Utility surface split out of settings controller.

### Changelog

- `GET /api/v1/changelog`
- `GET /api/v1/changelog/latest-highlighted`
- Auth: authenticated tenant users

Publishing remains superadmin-only:

- `POST /api/v1/superadmin/changelog`
- `PUT /api/v1/superadmin/changelog/{id}`
- `DELETE /api/v1/superadmin/changelog/{id}`

## Portal and RBAC Surfaces Outside Canonical Tenant-Admin Product

### Portal host

- Legacy admin insight reads still live for `ROLE_ADMIN`:
  - `/api/v1/portal/dashboard`
  - `/api/v1/portal/operations`
  - `/api/v1/portal/workforce`
- Accounting-owned portal surfaces:
  - `/api/v1/portal/finance/**`
  - `/api/v1/portal/support/tickets/**` (accounting-only host)

### RBAC host

- `/api/v1/admin/roles`
- `/api/v1/admin/roles/{roleKey}`

The role host is still present for platform role catalog operations, but it is not part of tenant-admin product UX.

## Approval Delegation Contract

Tenant-admin approval decisions are orchestrated in admin and delegated to domain services:

| Origin type | Domain delegate |
| --- | --- |
| `EXPORT_REQUEST` | `ExportApprovalService` |
| `CREDIT_REQUEST` | `CreditLimitRequestService` |
| `CREDIT_LIMIT_OVERRIDE_REQUEST` | `CreditLimitOverrideService` |
| `PAYROLL_RUN` | `PayrollService` |
| `PERIOD_CLOSE_REQUEST` | `AccountingPeriodService` |

This keeps domain policy in domain modules while giving tenant-admin frontend one canonical contract.

## Support Ownership Split

| Route host | Primary actor | Purpose |
| --- | --- | --- |
| `/api/v1/admin/support/tickets/**` | `ROLE_ADMIN` | Tenant-admin internal support UX |
| `/api/v1/portal/support/tickets/**` | `ROLE_ACCOUNTING` | Accounting-hosted internal support workflows |
| `/api/v1/dealer-portal/support/tickets/**` | `ROLE_DEALER` | Dealer self-service support |

## Role Assignment Contract for Tenant Admin

Tenant-admin create/update user flows must enforce:

- allowed: `ROLE_ACCOUNTING`, `ROLE_FACTORY`, `ROLE_SALES`, `ROLE_DEALER`
- denied: `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`
- denied: blank/unknown/custom role names

## Canonical vs Retired Tenant-Admin Patterns

| Retired/forbidden for tenant-admin product | Canonical tenant-admin contract |
| --- | --- |
| Split export approval actions (`/api/v1/admin/exports/{id}/approve|reject`) | `POST /api/v1/admin/approvals/{originType}/{id}/decisions` |
| Portal-hosted tenant-admin support (`/api/v1/portal/support/tickets/**`) | `/api/v1/admin/support/tickets/**` |
| Role creation dependency in tenant-admin UX | fixed assignable role list in admin-user contract |
| Treating settings as profile/self UX | `/api/v1/admin/self/settings` + auth self-service surfaces |

## Related Docs

- [docs/flows/tenant-admin-management.md](../flows/tenant-admin-management.md)
- [docs/frontend-portals/tenant-admin/api-contracts.md](../frontend-portals/tenant-admin/api-contracts.md)
- [docs/frontend-portals/tenant-admin/role-boundaries.md](../frontend-portals/tenant-admin/role-boundaries.md)
- [docs/frontend-api/admin-role.md](../frontend-api/admin-role.md)
- [docs/frontend-api/auth-and-company-scope.md](../frontend-api/auth-and-company-scope.md)
