# Task Packet

Ticket: `TKT-ERP-STAGE-030`
Slice: `SLICE-03`
Primary Agent: `refactor-techdebt-gc`
Reviewers: `qa-reliability`
Lane: `w3`
Branch: `tickets/tkt-erp-stage-030/refactor-techdebt-gc`
Worktree: `/home/realnigga/Desktop/orchestrator_erp_worktrees/_tmp_orch_cleanrepo_worktrees/TKT-ERP-STAGE-030/refactor-techdebt-gc`

## Objective
Resolve Stage-029 full-suite regressions on async-loop-predeploy-audit parity lane

## Agent Write Boundary (Enforced)
- `erp-domain/src/main/java/`
- `erp-domain/src/test/java/`
- `docs/`

## Requested Focus Paths
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_BulkPackagingCrossModuleTest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_DealerReceiptSettlementAuditTrailTest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_PurchasingToApAccountingTest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CreditDebitNoteIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CriticalAccountingAxesIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/PeriodCloseLockIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackingImportedCatalogPackagingIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BusinessLogicRegressionTest.java`

## Required Checks Before Done
- `bash ci/check-architecture.sh`
- `deferred-full-suite-on-integration: executed after SLICE-01 and SLICE-02 merge`

## Reviewer Contract
- Review-only agents do not commit code.
- Add one review file per reviewer under `tickets/<id>/slices/<slice>/reviews/`.
- Mark review status as `approved` only with concrete evidence.

## Shipability Bar
- The patch must be minimal, deterministic, and test-backed.
- Do not change behavior outside explicit scope without evidence and rationale.
- If any safety invariant is uncertain, fail closed and document blocker with evidence.

## Agent Prompt (Copy/Paste)
```text
You are `refactor-techdebt-gc`.
Implement this slice with minimal safe patching and proof-backed output.

Required output:
- identity
- files_changed
- commands_run
- harness_results
- residual_risks
- blockers_or_next_step
```
