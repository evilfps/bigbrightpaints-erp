# Admin / governance

## Scope and evidence

This review covers admin user management, changelog publishing, support tickets and GitHub sync, system settings/CORS/runtime flags, export approvals, and the adjacent governance controls exposed through the admin approvals and notification surfaces.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/{AdminUserController,AdminSettingsController,ChangelogController,SupportTicketController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/{AdminUserService,ChangelogService,SupportTicketService,SupportTicketGitHubSyncService,GitHubIssueClient,ExportApprovalService,TenantRuntimePolicyService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/{audit/AuditService.java,config/SystemSettingsService.java,notification/EmailService.java,security/{SecurityConfig,CompanyContextFilter}.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/controller/ReportController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/service/RoleService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/domain/{ChangelogEntry,ChangelogEntryRepository,SupportTicket,SupportTicketRepository,ExportRequest,ExportRequestRepository}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V39__export_requests_approval_gate.sql,V40__changelog_entries.sql,V41__support_tickets_github_integration.sql}` and `db/migration_v2/V1__core_auth_rbac.sql`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/{AdminUserSecurityIT,ChangelogControllerSecurityIT,SupportTicketControllerIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/controller/{AdminSettingsControllerApprovalsContractTest,AdminSettingsControllerTenantRuntimeContractTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/service/{AdminUserServiceTest,ChangelogServiceTest,ExportApprovalServiceTest,TenantRuntimePolicyServiceTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports/ReportExportApprovalIT.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeSupportTicketSyncExecutableCoverageTest.java`

Supporting runtime evidence was limited: `curl -i -s http://localhost:8081/actuator/health` returned connection failure (`exit 7`) during this session, so this document relies on static inspection plus existing integration/truth-suite coverage.

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Admin user governance | `GET/POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{id}`, `POST /api/v1/admin/users/{userId}/force-reset-password`, `PUT /api/v1/admin/users/{userId}/status`, `PATCH /api/v1/admin/users/{id}/{suspend|unsuspend}`, `PATCH /api/v1/admin/users/{id}/mfa/disable`, `DELETE /api/v1/admin/users/{id}` | `AdminUserController` | Admin and super-admin surface for account lifecycle, role/company membership, reset, and MFA override. |
| Admin role catalog | `GET /api/v1/admin/roles`, `GET /api/v1/admin/roles/{roleKey}` | `RoleController` | Read-only fixed-role catalog for the admin surface; `ROLE_SUPER_ADMIN` is hidden and role mutation is not exposed here anymore. |
| Changelog publishing | `POST /api/v1/admin/changelog`, `PUT/DELETE /api/v1/admin/changelog/{id}`, public `GET /api/v1/changelog`, `GET /api/v1/changelog/latest-highlighted` | `ChangelogController` | Private writers, public readers. |
| Support desk | `POST /api/v1/support/tickets`, `GET /api/v1/support/tickets`, `GET /api/v1/support/tickets/{ticketId}` | `SupportTicketController` | Authenticated users can create tickets; read visibility depends on role scope. |
| System settings / runtime flags | `GET/PUT /api/v1/admin/settings`, `GET /api/v1/admin/tenant-runtime/metrics`, `PUT /api/v1/companies/{id}/tenant-runtime/policy` | `AdminSettingsController`, `CompanyController` | Keeps global platform settings on admin settings while tenant runtime policy writes live on the company control-plane contract. |
| Export approvals | `POST /api/v1/exports/request`, `GET /api/v1/exports/{requestId}/download`, `GET /api/v1/admin/exports/pending`, `PUT /api/v1/admin/exports/{requestId}/{approve|reject}` | `ReportController`, `AdminSettingsController` | Report export is requested in reports space and approved in admin space. |
| Governance cockpit / operator comms | `GET /api/v1/admin/approvals`, `POST /api/v1/admin/notify` | `AdminSettingsController` | Aggregates pending approval work across modules and allows direct email dispatch. |

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `app_users`, `user_companies`, `roles` | `AdminUserService`, `RoleService`, auth domain repositories | User creation, status changes, tenant membership, MFA resets, password resets, and role attachment. |
| `dealers`, `accounts` | `AdminUserService.createDealerForUser(...)` | Creating a `ROLE_DEALER` user silently provisions or relinks dealer master data and receivable accounts. |
| `changelog_entries` | `ChangelogEntry`, `ChangelogEntryRepository`, `V40__changelog_entries.sql` | Public product-change feed with soft delete and highlighted-entry lookup. |
| `support_tickets` | `SupportTicket`, `SupportTicketRepository`, `V41__support_tickets_github_integration.sql` | Company-scoped local support case store plus GitHub sync metadata. |
| `export_requests` | `ExportRequest`, `ExportRequestRepository`, `V39__export_requests_approval_gate.sql` | Pending/approved/rejected export approvals keyed by company and requester. |
| `system_settings` generic KV | `SystemSettingsService`, `TenantRuntimePolicyService`, `TenantRuntimeEnforcementService`, `TenantRuntimeEnforcementInterceptor`, `V1__core_auth_rbac.sql` | Global CORS/mail/approval flags plus tenant-specific runtime quota keys and runtime counters. |
| `audit_logs` | `AuditService`, audit lookups in `AdminUserService`, auth/audit enums | Best-effort audit trail for changelog, user governance, and settings updates; also used to derive `lastLoginAt`. |
| `openapi.json` | repo snapshot | Documents most admin/governance routes, but currently omits support-ticket surfaces and under-describes approvals payloads. |

## Governance controls

### 1. Admin user management

`AdminUserController` exposes a classic tenant-admin user surface, but the service path is broader than the endpoint names suggest.

`AdminUserService.listUsers()` always resolves the current company from `CompanyContextService` and returns `findDistinctByCompanies_Id(companyId)`. It enriches the payload with `lastLoginAt` by querying `AuditLogRepository` for the latest `LOGIN_SUCCESS` event per email, so the governance UI is partly backed by audit logs rather than user rows.

`createUser(...)` is the densest workflow:

1. Resolve the active company.
2. If the actor is not `ROLE_SUPER_ADMIN`, force every requested `companyId` to equal the active company. A super admin may instead create the user directly inside another tenant without switching sessions.
3. Call `TenantRuntimePolicyService.assertCanAddEnabledUser(...)` for each target tenant before persisting, so admin user creation is quota-governed.
4. Generate a temporary password when none is provided and set `mustChangePassword=true`.
5. Attach companies and roles via the fixed system-role catalog. Admin-surface assignment resolves only persisted platform roles, rejects unknown role names, rejects `ROLE_SUPER_ADMIN` outright, and allows tenant admins to assign the fixed client-facing roles, including `ROLE_ADMIN`.
6. Persist `UserAccount`.
7. If one of the requested roles is `ROLE_DEALER`, auto-create or relink a `Dealer`, assign the portal user, and create or reactivate the dealer receivable account.
8. Send credentials email (best-effort, not required) and write an audit event.

Update and recovery flows are similarly side-effect heavy:

- `updateUser(...)` can change display name, enabled state, company membership, and roles. Any role/company change revokes all access and refresh tokens. `roles=null` preserves current role bindings; `roles=[]` is an explicit scrub that clears all persisted roles, which keeps hidden pre-packet authorities revocable without re-exposing them in admin DTOs.
- `forceResetPassword(...)` delegates to `PasswordResetService.requestResetByAdmin(...)`, which reuses the public reset-link machinery rather than a hard password rewrite.
- `updateUserStatus(...)` uses quota checks when re-enabling a user, revokes all tokens on disablement, sends suspension email, and audits the action.
- `disableMfa(...)` clears the MFA secret and recovery codes, then revokes all tokens.
- `deleteUser(...)` revokes tokens, deletes the row, sends a deletion email, and audits.

Control-boundary notes:

- The controller now uses the shared `PortalRoleActionMatrix.ADMIN_ONLY` guard. `ROLE_SUPER_ADMIN` still reaches the surface through role hierarchy (`SecurityConfig.roleHierarchy()` sets `ROLE_SUPER_ADMIN > ROLE_ADMIN`), but the admin-facing role catalog itself stays read-only, never exposes `ROLE_SUPER_ADMIN` for assignment, and fails fast if the canonical persisted fixed-role set is incomplete.
- `updateUser(...)`, `updateUserStatus(...)`, and `forceResetPassword(...)` support foreign-tenant targeting for super admins through `resolveScopedUserForAdminAction(...)`.
- `suspend(...)`, `unsuspend(...)`, `deleteUser(...)`, and `disableMfa(...)` do **not** use that cross-tenant branch; they pessimistically lock by the actor's active `companyId`, so a super admin hitting a foreign-tenant user through these endpoints gets a silent no-op rather than a clear denial or cross-tenant action.

### 2. Changelog publishing

`ChangelogController` is a public-facing publishing surface hidden behind admin authoring endpoints.

- Writers: `POST/PUT/DELETE /api/v1/admin/changelog...` require admin or super-admin authority.
- Readers: `GET /api/v1/changelog` and `GET /api/v1/changelog/latest-highlighted` are explicitly `permitAll()` in `SecurityConfig`.

`ChangelogService` has no tenant lookup at all. The workflow is global:

1. Validate semver/title/body through `ChangelogEntryRequest`.
2. Persist a row in `changelog_entries` with `createdBy`, `publishedAt`, `highlighted`, and soft-delete markers.
3. Publish the feed immediately; there is no draft, approval, or tenant scoping layer.
4. Record audit metadata with `DATA_CREATE`, `DATA_UPDATE`, or `DATA_DELETE`.

Important behavior details:

- `create(...)` sets `publishedAt` on first save.
- `update(...)` calls `applyRequest(...)`, and because `createdBy` is already populated, the method rewrites `publishedAt` to `now`. Editing an old entry therefore republishes and reorders it.
- `softDelete(...)` marks the row deleted but keeps it in the database. There is no immutable revision history of prior bodies/titles.
- `latestHighlighted()` simply returns the newest highlighted non-deleted row; one update can move a previously old item back to the top.

This is a governance surface because a tenant-scoped admin can change a globally visible public feed without an approval chain.

### 3. Support tickets and GitHub sync

`SupportTicketController` exposes a local support intake API, but the actual workflow spans local persistence, async GitHub creation, scheduled GitHub status sync, and resolution emails.

Creation path:

1. Require an authenticated principal and current company.
2. Validate `category`, `subject`, and `description`.
3. Save a `support_tickets` row with `OPEN` status.
4. Register an `afterCommit` callback that calls `SupportTicketGitHubSyncService.submitGitHubIssueAsync(savedId)`.
5. Return the local ticket immediately.

Role boundaries on reads are explicit:

- `ROLE_SUPER_ADMIN` sees every ticket across every company (`findAllByOrderByCreatedAtDesc()`).
- `ROLE_ADMIN` and `ROLE_ACCOUNTING` see all tickets in the current company.
- Other authenticated users only see their own tickets.

The GitHub integration path is the real side-effect engine:

- `SupportTicketGitHubSyncService.submitGitHubIssueAsync(...)` loads the saved ticket after commit.
- If `GitHubIssueClient.isEnabledAndConfigured()` is false, the ticket stays local and records `githubLastError`.
- Otherwise it creates a GitHub issue using repo owner/name/token from `GitHubProperties`, labels the issue from the category (`bug`, `enhancement`, `support`), and stores issue number/url/state/sync timestamps on the ticket.
- The issue body sends raw ticket description plus tenant `companyCode`, internal `ticketId`, and requester `userId` to GitHub.
- A scheduled poll every five minutes checks open GitHub-linked tickets, mirrors GitHub state back into `support_tickets`, and sends a required templated email when the issue closes.

Recovery behavior is asymmetric:

- Failed **issue creation** only records `githubLastError`; there is no scheduled retry for tickets that never received a GitHub issue number.
- Failed **status sync** is retried later because only already-linked tickets are revisited by the scheduler.
- Resolution email failures also collapse into `githubLastError`, mixing notification failure with GitHub failure state.

### 4. System settings, CORS, and runtime flags

`AdminSettingsController` mixes two very different governance planes: global system settings and tenant-scoped runtime throttles.

#### 4.1 Global settings plane

`GET/PUT /api/v1/admin/settings` reads and mutates `SystemSettingsService`, which persists these keys into the global `system_settings` table:

- `cors.allowed-origins`
- `auto-approval.enabled`
- `period-lock.enforced`
- `export.require-approval`
- `mail.enabled`
- `mail.from`
- `mail.base-url`
- `mail.send-credentials`
- `mail.send-password-reset`

These are runtime-wide values, not company-scoped values. `V1__core_auth_rbac.sql` defines `system_settings(setting_key, setting_value)` with no `company_id`, and `SystemSettingsService` reads/writes these keys without consulting `CompanyContextService`.

`updateSettings(...)` only adds an explicit super-admin guard for `periodLockEnforced`. A plain tenant admin may still change:

- global CORS allow-list,
- global mail delivery toggles and reset/credential delivery behavior,
- global auto-approval,
- global export-approval gating.

`CorsConfig` then turns those persisted values into the live `CorsConfigurationSource`. Validation is stronger than a typical allow-list implementation: wildcards are rejected, prod forbids HTTP/private origins, and normalization strips paths/query fragments. But `buildCorsConfiguration()` still sets `allowCredentials=true`, `allowedHeaders=*`, and the write boundary for those origins is an admin API.

#### 4.2 Tenant runtime plane

`GET /api/v1/admin/tenant-runtime/metrics` and `PUT /api/v1/admin/tenant-runtime/policy` route into `TenantRuntimePolicyService`, which stores per-company keys like `tenant.runtime.hold-state.{companyId}` and `tenant.runtime.max-active-users.{companyId}` in the same `system_settings` table. The service:

- exposes policy and current counters,
- normalizes malformed hold states fail-closed to `BLOCKED`,
- requires a `holdReason` when moving to `HOLD` or `BLOCKED`,
- audits configuration changes with request metadata,
- enforces enabled-user quotas during admin user creation and enablement.

This plane is still not singular. The same key family is also read by:

- `modules.company.service.TenantRuntimeEnforcementService` for auth/request admission,
- `modules.portal.service.TenantRuntimeEnforcementInterceptor` for report/portal/demo rate limits,
- `CompanyContextFilter` for privileged-policy-control path handling.

The three services do not share identical defaults or enforcement surfaces, so runtime policy is conceptually centralized in storage but operationally split across multiple implementations.

### 5. Export approvals

Export governance crosses the reports module and the admin module.

Request path:

1. `ReportController.requestExport(...)` accepts `reportType` and raw `parameters` from an admin/accounting actor.
2. `ExportApprovalService.createRequest(...)` resolves the authenticated actor, verifies that they belong to the active company, uppercases `reportType`, stores raw `parameters`, and persists a `PENDING` row in `export_requests`.

Decision path:

- `GET /api/v1/admin/exports/pending` lists pending rows for the current company.
- `PUT /api/v1/admin/exports/{requestId}/approve` changes state to `APPROVED` and records `approvedBy`/`approvedAt`.
- `PUT /api/v1/admin/exports/{requestId}/reject` changes state to `REJECTED` and stores a rejection reason.

Download gate:

- If `SystemSettingsService.isExportApprovalRequired()` is `true`, `resolveDownload(...)` only allows `APPROVED` requests.
- If the global flag is `false`, `resolveDownload(...)` allows the download regardless of request state, including `REJECTED` rows.

This means export approval is a soft gate rather than a durable decision. The schema even permits `EXPIRED`, but the service never writes that state, never enforces single-use downloads, and never audits request/approval/rejection/download actions inside `ExportApprovalService`.

### 6. Other governance controls

`AdminSettingsController.approvals()` is the admin cockpit for pending governance work. It merges:

- dealer credit requests,
- dispatch credit overrides,
- payroll approvals,
- accounting period close requests,
- export approvals.

The method is explicitly `@Transactional(readOnly = true)` and converts each pending item into an `AdminApprovalItemDto` with action labels, source portals, and approve/reject endpoint templates. This is valuable operationally because it gives the governance UI a unified queue, but it is only a synthesizer: it does not own the actual approval rules.

`POST /api/v1/admin/notify` is a smaller but still sensitive control. It lets any tenant admin send an arbitrary SMTP email through `EmailService.sendSimpleEmail(...)` using the platform-wide mail configuration. The method does not write an audit event of its own.

## Control boundaries

- `ROLE_SUPER_ADMIN` inherits admin endpoint access through role hierarchy, but not every admin operation actually honors cross-tenant intent in the service layer.
- Changelog authoring is tenant-admin writable but globally readable and globally stored.
- Support tickets are company-scoped for tenant admins, user-scoped for ordinary users, and globally readable for super admins.
- `AdminSettingsController` mixes global flags and tenant-scoped runtime policies in one namespace even though only the runtime-policy part is actually tenant-keyed.
- Export approvals are company-scoped, but the switch that decides whether approval matters (`export.require-approval`) is global.
- `CompanyContextFilter` treats `/api/v1/admin/tenant-runtime/policy` and canonical `/api/v1/companies/{id}/tenant-runtime/policy` as privileged policy-control paths, so control-plane authorization is path-sensitive.

## Side effects and integrations

- User creation can create or mutate `Dealer` and `Account` records when dealer roles are assigned.
- User disable/delete/MFA-disable flows revoke access and refresh tokens and may send outbound email.
- Changelog create/update/delete immediately changes a public unauthenticated feed.
- Support ticket creation schedules asynchronous GitHub issue creation after transaction commit.
- GitHub sync writes issue metadata back into local tickets and can send required resolution email.
- System settings updates mutate live CORS behavior, mail delivery behavior, auto-approval, export gating, and period-lock enforcement.
- Tenant runtime policy updates write counters/policy into `system_settings` and emit audit metadata containing request identifiers, trace ids, IP addresses, and user agents.
- Export approvals gate downloads across report endpoints but do not themselves generate audit events.
- Most admin-governance audit writes use `AuditService`, which is asynchronous and explicitly fail-open on persistence errors.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| critical | governance / security | `GET/PUT /api/v1/admin/settings` is tenant-admin accessible, but `SystemSettingsService` persists global `system_settings` keys with no tenant scope. Any tenant admin can rewrite platform-wide CORS, mail, auto-approval, and export-approval behavior. | `AdminSettingsController.updateSettings(...)`, `SystemSettingsService`, `V1__core_auth_rbac.sql` | A single tenant admin can change security perimeter and operational policy for every tenant, including reset-email delivery and trusted browser origins. |
| high | governance / integrity | Changelog publishing is globally stored and publicly readable, yet any tenant admin can create, update, or soft-delete entries. Updates also rewrite `publishedAt`, effectively republishing old entries. | `ChangelogController`, `ChangelogService.applyRequest(...)`, `ChangelogEntry`, `SecurityConfig`, `V40__changelog_entries.sql` | Tenant-local admins can alter the product-wide public announcement feed without maker-checker review or immutable revision history. |
| high | privacy / third-party integration | Support ticket GitHub sync exports raw ticket descriptions plus internal company code and requester user id to the configured GitHub repository. | `SupportTicketGitHubSyncService.buildIssueBody(...)`, `GitHubIssueClient`, `GitHubProperties` | Sensitive support data leaves the ERP boundary and lands in an external system with no redaction or per-category data minimization. |
| high | resilience / workflow integrity | Support ticket creation returns success before GitHub sync completes, and failed issue creation has no retry loop for unsynced tickets. | `SupportTicketService.create(...)`, `SupportTicketGitHubSyncService.submitGitHubIssueAsync(...)`, `syncGitHubIssueStatuses()` | Operators and end users can believe a ticket is fully escalated even though only the local row exists; external escalation may silently stall forever. |
| high | governance / audit | Export approval request/approve/reject/download flows mutate governance state but do not emit audit events from `ExportApprovalService`. | `ExportApprovalService`, `ReportController`, `AdminSettingsController` | Sensitive data-export decisions lack a first-class audit trail, weakening forensic review and compliance evidence. |
| high | approval semantics | When `export.require-approval=false`, `resolveDownload(...)` allows downloads for any stored request state, including `REJECTED` rows. | `ExportApprovalService.resolveDownload(...)`, `ReportExportApprovalIT.exportDownload_bypassesApprovalWhenSettingDisabled()` | A later flag flip can nullify an explicit rejection, so export approval is not a durable governance decision. |
| medium | control-boundary consistency | Super admins can update and force-reset foreign-tenant users, but suspend/unsuspend/delete/MFA-disable paths only lock within the active tenant and silently no-op on foreign users. | `AdminUserService.{updateUser,forceResetPassword,updateUserStatus,suspend,unsuspend,deleteUser,disableMfa}` | Incident-response authority is inconsistent across adjacent admin controls, which is dangerous during compromised-account handling. |
| medium | cross-tenant lock amplification | Masked tenant-admin actions now globally lock foreign user rows before scope checks on suspend/unsuspend/delete/MFA-disable paths. The caller still receives the masked `User not found` contract, but the foreign row is pessimistically write-locked for the duration of the transaction. | PR review on `AdminUserService.resolveScopedUserForAdminAction(...)`, `userRepository.lockById(...)`, masked admin user-control endpoints | A tenant admin who guesses foreign user IDs can block legitimate admin work against those rows without ever seeing a different response, turning lookup masking into a cross-tenant contention vector. |
| medium | hidden side effects / design | Creating a user with `ROLE_DEALER` auto-provisions or relinks dealer master data and receivable accounts from the admin-user surface. | `AdminUserService.createUser(...)`, `createDealerForUser(...)`, `AdminUserServiceTest.createUser_relinksExistingDealerByEmailAndReactivatesReceivableAccount()` | A user-management action crosses into sales/accounting state and can unexpectedly reactivate downstream financial objects. |
| medium | API / OpenAPI drift | Support ticket endpoints are missing from `openapi.json`, and `AdminApprovalsResponse` in OpenAPI omits `periodCloseRequests`. | `SupportTicketController`, `openapi.json`, `AdminApprovalsResponse.java`, `AdminSettingsControllerApprovalsContractTest` | Client generation, review tooling, and operator docs understate the real governance surface and miss one approval lane entirely. |
| medium | design / runtime governance | Tenant runtime policy is split across `TenantRuntimePolicyService`, `modules.company.service.TenantRuntimeEnforcementService`, and `modules.portal.service.TenantRuntimeEnforcementInterceptor`, with different defaults and enforcement surfaces. | `TenantRuntimePolicyService`, `modules.company.service.TenantRuntimeEnforcementService`, `modules.portal.service.TenantRuntimeEnforcementInterceptor` | Operators can believe they changed one quota model while a different runtime path still enforces another default. |
| medium | API / route drift | `GET /api/v1/admin/settings/policy` currently returns `404`, even though the surrounding admin-settings surface suggests a dedicated policy sub-route. | live backend probe on `GET /api/v1/admin/settings/policy`, `AdminSettingsController` route surface | Frontend/operator code can assume a separable policy endpoint exists and only discover at runtime that the backend has no live route there. |
| medium | observability / abuse prevention | `POST /api/v1/admin/notify` sends arbitrary SMTP email through shared mail config but does not create its own audit event. | `AdminSettingsController.notifyUser(...)`, `EmailService.sendSimpleEmail(...)` | This is a phishing/spam-capable admin surface with weak traceability. |
| low | protocol drift | OpenAPI documents `DELETE /api/v1/admin/changelog/{id}` as `200`, while the controller returns `204 No Content`. | `ChangelogController.delete(...)`, `openapi.json` | Generated clients and automated contract checks can build the wrong response expectations for a governance endpoint. |

## Security, privacy, protocol, observability, and bad-pattern notes

### Strengths

- `ChangelogEntryRequest` enforces semver input and bounded title length.
- `TenantRuntimePolicyService` is mostly fail-closed: malformed hold states normalize to `BLOCKED`, quota values must stay positive, and quota denials are audited with request metadata.
- CORS origin validation rejects wildcards and rejects non-HTTPS origins in prod profile.
- Support ticket visibility is role-sensitive and hides foreign-tenant tickets from tenant admins and ordinary users.
- Admin-user role assignment is hard-cut to the fixed six-role model: admin surfaces resolve persisted system roles only, `ROLE_SUPER_ADMIN` is platform-owner only, and missing fixed-role rows are treated as invalid platform state rather than silently synthesized or hidden.

### Bad patterns and hotspots

- The admin namespace hides both global configuration and tenant-local controls behind the same `/api/v1/admin` surface, even though their blast radii are very different.
- `AuditService` is intentionally async and fail-open. That protects request latency, but it means governance evidence is best-effort, not guaranteed.
- `SystemSettingsDto` exposes mail sender and base URL details to tenant admins even though those values are global and operationally sensitive.
- Export approval has no maker-checker protection; an admin can request an export and later approve it from the same company context.
- Support-ticket resolved-notification errors are stored in `githubLastError`, overloading one field with integration, sync, and notification failures.
- The masked-foreign-user fix needs to stay scope-safe: lookup masking should not acquire global write locks on foreign-tenant rows before company membership is verified.

## Evidence notes

- `AdminUserSecurityIT` proves cross-company protection for tenant admins, foreign-tenant force reset for super admins, quota enforcement on user creation, and super-admin-only runtime-policy updates.
- `AdminUserServiceTest` proves dealer/receivable-account side effects during dealer-user creation.
- `ChangelogControllerSecurityIT` proves tenant-admin/super-admin authoring and unauthenticated public read access.
- `SupportTicketControllerIT` proves role-scoped support-ticket visibility across requester/admin/super-admin actors.
- `TS_RuntimeSupportTicketSyncExecutableCoverageTest` proves category-to-label mapping and resolution-email behavior on GitHub close.
- `ReportExportApprovalIT` proves the end-to-end request -> approve -> download flow and also proves that disabling the global gate allows download of rejected requests.
- `AdminSettingsControllerApprovalsContractTest` proves the approvals cockpit now contains period-close approvals even though `openapi.json` still omits that field from the schema.
- `python3` inspection of `openapi.json` during this session confirmed that `/api/v1/support/tickets` and `/api/v1/support/tickets/{ticketId}` are absent from the published API snapshot.
