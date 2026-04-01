# Platform Health, Readiness, and Runtime Gating

Last reviewed: 2026-03-30

This packet documents the operator-facing health and readiness surfaces, integration health endpoints, module-gating mechanics, runtime-admission gates, and the important caveats operators must understand about which checks to trust and where the guarantees are partial. It fulfills VAL-PLAT-008.

> **Scope note:** This packet covers health/readiness endpoints and runtime-gating surfaces from an operator's perspective. For the full runtime-gating architecture, dual-enforcement-service coupling, and settings risk analysis, see [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md). For the configuration switches that control these surfaces, see [config-feature-toggles.md](config-feature-toggles.md). For module-gating config and the `CompanyModule` enum, see [company.md](../modules/company.md).

---

## 1. Health and Readiness Endpoints

The platform exposes health information through **four distinct surfaces**, each with different scope, activation conditions, and trust characteristics. Operators must understand which surface answers which question.

### 1.1 Spring Actuator Health — `/actuator/health`

| Property | Value |
| --- | --- |
| **URL** | `http://localhost:9090/actuator/health` |
| **Auth** | Unauthenticated (`permitAll` in `SecurityConfig`) |
| **Purpose** | Infrastructure liveness and application-startup readiness |
| **Consumer** | Load balancers, container orchestrators, uptime monitors |

**What it checks:** Standard Spring Boot Actuator health contributors (database connectivity, disk space, binders). The platform does not add custom actuator health contributors by default.

**Key caveat — environment health indicators are NOT active by default:**

Two custom health indicators exist but are **toggle-protected** and **inactive unless explicitly enabled**:

| Indicator | Config toggle | What it checks |
| --- | --- | --- |
| `RequiredConfigHealthIndicator` | `erp.environment.validation.health-indicator.enabled=true` | JWT secret length (≥32), encryption key length (≥32), license key presence (if enforcement enabled), SMTP completeness (if mail enabled) |
| `ConfigurationHealthIndicator` | `erp.environment.validation.health-indicator.enabled=true` | Per-company config completeness: base currency, default accounts, tax accounts, raw-material account mapping, finished-good accounts, production metadata. **DB-intensive** — results are cached for 60 seconds. |

**Operator caveat:** A standard `/actuator/health` probe without `erp.environment.validation.health-indicator.enabled=true` will **not** report configuration problems. A 200 from actuator only means the JVM is up and the database is reachable, not that the application is correctly configured for production use. Operators who rely on actuator alone will miss missing JWT secrets, encryption keys, SMTP configuration, and per-tenant account setup.

**Actuator degradation is expected in some profiles:** If actuator reports `DOWN` but the application answers API requests on port 8081, the API surface may still be functional. Operators should probe a real API endpoint (e.g., `GET /api/v1/auth/me` expecting 401/403 for unauthenticated requests) before treating the application as unavailable. This is documented in the environment library and the validation harness.

### 1.2 Integration Health — `/api/integration/health`

| Property | Value |
| --- | --- |
| **URL** | `http://localhost:8081/api/integration/health` |
| **Auth** | `ROLE_ADMIN` |
| **Purpose** | Lightweight admin-facing integration liveness check |
| **Response shape** | `{ "status": "UP", "timestamp": "..." }` |

**What it checks:** Nothing beyond authentication and application reachability. Returns a static `UP` status with a timestamp.

**Key caveat:** This endpoint is a **liveness confirmation**, not a deep integration check. It proves the application is reachable and the caller holds a valid admin token, but it does not verify database, message broker, downstream services, or configuration correctness.

### 1.3 Orchestrator Health — `/api/v1/orchestrator/health/*`

| Property | Value |
| --- | --- |
| **Integrations URL** | `GET /api/v1/orchestrator/health/integrations` |
| **Events URL** | `GET /api/v1/orchestrator/health/events` |
| **Auth** | `ROLE_ADMIN` |
| **Company context** | Required (uses `CompanyContextHolder`) |
| **Purpose** | Per-tenant orchestrator integration and event-outbox health |

#### Integrations health

Delegates to `IntegrationCoordinator.health()`, which queries live service data:

| Field | Source |
| --- | --- |
| `orders` | `SalesService.listOrders()` count |
| `plans` | `FactoryService.listPlans()` count |
| `accounts` | `AccountingService.listAccounts()` count |
| `employees` | `HrService.listEmployees()` count (only if `HR_PAYROLL` module is enabled for the tenant) |

**Operator caveat:** This endpoint runs real service queries against the database. A slow or failing response may indicate database performance issues, not just application health. The `employees` key is absent when HR/Payroll is disabled — operators should not treat its absence as an error.

#### Events health

Delegates to `EventPublisherService.healthSnapshot()`, which returns outbox metrics:

| Field | Source |
| --- | --- |
| `outboxPendingCount` | `OutboxEvent` count with status `PENDING` |
| `outboxPublishingCount` | `OutboxEvent` count with status `PUBLISHING` |
| `outboxPublishedCount` | `OutboxEvent` count with status `PUBLISHED` |
| `outboxFailedCount` | `OutboxEvent` count with status `FAILED` |

**Operator caveat:** A growing `outboxPendingCount` or `outboxPublishingCount` indicates the outbox processor is not keeping up or may be stuck. The `outboxFailedCount` indicates events that failed publishing and may need manual intervention. The outbox processor polls every 30 seconds by default (`orchestrator.outbox.cron`).

**Both endpoints are per-tenant, not cross-tenant aggregates.** They reflect the data visible in the caller's company context.

### 1.4 Accounting Configuration Health — `/api/v1/accounting/configuration/health`

| Property | Value |
| --- | --- |
| **URL** | `GET /api/v1/accounting/configuration/health` |
| **Auth** | `ROLE_ADMIN` or `ROLE_ACCOUNTING` |
| **Purpose** | Per-company configuration completeness report |
| **Response shape** | `ConfigurationHealthReport` with `healthy` boolean and `issues` list |

**What it checks** (via `ConfigurationHealthService`):

| Check | Domain | What it validates |
| --- | --- | --- |
| Base currency | `BASE_CURRENCY` | Company has a configured base currency |
| Default accounts | `DEFAULT_ACCOUNTS` | Inventory, COGS, revenue, and tax default accounts are set |
| Tax accounts | `TAX_ACCOUNT` | GST input and output tax accounts are configured |
| Raw material accounts | `RAW_MATERIAL_ACCOUNT` | Each raw material has an inventory account |
| Raw material mapping | `RAW_MATERIAL_MAPPING` | Production catalog entries for raw materials have linked raw material records |
| Finished good accounts | `FINISHED_GOOD_ACCOUNT` | Each finished good has revenue and tax accounts configured |
| Production metadata | `PRODUCTION_METADATA` | Each production product has all required account metadata keys (`fgValuationAccountId`, `fgCogsAccountId`, `fgRevenueAccountId`, `fgDiscountAccountId`, `fgTaxAccountId`, `wipAccountId`, `semiFinishedAccountId`) |

**Operator caveat:** This endpoint runs DB-intensive queries across all companies and all products. It is the same service used by `ConfigurationHealthIndicator` (the actuator health indicator), but this API endpoint is always available (no toggle required) and returns structured detail. Operators should prefer this endpoint for configuration completeness checks and use the actuator indicator only for automated alerting pipelines.

**Issue example:**
```json
{
  "healthy": false,
  "issues": [
    {
      "companyCode": "ACME",
      "domain": "TAX_ACCOUNT",
      "reference": "GST_INPUT",
      "message": "GST input tax account is not configured"
    }
  ]
}
```

---

## 2. Module Gating

Module gating controls which API path families a tenant can access. It is enforced by `ModuleGatingInterceptor`, a Spring `HandlerInterceptor` that runs after authentication and company-context resolution.

### 2.1 How Module Gating Works

1. `ModuleGatingInterceptor.preHandle()` resolves the request path.
2. If the path starts with `/api/v1/`, the interceptor maps it to a `CompanyModule` using `resolveTargetModule()`.
3. If the module is `null` or `isCore()` returns `true` (i.e., the module is a core module like `AUTH`, `ACCOUNTING`, `SALES`, or `INVENTORY`), the request is allowed unconditionally.
4. If the module is gatable, the interceptor calls `ModuleGatingService.requireEnabledForCurrentCompany()`.
5. If the module is not in the tenant's enabled set, the request is rejected with `MODULE_DISABLED` (`ApplicationException` + `ErrorCode.MODULE_DISABLED`).

### 2.2 Module Classification

| Module | Gatable | Default Enabled | Path Prefixes |
| --- | --- | --- | --- |
| `AUTH` | No (core) | Always | `/api/v1/auth` |
| `ACCOUNTING` | No (core) | Always | `/api/v1/accounting`, `/api/v1/invoices`, `/api/v1/audit` |
| `SALES` | No (core) | Always | `/api/v1/sales`, `/api/v1/dealers`, `/api/v1/credit/override-requests`, `/api/v1/credit/limit-requests` |
| `INVENTORY` | No (core) | Always | `/api/v1/inventory`, `/api/v1/raw-materials`, `/api/v1/dispatch`, `/api/v1/finished-goods` |
| `MANUFACTURING` | Yes | **Yes** | `/api/v1/factory`, `/api/v1/production` |
| `PURCHASING` | Yes | **Yes** | `/api/v1/purchasing`, `/api/v1/suppliers` |
| `PORTAL` | Yes | **Yes** | `/api/v1/portal`, `/api/v1/dealer-portal` |
| `REPORTS_ADVANCED` | Yes | **Yes** | `/api/v1/reports` |
| `HR_PAYROLL` | Yes | **No** | `/api/v1/hr`, `/api/v1/payroll`, `/api/v1/accounting/payroll` |

### 2.3 Operator Caveats

1. **Core modules cannot be disabled.** There is no toggle to turn off `AUTH`, `ACCOUNTING`, `SALES`, or `INVENTORY`. If a tenant should not use one of these surfaces, the enforcement must be at the RBAC/role level, not module gating.

2. **`HR_PAYROLL` is disabled by default.** New tenants do not have HR/Payroll access until explicitly enabled. Operators must also ensure payroll expense and cash accounts are configured on the `Company` entity before enabling this module.

3. **Path-to-module mapping is prefix-based.** The interceptor uses `String.startsWith()` for path matching. New paths added under an existing prefix are automatically covered by the same module gate. If a new path family is introduced outside the current prefixes, it will not be gated by any module (returns `null` from `resolveTargetModule()`).

4. **Module gating runs after runtime admission but before controller logic.** A request that passes runtime admission may still be rejected by module gating. Operators investigating 403s should check both runtime state (HOLD/BLOCKED) and module gating status.

5. **Module changes take effect on the next request.** Module enable/disable is persisted on the `Company` entity and read on each request. There is no caching delay.

---

## 3. Runtime Admission

Runtime admission is the per-tenant request-gate that enforces operational state, rate limits, and concurrency quotas. It is the most granular gate in the platform — finer than lifecycle state and separate from module gating.

### 3.1 Three-Layer Architecture

Runtime admission is implemented across three layers that operators must understand together:

| Layer | Service | Scope | Where it runs |
| --- | --- | --- | --- |
| 1. CompanyContextFilter | `TenantRuntimeRequestAdmissionService` → `TenantRuntimeEnforcementService` | Every authenticated tenant-scoped request | Spring Security filter chain |
| 2. Portal interceptor | `TenantRuntimeRequestAdmissionService` → `TenantRuntimeEnforcementService` | `/api/v1/reports/**`, `/api/v1/portal/**`, `/api/v1/demo/**` | Spring MVC interceptor |
| 3. Core security layer | `TenantRuntimeAccessService` | Same paths as layer 2 | Reads `system_settings` directly |

**Layer 1 is the primary enforcement point.** Layer 2 is a fallback for paths that may bypass the filter chain. Layer 3 is an independent enforcement service with its own cache and counters.

For the full architecture, coupling analysis, and risk assessment, see [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) §2.

### 3.2 What Runtime Admission Checks

For each request to a gated tenant, the enforcement service checks (in order):

1. **Runtime state** — `HOLD` rejects mutating requests (423 Locked); `BLOCKED` rejects all requests (403 Forbidden).
2. **Rate limit** — `maxRequestsPerMinute` (default: 5000). Excess requests receive 429 Too Many Requests.
3. **Concurrency limit** — `maxConcurrentRequests` (default: 200). Excess concurrent requests receive 429.
4. **Active-user quota** (auth operations only) — `maxActiveUsers` (default: 500). Excess users during login/refresh receive 429.

### 3.3 Runtime State vs Lifecycle State

Operators must distinguish two independent state machines:

| State Machine | Values | Enforcement | Managed By |
| --- | --- | --- | --- |
| **Lifecycle state** | `ACTIVE`, `SUSPENDED`, `DEACTIVATED` | `CompanyContextFilter` | `TenantLifecycleService` via `/api/v1/superadmin/tenants/{id}/lifecycle` |
| **Runtime state** | `ACTIVE`, `HOLD`, `BLOCKED` | `TenantRuntimeEnforcementService` | `TenantRuntimeEnforcementService` via `/api/v1/superadmin/tenants/{id}/limits` |

A tenant can be `ACTIVE` in lifecycle but `HOLD` or `BLOCKED` in runtime. Both are checked independently. Lifecycle changes are structural (affecting data visibility). Runtime changes are operational (affecting request admission) and can be applied instantly without affecting lifecycle.

### 3.4 Default Quotas

| Quota | Default | Config Property |
| --- | --- | --- |
| Max concurrent requests | 200 | `erp.tenant.runtime.default-max-concurrent-requests` |
| Max requests per minute | 5,000 | `erp.tenant.runtime.default-max-requests-per-minute` |
| Max active users | 500 | `erp.tenant.runtime.default-max-active-users` |
| Policy cache TTL | 15 seconds | `erp.tenant.runtime.policy-cache-seconds` |

Per-tenant overrides are stored in `system_settings` with keys like `tenant.runtime.max-concurrent-requests.{companyId}`. When no override exists, the config-level defaults apply.

---

## 4. Operator-Facing Caveats

### 4.1 Actuator Health Does Not Prove Production Readiness

`/actuator/health` returning 200 does **not** mean:
- JWT secret is correctly configured
- Encryption key is present
- SMTP is configured
- Per-tenant accounts are set up
- Runtime enforcement counters are initialized

Operators who need production-readiness verification should:
1. Enable `erp.environment.validation.health-indicator.enabled=true` to activate config checks in actuator
2. Probe `GET /api/v1/accounting/configuration/health` for per-tenant account completeness
3. Verify login works with a known admin credential
4. Check orchestrator health endpoints for outbox backlog

### 4.2 In-Memory Counters Reset on Restart

Runtime enforcement counters (in-flight requests, rate-limit windows, security monitoring counters) are **purely in-memory** and do not survive application restarts:

| Impact | What happens |
| --- | --- |
| Rate limits reset | A tenant that was rate-limited before restart can immediately send a full burst |
| Concurrency counters reset | A tenant at its concurrent limit before restart can exceed the limit temporarily |
| Security monitoring resets | Brute-force lockouts and suspicious-activity scores are cleared |

The **policy** (state, quotas) is persisted and survives restarts. Only the **counters** are lost.

### 4.3 Dual Enforcement Services Create Temporary Inconsistency

`TenantRuntimeEnforcementService` and `TenantRuntimeAccessService` maintain independent caches and counters with the same TTL (15 seconds). A policy change made through the super-admin API takes up to 15 seconds to be visible to both services. During this window:

- One service may enforce `ACTIVE` while the other still sees `HOLD`
- In-flight request counts may differ between the two services
- Legacy per-code settings keys may cause different behavior if both legacy and company-ID keys exist

Operators should wait at least 15 seconds after a policy change before verifying its effect.

### 4.4 Health Endpoints Are Per-Tenant, Not Global

The orchestrator health endpoints (`/api/v1/orchestrator/health/integrations` and `/health/events`) return data scoped to the caller's company context. They do not provide a cross-tenant aggregate view. To verify platform-wide health, operators must:

- Use actuator health for infrastructure-level checks
- Use accounting configuration health for per-tenant config checks
- Use orchestrator health per tenant for integration and outbox health

### 4.5 Configuration Health Checks Are DB-Intensive

`ConfigurationHealthService.evaluate()` queries across all companies, all finished goods, all raw materials, and all production products. The actuator indicator caches results for 60 seconds. Operators should avoid polling this endpoint at high frequency, especially in environments with many tenants or many products.

The direct API endpoint (`/api/v1/accounting/configuration/health`) does not use caching and will execute the full evaluation on every call.

### 4.6 Environment Health Indicators Require Explicit Activation

The two environment validation health indicators (`RequiredConfigHealthIndicator` and `ConfigurationHealthIndicator`) are **inactive by default**. They only register as Actuator health contributors when:

```
erp.environment.validation.health-indicator.enabled=true
```

Without this flag, `/actuator/health` will not include `requiredConfig` or `configuration` indicators, even if the application has configuration problems.

### 4.7 `/api/integration/health` Is a Liveness Check, Not a Deep Health Check

The integration health endpoint returns a static `UP` status. It does not verify database connectivity, message broker health, or downstream service availability. Operators should not rely on this endpoint alone to determine system health.

### 4.8 Super-Admin Paths Bypass Some Gates

Super-admin control-plane endpoints (`/api/v1/superadmin/**`) have special handling:
- They bypass tenant lifecycle restrictions (necessary for managing tenant state)
- Runtime policy control requests from super-admins are treated as privileged during admission
- Module gating does not apply to super-admin paths (they are not under `/api/v1/{module}` prefixes)

Operators should not use super-admin credentials for routine tenant operations — the bypasses are intentional for administrative control but may mask per-tenant gates.

---

## 5. Health Endpoint Summary

| Endpoint | Port | Auth | Scope | What It Proves | Caveat |
| --- | --- | --- | --- | --- | --- |
| `/actuator/health` | 9090 | None | Infrastructure | JVM up, DB reachable | Does not check config, accounts, or runtime policy without explicit toggle |
| `/api/integration/health` | 8081 | `ROLE_ADMIN` | Application | App reachable, admin token valid | Static `UP` response, no deep checks |
| `/api/v1/orchestrator/health/integrations` | 8081 | `ROLE_ADMIN` | Per-tenant | Cross-module data reachability | DB-intensive; per-tenant only |
| `/api/v1/orchestrator/health/events` | 8081 | `ROLE_ADMIN` | Per-tenant | Outbox processing health | Per-tenant only; growing pending count signals processor lag |
| `/api/v1/accounting/configuration/health` | 8081 | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Per-tenant | Config completeness | DB-intensive; not cached at API level |

---

## 6. Gate Evaluation Order

For a typical authenticated tenant request, the gates evaluate in this order:

```
1. Security filter chain — JWT validation, authentication
2. CompanyContextFilter — Company context resolution, lifecycle state check (SUSPENDED/DEACTIVATED)
3. CompanyContextFilter — Runtime admission (HOLD/BLOCKED, rate limit, concurrency)
4. TenantRuntimeEnforcementInterceptor — Fallback runtime check for portal/reports/demo paths
5. ModuleGatingInterceptor — Module enabled/disabled check for gatable modules
6. Controller — RBAC/role check via @PreAuthorize
```

A request that fails at step 2 (lifecycle) will not reach step 3 (runtime) or step 5 (module gating). A request that passes step 3 but fails at step 5 receives `MODULE_DISABLED`, not a runtime rejection.

---

## Cross-References

| Document | Relationship |
| --- | --- |
| [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) | Full runtime-gating architecture, dual-enforcement coupling, settings risk |
| [config-feature-toggles.md](config-feature-toggles.md) | Configuration switches that control health indicators and runtime defaults |
| [company.md](../modules/company.md) | Company lifecycle, module gating config, super-admin control plane |
| [core-security-error.md](../modules/core-security-error.md) | Security filter chain, error contract |
| [auth.md](../modules/auth.md) | Auth module: login/refresh/MFA, active-user quota enforcement |
| [orchestrator.md](../modules/orchestrator.md) | Outbox lifecycle, event publishing, retry/dead-letter |
| [RELIABILITY.md](../RELIABILITY.md) | Idempotency, retry, dead-letter handling, concurrency patterns |
