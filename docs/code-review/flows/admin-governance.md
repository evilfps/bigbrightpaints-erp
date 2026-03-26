# Admin / governance

## Scope and evidence

This review covers admin user management, settings, approvals, support tickets, export governance, and changelog ownership after the ERP-37 control-plane hard cut.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/{AdminUserController,AdminSettingsController,ChangelogController,SuperAdminChangelogController,SupportTicketController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/{AdminUserService,ChangelogService,SupportTicketService,SupportTicketGitHubSyncService,ExportApprovalService,TenantRuntimePolicyService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{SecurityConfig,CompanyContextFilter}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V39__export_requests_approval_gate.sql,V40__changelog_entries.sql,V41__support_tickets_github_integration.sql,V167__erp37_superadmin_control_plane_hard_cut.sql}`
- `openapi.json`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/{AdminUserSecurityIT,ChangelogControllerSecurityIT,SupportTicketControllerIT}.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/controller/{AdminSettingsControllerApprovalsContractTest,AdminSettingsControllerTenantRuntimeContractTest}.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Admin user governance | `GET/POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}`, `PUT /api/v1/admin/users/{userId}/status`, `POST /api/v1/admin/users/{userId}/force-reset-password`, `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, `DELETE /api/v1/admin/users/{id}` | `AdminUserController` | Tenant-admin operational surface with superadmin inheritance via role hierarchy. |
| Settings and governance inbox | `GET/PUT /api/v1/admin/settings`, `GET /api/v1/admin/approvals`, `POST /api/v1/admin/notify`, `PUT /api/v1/admin/exports/{requestId}/{approve|reject}` | `AdminSettingsController` | Reads remain admin-accessible; settings mutation is superadmin-only; approvals stay tenant-scoped. |
| Support desk | `POST /api/v1/support/tickets`, `GET /api/v1/support/tickets`, `GET /api/v1/support/tickets/{ticketId}` | `SupportTicketController` | Fully present in the current OpenAPI contract and role-scoped in reads. |
| Changelog reads | `GET /api/v1/changelog`, `GET /api/v1/changelog/latest-highlighted` | `ChangelogController` | Reads require authentication and are no longer public. |
| Changelog writes | `POST /api/v1/superadmin/changelog`, `PUT /api/v1/superadmin/changelog/{id}`, `DELETE /api/v1/superadmin/changelog/{id}` | `SuperAdminChangelogController` | Global write ownership moved to superadmin-only control. |

## Current authority boundaries

### Admin users

`AdminUserController` remains the operational user-management surface.

Current behavior:

- tenant admins and superadmins can list and manage users in scope
- superadmins inherit admin access through role hierarchy
- status changes, password-reset requests, MFA disable, and deletion still revoke active sessions
- foreign-tenant targeting remains masked for tenant admins and explicitly allowed only on the intended superadmin branches

### Settings

`AdminSettingsController` now splits read and write authority clearly:

- `GET /api/v1/admin/settings` uses `PortalRoleActionMatrix.ADMIN_ONLY`
- `PUT /api/v1/admin/settings` uses `PortalRoleActionMatrix.SUPER_ADMIN_ONLY`

This preserves one settings surface while stopping tenant admins from mutating global platform configuration.

### Approvals and exports

`GET /api/v1/admin/approvals` stays the tenant-scoped governance inbox for:

- credit requests
- credit overrides
- payroll approvals
- period-close approvals
- export approvals

Export decision routes remain under `/api/v1/admin/exports/{requestId}/approve|reject`. Platform superadmins do not use a separate approval prefix here.

### Support tickets

Support-ticket behavior remains:

- authenticated creation in the current tenant context
- tenant-admin and accounting read visibility within the tenant
- requester-only visibility for ordinary users
- global visibility for superadmins
- asynchronous GitHub sync after local persistence

### Changelog ownership

ERP-37 hard-cuts changelog governance:

- tenant-admin write access under `/api/v1/admin/changelog*` is gone
- superadmin-only write access now lives under `/api/v1/superadmin/changelog*`
- changelog reads are authenticated-only under `/api/v1/changelog*`
- OpenAPI now documents the delete response as `204 No Content`, matching runtime behavior

## Data path and stores

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `app_users`, `user_companies`, `roles` | `AdminUserService`, auth repositories | User lifecycle, role binding, tenant membership, MFA disable, reset, and delete flows. |
| `support_tickets` | `SupportTicket`, `SupportTicketRepository`, `V41__support_tickets_github_integration.sql` | Local support-case source of truth plus GitHub sync metadata. |
| `changelog_entries` | `ChangelogEntry`, `ChangelogEntryRepository`, `V40__changelog_entries.sql` | Authenticated read feed and superadmin-owned write surface. |
| `export_requests` | `ExportRequest`, `ExportRequestRepository`, `V39__export_requests_approval_gate.sql` | Tenant-scoped export approval state. |
| `system_settings` | `SystemSettingsService`, `AdminSettingsController` | Global platform configuration snapshot and updates. |

## Invariants

- Changelog writes are superadmin-only and global.
- Changelog reads require authentication.
- Support-ticket routes are published in `openapi.json`.
- `PUT /api/v1/admin/settings` is superadmin-only; tenant admins retain read access only.
- Export approvals remain tenant-admin workflow items under `/api/v1/admin/**`.
- No `/api/v1/admin/changelog*` write surface survives.

## Residual risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| medium | observability | `POST /api/v1/admin/notify` still sends arbitrary SMTP mail without a dedicated audit event of its own. | `AdminSettingsController.notifyUser(...)` | This is still an operator-controlled outbound mail surface with weaker traceability than the rest of the governance controls. |
| medium | workflow integrity | Support-ticket creation still returns success before GitHub sync completes. | `SupportTicketService.create(...)`, `SupportTicketGitHubSyncService.submitGitHubIssueAsync(...)` | Local persistence is authoritative, but operators still need to watch sync status rather than assume external escalation succeeded immediately. |

## Evidence notes

- `ChangelogControllerSecurityIT` proves superadmin-only writes and authenticated reads.
- `SupportTicketControllerIT` proves support-ticket visibility across requester, tenant-admin, and superadmin actors.
- `AdminUserSecurityIT` proves user-governance authority boundaries.
- `AdminSettingsControllerApprovalsContractTest` proves the approvals cockpit payload includes current approval lanes including period-close rows.
