# Routes

Last reviewed: 2026-04-15

| Route | Screen | Loader APIs | Primary actions | Guard |
| --- | --- | --- | --- | --- |
| `/tenant` | shell landing | `GET /api/v1/auth/me`, `GET /api/v1/admin/dashboard`, optional `GET /api/v1/changelog/latest-highlighted` | route to dashboard/users/approvals | tenant-admin session |
| `/tenant/dashboard` | dashboard | `GET /api/v1/admin/dashboard` | view activity/approval/user/support/runtime summary | tenant-admin session |
| `/tenant/users` | user list | `GET /api/v1/admin/users` | create, edit, status, suspend/unsuspend, reset, MFA disable, delete | tenant-admin session |
| `/tenant/users/new` | create user | none beyond shell bootstrap | `POST /api/v1/admin/users` | tenant-admin session |
| `/tenant/users/:userId` | user detail/edit | `GET /api/v1/admin/users/{id}` | `PUT /api/v1/admin/users/{id}`, status/reset/MFA/delete actions | tenant-admin session |
| `/tenant/approvals` | approval inbox | `GET /api/v1/admin/approvals` | open normalized approval detail | tenant-admin session |
| `/tenant/approvals/:originType/:id` | approval detail | `GET /api/v1/admin/approvals` | `POST /api/v1/admin/approvals/{originType}/{id}/decisions` | tenant-admin session |
| `/tenant/audit` | tenant audit feed | `GET /api/v1/admin/audit/events` | filter/search tenant audit history | tenant-admin session |
| `/tenant/support/tickets` | support list | `GET /api/v1/admin/support/tickets` | open ticket, create ticket | tenant-admin session |
| `/tenant/support/tickets/new` | create support ticket | none beyond shell bootstrap | `POST /api/v1/admin/support/tickets` | tenant-admin session |
| `/tenant/support/tickets/:ticketId` | support detail | `GET /api/v1/admin/support/tickets/{ticketId}` | read sync/error status | tenant-admin session |
| `/tenant/settings` | self settings | `GET /api/v1/admin/self/settings`, `GET /api/v1/auth/me` | password change, MFA setup/activate/disable via auth APIs | tenant-admin session |
| `/tenant/changelog` | changelog list | `GET /api/v1/changelog?page=&size=` | read-only changelog entries | authenticated tenant session |

Route rules:

- Bootstrap `GET /api/v1/auth/me` before rendering tenant-admin navigation.
- If `mustChangePassword=true`, route to password-change corridor before normal routes.
- Keep approval detail and support detail as dedicated deep-linkable routes.
