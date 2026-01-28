
# CODE-RED Stabilization Plan (V1)

Constraints (non-negotiable):
- No new features. No experiments. No refactors unless required for correctness/idempotency.
- Production code is source of truth; every change must be enforced by tests + invariants (fail closed).

## Verified Repo Findings (as of 2026-01-28)

These are repo-verified facts and blockers that must be addressed by the EPIC milestones below.

Verified (in this repo)
- Dispatch by `orderId` is nondeterministic when multiple slips exist (selects "most recent" and only warns):
  `SalesService.selectMostRecentSlip` and `SalesService.confirmDispatch`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:738`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1372`).
- Payroll run creation is not idempotent on the public payroll API: `/api/v1/payroll/runs` uses
  `PayrollService.createPayrollRun` which never sets `idempotency_key`, so duplicates are possible
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/controller/HrPayrollController.java:72`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java:73`).
- Accounting event store exists but is not wired: `AccountingEventStore` has no call sites
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/AccountingEventStore.java:52`).
- CI currently gates on schema drift scan + time API scan + tests
  (`.github/workflows/ci.yml:1`).
- CODE-RED scripts exist but hard-depend on `rg` (missing in some envs):
  `scripts/schema_drift_scan.sh:23`, `scripts/triage_tests.sh:27`.

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

Corrections vs earlier audit text (repo reality)
- The CODE-RED docs/scripts are present here (`docs/CODE-RED/stabilization-plan.md:1`, `scripts/verify_local.sh:1`,
  `scripts/db_predeploy_scans.sql:1`), but the drift/triage scripts currently assume `rg` is available
  (`scripts/schema_drift_scan.sh:23`, `scripts/triage_tests.sh:27`).
- "`SalesService.createOrder` not transactional / no idempotency handling" is not true in this code: it's wrapped in a
  `TransactionTemplate` and catches `DataIntegrityViolationException`
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:350`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:355`).
- "`SalesService.cancelOrder` is not transactional" is not true: it is transactional
  (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:600`).
- Manual journal namespace protection exists, but it likely doesn't block company-prefixed invoice refs like
  `BBP-INV-...` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceNumberService.java:41`,
  `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:94`).
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
- M00.2 (BLOCKER): Make CODE-RED scripts executable in CI (no implicit `rg`) and wire gates into CI
  - `scripts/schema_drift_scan.sh`, `scripts/triage_tests.sh`, `scripts/db_predeploy_scans.sql` must run in CI.
  - `.github/workflows/ci.yml` must fail if script gates fail.

Acceptance criteria
- No endpoint can create a journal entry with arbitrary amounts disconnected from domain state.
- Manual journal entry reference numbers are system-generated; caller-supplied `referenceNumber` is treated as
  a client idempotency key and blocked if it matches system namespaces.
- Dispatch confirmation is idempotent at the packaging slip boundary and cannot be double-applied via controller wiring.
- Dispatch overrides are impossible without an audit reason.
- CI gates include CODE-RED scripts (schema drift + predeploy scans + verify_local).

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

Milestones
- M03.0: Define per-product packaging variants + BOM (hard cutover)
  - Add product_packaging_variants + product_packaging_components tables.
  - Variants and BOM are required for packing (fail closed).
  - Canonical flow: `docs/CODE-RED/packaging-flow.md`.
- M03.1: Hard cutover packing algorithm to bulk -> size SKUs
  - Packing is batch-based, deterministic, idempotent, and uses BOM.
  - Remove legacy packaging size mapping endpoints and code paths.
- M03.2: Route all factory postings through AccountingFacade (no direct AccountingService posting)
- M03.3: Fix timezone boundary bugs (CompanyClock everywhere)

Acceptance criteria
- Packing converts bulk liters to size SKUs using per-product variants + BOM (no legacy fallback).
- Packing is deterministic and idempotent; bulk batch identity is preserved.
- WIP journals always balance; no posting bypasses accounting policy.
- Month boundary calculations use company timezone, not server timezone.

Evidence artifacts
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/CR_BulkPackingVariantIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/CR_PackagingBOMRoundingIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/CostAllocationVariancePolicyIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/WipToFinishedCostIT.java`

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
- M05.2: Memo/why required for manual entries.
- M05.3: Accounting event store is either wired or explicitly removed from claims
  - Wire `AccountingEventStore.recordJournalEntryPosted` inside `AccountingService.createJournalEntry` or remove replay claims.

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
  - `scripts/time_api_scan.sh`, wired in `.github/workflows/ci.yml` and `scripts/verify_local.sh`

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
- M09.0 (BLOCKER): Wire CODE-RED scripts into CI and make them robust on ubuntu-latest (no implicit `rg` dependency)
- M09.1: Pre-deploy scan gate (must be zero violations)
- M09.2: Rollback plan documented and rehearsed

Acceptance criteria
- Deploy is blocked if invariant scan fails.
- Rollback is documented and executable without ad hoc DB edits.

## Deploy Gates / DO-NOT-SHIP
- Must pass: `cd erp-domain && mvn -B -ntp verify` (CI gate today).
- Must pass (after fixing `rg` dependency): `bash scripts/verify_local.sh`.
- Must be clean: run every query in `scripts/db_predeploy_scans.sql`; any returned rows = NO-SHIP.
- NO-SHIP if unresolved:
  - Payroll run idempotency on public API (`PayrollService.createPayrollRun` missing `idempotency_key`).
  - Manual journal collision hole with company-prefixed invoice refs (`BBP-INV-...` vs reserved prefixes).
  - Nondeterministic dispatch by `orderId` when multiple slips exist.
  - Any ZoneId/systemDefault or LocalDate.now business-date usages remaining.

Evidence artifacts
- Docs:
  - `docs/CODE-RED/hydration.md`
