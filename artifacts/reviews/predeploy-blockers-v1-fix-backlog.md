# predeploy-blockers-v1 Fix Backlog (Post Full Commit Review)

## Coverage confirmation
- Full commit coverage complete: `312/312` commits in `origin/main..predeploy-blockers-v1`.
- Commit ledger with quality status: `artifacts/reviews/predeploy-blockers-v1-commit-ledger.tsv`.
- Codebase-aware remediation sequencing and impact map:
  - `artifacts/reviews/predeploy-blockers-v1-remediation-playbook.md`

## Priority fix queue

1. **P0 - Orchestrator idempotency state persistence** (`db22d046bbb5`)
- Fix:
  - Persist `markSuccess` / `markFailed` updates explicitly in `OrchestratorIdempotencyService`.
  - Add negative tests: failed command transitions to `FAILED`; replay allows deterministic retry policy.
- Evidence file:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/OrchestratorIdempotencyService.java:88`

2. **P1 - Purchase return integrity and payable-state sanity** (`b6554e3bd6c4`)
- Fix:
  - Validate return quantity against purchase-line remaining returnable quantity.
  - Prevent outstanding from dropping below zero; normalize status transitions on full/partial returns.
- Evidence files:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java:805`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java:992`
- Review comment (2026-02-09, step fix):
  - Added purchase-line returned quantity tracking and hard validation against remaining returnable quantity before journal posting.
  - Added fail-closed guard when return amount exceeds payable outstanding; no stock/journal mutation proceeds when violated.
  - Status transition is now deterministic: fully returned + zero outstanding => `VOID`; partial returns => `PARTIAL`; payment-only zero-outstanding remains `PAID`.
  - Regression coverage added in `PurchasingServiceTest` and `PurchaseReturnIdempotencyRegressionIT`.

3. **P1 - Reports accounting math correctness** (`5ead674c0797`)
- Fix:
  - Correct statement aggregation by account normal balance and reporting sign conventions.
  - Ensure cash-flow sections are classified and not collapsed into operating-only netting.
- Evidence files:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java:107`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java:344`
- Review comment (2026-02-09, step fix):
  - Replaced debit-minus-credit aggregation with account normal-balance aggregation so liability/revenue/equity totals follow enterprise sign conventions.
  - Cash-flow now classifies movement into operating/investing/financing by counterpart account characteristics rather than forcing all net cash movement into operating.
  - Added integration proof in `CR_Reports_CashFlow_NotZeroByConstructionIT` covering P&L signs, balance-sheet signs, and sectioned cash-flow.

4. **P1 - Inventory adjustment API contract alignment** (`cc4c796d9733`)
- Fix:
  - Require `idempotencyKey` at DTO/schema/controller boundary, or implement server-generated fallback with compatibility contract.
- Evidence files:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/InventoryAdjustmentService.java:96`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/dto/InventoryAdjustmentRequest.java:17`
- Review comment (2026-02-09, step fix):
  - `InventoryAdjustmentRequest.idempotencyKey` is now required at DTO schema level.
  - Controller now resolves header/body idempotency first, then validates the resolved request object via Bean Validation, preserving header fallback without schema/runtime mismatch.
  - Added focused controller tests for missing key, header/body mismatch, header fallback, and post-resolution validation path.

5. **P1 - Dealer onboarding side-effect ordering** (`24ef1303c60e`, `e240e9a56944`)
- Fix:
  - Emit credential email only after successful commit (transaction synchronization/outbox).
- Evidence file:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/DealerService.java:112`
- Review comment (2026-02-09, step fix):
  - Credential email dispatch moved to `afterCommit` synchronization so rollback paths do not emit user credentials for non-persisted dealers.
  - Added service-level regression tests proving no send-before-commit and no send-on-rollback behavior.

6. **P2 - Fail-fast on duplicate journal reference mappings** (`3e4fa3af54da`)
- Fix:
  - Do not silently pick latest mapping when duplicates exist; fail closed with explicit diagnostics.
- Evidence file:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java:66`

## Inherited high-risk hardening (active at head)

1. **P1 inherited - cascade reversal must not partially succeed**
- Fix:
  - In `cascadeReverseRelatedEntries`, do not swallow per-entry failures when in transactional cascade mode; fail the request and roll back all reversals, or return explicit partial-failure contract and block workflow completion.
  - Add regression tests where one related entry reversal fails after primary reversal attempt; assert invariant-preserving behavior.
- Evidence files:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java:3019`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java:3069`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java:3092`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java:173`
- Review comment (2026-02-09, step fix):
  - Implemented fail-closed behavior: related-entry reversal errors now abort cascade reversal with context (`cascadePrimaryEntryId`, `cascadeRelatedEntryId`) instead of being logged and ignored.
  - Added E2E regression test to prove rollback atomicity when an invalid related entry id is supplied.
  - Validation command: `mvn -B -ntp -Dtest=JournalEntryE2ETest#journalCascadeReversal_ReversesRelatedEntriesAndRestoresBalances+journalCascadeReversal_FailsClosedAndRollsBackPrimary test`.
  - Fix commit: `e7820148`.

2. **P1 inherited - temporal trial-balance sign mapping is inconsistent with snapshot/report paths**
- Fix:
  - Replace `TemporalBalanceService.getTrialBalanceAsOf` open-period debit/credit side calculation with shared normalization used by snapshot/report pipeline (`debitNormal` + normalized balance mapping).
  - Add endpoint parity tests to assert `/api/v1/accounting/trial-balance/as-of` and `/api/v1/reports/trial-balance?date=...` produce consistent sides for liability/equity/revenue accounts.
- Evidence files:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TemporalBalanceService.java:153`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java:577`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java:452`
- Review comment (2026-02-09, step fix):
  - Implemented normalization parity in `TemporalBalanceService` using shared debit/credit mapping semantics used by snapshot/report logic.
  - Added integration regression proof in `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_PeriodCloseSnapshotsTest.java` (`trialBalanceAsOf_openPeriod_matchesReportTrialBalanceSignConventions`).
  - Validation command: `mvn -B -ntp -Dtest=CR_PeriodCloseSnapshotsTest,CR_Reports_AsOfBalancesStable_AfterLatePostingIT test`.

## Mandatory post-fix verification
- `bash scripts/verify_local.sh`
- Targeted regression tests for orchestrator idempotency, purchase returns, reports, inventory-adjustment API compatibility, dealer onboarding rollback behavior.
- Cross-fix dependency and blast-radius matrix:
  - `artifacts/reviews/predeploy-blockers-v1-remediation-playbook.md`
