# Admin, Portal, and RBAC Surfaces

Last reviewed: 2026-03-30

This packet documents the **admin**, **portal**, **rbac**, and **sales dealer-portal** surfaces that together define the platform's role-action boundaries, host ownership, and access-control model. It covers system roles, the `PortalRoleActionMatrix`, admin/user management, approval workflows, export gating, support paths, dealer self-service versus admin approval flows, and the restrictions around role assignment and host ownership.

## Ownership Summary

| Module | Owns |
| --- | --- |
| `admin` | User management, system settings, changelog, export-approval workflow, support tickets (admin-side), tenant runtime policy |
| `portal` | Internal admin/accounting read models: dashboard insights, portal finance (ledger/invoices/aging by dealer), internal support tickets |
| `rbac` | Role and permission model: `SystemRole` enum, `Role`/`Permission` entities, role synchronization, role-mutation restrictions |
| `sales` (dealer-portal) | Dealer self-service endpoints: dashboard, ledger, invoices, aging, orders, credit-limit requests, invoice PDF export, dealer support tickets |

The `PortalRoleActionMatrix` in `core/security/` defines the shared role predicates used across these modules.

## Actor Boundaries

### System Roles (SystemRole enum)

| Role | Description | Default Permissions |
| --- | --- | --- |
| `ROLE_SUPER_ADMIN` | Platform owner with global cross-tenant management and support authority | `portal:accounting`, `portal:factory`, `portal:sales`, `portal:dealer`, `dispatch.confirm`, `factory.dispatch`, `payroll.run` |
| `ROLE_ADMIN` | Platform administrator | Same default permissions as SUPER_ADMIN |
| `ROLE_ACCOUNTING` | Accounting, finance, HR, and inventory operator | `portal:accounting`, `dispatch.confirm`, `payroll.run` |
| `ROLE_FACTORY` | Factory, production, and dispatch operator | `portal:factory`, `dispatch.confirm`, `factory.dispatch` |
| `ROLE_SALES` | Sales operations and dealer management | `portal:sales` (retired: `dispatch.confirm`) |
| `ROLE_DEALER` | Dealer workspace user | `portal:dealer` |

### Actor Boundary Rules

1. **Super-admin is control-plane only.** Super-admin users authenticate with platform scope (default: `PLATFORM`) and are restricted to `/api/v1/superadmin/**`, `/api/v1/auth/**`, `/api/v1/companies`, and `/api/v1/admin/settings`. They are explicitly blocked from tenant business endpoints by `CompanyContextFilter`.

2. **Admin and Accounting share most approval and finance surfaces.** Both roles can view and approve credit-limit requests, credit-limit overrides, export requests, period-close requests, and payroll runs via the admin approvals endpoint.

3. **Sales can create and view credit-limit requests, but cannot approve them.** Approval requires `ROLE_ADMIN` or `ROLE_ACCOUNTING`.

4. **Factory and Sales can create credit-limit override requests, but cannot approve them.** Override approval requires `ROLE_ADMIN` or `ROLE_ACCOUNTING`.

5. **Dealer is self-service only.** Dealer users are restricted to `/api/v1/dealer-portal/**`, which auto-scopes all data to the authenticated dealer's own records. Dealers cannot access admin, internal-portal, or other-tenant surfaces.

## Host Ownership Map

| Host prefix | Owning module | Primary actors | Access predicate |
| --- | --- | --- | --- |
| `/api/v1/admin/*` | `admin` | Admin, Super Admin | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` (varies by endpoint) |
| `/api/v1/admin/users/*` | `admin` | Admin, Super Admin | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` |
| `/api/v1/admin/roles/*` | `rbac` | Admin, Super Admin | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` |
| `/api/v1/superadmin/*` | `company` (control plane) | Super Admin only | `ROLE_SUPER_ADMIN` |
| `/api/v1/portal/*` | `portal` | Admin only | `ROLE_ADMIN` |
| `/api/v1/portal/finance/*` | `portal` | Admin, Accounting | `ADMIN_OR_ACCOUNTING` |
| `/api/v1/portal/support/tickets` | `portal` | Admin, Accounting | `ADMIN_OR_ACCOUNTING` |
| `/api/v1/dealer-portal/*` | `sales` | Dealer only | `DEALER_ONLY` |
| `/api/v1/dealer-portal/support/tickets` | `sales` | Dealer only | `DEALER_ONLY` |
| `/api/v1/dealers/*` | `sales` | Admin, Sales, Accounting | `ADMIN_SALES_ACCOUNTING` |
| `/api/v1/credit/limit-requests` | `sales` | Admin, Sales (create/view); Admin, Accounting (approve/reject) | Mixed |
| `/api/v1/credit/override-requests` | `sales` | Admin, Factory, Sales (create); Admin, Accounting (approve/reject) | Mixed |
| `/api/v1/changelog` | `admin` | Any authenticated user | `isAuthenticated()` |
| `/api/v1/superadmin/changelog` | `admin` | Super Admin only | `ROLE_SUPER_ADMIN` |

## PortalRoleActionMatrix Predicates

The `PortalRoleActionMatrix` class in `core/security/` defines the canonical role predicates applied at the controller class level via `@PreAuthorize`. These are the authoritative access-control constants for portal surfaces.

| Constant | Predicate | Used by |
| --- | --- | --- |
| `DEALER_ONLY` | `hasAuthority('ROLE_DEALER')` | `DealerPortalController`, `DealerPortalSupportTicketController` |
| `ADMIN_ONLY` | `hasAuthority('ROLE_ADMIN')` | `AdminSettingsController` (settings read, notify), `PortalInsightsController` |
| `SUPER_ADMIN_ONLY` | `hasAuthority('ROLE_SUPER_ADMIN')` | `AdminSettingsController` (settings update), `SuperAdminChangelogController` |
| `ADMIN_OR_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')` | `PortalFinanceController`, `PortalSupportTicketController`, credit-limit request approve/reject, credit-override approve/reject |
| `ADMIN_SALES_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_ACCOUNTING')` | `DealerController`, credit-limit request create |
| `ADMIN_SALES_FACTORY_ACCOUNTING` | `hasAnyAuthority('ROLE_ADMIN','ROLE_SALES','ROLE_FACTORY','ROLE_ACCOUNTING')` | `SalesController` (orders list, search, dashboard, timeline) |
| `ADMIN_FACTORY_SALES` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY','ROLE_SALES')` | Credit-limit override request create |
| `ADMIN_FACTORY` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY')` | Dispatch and factory surfaces |
| `ADMIN_ACCOUNTING_SUPER_ADMIN` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING','ROLE_SUPER_ADMIN')` | `AdminSettingsController` (approvals endpoint) |
| `FINANCIAL_DISPATCH` | `hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING') and hasAuthority('dispatch.confirm')` | Financial dispatch reconciliation |
| `OPERATIONAL_DISPATCH` | `hasAnyAuthority('ROLE_ADMIN','ROLE_FACTORY') and hasAuthority('dispatch.confirm')` | Operational dispatch confirmation |

### Access-Denied Messages

`PortalRoleActionMatrix.resolveAccessDeniedMessage()` provides context-specific messages when a role lacks access. For example:

- Factory users trying to reach financial dispatch paths are told to use the factory dispatch workspace.
- Sales users trying to reach dispatch paths are told accounting must complete the final posting.
- Dealer users trying to reach promotions are told dealer access is limited to their own portal records.
- Sales or Factory users trying to approve credit overrides are told an admin or accountant must review.

## Primary Controllers and Routes

### AdminUserController — `/api/v1/admin/users`

Tenant-scoped user management. All endpoints require `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/admin/users` | List users for current tenant |
| POST | `/api/v1/admin/users` | Create a new user |
| PUT | `/api/v1/admin/users/{id}` | Update user details |
| PUT | `/api/v1/admin/users/{userId}/status` | Enable or disable user |
| POST | `/api/v1/admin/users/{userId}/force-reset-password` | Send password-reset link to user |
| PATCH | `/api/v1/admin/users/{id}/suspend` | Suspend user |
| PATCH | `/api/v1/admin/users/{id}/unsuspend` | Unsuspend user |
| PATCH | `/api/v1/admin/users/{id}/mfa/disable` | Disable user MFA |
| DELETE | `/api/v1/admin/users/{id}` | Delete user |

### AdminSettingsController — `/api/v1/admin`

Central admin operations hub. Role requirements vary by endpoint.

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/settings` | `ROLE_ADMIN` | Read system settings |
| PUT | `/api/v1/admin/settings` | `ROLE_SUPER_ADMIN` | Update system settings (period-lock changes also require SUPER_ADMIN) |
| PUT | `/api/v1/admin/exports/{id}/approve` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | Approve an export request |
| PUT | `/api/v1/admin/exports/{id}/reject` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | Reject an export request |
| POST | `/api/v1/admin/notify` | `ROLE_ADMIN` | Send notification email to user |
| GET | `/api/v1/admin/approvals` | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, or `ROLE_SUPER_ADMIN` | List all pending approvals across domains |

### ChangelogController — `/api/v1/changelog`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/changelog` | Any authenticated user | Paginated changelog list |
| GET | `/api/v1/changelog/latest-highlighted` | Any authenticated user | Latest highlighted changelog entry |

### SuperAdminChangelogController — `/api/v1/superadmin/changelog`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/superadmin/changelog` | `ROLE_SUPER_ADMIN` | Create changelog entry |
| PUT | `/api/v1/superadmin/changelog/{id}` | `ROLE_SUPER_ADMIN` | Update changelog entry |
| DELETE | `/api/v1/superadmin/changelog/{id}` | `ROLE_SUPER_ADMIN` | Soft-delete changelog entry |

### RoleController — `/api/v1/admin/roles`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/admin/roles` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | List roles (super-admin sees SUPER_ADMIN role; admin does not) |
| GET | `/api/v1/admin/roles/{roleKey}` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | Get single role by key |
| POST | `/api/v1/admin/roles` | `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` | Create/update role (SUPER_ADMIN authority required to mutate system roles) |

### PortalInsightsController — `/api/v1/portal`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/portal/dashboard` | `ROLE_ADMIN` | Dashboard insights |
| GET | `/api/v1/portal/operations` | `ROLE_ADMIN` | Operations insights |
| GET | `/api/v1/portal/workforce` | `ROLE_ADMIN` | Workforce insights |

### PortalFinanceController — `/api/v1/portal/finance`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/portal/finance/ledger` | `ADMIN_OR_ACCOUNTING` | Dealer ledger (by dealerId query param) |
| GET | `/api/v1/portal/finance/invoices` | `ADMIN_OR_ACCOUNTING` | Dealer invoices (by dealerId query param) |
| GET | `/api/v1/portal/finance/aging` | `ADMIN_OR_ACCOUNTING` | Dealer aging (by dealerId query param) |

### PortalSupportTicketController — `/api/v1/portal/support/tickets`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/portal/support/tickets` | `ADMIN_OR_ACCOUNTING` | Create support ticket (admin side) |
| GET | `/api/v1/portal/support/tickets` | `ADMIN_OR_ACCOUNTING` | List support tickets (admin side) |
| GET | `/api/v1/portal/support/tickets/{ticketId}` | `ADMIN_OR_ACCOUNTING` | Get support ticket detail (admin side) |

### DealerPortalController — `/api/v1/dealer-portal`

All endpoints require `ROLE_DEALER` (`DEALER_ONLY`). Data is automatically scoped to the authenticated dealer.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/dealer-portal/dashboard` | Dealer dashboard (balance, credit, aging) |
| GET | `/api/v1/dealer-portal/ledger` | Dealer's own ledger |
| GET | `/api/v1/dealer-portal/invoices` | Dealer's own invoices |
| GET | `/api/v1/dealer-portal/aging` | Dealer's own outstanding and aging buckets |
| GET | `/api/v1/dealer-portal/orders` | Dealer's own orders |
| POST | `/api/v1/dealer-portal/credit-limit-requests` | Submit credit-limit increase request |
| GET | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | Download dealer's own invoice PDF (audit-logged) |

### DealerPortalSupportTicketController — `/api/v1/dealer-portal/support/tickets`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/dealer-portal/support/tickets` | `DEALER_ONLY` | Create support ticket (dealer side) |
| GET | `/api/v1/dealer-portal/support/tickets` | `DEALER_ONLY` | List dealer's own support tickets |
| GET | `/api/v1/dealer-portal/support/tickets/{ticketId}` | `DEALER_ONLY` | Get dealer's own support ticket detail |

### CreditLimitRequestController — `/api/v1/credit/limit-requests`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/credit/limit-requests` | `ROLE_ADMIN` or `ROLE_SALES` | List credit-limit requests |
| POST | `/api/v1/credit/limit-requests` | `ROLE_SALES` or `ROLE_ADMIN` | Create credit-limit request (admin or sales initiated) |
| POST | `/api/v1/credit/limit-requests/{id}/approve` | `ADMIN_OR_ACCOUNTING` | Approve credit-limit request |
| POST | `/api/v1/credit/limit-requests/{id}/reject` | `ADMIN_OR_ACCOUNTING` | Reject credit-limit request |

### CreditLimitOverrideController — `/api/v1/credit/override-requests`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/credit/override-requests` | `ADMIN_FACTORY_SALES` | Create dispatch credit-override request |
| GET | `/api/v1/credit/override-requests` | `ADMIN_OR_ACCOUNTING` | List override requests (optional status filter) |
| POST | `/api/v1/credit/override-requests/{id}/approve` | `ADMIN_OR_ACCOUNTING` | Approve override request |
| POST | `/api/v1/credit/override-requests/{id}/reject` | `ADMIN_OR_ACCOUNTING` | Reject override request |

## Approval Surfaces

The admin approvals endpoint (`GET /api/v1/admin/approvals`) aggregates pending approval items across five domains. The caller must have `ROLE_ADMIN`, `ROLE_ACCOUNTING`, or `ROLE_SUPER_ADMIN`.

### Approval Categories

| Category | Origin | Who can request | Who can approve/reject | Approval endpoint |
| --- | --- | --- | --- | --- |
| **Credit-limit request** | Sales or Dealer self-service | Sales, Admin (or Dealer via dealer-portal) | Admin, Accounting | `/api/v1/credit/limit-requests/{id}/approve` and `/reject` |
| **Credit-limit override** | Dispatch flow | Admin, Factory, Sales | Admin, Accounting | `/api/v1/credit/override-requests/{id}/approve` and `/reject` |
| **Payroll run** | HR/Payroll module | HR/Payroll system | Admin, Accounting | `/api/v1/payroll/runs/{id}/approve` |
| **Period-close request** | Accounting module | Accounting system | Admin, Accounting | `/api/v1/accounting/periods/{id}/approve-close` and `/reject-close` |
| **Export request** | Reports module | Any authenticated user | Admin, Super Admin | `/api/v1/admin/exports/{id}/approve` and `/reject` |

### Sensitive Approval Details

The approvals endpoint conditionally includes requester user ID and email based on the caller's role:

- `ROLE_ADMIN` and `ROLE_SUPER_ADMIN` see full requester details (user ID, email).
- `ROLE_ACCOUNTING` sees the approval summary but not requester identity details.

### Approval Ownership Model

Each `AdminApprovalItemDto` carries:

- `originType`: identifies the domain (`CREDIT_REQUEST`, `CREDIT_LIMIT_OVERRIDE_REQUEST`, `PAYROLL_RUN`, `PERIOD_CLOSE_REQUEST`, `EXPORT_REQUEST`).
- `ownerType`: identifies the originating module (`SALES`, `FACTORY`, `HR`, `ACCOUNTING`, `REPORTS`).
- `approveEndpoint` / `rejectEndpoint`: the canonical endpoint for the approve/reject action.

## Export Approval Workflow

Export requests are gated by the `exportApprovalRequired` system setting:

1. **When `exportApprovalRequired = true`**: any report export creates an `ExportRequest` with `PENDING` status. An admin or super-admin must approve it via `/api/v1/admin/exports/{id}/approve` before the export file can be downloaded.
2. **When `exportApprovalRequired = false`**: exports proceed directly without approval.

The approval/reject endpoints require `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.

## Support Ticket Surfaces

Support tickets exist in two separate host spaces, each with its own controller and service, enforcing actor isolation:

| Surface | Host | Actor | Auto-scope |
| --- | --- | --- | --- |
| Internal support tickets | `/api/v1/portal/support/tickets` | Admin, Accounting | Tenant-scoped |
| Dealer support tickets | `/api/v1/dealer-portal/support/tickets` | Dealer | Dealer-scoped (own tickets only) |

Both surfaces use the same domain entity (`SupportTicket`) and similar DTOs (`SupportTicketCreateRequest`, `SupportTicketResponse`, `SupportTicketListResponse`), but they are served by separate controllers and services:

- `PortalSupportTicketController` → `PortalSupportTicketService` — internal admin/accounting ticket management.
- `DealerPortalSupportTicketController` → `DealerPortalSupportTicketService` — dealer self-service ticket management.

Dealer support tickets are automatically scoped to the authenticated dealer's company and user ID by the `DEALER_ONLY` guard and `DealerPortalService.getCurrentDealer()`.

Admin-side support tickets can be synced to GitHub Issues via `SupportTicketGitHubSyncService` when configured.

## Dealer Self-Service versus Admin Approval Flows

### Credit-Limit Request Flow

```
Dealer (self-service)                   Admin (approval)
     │                                       │
     ├─ POST /dealer-portal/credit-limit-requests ──► creates pending request
     │                                       │
     │                           GET /credit/limit-requests ◄── Sales or Admin views queue
     │                                       │
     │                        POST /credit/limit-requests/{id}/approve ◄── Admin or Accounting approves
     │                                       │
     └─────────── response ──────────────────┘
```

- **Dealer path**: the dealer submits via `/api/v1/dealer-portal/credit-limit-requests`, which is dealer-scoped and uses `DealerPortalCreditLimitRequestCreateRequest`.
- **Sales/Admin path**: sales or admin users submit via `/api/v1/credit/limit-requests`, specifying the `dealerId` explicitly.
- **Approval path**: only `ROLE_ADMIN` or `ROLE_ACCOUNTING` can approve or reject via the credit-limit request controller.

### Credit-Limit Override Flow

```
Factory/Sales (dispatch)                 Admin (approval)
     │                                       │
     ├─ POST /credit/override-requests ──► creates pending override
     │                                       │
     │                           GET /credit/override-requests ◄── Admin or Accounting views queue
     │                                       │
     │                        POST /credit/override-requests/{id}/approve ◄── Admin or Accounting approves
     │                                       │
     └─────────── response ──────────────────┘
```

- **Request path**: `ROLE_ADMIN`, `ROLE_FACTORY`, or `ROLE_SALES` can create an override request during dispatch.
- **Approval path**: only `ROLE_ADMIN` or `ROLE_ACCOUNTING` can approve or reject.

## Role Assignment Restrictions

### Super-Admin Restrictions on Role Mutation

1. **Viewing the SUPER_ADMIN role**: `listRolesForCurrentActor()` filters out `ROLE_SUPER_ADMIN` unless the caller has `ROLE_SUPER_ADMIN` authority. Regular admins cannot see or discover the super-admin role.

2. **Mutating system roles**: `RoleService.persistRole()` enforces `SUPER_ADMIN` authority for any role mutation that targets a `SystemRole` name. Non-super-admin users receive `AccessDeniedException` with the message `"SUPER_ADMIN authority required for role mutation: ROLE_XXX"`.

3. **Ensuring roles exist**: `RoleService.ensureRoleExists()` enforces `SUPER_ADMIN` authority when creating or managing `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`. Other system roles (ACCOUNTING, FACTORY, SALES, DEALER) can be ensured by non-super-admin actors.

4. **Shared role permission management**: `RoleService.canManageSharedRoleMutation()` returns `false` (with an audit denial) when a non-super-admin user attempts to manage permissions for any `SystemRole`. All such decisions are audit-logged.

### Role Synchronization at Startup

`RbacSynchronizationConfig` runs after application startup (`ApplicationReadyEvent`) and calls `RoleService.synchronizeSystemRoles()` to ensure all `SystemRole` enum values have corresponding `Role` entities with their default permissions seeded. This means:

- New roles added to the `SystemRole` enum are automatically created on next startup.
- New default permissions are reconciled (added but not removed unless retired).
- Retired permissions (e.g., `dispatch.confirm` for `ROLE_SALES`) are automatically removed.

### Role Hierarchy

The `SecurityConfig` enforces: `ROLE_SUPER_ADMIN > ROLE_ADMIN`. This means super-admin implicitly carries admin authority.

## Domain Entities

### admin module

| Entity | Purpose |
| --- | --- |
| `SupportTicket` | Support ticket with category, status, GitHub sync state, and resolution tracking |
| `SupportTicketCategory` | Enum: ticket categories |
| `SupportTicketStatus` | Enum: `OPEN`, etc. |
| `ExportRequest` | Export approval request with report type, parameters, status, approver, and rejection reason |
| `ChangelogEntry` | System changelog entry with title, content, highlighted flag, soft-delete |

### rbac module

| Entity | Purpose |
| --- | --- |
| `Role` | Persisted role entity with name, description, and many-to-many permissions |
| `Permission` | Persisted permission with code and description |
| `SystemRole` | Enum: authoritative definition of platform roles, their descriptions, default permissions, and retired permissions |

### Key DTOs

| DTO | Module | Purpose |
| --- | --- | --- |
| `UserDto`, `CreateUserRequest`, `UpdateUserRequest` | admin | User management payloads |
| `SystemSettingsDto`, `SystemSettingsUpdateRequest` | admin | System settings read/write |
| `AdminApprovalItemDto`, `AdminApprovalsResponse` | admin | Unified approval queue items |
| `ExportRequestDto`, `ExportRequestCreateRequest`, `ExportRequestDecisionRequest` | admin | Export approval payloads |
| `SupportTicketCreateRequest`, `SupportTicketResponse`, `SupportTicketListResponse` | admin | Support ticket payloads (shared between admin and dealer surfaces) |
| `RoleDto`, `CreateRoleRequest`, `PermissionDto` | rbac | Role management payloads |
| `DashboardInsights`, `OperationsInsights`, `WorkforceInsights` | portal | Portal insight payloads |
| `TenantRuntimeMetricsDto` | admin | Tenant runtime metrics |
| `AdminNotifyRequest` | admin | Admin notification payload |
| `ChangelogEntryRequest`, `ChangelogEntryResponse` | admin | Changelog payloads |

## Key Services

| Service | Module | Purpose |
| --- | --- | --- |
| `AdminUserService` | admin | User CRUD, status changes, MFA disable, force-reset, suspend/unsuspend |
| `ChangelogService` | admin | Changelog list, create, update, soft-delete |
| `ExportApprovalService` | admin | Export request lifecycle: create, list pending, approve, reject |
| `TenantRuntimePolicyService` | admin | Per-tenant runtime policy management |
| `PortalInsightsService` | portal | Dashboard, operations, and workforce insight computation |
| `PortalSupportTicketService` | admin | Internal support ticket CRUD (admin/accounting) |
| `DealerPortalSupportTicketService` | admin | Dealer-side support ticket CRUD (dealer-scoped) |
| `RoleService` | rbac | Role listing, creation, synchronization, permission reconciliation, super-admin enforcement |
| `DealerPortalService` | sales | Dealer dashboard, ledger, invoices, aging, orders, invoice PDF |

## Cross-Module Boundaries

| Boundary | Direction | Description |
| --- | --- | --- |
| admin → auth | dependency | `AdminUserService` manages `UserAccount` entities for user CRUD |
| admin → company | dependency | `AdminSettingsController` reads company context for tenant-scoped approvals |
| admin → sales | dependency | `AdminSettingsController` reads credit requests and credit overrides for approval queue |
| admin → accounting | dependency | `AdminSettingsController` reads period-close requests for approval queue |
| admin → hr | dependency | `AdminSettingsController` reads payroll runs for approval queue (when HR_PAYROLL enabled) |
| admin → core/config | dependency | `AdminSettingsController` reads/writes system settings via `SystemSettingsService` |
| admin → core/audit | dependency | `AdminSettingsController` records settings-update audit events |
| admin → core/notification | dependency | `AdminSettingsController` sends notification emails |
| portal → sales | dependency | `PortalFinanceController` uses `DealerPortalService` for dealer finance views |
| rbac → core/audit | dependency | `RoleService` audit-logs role mutation authority decisions |
| sales (dealer-portal) → invoice | dependency | `DealerPortalController` uses `InvoicePdfService` for PDF generation |
| sales (dealer-portal) → admin | dependency | `DealerPortalController` and `DealerPortalSupportTicketController` use admin DTOs |
| sales (dealer-portal) → core/audit | dependency | `DealerPortalController` logs invoice PDF exports via `AuditService` |

## Known Caveats

1. **Support ticket DTOs are shared between admin and dealer surfaces.** Both `PortalSupportTicketController` and `DealerPortalSupportTicketController` use the same `SupportTicketCreateRequest`, `SupportTicketResponse`, and `SupportTicketListResponse` DTOs from the `admin` module. The isolation is enforced by separate controllers and services, not by separate DTOs.

2. **Portal insights are admin-only.** The dashboard, operations, and workforce insights endpoints require `ROLE_ADMIN` exclusively. Accounting and other roles cannot access these insights, even though they share the same `portal` host prefix.

3. **Portal finance requires an explicit dealerId query parameter.** The `PortalFinanceController` does not auto-scope to a dealer; the caller must pass `?dealerId=` to select the target dealer. This is an admin-facing pattern (internal users viewing any dealer), not a self-service pattern.

4. **Settings update requires SUPER_ADMIN, but settings read requires only ADMIN.** This creates a split-read/write boundary where admin users can view settings but cannot change them (including period-lock enforcement, export-approval gating, and platform auth code).

5. **Export approval is optional and controlled by a system setting.** When `exportApprovalRequired = false`, exports bypass the approval workflow entirely. The setting itself is only modifiable by `ROLE_SUPER_ADMIN`.

6. **Role creation requires a known SystemRole name.** `RoleController.createRole()` delegates to `RoleService.persistRole()`, which validates the role name against the `SystemRole` enum. Arbitrary custom role names are not supported.

7. **The SALES role has a retired permission.** `dispatch.confirm` is listed under `retiredPermissions` for `ROLE_SALES`. The startup synchronization will remove this permission from the SALES role, but the credit-limit request create endpoint still accepts `ROLE_SALES`. This means sales users can request credit-limit changes but historically could confirm dispatch — that dispatch authority is now retired.

## Cross-References

- [docs/modules/auth.md](auth.md) — auth module (login, refresh, logout, MFA, token revocation, security filters)
- [docs/modules/company.md](company.md) — company module (tenant lifecycle, runtime admission, module gating, super-admin control plane)
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — canonical module inventory
- [docs/adrs/ADR-006-portal-and-host-boundary-separation.md](../adrs/ADR-006-portal-and-host-boundary-separation.md) — portal/host boundary ADR
- [docs/adrs/ADR-002-multi-tenant-auth-scoping.md](../adrs/ADR-002-multi-tenant-auth-scoping.md) — multi-tenant auth scoping ADR
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — overall architecture reference
- [docs/SECURITY.md](../SECURITY.md) — security review policy
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory
- [docs/flows/tenant-admin-management.md](../flows/tenant-admin-management.md) — canonical tenant/admin management flow (behavioral entrypoint)
