# Playwright Journeys

## Journey 1: Bootstrap tenant-admin shell

1. Log in as a tenant admin.
2. Visit `/tenant`.
3. Assert `GET /api/v1/auth/me` returns `companyCode` and includes `ROLE_ADMIN`.
4. Assert tenant navigation renders.
5. If `mustChangePassword=true`, assert redirect into the password-change flow instead of normal shell landing.

## Journey 2: Create, suspend, and unsuspend a user

1. Visit `/tenant/users`.
2. Create a user from `/tenant/users/new`.
3. Assert the new row appears with the expected email and roles.
4. Trigger suspend and assert the backend returns `204`.
5. Re-fetch and assert the user row shows disabled state.
6. Trigger unsuspend and assert the row returns to enabled state.

## Journey 3: Send forced reset link and disable MFA

1. Open `/tenant/users/:userId`.
2. Trigger "Send reset link".
3. Assert `POST /api/v1/admin/users/{userId}/force-reset-password` succeeds.
4. Trigger "Disable MFA".
5. Assert the backend returns `204`.
6. Re-fetch the user list and assert `mfaEnabled=false`.

## Journey 4: Approve an export request

1. Open `/tenant/approvals`.
2. Assert `GET /api/v1/admin/approvals` returns `items[]` with rows where `originType=EXPORT_REQUEST`.
3. Open one export request detail.
4. Approve it.
5. Assert `POST /api/v1/admin/approvals/EXPORT_REQUEST/{requestId}/decisions` with `{"decision":"APPROVE"}` succeeds.
6. Refresh inbox and assert the row is gone from pending requests.

## Journey 5: Reject an export request

1. Open one export approval detail.
2. Enter a rejection reason.
3. Submit reject.
4. Assert `POST /api/v1/admin/approvals/EXPORT_REQUEST/{requestId}/decisions` with `{"decision":"REJECT","reason":"..."}` succeeds.
5. Refresh inbox and assert the row is removed from pending state.

## Journey 6: Create and inspect a support ticket

1. Visit `/tenant/support/tickets/new`.
2. Submit category, subject, and description.
3. Assert success redirect to `/tenant/support/tickets/:ticketId`.
4. Assert ticket detail shows `status`, `githubIssueUrl` when available, and timestamps.
