# Core Platform Contracts: Audit-Surface Ownership, Runtime-Gating Split, and Settings Risk

Last reviewed: 2026-03-30

This packet documents the **audit-surface ownership model**, the **runtime-gating split** between company filters and admission services, and the **global-versus-tenant settings risk** that platform maintainers must understand. It is the second slice of the core platform contracts packet, extending the security filter chain and error contract documented in [core-security-error.md](core-security-error.md).

> **Scope note:** This slice covers audit ownership, runtime-gating architecture, and settings scoping. Shared-versus-module-local idempotency behavior is documented in [core-idempotency.md](core-idempotency.md). Together the three slices form one coherent canonical reference for core platform contracts (see the reconciled contract table in [core-idempotency.md §5](core-idempotency.md#5-reconciled-core-platform-contract)).

---

## Ownership Summary

| Area | Package | Role |
| --- | --- | --- |
| Platform audit | `core/audit/` | Security/auth/admin operational audit log via `AuditService` → `audit_logs` table |
| Enterprise audit trail | `core/audittrail/` | Tamper-evident business-action timeline via `EnterpriseAuditTrailService` → `audit_action_events` table |
| ML interaction events | `core/audittrail/` | Frontend/analytics interaction telemetry via `EnterpriseAuditTrailService` → `ml_interaction_events` table |
| Accounting event store | `modules/accounting/event/` | Immutable accounting event trail via `AccountingEventStore` → `accounting_events` table |
| Accounting audit read model | `modules/accounting/service/` | `AccountingAuditService` aggregates journals/events/doc links for digest/export queries (read-only, not a writer) |
| Exception-to-audit routing | `core/exception/` | `AuditExceptionRoutingService` routes settlement failures and malformed requests to platform audit |
| Journal posted audit listener | `core/audit/` | `JournalEntryPostedAuditListener` bridges accounting domain events to platform audit after commit |
| Company runtime enforcement | `modules/company/service/` | `TenantRuntimeEnforcementService` owns policy mutation, quota enforcement, and snapshot logic |
| Company runtime admission | `modules/company/service/` | `TenantRuntimeRequestAdmissionService` is the thin facade used by filters and auth flows |
| Core tenant runtime access | `core/security/` | `TenantRuntimeAccessService` provides a second enforcement layer used by the portal interceptor |
| Portal runtime interceptor | `modules/portal/service/` | `TenantRuntimeEnforcementInterceptor` applies runtime gating to portal/reports/demo paths |
| Global settings | `core/config/` | `SystemSettingsService` owns runtime-tunable settings persisted in `system_settings` |
| Accounting settings | `modules/accounting/service/` | `CompanyAccountingSettingsService` owns tenant-scoped payroll and tax account configuration |

---

## 1. Audit-Surface Ownership Model

The platform has three distinct audit surfaces that serve different audiences, carry different integrity guarantees, and have different failure modes. A single journal posting may produce records in all three surfaces: a platform audit marker (who did it), an enterprise audit trail event (signed proof with business context), and an accounting event record (financial details for period close).

This ownership split is an accepted architectural decision documented in [ADR-004: Layered Audit Surfaces](../adrs/ADR-004-layered-audit-surfaces.md).

### 1.1 Platform Audit (`AuditService`)

**Source:** [`core/audit/AuditService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/AuditService.java)

**Table:** `audit_logs`

**Owner:** `core/audit/` package

**Scope:** Authentication events (login, logout, MFA, password changes), authorization events (access granted/denied), data CRUD operations, security alerts, integration failures, administrative actions, and configuration changes.

**Write semantics:**

| Property | Behavior |
| --- | --- |
| Transaction | `@Async` + `REQUIRES_NEW` — writes in an independent transaction |
| Blocking | Non-blocking to the caller; failures are caught and logged |
| Company scoping | Via `CompanyContextHolder`, with auth-specific overrides for login/logout where company context may not yet be established |
| Actor resolution | From `SecurityContext`, with explicit overrides for auth events (`logAuthSuccess`/`logAuthFailure`) |
| Persistence | Database-backed (`audit_logs` table); survives restarts |
| Retry | No persistent retry; if the async write fails, the event is logged but lost |

**Key consumers:** Security reviewers, tenant admins viewing audit logs, `GlobalExceptionHandler` (settlement failure routing), `TenantRuntimeEnforcementService` (policy change and rejection auditing).

**Event types:** Defined in [`AuditEvent`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/AuditEvent.java) — covers authentication, MFA, authorization, data access, admin operations, system events, business operations, financial events, and compliance events.

### 1.2 Enterprise Audit Trail (`EnterpriseAuditTrailService`)

**Source:** [`core/audittrail/EnterpriseAuditTrailService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailService.java)

**Tables:** `audit_action_events` (business events), `ml_interaction_events` (ML/analytics events), `audit_action_event_retry` (persistent retry queue)

**Owner:** `core/audittrail/` package

**Scope:** Business-action timeline for regulatory and compliance purposes. Records financial and operational business events (journal postings, dispatch confirmations, payroll runs, order operations) with HMAC-signed actor identity.

**Write semantics:**

| Property | Behavior |
| --- | --- |
| Transaction | `@Async` + `REQUIRES_NEW` — writes in an independent transaction |
| Blocking | Non-blocking to the caller; failures enter a retry queue |
| Company scoping | Enforced per event via `CompanyContextService.requireCurrentCompany()` |
| Actor identity | HMAC-signed using `erp.security.audit.private-key`; supports anonymization for ML events |
| Persistence | Database-backed with a persistent retry table (`audit_action_event_retry`) and in-memory overflow queue |
| Retry | Up to `erp.audit.business.retry.max-attempts` (default: 4) with exponential backoff; persistent retries via scheduled job every 30 seconds |
| Queue limits | In-memory overflow: `erp.audit.business.retry.max-queue-size` (default: 500); events exceeding this are dropped |

**Key consumers:** Compliance officers, regulatory auditors, `TenantRuntimeAccessService` (runtime denial events), frontend via `EnterpriseAuditTrailController` (business event queries and ML interaction ingestion).

**Query capabilities:**
- Business events: filterable by date range, module, action, status, actor, and reference number
- ML events: filterable by date range, module, action, status, actor, and actor identifier

### 1.3 Accounting Event Store (`AccountingEventStore`)

**Source:** [`modules/accounting/event/AccountingEventStore.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/AccountingEventStore.java)

**Table:** `accounting_events`

**Owner:** `modules/accounting/` package

**Scope:** Immutable accounting event trail that records journal entries, settlements, corrections, and balance adjustments with full line-item detail and balance-before/balance-after tracking.

**Write semantics:**

| Property | Behavior |
| --- | --- |
| Transaction | **Synchronous** — within the same transaction as the accounting business operation |
| Blocking | Blocking; if the event cannot be persisted, the entire business transaction rolls back |
| Company scoping | Via the `Company` entity on the parent `JournalEntry` |
| Actor resolution | Via `SecurityActorResolver.resolveActorWithSystemProcessFallback()` |
| Persistence | Database-backed (`accounting_events` table); survives restarts |
| Retry | No separate retry; shares the fate of the enclosing business transaction |
| Sequence integrity | Per-aggregate sequence numbers with optimistic retry (up to 5 attempts) on contention |

**Key consumers:** Accounting reconciliation, period-close validation, `AccountingAuditService` (read model for digest/export), balance replay queries (`replayBalanceAsOf`, `replayBalanceAsOfDate`).

**Event types:** `JOURNAL_ENTRY_POSTED`, `ACCOUNT_DEBIT_POSTED`, `ACCOUNT_CREDIT_POSTED`, `JOURNAL_ENTRY_REVERSED`, `BALANCE_CORRECTION`.

**Spring event bridge:** After persisting events, `AccountingEventStore` publishes a `JournalEntryPostedEvent` via Spring's `ApplicationEventPublisher`. This event is consumed by `JournalEntryPostedAuditListener` to write a platform audit marker after the transaction commits.

### 1.4 Ownership Boundaries at a Glance

| Surface | Writer Service | Table | Write Timing | Failure Mode | Primary Consumer |
| --- | --- | --- | --- | --- | --- |
| Platform audit | `AuditService` | `audit_logs` | Async, `REQUIRES_NEW` | **Fail-silent** — event lost on write failure | Security reviewers, tenant admins |
| Enterprise audit trail | `EnterpriseAuditTrailService` | `audit_action_events` | Async, `REQUIRES_NEW` + persistent retry | **Best-effort durable** — retry up to 4 attempts, then drop | Compliance, regulatory auditors |
| Accounting event store | `AccountingEventStore` | `accounting_events` | Synchronous, same transaction | **Fail-closed** — rolls back business transaction | Accounting reconciliation, period close |

### 1.5 De-dup Contract

Accounting journal/reversal/settlement summary events are captured by `AccountingEventStore` as the structured source of truth. Legacy summary success writes for these events in `AuditService` are fully decommissioned (not toggle-controlled). No profile may re-enable legacy summary success writes for `JOURNAL_ENTRY_POSTED`, `JOURNAL_ENTRY_REVERSED`, or `SETTLEMENT_RECORDED`.

`AuditService` remains active for:
- Failure and security/admin signal paths
- `JournalEntryPostedAuditListener` markers (written after commit, not inside the business transaction)
- Exception-routing events via `AuditExceptionRoutingService`

The de-dup policy is maintained in [`docs/AUDIT_TRAIL_OWNERSHIP.md`](../AUDIT_TRAIL_OWNERSHIP.md).

### 1.6 Cross-Surface Event Flow

A single journal entry posting triggers the following event flow:

```
AccountingCoreEngine posts journal entry
  │
  ├─ AccountingEventStore.recordJournalEntryPosted()      [synchronous, same transaction]
  │     Persists JOURNAL_ENTRY_POSTED + per-line DEBIT/CREDIT events
  │     Publishes JournalEntryPostedEvent via Spring ApplicationEventPublisher
  │
  ├─ Transaction commits
  │
  ├─ JournalEntryPostedAuditListener.onJournalEntryPosted() [AFTER_COMMIT, async]
  │     Writes JOURNAL_ENTRY_POSTED marker to AuditService (platform audit)
  │
  └─ EnterpriseAuditTrailService.recordBusinessEvent()     [async, REQUIRES_NEW]
        Records signed business-action event (if called by the posting service)
```

---

## 2. Runtime-Gating Split

Tenant runtime gating is implemented across **three layers** that must be understood together. The split exists because different enforcement points own different scopes and must not be collapsed into a single gate without careful consideration.

### 2.1 Layer 1: CompanyContextFilter (Request Pipeline)

**Source:** [`core/security/CompanyContextFilter.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java)

**Scope:** Every authenticated, tenant-scoped request that passes through the Spring Security filter chain.

**Responsibility:** Resolve company context, enforce tenant lifecycle restrictions, and delegate to `TenantRuntimeRequestAdmissionService` for runtime admission.

**What it checks:**
1. JWT `companyCode` claim vs `X-Company-Code` header consistency
2. Tenant lifecycle state (`ACTIVE` / `SUSPENDED` / `DEACTIVATED`)
3. Runtime admission via `TenantRuntimeRequestAdmissionService.beginRequest()`
4. Super-admin control-plane vs tenant business path restrictions

**Behavior:** Fail-closed — any ambiguity in company context or runtime state is rejected with 403.

**Documented in:** [core-security-error.md](core-security-error.md) section 1.4 and [company.md](company.md) section "Company Context in the Request Pipeline".

### 2.2 Layer 2: TenantRuntimeEnforcementService (Policy Owner)

**Source:** [`modules/company/service/TenantRuntimeEnforcementService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java)

**Scope:** Canonical owner of per-tenant runtime policy state, quota counters, and admission decisions.

**Responsibility:** Maintain runtime state (`ACTIVE` / `HOLD` / `BLOCKED`), enforce quotas, track in-flight requests, and provide audit-chain IDs for policy-change traceability.

**What it checks:**
1. Runtime state: `HOLD` rejects mutating requests (423 Locked); `BLOCKED` rejects all requests (403)
2. Per-minute rate limit: `maxRequestsPerMinute` (default: 5000)
3. Concurrent request limit: `maxConcurrentRequests` (default: 200)
4. Auth-operation enforcement: active-user quota (`maxActiveUsers`, default: 500) during login/refresh

**Access pattern:** Called by `TenantRuntimeRequestAdmissionService` (the facade), which is called by `CompanyContextFilter` and `AuthService`. The enforcement service is not called directly by filters.

**Policy persistence:** Runtime policies are persisted to `system_settings` with per-company keys:
- `tenant.runtime.hold-state.{companyId}`
- `tenant.runtime.hold-reason.{companyId}`
- `tenant.runtime.max-concurrent-requests.{companyId}`
- `tenant.runtime.max-requests-per-minute.{companyId}`
- `tenant.runtime.max-active-users.{companyId}`
- `tenant.runtime.policy-reference.{companyId}`
- `tenant.runtime.policy-updated-at.{companyId}`

**Cache behavior:** Policies are cached in-memory with a configurable TTL (default: 15 seconds via `erp.tenant.runtime.policy-cache-seconds`). On cache expiry, the persisted policy is loaded from the database. If the database is unavailable during a cache refresh, the last-known in-memory policy is kept — this is intentional to maintain request admission availability during transient persistence outages.

**Counters:** In-memory only (`ConcurrentHashMap` + `AtomicInteger`/`AtomicLong`). Do not survive restarts or share across instances. The policy itself (state, quotas) is persisted.

### 2.3 Layer 3: TenantRuntimeAccessService (Core Security Layer)

**Source:** [`core/security/TenantRuntimeAccessService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/TenantRuntimeAccessService.java)

**Scope:** A second enforcement layer in `core/security/` that reads runtime policies directly from `system_settings` and maintains its own in-memory counters and cache.

**Responsibility:** Provide runtime enforcement for paths that may not go through the `CompanyContextFilter` admission path (portal, reports, demo paths via the interceptor).

**What it checks:**
1. Runtime state: `BLOCKED` (all requests) and `HOLD` (mutating requests)
2. Per-minute rate limit
3. Concurrent request limit

**Key difference from Layer 2:** This service has its own policy cache, its own counters, and reads settings directly from `SystemSettingsRepository`. It also writes both platform audit entries (via `AuditService`) and enterprise audit trail entries (via `EnterpriseAuditTrailService`) for denials, whereas `TenantRuntimeEnforcementService` writes only platform audit entries.

**Legacy settings keys:** This layer also supports legacy per-code settings keys (`tenant.runtime.{companyCode}.state`, `tenant.runtime.{companyCode}.quota.max-concurrent`, etc.) as fallbacks when company-ID-scoped keys are not present. The company-ID-scoped keys take precedence.

### 2.4 Layer 3.5: TenantRuntimeEnforcementInterceptor (Portal/Reports/Demo)

**Source:** [`modules/portal/service/TenantRuntimeEnforcementInterceptor.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementInterceptor.java)

**Scope:** A Spring `HandlerInterceptor` that applies runtime gating to portal, reports, and demo paths.

**Enforced paths:**
- `/api/v1/reports/**`
- `/api/v1/portal/**`
- `/api/v1/demo/**`

**Behavior:** Skips if `TenantRuntimeRequestAttributes.CANONICAL_ADMISSION_APPLIED` is already set (meaning `CompanyContextFilter` already handled admission). Otherwise, calls `TenantRuntimeRequestAdmissionService` as a fallback enforcement layer.

### 2.5 Admission Flow Diagram

```
Incoming Request
  │
  ├─ CompanyContextFilter (Layer 1)
  │     Resolves company, checks lifecycle, calls TenantRuntimeRequestAdmissionService
  │     ├─ TenantRuntimeRequestAdmissionService → TenantRuntimeEnforcementService (Layer 2)
  │     │     Checks runtime state, rate limit, concurrency
  │     │     Returns: admitted / rejected / not-tracked
  │     └─ Sets CompanyContextHolder, proceeds or returns 403
  │
  ├─ TenantRuntimeEnforcementInterceptor (Layer 3.5, portal/reports/demo only)
  │     If CANONICAL_ADMISSION_APPLIED is not set:
  │     Calls TenantRuntimeRequestAdmissionService as fallback
  │
  └─ Controller / module code
```

### 2.6 Runtime State vs Lifecycle State

A tenant has **two independent state machines** that both affect request admission:

| State Machine | Owner | Values | Enforcement Point |
| --- | --- | --- | --- |
| Lifecycle state | `TenantLifecycleService` | `ACTIVE`, `SUSPENDED`, `DEACTIVATED` | `CompanyContextFilter` |
| Runtime state | `TenantRuntimeEnforcementService` | `ACTIVE`, `HOLD`, `BLOCKED` | `CompanyContextFilter` + interceptor |

A tenant can be `ACTIVE` in lifecycle but `HOLD` or `BLOCKED` in runtime. Both layers are checked independently. The runtime state is an operational control that can be changed instantly by super-admins without affecting the lifecycle state.

### 2.7 Coupling Between Admission Services

The runtime-gating architecture has intentional coupling that maintainers must understand:

1. **`CompanyContextFilter` depends on `TenantRuntimeRequestAdmissionService`** — not directly on `TenantRuntimeEnforcementService`. This indirection prevents the filter chain from binding to the policy service directly.

2. **`TenantRuntimeRequestAdmissionService` is a thin facade** — it delegates entirely to `TenantRuntimeEnforcementService`. All policy logic lives in the enforcement service.

3. **`TenantRuntimeAccessService` is independent** — it reads policies directly from `system_settings`, maintains its own cache and counters, and is used by the portal interceptor as a separate enforcement path.

4. **Both enforcement services share the same `system_settings` persistence** — policy changes written by `TenantRuntimeEnforcementService` (via super-admin API) will eventually be visible to `TenantRuntimeAccessService` after cache TTL expiry (up to 15 seconds).

5. **`AuthService` calls `TenantRuntimeRequestAdmissionService.enforceAuthOperationAllowed()`** — during login and refresh, the auth service checks tenant runtime state and active-user quota before issuing tokens.

---

## 3. Global-Versus-Tenant Settings Risk

Settings in the platform are stored in the `system_settings` table (key-value pairs) and are consumed by multiple services. The critical risk is that **global and tenant-scoped settings share the same table and the same repository**, and the distinction is entirely key-based. There is no schema-level isolation between global and per-tenant settings.

### 3.1 Settings Scope Classification

#### Global Settings (no tenant qualifier)

These settings affect the entire platform and are managed through `SystemSettingsService`:

| Setting Key | Default | Purpose |
| --- | --- | --- |
| `cors.allowed-origins` | `http://localhost:3002` | CORS origin allowlist |
| `auto-approval.enabled` | `true` | Whether sales orders are auto-approved |
| `period-lock.enforced` | `true` | Whether period locking is enforced for accounting |
| `export.require-approval` | `false` | Whether data exports require admin approval |
| `mail.enabled` | (config) | Whether email sending is enabled |
| `mail.from` | (config) | Sender address |
| `mail.base-url` | (config) | Base URL for email links |
| `mail.send-credentials` | (config) | Whether to send credential emails |
| `mail.send-password-reset` | (config) | Whether to send password-reset emails |
| `auth.platform.code` | `PLATFORM` | Super-admin platform scope code |

These are managed via `AdminSettingsController` (`/api/v1/admin/settings`) and require `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`.

#### Tenant-Scoped Settings (company-ID-qualified keys)

These settings are specific to a tenant and are managed through the super-admin control plane:

| Setting Key Pattern | Purpose | Managed By |
| --- | --- | --- |
| `tenant.runtime.hold-state.{companyId}` | Runtime state (ACTIVE/HOLD/BLOCKED) | `TenantRuntimeEnforcementService` |
| `tenant.runtime.hold-reason.{companyId}` | Reason for hold/blocked state | `TenantRuntimeEnforcementService` |
| `tenant.runtime.max-concurrent-requests.{companyId}` | Concurrent request quota | `TenantRuntimeEnforcementService` |
| `tenant.runtime.max-requests-per-minute.{companyId}` | Rate limit quota | `TenantRuntimeEnforcementService` |
| `tenant.runtime.max-active-users.{companyId}` | Active user quota | `TenantRuntimeEnforcementService` |
| `tenant.runtime.policy-reference.{companyId}` | Audit chain ID | `TenantRuntimeEnforcementService` |
| `tenant.runtime.policy-updated-at.{companyId}` | Last policy update timestamp | `TenantRuntimeEnforcementService` |

#### Tenant-Scoped Accounting Settings

These are stored on the `Company` entity directly (not in `system_settings`):

| Setting | Purpose | Managed By |
| --- | --- | --- |
| `payrollExpenseAccount` | Default payroll expense account | `CompanyAccountingSettingsService` |
| `payrollCashAccount` | Default payroll cash/bank account | `CompanyAccountingSettingsService` |
| `gstInputTaxAccountId` | GST input tax account | `CompanyAccountingSettingsService` |
| `gstOutputTaxAccountId` | GST output tax account | `CompanyAccountingSettingsService` |
| `gstPayableAccountId` | GST payable account (optional) | `CompanyAccountingSettingsService` |

#### Legacy Per-Code Tenant Settings

`TenantRuntimeAccessService` also reads legacy per-company-code keys as fallbacks:
- `tenant.runtime.{companyCode}.state`
- `tenant.runtime.{companyCode}.reason-code`
- `tenant.runtime.{companyCode}.quota.max-concurrent`
- `tenant.runtime.{companyCode}.quota.max-requests-per-minute`

These legacy keys exist for backward compatibility and are only used when company-ID-scoped keys are absent. Company-ID-scoped keys always take precedence.

### 3.2 Risk: Shared Table, No Schema Isolation

All global and tenant-scoped settings share the `system_settings` table. The distinction between global and tenant-scoped settings is purely **key-based** — there is no column, constraint, or schema-level enforcement that prevents:

1. A tenant-scoped key from being read or overwritten by global settings management code
2. A global key from being accidentally qualified with a company ID
3. Cross-tenant setting leakage if a key's company-ID qualifier is incorrect

**Mitigating factors:**
- `SystemSettingsService` manages global settings through a typed API (`snapshot()`, `update()`) that only touches known global keys.
- `TenantRuntimeEnforcementService` manages tenant-scoped runtime settings through its own typed API that constructs keys using verified company IDs.
- `CompanyAccountingSettingsService` stores accounting settings on the `Company` entity rather than in `system_settings`, avoiding key-confusion risk for financial configuration.
- The super-admin control-plane API validates company existence before writing tenant-scoped settings.

**Residual risk:**
- Any code that reads from `SystemSettingsRepository` directly (without going through the typed services) must correctly construct or interpret setting keys. Incorrect key construction could read a global setting as tenant-scoped or vice versa.
- `TenantRuntimeAccessService` reads settings directly from `SystemSettingsRepository` by constructing keys manually. If the company-ID-to-company-code resolution is incorrect, it could read the wrong tenant's policy.

### 3.3 Risk: Dual Enforcement Services

The existence of two independent runtime enforcement services (`TenantRuntimeEnforcementService` and `TenantRuntimeAccessService`) creates a temporary consistency gap:

1. **Cache desynchronization:** Both services maintain independent in-memory caches with the same TTL (15 seconds). A policy change written by `TenantRuntimeEnforcementService` takes up to 15 seconds to be visible to `TenantRuntimeAccessService`, and vice versa.
2. **Counter divergence:** In-flight request counters are independent and in-memory. During normal operation, this means the same tenant may have different concurrent-request counts in the two services.
3. **Legacy key fallback:** `TenantRuntimeAccessService` reads legacy per-code keys as fallbacks. If someone writes settings using company-ID keys but the legacy per-code keys still exist with different values, the two services could enforce different policies for the same tenant.

**Mitigating factors:**
- The portal interceptor (`TenantRuntimeEnforcementInterceptor`) checks `CANONICAL_ADMISSION_APPLIED` and skips if `CompanyContextFilter` already handled admission, reducing the chance of dual enforcement on the same request.
- Policy mutations go through `TenantRuntimeEnforcementService` (via the super-admin API), which writes company-ID-scoped keys exclusively.

### 3.4 Risk: In-Memory Counters Do Not Survive Restart

Runtime enforcement counters (in-flight requests, rate-limit windows) are purely in-memory:

- After a restart, all concurrent-request and rate-limit counters reset to zero.
- A tenant that was at its concurrent-request limit before a restart will have its limit reset, potentially allowing a burst of requests above the intended quota.
- Rate-limit windows reset, so a tenant that was rate-limited in the current minute will have its counter reset.

This is an intentional trade-off for availability: persisting per-request counters would add latency and complexity that the current architecture avoids. The policy itself (state, quotas) is persisted and survives restarts.

---

## 4. Audit Routing for Exception Paths

The platform routes specific exception types to audit surfaces to ensure important failures are captured even when the normal business-event path is not triggered.

### 4.1 AuditExceptionRoutingService

**Source:** [`core/exception/AuditExceptionRoutingService.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/AuditExceptionRoutingService.java)

| Exception Path | Audit Surface | Event Type | Current Scope |
| --- | --- | --- | --- |
| `ApplicationException` on `/api/v1/accounting/settlements/**` | Platform audit (`AuditService`) | `INTEGRATION_FAILURE` | Settlement failures only |
| Malformed request body (`HttpMessageNotReadableException`) | Platform audit (`AuditService`) | `INTEGRATION_FAILURE` | All malformed request bodies |

Non-settlement `ApplicationException` instances and other exceptions are **not currently routed** to audit by the global handler.

### 4.2 JournalEntryPostedAuditListener

**Source:** [`core/audit/JournalEntryPostedAuditListener.java`](../../erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/JournalEntryPostedAuditListener.java)

Listens for `AccountingEventStore.JournalEntryPostedEvent` (Spring application event) after transaction commit and writes a `JOURNAL_ENTRY_POSTED` marker to platform audit. This bridges the accounting event store (synchronous, transactional) to the platform audit surface (async, best-effort).

**Failure mode:** If the audit write fails, the failure is logged but does not propagate. The accounting event store record is already committed and is not affected.

---

## 5. Current Limitations and Known Gaps

1. **Dual runtime enforcement services:** `TenantRuntimeEnforcementService` and `TenantRuntimeAccessService` maintain independent caches and counters, creating a temporary consistency gap for policy changes and in-flight tracking. See section 3.3 for details.

2. **Audit routing is narrow:** Only settlement failures and malformed requests are automatically routed from the global exception handler to audit. Other important failures (concurrency conflicts, credit-limit breaches, accounting posting failures) are not automatically captured.

3. **Platform audit has no persistent retry:** If `AuditService` fails to write (e.g., database contention under load), the audit event is lost. The enterprise audit trail has persistent retry, but the platform audit does not.

4. **Profile audit gap:** User profile changes (via `/api/v1/auth/profile` endpoints) do not emit audit events. This is a compliance risk and is classified as a **Bug to Fix Now** in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). See [`RECOMMENDATIONS.md`](../RECOMMENDATIONS.md) — Auth and Identity section for the full classification and rationale.

5. **Settings table has no schema-level scope isolation:** Global and tenant-scoped settings share the `system_settings` table with only key naming conventions to distinguish them. See section 3.2 for the risk analysis.

6. **Legacy per-code settings keys still read:** `TenantRuntimeAccessService` reads legacy per-company-code settings keys as fallbacks. These keys coexist with the newer company-ID-scoped keys and could cause policy confusion if both are present with different values.

7. **In-memory counters reset on restart:** Concurrent-request and rate-limit counters are not persisted. A restart resets all quotas to zero. See section 3.4.

8. **Accounting-event ownership for idempotency is documented in the idempotency slice:** How `AccountingEventStore` participates in idempotency checks and the accounting idempotency delegation pattern are covered in [core-idempotency.md](core-idempotency.md) §2.7.

---

## 6. Cross-References

| Document | Relationship |
| --- | --- |
| [core-security-error.md](core-security-error.md) | First slice: security filters and exception/error contract |
| [core-idempotency.md](core-idempotency.md) | Third/integrating slice: shared vs module-local idempotency, reconciled contract table |
| [company.md](company.md) | Company module: tenant lifecycle, runtime admission, module gating |
| [auth.md](auth.md) | Auth module: login/refresh/logout/MFA/password corridor |
| [admin-portal-rbac.md](admin-portal-rbac.md) | Admin/portal/RBAC: role-action boundaries, admin settings |
| [docs/adrs/ADR-004-layered-audit-surfaces.md](../adrs/ADR-004-layered-audit-surfaces.md) | ADR: why three audit surfaces exist and what trade-offs they carry |
| [docs/AUDIT_TRAIL_OWNERSHIP.md](../AUDIT_TRAIL_OWNERSHIP.md) | De-dup contract and change-control rule for audit trail ownership |
| [docs/RELIABILITY.md](../RELIABILITY.md) | Reliability: retry, dead-letter, idempotency patterns |
| [docs/SECURITY.md](../SECURITY.md) | Security review policy |
| [docs/ARCHITECTURE.md](../ARCHITECTURE.md) | Architecture overview |
