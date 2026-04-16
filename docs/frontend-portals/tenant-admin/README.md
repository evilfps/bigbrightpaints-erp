# Tenant Admin Portal

Last reviewed: 2026-04-15

## Purpose

Tenant-admin portal is the tenant-scoped administration shell for `ROLE_ADMIN`.

It owns:

- dashboard (`/api/v1/admin/dashboard`)
- user lifecycle (`/api/v1/admin/users/**`)
- approval inbox + decisions (`/api/v1/admin/approvals`, `POST /api/v1/admin/approvals/{originType}/{id}/decisions`)
- tenant audit feed (`/api/v1/admin/audit/events`)
- internal support (`/api/v1/admin/support/tickets/**`)
- self settings (`/api/v1/admin/self/settings` + auth self-service)
- read-only changelog (`/api/v1/changelog`)

Still-live legacy admin insight reads:

- `/api/v1/portal/dashboard`
- `/api/v1/portal/operations`
- `/api/v1/portal/workforce`

## Users

- Primary actor: `ROLE_ADMIN`
- Tenant-admin shell and navigation are `ROLE_ADMIN` only.

## Hard boundaries

- No superadmin control-plane routes.
- No role-creation UX.
- No accounting journal/reconciliation workflows.
- No factory execution workflows.
- No dealer self-service workflows.

## Critical frontend rules

- Always bootstrap with `GET /api/v1/auth/me`.
- Treat `mustChangePassword=true` as a blocking corridor state.
- Persist tenant scope as `companyCode` and send `X-Company-Code`.
