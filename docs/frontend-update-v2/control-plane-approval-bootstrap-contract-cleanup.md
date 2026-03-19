# Control-plane approval and bootstrap contract cleanup

This handoff covers the bounded ERP-19 contract cleanup that landed with the admin approval inbox and tenant onboarding packet.

## Included now

- `GET /api/v1/admin/approvals` remains the single approval inbox.
- `AdminApprovalItemDto` now exposes typed `originType` and `ownerType` fields.
- `GET /api/v1/admin/exports/pending` is retired.
- `POST /api/v1/superadmin/tenants/onboard` now returns explicit bootstrap confirmation fields.
- `GET /api/v1/companies/superadmin/dashboard` is retired.
- `GET /api/v1/superadmin/dashboard` remains the live dashboard route.

## Approval inbox contract

Endpoint:
- `GET /api/v1/admin/approvals`

Changed payload fields for each approval item:
- added `originType`
- added `ownerType`
- removed `type`
- removed `sourcePortal`

Current emitted `originType` values:
- `CREDIT_REQUEST`
- `CREDIT_LIMIT_OVERRIDE_REQUEST`
- `PAYROLL_RUN`
- `PERIOD_CLOSE_REQUEST`
- `EXPORT_REQUEST`

Current emitted `ownerType` values:
- `SALES`
- `FACTORY`
- `HR`
- `ACCOUNTING`
- `REPORTS`

Unchanged approval item fields:
- `reference`
- `status`
- `summary`
- `actionType`
- `actionLabel`
- `approveEndpoint`
- `rejectEndpoint`
- `createdAt`

Frontend action:
- switch approval queue rendering, filtering, and badge logic to `originType` and `ownerType`
- stop reading `type` and `sourcePortal`

## Tenant onboarding success contract

Endpoint:
- `POST /api/v1/superadmin/tenants/onboard`

The success message now explicitly states that onboarding created the seeded chart of accounts, tenant admin, and default accounting period.

Added response fields:
- `bootstrapMode`
- `seededChartOfAccounts`
- `defaultAccountingPeriodCreated`
- `tenantAdminProvisioned`

Existing response fields that still remain:
- `companyId`
- `companyCode`
- `templateCode`
- `accountsCreated`
- `accountingPeriodId`
- `adminEmail`
- `adminTemporaryPassword`
- `credentialsEmailSent`
- `systemSettingsInitialized`

Current backend meaning:
- `bootstrapMode` is `SEEDED`
- `seededChartOfAccounts` is `true`
- `defaultAccountingPeriodCreated` is `true`
- `tenantAdminProvisioned` is `true`

Frontend action:
- treat the explicit bootstrap fields as the source of truth for seeded onboarding success
- continue treating `adminTemporaryPassword` as sensitive output because it remains in the response

## Retired control-plane aliases

- do not call `GET /api/v1/admin/exports/pending`
- do not call `GET /api/v1/companies/superadmin/dashboard`
- use `GET /api/v1/admin/approvals` for approval queue access
- use `GET /api/v1/superadmin/dashboard` for the super-admin dashboard

## Out of scope follow-up

The broader tenant route-family hard cut onto `/api/v1/superadmin/tenants/**` is still outside this packet and should be handled separately if it remains needed after fresh inspection.
