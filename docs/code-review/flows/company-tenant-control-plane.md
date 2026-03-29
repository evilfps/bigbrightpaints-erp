# Company / tenant control plane

## Scope and evidence

This review covers tenant onboarding, company CRUD/configuration, lifecycle transitions, scoped company context, module gating, tenant runtime policy, usage metrics, and super-admin support flows.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/{CompanyController,SuperAdminController,SuperAdminTenantOnboardingController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/{CompanyService,TenantOnboardingService,TenantLifecycleService,SuperAdminService,ModuleGatingInterceptor,ModuleGatingService,TenantRuntimeEnforcementService,TenantUsageMetricsService,TenantUsageMetricsInterceptor}.java`
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
| Company directory and control plane | `GET /api/v1/companies` | `CompanyController` | Tenant creation moved to the super-admin onboarding flow. The old `/api/v1/companies/{id}` delete surface is retired and no longer has a live operation contract. |
| Canonical super-admin company controls | `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules`, `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`, `POST /api/v1/superadmin/tenants/{id}/support/warnings`, `PUT /api/v1/superadmin/tenants/{id}/support/context`, `POST /api/v1/superadmin/tenants/{id}/force-logout` | `SuperAdminController` | The live contract is tenant-id under `/api/v1/superadmin/tenants/**`; company-id control-plane aliases are retired from the canonical surface. |
| Super-admin operations hub | `GET /api/v1/superadmin/dashboard`, `GET /api/v1/superadmin/tenants`, `GET /api/v1/superadmin/tenants/{id}` | `SuperAdminController` | Platform dashboard and tenant-detail reads stay here; lifecycle and support mutations use the canonical tenant-control endpoints above. |
| Scoped company context | Login and refresh with `companyCode`; `GET /api/v1/companies` for company metadata | `AuthController`, `CompanyController` | The canonical contract has no post-login company-switch route; bearer scope is chosen during login or refresh for one scoped account. |

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

### 2. Company CRUD and tenant configuration

`CompanyService` still contains some tenant-control mutations internally, but the published control-plane contract is the super-admin tenant family.

- `POST /api/v1/superadmin/tenants/onboard` is the only tenant-creation surface left in the live contract.
- `PUT /api/v1/superadmin/tenants/{id}/modules` calls `CompanyService.updateEnabledModules(...)`.
- `PUT /api/v1/superadmin/tenants/{id}/lifecycle`, `PUT /api/v1/superadmin/tenants/{id}/limits`, `PUT /api/v1/superadmin/tenants/{id}/modules`, `POST /api/v1/superadmin/tenants/{id}/support/warnings`, `PUT /api/v1/superadmin/tenants/{id}/support/context`, and `POST /api/v1/superadmin/tenants/{id}/force-logout` are the current-state control mutations.

What actually changes in `Company`:

- `name`, `code`, `timezone`, `stateCode`, `defaultGstRate`
- stored quota columns (`quota_max_*`, soft/hard flags)
- `enabled_modules` JSONB, but only for optional modules

Observed protocol split:

- The generic APIs use `timezone`.
- The alias super-admin create/update records use `region` and then map it into `CompanyRequest.timezone`.
- `CompanySuperAdminDashboardDto.TenantOverview.region` is later populated from `company.getTimezone()`.

That means the control plane currently treats `region` and `timezone` as the same field even though onboarding uses `timezone` explicitly.

### 3. Lifecycle changes and tenant admission control

Lifecycle changes now flow through the super-admin tenant family:

- Canonical: `PUT /api/v1/superadmin/tenants/{id}/lifecycle` -> `SuperAdminTenantControlPlaneService.updateLifecycleState(...)` -> `CompanyService.updateLifecycleState(...)` -> `TenantLifecycleService.transition(...)`
- Retired company-id lifecycle aliases belong to history only and are not part of the current-state contract.

Runtime admission then happens in `CompanyContextFilter` before controller code:

1. Reconcile `X-Company-Code` with JWT claims.
2. Reject unauthenticated company-scoped traffic.
3. For canonical `/api/v1/superadmin/tenants/{id}/...` control paths, resolve the path target company id and, if the actor is `ROLE_SUPER_ADMIN`, rebind the active company context to the targeted tenant.
4. Apply lifecycle rules: `ACTIVE` allows all, `SUSPENDED` is read-only, `DEACTIVATED` blocks everything.
5. Delegate request admission to `modules.company.service.TenantRuntimeEnforcementService.beginRequest(...)`.

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

The runtime policy layer is split across multiple implementations that share the same `tenant.runtime.*` setting keys.

#### Published runtime policy paths

- Tenant self-service reads and writes use `GET /api/v1/admin/tenant-runtime/metrics` and `PUT /api/v1/admin/tenant-runtime/policy` via `AdminSettingsController` -> `TenantRuntimePolicyService`.
- The old company-id runtime-policy mutation path is retired from the published contract even though `CompanyService.updateTenantRuntimePolicy(...)` still exists as an internal implementation seam.

This service also powers:

- `CompanyContextFilter.beginRequest(...)` / `completeRequest(...)` for request admission
- `AuthService.login(...)` and `AuthService.refresh(...)` through `enforceAuthOperationAllowed(...)`

It tracks state (`ACTIVE/HOLD/BLOCKED`), request rate, concurrent requests, and active-user caps from `system_settings`, not from the `companies` table.

#### Tenant-self runtime policy path

- `GET /api/v1/admin/tenant-runtime/metrics`
- `PUT /api/v1/admin/tenant-runtime/policy`
- `AdminSettingsController` -> `TenantRuntimePolicyService`
- `TenantRuntimeEnforcementInterceptor` applies a second layer of rate/concurrency enforcement for `/api/v1/reports/**`, `/api/v1/portal/**`, and `/api/v1/demo/**`; the retired `/api/v1/accounting/reports/**` alias is no longer part of the supported runtime surface.

#### Metrics split

- `CompanyService.getTenantMetrics(...)` and `CompanyService.getSuperAdminDashboard(...)` use `AuditLogRepository` counts plus the stored `Company` quota columns.
- `SuperAdminService.getTenantUsage(...)` and `SuperAdminService.getDashboard(...)` use `TenantUsageMetricsService` counters stored in `system_settings` plus `AuditLogRepository.estimateAuditStorageBytesByCompanyId(...)`.
- `TenantUsageMetricsInterceptor` increments API counters before module gating and before the portal/report runtime interceptor.

Result: the control plane exposes multiple tenant-usage dashboards that do not share a single source of truth.

### 7. Super-admin support and recovery paths

#### Admin password reset

`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset` -> `SuperAdminService.resetTenantAdminPassword(...)` -> `TenantAdminProvisioningService.resetTenantAdminPassword(...)`.

This path:

- validates that the target user belongs to the tenant and has `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`
- rewrites the password hash
- sets `mustChangePassword=true`
- clears lockout counters
- revokes access tokens and refresh tokens
- requires credential-email delivery to be enabled

#### Support warning

`POST /api/v1/superadmin/tenants/{id}/support/warnings` -> `SuperAdminTenantControlPlaneService.issueSupportWarning(...)` -> `CompanyService.issueTenantSupportWarning(...)`.

This path only creates a response DTO plus audit metadata. It does **not** persist a warning entity, queue a notification workflow, or change lifecycle state. The “warning” is therefore an operator signal, not a managed workflow object.

## State and invariants

- Tenant lifecycle is terminal once it reaches `DEACTIVATED`; service code does not allow recovery transitions.
- `CompanyContextFilter` is fail-closed on mismatched company headers, mismatched JWT claims, or missing company claims on authenticated requests.
- Public password-reset endpoints bypass tenant binding; most other authenticated requests require a company claim.
- Canonical `/api/v1/superadmin/tenants/{id}/...` control paths can be executed by super admins outside the tenant membership list because the filter rebinds to the path target company.
- `/api/v1/companies/{id}` remains a retired path shell and is not part of the live operation contract.
- `enabledModules` may only contain optional modules; core modules are implicit and always enabled.
- Company quota flags are enforced fail-closed at the entity/schema level (`soft OR hard` must stay true), but the live request-admission layer reads a different settings-backed quota model.
- There is no canonical post-login company-switch route; company scope is selected during login or refresh.
- Onboarding must create 50-100 accounts, a first admin, and an open accounting period.
- Company deletion is intentionally unsupported even though the route exists.

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
| medium | design / protocol | Historical company-id control paths and alias families still leave behind implementation seams next to the published `/api/v1/superadmin/tenants/{id}/...` control plane. | `CompanyContextFilter.isLifecycleControlRequest(...)`, `CompanyController`, `SuperAdminController`, `openapi.json` | Audit scope, runtime-policy bypass, and request-company binding can still drift if retired company-id paths are treated as live again. |
| medium | observability | Usage and runtime dashboards are backed by different counters and different interceptors. | `CompanyService.getTenantMetrics(...)`, `SuperAdminService.getTenantUsage(...)`, `TenantUsageMetricsService`, `TenantRuntimePolicyService`, `TenantRuntimeEnforcementInterceptor` | `/companies/{id}/tenant-metrics`, `/superadmin/tenants/{id}/usage`, `/superadmin/dashboard`, and `/admin/tenant-runtime/metrics` can disagree under load or after denials; the retired `/companies/superadmin/dashboard` alias is intentionally out of contract. |
| high | cache invalidation / runtime policy drift | The retired company-id runtime-policy mutation seam can still miss immediate policy-cache invalidation because `CompanyContextFilter` no longer calls `beginRequest()` on lifecycle-control paths, so `TenantRuntimeEnforcementService.completeRequest()` sees `TenantRequestAdmission.notTracked()` instead of a tracked policy-control request. | PR review on `CompanyContextFilter.beginRequest()/completeRequest()`, `TenantRuntimeEnforcementService.completeRequest(...)`, retired `CompanyService.updateTenantRuntimePolicy(...)` seam | If that internal seam is reused without a published contract refresh, a node can keep enforcing stale policy for roughly the cache TTL instead of the new setting. |
| medium | contract / dashboard drift | `GET /api/v1/admin/tenant-runtime/metrics` and `GET /api/v1/portal/{dashboard,operations,workforce}` return `200`, but their live payload shapes are still undocumented and may not match frontend tile/runtime assumptions. | live backend probes on `/api/v1/admin/tenant-runtime/metrics` and `/api/v1/portal/{dashboard,operations,workforce}`, `AdminSettingsController`, `PortalInsightsController`, absence of shape-specific contract coverage in review artifacts | Dashboard and quota UI work is forced to integrate against implicit payloads, increasing the chance of silent frontend/backend drift on tenant runtime and portal tiles. |
| medium | protocol / API drift | Super-admin alias DTOs call the field `region`, but the value is written into `Company.timezone` and later exposed back as `region`. | `CompanyController.SuperAdminTenantCreateRequest`, `CompanyRequest`, `CompanyService.buildTenantOverview(...)`, `CompanySuperAdminDashboardDto` | The public contract blurs geography and timezone, which is hard to migrate cleanly once clients depend on it. |
| medium | observability / workflow design | Support warnings are not persisted objects and are not part of the canonical target-tenant rebinding list. | `CompanyService.issueTenantSupportWarning(...)`, `CompanyContextFilter.isLifecycleControlRequest(...)`, `CompanyControllerIT.support_warning_endpoint_issues_warning_for_super_admin` | Warnings are hard to track, retry, or list later, and request/audit attribution follows a weaker path than lifecycle or reset actions. |
| low | API / UX | Historical company-id delete references linger in review artifacts even though `/api/v1/companies/{id}` is retired from the live contract. | retired `CompanyController.delete(...)` references, `openapi.json` history | Client generators and operators can still be misled if the retired history is read as current contract truth. |

## Security, privacy, protocol, and observability notes

### Strengths

- Company claim/header reconciliation in `CompanyContextFilter` blocks simple tenant-header spoofing.
- Unauthenticated callers cannot inject `X-Company-Code` to force tenant context.
- Canonical runtime-policy control and lifecycle control allow super-admin recovery even when the target tenant is non-active.
- Admin password reset revokes access tokens and refresh tokens before emailing replacement credentials.

### Hotspots

- `TenantUsageMetricsInterceptor` records API usage before module gating and before the portal/report runtime interceptor, so “usage” can include requests that are rejected later in the chain.
- `CompanyService.getSuperAdminDashboard(...)` and `SuperAdminService.listTenants(...)` both use per-tenant count loops, which will turn into N+1 query pressure as tenant count grows.
- Lifecycle code writes audit metadata claiming `lifecycleEvidence=immutable-audit-log`, but `AuditService` is asynchronous and intentionally fail-open on persistence errors, so the evidence trail is best-effort rather than guaranteed.
- Because there is no canonical post-login switch route, frontend and operator flows must treat login/refresh as the only valid way to change tenant scope.
- There are effectively three runtime-policy implementations in main code (`modules.company.service.TenantRuntimeEnforcementService`, `modules.admin.service.TenantRuntimePolicyService` + `modules.portal.service.TenantRuntimeEnforcementInterceptor`, and `core.security.TenantRuntimeEnforcementService` with no obvious production wiring). Shared keys with divergent logic are a maintenance risk.
- The current auth/security follow-up PR still leaves a regression risk if retired company-scoped runtime-policy updates are ever revived without restoring tracked invalidation.

## Evidence notes

- `TenantOnboardingControllerTest` proves onboarding creates company, accounts, admin membership, and an open accounting period for each template.
- Historical `CompanyControllerIT` coverage referenced retired company-id control paths; current published control-plane proof is centered on `/api/v1/superadmin/**` and tenant-scoped admin runtime surfaces.
- `SuperAdminControllerIT` proves `/api/v1/superadmin/**` can suspend, activate, deactivate, list usage, and rewrite enabled modules.
- `TS_RuntimeCompanyContextFilterExecutableCoverageTest` and `TS_RuntimeTenantPolicyControlExecutableCoverageTest` prove that canonical control paths are explicitly recognized, and that privileged runtime-policy control is path-sensitive.
- `TS_RuntimeTenantControlPlaneEnforcementTest` shows the separate runtime-enforcement service still interprets the same `tenant.runtime.*` policy namespace. Legacy token fallback behavior is out of contract and removed on this branch.
