# Company / tenant control plane

## Scope and evidence

This review covers tenant onboarding, company CRUD/configuration, lifecycle transitions, scoped company context, module gating, tenant runtime policy, usage metrics, and super-admin support flows.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/{CompanyController,SuperAdminController,SuperAdminTenantOnboardingController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/{CompanyService,TenantOnboardingService,TenantLifecycleService,SuperAdminTenantControlPlaneService,ModuleGatingInterceptor,ModuleGatingService,TenantRuntimeEnforcementService,TenantUsageMetricsService,TenantUsageMetricsInterceptor}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{CompanyContextFilter,SecurityConfig,TenantRuntimeEnforcementService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/{controller/AdminSettingsController.java,service/TenantRuntimePolicyService.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/{AuthService,TenantAdminProvisioningService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/domain/{Company,CompanyLifecycleState,CompanyModule,CompanyRepository}.java`
- `erp-domain/src/main/resources/db/migration_v2/{V19__company_lifecycle_state.sql,V20__company_quota_controls.sql,V24__tenant_onboarding_coa_templates.sql,V25__module_gating_and_period_costing_method.sql}`
- `openapi.json`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/{CompanyControllerIT,SuperAdminControllerIT,TenantOnboardingControllerTest}.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/{TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_RuntimeTenantControlPlaneEnforcementTest}.java`

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Tenant bootstrap | `GET /api/v1/superadmin/tenants/coa-templates`, `POST /api/v1/superadmin/tenants/onboard` | `SuperAdminTenantOnboardingController` | Creates tenant shell, chart of accounts, first admin, default period, and some global settings; the response now explicitly confirms seeded bootstrap outcomes. |
| Company directory | `GET /api/v1/companies` | `CompanyController` | Read-only company directory metadata for scoped users and super admins. Tenant creation and tenant control-plane mutations are intentionally moved out of this surface. |
| Canonical super-admin tenant control plane | `GET /api/v1/superadmin/dashboard`, `GET /api/v1/superadmin/tenants`, `GET /api/v1/superadmin/tenants/{id}`, `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules`, `POST /api/v1/superadmin/tenants/{id}/support/warnings`, `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`, `PUT /api/v1/superadmin/tenants/{id}/support/context`, `POST /api/v1/superadmin/tenants/{id}/force-logout`, `PUT /api/v1/superadmin/tenants/{id}/admins/main`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request`, `POST /api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | `SuperAdminController` | Canonical public tenant control plane for lifecycle, limits, module configuration, support, and admin recovery flows. `CompanyContextFilter` explicitly recognizes this family as company-bound control traffic. |
| Scoped company context | Login and refresh with `companyCode`; `GET /api/v1/companies` for company metadata | `AuthController`, `CompanyController` | The canonical contract has no post-login company-switch route; bearer scope is chosen during login or refresh for one scoped account. |
| Tenant-scoped admin operations | `GET /api/v1/admin/settings`, `PUT /api/v1/admin/settings`, `GET /api/v1/admin/approvals`, `PUT /api/v1/admin/exports/{requestId}/{approve|reject}`, `POST /api/v1/admin/notify` | `AdminSettingsController` | Tenant-scoped admin settings and approval surface. The older public `/api/v1/admin/tenant-runtime/*` routes are retired from the live contract. |

## Data path and schema touchpoints

| Data store | Evidence | Used by |
| --- | --- | --- |
| `companies` row | `Company.java`, `V19__company_lifecycle_state.sql`, `V20__company_quota_controls.sql`, `V25__module_gating_and_period_costing_method.sql` | Name/code/timezone/stateCode, lifecycle state, enabled modules, stored quota envelope, default accounts. |
| `app_users.company_id` | `UserAccount.java`, `AuthService.resolveCompanyForScope(...)` | Single-company binding and login target selection for each scoped account. |
| `coa_templates` | `V24__tenant_onboarding_coa_templates.sql`, `CoATemplateService` | Bootstrap template catalog. |
| Accounts + accounting periods | `TenantOnboardingService`, `AccountRepository`, `AccountingPeriodService` | Onboarding side effects create 63-91 accounts plus an open period. |
| `system_settings` generic KV | `TenantRuntimePolicyService`, `TenantRuntimeEnforcementService`, `TenantUsageMetricsService` | Runtime policy, runtime counters, usage counters, onboarding defaults (`auto-approval.enabled`, `period-lock.enforced`). |
| `audit_logs` | `AuditService`, `AuditLogRepository`, `CompanyService.getTenantMetrics(...)` | Tenant metrics, dashboard summaries, support/lifecycle audit breadcrumbs. |

## Service chain

### 1. Tenant onboarding

`SuperAdminTenantOnboardingController.onboardTenant(...)` hands off to `TenantOnboardingService.onboardTenant(...)`.

Narrative chain:

1. Validate and normalize `code` and `firstAdminEmail`.
2. `CoATemplateService.requireActiveTemplate(...)` resolves a template from `coa_templates`.
3. `TenantOnboardingService.resolveTemplateBlueprints(...)` expands the template into 63-91 in-memory account blueprints.
4. Persist a new `Company` row.
5. Persist the template accounts, wire default account ids back onto the `Company`, and create an open accounting period.
6. Provision a first admin user with `ROLE_ADMIN`, bind the account to the new company scope, generate temporary credentials internally, and require credential email delivery to the target user.
7. Seed global system settings (`auto-approval.enabled`, `period-lock.enforced`) if missing.
8. Return `TenantOnboardingResponse` with explicit `bootstrapMode`, `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, `tenantAdminProvisioned`, and `adminEmail` fields only.

Important invariants:

- Template size must stay between 50 and 100 accounts.
- Parent accounts must appear before children in the blueprint list.
- First admin email and company code must be globally unique.
- The company must exist before admin provisioning starts.

Side effects:

- `accounts`, `app_users`, `accounting_periods`, and `system_settings` are mutated in one onboarding transaction.
- SMTP delivery is required, and the response does not expose any password field.

### 2. Company directory and tenant configuration

`CompanyController` is now read-only, while `SuperAdminController`,
`SuperAdminTenantControlPlaneService`, and `CompanyService` own the mutable tenant control plane.

- `GET /api/v1/companies` is the only live `CompanyController` route.
- `POST /api/v1/superadmin/tenants/onboard` is the only tenant-creation surface left in the live contract.
- `PUT /api/v1/superadmin/tenants/{id}/limits` updates the stored quota columns on `Company` and mirrors the resolved limits into `TenantRuntimeEnforcementService`.
- `PUT /api/v1/superadmin/tenants/{id}/modules` delegates through `SuperAdminTenantControlPlaneService.updateModules(...)` to `CompanyService.updateEnabledModules(...)`.

What actually changes in `Company`:

- `name`, `code`, `timezone`, `stateCode`, `defaultGstRate`
- stored quota columns (`quota_max_*`, soft/hard flags)
- `enabled_modules` JSONB, but only for optional modules

Observed protocol split:

- The generic APIs use `timezone`.
- `CompanySuperAdminDashboardDto.TenantOverview.region` is populated from `company.getTimezone()`.

That means the super-admin dashboard contract still treats `region` and `timezone` as the same field even though onboarding uses `timezone` explicitly.

### 3. Lifecycle changes and tenant admission control

There is one live public lifecycle mutation family:

- `PUT /api/v1/superadmin/tenants/{id}/lifecycle` -> `SuperAdminTenantControlPlaneService.updateLifecycleState(...)` -> `CompanyService.updateLifecycleState(...)` -> `TenantLifecycleService.transition(...)`

Runtime admission then happens in `CompanyContextFilter` before controller code:

1. Reconcile `X-Company-Code` with JWT claims.
2. Reject unauthenticated company-scoped traffic.
3. For company-bound `/api/v1/superadmin/tenants/{id}/...` control paths, resolve the path target company id and, if the actor is `ROLE_SUPER_ADMIN`, rebind the active company context to the targeted tenant.
4. Apply lifecycle rules: `ACTIVE` allows all, `SUSPENDED` is read-only, `DEACTIVATED` blocks everything.
5. Delegate request admission to `TenantRuntimeRequestAdmissionService.beginRequest(...)`.

`TenantLifecycleService` enforces a small state machine:

- `ACTIVE -> SUSPENDED | DEACTIVATED`
- `SUSPENDED -> ACTIVE | DEACTIVATED`
- `DEACTIVATED -> (none)`

### 4. Scoped company context

The real bearer-token binding comes from auth:

- `AuthService.login(...)` and `AuthService.refresh(...)` generate JWTs for the selected `companyCode`.
- `CompanyContextFilter` later rejects mismatches between token company claims and company headers.

There is no canonical post-login company-switch route. Moving to another company context requires login or refresh for that company scope.

### 5. Module gating

Module enablement is stored on `Company.enabledModules` and normalized through `CompanyModule`.

- Core modules (`AUTH`, `ACCOUNTING`, `SALES`, `INVENTORY`) are always-on and are rejected if passed through `enabledModules` mutation payloads.
- Optional modules (`MANUFACTURING`, `HR_PAYROLL`, `PURCHASING`, `PORTAL`, `REPORTS_ADVANCED`) default to enabled when `enabled_modules` is null.

Request-time enforcement path:

1. `TenantRuntimeEnforcementConfig` registers `TenantUsageMetricsInterceptor`, `ModuleGatingInterceptor`, and `TenantRuntimeEnforcementInterceptor`.
2. `ModuleGatingInterceptor.resolveTargetModule(...)` maps URL prefixes to a `CompanyModule`.
3. `ModuleGatingService.isEnabledForCurrentCompany(...)` resolves the current `Company` from `CompanyContextHolder` and checks the normalized optional-module set.

This is lightweight, but it makes route-prefix naming part of the authorization contract.

### 6. Tenant runtime policy and usage metrics

The runtime policy layer is split across multiple implementations that share the same `tenant.runtime.*` setting keys, but the public company-control mutation routes were hard-cut in Wave 2.

#### Public contract state

- `OpenApiSnapshotIT` asserts `PUT /api/v1/companies/{id}/tenant-runtime/policy` is missing.
- `OpenApiSnapshotIT` also asserts `PUT /api/v1/admin/tenant-runtime/policy` is missing.
- `CompanyService.updateTenantRuntimePolicy(...)` remains an internal service surface exercised by service/runtime tests, not a live public controller contract.

#### Internal runtime policy machinery

The remaining service code still powers:

- `CompanyContextFilter.beginRequest(...)` / `completeRequest(...)` for request admission
- `AuthService.login(...)` and `AuthService.refresh(...)` through `enforceAuthOperationAllowed(...)`

It tracks state (`ACTIVE/HOLD/BLOCKED`), request rate, concurrent requests, and active-user caps from `system_settings`, not from the `companies` table.

#### Metrics split

- `CompanyService.getTenantMetrics(...)` and `CompanyService.getSuperAdminDashboard(...)` use `AuditLogRepository` counts plus the stored `Company` quota columns.
- `SuperAdminTenantControlPlaneService.getTenantDetail(...)` and `SuperAdminTenantControlPlaneService.listTenants(...)` delegate to `CompanyService.getTenantMetricsForSuperAdmin(...)`, which uses the same stored quota columns plus audit-derived usage data.
- `TenantUsageMetricsInterceptor` increments API counters before module gating and before the portal/report runtime interceptor.

Result: the control plane exposes multiple tenant-usage dashboards that do not share a single source of truth.

### 7. Super-admin support and recovery paths

#### Admin password reset

`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` -> `SuperAdminTenantControlPlaneService.resetTenantAdminPassword(...)` -> `CompanyService.resetTenantAdminPassword(...)` -> `TenantAdminProvisioningService.resetTenantAdminPassword(...)`.

This path:

- validates that the target user belongs to the tenant and has `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`
- rewrites the password hash
- sets `mustChangePassword=true`
- clears lockout counters
- revokes access tokens and refresh tokens
- requires credential-email delivery to be enabled

#### Support warning

`POST /api/v1/superadmin/tenants/{id}/support/warnings` -> `SuperAdminTenantControlPlaneService.issueSupportWarning(...)`.

This path persists a `TenantSupportWarning` row, returns the saved warning metadata, and writes audit metadata. It still does **not** change lifecycle state automatically, so the warning remains an operator-managed workflow input rather than an automated state transition.

## State and invariants

- Tenant lifecycle is terminal once it reaches `DEACTIVATED`; service code does not allow recovery transitions.
- `CompanyContextFilter` is fail-closed on mismatched company headers, mismatched JWT claims, or missing company claims on authenticated requests.
- Public password-reset endpoints bypass tenant binding; most other authenticated requests require a company claim.
- Company-bound `/api/v1/superadmin/tenants/{id}/...` control paths can be executed by super admins outside the tenant membership list because the filter rebinds to the path target company.
- There is no live `/api/v1/companies/{id}/...` control-plane family left in the published contract.
- `enabledModules` may only contain optional modules; core modules are implicit and always enabled.
- Company quota flags are enforced fail-closed at the entity/schema level (`soft OR hard` must stay true), but the live request-admission layer reads a different settings-backed quota model.
- There is no canonical post-login company-switch route; company scope is selected during login or refresh.
- Onboarding must create 50-100 accounts, a first admin, and an open accounting period.
- Company deletion is intentionally unsupported and the route is removed from runtime.

## Side effects and integrations

- SMTP is part of onboarding and admin-reset flows via `EmailService`.
- `AuditService` and `EnterpriseAuditTrailService` record runtime denials and lifecycle/configuration changes.
- `SystemSettingsRepository` becomes a control-plane backing store for runtime policy, runtime counters, usage counters, and bootstrap defaults.
- `AuditLogRepository` powers the richer super-admin metrics surfaces.
- `RoleService.ensureRoleExists("ROLE_ADMIN")` is a hidden onboarding dependency.

## Failure points

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| high | integrity / migrations | Database lifecycle constraint still allows `ACTIVE/HOLD/BLOCKED`, while the Java enum and DTOs use `ACTIVE/SUSPENDED/DEACTIVATED`. | `V19__company_lifecycle_state.sql`, `CompanyLifecycleState.java` | Super-admin suspend/deactivate flows can fail against migrated databases or drift between old and new lifecycle vocabularies. |
| high | governance / integrity | The quota envelope stored on `Company` is not the same model used by live runtime admission. | `CompanyService.create/update`, `TenantOnboardingService.createCompany`, `CompanyService.getTenantMetrics`, `TenantRuntimeEnforcementService.loadPersistedPolicy(...)`, no main-code callers for `CompanyService.isRuntimeAccessAllowed(...)` | Operators can configure quotas in company CRUD and dashboards, but request admission still follows separate `tenant.runtime.*` settings and defaults. |
| low | resolved hard-cut cleanup | Auth V2 onboarding no longer returns plaintext password fields, and credential email delivery is required before success is returned. | `TenantOnboardingResponse.java`, `ScopedAccountBootstrapService`, `TenantOnboardingControllerTest` | This earlier privacy finding is resolved on the current branch and retained here only for traceability. |
| medium | observability | Super-admin dashboard summaries and tenant detail usage views still depend on different aggregation paths and interceptors. | `CompanyService.getSuperAdminDashboard(...)`, `SuperAdminTenantControlPlaneService.getTenantDetail(...)`, `TenantUsageMetricsService`, `TenantRuntimePolicyService`, `TenantRuntimeEnforcementInterceptor` | Dashboard totals can still drift from per-tenant detail views under load or after denied requests because the counters are assembled from multiple layers. |
| medium | contract / dashboard drift | Super-admin dashboard DTOs still call the field `region`, but the value is sourced from `Company.timezone`. | `CompanyService.buildTenantOverview(...)`, `CompanySuperAdminDashboardDto` | The public contract still blurs geography and timezone, which is hard to migrate cleanly once clients depend on it. |
| low | resolved hard-cut cleanup | `DELETE /api/v1/companies/{id}` is removed from runtime and the published contract on the current branch. | `CompanyController`, `OpenApiSnapshotIT`, `openapi.json` | This earlier API/UX finding is resolved on the current branch and retained here only for traceability. |

## Security, privacy, protocol, and observability notes

### Strengths

- Company claim/header reconciliation in `CompanyContextFilter` blocks simple tenant-header spoofing.
- Unauthenticated callers cannot inject `X-Company-Code` to force tenant context.
- Canonical runtime-policy control and lifecycle control allow super-admin recovery even when the target tenant is non-active.
- Admin password reset revokes access tokens and refresh tokens before emailing replacement credentials.

### Hotspots

- `TenantUsageMetricsInterceptor` records API usage before module gating and before the portal/report runtime interceptor, so “usage” can include requests that are rejected later in the chain.
- `CompanyService.getSuperAdminDashboard(...)` and `SuperAdminTenantControlPlaneService.listTenants(...)` both use per-tenant count loops, which will turn into N+1 query pressure as tenant count grows.
- Lifecycle code writes audit metadata claiming `lifecycleEvidence=immutable-audit-log`, but `AuditService` is asynchronous and intentionally fail-open on persistence errors, so the evidence trail is best-effort rather than guaranteed.
- Because there is no canonical post-login switch route, frontend and operator flows must treat login/refresh as the only valid way to change tenant scope.
- There are effectively three runtime-policy implementations in main code (`modules.company.service.TenantRuntimeEnforcementService`, `modules.admin.service.TenantRuntimePolicyService` + `modules.portal.service.TenantRuntimeEnforcementInterceptor`, and `core.security.TenantRuntimeEnforcementService` with no obvious production wiring). Shared keys with divergent logic are a maintenance risk.
## Evidence notes

- `TenantOnboardingControllerTest` proves onboarding creates company, accounts, admin membership, and an open accounting period for each template.
- `CompanyControllerIT` proves retired `/api/v1/companies/{id}/...` aliases return `404`, including tenant metrics, support reset, and runtime-policy mutation paths.
- `SuperAdminControllerIT` proves `/api/v1/superadmin/**` can list tenants, fetch tenant detail, update lifecycle, update limits, rewrite enabled modules, and execute support/admin recovery flows.
- `TS_RuntimeCompanyContextFilterExecutableCoverageTest` and `TS_RuntimeTenantPolicyControlExecutableCoverageTest` prove that company-bound super-admin control paths are explicitly recognized and that privileged runtime-policy control remains path-sensitive inside the internal service layer.
- `TS_RuntimeTenantControlPlaneEnforcementTest` shows the separate runtime-enforcement service still interprets the same `tenant.runtime.*` policy namespace. Legacy token fallback behavior is out of contract and removed on this branch.
