# API Contracts

Last reviewed: 2026-04-15

## Shared transport rules

- Bootstrap shell state with `GET /api/v1/auth/me`.
- For tenant-scoped routes, send `X-Company-Code` with the active `companyCode`.
- Never send `X-Company-Id`.
- Do not persist numeric tenant IDs for tenant-admin shell behavior.

## Auth and self-service dependencies

Tenant-admin settings/self flows build on auth-owned self-service APIs:

- `GET /api/v1/auth/me`
- `POST /api/v1/auth/password/change`
- `POST /api/v1/auth/password/forgot`
- `POST /api/v1/auth/password/reset`
- `POST /api/v1/auth/mfa/setup`
- `POST /api/v1/auth/mfa/activate`
- `POST /api/v1/auth/mfa/disable`

If `mustChangePassword=true` in `/auth/me`, frontend must route to password change before normal tenant-admin routes.

## Dashboard

### `GET /api/v1/admin/dashboard`

Response: `ApiResponse<AdminDashboardDto>`

Top-level fields:

- `recentActivity[]`
- `approvalSummary`
- `userSummary`
- `supportSummary`
- `tenantRuntime`
- `securitySummary`

Visibility boundary:

- `userSummary` excludes privileged identities (`ROLE_ADMIN`, `ROLE_SUPER_ADMIN`) from tenant-admin-visible counts.
- `recentActivity` filters privileged-actor rows so hidden identities are not exposed via dashboard activity feed.

## User management: `/api/v1/admin/users/**`

### `GET /api/v1/admin/users`

Returns `ApiResponse<List<UserDto>>`.

Visibility boundary:

- Tenant-admin user-management list excludes identities holding `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.

### `GET /api/v1/admin/users/{id}`

Returns `ApiResponse<UserDto>`.

Scope boundary:

- Identities holding `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` are out of scope for tenant-admin user-management reads.

### `POST /api/v1/admin/users`

Request body (`CreateUserRequest`):

| Field | Type | Required | Contract |
| --- | --- | --- | --- |
| `email` | string | yes | valid email |
| `displayName` | string | yes | non-blank |
| `roles` | string[] | yes | non-empty, fixed assignable set only |

Tenant-admin assignable roles are fixed:

- `ROLE_ACCOUNTING`
- `ROLE_FACTORY`
- `ROLE_SALES`
- `ROLE_DEALER`

Denied:

- `ROLE_ADMIN`
- `ROLE_SUPER_ADMIN`
- unknown/custom roles

### `PUT /api/v1/admin/users/{id}`

Request body (`UpdateUserRequest`):

- `displayName` required
- `roles` optional full desired set (same fixed-role validation); omit to keep existing roles unchanged

### `PUT /api/v1/admin/users/{userId}/status`

Request:

```json
{
  "enabled": true
}
```

Returns updated `UserDto`.

### Mutations returning `204 No Content`

- `PATCH /api/v1/admin/users/{id}/suspend`
- `PATCH /api/v1/admin/users/{id}/unsuspend`
- `PATCH /api/v1/admin/users/{id}/mfa/disable`
- `DELETE /api/v1/admin/users/{id}`

Frontend rule: re-fetch list/detail after these actions.

Mutation scope boundary:

- Tenant-admin user-management mutations (`status`, `suspend`, `unsuspend`, `mfa disable`, `delete`, `force-reset-password`) do not operate on `ROLE_ADMIN` or `ROLE_SUPER_ADMIN` targets.

### `POST /api/v1/admin/users/{userId}/force-reset-password`

No request body.

Returns `ApiResponse<String>` with success message and `"OK"` payload.

## Approval inbox and decisions

### `GET /api/v1/admin/approvals`

Response shape (`AdminApprovalInboxResponse`):

```json
{
  "items": [AdminApprovalItemDto],
  "pendingCount": 5
}
```

`AdminApprovalItemDto` core fields:

- `originType`
- `ownerType`
- `id`
- `publicId`
- `reference`
- `status`
- `summary`
- `actionType`
- `actionLabel`
- `approveEndpoint`
- `rejectEndpoint`
- `createdAt`

Optional context fields (origin-dependent):

- `reportType`
- `parameters`
- `requesterUserId`
- `requesterEmail`

Period-close row context:

- `summary` includes request-maker context plus `force requested` and `request note` markers when present.

Action endpoint rules:

- `rejectEndpoint` is `null` for `PAYROLL_RUN` items (approve-only workflow).
- Other origin types expose both `approveEndpoint` and `rejectEndpoint`.

`originType` values:

- `EXPORT_REQUEST`
- `CREDIT_REQUEST`
- `CREDIT_LIMIT_OVERRIDE_REQUEST`
- `PAYROLL_RUN`
- `PERIOD_CLOSE_REQUEST`

### `POST /api/v1/admin/approvals/{originType}/{id}/decisions`

Request body (`AdminApprovalDecisionRequest`):

```json
{
  "decision": "APPROVE",
  "reason": "context-dependent",
  "expiresAt": "2026-04-15T10:30:00Z"
}
```

`decision` is required and must be `APPROVE` or `REJECT`.

Origin-specific decision constraints:

- `EXPORT_REQUEST`: `APPROVE` or `REJECT`; `reason` optional.
- `CREDIT_REQUEST`: `APPROVE` or `REJECT`; `reason` required (nonblank) for both.
- `CREDIT_LIMIT_OVERRIDE_REQUEST`: `APPROVE` or `REJECT`; `reason` required; `expiresAt` is used for override approval windows.
- `PAYROLL_RUN`: only `APPROVE` is supported; `REJECT` fails validation.
- `PERIOD_CLOSE_REQUEST`: `APPROVE` or `REJECT`; `reason` is required (nonblank) for both, and request `force` posture is preserved by workflow and not overridden by admin generic decisions.

Response: `ApiResponse<AdminApprovalItemDto>` for the decided row.

## Audit trail

### `GET /api/v1/admin/audit/events`

Returns `ApiResponse<PageResponse<AuditFeedItemDto>>` with standard query filters (`from`, `to`, `module`, `action`, `status`, `actor`, `entityType`, `reference`, `page`, `size`).

## Internal support tickets

### `GET /api/v1/admin/support/tickets`

Response:

```json
{
  "tickets": [SupportTicketResponse]
}
```

### `POST /api/v1/admin/support/tickets`

Request (`SupportTicketCreateRequest`):

- `category` required, max 32
- `subject` required, max 255
- `description` required, max 4000

### `GET /api/v1/admin/support/tickets/{ticketId}`

Returns `SupportTicketResponse` detail.

Important:

- Tenant-admin support UX uses `/api/v1/admin/support/tickets/**`.
- `/api/v1/portal/support/tickets/**` is accounting-hosted and out of tenant-admin portal ownership.

## Self settings

### `GET /api/v1/admin/self/settings`

Response (`AdminSelfSettingsDto`):

- `email`
- `displayName`
- `companyCode`
- `mfaEnabled`
- `mustChangePassword`
- `roles`
- `tenantRuntime`
- `activeSessionEstimate`

## Tenant changelog (read-only)

- `GET /api/v1/changelog?page=&size=`
- `GET /api/v1/changelog/latest-highlighted`

Publishing stays superadmin-only.
