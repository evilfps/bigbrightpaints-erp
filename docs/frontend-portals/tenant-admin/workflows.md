# Workflows

Last reviewed: 2026-04-15

## Session bootstrap

1. Call `GET /api/v1/auth/me` on shell load.
2. Persist `companyCode` as active tenant scope.
3. If `mustChangePassword=true`, route into password-change corridor before normal tenant-admin routes.
4. Hide tenant-admin navigation when caller is not `ROLE_ADMIN`.

## Dashboard

1. Load `GET /api/v1/admin/dashboard`.
2. Render activity + approval + user + support + runtime + security summaries.

## User lifecycle

1. List users from `GET /api/v1/admin/users`.
2. Create users via `POST /api/v1/admin/users` with fixed assignable role set.
3. Update detail via `PUT /api/v1/admin/users/{id}`.
4. Use explicit lifecycle actions for status/suspend/unsuspend/MFA disable/delete/reset-link.
5. Re-fetch after no-content mutations.

## Approval decisions

1. Load `GET /api/v1/admin/approvals`.
2. Render normalized `items[]` rows.
3. Submit approve/reject via `POST /api/v1/admin/approvals/{originType}/{id}/decisions`.
4. Refresh inbox after each decision.

## Support

1. List tickets from `GET /api/v1/admin/support/tickets`.
2. Create ticket via `POST /api/v1/admin/support/tickets`.
3. Open detail via `GET /api/v1/admin/support/tickets/{ticketId}`.

## Self settings

1. Load `GET /api/v1/admin/self/settings`.
2. Route password and MFA mutations through auth APIs.

## Changelog

1. Show optional highlight via `GET /api/v1/changelog/latest-highlighted`.
2. Show list view via `GET /api/v1/changelog?page=&size=`.
