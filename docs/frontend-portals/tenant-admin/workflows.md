# Workflows

## Session bootstrap

1. Call `GET /api/v1/auth/me` on shell load.
2. Store `companyCode` as the active tenant scope key.
3. If `mustChangePassword=true`, route to the password-change screen before rendering the normal shell.
4. Hide tenant-admin navigation when `roles` does not include `ROLE_ADMIN`.

## User lifecycle

1. Enter from `/tenant/users`.
2. Create users from `/tenant/users/new` with `email`, `displayName`, and `roles`.
3. Omit `companyId` in tenant-admin requests. The backend resolves the current tenant from session scope.
4. Use detail actions for:
   - edit display name and roles
   - enable or disable
   - suspend or unsuspend
   - disable MFA
   - send reset link
   - delete
5. Re-fetch after no-content mutations.

## Forced reset and must-change-password

1. Tenant admin triggers `POST /api/v1/admin/users/{userId}/force-reset-password`.
2. The target user receives a reset link and later returns through the auth password-reset flow.
3. After reset, `GET /api/v1/auth/me` may report `mustChangePassword=true` until the user completes the corridor.

## Export approval

1. Load `GET /api/v1/admin/approvals`.
2. Read only the `exportRequests` slice for this screen.
3. Open a single request detail route for approve or reject.
4. On approve, call `PUT /api/v1/admin/exports/{requestId}/approve`.
5. On reject, collect a reason and call `PUT /api/v1/admin/exports/{requestId}/reject`.
6. Refresh the inbox after the decision.

## Support escalation

1. Open `/tenant/support/tickets`.
2. Create a ticket with category, subject, and description.
3. Poll list or revisit detail to observe GitHub sync fields and resolution status.

## Tenant changelog

1. Show highlighted latest entry on the shell landing page if present.
2. Use `/tenant/changelog` for the full read-only list.
