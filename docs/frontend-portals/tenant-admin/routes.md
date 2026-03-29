# Routes

Recommended frontend route map:

| Route | Screen | Loader APIs | Primary actions | Guard |
|---|---|---|---|---|
| `/tenant` | shell landing | `GET /api/v1/auth/me`, optional `GET /api/v1/changelog/latest-highlighted` | route to users or approvals | tenant-admin session |
| `/tenant/users` | user list | `GET /api/v1/auth/me`, `GET /api/v1/admin/users` | create user, open detail, suspend, unsuspend, delete | tenant-admin session |
| `/tenant/users/new` | create user | `GET /api/v1/auth/me` | `POST /api/v1/admin/users` | tenant-admin session |
| `/tenant/users/:userId` | user detail and edit | `GET /api/v1/admin/users` or cached row source | `PUT /api/v1/admin/users/{id}`, status, reset, MFA disable, delete | tenant-admin session |
| `/tenant/approvals` | approval inbox | `GET /api/v1/admin/approvals` | open export approval detail | tenant-admin session |
| `/tenant/approvals/export/:requestId` | export approval detail | `GET /api/v1/admin/approvals` | `PUT /api/v1/admin/exports/{requestId}/approve`, `PUT /api/v1/admin/exports/{requestId}/reject` | tenant-admin session |
| `/tenant/support/tickets` | support list | `GET /api/v1/portal/support/tickets` | open ticket, create ticket | tenant-admin session |
| `/tenant/support/tickets/new` | create support ticket | none beyond shell bootstrap | `POST /api/v1/portal/support/tickets` | tenant-admin session |
| `/tenant/support/tickets/:ticketId` | support detail | `GET /api/v1/portal/support/tickets/{ticketId}` | read-only follow-up state | tenant-admin session |
| `/tenant/changelog` | changelog list | `GET /api/v1/changelog?page=&size=` | read entries | authenticated tenant session |

Route design rules:

- Bootstrap `GET /api/v1/auth/me` before rendering tenant navigation.
- Use the route param `requestId` or `ticketId` only for screen state. The backend source of truth still comes from the corresponding API payloads.
- Keep approval detail and support detail as separate routes so deep links work from notifications or inbox rows.
