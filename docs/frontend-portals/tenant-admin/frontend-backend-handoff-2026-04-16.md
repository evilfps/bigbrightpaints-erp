# Tenant-Admin Backend Hard-Cut: Frontend Handoff (2026-04-16)

## Scope and branch

- Branch: `refactor-admin`
- Base comparison: `origin/main`
- Current head at handoff write time: `dd1ea49f6`
- This handoff covers tenant-admin backend hard-cut slices plus PR #192 review-fix updates.

## What changed overall in this branch

### Completed backend slices

1. Tenant-admin contract hard-cut and endpoint inventory cleanup.
2. Tenant-admin user-management rewrite with fixed-role assignment policy.
3. Approval orchestration hard-cut to one generic decision endpoint.
4. Canonical tenant-admin dashboard read model.
5. Tenant-admin support ownership on admin host.
6. Tenant-admin self settings read model and utility split.
7. Documentation realignment for frontend portal contracts.
8. Dashboard approval-summary performance optimization (counts-only path).
9. Regression fix to align pending count semantics with inbox (`upper(trim(status))='PENDING'`).
10. Database index hardening for normalized pending-status lookups (`V183`) to keep dashboard/inbox queries performant.

### PR #192 review-fix slice (latest)

1. `AdminApprovalService`: removed unused payroll `reason` parameter and hardened null-safe status normalization.
2. `AdminDashboardService`: hardened null-safe actor-key normalization.
3. `CoreFallbackExceptionHandler`: removed unused `java.io.IOException` import.
4. `CompanyContextFilter`: added explicit audit logging for `SUPER_ADMIN_PLATFORM_ONLY` 403 denials before filter exit.
5. `UpdateUserRequest`: made optional `roles` intent explicit (`@Nullable` + schema metadata) while preserving non-empty validation when roles are provided.
6. `api-contracts.md`: clarified omission semantics for update roles (`omit => keep existing assignments`).

### Security/control-plane posture already enforced

- Tenant-admin cannot assign privileged roles (`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`) or custom roles.
- Privileged targets are masked from tenant-admin user-management reads/mutations as not-found.
- Platform control-plane hosts for settings/roles/notify are superadmin-only under `/api/v1/superadmin/**`.
- Approval decision validation is origin-specific and fail-closed.

## Frontend-impacting API route diff (vs `origin/main`)

### Added routes

- `GET /api/v1/admin/dashboard`
- `GET /api/v1/admin/self/settings`
- `GET /api/v1/admin/support/tickets`
- `POST /api/v1/admin/support/tickets`
- `GET /api/v1/admin/support/tickets/{ticketId}`
- `POST /api/v1/admin/approvals/{originType}/{id}/decisions`
- `GET /api/v1/superadmin/settings`
- `PUT /api/v1/superadmin/settings`
- `GET /api/v1/superadmin/roles`
- `POST /api/v1/superadmin/roles`
- `GET /api/v1/superadmin/roles/{roleKey}`
- `POST /api/v1/superadmin/notify`

### Removed routes

- `PUT /api/v1/admin/exports/{requestId}/approve`
- `PUT /api/v1/admin/exports/{requestId}/reject`
- `POST /api/v1/admin/notify`
- `GET /api/v1/admin/roles`
- `POST /api/v1/admin/roles`
- `GET /api/v1/admin/roles/{roleKey}`
- `GET /api/v1/admin/settings`
- `PUT /api/v1/admin/settings`

## Request/response contract deltas you must apply in frontend

### User management payload updates

- `UpdateUserRequest`:
  - `enabled` removed.
  - `displayName` required.
  - `roles` is optional; when provided it is treated as the full desired set and must be non-empty.
  - Omitting `roles` keeps existing role assignments unchanged.
- Status toggle moved to dedicated endpoint:
  - `PUT /api/v1/admin/users/{userId}/status`
  - request body: `{ "enabled": true|false }` (`UpdateUserStatusRequest`).

### Approval action model change

- Export-specific approve/reject routes are retired.
- Use:
  - `POST /api/v1/admin/approvals/{originType}/{id}/decisions`
  - request body (`AdminApprovalDecisionRequest`):
    - `decision` required (`APPROVE|REJECT`)
    - `reason` required for `CREDIT_REQUEST`, `CREDIT_LIMIT_OVERRIDE_REQUEST`, `PERIOD_CLOSE_REQUEST`
    - `expiresAt` used for override decisions when relevant

### New tenant-admin read models

- `GET /api/v1/admin/dashboard` returns `AdminDashboardDto` with:
  - `approvalSummary`
  - `userSummary`
  - `supportSummary`
  - `tenantRuntime`
  - `securitySummary`
  - `recentActivity`
- `GET /api/v1/admin/self/settings` returns `AdminSelfSettingsDto` with:
  - `email`, `displayName`, `companyCode`
  - `mfaEnabled`, `mustChangePassword`
  - `roles`, `tenantRuntime`, `activeSessionEstimate`

### Support surface ownership

- Tenant-admin support UI should use only:
  - `GET/POST /api/v1/admin/support/tickets`
  - `GET /api/v1/admin/support/tickets/{ticketId}`
- Do not route tenant-admin support workflows to portal-hosted support endpoints.

## Behavior-level frontend rules to keep

- Bootstrap shell using `GET /api/v1/auth/me`.
- If `mustChangePassword=true`, force password-change corridor before normal tenant-admin routes.
- Persist and send tenant scope with `X-Company-Code` (never `X-Company-Id`).
- Treat hidden privileged users as masked not-found targets in UI flows (do not show privileged-management affordances from tenant-admin shell).

## Performance note for dashboard counters

- Backend dashboard approval counters now use direct count queries, not full inbox hydration.
- Pending count semantics are normalized to the same rule as inbox queries (`upper(trim(status))='PENDING'`) to avoid count/list drift.

## Frontend migration checklist (recommended order)

1. Replace any direct calls to retired admin settings/roles/notify routes.
2. Migrate approval action UI to generic decisions endpoint.
3. Update user edit form payload (`displayName` + full `roles` set) and move enable/disable to `/status`.
4. Wire dashboard page to `GET /api/v1/admin/dashboard`.
5. Wire tenant-admin settings page/profile panel to `GET /api/v1/admin/self/settings`.
6. Move tenant-admin support pages to `/api/v1/admin/support/tickets/**`.
7. Verify corridor behavior for `mustChangePassword`.
8. Re-run portal E2E journeys for tenant-admin approval/user/support/settings flows.

## Verification approach used on backend branch

- Slice-focused Java verification was run incrementally after changes (targeted service/security/integration tests).
- Latest PR #192 review-fix slice validation:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=AdminApprovalServiceTest,CompanyContextFilterControlPlaneBindingTest,CoreFallbackExceptionHandlerTest,AdminUserServiceTest test`
  - Result: pass (exit 0).
- Note: `AdminUserSecurityIT` currently fails to bootstrap due a pre-existing test application bean-name conflict (`reportsInventoryValuationService`) unrelated to this slice.
- Policy and governance gates for high-risk/migration surfaces:
  - `bash ci/check-codex-review-guidelines.sh` -> pass
  - `bash ci/check-enterprise-policy.sh` -> pass
- Existing branch docs also capture broader gate contracts and evidence paths:
  - `docs/flows/tenant-admin-management.md`
  - `docs/approvals/R2-CHECKPOINT.md`

## What is left in the implementation plan

### Backend

- Core tenant-admin hard-cut slices are implemented in this branch.
- Remaining backend work is merge-readiness packaging:
  - final PR review pass
  - final gate reruns as needed in PR context
  - resolve any new review findings if they appear

### Frontend

- Apply contract migration checklist above.
- Validate route ownership split between tenant-admin and superadmin shells.
- Re-baseline frontend tests against the generic approvals decision model and new admin read models.
