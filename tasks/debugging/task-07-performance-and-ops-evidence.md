# Task 07 — Performance + Ops Evidence (Budgets, Health, Outbox, Backups, Go/No‑Go)

## Purpose
**Accountant-level:** ensure the system is operable for period close and audit workloads (reports, reconciliation, statements) without timeouts or partial failures that could cause misstatement.

**System-level:** enforce performance budgets on hot endpoints, verify docker compose boot/health, verify outbox processing safety, and capture ops evidence for deployment readiness.

## Scope guard (explicitly NOT allowed)
- No new performance “features” like caching layers unless already intended and minimally invasive.
- No disabling safeguards to improve speed.
- No production config changes applied directly on the host; only docs/runbooks and code-level budgets/tests.

## Milestones

### M1 — Performance budgets (hot endpoints + heavy tables)
Deliverables:
- Confirm existing performance tests and budgets:
  - list/search endpoints are paginated and avoid N+1 regressions
  - heavy reports have time budgets
- Extend budgets only where missing for financially critical screens (balance sheet, ledger, statements, reconciliation).

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=PerformanceBudgetIT,PerformanceExplainIT test`

Evidence to capture:
- Performance test outputs + any captured EXPLAIN plans.
- Any indexing actions proposed (do not apply without tests + migration plan).

Stop conditions + smallest decision needed:
- If a budget fails due to environment variability: smallest decision is whether to (A) adjust the budget to a realistic bound, or (B) optimize the query/index. Prefer (B) if it is a clear N+1 or missing-index issue.

### M2 — Ops boot + health evidence (compose + smoke checks)
Deliverables:
- Docker compose boot checklist:
  - build + up
  - `/actuator/health` and readiness checks
  - required env vars documented
- Smoke checks for critical endpoints (auth, reconciliation, orchestrator health).

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Runtime checks:
  - `docker compose up -d --build`
  - `curl -fsS http://localhost:9090/actuator/health`

Evidence to capture:
- Compose logs + health outputs stored under `docs/ops_and_debug/LOGS/`.
- A short env var checklist (required + optional).

Stop conditions + smallest decision needed:
- If Docker/Testcontainers is broken: smallest decision is whether to add `docs/ops_and_debug/ENV_SETUP_LINUX.md` with exact fix commands (allowed) vs diagnosing a repo regression. Prefer documenting exact commands first.

### M3 — Outbox + backup/restore evidence (data integrity + rollback safety)
Deliverables:
- Outbox safety checks:
  - retry backoff behavior
  - stuck event detection query
  - idempotency guarantees for handlers
- Backup/restore runbook evidence:
  - pg_dump/pg_restore steps and verification checks

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,IntegrationCoordinatorTest test`

Evidence to capture:
- Outbox table query outputs and “no stuck retries” proof on a seeded dataset.
- Backup/restore logs + sanity queries (row counts, Flyway history).

Stop conditions + smallest decision needed:
- If backup/restore fails due to permissions: smallest decision is whether to (A) adjust compose to mount volumes properly, or (B) document the required operator steps for prod (recommended).

