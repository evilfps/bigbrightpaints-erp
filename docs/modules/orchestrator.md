# Orchestrator and Event Surfaces

Last reviewed: 2026-04-02

This packet documents the orchestrator module, event/listener bridges, schedulers, retry/dead-letter behavior, feature flags, and the distinction between background coordination and canonical module ownership. It is grounded in the actual implementation rather than aspirational redesign.

A reader can understand the outbox path, listener model, and scheduler surfaces without reading source code.

Deprecated, dead, or incomplete orchestration seams are disclosed explicitly rather than flattened into generic background-work prose.

---

## Module Location and Scope

**Package:** `com.bigbrightpaints.erp.orchestrator`

**Database tables:** `orchestrator_outbox`, `orchestrator_commands`, `orchestrator_audit`, `scheduled_jobs`, `order_auto_approval_state`

**Owning principle:** The orchestrator coordinates but does not own business logic. Module services own their domain logic; the orchestrator dispatches commands, publishes events, tracks outcomes, and aggregates dashboards. It is a background coordination layer, not a business module.

### Controllers

| Controller | Host | Purpose |
| --- | --- | --- |
| `OrchestratorController` | `/api/v1/orchestrator` | Order approval, fulfillment updates, trace lookup, health endpoints |
| `DashboardController` | `/api/v1/orchestrator/dashboard` | Admin, factory, and finance dashboard aggregation |

### Key Services

| Service | Purpose |
| --- | --- |
| `CommandDispatcher` | Entry point for surviving orchestrated commands: order approval, auto-approval, fulfillment updates, and payroll-related redirects/guards |
| `EventPublisherService` | Outbox-based event persistence and scheduled publishing to RabbitMQ |
| `IntegrationCoordinator` | Cross-module integration façade: inventory reservation, production scheduling, payroll coordination, dashboard aggregation |
| `OrchestratorIdempotencyService` | Idempotency key reservation and payload-hash-based replay detection for orchestrator commands |
| `OrderAutoApprovalListener` | Spring `@TransactionalEventListener` that auto-approves sales orders after commit when the system setting is enabled |
| `TraceService` | Audit-trail persistence and lookup for orchestrator operations |
| `DashboardAggregationService` | Thin wrapper around `IntegrationCoordinator` dashboard methods |
| `SchedulerService` | Dynamic cron-based job registration, pause, and execution tracking |
| `CorrelationIdentifierSanitizer` | Input sanitization for trace IDs, request IDs, and idempotency keys |
| `PolicyEnforcer` | Access-control checks for orchestrator command permissions |
| `ExternalSyncService` | Retryable external sync stubs (costing snapshots, accounting data export) |

### Key Entities

| Entity | Table | Purpose |
| --- | --- | --- |
| `OutboxEvent` | `orchestrator_outbox` | Persisted domain events awaiting or completed publishing |
| `OrchestratorCommand` | `orchestrator_commands` | Idempotency tracking for orchestrator commands with status, request hash, trace ID |
| `AuditRecord` | `orchestrator_audit` | Orchestrator-scoped audit trail keyed by trace ID |
| `ScheduledJobDefinition` | `scheduled_jobs` | Registered scheduled job definitions with cron, last-run, and active status |
| `OrderAutoApprovalState` | `order_auto_approval_state` | State machine for multi-step order auto-approval (inventory reserve, status update, completion) |

### Key DTOs

| DTO | Purpose |
| --- | --- |
| `DomainEvent` | Orchestrator-internal event record persisted to the outbox |
| `ApproveOrderRequest` | Order approval command payload |
| `OrderFulfillmentRequest` | Fulfillment status update payload |
| `PayrollRunRequest` | Payroll run command payload |

---

## Outbox Path (Canonical Event Publishing)

The outbox is the primary reliability mechanism for the orchestrator. A reader can understand the full path without reading source code.

`EventPublisherService` owns the outbox lifecycle.

See [ADR-003](../adrs/ADR-003-outbox-pattern-for-cross-module-events.md) for the architectural decision behind this choice.

See [RELIABILITY.md](../RELIABILITY.md) for retry/dead-letter handling and known safety gaps.

### Lifecycle

| Status | Meaning |
| --- | --- |
| `PENDING` | Event is written to outbox within the business transaction, awaiting pickup |
| `PUBLISHING` | Event claimed by the scheduled job for publishing; no other scheduler run may claim the same event while in this state |
| `PUBLISHED` | Event successfully delivered to RabbitMQ and finalized |
| `FAILED` | Event dead-lettered after exhausting retries |

### Publishing Flow

1. **Enqueue.** When a service emits a `DomainEvent`, `EventPublisherService.enqueue()` persists it as an `OutboxEvent` (status `PENDING`) within the same database transaction as the business operation.
2. **Scheduled polling.** `OutboxPublisherJob` runs on a configurable cron schedule (default: every 30 seconds) and calls `EventPublisherService.publishPendingEvents()`. The job is protected by ShedLock for distributed locking (`outbox-publish-pending` lock at most 5 minutes by default).
3. **Claim with fencing.** The publisher claims up to 10 pending events per run using `findByIdForUpdate` (pessimistic write lock) and optimistic locking (`version` field). It transitions the event to `PUBLISHING` with a configurable time-based lease (default: 120 seconds). The lock-at-most-for duration (default: 5 minutes) must exceed the publish-lease duration.
4. **Publish to RabbitMQ.** On successful claim, the publisher sends the event payload to the `bbp.orchestrator.events` exchange with the event type as the routing key.
5. **Finalize.** On successful broker acceptance, the publisher transitions the event to `PUBLISHED` in a new transaction.
6. **Handle failures.** Failures are categorized as deterministic-retryable or ambiguous (see the Retry and Dead-Letter Behavior section below).

### Key Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `orchestrator.outbox.cron` | `0/30 * * * * *` | Outbox publisher cron schedule |
| `orchestrator.outbox.publish-lease-seconds` | `120` | How long a PUBLISHING claim lasts before stale-lease reclaim |
| `orchestrator.outbox.ambiguous-recheck-seconds` | `300` | How long ambiguous/stale events are held before re-check |
| `orchestrator.outbox.lock-at-most-for` | `PT5M` | ShedLock maximum lock duration |
| `spring.task.scheduling.enabled` | `true` | Master toggle for all scheduler jobs |

---

## Retry and Dead-Letter Behavior

### Deterministic Retryable Failures

The following failure types are treated as deterministic and automatically retried with exponential backoff:

- `AmqpConnectException` — broker unavailable
- `AmqpAuthenticationException` — broker auth failure
- `MessageConversionException` — payload serialization failure

**Backoff:** `30s × 2^retryCount`, capped at exponent 10. **Max attempts:** 5.

After 5 failures, the event is dead-lettered (status `FAILED`, `deadLetter = true`).

### Ambiguous (Fail-Closed) States

If the broker publish outcome is uncertain (for example: timeout after broker acceptance, or failure during finalization), the event is held in `PUBLISHING` with one of these error prefixes:

- `AMBIGUOUS_PUBLISH:` — broker send threw a non-deterministic exception; the event may or may not have been delivered
- `FINALIZE_FAILURE:` — broker publish succeeded but the status-update transaction failed
- `STALE_LEASE_UNCERTAIN:` — lease expired during publishing, or stale-lease reclaim detected lingering ambiguity

These events are **not automatically retried**. They are held for a configurable recheck delay (default: 300 seconds) as a fail-closed reconciliation marker to prevent duplicate side effects. Operators must manually inspect and resolve these events.

### Dead-Lettered Events

Events that exhaust all retry attempts are flagged as dead-lettered (`deadLetter = true`, `status = FAILED`). They are preserved for investigation rather than silently dropped.

### Health and Metrics

The outbox exposes counts through both a health snapshot endpoint (`GET /api/v1/orchestrator/health/events`) and Micrometer gauges:

| Metric | Meaning |
| --- | --- |
| `outbox.events.pending` | Events awaiting publish |
| `outbox.events.retrying` | Events that have been retried at least once |
| `outbox.events.publishing` | Events currently in the publishing state |
| `outbox.events.stale_publishing` | Events stuck in publishing beyond their lease |
| `outbox.events.ambiguous_publishing` | Events held for manual reconciliation |
| `outbox.events.deadletters` | Events dead-lettered after exhausting retries |

---

## Internal Spring Event Bridges

The orchestrator participates in two kinds of event bridges: internal Spring events published by modules, and outbox events published by the orchestrator itself. Both affect module behavior and represent important but sometimes hidden coupling.

### Event Bridge Map

| Source Event | Listener | Phase | Target | Config Guard |
| --- | --- | --- | --- | --- |
| `InventoryMovementEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` (`REQUIRES_NEW` tx) | Accounting journal entries | `erp.inventory.accounting.events.enabled` (default: `true`) |
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` (`REQUIRES_NEW` tx) | Accounting journal entries | `erp.inventory.accounting.events.enabled` (default: `true`) |
| `PackagingSlipEvent` | `FactorySlipEventListener` | `@EventListener` (synchronous) | Log only | None |
| `SalesOrderCreatedEvent` | `OrderAutoApprovalListener` | `AFTER_COMMIT` | Orchestrator auto-approval | `SystemSettingsService.isAutoApprovalEnabled()` |
| `JournalEntryPostedEvent` | `JournalEntryPostedAuditListener` | `AFTER_COMMIT` (`fallbackExecution = true`) | Core audit marker | None |

### Important Notes on the Inventory–Accounting Bridge

The `InventoryAccountingEventListener` is conditional on `erp.inventory.accounting.events.enabled`. When this property is `false`, inventory movements and valuation changes will **not** automatically create accounting entries. The event bridge is disabled silently. If the toggle is misconfigured, the system silently skips expected accounting side effects rather than failing closed. This is a significant hidden coupling risk.

The listener skips canonical-workflow movements (`GOODS_RECEIPT`, `SALES_ORDER`, `PACKAGING_SLIP` related entity types) to avoid duplicate posting when the canonical module path already handles the GL posting.

### Important Notes on the Order Auto-Approval Bridge

The `OrderAutoApprovalListener` depends on `SystemSettingsService.isAutoApprovalEnabled()`. When auto-approval is disabled, the event is logged but skipped; otherwise, the listener calls `CommandDispatcher.autoApproveOrder()` which triggers the inventory reservation and orchestrator outbox event persistence.

This is a powerful cross-module seam: when a sales order is created, a chain of side effects fires — inventory reservation → potential production scheduling → orchestrator event persistence — all from the same `AFTER_COMMIT` phase. Operators should understand this cascade when working with order creation flows.

---

## Scheduler Surfaces

### Registered Jobs

| Job | Cron | Purpose |
| --- | --- | --- |
| `outbox-publisher` | `0/30 * * * * *` (default, configurable) | Publishes pending outbox events to RabbitMQ |
| Audit digest | `0 30 2 * * *` | Emits previous-day audit digest for each company (see `AuditDigestScheduler` in accounting) |
| Dunning sweep | `0 15 3 * * *` | Places dealers on hold if >45 days bucket has balance (see `DunningService` in sales) |

Other scheduled jobs may be registered dynamically through `SchedulerService`. The `SchedulerService` supports job registration, pause, listing, and last-run tracking. Jobs are persisted to the `scheduled_jobs` table.

### ShedLock

All scheduled jobs are protected by ShedLock for distributed locking via `ShedLockConfig`. The lock provider uses JDBC-based pessimistic locking against the same datasource as the application. The lock-at-most-for duration is configurable via `orchestrator.outbox.lock-at-most-for` (default: 5 minutes).

### Thread Pool

The `SchedulerConfig` configures a `ThreadPoolTaskScheduler` with a 5-thread pool (prefix: `orchestrator-scheduler-`). All scheduler components are conditional on `spring.task.scheduling.enabled` (default: `true`).

---

## Feature Flags

| Flag | Default | Purpose |
| --- | --- | --- |
| `orchestrator.payroll.enabled` | `false` | Controls whether the orchestrator payroll run command is active. When `false` (CODE-RED), the command throws `OrchestratorFeatureDisabledException` with a pointer to `/api/v1/payroll/runs`. |
| `orchestrator.factory-dispatch.enabled` | `false` | Controls whether the orchestrator factory-dispatch command is active. When `false` (CODE-RED), the command is rejected with `OrchestratorFeatureDisabledException`. |
| `erp.inventory.accounting.events.enabled` | `true` | Controls whether the inventory→accounting event bridge is active. When `false`, inventory events do **not** trigger accounting journal entries. |
| `SystemSettingsService.isAutoApprovalEnabled()` | dynamic | Controls whether the `OrderAutoApprovalListener` fires after `SalesOrderCreatedEvent`. Evaluated at runtime from the system settings service. |

---

## Deprecated, Dead, and No-Op Orchestration Seams

### Retired: Orchestrator Dispatch Shortcut (HARD CUT)

The older orchestrator-side dispatch shortcut is retired and intentionally not recoverable. Orchestrator guidance must not describe any dispatch-posting writer inside this module; the surviving public dispatch writer is `POST /api/v1/dispatch/confirm`.

**Dispatch is explicitly a two-layer seam:**
- **Transport/controller ownership**: `inventory.DispatchController` at `/api/v1/dispatch/**`
- **Commercial/accounting ownership**: `sales.SalesDispatchReconciliationService` handles the authoritative commercial and accounting side effects

The canonical public write remains `POST /api/v1/dispatch/confirm`: inventory owns transport/controller execution there, while sales owns the downstream commercial/accounting side effects.

**Action:** Treat historical orchestrator dispatch references as retired and route shipment posting through `POST /api/v1/dispatch/confirm` only.

### Deprecated: Orchestrator Payroll Run (HARD BLOCK)

`IntegrationCoordinator.generatePayroll()` throws `ApplicationException` with a deprecation message pointing to `/api/v1/payroll/runs`. The canonical payroll run path is `POST /api/v1/payroll/runs`, owned by the HR module. The orchestrator still wraps `recordPayrollPayment()` for accounting, but the generate step is replaced by the HR module's own payroll run implementation.

**Action:** Do not call `runPayroll` through the orchestrator. The response is a business error redirecting to `/api/v1/payroll/runs`.

### No-Op / Stub: ExternalSyncService

`ExternalSyncService` contains two `@Retryable` methods (`sendCostingSnapshot`, `exportAccountingData`) that log only and perform no meaningful work. These appear to be placeholder stubs for future external integrations rather than active integrations. They should not be treated as production-ready behavior.

### No-Op: WorkflowService State Machine Definitions

`WorkflowService` maintains an in-memory `ConcurrentHashMap` of named workflow step definitions (`order-approval`, `order-auto-approval`, `order-fulfillment`, `dispatch`, `payroll`). These are used to generate trace IDs and validate workflow names but do **not actually execute workflow steps**. The `startWorkflow` method generates a UUID but does not orchestrate a multi-step sequence. The step definitions are informational only.

### No-Op: PolicyEnforcer

`PolicyEnforcer` contains basic null checks for `userId` and `companyId` for order approval, dispatch, and payroll permissions. It does not perform actual RBAC enforcement — the real enforcement happens at the controller `@PreAuthorize` annotations and the module-level RBAC infrastructure. `PolicyEnforcer` is a placeholder for future policy expansion.

### Weak: FactorySlipEventListener

`FactorySlipEventListener` listens to `PackagingSlipEvent` via `@EventListener` (synchronous, within the originating transaction). It only logs the event details. There is no downstream processing, no queue integration, and no side effects. It is a lightweight observer that may be removed or replaced by a more meaningful integration in the future.

### Configuration-Guarded Safety Risks

| Seam | Risk | Impact |
| --- | --- | --- |
| `erp.inventory.accounting.events.enabled = false` | Inventory movements and valuation changes will **not** trigger accounting journal entries. | If this toggle is off, inventory operations silently skip financial side effects rather than failing closed. This is a significant hidden coupling risk. |
| `orchestrator.payroll.enabled = false` | Orchestrator payroll run commands will be rejected. | This is the correct fail-closed behavior but means the orchestrator payroll path is a dead end. |
| `orchestrator.factory-dispatch.enabled = false` | Historical orchestrator dispatch shortcuts stay disabled. | Dispatch is a two-layer seam: inventory owns transport/controller (`/api/v1/dispatch/**`), sales owns commercial/accounting side effects. The canonical dispatch path is `POST /api/v1/dispatch/confirm`, not the orchestrator. |
| `auto-approval disabled` | `SalesOrderCreatedEvent` will be consumed but the listener will log and skip. | No inventory reservation or orchestrator event is published. Orders stay in their initial status until manually approved. |

---

## Orchestration vs. Canonical Module Ownership

A reader must understand the distinction between what the orchestrator coordinates and what modules actually own.

| Surface | Orchestrator Role | Canonical Owner |
| --- | --- | --- |
| Order approval workflow | Coordinates approval events, inventory reservation, and production scheduling | Sales module owns order lifecycle; orchestrator is a coordination overlay |
| Inventory reservation | Performs reservation on behalf of the approval workflow | Inventory module owns reservation logic (`FinishedGoodsService`) |
| Production scheduling | Creates urgent production plans for shortfalls | Factory module owns production plans (`FactoryService`) |
| Dispatch confirmation | **Retired from orchestrator ownership** | Two-layer seam: inventory owns transport/controller (`/api/v1/dispatch/**`), sales owns commercial/accounting (`SalesDispatchReconciliationService`). Canonical path: `POST /api/v1/dispatch/confirm` |
| Payroll run + payment | **Deprecated** for generation; still wraps payment recording | HR module owns canonical payroll (`POST /api/v1/payroll/runs`) |
| Dashboard aggregation | Aggregates cross-module read models for admin/factory/finance dashboards | Each module owns its own read models; orchestrator only composes them |
| Outbox event publishing | Owns the full outbox lifecycle | Orchestrator owns this; it is not delegated to business modules |
| Audit/trace recording | Owns orchestrator-scoped audit trail | Separate from platform audit and enterprise audit trail (see ADR-004) |

---

## Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — orchestrator module ownership and canonical dependency edges
- [docs/RELIABILITY.md](../RELIABILITY.md) — retry/dead-letter handling, known safety gaps, and configuration-guarded risks
- [docs/agents/ORCHESTRATION_LAYER.md](../agents/ORCHESTRATION_LAYER.md) — orchestration layer governance
- [docs/adrs/ADR-003-outbox-pattern-for-cross-module-events.md](../adrs/ADR-003-outbox-pattern-for-cross-module-events.md) — outbox pattern architectural decision
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure
- [docs/modules/core-audit-runtime-settings.md](core-audit-runtime-settings.md) — audit-surface ownership and runtime-gating split
