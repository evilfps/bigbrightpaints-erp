# CODE-RED Stabilization Plan (V1)

Constraints (non-negotiable):
- No new features. No experiments. No refactors unless required for correctness/idempotency.
- Production code is source of truth; every change must be enforced by tests + invariants (fail closed).

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
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CreditDebitNoteIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/SettlementE2ETest.java`
- Scripts:
  - `scripts/verify_local.sh`
  - `scripts/triage_tests.sh`
  - `scripts/db_predeploy_scans.sql`
  - `scripts/schema_drift_scan.sh`

## EPIC 01 - Sales -> Inventory -> Dispatch -> Invoice -> Accounting (Dispatch-Truth Only)

Milestones
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
- M05.1: Reserved reference prefixes list enforced for manual entries
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
- M07.1: Replace scattered LocalDate.now()/ZoneId.systemDefault() in business logic with CompanyClock

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
- M09.1: Pre-deploy scan gate (must be zero violations)
- M09.2: Rollback plan documented and rehearsed

Acceptance criteria
- Deploy is blocked if invariant scan fails.
- Rollback is documented and executable without ad hoc DB edits.

Evidence artifacts
- Docs:
  - `docs/CODE-RED/hydration.md`
