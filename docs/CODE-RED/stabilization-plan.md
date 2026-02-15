
# CODE-RED Stabilization Plan (V1)

Execution governance note:
- Canonical orchestration and queue control now live in `docs/ENTERPRISE_BACKEND_STABILIZATION_PLAN.md` + `asyncloop`.
- This document remains the detailed CODE-RED technical backlog/history.

For the full detailed execution plan (V2), see:
- `docs/CODE-RED/plan-v2.md`

Constraints (non-negotiable):
- No new features. No experiments. No refactors unless required for correctness/idempotency.
- Production code is source of truth; every change must be enforced by tests + invariants (fail closed).

## Status Snapshot (as of 2026-01-30)

- Local release gate: `bash scripts/verify_local.sh` (schema drift scan + Flyway overlap scan + time API scan + `mvn verify`).
- CI gate: `.github/workflows/ci.yml` must run the same steps as `scripts/verify_local.sh` (scans + `mvn verify`, plus triage on failures).

## Verified Repo Findings (as of 2026-01-30)

These are repo-verified facts and blockers that must be addressed by the EPIC milestones below.

Verified (in this repo)
- Inventory "slip lookup by order" is unsafe:
  - `DispatchController.getPackagingSlipByOrder` is a mutating GET (`/api/v1/dispatch/order/{orderId}`) because it can
    lazily create slips/reservations (`FinishedGoodsService.getPackagingSlipByOrder` -> `reserveForOrder`) and, when
    multiple slips exist, it selects the "most recent" slip (nondeterministic).
    (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java:83`,
    `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:1154`).
- Orchestrator fulfillment can mark orders `SHIPPED`/`DISPATCHED` without slip dispatch/invoice/journals by calling
  `SalesService.updateStatusInternal` (bypasses dispatch invariants). This must remain disabled in prod until routed
  through the canonical dispatch workflow.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:296`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinator.java:316`).
- Packing still bypasses `AccountingFacade` by posting journals directly via `AccountingService.createJournalEntry`:
  `PackingService` + `BulkPackingService`.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java:401`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java:613`).
- Bulk packing is not idempotent at the API boundary: `BulkPackRequest` has no idempotency key and bulk pack journaling
  is not reserve-first.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/dto/BulkPackRequest.java:17`).
- Packaging variants/BOM hard-cutover schema is not implemented yet: no `product_packaging_variants` /
  `product_packaging_components` tables exist; the current implementation uses packaging size mappings (legacy model).
- Accounting event store exists but is not wired: `AccountingEventStore` has no call sites
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/AccountingEventStore.java:52`).
  Decision: it is explicitly not relied upon for temporal truth; closed-period reporting will use period-end snapshots.
- Production log producedAt parsing ignores company timezone for common local date/time inputs (UTC conversion bug):
  `ProductionLogService.resolveProducedAt(...)` uses `ZoneOffset.UTC` for LocalDate/LocalDateTime parsing, which can shift
  journal dates/periods for non-UTC companies.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:542`).
- Inventory->GL auto-posting is enabled-by-default and swallows failures:
  `InventoryAccountingEventListener` posts via `AccountingService` directly and logs-and-continues on errors, creating
  inventory/GL drift risk under period locks/date validation.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java:1`).
- CI currently gates on schema drift scan + time API scan + tests
  (must mirror `scripts/verify_local.sh`; see `.github/workflows/ci.yml`).

Resolved since 2026-01-27
- Backorder/partial dispatch invoice-per-slip behavior is fixed: `confirmDispatch` now resolves an existing invoice
  only for already-dispatched slips via `resolveExistingInvoiceForSlip`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1407`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:2258`).
- Manual journal reference numbers are system-generated; caller-supplied `referenceNumber` is treated as a
  client idempotency key and rejected if it matches reserved/system namespaces (including `*-INV-*`)
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java:141`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:94`).
- Timezone/business-date violations removed: CompanyClock/CompanyTime are now used for business dates and
  month boundaries (Cost Allocation + Reporting), backed by `scripts/time_api_scan.sh` and CI gate.

Resolved since 2026-01-30
- AP reconciliation mismatch fixed: `ReconciliationService` now normalizes AP GL balances to the supplier ledger sign
  convention before comparing.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java:1`).
- Audit logging is made non-blocking for SERIALIZABLE business transactions: async + REQUIRES_NEW now applies by calling
  the proxied bean (self-injection), preventing audit rows from participating in caller transactions.
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/AuditService.java:1`).
- `PayrollBatchPaymentIT` no longer assumes an empty database when running as part of the full suite (scoped to the run).
  (`erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PayrollBatchPaymentIT.java:1`).

Corrections vs earlier audit text (repo reality)
- The CODE-RED docs/scripts are present here (`docs/CODE-RED/stabilization-plan.md:1`, `scripts/verify_local.sh:1`,
  `scripts/db_predeploy_scans.sql:1`); scripts have an `rg` -> `grep` fallback and can run in CI.
- "`SalesService.createOrder` not transactional / no idempotency handling" is not true in this code: it's wrapped in a
  `TransactionTemplate` and catches `DataIntegrityViolationException`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:350`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:355`).
- "`SalesService.cancelOrder` is not transactional" is not true: it is transactional
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:600`).
- Manual journal namespace protection blocks any reference containing `-INV-` (including company-prefixed invoice refs).
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:94`).
- Prior plan text implying dispatch reused `order.fulfillmentInvoiceId` for backorders is stale; current code resolves
  existing invoices only for already-dispatched slips via `resolveExistingInvoiceForSlip`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1407`).

## EPIC 00 - Discipline / Gates (Executable Release Plan)

Milestones
- M00.1 (DONE): Freeze invariants + remove obvious double-post/double-dispatch hazards
  - Disable/guard "order-truth" journal posting paths (fail closed).
  - Ensure dispatch confirmation cannot double-call inventory dispatch.
- Manual journal namespace protection (reserved prefixes), fail closed.
  - Require an audit reason when dispatch overrides are applied (price/discount/tax).
- M00.2 (DONE): Make CODE-RED scripts executable in CI (no implicit `rg`) and wire gates into CI
  - CI runs `scripts/schema_drift_scan.sh` + `scripts/time_api_scan.sh` + `mvn verify`.
  - CI runs `scripts/triage_tests.sh` on failure.

Acceptance criteria
- No endpoint can create a journal entry with arbitrary amounts disconnected from domain state.
- Manual journal entry reference numbers are system-generated; caller-supplied `referenceNumber` is treated as
  a client idempotency key and blocked if it matches system namespaces.
- Dispatch confirmation is idempotent at the packaging slip boundary and cannot be double-applied via controller wiring.
- Dispatch overrides are impossible without an audit reason.
- CI gates include CODE-RED scripts (schema drift + time API scan) + tests; `scripts/verify_local.sh` mirrors CI locally.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchControllerTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/service/IntegrationCoordinatorTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesServiceTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/orchestrator/OrchestratorControllerIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/JournalEntryE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CreditDebitNoteIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/SettlementE2ETest.java`
- Scripts:
  - `scripts/verify_local.sh`
  - `scripts/triage_tests.sh`
  - `scripts/db_predeploy_scans.sql`
  - `scripts/schema_drift_scan.sh`

## EPIC 01 - Sales -> Inventory -> Dispatch -> Invoice -> Accounting (Dispatch-Truth Only)

Milestones
- M01.0 (DONE): Backorder slips must be dispatchable/invoiceable (invoice + journals are per packaging slip, not per order)
- M01.1 (DONE): Declare and enforce a single canonical dispatch/invoice path (SalesService.confirmDispatch)
- M01.2 (DONE): Fail closed on slipless invoice issuance and order-truth postings
- M01.3 (DONE): Lock idempotency markers at slip + journal reference boundaries
- M01.4 (DONE): Fail closed when `orderId` maps to multiple slips (require `packingSlipId`)
  - `SalesService.selectMostRecentSlip` must be replaced with explicit failure or deterministic explicit selection.

Acceptance criteria
- Invoice quantities, revenue/tax posting inputs, and COGS are derived from shipped quantities (dispatch truth).
- One packaging slip -> at most one invoice (DB + code).
- Repeated dispatch confirmation calls are no-ops after first success (no double revenue/COGS).
- Dispatch by `orderId` is deterministic or blocked when multiple slips exist.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/DispatchConfirmationIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/FactoryPackagingCostingIT.java`
- DB scans:
  - `scripts/db_predeploy_scans.sql` (dispatched slips missing links; invoices missing journals)

## EPIC 02 - Purchasing / Supplier Intake (Operational Leniency, Enterprise Safety)

Milestones
- M02.1 (DONE): GRN/invoice reconciliation gates (scan/report) and period close guardrails
- M02.2: Supplier invoice posting idempotency + reference uniqueness enforcement

Acceptance criteria
- Supplier invoices are unique and locked per (company, supplier, invoice number).
- Period close fails (or requires explicit manual accrual) if un-invoiced GRNs exist.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PeriodCloseLockIT.java`
- DB scans:
  - `scripts/db_predeploy_scans.sql`

## EPIC 03 - Manufacturing/WIP + Packaging Canonicalization (Bulk -> Size SKUs)

Scope note:
- Stabilization (Scope A): keep existing packing flows/endpoints and harden them (idempotency + AccountingFacade-only
  posting + timezone correctness).
- Full canonicalization (Scope B): variants/BOM hard cutover is Plan B and is tracked in
  `docs/CODE-RED/full-v1-cutover-plan.md`.

Milestones
- M03.A (Scope A): Route all factory postings through AccountingFacade (no direct AccountingService posting)
- M03.B (Scope A): Add request-level idempotency + deterministic references for:
  - `/api/v1/factory/production/logs/*`
  - `/api/v1/factory/packing-records/*`
  - `/api/v1/factory/pack/*`
- M03.C (Scope A): Fix timezone boundary bugs (CompanyClock everywhere; producedAt parsing must not drift)
- M03.D (Scope A): Add retry + concurrency tests for packing/production so double-clicks/timeouts cannot double-consume stock

Acceptance criteria
- Existing manufacturing endpoints remain available, but retries are safe:
  - no duplicate batches/movements/journals on retry
  - deterministic references per business event
- WIP/packing journals always balance and follow the posting boundary (AccountingFacade-owned).
- Company timezone is canonical for producedAt/entryDate (no server-time drift).
- Bulk batch identity is preserved (parent/child batch linkage remains auditable).

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_ManufacturingWipCostingTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_BulkPackagingCrossModuleTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/CompleteProductionCycleTest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/FactoryPackagingCostingIT.java`

## EPIC 04 - HR / Payroll Canonicalization (Single Path)

Milestones
- M04.0 (DONE): Enforce payroll run idempotency on the public payroll API (set and use `payroll_runs.idempotency_key`)
- M04.1 (DONE): Canonical payroll run creation and idempotency key (company + runType + period)
- M04.2 (DONE): Posting owned by AccountingFacade only; disable/route alternate posting paths

Acceptance criteria
- Payroll run cannot be duplicated for the same scope (idempotent).
- Once posted/paid, recalculation fails closed.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PayrollBatchPaymentIT.java`

## EPIC 05 - Manual Journal Policy + Period Locks (Fail Closed)

Milestones
- M05.1 (DONE): Manual journal entry API must not accept caller-supplied reference numbers (system-generated only).
- M05.2 (DONE): Manual journal idempotency is concurrency-safe (reserve-first idempotency mapping).
- M05.3: Memo/why required for manual entries.
- M05.4: AccountingEventStore is explicitly not relied upon for temporal truth
  - Remove/demote “temporal truth / replay” claims from docs and reporting paths.
  - Closed-period reporting uses period-end snapshots (journals + snapshots are truth).

Acceptance criteria
- Manual entries are system-referenced only (no user-supplied reference numbers); period lock enforced.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/JournalEntryE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PeriodCloseLockIT.java`

## EPIC 06 - India Leniency Centralized (Explicit Policy)

Milestones
- M06.1: Operational policy object and explicit audit hooks for leniency paths

Acceptance criteria
- All leniency is explicit, audited, and cannot corrupt ledger/idempotency/tenant isolation.

Evidence artifacts
- Docs:
  - `docs/CODE-RED/decision-log.md`

## EPIC 07 - Timezone + Business Date Canonicalization

Milestones
- M07.1 (DONE): Replace scattered LocalDate.now()/ZoneId.systemDefault() in business logic with CompanyClock
  - CostAllocationService month windows + ReportService monthlyProductionCosts
  - StatementService aging date fallback
  - Entity defaults: PartnerSettlementAllocation, PackingRecord, InventoryAdjustment, InventoryMovementEvent
- M07.2 (DONE): Add time API guard in CI and verify_local
  - `scripts/time_api_scan.sh`, wired into `scripts/verify_local.sh` and required for CI parity

Acceptance criteria
- Business dates (invoice date, journal entry date, payroll period, month boundaries) use company timezone consistently.

Evidence artifacts
- Search evidence:
  - `rg -n \"ZoneId\\.systemDefault\\(\" erp-domain/src/main/java`
  - `scripts/time_api_scan.sh`

## EPIC 08 - Schema Convergence / Drift Elimination

Milestones
- M08.1: Add convergence migration(s) and deterministic backfills
- M08.2: Enforce scan -> clean -> constrain rollout discipline

Acceptance criteria
- Fresh install schema == prod schema; drift patterns are detected and blocked pre-deploy.

Evidence artifacts
- Scripts:
  - `scripts/schema_drift_scan.sh`
  - `scripts/db_predeploy_scans.sql`

## EPIC 09 - Deploy Gates + Observability + Rollback Discipline

Milestones
- M09.0 (DONE): Wire CODE-RED scripts into CI and make them robust on ubuntu-latest (no implicit `rg` dependency)
- M09.1: Pre-deploy scan gate (must be zero violations)
- M09.2: Rollback plan documented and rehearsed

Acceptance criteria
- Deploy is blocked if invariant scan fails.
- Rollback is documented and executable without ad hoc DB edits.

## Deploy Gates / DO-NOT-SHIP
- Must pass: `cd erp-domain && mvn -B -ntp verify` (CI gate today).
- Must pass: `bash scripts/verify_local.sh`.
- Must be clean: run every query in `scripts/db_predeploy_scans.sql`; any returned rows = NO-SHIP.
- NO-SHIP if unresolved:
  - Mutating/nondeterministic slip lookup by `orderId` (`/api/v1/dispatch/order/{orderId}` selects "most recent" and can create slips).
  - Orchestrator order status updates to SHIPPED/DISPATCHED without canonical dispatch invariants (must be disabled or routed).
  - Any ZoneId/systemDefault or LocalDate.now business-date usages remaining.

Evidence artifacts
- Docs:
  - `docs/CODE-RED/hydration.md`
