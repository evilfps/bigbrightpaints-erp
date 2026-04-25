# Workflows

Last reviewed: 2026-04-19

## Session bootstrap

1. Call `GET /api/v1/auth/me` on shell load.
2. Persist `companyCode` as active tenant scope.
3. If `mustChangePassword=true`, route into password-change corridor before normal tenant-admin routes.
4. Hide tenant-admin navigation when caller is not `ROLE_ADMIN`.

## Dashboard

1. Load `GET /api/v1/admin/dashboard`.
2. Render activity + approval + user + runtime + security summaries.

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

## Report a problem

1. Open `/tenant/report-problem`.
2. Collect source/category/summary plus optional diagnostics fields.
3. Submit via `POST /api/v1/incidents/report`.
4. Show confirmation with returned `publicId` for operator follow-up.

## Self settings

1. Load `GET /api/v1/admin/self/settings`.
2. Route password and MFA mutations through auth APIs.

## Release and updater awareness

1. Show latest release from `GET /api/v1/changelog/latest`.
2. Show history from `GET /api/v1/changelog?page=&size=`.
3. Evaluate update UX from `GET /api/v1/runtime/version?installedVersion=`.
