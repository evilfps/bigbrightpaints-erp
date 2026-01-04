# Epic 07 — Performance & Scalability (DB + APIs)

## Objective
Bring performance to “ERP usable” levels under realistic data sizes:
- key lists/search screens are fast and paginated
- heavy accounting reports are stable and bounded
- no N+1 query explosions in critical flows
- DB indexes match query patterns (with safe Flyway forward migrations)

## Scope guard (no new features)
- Performance work must not change business semantics; verify via invariants + golden scenarios.
- Prefer indexes/pagination/query tuning over redesigning workflows.

## Dependencies / parallel work
- Can run in parallel with feature epics; coordinate when adding indexes/constraints.

## Likely touch points (exact)
- Repositories/services/controllers across:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests and profiling helpers:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**`
  - `erp-domain/src/test/resources/synthetic-data/**`

## Step-by-step implementation plan
1) Identify hot endpoints and heavy tables (initial focus):
   - journal lines, ledger entries, inventory movements, outbox events, invoices, orders.
2) Add measurement:
   - request timing at controller level (where appropriate)
   - query count checks for key endpoints (avoid N+1)
   - DB-level EXPLAIN plans for top queries
3) Fix the biggest offenders:
   - add/adjust indexes via Flyway forward migrations
   - add pagination + stable sorting where missing
   - fix N+1 with fetch strategies or query refactors
4) Define performance budgets:
   - p95 targets for list endpoints and bounded targets for reports
5) Add regression checks:
   - at minimum: “query count under X” tests and “report completes under Y seconds” for representative data

## Acceptance criteria
- Key list/search endpoints are paginated and do not degrade catastrophically with data growth.
- Hot-path queries use indexes and avoid sequential scans for common filters.
- No N+1 regressions in critical paths (O2C/P2P/Payroll/Production).

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- (When profiling locally) Boot: `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
