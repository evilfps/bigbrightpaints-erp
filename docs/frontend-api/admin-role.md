# Admin Role API Handoff

Role target: `ROLE_ADMIN` (tenant-admin product surface).

Last reviewed: 2026-04-15

## Auth requirements

- Bearer JWT + `X-Company-Code`
- Canonical bootstrap: `GET /api/v1/auth/me`
- Tenant-admin workflow prefixes are tenant-scoped and blocked for platform superadmin callers

## Endpoints by workflow

| Workflow | Method + path | Request DTO | Response DTO | Required authority |
| --- | --- | --- | --- | --- |
| Dashboard | `GET /api/v1/admin/dashboard` | — | `ApiResponse<AdminDashboardDto>` | `ROLE_ADMIN` |
| Approval inbox | `GET /api/v1/admin/approvals` | — | `ApiResponse<AdminApprovalInboxResponse>` | `ROLE_ADMIN` |
| Approval decision | `POST /api/v1/admin/approvals/{originType}/{id}/decisions` | `AdminApprovalDecisionRequest` | `ApiResponse<AdminApprovalItemDto>` | `ROLE_ADMIN` |
| User list | `GET /api/v1/admin/users` | — | `ApiResponse<List<UserDto>>` | `ROLE_ADMIN` |
| User detail | `GET /api/v1/admin/users/{id}` | — | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User create | `POST /api/v1/admin/users` | `CreateUserRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User update | `PUT /api/v1/admin/users/{id}` | `UpdateUserRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User status | `PUT /api/v1/admin/users/{userId}/status` | `UpdateUserStatusRequest` | `ApiResponse<UserDto>` | `ROLE_ADMIN` |
| User suspend | `PATCH /api/v1/admin/users/{id}/suspend` | — | `204 No Content` | `ROLE_ADMIN` |
| User unsuspend | `PATCH /api/v1/admin/users/{id}/unsuspend` | — | `204 No Content` | `ROLE_ADMIN` |
| User MFA disable | `PATCH /api/v1/admin/users/{id}/mfa/disable` | — | `204 No Content` | `ROLE_ADMIN` |
| User delete | `DELETE /api/v1/admin/users/{id}` | — | `204 No Content` | `ROLE_ADMIN` |
| Force password reset | `POST /api/v1/admin/users/{userId}/force-reset-password` | — | `ApiResponse<String>` | `ROLE_ADMIN` |
| Tenant audit feed | `GET /api/v1/admin/audit/events` | — | `ApiResponse<PageResponse<AuditFeedItemDto>>` | `ROLE_ADMIN` |
| Internal support list | `GET /api/v1/admin/support/tickets` | — | `ApiResponse<SupportTicketListResponse>` | `ROLE_ADMIN` |
| Internal support create | `POST /api/v1/admin/support/tickets` | `SupportTicketCreateRequest` | `ApiResponse<SupportTicketResponse>` | `ROLE_ADMIN` |
| Internal support detail | `GET /api/v1/admin/support/tickets/{ticketId}` | — | `ApiResponse<SupportTicketResponse>` | `ROLE_ADMIN` |
| Self settings | `GET /api/v1/admin/self/settings` | — | `ApiResponse<AdminSelfSettingsDto>` | `ROLE_ADMIN` |

## DTOs used by tenant-admin UI

- `AdminDashboardDto`
- `AdminApprovalInboxResponse`, `AdminApprovalItemDto`, `AdminApprovalDecisionRequest`
- `CreateUserRequest`, `UpdateUserRequest`, `UpdateUserStatusRequest`, `UserDto`
- `SupportTicketCreateRequest`, `SupportTicketListResponse`, `SupportTicketResponse`
- `AdminSelfSettingsDto`
- `AuditFeedItemDto`, `PageResponse<AuditFeedItemDto>`

## Explicit exclusions from tenant-admin product

- `/api/v1/superadmin/roles/**` (RBAC catalog host)
- `/api/v1/superadmin/settings` (governance/system settings host)
- `/api/v1/superadmin/notify` (control-plane utility host)
- `/api/v1/superadmin/**` (control-plane ownership)
