# Accounting Workflow Truth Map: Tests, Docs, and OpenAPI

## Folder Map

- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/controller`
  Purpose: controller route and contract truth.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service`
  Purpose: accounting business-rule truth.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/reports`
  Purpose: canonical report route and query truth.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite`
  Purpose: executable cross-module invariants.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting`
  Purpose: end-to-end workflow truth.
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered`
  Purpose: high-risk regression and prod-hardening coverage.
- `docs/*` and `openapi.json`
  Purpose: contract and review documentation truth.

## Workflow Proof Coverage

- journals and double-entry:
  - `AccountingServiceTest`
  - `JournalEntryServiceTest`
  - `TS_DoubleEntryMathInvariantTest`
  - `JournalEntryE2ETest`
- period close and reopen:
  - `AccountingControllerPeriodCloseWorkflowEndpointsTest`
  - `AccountingPeriodServiceTest`
  - `TS_PeriodCloseAtomicSnapshotTest`
  - `PeriodCloseLockIT`
- reconciliation:
  - `AccountingControllerReconciliationDiscrepancyEndpointsTest`
  - `ReconciliationServiceTest`
  - `TS_SubledgerControlReconciliationContractTest`
  - `ReconciliationControlsIT`
- P2P:
  - `TS_P2PSettlementRuntimeTest`
  - `ProcureToPayE2ETest`
  - `CR_PurchasingToApAccountingTest`
- O2C:
  - `TS_O2CDispatchCanonicalPostingTest`
  - `TS_O2CDispatchReplaySafetyTest`
  - `CR_SalesDispatchInvoiceAccounting`
- reports:
  - `ReportControllerContractTest`
  - `ReportControllerRouteContractIT`
  - `BalanceSheetReportQueryServiceTest`
  - `TrialBalanceReportQueryServiceTest`
  - `ProfitLossReportQueryServiceTest`
- exports and runtime:
  - `TS_RuntimeAccountingControllerExportCoverageTest`

## Contract Truth Files

- `openapi.json`
- `docs/endpoint-inventory.md`
- `docs/accounting-portal-endpoint-map.md`
- `docs/accounting-portal-frontend-engineer-handoff.md`
- `docs/code-review/flows/finance-reporting-audit.md`
- `docs/code-review/executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md`

## What Works

- route retirement for `/api/v1/accounting/reports/**` is locked by tests and OpenAPI assertions
- truthsuite and e2e already prove most of the important cross-module boundaries
- codered layer catches several high-risk parity issues

## Stale or Weak Proof

- likely ballast:
  - `AccountingServiceBenchmarkTest`
  - `PerformanceBudgetIT`
  - `PerformanceExplainIT`
- legacy-shaped:
  - `TS_P2PPurchaseAuditTrailRepositoryCompatibilityTest`
  - `CR_PayrollLegacyEndpointGatedIT`
  - `CR_FactoryLegacyBatchProdGatingIT`
- naming outlier:
  - `CR_SalesDispatchInvoiceAccounting` lacks the usual `Test` or `IT` suffix

## Missing Proof

- canonical cash-flow route/service coverage is still thin
- cash-flow export/audit proof is weaker than other financial reports
- docs still lag some runtime truths, especially around direct period close vs maker-checker canonical flow

## Review Hotspots

- `ReportControllerContractTest`
- `ReportControllerRouteContractIT`
- `TS_RuntimeAccountingControllerExportCoverageTest`
- `TS_PeriodCloseAtomicSnapshotTest`
- `TS_O2CDispatchCanonicalPostingTest`
- `TS_P2PSettlementRuntimeTest`
- `CR_Reports_AsOfBalancesStable_AfterLatePostingIT`
- `CR_Reports_CashFlow_NotZeroByConstructionIT`
