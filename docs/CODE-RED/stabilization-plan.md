# CODE-RED Stabilization Plan (V1)

Constraints (non-negotiable):
- No new features. No experiments. No refactors unless required for correctness/idempotency.
- Production code is source of truth; every change must be enforced by tests + invariants (fail closed).

## Verified Repo Findings (as of 2026-01-27)

These are repo-verified facts and blockers that must be addressed by the EPIC milestones below.

Verified (in this repo)
- Backorder/partial dispatch is release-blocking: `SalesService.confirmDispatch` reuses the order's existing invoice
  (`order.getFulfillmentInvoiceId`) and then hard-fails if totals differ, so a second/backorder slip can't be
  invoiced (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1405`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1864`).
  Inventory *does* create backorder slips (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java:920`).
- Payroll duplication risk is real on the public payroll API: `/api/v1/payroll/runs` uses
  `PayrollService.createPayrollRun` which never sets `idempotency_key`, so duplicates are possible
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/controller/HrPayrollController.java:72`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java:73`).
- Timezone bugs exist: `ZoneId.systemDefault()` used for month windows
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/CostAllocationService.java:65`) and
  business-date defaults use `LocalDate.now()` in multiple domain/event objects
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocation.java:100`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackingRecord.java:89`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryAdjustment.java:68`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/event/InventoryMovementEvent.java:90`).
- CI still only gates on tests: workflow runs `mvn verify` only (`.github/workflows/ci.yml:34`).

Corrections vs earlier audit text (repo reality)
- The CODE-RED docs/scripts are present here (`docs/CODE-RED/stabilization-plan.md:1`, `scripts/verify_local.sh:1`,
  `scripts/db_predeploy_scans.sql:1`), but the drift/triage scripts currently assume `rg` is available
  (`scripts/schema_drift_scan.sh:23`, `scripts/triage_tests.sh:27`).
- "`SalesService.createOrder` not transactional / no idempotency handling" is not true in this code: it's wrapped in a
  `TransactionTemplate` and catches `DataIntegrityViolationException`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:350`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:355`).
- Manual journal namespace protection exists, but it likely doesn't block company-prefixed invoice refs like
  `BBP-INV-...` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceNumberService.java:41`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:94`).

## EPIC 00 - Freeze Invariants + Kill Obvious Foot-Guns

Milestones
- M00.1 (THIS PR): Freeze invariants + remove obvious double-post/double-dispatch hazards
  - Disable/guard "order-truth" journal posting paths (fail closed).
  - Ensure dispatch confirmation cannot double-call inventory dispatch.
  - Manual journal namespace protection (reserved prefixes vs MANUAL-), fail closed.
  - Require an audit reason when dispatch overrides are applied (price/discount/tax).

Acceptance criteria
- No endpoint can create a journal entry with arbitrary amounts disconnected from domain state.
- Manual journal entry cannot use reserved system reference prefixes.
- Dispatch confirmation is idempotent at the packaging slip boundary and cannot be double-applied via controller wiring.
- Dispatch overrides are impossible without an audit reason.

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
- M01.0 (BLOCKER): Backorder slips must be dispatchable/invoiceable (invoice + journals are per packaging slip, not per order)
- M01.1: Declare and enforce a single canonical dispatch/invoice path (SalesService.confirmDispatch)
- M01.2: Fail closed on slipless invoice issuance and order-truth postings
- M01.3: Lock idempotency markers at slip + journal reference boundaries

Acceptance criteria
- Invoice quantities, revenue/tax posting inputs, and COGS are derived from shipped quantities (dispatch truth).
- One packaging slip -> at most one invoice (DB + code).
- Repeated dispatch confirmation calls are no-ops after first success (no double revenue/COGS).

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/DispatchConfirmationIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/FactoryPackagingCostingIT.java`
- DB scans:
  - `scripts/db_predeploy_scans.sql` (dispatched slips missing links; invoices missing journals)

## EPIC 02 - Purchasing / Supplier Intake (Operational Leniency, Enterprise Safety)

Milestones
- M02.1: GRN/invoice reconciliation gates (scan/report) and period close guardrails
- M02.2: Supplier invoice posting idempotency + reference uniqueness enforcement

Acceptance criteria
- Supplier invoices are unique and locked per (company, supplier, invoice number).
- Period close fails (or requires explicit manual accrual) if un-invoiced GRNs exist.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PeriodCloseLockIT.java`
- DB scans:
  - `scripts/db_predeploy_scans.sql`

## EPIC 03 - Manufacturing/WIP + Cost Allocation (Deterministic)

Milestones
- M03.1: Route all factory postings through AccountingFacade (no direct AccountingService posting)
- M03.2: Fix timezone boundary bugs (CompanyClock everywhere)

Acceptance criteria
- WIP journals always balance; no posting bypasses accounting policy.
- Month boundary calculations use company timezone, not server timezone.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/CostAllocationVariancePolicyIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/WipToFinishedCostIT.java`

## EPIC 04 - HR / Payroll Canonicalization (Single Path)

Milestones
- M04.0 (BLOCKER): Enforce payroll run idempotency on the public payroll API (set and use `payroll_runs.idempotency_key`)
- M04.1: Canonical payroll run creation and idempotency key (company + runType + period)
- M04.2: Posting owned by AccountingFacade only; disable/route alternate posting paths

Acceptance criteria
- Payroll run cannot be duplicated for the same scope (idempotent).
- Once posted/paid, recalculation fails closed.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PayrollBatchPaymentIT.java`

## EPIC 05 - Manual Journal Policy + Period Locks (Fail Closed)

Milestones
- M05.1: Reserved reference prefixes list enforced for manual entries (including company-prefixed invoice refs like `BBP-INV-...`)
- M05.2: Memo/why required for manual entries

Acceptance criteria
- Manual entries never collide with system namespaces; period lock enforced.

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
- M07.1: Replace scattered LocalDate.now()/ZoneId.systemDefault() in business logic with CompanyClock (CostAllocationService + entity defaults)

Acceptance criteria
- Business dates (invoice date, journal entry date, payroll period, month boundaries) use company timezone consistently.

Evidence artifacts
- Search evidence:
  - `rg -n \"ZoneId\\.systemDefault\\(\" erp-domain/src/main/java`

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
- M09.0 (BLOCKER): Wire CODE-RED scripts into CI and make them robust on ubuntu-latest (no implicit `rg` dependency)
- M09.1: Pre-deploy scan gate (must be zero violations)
- M09.2: Rollback plan documented and rehearsed

Acceptance criteria
- Deploy is blocked if invariant scan fails.
- Rollback is documented and executable without ad hoc DB edits.

Evidence artifacts
- Docs:
  - `docs/CODE-RED/hydration.md`
