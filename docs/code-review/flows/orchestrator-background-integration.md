# Orchestrator / background / integration

## Scope and evidence

This review covers the orchestrator entrypoints, lease-based command dispatch, outbox publication, transaction listeners, scheduler registration, dashboard aggregation, integration-health surfaces, correlation/trace handling, retries, and failure routing. It also includes the related background integrations that materially affect incident recovery in this branch: enterprise audit retries, GitHub ticket sync, daily audit digest, and dunning sweeps.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/{OrchestratorController,DashboardController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/{CommandDispatcher,OrchestratorIdempotencyService,IntegrationCoordinator,EventPublisherService,TraceService,OrderAutoApprovalListener,DashboardAggregationService,CorrelationIdentifierSanitizer}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/{OrchestratorCommand,OrchestratorCommandRepository,OutboxEvent,OutboxEventRepository,AuditRecord,AuditRepository,OrderAutoApprovalState,OrderAutoApprovalStateRepository,ScheduledJobDefinition,ScheduledJobDefinitionRepository}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/scheduler/{OutboxPublisherJob,SchedulerService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/config/{OrchestratorFeatureFlags,DispatchMappingHealthIndicator,SchedulerConfig,ShedLockConfig}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/workflow/WorkflowService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/integration/ExternalSyncService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/{event/SalesOrderCreatedEvent.java,service/SalesCoreEngine.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/{exception/AuditExceptionRoutingService.java,audit/{IntegrationFailureAlertRoutingPolicy,IntegrationFailureMetadataSchema,IntegrationFailureAlertRoute}.java,audittrail/EnterpriseAuditTrailService.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/SupportTicketGitHubSyncService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AuditDigestScheduler.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/DunningService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/config/DevRabbitConfig.java`
- `erp-domain/src/main/resources/{application.yml,application-prod.yml,db/migration_v2/V6__orchestrator.sql,db/migration_v2/V35__performance_hotspot_indexes.sql}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/{OrchestratorControllerIT,TraceServiceIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/{CommandDispatcherTest,EventPublisherServiceTest,IntegrationCoordinatorTest,OrderAutoApprovalListenerTest,DashboardAggregationServiceTest,CorrelationIdentifierSanitizerTest,OrchestratorIdempotencyServiceTest}.java`, and truth suites under `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/{orchestrator,runtime}/`

Supporting runtime evidence in this session:

- `curl -i -s http://localhost:8081/actuator/health` returned HTTP `404` (`"No static resource actuator/health."`).
- `curl -i -s http://localhost:9090/actuator/health` returned HTTP `200` with `{"status":"UP","groups":["liveness","readiness"]}`.
- Baseline validator `mvn test -Pgate-fast -Djacoco.skip=true` passed before drafting.

## Entrypoints

| Surface | Entrypoints | Controller / trigger | Notes |
| --- | --- | --- | --- |
| Order approval orchestration | `POST /api/v1/orchestrator/orders/{orderId}/approve` | `OrchestratorController.approveOrder(...)` | Accepts optional `Idempotency-Key` and `X-Request-Id`, normalizes payloads, then dispatches a lease-scoped orchestration command. |
| Fulfillment updates | `POST /api/v1/orchestrator/orders/{orderId}/fulfillment` | `OrchestratorController.fulfillOrder(...)` | Allows `PROCESSING`, `CANCELLED`, and `READY_TO_SHIP`; fail-closes shipped/dispatch status unless the canonical dispatch truth already exists. |
| Factory batch dispatch (legacy / CODE-RED gated) | `POST /api/v1/orchestrator/factory/dispatch/{batchId}` | `OrchestratorController.dispatch(...)` | Still advertised in OpenAPI, but actual execution is feature-flagged and defaults to disabled. |
| Deprecated aliases | `POST /api/v1/orchestrator/dispatch`, `POST /api/v1/orchestrator/dispatch/{orderId}`, `POST /api/v1/orchestrator/payroll/run` | `OrchestratorController` | These now return HTTP `410 GONE` with `canonicalPath` hints. |
| Trace and health reads | `GET /api/v1/orchestrator/traces/{traceId}`, `GET /api/v1/orchestrator/health/{integrations,events}` | `OrchestratorController.trace(...)`, `integrationsHealth()`, `eventHealth()` | Trace reads are company-scoped; event health is not. |
| Dashboard aggregation | `GET /api/v1/orchestrator/dashboard/{admin,factory,finance}` | `DashboardController` | Thin wrappers over `IntegrationCoordinator.fetch*Dashboard(...)`. |
| Transaction listener | `SalesOrderCreatedEvent` | `OrderAutoApprovalListener.onOrderCreated(...)` | Fires after sales-order commit and can trigger orchestrator auto-approval. |
| Scheduler bootstrap | app startup + `@PostConstruct` | `OutboxPublisherJob.schedule()` | Registers the `outbox-publisher` job in `scheduled_jobs` and in the in-memory scheduler. |
| Related background integrations | `@Scheduled` / `@Async` methods | `EnterpriseAuditTrailService`, `SupportTicketGitHubSyncService`, `AuditDigestScheduler`, `DunningService` | These are not in the orchestrator package, but they shape the actual background failure and recovery posture. |

`openapi.json` still exposes the orchestrator dispatch aliases, factory dispatch path, dashboards, health paths, and `/api/v1/orchestrator/payroll/run`, so the contract surface still includes routes that now respond with `410` or remain CODE-RED gated.

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `orchestrator_commands` | `V6__orchestrator.sql`, `OrchestratorCommand`, `OrchestratorCommandRepository`, `OrchestratorIdempotencyService` | Lease-based exactly-once command scope keyed by `(company_id, command_name, idempotency_key)`, request hash, trace id, status, and last error. |
| `orchestrator_outbox` | `V6__orchestrator.sql`, `OutboxEvent`, `OutboxEventRepository`, `EventPublisherService`, `V35__performance_hotspot_indexes.sql` | Durable event queue with status, retry count, next-attempt timestamp, dead-letter marker, trace/request/idempotency identifiers, and company scope. |
| `orchestrator_audit` | `V6__orchestrator.sql`, `AuditRecord`, `AuditRepository`, `TraceService` | Trace timeline store for orchestrator event history keyed by trace id and company. |
| `order_auto_approval_state` | `V6__orchestrator.sql`, `OrderAutoApprovalState`, `OrderAutoApprovalStateRepository` | Partial progress tracker for inventory reservation and status updates during order auto-approval. |
| `scheduled_jobs` / `shedlock` | `V6__orchestrator.sql`, `ScheduledJobDefinition`, `SchedulerService`, `ShedLockConfig` | Outbox scheduler registration metadata plus distributed lock state for the outbox publisher. |
| `sales_orders.trace_id` | `SalesOrder`, `V3__sales_invoice.sql`, `SalesCoreEngine.attachTraceId(...)` | Cross-module link from orchestration trace ids back into the sales aggregate. |
| Management health groups | `application-prod.yml` | Readiness probes expose `db`, `rabbit`, `requiredConfig`, and `configuration`, but not outbox backlog age or GitHub sync state. |
| Runtime broker stub (non-prod) | `DevRabbitConfig` | In `dev` and `openapi` profiles, Rabbit sends are converted into debug logs rather than real broker delivery. |

## Flow narrative

### 1. Command dispatch is lease-based, idempotent, and still mostly synchronous

`OrchestratorController` is the public command boundary. For order approval, fulfillment, and factory dispatch it:

1. requires company context from `CompanyContextHolder`,
2. sanitizes `X-Request-Id` and `Idempotency-Key` through `CorrelationIdentifierSanitizer`,
3. falls back from explicit idempotency key -> request id -> deterministic hash-derived key,
4. normalizes payloads when the caller omitted an explicit idempotency key, and
5. hands the request to `CommandDispatcher`.

`CommandDispatcher` then turns each request into a lease through `OrchestratorIdempotencyService.start(...)`.

- The idempotency service hashes `(companyId, commandName, normalized request payload)` with a stable, sorted `ObjectMapper`.
- `reserveScope(...)` inserts the row with `ON CONFLICT DO NOTHING` into `orchestrator_commands`.
- A matching successful command becomes a no-op replay and returns the original `traceId`.
- A matching failed command is reopened with `markRetry()` and the same `traceId`.
- A same-scope / different-payload request fails with `CONCURRENCY_CONFLICT`.
- `markSuccess(...)` is deferred to `afterCommit`, and if the outer transaction rolls back it is persisted as `FAILED` with `"Outer transaction rolled back before commit"`.

This is a good resilience pattern, but the “workflow” abstraction is thinner than its name suggests:

- `WorkflowService.startWorkflow(...)` only returns a UUID.
- `WorkflowService.steps(...)` is never called outside the class itself.
- The configured step names (`VALIDATE_CREDIT`, `QUEUE_PRODUCTION`, `BOOK_LEDGER`, `NOTIFY_CUSTOMER`) are descriptive metadata, not an executed or persisted state machine.

Operationally, the real orchestration still happens as direct synchronous Java service calls inside the request transaction. The durable part is the idempotency/trace/outbox envelope around those calls, not a separate workflow engine.

### 2. Order-created listener drives auto-approval and urgent-production side effects

`SalesCoreEngine` publishes `SalesOrderCreatedEvent(saved.getId(), company.getCode(), saved.getTotalAmount())` after the sales order is saved. `OrderAutoApprovalListener` subscribes with `@TransactionalEventListener(phase = AFTER_COMMIT)`, so auto-approval only starts once the originating order transaction has committed.

The listener is guarded by `SystemSettingsService.isAutoApprovalEnabled()`, which itself is seeded from `erp.auto-approval.enabled` and can be overridden through persisted system settings. When enabled, the listener:

- sets company context explicitly,
- derives a deterministic `AUTO-APPROVE-{orderId}` idempotency key and request id,
- calls `CommandDispatcher.autoApproveOrder(...)`, and
- writes the returned `traceId` back to the sales order via `SalesService.attachTraceId(...)`.

The command path then reaches `IntegrationCoordinator.autoApproveOrder(...)`, which uses `OrderAutoApprovalState` as a partial-progress ledger:

- `reserveInventory(...)` loads the sales order, reserves finished goods, and updates orchestrator workflow status to `RESERVED`.
- If inventory shortages exist, `scheduleUrgentProduction(...)` creates a production plan and factory task with the trace/idempotency identifiers embedded in notes and descriptions.
- If no shortages exist, the order status moves to `READY_TO_SHIP`; otherwise it becomes `PENDING_PRODUCTION`.
- Retries reuse the same state row, so inventory reservation is not re-run if it already succeeded.

This is the strongest implemented orchestration in the package, but the progress model is incomplete. `OrderAutoApprovalState` contains booleans for `salesJournalPosted`, `dispatchFinalized`, and `invoiceIssued`, yet no current orchestrator path ever calls `markSalesJournalPosted()`, `markDispatchFinalized()`, or `markInvoiceIssued()`. Likewise, `IntegrationCoordinator.queueProduction(...)` and `createAccountingEntry(...)` exist but have no call sites. The table suggests a fuller orchestrated lifecycle than the current implementation actually executes.

### 3. Fulfillment updates and legacy dispatch/payroll branches fail closed toward canonical surfaces

Fulfillment updates are not allowed to invent final business truth.

- `PROCESSING` only updates orchestrator workflow status.
- `CANCELLED` marks the auto-approval state as failed and calls `salesService.cancelOrder(...)`.
- `READY_TO_SHIP` re-enters the auto-approval flow.
- `SHIPPED`, `DISPATCHED`, `FULFILLED`, and `COMPLETED` are rejected unless `salesService.hasDispatchConfirmation(...)` is already true, in which case the method only echoes the real sales-order status.

That is a deliberate fail-closed design: the canonical shipment truth is `/api/v1/dispatch/confirm`, not the orchestrator façade.

The same pattern appears in the legacy dispatch and payroll branches:

- `/api/v1/orchestrator/dispatch` and `/api/v1/orchestrator/dispatch/{orderId}` always return `410 GONE` with `canonicalPath=/api/v1/dispatch/confirm`.
- `/api/v1/orchestrator/payroll/run` always returns `410 GONE` with `canonicalPath=/api/v1/payroll/runs`.
- `/api/v1/orchestrator/factory/dispatch/{batchId}` still exists, but `CommandDispatcher.dispatchBatch(...)` wraps it in `executeFeatureGuardedCommand(...)` and fails closed with `OrchestratorFeatureDisabledException` unless `orchestrator.factory-dispatch.enabled=true`.
- `CommandDispatcher.runPayroll(...)` still exists too, but even with the feature flag enabled the downstream `IntegrationCoordinator.generatePayroll(...)` immediately throws a canonical-path business exception, so the legacy orchestration path cannot complete end-to-end.

If factory dispatch is ever re-enabled, its side effects are direct and synchronous:

- `updateProductionStatus(...)` marks a production plan `COMPLETED` and can resume order auto-approval,
- the retired release-batch bridge no longer exists, so the coordinator cannot recreate a second
  factory batch-create seam alongside canonical `POST /api/v1/factory/production/logs` ownership
  on dispatch callers.

That keeps the fail-closed dispatch branch from reviving a second factory batch-create seam, but it also means any future dispatch re-enable would need its own canonical execution contract.

### 4. Outbox publication is the durable boundary after synchronous side effects

Every successful orchestrator command ends by enqueuing a `DomainEvent` and writing a trace record.

- `EventPublisherService.enqueue(...)` serializes the whole `DomainEvent` record, requires current company context, sanitizes `traceId`, `requestId`, and `idempotencyKey`, and inserts a `PENDING` row into `orchestrator_outbox`.
- `TraceService.record(...)` writes a company-scoped `orchestrator_audit` row with JSON-serialized details.
- The outbox payload therefore contains the same identifiers that are also persisted in the outbox columns and in `orchestrator_audit`.

`OutboxPublisherJob` is the runtime bridge from DB row to broker send. At startup it registers `outbox-publisher` with cron `0/30 * * * * *` through `SchedulerService.registerJob(...)`, which persists a `scheduled_jobs` row and schedules an in-memory task.

The publish loop in `EventPublisherService.publishPendingEvents()` is the most robust background mechanism in this branch:

- it uses both a single-instance `AtomicBoolean` mutex and `@SchedulerLock(name = "outbox-publish-pending")`,
- it reclaims stale `PUBLISHING` rows before starting new work,
- it claims each row with `findByIdForUpdate(...)` in a `REQUIRES_NEW` transaction,
- it stores a lease window in `nextAttemptAt`,
- it publishes to `rabbitTemplate.convertAndSend("bbp.orchestrator.events", eventType, payload)`, and
- it finalizes `PUBLISHED` in a separate transaction using the entity version as a fence token.

The exact-once story is therefore: synchronous business work + durable command lease + durable outbox row + fenced finalize step. The tests cover the happy path, deterministic retry path, ambiguous publish path, finalize-failure path, stale-lease path, and multi-instance lease overlap.

Two limits matter operationally:

1. `findTop10ByStatusAndDeadLetterFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(...)` hard-caps the publisher to 10 rows per cycle, so the scheduled baseline drain rate is roughly 20 rows/minute per node.
2. The repo contains no Rabbit exchange/queue/binding declaration or `@RabbitListener` consumer for `bbp.orchestrator.events`; the code only proves the publisher side.

So the outbox is durable inside the ERP, but the end-to-end broker contract and replay tooling are not visible in this repository.

### 5. Dashboard aggregation and integration health are thin aggregation layers, not real dependency probes

The orchestrator dashboard surface is intentionally shallow:

- `DashboardController` delegates to `DashboardAggregationService`.
- `DashboardAggregationService` delegates again to `IntegrationCoordinator.fetchAdminDashboard(...)`, `fetchFactoryDashboard(...)`, and `fetchFinanceDashboard(...)`.
- The coordinator then fans out into live list/report queries like `salesService.listOrders(null)`, `salesService.listDealers()`, `factoryService.dashboard()`, `accountingService.listAccounts()`, `hrService.listEmployees()`, `reportService.cashFlow()`, `reportService.inventoryValuation()`, and `reportService.inventoryReconciliation()`.

There is no caching, snapshot table, or scheduler-backed materialization here. Every dashboard request is a synchronous fan-out across multiple modules.

The health surfaces are also thinner than their names imply:

- `integrationHealth()` only returns counts of orders, plans, accounts, and employees.
- `eventHealth()` returns outbox backlog counters from `EventPublisherService.healthSnapshot()`.
- The true infrastructure health (DB, Rabbit, dispatch mapping, readiness/liveness) lives on the management port via Spring Boot health groups, not on the orchestrator controller.

In this session, `http://localhost:9090/actuator/health` was reachable and reported `UP`, while `http://localhost:8081/actuator/health` returned `404`. Operators therefore need to know that app-port probing and management-port probing are not interchangeable.

There is also a tenant-isolation problem in the event-health path: `EventPublisherService.healthSnapshot()` uses global repository counts with no company filter, but `GET /api/v1/orchestrator/health/events` is only role-gated as `ROLE_ADMIN`. In a multi-tenant ERP, that means a tenant admin can observe global backlog, ambiguous-publishing, and dead-letter counts across all companies.

### 6. Related background jobs and integration side effects outside the orchestrator package

The orchestrator flow does not live in isolation. Several adjacent background services affect the real incident and recovery posture:

- `EnterpriseAuditTrailService` records business events asynchronously, retries them every 30 seconds, keeps both an in-memory queue and a persisted retry table, and drops retries after the configured max attempts. This improves latency for front-door transactions but accepts eventual audit gaps during prolonged failures.
- `SupportTicketGitHubSyncService` creates GitHub issues asynchronously and polls issue status every five minutes. Status sync retries on the next schedule, but initial issue-creation failures are only written to `githubLastError`; there is no scheduler that automatically re-submits unsynced tickets.
- `AuditDigestScheduler` emits per-company previous-day accounting digest lines to logs at `02:30`.
- `DunningService` performs a daily dealer hold/reminder sweep at `03:15`.
- `ExternalSyncService` advertises retryable outbound integrations (`sendCostingSnapshot`, `exportAccountingData`) with Spring Retry, but there are no call sites in this branch.

These jobs share an operational smell: only the outbox publisher uses ShedLock. The enterprise audit retry job, GitHub sync, dunning sweep, and audit digest all rely on plain `@Scheduled` execution. In a multi-instance deployment, they will duplicate unless deployment topology guarantees a single scheduler instance.

## Retries, failures, and failure routing

### Orchestrator command retries

- Duplicate same-payload requests reuse the original trace id and do not re-execute.
- Failed commands can be retried by reusing the same idempotency key and payload.
- Payload mismatches on the same scope raise `CONCURRENCY_CONFLICT`.
- Invalid posting amounts fail before dispatch/payroll side effects and mark the command row failed.
- Denied legacy commands emit `OrchestratorCommandDenied` both into the outbox and into `orchestrator_audit` before the controller returns `410`.

### Outbox retries and failure buckets

- deterministic failures (`MessageConversionException`, `AmqpConnectException`, `AmqpAuthenticationException`) move the row back to `PENDING` with exponential backoff `30s * 2^retryCount`;
- rows dead-letter after `MAX_RETRY_ATTEMPTS = 5`;
- ambiguous publish outcomes are held in `PUBLISHING` with `AMBIGUOUS_PUBLISH:*` markers and a delayed recheck window;
- finalize failures are also held in `PUBLISHING` with `FINALIZE_FAILURE:*` markers;
- stale publishing leases are converted into `STALE_LEASE_UNCERTAIN:*` markers instead of being auto-republished.

This is an intentionally fail-closed design: the code would rather stall a row for manual reconciliation than risk duplicate external side effects.

### Failure routing is fragmented

The branch contains a generic integration-failure routing policy, but orchestrator failures only partly use it.

- `AuditExceptionRoutingService` routes malformed requests and settlement-related `ApplicationException`s into `AuditEvent.INTEGRATION_FAILURE` with a stable schema and alert routes such as `SEV2_URGENT` and `SEV3_TICKET`.
- Orchestrator command denials and outbox failures do **not** feed that policy. They remain in `orchestrator_commands`, `orchestrator_outbox.lastError`, and logs.
- `SupportTicketGitHubSyncService`, `AuditDigestScheduler`, `DunningService`, and `EnterpriseAuditTrailService` mostly record failures as warnings or per-record last-error strings rather than central incident routes.

So the repo has three different failure-routing styles at once:

1. structured alert-routing metadata for selected integration failures,
2. durable but manual-reconciliation outbox markers,
3. plain warn/error logs plus per-record last-error fields for other background integrations.

## Side effects, integrations, and recovery behavior

- Order approval can reserve inventory, attach trace ids to sales orders, queue urgent production plans/tasks, update sales workflow status, enqueue broker events, and write trace rows.
- Fulfillment updates can cancel orders or re-enter auto-approval, but they deliberately refuse to invent dispatch truth.
- If factory dispatch is re-enabled, it can complete plans and resume auto-approval, but it no longer logs release batches through the retired factory batch surface.
- Outbox publication can write to RabbitMQ exchange `bbp.orchestrator.events`; there is no in-repo evidence of a matching consumer or dead-letter policy.
- Enterprise audit background retries can eventually drop unpersisted audit events after max-attempt exhaustion.
- GitHub support-ticket sync mutates ticket status, last-sync timestamps, GitHub issue links, and resolved-notification timestamps; initial create failures do not auto-retry.
- Dunning and audit-digest jobs can change dealer status and emit external email/log side effects with only warn-level failure handling.

Recovery posture is strongest where the repo has durable replay anchors (`orchestrator_commands`, `orchestrator_outbox`, `orchestrator_audit`). Recovery is weaker once a row lands in ambiguous publish state, once a GitHub issue create fails, or once a non-ShedLock scheduler runs in a multi-node deployment. In all three cases, the code preserves evidence, but it does not provide an operator-facing replay surface.

## Resilience, observability, and incident recovery

### Strengths

- Lease-based idempotency protects command replay and preserves a stable trace id across retries.
- `markSuccess(...)` is commit-aware, so rollback does not leave false-success command rows behind.
- Outbox publication uses fenced claims plus fail-closed ambiguous-state handling instead of optimistic blind retries.
- Trace, request, and idempotency identifiers are sanitized before persistence and before log rendering.
- The management readiness group includes `rabbit`, and the outbox publisher exports Micrometer gauges for pending/retrying/publishing/ambiguous/dead-letter counts.

### Gaps

- The controller-level event health API exposes global tenant data because counts are not company-scoped.
- No controller or integration tests cover the dashboard or health endpoints; existing tests focus on approval/fulfillment/idempotency behavior.
- `scheduled_jobs.next_run_at` is defined in the schema/entity but is never populated anywhere in the codebase.
- The outbox publisher exposes counts, but not backlog age, oldest pending event, last successful publish, or per-company breakdown.
- Management tracing is configured through OTLP in `application-prod.yml`, but the custom orchestrator `traceId` is a separate correlation system with no bridge to OpenTelemetry span ids or MDC.
- In `dev` and `openapi` profiles, `DevRabbitConfig.NoOpRabbitTemplate` makes broker sends succeed as debug no-ops, so non-prod review surfaces do not exercise real broker delivery.
- The sales aggregate stores `trace_id` as `varchar(64)` while orchestrator tables and sanitizers allow `128`, so future non-UUID trace formats could overflow the sales column.

### Incident recovery assumptions

The code assumes operators can reconcile and repair background failures out-of-band.

- Ambiguous outbox rows are held, not replayed.
- Dead-letter rows remain counted, but there is no replay endpoint or admin job in this branch.
- GitHub ticket sync errors stay on the ticket record.
- Enterprise audit retry exhaustion results in dropped retries after logging.

That means recovery today is evidence-rich but tool-poor: the system records what went wrong, yet the likely remediation path is manual SQL / admin intervention rather than a first-class recovery workflow.

## Orchestrator and integration risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| high | privacy / observability | `GET /api/v1/orchestrator/health/events` exposes global outbox counters to tenant admins because `EventPublisherService.healthSnapshot()` uses unscoped repository counts. | `OrchestratorController.eventHealth()`, `EventPublisherService.healthSnapshot()`, `OutboxEventRepository` count methods | A company admin can infer system-wide backlog, dead letters, or ambiguous publish incidents for other tenants. |
| high | resilience / incident recovery | Outbox ambiguous, finalize-failure, and stale-lease rows are intentionally held for manual reconciliation, but the repo provides no replay API, reconciliation job, or dead-letter consumer contract. | `EventPublisherService`, `OutboxEventRepository`, absence of consumer/binding code for `bbp.orchestrator.events` | The system avoids duplicate side effects, but real incident recovery depends on manual operator work rather than a supported control plane. |
| high | design / state-machine drift | `WorkflowService` step definitions and `OrderAutoApprovalState` flags imply a fuller orchestrated lifecycle than the code actually executes; `salesJournalPosted`, `dispatchFinalized`, `invoiceIssued`, `queueProduction(...)`, and `createAccountingEntry(...)` are not wired into live flows. | `WorkflowService`, `OrderAutoApprovalState`, `IntegrationCoordinator`, repo-wide call-site search | Readers can overestimate what the orchestrator truly guarantees, and incident triage will find state tables that do not reflect dispatch/journal/invoice reality. |
| medium | performance / scalability | The outbox publisher drains at most 10 rows per scheduled run and the default cron is every 30 seconds; backlog age is not surfaced anywhere. | `OutboxEventRepository.findTop10...`, `OutboxPublisherJob`, `application.yml` | A bursty integration workload can accumulate pending events faster than the scheduled publisher drains them, without a first-class “oldest stuck event” alarm. |
| medium | runtime operations | Only the outbox publisher is protected by ShedLock; enterprise audit retry, GitHub sync, audit digest, and dunning sweeps are plain `@Scheduled` jobs. | `ShedLockConfig`, `OutboxPublisherJob`, `EnterpriseAuditTrailService`, `SupportTicketGitHubSyncService`, `AuditDigestScheduler`, `DunningService` | In clustered deployments, duplicate executions can create duplicate emails, duplicate GitHub polls, or confusing audit retry behavior. |
| medium | API / observability drift | OpenAPI still advertises deprecated dispatch/payroll endpoints, the app port lacks actuator health, and `/health/integrations` is only a count snapshot rather than a true dependency probe. | `openapi.json`, `OrchestratorController`, runtime curls to `8081` and `9090`, `IntegrationCoordinator.health()` | Clients and operators can integrate to dead or misleading surfaces and miss the actual management-port health story. |
| medium | operator-surface drift | `GET /api/v1/admin/operations/status` currently returns `404`, so the expected operator-status surface is absent on the live backend. | live backend probe on `GET /api/v1/admin/operations/status`, operator/admin route survey during review | Ops dashboards or frontend operator tooling can assume there is a dedicated status endpoint and only discover at runtime that the backend exposes no live handler. |
| medium | test-confidence gap | `DevRabbitConfig` turns broker publishes into no-op debug logs in `dev`/`openapi` profiles. | `DevRabbitConfig.NoOpRabbitTemplate` | Non-prod validation can mark outbox rows as published without proving real broker routing or downstream consumer behavior. |
| low | schema consistency | Orchestrator trace ids allow 128 characters, but `sales_orders.trace_id` is only 64 characters and `SalesCoreEngine.attachTraceId(...)` does not enforce the lower bound. | `CorrelationIdentifierSanitizer`, `V6__orchestrator.sql`, `SalesOrder`, `V3__sales_invoice.sql`, `SalesCoreEngine.attachTraceId(...)` | A future trace format expansion could fail when writing back into sales orders even though orchestrator tables accept it. |
| low | design completeness | `ExternalSyncService` defines retryable outbound sync methods, but there are no call sites in the branch and no health surface mentions them. | `ExternalSyncService`, repo-wide call-site search | The integration story appears broader than the actually wired runtime surface. |

## Security, privacy, protocol, performance, and observability notes

### Strengths

- Correlation identifiers are normalized, bounded, and log-sanitized to avoid control-character injection and runaway header values.
- Order approval, dispatch, payroll, trace, and dashboard surfaces are role-gated.
- Legacy dispatch and payroll façades prefer `410 GONE` + `canonicalPath` over silent behavior drift.
- Order-fulfillment updates refuse to claim shipment completion without canonical dispatch confirmation.
- Outbox publishing uses durable identifiers (`traceId`, `requestId`, `idempotencyKey`) across command rows, outbox rows, and trace rows.

### Hotspots

- The event-health surface violates tenant least-knowledge because it is global rather than company-scoped.
- The repo has two tracing systems (`traceId` in business tables and OTLP tracing in management config) with no documented bridge.
- Dashboard endpoints are synchronous fan-out reads with no caching, throttling, or background materialization.
- Scheduler metadata is persisted, but there is no operational controller or command surface for job inspection/replay/pause in this branch.
- GitHub sync, dunning, and audit retry failures mostly terminate in warn/error logs, not in a shared incident-routing contract.

## Evidence notes

- `OrchestratorControllerIT` proves order-approval idempotency, request-id fallback, malformed identifier rejection, `410 GONE` canonical-path behavior for legacy dispatch/payroll endpoints, and fulfillment rejection when dispatch truth is missing.
- `CommandDispatcherTest` proves trace/idempotency propagation, denied-command audit emission, fail-closed dispatch/payroll gates, invalid posting-amount rejection, and auto-approval event publication.
- `OrchestratorIdempotencyServiceTest` plus `TS_RuntimeOrchestratorIdempotencyExecutableCoverageTest` prove lease reuse, payload mismatch conflict, failed-command retry, and rollback-aware status persistence.
- `EventPublisherServiceTest`, `TS_OrchestratorExactlyOnceOutboxTest`, and `TS_RuntimeEventPublisherExecutableCoverageTest` prove claim-before-publish, retry/backoff, ambiguous/finalize/stale-lease buckets, gauge registration, and dead-letter counting.
- `IntegrationCoordinatorTest` proves trace/idempotency propagation into urgent production plan/task notes, dispatch-journal memos, release-batch notes, and payroll-payment memos.
- `TraceServiceIT` and `TS_RuntimeTraceServiceExecutableCoverageTest` prove company-scoped trace reads and persistence of request/idempotency identifiers.
- Runtime probing in this session confirms the management health surface is on `9090`, not the app port on `8081`.
