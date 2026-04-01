# Company Module

Last reviewed: 2026-03-30

This packet documents the **company module** (`modules/company`) and the tenant-runtime infrastructure it owns. It covers tenant lifecycle, runtime admission, module gating, tenant onboarding, super-admin control-plane operations, company-context resolution, and usage-enforcement surfaces.

## Ownership Summary

The company module owns the **tenant lifecycle and runtime enforcement** surface: company CRUD, tenant lifecycle transitions, runtime request admission, per-tenant quota enforcement, module gating, super-admin control-plane operations, tenant onboarding, and company-context resolution.

| Area | Package |
| --- | --- |
| Company controllers | `modules/company/controller/` |
| Company services | `modules/company/service/` |
| Company DTOs | `modules/company/dto/` |
| Company domain entities | `modules/company/domain/` |

## Primary Controllers and Routes

### CompanyController — `/api/v1/companies`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/companies` | `ROLE_SUPER_ADMIN`, `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES` | List companies (super-admin: all; tenant users: own company only) |
| DELETE | `/api/v1/companies/{id}` | `ROLE_ADMIN` | Currently always denies deletion — companies cannot be deleted |

### SuperAdminController — `/api/v1/superadmin`

All endpoints require `ROLE_SUPER_ADMIN`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/superadmin/dashboard` | Super-admin dashboard metrics |
| GET | `/api/v1/superadmin/tenants` | List tenants (optional `?status=` filter) |
| GET | `/api/v1/superadmin/tenants/{id}` | Tenant detail |
| PUT | `/api/v1/superadmin/tenants/{id}/lifecycle` | Update tenant lifecycle state |
| PUT | `/api/v1/superadmin/tenants/{id}/limits` | Update tenant usage limits |
| PUT | `/api/v1/superadmin/tenants/{id}/modules` | Update enabled modules for tenant |
| POST | `/api/v1/superadmin/tenants/{id}/support/warnings` | Issue support warning |
| POST | `/api/v1/superadmin/tenants/{id}/support/admin-password-reset` | Force-reset tenant admin password |
| PUT | `/api/v1/superadmin/tenants/{id}/support/context` | Update support notes and tags |
| POST | `/api/v1/superadmin/tenants/{id}/force-logout` | Force-logout all tenant users |
| PUT | `/api/v1/superadmin/tenants/{id}/admins/main` | Replace main admin |
| POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request` | Request admin email change |
| POST | `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` | Confirm admin email change |

### SuperAdminTenantOnboardingController — `/api/v1/superadmin/tenants`

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/v1/superadmin/tenants/coa-templates` | `ROLE_SUPER_ADMIN` | List available chart-of-accounts templates |
| POST | `/api/v1/superadmin/tenants/onboard` | `ROLE_SUPER_ADMIN` | Onboard new tenant (company + admin user + seeded CoA + default period) |

## Key Services

### TenantLifecycleService

Manages company lifecycle state transitions with a constrained state machine:

```
ACTIVE  ←→  SUSPENDED  →  DEACTIVATED
  │               │             ↑
  │               └─────────────┘
  └─────────────────────────────┘
```

- `ACTIVE → SUSPENDED`, `ACTIVE → DEACTIVATED`
- `SUSPENDED → ACTIVE`, `SUSPENDED → DEACTIVATED`
- `DEACTIVATED → ACTIVE` (recovery)

Each transition requires a reason string. Invalid transitions are rejected. All transitions are audited.

Lifecycle states are enforced by `CompanyContextFilter` on every tenant-scoped request:

| State | GET/HEAD/OPTIONS | POST/PUT/DELETE/PATCH |
| --- | --- | --- |
| `ACTIVE` | Allowed | Allowed |
| `SUSPENDED` | Allowed | Denied |
| `DEACTIVATED` | Denied | Denied |

### TenantRuntimeEnforcementService

The canonical tenant runtime policy-mutation and snapshot owner. Manages per-tenant runtime policies and in-flight request tracking.

**Runtime states** (separate from lifecycle states):

| Runtime State | Effect |
| --- | --- |
| `ACTIVE` | Normal operation |
| `HOLD` | Read-only — mutating requests rejected with `HTTP 423 LOCKED` |
| `BLOCKED` | All requests rejected with `HTTP 403 FORBIDDEN` |

**Quota enforcement:**

| Limit | Default | Description |
| --- | --- | --- |
| `maxConcurrentRequests` | 200 | Maximum in-flight requests per tenant |
| `maxRequestsPerMinute` | 5000 | Maximum requests per minute per tenant |
| `maxActiveUsers` | 500 | Maximum enabled user accounts per tenant |

**Request admission flow:**

1. Normalize company code.
2. Resolve runtime policy (cached in-memory with configurable TTL, default 15 seconds; backed by `system_settings` table).
3. Check runtime state (`HOLD` rejects mutating requests; `BLOCKED` rejects all).
4. Check per-minute rate limit.
5. Check concurrent request limit.
6. Admit request, increment in-flight counter.
7. On completion: decrement in-flight counter, track error responses.

**Auth-operation enforcement:**
Called during login and refresh to check tenant state and active-user quota. Rejects login if the tenant is on hold/blocked or if the active-user count exceeds the quota.

**Policy persistence:**
Runtime policies are persisted to the `system_settings` table with keys like `tenant.runtime.hold-state.{companyId}`, `tenant.runtime.max-concurrent-requests.{companyId}`, etc. On startup or cache expiry, policies are loaded from persistence. If persistence is unavailable during a cache refresh, the last-known in-memory policy is kept (request admission remains available during transient outages).

### TenantRuntimeRequestAdmissionService

A thin facade over `TenantRuntimeEnforcementService` that provides the entry point for runtime filters, interceptors, and auth flows. It prevents direct coupling between the filter chain and the enforcement policy service.

### TenantOnboardingService

Handles new-tenant creation:

1. Validates the onboarding request (company code, name, admin email, password, CoA template).
2. Creates the `Company` entity with `ACTIVE` lifecycle state.
3. Seeds the chart of accounts from the selected template.
4. Creates the tenant admin `UserAccount` with `ROLE_ADMIN`.
5. Creates the default accounting period.
6. Returns the onboarding result with company and admin details.

### SuperAdminTenantControlPlaneService

The super-admin control plane for tenant management. Provides tenant listing/detail, lifecycle transitions, usage-limit updates, module management, support operations (warnings, password resets, notes/tags), session management (force-logout, main-admin replacement), and admin email changes with verification tokens.

### ModuleGatingService

Controls which optional modules are available per tenant:

| Module | Gatable | Default |
| --- | --- | --- |
| `AUTH` | No (core) | Always enabled |
| `ACCOUNTING` | No (core) | Always enabled |
| `SALES` | No (core) | Always enabled |
| `INVENTORY` | No (core) | Always enabled |
| `MANUFACTURING` | Yes | Enabled |
| `HR_PAYROLL` | Yes | **Disabled** |
| `PURCHASING` | Yes | Enabled |
| `PORTAL` | Yes | Enabled |
| `REPORTS_ADVANCED` | Yes | Enabled |

Core modules cannot be disabled. `HR_PAYROLL` is the only gatable module that defaults to disabled.

### CompanyContextService

Resolves the current company from `CompanyContextHolder` (ThreadLocal set by `CompanyContextFilter`). Provides `requireCurrentCompany()` and `resolveCurrentCompanyCode()`.

## Domain Entities

| Entity | Purpose |
| --- | --- |
| `Company` | Tenant entity with code, name, lifecycle state, enabled modules, quotas |
| `CompanyLifecycleState` | Enum: `ACTIVE`, `SUSPENDED`, `DEACTIVATED` |
| `CompanyModule` | Enum: core and gatable module definitions with default enabled sets |
| `CoATemplate` | Chart-of-accounts template for tenant onboarding |
| `TenantAdminEmailChangeRequest` | Tracks pending admin email change requests with verification tokens |
| `TenantSupportWarning` | Support warning records issued against tenants |

## Company Context in the Request Pipeline

Company context is set by `CompanyContextFilter` (in `core/security/`, documented in [auth.md](auth.md)):

1. JWT `companyCode` claim is extracted by `JwtAuthenticationFilter`.
2. `CompanyContextFilter` validates the claim against any `X-Company-Code` header, rejects legacy `X-Company-Id` headers, resolves the company lifecycle state, enforces lifecycle restrictions, runs tenant runtime admission, and sets `CompanyContextHolder`.
3. Downstream services use `CompanyContextHolder.getCompanyCode()` or `CompanyContextService` to access tenant context.

### Super-Admin Platform Scope

Super-admin users authenticate with the platform scope code (default: `PLATFORM`, configurable via `auth.platform.code` system setting). They are restricted to:

- `/api/v1/superadmin/**` — control-plane operations.
- `/api/v1/auth/**` — auth endpoints.
- `/api/v1/companies` — company listing.
- `/api/v1/admin/settings` — global settings.

Super-admin users are **explicitly blocked** from tenant business endpoints (sales, inventory, factory, purchasing, HR, portal, dealer-portal, etc.) by `CompanyContextFilter`.

### Tenant Onboarding

The onboarding flow creates a fully operational tenant in one transaction:

1. Validate request: company code uniqueness, admin email format, CoA template selection.
2. Create company: `Company` entity with `ACTIVE` lifecycle state and default module set.
3. Seed chart of accounts from the selected `CoATemplate`.
4. Create admin user: `UserAccount` with `ROLE_ADMIN`, scoped to the new company.
5. Create default accounting period.
6. Return result: company code, admin public ID, admin email.

Onboarding is accessible only to `ROLE_SUPER_ADMIN` via `POST /api/v1/superadmin/tenants/onboard`.

## Cross-Module Boundaries

| Boundary | Direction | Description |
| --- | --- | --- |
| company → auth | dependency | Super-admin control plane calls `TokenBlacklistService` and `RefreshTokenService` for force-logout |
| company → auth | dependency | `TenantRuntimeRequestAdmissionService` is called by `AuthService` for login/refresh admission |
| company → auth | dependency | `TenantOnboardingService` creates `UserAccount` entities |
| company → accounting | dependency | `TenantOnboardingService` seeds chart of accounts and creates default period |
| company → core/security | dependency | `CompanyContextFilter` enforces lifecycle and runtime admission |
| company → core/config | dependency | `TenantRuntimeEnforcementService` persists policies via `SystemSettingsRepository` |

## Known Caveats

1. **Runtime counters are in-memory**: in-flight and rate-limit counters (`ConcurrentHashMap` + `AtomicInteger`/`AtomicLong`) do not survive restarts or share across instances. The policy itself (state, quotas) is persisted to `system_settings`.
2. **Policy cache TTL**: runtime policies are cached for 15 seconds (configurable via `erp.tenant.runtime.policy-cache-seconds`). Changes to quotas take up to 15 seconds to propagate, unless the cache is explicitly invalidated (which happens automatically when a super-admin updates limits through the control-plane API).
3. **Tenant deletion is blocked**: the `DELETE /api/v1/companies/{id}` endpoint exists but always throws `AccessDeniedException`. Companies cannot be deleted through the API.
4. **HR_PAYROLL defaults to disabled**: unlike other gatable modules, HR/Payroll must be explicitly enabled per tenant during or after onboarding.
5. **Tenant runtime hold/blocked states are orthogonal to lifecycle states**: a tenant can be `ACTIVE` in lifecycle but `HOLD` or `BLOCKED` in runtime. Both layers are checked independently by `CompanyContextFilter`.
6. **Graceful degradation during policy persistence outage**: if the `system_settings` table is unavailable during a cache refresh, the last-known in-memory policy is kept rather than blocking all tenant requests. This is intentional to maintain availability.

## Cross-References

- [docs/modules/auth.md](auth.md) — auth module (login, refresh, logout, MFA, token revocation, security filters)
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — canonical module inventory
- [docs/adrs/ADR-002-multi-tenant-auth-scoping.md](../adrs/ADR-002-multi-tenant-auth-scoping.md) — ADR for multi-tenant auth scoping
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — overall architecture reference
- [docs/SECURITY.md](../SECURITY.md) — security review policy
- [docs/RELIABILITY.md](../RELIABILITY.md) — reliability posture
- [docs/adrs/ADR-006-portal-and-host-boundary-separation.md](../adrs/ADR-006-portal-and-host-boundary-separation.md) — portal/host boundary ADR
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory including auth/identity and tenant/admin management flows
- [docs/flows/auth-identity.md](../flows/auth-identity.md) — canonical auth/identity flow (behavioral entrypoint)
- [docs/flows/tenant-admin-management.md](../flows/tenant-admin-management.md) — canonical tenant/admin management flow (behavioral entrypoint)
