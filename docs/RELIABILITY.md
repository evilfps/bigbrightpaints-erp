# Reliability and Observability

Last reviewed: 2026-02-15
Owner: QA & Reliability Agent

## Initial Reliability Risk Register (Phase A)

| Risk area | Failure mode | First-line detection | First-line mitigation |
| --- | --- | --- | --- |
| Async orchestration/outbox | Duplicate or missing side effects | Outbox lag + replay counters + idempotency failure logs | Enforce idempotency checks + retry/backoff guards |
| Posting pipelines | Partial or duplicated journals | Reconciliation drift and journal-link invariant tests | Block release on accounting/reconciliation gates |
| Migrations | Startup failure or data drift | Flyway overlap/drift scans + predeploy SQL checks | Stage migration dry-run + rollback plan |
| Auth/session services | Elevated 401/403 or token failures | Auth endpoint error-rate + latency traces | Fail closed + targeted regression suite |
| Payroll/period close | Non-deterministic close or reopening inconsistencies | Period-close contract tests + reconciliation dashboards | Enforce close boundary checks and approval gates |

## Metrics Expectations
- Required metric groups:
  - API: request count, p95 latency, error rate by endpoint and status class.
  - DB: query latency, connection pool saturation, migration duration.
  - Async: outbox queue depth, retry count, dead-letter count, scheduler lag.
  - Domain integrity: posting success/failure rates, reconciliation mismatch counts.
- Metric backend in production: unspecified.
  - TODO: document Prometheus/managed metrics target and retention.

## Tracing Expectations (OpenTelemetry)
- Use `traceId` propagation across controllers -> services -> orchestrator side effects.
- Include company context and operation labels as span attributes (no raw PII).
- Exporter detected in dependencies: OTLP (`opentelemetry-exporter-otlp`).
- Collector endpoint per environment: unspecified.
  - TODO: define env vars and deployment wiring.

## Instrumentation Guidelines (OpenTelemetry + Prometheus)
- Traces:
  - instrument inbound HTTP handlers and async command boundaries.
  - propagate `traceId` across orchestrator and posting calls.
  - export via OTLP to your collector.
- Metrics:
  - expose Prometheus-compatible metrics endpoint (usually Actuator Prometheus).
  - include domain counters for posting failures, idempotency rejects, reconciliation mismatches.
  - tag metrics with low-cardinality labels (`domain`, `operation`, `result`).
- Logs:
  - include `traceId` and `companyId` when available for triage joins.

## Logging Expectations
- Structured JSON logs preferred for machine parsing.
- Log levels:
  - INFO for state transitions and durable side effects.
  - WARN for retries/degraded mode.
  - ERROR for invariant breaks requiring operator action.
- Redaction must follow `docs/SECURITY.md`.

## Baseline Dashboards (Templates)
No specific platform constraint detected. Create equivalent dashboards in your chosen platform:
- API Health
  - request rate, p95/p99 latency, 4xx/5xx split by route.
- Ledger Integrity
  - posting failures, reconciliation mismatches, period-close exceptions.
- Async Loop Stability
  - outbox backlog, retry rate, scheduler failures, dead-letter volume.
- Migration Safety
  - migration duration, failure count, schema drift alerts.
- Auth and Tenant Isolation
  - denied requests by reason, suspicious company-switch attempts.

## SLO / SLI Placeholders by Domain

| Domain | Availability SLO | Latency SLO | Correctness SLI |
| --- | --- | --- | --- |
| Accounting | TODO | TODO | Reconciliation mismatch rate |
| Inventory | TODO | TODO | Negative stock prevention violations |
| Sales | TODO | TODO | Dispatch->invoice linkage success rate |
| HR/Payroll | TODO | TODO | Payroll posting success and reversal correctness |
| Auth/RBAC | TODO | TODO | Unauthorized access false-negative rate |
| Orchestrator | TODO | TODO | Duplicate side-effect rate |

All SLO targets are currently unspecified.
TODO: set numeric SLO values with product and ops owners.

## Incident Handling
- Severity model: unspecified.
  - TODO: define SEV levels, paging policy, and escalation timeline.
- Minimum incident artifact:
  - impacted domains
  - first failing invariant/test
  - mitigation
  - rollback decision

## Harness Integration
- Fast checks: `bash ci/lint-knowledgebase.sh`, `bash ci/check-architecture.sh`
- Full checks: `bash scripts/verify_local.sh`
- CI references:
  - `.github/workflows/ci.yml`
  - `.github/workflows/doc-lint.yml`
  - `.github/workflows/codex-review.yml`
