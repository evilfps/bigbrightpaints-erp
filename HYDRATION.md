# HYDRATION

## Completed Epics
- Epic 03: branch `epic-03-production-stock`, tip `3f2370c38c0152153369507159e5ae26ca1fa048`.
- Epic 04: branch `epic-04-p2p-ap`, tip `c5dd42334a397b1137d821bd81f50b1504debca4`.
- Epic 05: branch `epic-05-hire-to-pay`, tip `dd1589c00634f9a122ebc9d35caf5114ada1f561`.
- Epic 06: branch `epic-06-admin-security`, tip `dabaeebc8de027491f0974050032bb86afbee5cc`.
- Epic 07: branch `epic-07-performance-scalability`, tip `96c0c71c0d751f3767cfbfb43e970842da9112b5`.
- Epic 08: branch `epic-08-reconciliation-controls`, tip `afe04b5561d9d6510d61bce58640da2dfbec5010`.
- Epic 09: branch `epic-09-operational-readiness`, tip `ca3851aea88ca5b791e65b896a1419a741283c49`.
- Epic 10: branch `epic-10-cross-module-traceability`, tip `c94755d70bcb5ba452ae64ddd7d8a6b96b50d392`.
- Epic 10 (onboarding integrity): branch `epic-10-onboarding-integrity`, tip `cbec6d3`.

## Repo / Worktree State
- Worktree: `/home/realnigga/Desktop/CLI_BACKEND_epic04`
- Branch: `debug-04-module-deep-debug` (Task 04 M6 code committed, tip `0613c48`)
- Dirty: no

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS; final gates).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30804 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test` (PASS; Tests run 24, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test` (PASS; Tests run 14, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT,DispatchConfirmationIT,LandedCostRevaluationIT,RevaluationCogsIT,ReconciliationControlsIT test` (PASS; Tests run 8, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test` (PASS; Tests run 18, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT,PeriodCloseLockIT test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test` (PASS; Tests run 12, Failures 0, Errors 0, Skipped 0).
- `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).

## Warnings / Notes
- Checkstyle baseline warnings (30804) persisted with failOnViolation=false.
- Endpoint inventory mismatch: openapi has endpoints missing from endpoint_inventory.tsv; inventory-only includes `/api/integration/health` (see evidence log).
- Alias handler drift flagged for `AccountingController#recordDealerReceipt` (cascade-reverse vs receipts/dealer path in endpoint inventory scan).
- Idempotency verification flagged for opening stock import and raw material intake (see Task 01 M2 list).
- Gap checklist flagged CSV opening stock import tests, raw material intake journal linkage tests, orchestrator trigger linkage tests, and dealer portal scoping tests.
- Authenticated-only endpoints remain for orchestrator health/traces and packing endpoints; security review required.
- Deprecated endpoints ledger updated with proof requirements; no removals executed.
- Task 03 M1 UNKNOWNs: unallocated receipt flow drift risk, dispatch idempotency marker usage, payroll payment artifacts linkage.
- `mvn test` warnings about negative balance and invalid company ID surfaced in M1 logs.
- `openapi.json` newline-only change reverted per contract policy.
- Task 03 M2 warnings: negative balances/invalid company ID format in fixtures; dispatch debit/credit accounts not configured (COGS postings skipped).
- Task 03 M3 assertion list + sample outputs captured; `mvn test` warnings (negative balances/invalid company ID format) and focused test warnings (dispatch debit/credit accounts not configured) persisted.
- Task 03 final gates completed; `openapi.json` newline-only change reverted after test runs.
- Task 04 M1 rerun after statement range update; `openapi.json` newline-only change reverted per contract policy.
- Task 04 M1 logs include API evidence for dealer ledger, statement/aging, and invoice list/detail.
- Task 04 M2 logs include API evidence for supplier statement/aging and inventory valuation/reconciliation; `openapi.json` newline-only change reverted after tests.
- Task 04 M3 logs include production movement reference evidence; `openapi.json` newline-only change reverted after tests.
- Task 04 M4 logs include payroll run status transition evidence; `openapi.json` newline-only change reverted after tests.
- Task 04 M5 logs include RBAC evidence for admin/dealer portal read-only enforcement and cross-company rejection.
- Task 04 M5 fixture warnings (negative balances, invalid company ID format) persisted; `openapi.json` newline-only change reverted after tests.
- Task 04 M6 trace endpoint LOB streaming error fixed by mapping audit records to DTOs; orchestrator trace/health now admin-only with evidence logs captured.
- Task 04 M6 fixture warnings (negative balances, invalid company ID format) persisted; `openapi.json` newline-only change reverted after tests.
- Task 04 final gates completed; focused module suites all green; `openapi.json` newline-only change reverted after final runs.

## Resume Instructions (Post Epic 10)
1. Task 04 final gates complete on `debug-04-module-deep-debug` (code tip `0613c48`); ready to push and deliver completion report.

## Update 2026-01-11 (Task 04 M1 rerun)
- Branch: `debug-04-module-deep-debug`
- Tip: `516ee00`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test` (PASS; Tests run 24, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Continue Task 04 M2 (Purchasing/AP + Inventory deep debug) on `debug-04-module-deep-debug`.
  2. Run required gates + focused tests, capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: M2 <summary>`.

## Update 2026-01-11 (Task 04 M2 Purchasing/P2P + Inventory deep debug)
- Branch: `debug-04-module-deep-debug`
- Tip: `7435d72`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test` (PASS; Tests run 14, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT,DispatchConfirmationIT,LandedCostRevaluationIT,RevaluationCogsIT,ReconciliationControlsIT test` (PASS; Tests run 8, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Continue Task 04 M3 (Factory/Production deep debug) on `debug-04-module-deep-debug`.
  2. Run required gates + focused tests, capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: M3 <summary>`.

## Update 2026-01-11 (Task 04 M3 Factory/Production deep debug)
- Branch: `debug-04-module-deep-debug`
- Tip: `0c6f360`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test` (PASS; Tests run 18, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Continue Task 04 M4 (HR/Payroll deep debug) on `debug-04-module-deep-debug`.
  2. Run required gates + focused tests, capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: M4 <summary>`.

## Update 2026-01-11 (Task 04 M4 HR/Payroll deep debug)
- Branch: `debug-04-module-deep-debug`
- Tip: `04adbbe`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT,PeriodCloseLockIT test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Continue Task 04 M5 (Admin/Auth/Dealer portal deep debug) on `debug-04-module-deep-debug`.
  2. Run required gates + focused tests, capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: M5 <summary>`.

## Update 2026-01-11 (Task 04 M5 Admin/Auth/Dealer portal deep debug)
- Branch: `debug-04-module-deep-debug`
- Tip: `479ce1b`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test` (PASS; Tests run 12, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Continue Task 04 M6 (Orchestrator/outbox deep debug) on `debug-04-module-deep-debug`.
  2. Run required gates + focused tests, capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: M6 <summary>`.

## Update 2026-01-11 (Task 04 M6 Orchestrator/outbox deep debug)
- Branch: `debug-04-module-deep-debug`
- Tip: `252594e`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Run Task 04 final gates + all focused module suites.
  2. Capture logs, update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit `debug-04: final gates`.

## Update 2026-01-11 (Task 04 final gates)
- Branch: `debug-04-module-deep-debug`
- Tip: `10651e8`
- Dirty: no
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 214, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,DispatchConfirmationIT,DealerLedgerIT,SettlementE2ETest,GstInclusiveRoundingIT test` (PASS; Tests run 24, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,ProcureToPayE2ETest,SupplierStatementAgingIT,ReconciliationControlsIT test` (PASS; Tests run 14, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=InventoryGlReconciliationIT,DispatchConfirmationIT,LandedCostRevaluationIT,RevaluationCogsIT,ReconciliationControlsIT test` (PASS; Tests run 8, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,FactoryPackagingCostingIT,CompleteProductionCycleTest,WipToFinishedCostIT test` (PASS; Tests run 18, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT,PayrollBatchPaymentIT,PeriodCloseLockIT test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AuthHardeningIT,MfaControllerIT,AdminUserSecurityIT test` (PASS; Tests run 12, Failures 0, Errors 0, Skipped 0).
  - `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,CommandDispatcherTest,IntegrationCoordinatorTest test` (PASS; Tests run 13, Failures 0, Errors 0, Skipped 0).
- Notes:
  - `openapi.json` newline-only change reverted per contract policy.
- Resume instructions:
  1. Push branch `debug-04-module-deep-debug`.
  2. Deliver Task 04 completion report with log paths and summaries.

## Update 2026-01-11 (Task 05 M1 Reconciliation controls)
- Branch: `debug-05-reconciliation-period-controls`
- Tip: pending commit (M1 reconciliation controls)
- Dirty: yes (pre-commit)
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 215, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=ReconciliationControlsIT,InventoryGlReconciliationIT test` (PASS; Tests run 5, Failures 0, Errors 0, Skipped 0).
- Notes:
  - Evidence logs captured under `docs/ops_and_debug/LOGS/20260111T075611Z_task05_M1_*` and appended to `docs/ops_and_debug/EVIDENCE.md`.
  - Fixture warnings persisted (invalid company ID format, negative balances, dispatch accounts not configured).
  - `openapi.json` regenerated during tests and restored to repository state.
- Resume instructions:
  1. Commit M1 changes with message `debug-05: M1 <summary>`.
  2. Continue Task 05 M2 (period lock/close/reopen controls) on `debug-05-reconciliation-period-controls`.
  3. Run required gates + focused `PeriodCloseLockIT`, capture logs, update evidence/stabilization/hydration.

## Update 2026-01-11 (Task 05 M2 Period lock/close controls)
- Branch: `debug-05-reconciliation-period-controls`
- Tip: pending commit (M2 period close controls)
- Dirty: yes (pre-commit)
- Commands run:
  - `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
  - `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30807 violations).
  - `mvn -f erp-domain/pom.xml test` (PASS; Tests run 215, Failures 0, Errors 0, Skipped 4).
  - `mvn -f erp-domain/pom.xml -Dtest=PeriodCloseLockIT test` (PASS; Tests run 3, Failures 0, Errors 0, Skipped 0).
- Notes:
  - Evidence logs captured under `docs/ops_and_debug/LOGS/20260111T081000Z_task05_M2_*` and appended to `docs/ops_and_debug/EVIDENCE.md`.
  - Fixture warnings persisted (invalid company ID format, negative balances, dispatch accounts not configured).
  - `openapi.json` regenerated during tests and restored to repository state.
- Resume instructions:
  1. Commit M2 changes with message `debug-05: M2 <summary>`.
  2. Continue Task 05 M3 (reconciliation evidence pack) on `debug-05-reconciliation-period-controls`.
  3. Run required gates, capture logs, update evidence/stabilization/hydration.
