# Role Boundaries

Last reviewed: 2026-04-15

## Primary access

- Frontend shell owner: `ROLE_ADMIN`.
- Tenant-admin product contracts live under `/api/v1/admin/**` (tenant workflows only).
- Approval actions are tenant-admin only via `POST /api/v1/admin/approvals/{originType}/{id}/decisions`.

## Hard boundaries

- Do not expose `ROLE_SUPER_ADMIN` anywhere in tenant-admin shell UX.
- Do not expose control-plane tenant mutation (`/api/v1/superadmin/**`) here.
- Do not expose role creation or role catalog mutation in tenant-admin UX.
- Do not expose accounting, factory, dealer, or platform-specific navigation in this shell.

## User role assignment boundaries

Tenant-admin user create/update forms must allow only:

- `ROLE_ACCOUNTING`
- `ROLE_FACTORY`
- `ROLE_SALES`
- `ROLE_DEALER`

Must reject:

- `ROLE_ADMIN`
- `ROLE_SUPER_ADMIN`
- unknown/custom roles

Tenant-admin user-management scope also excludes acting on users that currently hold:

- `ROLE_ADMIN`
- `ROLE_SUPER_ADMIN`

## Support ownership boundaries

- Tenant-admin internal support host: `/api/v1/admin/support/tickets/**`.
- `/api/v1/portal/support/tickets/**` is accounting-hosted and must not back tenant-admin screens.
- Dealer support remains under `/api/v1/dealer-portal/support/tickets/**`.

## UI implications

- Gate access from live `GET /api/v1/auth/me` data, not cached assumptions.
- If a component is shared with other portals, do not leak tenant-admin shell chrome or decision actions outside this portal.
