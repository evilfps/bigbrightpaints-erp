# Company / tenant control plane

## Scope and evidence

This review covers tenant onboarding, the canonical superadmin tenant control plane, company-list visibility, multi-company switching, runtime admission binding, and the persisted support/admin-governance truth introduced by ERP-37.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/{CompanyController,MultiCompanyController,SuperAdminController,SuperAdminTenantOnboardingController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/{CompanyService,SuperAdminTenantControlPlaneService,TenantLifecycleService,TenantOnboardingService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/TenantAdminProvisioningService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/domain/{Company,CompanyLifecycleState,TenantSupportWarning,TenantAdminEmailChangeRequest}.java`
- `erp-domain/src/main/resources/db/migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql`
- `openapi.json`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/{CompanyControllerIT,SuperAdminControllerIT,TenantOnboardingControllerTest}.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/{AuthTenantAuthorityIT,SuperAdminTenantWorkflowIsolationIT,TenantRuntimeEnforcementAuthIT}.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/{TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_RuntimeTenantRuntimeEnforcementTest}.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Tenant bootstrap | `GET /api/v1/superadmin/tenants/coa-templates`, `POST /api/v1/superadmin/tenants/onboard` | `SuperAdminTenantOnboardingController` | Creates tenant shell, seeded CoA, first admin, and default accounting period. Success now returns onboarding delivery/completion truth and no temporary password. |
| Canonical superadmin tenant control | `GET /api/v1/superadmin/dashboard`, `GET /api/v1/superadmin/tenants`, `GET /api/v1/superadmin/tenants/{id}`, `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules`, `POST /api/v1/superadmin/tenants/{id}/support/warnings`, `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`, `PUT /api/v1/superadmin/tenants/{id}/support/context`, `POST /api/v1/superadmin/tenants/{id}/force-logout`, `PUT /api/v1/superadmin/tenants/{id}/admins/main`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | `SuperAdminController` | This is the only surviving public mutation family for tenant lifecycle, limits, modules, support, and admin-governance actions. |
| Company list / hard-denied delete | `GET /api/v1/companies`, `DELETE /api/v1/companies/{id}` | `CompanyController` | List remains. Delete is intentionally published as a denial path and does not remove tenants. No other company-scoped control-plane mutations survive here. |
| Multi-company switching | `POST /api/v1/multi-company/companies/switch` | `MultiCompanyController` | Still membership validation only; actual tenant-bound access tokens are minted through login/refresh. |

## Persisted truth

ERP-37 hard-cuts tenant control onto persisted company- and support-owned truth instead of split settings aliases.

### `companies`

`V167__erp37_superadmin_control_plane_hard_cut.sql` and `Company.java` now make `companies` the canonical home for:

- lifecycle state using only `ACTIVE`, `SUSPENDED`, `DEACTIVATED`
- quota envelope including `quota_max_concurrent_requests`
- `main_admin_user_id`
- `support_notes`
- `support_tags`
- onboarding truth: `onboarding_coa_template_code`, `onboarding_admin_email`, `onboarding_admin_user_id`, `onboarding_completed_at`, `onboarding_credentials_emailed_at`

The migration rewrites legacy stored values:

- `HOLD` -> `SUSPENDED`
- `BLOCKED` -> `DEACTIVATED`
- `quota_max_concurrent_sessions` -> `quota_max_concurrent_requests`

### `tenant_support_warnings`

Support warnings are now first-class persisted rows keyed by tenant, with category, message, requested lifecycle state, grace period, issuer, and timestamp.

### `tenant_admin_email_change_requests`

Admin email changes are now a persisted verification workflow with:

- company scope
- target admin user
- current and requested email
- verification token
- sent, verified, confirmed, expiry, and consumed markers

### Audit and session stores

The support timeline and force-logout flows still rely on shared platform stores:

- `audit_logs` for immutable control-plane history
- `refresh_tokens`, `user_token_revocations`, and `blacklisted_tokens` for full-session revocation

## Service flow

### 1. Tenant onboarding

`TenantOnboardingService.onboardTenant(...)` is the only tenant bootstrap path.

Current behavior:

1. Resolve an active CoA template.
2. Persist the tenant.
3. Seed accounts and the default accounting period.
4. Provision the first admin with `saveAndFlush(...)` so the main-admin pointer can be persisted immediately.
5. Require credential-email delivery to succeed before onboarding returns success.
6. Persist onboarding evidence back onto the `Company` row.
7. Return `TenantOnboardingResponse` with `mainAdminUserId`, `credentialsEmailSent`, `credentialsEmailedAt`, and `onboardingCompletedAt`.

Hard-cut change:

- `adminTemporaryPassword` is gone from the response contract.

### 2. Canonical tenant detail and control actions

`SuperAdminTenantControlPlaneService` is now the single control-plane service.

`GET /api/v1/superadmin/tenants/{id}` assembles one canonical detail payload:

- tenant identity
- lifecycle state and reason
- enabled modules
- onboarding truth
- main-admin summary
- limit envelope
- honest usage (`auditStorageBytes`, `currentConcurrentRequests`)
- support notes/tags
- support timeline built from persisted warnings plus audit history
- available action flags

Mutation flows now live together:

- lifecycle transitions
- limits updates
- module updates
- support warnings
- support context updates
- admin password reset
- tenant-wide force logout
- main-admin replacement
- admin email-change request/confirm

The control plane no longer exposes separate `/usage`, `/activate`, `/deactivate`, `/suspend`, or `/lifecycle-state` aliases.

### 3. Lifecycle and runtime admission

`TenantLifecycleService` now uses the same vocabulary the API and database expose.

Allowed lifecycle moves are:

- `ACTIVE -> SUSPENDED | DEACTIVATED`
- `SUSPENDED -> ACTIVE | DEACTIVATED`
- `DEACTIVATED -> ACTIVE`

Request-time tenant binding is enforced in `CompanyContextFilter`:

- only `/api/v1/superadmin/tenants/{id}` and the canonical nested mutation suffixes are treated as target-tenant control requests
- superadmin actors are rebound to the targeted tenant only on that canonical family
- superadmins are still blocked from tenant business paths except explicitly allowed control exceptions

### 4. Admin governance controls

ERP-37 folds admin-governance actions into the tenant control plane:

- main-admin pointer is persisted on `companies.main_admin_user_id`
- support resets keep `mustChangePassword=true`
- force logout revokes access and refresh sessions across the entire tenant
- email changes require request + verification + confirm instead of direct mutation

## Invariants

- The only surviving public tenant-control mutation family is `/api/v1/superadmin/tenants/{id}/...`.
- No public tenant-control mutation route survives under `/api/v1/companies/{id}/...`.
- No tenant runtime read/write surface survives under `/api/v1/admin/tenant-runtime/**`.
- Lifecycle vocabulary is `ACTIVE | SUSPENDED | DEACTIVATED` end-to-end.
- Quota concurrency vocabulary is `quotaMaxConcurrentRequests`, not sessions.
- Tenant usage exposes `auditStorageBytes`, not a generic tenant `storageBytes`.
- Support warnings and admin email changes are persisted objects, not ephemeral response-only signals.
- Tenant detail is the single control-plane truth surface for support, onboarding, limits, usage, and admin-governance context.

## Residual risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| medium | API / UX | `DELETE /api/v1/companies/{id}` remains published even though the controller always denies the operation. | `CompanyController.delete(...)`, `openapi.json`, `CompanyControllerIT` | Clients can still discover a destructive-looking route that is intentionally unsupported. |
| low | naming drift | `CompanySuperAdminDashboardDto.TenantOverview` still calls the timezone-backed field `region`. | `CompanySuperAdminDashboardDto.TenantOverview` | Dashboard consumers need to treat that field as display geography/timezone metadata, not a new canonical storage concept. |

## Evidence notes

- `SuperAdminControllerIT` proves the canonical tenant detail, lifecycle, limits, modules, support warning/context, force-logout, main-admin replacement, and email-change flows.
- `TenantOnboardingControllerTest` proves onboarding success, fail-closed credential delivery, and the no-password response contract.
- `AuthTenantAuthorityIT` and `SuperAdminTenantWorkflowIsolationIT` prove tenant-admin boundaries remain intact while superadmin control is routed through the canonical tenant-control plane.
- `TS_RuntimeCompanyContextFilterExecutableCoverageTest` and `TS_RuntimeTenantPolicyControlExecutableCoverageTest` prove `CompanyContextFilter` binds only the canonical `/api/v1/superadmin/tenants/{id}/...` family.
