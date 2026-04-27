# Tenant / Admin Management Flow

Last reviewed: 2026-04-15

This packet describes the canonical behavior for tenant and admin management after the tenant-admin hard-cut refactor.

## Product Boundary

### Tenant-admin product (in scope)

- Dashboard
- User management
- Approval inbox + decisions
- Tenant audit feed
- Internal support tickets (admin host)
- Self-settings read model
- Tenant changelog reads

### Control-plane (out of tenant-admin scope)

- Tenant onboarding
- Tenant lifecycle transitions
- Module enable/disable
- Tenant limit and quota mutation
- Support recovery operations
- Platform changelog publishing

All control-plane behavior stays under `/api/v1/superadmin/**`.

## Actors

| Actor | Role | Scope |
| --- | --- | --- |
| Superadmin | `ROLE_SUPER_ADMIN` | Platform control plane only |
| Tenant admin | `ROLE_ADMIN` | Tenant-admin product workflows |
| Tenant user | non-admin role | Self-service module workflows |
| Accounting | `ROLE_ACCOUNTING` | Accounting/portal workflows, not tenant-admin shell |

## Entrypoints

### Superadmin control-plane entrypoints

- `POST /api/v1/superadmin/tenants/onboard`
- `GET /api/v1/superadmin/tenants`
- `GET /api/v1/superadmin/tenants/{id}`
- `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
- `PUT /api/v1/superadmin/tenants/{id}/limits`
- `PUT /api/v1/superadmin/tenants/{id}/modules`
- `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`
- `POST /api/v1/superadmin/tenants/{id}/support/warnings`
- `PUT /api/v1/superadmin/tenants/{id}/support/context`
- `POST /api/v1/superadmin/changelog`
- `POST /api/v1/superadmin/notify`

### Tenant-admin product entrypoints

| Workflow | Method + path |
| --- | --- |
| Dashboard | `GET /api/v1/admin/dashboard` |
| User list/create | `GET, POST /api/v1/admin/users` |
| User detail/update/delete | `GET, PUT, DELETE /api/v1/admin/users/{id}` |
| User status | `PUT /api/v1/admin/users/{userId}/status` |
| User suspend/unsuspend | `PATCH /api/v1/admin/users/{id}/suspend`, `PATCH /api/v1/admin/users/{id}/unsuspend` |
| User MFA disable | `PATCH /api/v1/admin/users/{id}/mfa/disable` |
| User reset link | `POST /api/v1/admin/users/{userId}/force-reset-password` |
| Approval inbox | `GET /api/v1/admin/approvals` |
| Approval decision | `POST /api/v1/admin/approvals/{originType}/{id}/decisions` |
| Audit feed | `GET /api/v1/admin/audit/events` |
| Internal support | `POST, GET /api/v1/admin/support/tickets`, `GET /api/v1/admin/support/tickets/{ticketId}` |
| Self settings | `GET /api/v1/admin/self/settings` |
| Tenant changelog | `GET /api/v1/changelog`, `GET /api/v1/changelog/latest-highlighted` |

## Preconditions

### Tenant-admin session preconditions

1. Caller must authenticate and pass `GET /api/v1/auth/me`.
2. Tenant-scoped requests must use `X-Company-Code`.
3. If `mustChangePassword=true`, user must complete `POST /api/v1/auth/password/change` before normal shell routes.

### Tenant-admin user-management preconditions

1. Caller must be `ROLE_ADMIN` in the active tenant.
2. Target user must be in the same tenant scope.
3. Assignable roles are fixed to:
   - `ROLE_ACCOUNTING`
   - `ROLE_FACTORY`
   - `ROLE_SALES`
   - `ROLE_DEALER`
4. Unknown/blank/custom roles are rejected.

### Tenant-admin approval preconditions

1. Caller must be tenant admin (`ROLE_ADMIN`).
2. `originType` must be one of:
   - `EXPORT_REQUEST`
   - `CREDIT_REQUEST`
   - `CREDIT_LIMIT_OVERRIDE_REQUEST`
   - `PAYROLL_RUN`
   - `PERIOD_CLOSE_REQUEST`
3. Decision request must include `decision=APPROVE|REJECT`.
4. Decision semantics are origin-specific:
   - `CREDIT_REQUEST`: reason is required for approve/reject.
   - `CREDIT_LIMIT_OVERRIDE_REQUEST`: reason is required; `expiresAt` may be required by workflow policy.
   - `PAYROLL_RUN`: only approve is supported; reject fails validation.
   - `PERIOD_CLOSE_REQUEST`: reason is required for approve/reject; workflow force posture remains request-owned.

## Lifecycle Flows

### 1) Session bootstrap and corridor

```text
GET /api/v1/auth/me
  -> mustChangePassword=true ? force password-change corridor : proceed to tenant-admin shell
```

### 2) Dashboard

```text
GET /api/v1/admin/dashboard
  -> returns activity + approval + user + support + runtime + security summary
```

### 3) User lifecycle

```text
Create -> validate fixed role set -> provision scoped account -> optional dealer provisioning for ROLE_DEALER
Update -> validate fixed role set -> apply display/role changes -> revoke tokens when role set changes
Disable/Suspend/Delete/MFA disable -> scoped target checks -> revoke sessions/tokens where required -> audit
Force reset link -> scoped target checks -> password reset token + mail dispatch -> audit
```

### 4) Approval lifecycle

```text
GET /api/v1/admin/approvals
  -> normalized item list + pendingCount
POST /api/v1/admin/approvals/{originType}/{id}/decisions
  -> domain-delegated decision with origin-specific validation rules
  -> returns normalized updated item
```

### 5) Internal support lifecycle

```text
POST /api/v1/admin/support/tickets
  -> ticket created in tenant scope
GET /api/v1/admin/support/tickets
GET /api/v1/admin/support/tickets/{ticketId}
  -> list/detail and sync state visibility
```

### 6) Self settings lifecycle

```text
GET /api/v1/admin/self/settings
  -> identity + MFA + mustChangePassword + role list + runtime metrics + active session estimate
```

## Canonical vs Retired Paths

| Retired for tenant-admin product | Canonical |
| --- | --- |
| Export-specific approve/reject aliases | `POST /api/v1/admin/approvals/{originType}/{id}/decisions` |
| `/api/v1/portal/support/tickets/**` as tenant-admin host | `/api/v1/admin/support/tickets/**` |
| Tenant-admin dependency on platform role-catalog hosts (`/api/v1/superadmin/roles/**`) | fixed role assignment validation in `/api/v1/admin/users/**` |
| `/api/v1/auth/profile` as identity source | `GET /api/v1/auth/me` |

## Completion Boundary

Flow is complete when:

1. Superadmin control-plane actions remain isolated to `/api/v1/superadmin/**`.
2. Tenant-admin primary screens run on canonical admin product surfaces (`/api/v1/admin/**`).
3. Legacy portal insights reads (`/api/v1/portal/dashboard|operations|workforce`) remain live `ROLE_ADMIN` read surfaces until backend retirement.
4. Tenant-admin approval actions happen only through generic admin decisions endpoint with origin-specific decision constraints.
4. Tenant-admin support runs on admin host, not portal host.
5. User role assignment remains fixed-list and escalation-proof.
6. Tenant-admin self-settings use `/api/v1/admin/self/settings` + auth-owned self-service flows.

## Implementation Plan (Executed Slices)

This is the canonical implementation order and status for the tenant-admin hard-cut refactor.

### Slice 1: Contract inventory + hard-cut removals (complete)

- Tenant-admin product ownership moved to canonical `/api/v1/admin/**` surfaces.
- Tenant-admin role creation/custom-role dependency removed from product contract.
- Portal-hosted tenant-admin support ownership retired in favor of admin host.
- Legacy and canonical boundaries aligned in endpoint inventory + portal docs.

### Slice 2: User management rewrite (complete)

- `AdminUserController` + `AdminUserService` now enforce fixed assignable roles only.
- Tenant-admin user list/detail/mutations mask privileged identities (`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`) as not found.
- User lifecycle actions remain tenant-scoped and audit-emitting.

### Slice 3: Approval system rewrite (complete)

- Canonical approval inbox contract: `GET /api/v1/admin/approvals`.
- Canonical generic decision contract: `POST /api/v1/admin/approvals/{originType}/{id}/decisions`.
- Origin-specific decision constraints enforced in admin orchestration:
  - credit and credit-override require nonblank reason
  - payroll reject blocked (approve-only)
  - period-close force posture preserved

### Slice 4: Dashboard read model (complete)

- Canonical dashboard endpoint: `GET /api/v1/admin/dashboard`.
- Aggregates approval, user, support, runtime, security, and recent activity summaries.
- User-summary and activity rows now follow tenant-admin visibility masking for privileged identities (`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`).
- No quota/runtime mutation behavior is exposed from dashboard.

### Slice 5: Support rewrite (complete)

- Tenant-admin internal support is owned by `/api/v1/admin/support/tickets/**`.
- Portal support host remains accounting-owned and out of tenant-admin ownership.
- Sync state visibility remains scoped to support DTOs only.

### Slice 6: Settings/self-service rewrite (complete)

- Canonical tenant-admin settings payload: `GET /api/v1/admin/self/settings`.
- Self security and password/MFA flows remain auth-owned (`/api/v1/auth/**`).
- Utility notify action moved to superadmin control-plane host (`POST /api/v1/superadmin/notify`).

### Slice 7: Documentation realignment (complete)

- Updated docs:
  - `docs/modules/admin-portal-rbac.md`
  - `docs/flows/tenant-admin-management.md`
  - `docs/frontend-portals/tenant-admin/api-contracts.md`
  - `docs/frontend-portals/tenant-admin/routes.md`
  - `docs/frontend-portals/tenant-admin/role-boundaries.md`
  - `docs/frontend-api/admin-role.md`
  - `docs/frontend-api/auth-and-company-scope.md`
  - `docs/endpoint-inventory.md`

## Verification Contract

Run these in `bigbrightpaints-erp_worktrees/tenant-admin-hardcut-s1`:

- `bash scripts/guard_openapi_contract_drift.sh`
- `bash scripts/guard_accounting_portal_scope_contract.sh`
- `bash ci/lint-knowledgebase.sh`
- `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=AdminUserServiceTest,AdminApprovalServiceTest,AdminApprovalControllerContractTest test`
- Colima + curl contract checks:
  - tenant-admin user list excludes privileged targets
  - privileged-target and missing-target user mutations return the same masked not-found contract
  - credit-override generic decision rejects blank reason
  - payroll generic decision rejects `REJECT`

## Related Docs

- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md)
- [docs/frontend-portals/tenant-admin/api-contracts.md](../frontend-portals/tenant-admin/api-contracts.md)
- [docs/frontend-api/admin-role.md](../frontend-api/admin-role.md)
