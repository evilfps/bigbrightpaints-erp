# Control-plane approval and bootstrap contract cleanup

This handoff now reflects the ERP-37 tenant-control hard cut that landed on top of the earlier approval inbox and onboarding packet.

## Included now

- `GET /api/v1/admin/approvals` remains the single tenant-scoped approval inbox.
- `AdminApprovalItemDto` continues to expose typed `originType` and `ownerType` fields, and export approval rows retain machine-readable export detail.
- `POST /api/v1/superadmin/tenants/onboard` now returns onboarding truth without `adminTemporaryPassword`.
- `GET /api/v1/superadmin/dashboard` and `GET /api/v1/superadmin/tenants/{id}` are now the canonical aggregate/detail superadmin read surfaces.
- `/api/v1/admin/changelog*`, `/api/v1/admin/tenant-runtime/*`, `/api/v1/companies/{id}/tenant-runtime/policy`, `/api/v1/companies/{id}/tenant-metrics`, and `/api/v1/superadmin/tenants/{id}/usage` are retired from the published contract.

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
- `parameters`, `requesterUserId`, and `requesterEmail` only for tenant-admin viewers
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

Current response fields for onboarding truth:
- `bootstrapMode`
- `seededChartOfAccounts`
- `defaultAccountingPeriodCreated`
- `tenantAdminProvisioned`
- `companyId`
- `companyCode`
- `templateCode`
- `accountsCreated`
- `accountingPeriodId`
- `adminEmail`
- `mainAdminUserId`
- `credentialsEmailSent`
- `credentialsEmailedAt`
- `onboardingCompletedAt`
- `systemSettingsInitialized`

Current backend meaning:
- `bootstrapMode` is `SEEDED`
- `seededChartOfAccounts` is `true`
- `defaultAccountingPeriodCreated` is `true`
- `tenantAdminProvisioned` is `true`

Frontend action:
- treat the explicit bootstrap fields as the source of truth for seeded onboarding success
- stop expecting or rendering `adminTemporaryPassword`
- if `credentialsEmailSent=false`, keep the onboarding flow in error/support recovery instead of pretending the tenant is fully ready

## Retired control-plane aliases

- do not call `GET /api/v1/admin/exports/pending`
- do not call `GET /api/v1/companies/superadmin/dashboard`
- do not call `/api/v1/admin/changelog*`
- do not call `/api/v1/admin/tenant-runtime/*`
- do not call `/api/v1/companies/{id}/tenant-runtime/policy`
- do not call `/api/v1/companies/{id}/tenant-metrics`
- do not call `/api/v1/superadmin/tenants/{id}/usage`
- use `GET /api/v1/admin/approvals` for tenant-scoped admin/accounting approval queue access only
- use `GET /api/v1/superadmin/dashboard` for aggregate platform counts and `GET /api/v1/superadmin/tenants/{id}` for the canonical detailed tenant payload
