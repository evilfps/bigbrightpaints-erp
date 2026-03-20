# R2 Checkpoint

## Scope
- Feature: `ERP-20 Packet 3: accounting source-of-truth cleanup`
- PR: `#126`
- Review candidate SHA: `71f7a390956701b196ddaddd3681d0ab66ee398f`
- PR branch: `feature/erp-stabilization-program--erp-20`
- Rebuild branch: `feature/erp-stabilization-program--erp-20--accounting-truth`
- Why this is R2: this packet hard-cuts accounting and reporting truth on privileged accounting/report/runtime surfaces, retires the public `/api/v1/accounting/reports/**` aliases, removes compatibility constructors and wrapper-heavy legacy wiring from `AccountingController`, changes fail-fast behavior for canonical report requests, and refreshes the OpenAPI plus frontend/backend handoff docs for a money-state-sensitive contract.

## Risk Trigger
- Triggered by runtime-bearing changes under `modules/accounting`, `modules/company`, and `modules/reports` where stale aliases, compatibility bridges, or fallback-heavy request handling would create incorrect accounting/report truth.
- Contract surfaces affected: `GET /api/v1/reports/trial-balance`, `GET /api/v1/reports/profit-loss`, `GET /api/v1/reports/balance-sheet`, `GET /api/v1/reports/aged-debtors`, `AccountingController`, `ModuleGatingInterceptor`, and the retired alias family `/api/v1/accounting/reports/**`.
- Failure mode if wrong: callers keep relying on retired public aliases, report payloads silently manufacture defaults instead of failing fast, GST reporting accepts posted invoice lines without canonical taxable amounts, or accounting/report runtime guards drift from the single canonical route family.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Approval status: `pending PR review and merge approval`
- Basis: the bounded ERP-20 proof is green on review candidate `71f7a390956701b196ddaddd3681d0ab66ee398f`, and the remaining merge gate is explicit human approval after CI and review threads settle on PR `#126`.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes accounting truth and report/public contract behavior on high-risk runtime paths, so the R2 gate remains explicit human approval on the GitHub PR after proof is replayable from committed sources.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the ERP-20 packet commit(s), restore the prior `origin/main` contract state, refresh `openapi.json`, and rerun the focused accounting/report proof plus OpenAPI verification before republishing the pre-ERP-20 head.
- Rollback trigger:
  - any live traffic still depends on `/api/v1/accounting/reports/**`
  - report requests no longer fail fast on missing canonical payloads
  - GST report generation accepts taxed posted invoice lines without canonical taxable amounts
  - OpenAPI or frontend handoff docs diverge from the merged canonical route surface

## Expiry
- Valid until: `2026-03-27`
- Re-evaluate if: any additional runtime-bearing accounting/report/company change lands above review candidate `71f7a390956701b196ddaddd3681d0ab66ee398f`, the focused proof is rerun on a different candidate SHA, or the bounded packet grows beyond accounting source-of-truth cleanup.

## Residual Follow-up
- Explicit follow-up ticket: `ERP-31`
- Follow-up scope: additional hard-cut cleanup outside ERP-20, including adjacent control-plane/accounting convergence work that is not strictly required for the canonical report namespace cleanup.
- Why excluded here: ERP-20 is intentionally bounded to report/accounting source-of-truth cleanup, stale alias retirement, fallback removal, and matching stale test/doc cleanup.

## Verification Evidence
- Focused packet suite on review candidate `71f7a390956701b196ddaddd3681d0ab66ee398f`:
  - `cd erp-domain && mvn -B -ntp -Dtest=OpenApiSnapshotIT,ReportControllerContractTest,ReportControllerRouteContractIT,AccountingControllerActivityContractTest,AccountingControllerExportGovernanceContractTest,AccountingControllerJournalEndpointsTest,AccountingControllerIdempotencyHeaderParityTest,AccountingControllerPeriodCloseWorkflowEndpointsTest,AccountingControllerPeriodCostingMethodEndpointsTest,AccountingControllerReconciliationDiscrepancyEndpointsTest,TenantRuntimeEnforcementInterceptorTest,PerformanceBudgetIT,ModuleGatingInterceptorTest,JournalEntryServiceTest,SettlementServiceTest,ReportServiceInventoryAndGstTest,BalanceSheetReportQueryServiceTest,TS_RuntimeAccountingReplayConflictExecutableCoverageTest,AccountingServiceTest,AccountingServiceStandardJournalTest,IntegrationCoordinatorTest test`
  - result: `BUILD SUCCESS`
  - tests: `367 run, 0 failures, 0 errors, 0 skipped`
- Changed-files coverage proof on review candidate `71f7a390956701b196ddaddd3681d0ab66ee398f`:
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base db223fa2f7e2bfd304f6a2907775ddc6f6d26c54 --src-root erp-domain/src/main/java --threshold-line 0.95 --threshold-branch 0.90 --fail-on-vacuous`
  - result: `PASS`
  - summary: `line_covered=66`, `line_total=68`, `line_ratio=0.9705882352941176`, `branch_covered=35`, `branch_total=38`, `branch_ratio=0.9210526315789473`, `files_with_unmapped_lines=[]`
- Non-mutating OpenAPI verification on review candidate `71f7a390956701b196ddaddd3681d0ab66ee398f`:
  - `cd erp-domain && mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Dtest=OpenApiSnapshotIT test`
  - result: `BUILD SUCCESS`
  - tests: `3 run, 0 failures, 0 errors, 0 skipped`
- Hygiene proof:
  - `git diff --check`
  - result: clean
- Contract spot checks against refreshed `openapi.json`:
  - sha256: `9d8d147b607cbb4a4210876ed49c36c80a4e841272de38180ebc345630b9113c`
  - total paths: `308`
  - total operations: `369`
  - `/api/v1/reports/trial-balance` present
  - `/api/v1/accounting/reports/trial-balance` absent
  - canonical report namespace remains `/api/v1/reports/**` only

## Reviewer Notes
- Review should block any attempt to preserve `/api/v1/accounting/reports/**` as a supported public surface or to reintroduce `AccountingController` compatibility constructors, wrapper helpers, or default-manufacturing report fallbacks.
- The new GST fail-fast behavior is intentional: taxed posted invoice lines without canonical taxable amounts are now rejected, including the `unknown` invoice-reference path when an invoice number is unavailable.
- The period-close request/approve flow remains explicit and auditable; ERP-20 does not widen scope into supplier-ledger cleanup, portal split cleanup, or unrelated control-plane refactors.
