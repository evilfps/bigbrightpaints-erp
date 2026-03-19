# Control-plane approval and bootstrap contract cleanup

This handoff covers the bounded ERP-19 contract cleanup that landed with the admin approval inbox and tenant onboarding packet.

## Included now

- `GET /api/v1/admin/approvals` remains the single tenant-scoped approval inbox.
- `AdminApprovalItemDto` now exposes typed `originType` and `ownerType` fields, and export approval rows retain machine-readable export detail.
- `GET /api/v1/admin/exports/pending` is retired.
- `POST /api/v1/superadmin/tenants/onboard` now returns explicit bootstrap confirmation fields.
- `GET /api/v1/companies/superadmin/dashboard` is retired.
- `GET /api/v1/superadmin/dashboard` remains the live aggregate-count dashboard route.

## Approval inbox contract

Endpoint:
- `GET /api/v1/admin/approvals`

Changed payload fields for each approval item:
- added `originType`
- added `ownerType`
- removed `type`
- removed `sourcePortal`

Export approval rows (`originType=EXPORT_REQUEST`) additionally expose:
- `reportType` for all tenant-scoped inbox viewers
- `parameters`, `requesterUserId`, and `requesterEmail` only for tenant admin or super-admin viewers
- accounting viewers receive the export row with those sensitive fields redacted

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

Stable approval item fields:
- `reference`
- `status`
- `summary`
- `createdAt`

Conditional action fields:
- tenant admin viewers keep `actionType`, `actionLabel`, `approveEndpoint`, and `rejectEndpoint` on export approval rows
- accounting-only viewers receive export approval rows with those action fields as `null`

Frontend action:
- switch approval queue rendering, filtering, and badge logic to `originType` and `ownerType`
- render export approval detail from `reportType`
- only render `parameters`, `requesterUserId`, and `requesterEmail` when the caller is a tenant admin
- treat `actionType`, `actionLabel`, `approveEndpoint`, and `rejectEndpoint` as nullable on accounting export rows; do not render export decision controls when they are `null`
- stop reading `type` and `sourcePortal`
- keep `GET /api/v1/admin/approvals` limited to tenant-scoped admin/accounting callers; do not point platform super-admin tooling at that prefix

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
- use `GET /api/v1/admin/approvals` for tenant-scoped admin/accounting approval queue access only
- use `GET /api/v1/superadmin/dashboard` only for aggregate platform dashboard counts; it does not replace the retired detailed tenant payload

## Out of scope follow-up

The broader tenant route-family hard cut onto `/api/v1/superadmin/tenants/**` is still outside this packet and should be handled separately if it remains needed after fresh inspection.
