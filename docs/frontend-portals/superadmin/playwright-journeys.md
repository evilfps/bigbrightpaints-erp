# Playwright Journeys

## Journey 1: Onboard tenant with seeded accounting bootstrap

1. Log in as a platform superadmin.
2. Visit `/platform/tenants/new`.
3. Wait for `GET /api/v1/superadmin/tenants/coa-templates` and assert at least one option is rendered.
4. Fill tenant name, code, timezone, first-admin email, and select a COA template.
5. Submit onboarding.
6. Assert the response payload contains:
   - `seededChartOfAccounts === true`
   - `defaultAccountingPeriodCreated === true`
   - `tenantAdminProvisioned === true`
7. Assert redirect to `/platform/tenants/:tenantId`.
8. Assert the detail screen shows onboarding template code and admin email from the response.

## Journey 2: Suspend a tenant

1. Open `/platform/tenants/:tenantId/lifecycle`.
2. Verify current lifecycle is shown.
3. Select `SUSPENDED`, enter a reason, and confirm.
4. Assert `PUT /api/v1/superadmin/tenants/{id}/lifecycle` succeeds.
5. Assert the detail page now shows `lifecycleState = SUSPENDED`.

## Journey 3: Reset tenant admin password and force logout

1. Open `/platform/tenants/:tenantId/support`.
2. Submit admin password reset with a reason.
3. Assert success banner using returned `adminEmail`.
4. Trigger force logout with a reason.
5. Assert the response includes `revokedUserCount`.
6. Assert the support timeline refreshes.

## Journey 4: Replace main admin

1. Open `/platform/tenants/:tenantId/admin-access`.
2. Choose a replacement admin.
3. Confirm the action.
4. Assert the response returns the new `MainAdminSummaryDto`.
5. Assert tenant detail reflects the new main-admin email.
