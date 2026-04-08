# Admin Role API Handoff

Role target: `ROLE_ADMIN` (tenant admin surface).

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Admin controls are tenant-scoped unless route is explicitly superadmin-only

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
|---|---|---|---|---|
| Approval inbox | `GET /api/v1/admin/approvals` | — | `ApiResponse<AdminApprovalsResponse>` | `ROLE_ADMIN` |
| Export approve | `PUT /api/v1/admin/exports/{requestId}/approve` | — | `ApiResponse<ExportRequestDto>` | `ROLE_ADMIN` |
| Export reject | `PUT /api/v1/admin/exports/{requestId}/reject` | `ExportRequestDecisionRequest` | `ApiResponse<ExportRequestDto>` | `ROLE_ADMIN` |
| User list | `GET /api/v1/admin/users` | — | `ApiResponse<List<UserDto>>` | `ROLE_ADMIN` |
| User create | `POST /api/v1/admin/users` | `CreateUserRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User update | `PUT /api/v1/admin/users/{id}` | `UpdateUserRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User status | `PUT /api/v1/admin/users/{userId}/status` | `UpdateUserStatusRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User suspend | `PATCH /api/v1/admin/users/{id}/suspend` | — | `204 No Content` | `ROLE_ADMIN` |
| User unsuspend | `PATCH /api/v1/admin/users/{id}/unsuspend` | — | `204 No Content` | `ROLE_ADMIN` |
| Force password reset | `POST /api/v1/admin/users/{userId}/force-reset-password` | — | `ApiResponse<String>` | `ROLE_ADMIN` |
| Settings read | `GET /api/v1/admin/settings` | — | `ApiResponse<SystemSettingsDto>` | `ROLE_ADMIN` |
| Settings update | `PUT /api/v1/admin/settings` | `SystemSettingsUpdateRequest` | `ApiResponse<SystemSettingsDto>` | `ROLE_ADMIN` |
| Tenant audit feed | `GET /api/v1/admin/audit/events` | — | `ApiResponse<PageResponse<AuditFeedItemDto>>` | `ROLE_ADMIN` |
| Backorder cancel (operations) | `POST /api/v1/dispatch/backorder/{slipId}/cancel` | query `reason` | `ApiResponse<PackagingSlipDto>` | `ROLE_ADMIN` or `ROLE_FACTORY` |

## DTOs used by admin UI

- `AdminApprovalsResponse`, `AdminApprovalItemDto`
- `ExportRequestDto`, `ExportRequestDecisionRequest`
- `CreateUserRequest`, `UpdateUserRequest`, `UpdateUserStatusRequest`, `UserDto`
- `SystemSettingsDto`, `SystemSettingsUpdateRequest`
- `AuditFeedItemDto`, `PageResponse<AuditFeedItemDto>`
- `PackagingSlipDto`
