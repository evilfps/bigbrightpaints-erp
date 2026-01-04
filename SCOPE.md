# SCOPE — BigBright ERP Backend (ERP-Grade Correctness + Performance)

## Mission
Make the `erp-domain` service behave like a real ERP: **every business event produces correct, traceable, reversible accounting + inventory state**, with strong access control and predictable performance.

This scope assumes discrepancies are unacceptable (even small rounding/linking mistakes can materially impact the business).

## In-scope ERP business processes (end-to-end)
- **Record-to-Report (Accounting Core)**: chart of accounts, journals, postings, periods (lock/close), trial balance, balance sheet/P&L, audit trails, reversals.
- **Order-to-Cash (Sales + AR)**: dealers, credit checks/overrides, sales orders, dispatch, invoices, receipts/settlements, AR aging/statements.
- **Procure-to-Pay (Purchasing + AP)**: raw material purchases/intake, supplier settlements, payments, AP aging/statements.
- **Plan/Produce-to-Stock (Factory/Production + Inventory)**: production logs, packing, finished goods batches, inventory movements, valuation/costing links to GL.
- **Hire-to-Pay (HR/Payroll)**: employees, attendance/leave, payroll runs, approvals, posting to GL, payment runs, auditability.
- **Admin/Security**: users/roles (RBAC), MFA, token lifecycle (refresh/logout/blacklist), multi-company boundaries, system settings.
- **Cross-cutting reliability**: outbox/events, schedulers, idempotency under retries, and operational runbooks.

## Code areas touched (where work will land)
- Service/app: `erp-domain/src/main/java/com/bigbrightpaints/erp/**`
  - entrypoint: `erp-domain/src/main/java/com/bigbrightpaints/erp/ErpDomainApplication.java`
  - core: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/**` (security, audit, shared config)
  - domains: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**` (accounting, inventory, sales, purchasing, hr, invoice, reports, admin/auth/rbac, etc.)
  - orchestration: `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**` (outbox/events, workflows, schedulers)
- Database/migrations: `erp-domain/src/main/resources/db/migration/**` (Flyway forward-only), plus JPA entities/repositories across `**/domain` and `**/repository`
- Config/ops: `docker-compose.yml`, `erp-domain/Dockerfile`, `.env.example`, `erp-domain/src/main/resources/application*.yml`
- Verification: `erp-domain/src/test/**` (unit + integration + e2e), `erp-domain/docs/**`, `openapi.json`

## Non-negotiables (ERP invariants)
- **Journals**
  - Every posted journal entry is balanced (debits == credits) and belongs to the correct company.
  - Every journal is linked to its originating document/event (invoice, inventory movement, payroll run, settlement, etc.) and is discoverable from both sides.
  - Every financially-impacting action is reversible (or has a documented irreversible reason) and reversals are also linked + auditable.
  - Period lock/close rules are enforced (no back-dating or posting into locked/closed periods; reopening is controlled and auditable).
- **Subledgers (AR/AP)**
  - Dealer/supplier ledgers reconcile to control accounts; aging/statements match journal activity.
- **Inventory**
  - Inventory movements are complete, ordered, and idempotent under retries.
  - Stock non-negativity is enforced where configured (including dispatch/adjustments/reservations).
  - Inventory valuation/costing is consistent with GL postings (COGS, inventory, WIP) and traceable to movement batches.
- **Security + tenancy**
  - JWT auth + RBAC is enforced; no privilege escalation.
  - Company context is consistent; no cross-company reads/writes.
- **Migrations**
  - Do not edit/delete applied Flyway migrations; use forward-only fixes with rollback guidance.
  - Avoid long locks on high-volume tables; use safe index/backfill strategies.

## Cross-module linkage contract (traceability)
For the ERP to be auditable, every “downstream” artifact must be traceable back to its source document, and “upstream” documents must be able to find their derived artifacts.

Minimum linkage chains to verify (using current behavior; no new product features):
- **O2C**: Sales Order → Dispatch/Packaging Slip → Inventory Movements → Invoice → Journal Entry → Dealer Ledger / AR statements.
- **P2P**: Purchase/Intake → Inventory Movements → Supplier Settlement/Payment → Journal Entry → Supplier Ledger / AP statements.
- **Production**: Production Log → Packing Record → Finished Goods Batch → Inventory Movements → (if enabled) Journal Entry.
- **Payroll**: Payroll Run → Posting Journal Entry → Payment/Mark-paid records → Payroll reports.
- **Reversals**: Any reversal must link to the original journal/document and preserve an auditable chain.

## Non-goals
- New UI/front-end work, or rewriting the stack (Spring Boot/JPA/Flyway/Postgres/RabbitMQ stay).
- Adding new business features/modules; the work is **verification + bug fixes + integrity/performance hardening** of existing flows.
- “Reset the DB” approaches or destructive rewrites of accounting semantics.
- Large refactors unless they directly reduce correctness/performance risk.

## Key risks (and how we mitigate)
- **Silent financial semantic drift** → lock-in behavior with golden-path tests + reconciliation checks.
- **Rounding/precision edge cases** → document a single rounding policy and enforce via tests.
- **Linking gaps (journals ↔ documents)** → add invariant tests that fail on missing links.
- **Retries/duplication (outbox/schedulers/requests)** → idempotency keys + at-least-once safe handlers + regression tests.
- **Performance regressions** → index/query audits, pagination, and benchmark baselines for hot endpoints.

## Acceptance criteria (definition of “ERP works”)
- For each in-scope business process above, there is at least one automated **golden-path E2E scenario** that asserts:
  - correct domain state transitions
  - correct accounting postings (balanced + linked + reversible)
  - correct inventory movements/valuation (where applicable)
  - correct subledger/statement output (AR/AP where applicable)
- Cross-module reconciliation checks exist and pass:
  - inventory valuation vs GL inventory control
  - AR/AP subledgers vs control accounts
  - trial balance balances and matches period close rules
- Cross-module linkage checks exist and pass for golden scenarios (documents ↔ movements ↔ journals ↔ subledgers).
- App is operable:
  - boots via Docker Compose; `/actuator/health` is `UP`
  - `mvn -f erp-domain/pom.xml test` passes (Testcontainers/Docker required for integration tests)
- Performance baseline is defined and met for core screens:
  - list/search endpoints are paginated and stable
  - heavy reports have documented expectations and do not time out under realistic data sizes

## How to use this repo asynchronously (scope → tasks workflow)
- Work is organized as epics in `tasks/`. Each epic is a large, test-driven workstream that can be owned independently.
- Each epic produces: tests, fixes, and docs/runbook notes, without weakening security or accounting semantics.
