# API Contracts

## Shared transport rules

- Bootstrap the shell with `GET /api/v1/auth/me`.
- For tenant-scoped routes, send `X-Company-Code` with the current `companyCode`.
- Never send `X-Company-Id`.
- Frontend must not persist or request `companyId` for ordinary tenant-admin flows.

## `GET /api/v1/auth/me`

Use this for every page refresh and initial shell load.

Response `data` fields:

| Field | Type | Frontend use |
|---|---|---|
| `email` | string | user identity chip |
| `displayName` | string | shell header |
| `companyCode` | string | tenant scope header value |
| `mfaEnabled` | boolean | session security indicator |
| `mustChangePassword` | boolean | redirect gate before normal shell |
| `roles` | string[] | route guards |
| `permissions` | string[] | action-level gating |

Frontend rules:

- This is the only profile/identity source.
- If `mustChangePassword=true`, route to the password-change flow before `/tenant/users` or `/tenant/approvals`.

## User management: `/api/v1/admin/users/**`

### `GET /api/v1/admin/users`

Response row fields:

- `id`
- `publicId`
- `email`
- `displayName`
- `enabled`
- `mfaEnabled`
- `roles`
- `companyCode`
- `lastLoginAt`

Use `enabled` for status pills. There is no separate suspended enum in the response; suspended users come back as disabled.

### `POST /api/v1/admin/users`

Request body:

| Field | Type | Required | Frontend rule |
|---|---|---|---|
| `email` | string | yes | valid email |
| `displayName` | string | yes | required |
| `roles` | string[] | yes | non-empty; never include `ROLE_SUPER_ADMIN` |

Response is `UserDto`. Company scope is implicit from the authenticated tenant context; there is no request-side tenant reassignment field.

### `PUT /api/v1/admin/users/{id}`

Request body:

| Field | Type | Required | Frontend rule |
|---|---|---|---|
| `displayName` | string | yes | required |
| `roles` | string[] | no | send the full desired role set |
| `enabled` | boolean | no | use only when editing from the detail form |

Use one edit form for display name and roles. Keep status toggles as separate actions when possible for clearer audit UX. Company scope is not editable from this surface.

### `PUT /api/v1/admin/users/{userId}/status`

Request:

```json
{
  "enabled": true
}
```

Response returns `UserDto`.

### `PATCH /api/v1/admin/users/{id}/suspend`
### `PATCH /api/v1/admin/users/{id}/unsuspend`
### `PATCH /api/v1/admin/users/{id}/mfa/disable`
### `DELETE /api/v1/admin/users/{id}`

These return `204 No Content`.

Frontend rules:

- Keep optimistic UI minimal.
- Re-fetch the user list or user row after success because these endpoints do not return updated objects.

### `POST /api/v1/admin/users/{userId}/force-reset-password`

No request body.

Response envelope returns `"OK"` on success.

Frontend rule:

- Show this as "Send reset link". Do not promise an immediate password change.
- The target user will be forced through the password reset flow outside this portal.

## Approval inbox: `GET /api/v1/admin/approvals`

Response shape:

```json
{
  "creditRequests": [],
  "payrollRuns": [],
  "periodCloseRequests": [],
  "exportRequests": []
}
```

For tenant-admin screens in this worker, the export array is the relevant actionable slice.

`AdminApprovalItemDto` fields:

| Field | Type | Notes |
|---|---|---|
| `originType` | enum | one of `CREDIT_REQUEST`, `CREDIT_LIMIT_OVERRIDE_REQUEST`, `PAYROLL_RUN`, `PERIOD_CLOSE_REQUEST`, `EXPORT_REQUEST` |
| `ownerType` | enum | one of `SALES`, `FACTORY`, `HR`, `ACCOUNTING`, `REPORTS` |
| `id` | long | internal row id |
| `publicId` | uuid | stable external reference |
| `reference` | string | display reference |
| `status` | string | uppercase state |
| `summary` | string | inbox summary text |
| `reportType` | string or null | export-only machine label |
| `parameters` | string or null | visible to admin-capable viewers |
| `requesterUserId` | long or null | visible to admin-capable viewers |
| `requesterEmail` | string or null | visible to admin-capable viewers |
| `actionType` | string or null | action code |
| `actionLabel` | string or null | button label |
| `approveEndpoint` | string or null | backend action route |
| `rejectEndpoint` | string or null | backend action route |
| `createdAt` | instant | sort key |

Frontend rules:

- Filter `exportRequests` for the tenant-admin export-approval screen.
- Render rows from the payload, not from hardcoded report names.
- Treat `parameters`, `requesterUserId`, and `requesterEmail` as sensitive. Only render them when present.

## Export approval actions

### `PUT /api/v1/admin/exports/{requestId}/approve`

No request body.

### `PUT /api/v1/admin/exports/{requestId}/reject`

Optional request body:

```json
{
  "reason": "Human-readable rejection reason"
}
```

Frontend rules:

- Use a reason textbox for reject even though the body is optional.
- After approve or reject, refresh `GET /api/v1/admin/approvals` and remove the row from pending state.

## Support tickets

### `GET /api/v1/portal/support/tickets`

Response:

```json
{
  "tickets": [SupportTicketResponse]
}
```

### `POST /api/v1/portal/support/tickets`

Request fields:

- `category` required, max 32
- `subject` required, max 255
- `description` required, max 4000

### `GET /api/v1/portal/support/tickets/{ticketId}`

`SupportTicketResponse` fields:

- `id`
- `publicId`
- `companyCode`
- `userId`
- `requesterEmail`
- `category`
- `subject`
- `description`
- `status`
- `githubIssueNumber`
- `githubIssueUrl`
- `githubIssueState`
- `githubSyncedAt`
- `githubLastError`
- `resolvedAt`
- `resolvedNotificationSentAt`
- `createdAt`
- `updatedAt`

## Tenant changelog

- `GET /api/v1/changelog?page=&size=`
- `GET /api/v1/changelog/latest-highlighted`

Use this as a read-only tenant-facing release note stream. Publishing stays in the superadmin portal.
