# Task 00 — Accounting Stabilization End‑to‑End (GST / Non‑GST)

This is the “no‑excuses” plan to make accounting stable, unified, and production‑ready without adding new business features.

## Work Scheduler (always-on)
- If async verify is RUNNING: pick a Safe Work Bucket item and proceed (tests/assertions, docs, endpoint inventory, security audit, posting call-site inventory, small refactors behind tests).
- If async verify FAILED: follow the failure triage discipline (capture first failing test + stack, inspect surefire, classify, rerun twice, minimal fix, restart async verify).
- If blocked: record blocker + evidence needed in `HYDRATION.md`, then immediately switch to another independent milestone/track.
- Maintain at least one active track at all times:
  1) correctness (code/tests), 2) audit/trace (cross-module mapping + evidence), 3) security/logic flaw hunting.

## Execution Contract (non‑negotiable for any agent)
- Start (and stay) on branch: `accounting-correctness-v1` (create if missing). Never work on `main` directly.
- No new business features, no new endpoints; only correctness, stability, unification, security, and docs.
- After every milestone:
  - Update `HYDRATION.md` with: milestone status, findings, decisions, test results, and commit SHA.
  - Run the milestone’s targeted tests + the full suite gate.
  - Commit changes (small, scoped commits) and push (no force push).
- Long tasks must run async (Codex Cloud rule): use the async procedure from `docs/codex-cloud-ci-debugging-plan.md`.
- Do not stop until Task 00 is complete: if blocked, record the blocker + evidence needed in `HYDRATION.md`, then move to the next independent milestone.

## Common Commands (repo-specific)
- Type-check (compile): `cd erp-domain && mvn -B -ntp -DskipTests compile`
- Lint (advisory Checkstyle): `cd erp-domain && mvn -B -ntp -DskipTests checkstyle:check`
- Targeted tests: `cd erp-domain && mvn -B -ntp -Dtest=ClassName1,ClassName2 test`
- Full suite gate (CI entrypoint): `cd erp-domain && mvn -B -ntp verify`
- Async full suite gate (Codex Cloud pattern):
  - `nohup bash -lc 'cd erp-domain && mvn -B -ntp verify' > /tmp/task00-verify.log 2>&1 & echo $! > /tmp/task00-verify.pid`
  - `tail -n 120 /tmp/task00-verify.log`
- Migration sanity (forces app context + Flyway in tests): `cd erp-domain && mvn -B -ntp -Dtest=ApplicationSmokeTest test`
- OpenAPI surface inventory (repo has ~200+ paths):
  - `python3 - <<'PY'\nimport json\nfor p in ['openapi.json','erp-domain/openapi.json']:\n  d=json.load(open(p))\n  print(p, 'paths=', len(d.get('paths',{})))\nPY`

## EPIC 00 — Baseline, Guardrails, Async Debugging Procedure
Objective:
- Establish a reproducible baseline, enforce invariant gates, and adopt the repeatable CI/debug workflow.

Scope:
- Debugging workflow: `docs/codex-cloud-ci-debugging-plan.md`
- Invariants/E2E suites:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CriticalAccountingAxesIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/GstInclusiveRoundingIT.java`

Milestones:

Milestone 00 — Branch + baseline full suite (async-friendly)
- Implementation steps:
  - Ensure branch: `git switch -c accounting-correctness-v1` (or `git switch accounting-correctness-v1`).
  - Run full suite gate (async if needed) and capture results into `HYDRATION.md`.
- Verify steps:
  - Confirm working tree is clean enough to reason about (no unrelated diffs).
- Validate steps:
  - Run: `cd erp-domain && mvn -B -ntp verify`
- Full suite gate:
  - Pass means: 0 failing tests; JaCoCo gates satisfied; no new flake.
- Rollback/recovery notes:
  - If the baseline is broken on this branch, stop code changes and follow `docs/codex-cloud-ci-debugging-plan.md`.

Milestone 01 — Failure triage discipline (if/when failures appear)
- Implementation steps:
  - Identify the first failing test and capture the full stack trace.
  - Inspect `erp-domain/target/surefire-reports/**` for all failing tests.
  - Classify failures (logic vs nondeterministic vs infra/config vs data/setup) before changing code.
- Verify steps:
  - Re-run the single failing test twice to confirm determinism.
- Validate steps:
  - Re-run the failing test only: `cd erp-domain && mvn -B -ntp -Dtest=<FailingTest> test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If multiple unrelated failures appear, isolate by reverting the most recent commit(s) and bisect.

Milestone 02 — Tighten invariant coverage (assertions first) **COMPLETED** — commit `25673232fd12ae5b8490df154a89cdd575cfd593`
- Implementation steps:
  - Extend existing suites (prefer extending over adding new suites) to assert:
    - No duplicate AR journals per dispatch reference.
    - Packaging slip links to invoice + AR journal + COGS journal after dispatch confirm.
    - GST input/output postings land in configured accounts (`CompanyAccountingSettingsService.requireTaxAccounts()`).
- Verify steps:
  - Run updated tests 3 times to rule out flake.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If new assertions expose legacy behavior not fixable yet, revert the assertion commit and record the scenario in `HYDRATION.md`.

## EPIC 01 — Cross‑Module Accounting Correctness (Inventory → Sales → Accounting)
Objective:
- Ensure shipped‑quantity workflow is correct and idempotent across BOTH dispatch entry points:
  - `POST /api/v1/sales/dispatch/confirm` → `SalesService.confirmDispatch(...)`
  - `POST /api/v1/dispatch/confirm` → `DispatchController.confirmDispatch(...)`

Scope:
- Dispatch + accounting:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/DispatchConfirmationIT.java`

Milestones:

Milestone 01 — Dispatch confirm idempotency + artifact completeness
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`confirmDispatch`, `computeDispatchLineAmounts`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java`
  - `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql` (canonical reference rules)
- What to search (ripgrep patterns):
  - `confirmDispatch\\(`, `markSlipDispatched`, `DISPATCH`, `inventory_movements`
  - `journalEntryId`, `cogsJournalEntryId`, `salesJournalEntryId`, `fulfillmentInvoiceId`
  - `JournalReferenceResolver`, `canonical_reference`, `journal_reference_mappings`
  - `idempotency`, `referenceId`, `reference_id`
- What evidence to record (HYDRATION finding entry + links):
  - Full call-chain maps for BOTH endpoints to the authoritative flow.
  - Artifact linkage map: packaging slip ↔ inventory movements ↔ invoice ↔ journals ↔ dealer ledger.
  - Current idempotency + uniqueness safeguards (code + DB constraints) for dispatch postings.
- Implementation steps:
  - Trace BOTH endpoints through controllers/services to the shared authoritative flow (document the call chain with file paths and method names).
  - Audit `SalesService.confirmDispatch(...)` for partial-state recovery:
    - If slip is already dispatched, ensure the call returns without side effects when artifacts exist.
    - If slip is dispatched but one of {invoice, AR journal, COGS journal} is missing, create only the missing artifact(s) without mutating inventory.
  - Validate packaging slip → inventory movements → invoice → journals → dealer ledger linkage (evidence map; no gaps).
  - Verify DB uniqueness/constraints for journal reference mappings and inventory movements that protect against duplicates.
  - Add/extend tests to cover retry behavior (same request twice).
- Verify steps:
  - After second call:
    - No additional `inventory_movements` rows of type `DISPATCH` for the same `referenceId`.
    - No new `journal_entries` rows for the same canonical reference.
    - Packaging slip + sales order posting markers are stable.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If a fix risks creating duplicates, prefer “fail closed with a clear message” and document the operator recovery step.

Milestone 02 — Endpoint equivalence: `/sales/dispatch/confirm` vs `/dispatch/confirm`
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
- What to search (ripgrep patterns):
  - `@PostMapping\\(\"/dispatch/confirm\"`, `@PostMapping\\(\"/sales/dispatch/confirm\"`
  - `confirmDispatch\\(`, `confirmDispatchInternal`, `confirmSlip`
  - `AccountingFacade.postCogsJournal`, `AccountingFacade.postSalesJournal`
- What evidence to record (HYDRATION finding entry + links):
  - Single authoritative flow used by both endpoints (exact method + call path).
  - Equivalence proof artifacts: inventory movement, invoice, AR journal, COGS journal, and ledger entries are identical.
- Implementation steps:
  - Ensure both controllers route through the SAME authoritative accounting flow and cannot diverge.
  - Ensure equivalence includes ledger updates and reference mappings (not just journal creation).
  - Add an E2E/integration test that calls BOTH endpoints for the same slip and asserts:
    - only one inventory dispatch,
    - only one AR journal,
    - only one COGS journal,
    - same invoice linkage.
- Verify steps:
  - Confirm `DispatchController.confirmDispatch(...)` cannot double-post even under retries.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=DispatchConfirmationIT,OrderFulfillmentE2ETest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If controller behavior must change, prefer internal refactor (shared service method) over endpoint changes.

Milestone 03 — COGS cost accuracy + traceability
- Implementation steps:
  - Ensure COGS journal uses the same unit costs recorded on `PackagingSlipLine.unitCost` and `InventoryMovement.unitCost`.
  - Ensure dispatch movements are linked to the COGS journal (`FinishedGoodsService.linkDispatchMovementsToJournal(...)`).
- Verify steps:
  - For a dispatch scenario:
    - Σ(cost) == Σ(shippedQty × unitCost) across slip lines.
    - Journal debits COGS and credits inventory valuation for exactly Σ(cost).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,RevaluationCogsIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If existing persisted costs are wrong, propose a one-off data repair plan (do not auto-run).

Milestone 04 — Partial dispatch + multiple slips per order
- Implementation steps:
  - Verify partial shipped quantities produce:
    - correct inventory movements,
    - correct invoice totals for shipped qty,
    - correct journals for shipped qty only.
  - Ensure multiple slips per order are handled deterministically (`SalesService.selectMostRecentSlip(...)`, `FinishedGoodsService.selectMostRecentSlip(...)`).
- Verify steps:
  - No over-posting when multiple slips exist; verify the selected slip is the one being posted.
- Validate steps:
  - Run existing E2E suites and extend as needed:
    - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If behavior changes for legacy data (multiple slips), document selection policy and add a regression test.

## EPIC A — Cross‑Module Trace Map + Evidence Pack
Objective:
- Build a concrete, evidence-backed map that traces business events across inventory → sales → invoice → accounting → ledgers → tax → settlements/returns.

Scope:
- Cross-module flows and reference mappings; documentation only (no new features).

Milestones:

Milestone A1 — Evidence map skeleton (entrypoints → services → tables → journals)
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/DealerLedgerService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceSettlementPolicy.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`
- What to search (ripgrep patterns):
  - `postSalesJournal`, `postCogsJournal`, `postSalesReturn`, `postPurchaseJournal`
  - `createJournalEntry`, `journal_reference_mappings`, `canonical_reference`
  - `applyPayment`, `settlement`, `PartnerSettlementAllocation`
  - `InventoryMovement`, `PackagingSlip`, `invoice_id`, `journal_entry_id`
- What evidence to record (HYDRATION finding entry + links):
  - Event-by-event table: dispatch, invoice issuance, payment, settlement, return.
  - For each event: entrypoint(s), services, repositories, tables, journals, idempotency keys, reference scheme.
- Implementation steps:
  - Create a trace-map document under `docs/` (e.g., `docs/cross-module-trace-map.md`) with the evidence table above.
  - Capture call chains with file paths + method names for each event.
- Verify steps:
  - Cross-check each entrypoint against the code path to ensure no missing services/repositories.
- Validate steps:
  - N/A (docs), but run a smoke suite to ensure no accidental breakage:
    - `cd erp-domain && mvn -B -ntp -Dtest=ApplicationSmokeTest,CriticalPathSmokeTest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone A2 — Golden path trace (dispatch → invoice → journals → ledger)
- What to read (start here):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java`
- What to search (ripgrep patterns):
  - `orderToCash`, `dispatch`, `invoice`, `dealer ledger`
- What evidence to record (HYDRATION finding entry + links):
  - Golden path trace with exact call chain + tables touched + journal entries produced.
- Implementation steps:
  - Add a “golden path” trace section to the `docs/` trace map with specific code references.
- Verify steps:
  - Ensure the golden path references match real call paths (no stale assumptions).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone A3 — Evidence pack: idempotency + reference schemes by event
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/util/SalesOrderReference.java`
  - `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`
- What to search (ripgrep patterns):
  - `idempotency`, `reference`, `canonical`, `unique`, `constraint`
- What evidence to record (HYDRATION finding entry + links):
  - For each event: idempotency keys + DB constraints that prevent duplicates (or gaps to fix later).
- Implementation steps:
  - Annotate the trace map with idempotency + canonical reference strategy per event.
- Verify steps:
  - Confirm idempotency keys align with DB uniqueness (or record mismatches).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC B — Idempotency & Duplicate Posting Hunt (DB + code)
Objective:
- Eliminate duplicate postings and ensure retries/partial failures are safe across dispatch, invoice, settlement, and return flows.

Scope:
- Idempotency keys, canonical references, DB constraints, and retry/partial-failure behavior.

Milestones:

Milestone B1 — Idempotency inventory (code + DB)
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceSettlementPolicy.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - DB migrations under `erp-domain/src/main/resources/db/migration/`
- What to search (ripgrep patterns):
  - `idempotency`, `reference`, `canonical_reference`, `unique`, `constraint`
  - `journal_reference_mappings`, `inventory_movements`, `settlement_allocation`
- What evidence to record (HYDRATION finding entry + links):
  - Inventory of idempotency mechanisms (keys, hashes, unique constraints) by module.
- Implementation steps:
  - Document all idempotency/uniqueness mechanisms and where they apply or are missing.
- Verify steps:
  - Cross-check repository assumptions vs. DB uniqueness constraints.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone B2 — Retry + partial failure regression tests
- What to read (start here):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/DispatchConfirmationIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/SettlementE2ETest.java`
- What to search (ripgrep patterns):
  - `retry`, `idempotent`, `dispatch`, `settlement`, `applyPayment`
- What evidence to record (HYDRATION finding entry + links):
  - Tests that demonstrate retries, reorders, and partial failures do not duplicate postings.
- Implementation steps:
  - Add regression tests that intentionally retry, reorder, and partially fail flows.
- Verify steps:
  - Confirm no duplicates in inventory movements or journals for the same canonical reference.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,DispatchConfirmationIT,SettlementE2ETest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone B3 — Duplicate posting hardening (minimal diffs)
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalReferenceMappingRepository.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
- What to search (ripgrep patterns):
  - `findByCanonicalReference`, `existsBy`, `referenceId`, `inventory_movements`
- What evidence to record (HYDRATION finding entry + links):
  - Before/after verification that duplicates are prevented by code or constraints.
- Implementation steps:
  - Tighten idempotency checks where duplicates can slip through (fail closed).
- Verify steps:
  - Confirm canonical reference uniqueness (code or DB).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=IdempotencyConflictRegressionIT,ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC C — Accounting Balance & Rounding Standardization (single source of truth)
Objective:
- Standardize rounding and balance checks across order/dispatch/invoice/journal paths with minimal diffs.

Scope:
- Rounding helpers, GST computation utilities, journal balance enforcement.

Milestones:

Milestone C1 — Rounding helper inventory + consolidation plan
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesJournalService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java`
- What to search (ripgrep patterns):
  - `round`, `setScale`, `HALF_UP`, `currency`, `tax`, `gst`
- What evidence to record (HYDRATION finding entry + links):
  - Inventory of rounding helpers + tax computations and where they diverge.
- Implementation steps:
  - Document rounding/tax helper usage and propose minimal-diff consolidation.
- Verify steps:
  - Ensure consolidation plan does not change behavior without tests.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=GstInclusiveRoundingIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone C2 — Balance + rounding assertions (property-style)
- What to read (start here):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/ErpInvariantsSuiteIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CriticalAccountingAxesIT.java`
- What to search (ripgrep patterns):
  - `balance`, `rounding`, `gst`, `discount`
- What evidence to record (HYDRATION finding entry + links):
  - Assertions that net + tax == gross and journals balance after rounding.
- Implementation steps:
  - Add property-style assertions (balance holds; net+tax=gross; discount only when explicit).
- Verify steps:
  - Run tests twice (flake guard) and confirm deterministic results.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone C3 — Standardize tax computation usage (minimal diff)
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesJournalService.java`
- What to search (ripgrep patterns):
  - `gst`, `tax`, `compute`, `discount`, `inclusive`
- What evidence to record (HYDRATION finding entry + links):
  - Single source of truth for tax/rounding with exact call sites.
- Implementation steps:
  - Consolidate behavior with minimal code diff; update tests if required.
- Verify steps:
  - Journals still balance with zero tolerance after consolidation.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=GstInclusiveRoundingIT,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC D — Business Logic Flaw Audit (bypass, tampering, invariants)
Objective:
- Proactively find business-logic bypasses and security/authorization holes that could corrupt accounting state.

Scope:
- Cross-company access, period lock bypass, dispatch/settlement/return invariants.

Milestones:

Milestone D1 — Cross-company tampering attempts (fail closed)
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`
  - Controllers under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**/controller`
- What to search (ripgrep patterns):
  - `X-Company-Id`, `CompanyContext`, `permitAll`, `hasAuthority`
- What evidence to record (HYDRATION finding entry + links):
  - Security test results that prove cross-company access is blocked.
- Implementation steps:
  - Create checklist-driven tests that attempt cross-company tampering via headers/context.
- Verify steps:
  - Confirm access denied (403/404) and no data leaked.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=*Security*Test,*Auth*Test test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone D2 — Period lock + posting bypass attempts
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
- What to search (ripgrep patterns):
  - `requireOpenPeriod`, `period`, `lock`, `override`
- What evidence to record (HYDRATION finding entry + links):
  - Tests that confirm postings cannot happen in locked periods without explicit override.
- Implementation steps:
  - Add failing tests for posting outside open period; fix by enforcing fail-closed behavior.
- Verify steps:
  - Confirm idempotent flows do not skip period checks incorrectly.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=AccountingPeriodLockIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone D3 — Dispatch confirm tampering + duplicate prevention
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
- What to search (ripgrep patterns):
  - `DISPATCHED`, `confirmDispatch`, `inventory_movements`, `journalEntryId`
- What evidence to record (HYDRATION finding entry + links):
  - Tests that ensure dispatch confirm cannot be applied twice or with missing artifacts.
- Implementation steps:
  - Add tests that attempt re-dispatch and confirm idempotent recovery without duplication.
- Verify steps:
  - Ensure no duplicate inventory movements/journals for same slip.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,DispatchConfirmationIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone D4 — Settlement duplication + over-application guard
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceSettlementPolicy.java`
- What to search (ripgrep patterns):
  - `settlement`, `applyPayment`, `outstanding`, `idempotency`
- What evidence to record (HYDRATION finding entry + links):
  - Tests that prevent negative outstanding or duplicate settlement application.
- Implementation steps:
  - Add tests for settlement duplication and over-application; fix fail-closed.
- Verify steps:
  - Ensure outstanding never goes negative and settlements are idempotent.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=SettlementE2ETest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone D5 — Returns without matching dispatch cost layers
- What to read (start here):
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
- What to search (ripgrep patterns):
  - `return`, `COGS`, `inventoryAdjustment`, `cost layer`
- What evidence to record (HYDRATION finding entry + links):
  - Tests that prevent returns without matching dispatch layers (fail closed).
- Implementation steps:
  - Add tests that attempt return without dispatch cost layers; enforce rejection or safe recovery.
- Verify steps:
  - Confirm return journaling requires a valid dispatch linkage.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=SalesReturnCreditNoteE2EIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC E — Data Integrity & Constraint Alignment
Objective:
- Align DB constraints with repository assumptions for critical accounting/inventory tables.

Scope:
- Journal references, settlement allocations, inventory movements, slip status transitions.

Milestones:

Milestone E1 — Constraint vs repository assumption review
- What to read (start here):
  - DB migrations under `erp-domain/src/main/resources/db/migration/`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalReferenceMappingRepository.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocationRepository.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovementRepository.java`
- What to search (ripgrep patterns):
  - `unique`, `constraint`, `index`, `findBy`, `Optional<`
- What evidence to record (HYDRATION finding entry + links):
  - Mismatch list: where code assumes uniqueness but DB doesn’t enforce (or vice versa).
- Implementation steps:
  - Compare DB constraints/migrations vs repository assumptions for key tables.
- Verify steps:
  - Identify safe constraint additions or code hardening opportunities.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone E2 — Align constraints or code (safe, minimal)
- What to read (start here):
  - `erp-domain/src/main/resources/db/migration/` (relevant migrations)
  - Affected repository/service files from E1
- What to search (ripgrep patterns):
  - `unique`, `constraint`, `duplicate`, `idempotent`
- What evidence to record (HYDRATION finding entry + links):
  - Migration plan with rollback notes OR code-side safe tolerance.
- Implementation steps:
  - Where mismatched: either strengthen code to tolerate safely or add safe constraints/migrations with rollback plan.
- Verify steps:
  - Confirm fix eliminates duplicate/ambiguous lookups.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=IdempotencyConflictRegressionIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC F — Regression Matrix (systematic)
Objective:
- Encode the run order and enforce repeatability (flake guard) at the end of Task 00.

Scope:
- Regression suite ordering + duplicate run checks.

Milestones:

Milestone F1 — Regression run order + evidence recording
- What to read (start here):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/*`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/*`
- What to search (ripgrep patterns):
  - `E2E`, `Regression`, `Invariants`, `Performance`
- What evidence to record (HYDRATION finding entry + links):
  - Recorded run order, results, and any flake observations.
- Implementation steps:
  - Encode and execute the regression run order; record results in `HYDRATION.md`.
- Verify steps:
  - Confirm each suite passes in the declared order once.
- Validate steps:
  - Use the commands in EPIC 08 Milestone 01 (same order).
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

Milestone F2 — Flake guard (rerun key suites)
- What to read (start here):
  - Same suites as F1
- What to search (ripgrep patterns):
  - `@RepeatedTest`, `flake`, `nondeterministic`
- What evidence to record (HYDRATION finding entry + links):
  - Proof of two clean runs for key suites.
- Implementation steps:
  - Re-run key suites twice to detect nondeterminism.
- Verify steps:
  - Same test results across both runs.
- Validate steps:
  - Repeat the EPIC 08 Milestone 01 validate commands.
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`

## EPIC 02 — GST & Non‑GST Correctness (Sales + Returns)
Objective:
- Make GST/non‑GST rules consistent across:
  - order creation (`SalesService.mapOrderItems(...)`)
  - dispatch confirmation (`SalesService.confirmDispatch(...)`, `SalesService.computeDispatchLineAmounts(...)`)
  - invoice issuance (`InvoiceService.issueInvoiceForOrder(...)` and dispatch-created invoices)
  - sales journals (`SalesJournalService.postSalesJournal(...)`, `AccountingFacade.postSalesJournal(...)`)
  - returns (`SalesReturnService.processReturn(...)`, `AccountingFacade.postSalesReturn(...)`)

Scope:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesJournalService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesReturnService.java`

Milestones:

Milestone 01 — GST treatments: `NONE` / `PER_ITEM` / `ORDER_TOTAL`
- Implementation steps:
  - Validate `SalesService.mapOrderItems(...)` behavior for:
    - `GstTreatment.NONE` (tax totals 0; no tax postings).
    - `GstTreatment.PER_ITEM` (per-line tax, inclusive/exclusive).
    - `GstTreatment.ORDER_TOTAL` (order-level tax allocated to lines; `gstRoundingAdjustment` captured).
  - Add/extend tests for each treatment mode.
- Verify steps:
  - Verify computed totals are stable and do not drift between order → dispatch → invoice → journal.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=CriticalAccountingAxesIT,GstInclusiveRoundingIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If totals change in fixtures, update fixtures only when the new behavior is demonstrably more correct.

Milestone 02 — Inclusive vs exclusive + rounding edge cases (no phantom discounts) **COMPLETED** — commit `20f4af1292ff743783b1257b3017b44b81bec1d2`
- Implementation steps:
  - Ensure GST-inclusive math does not create “discount” from rounding deltas in `InvoiceService` / `SalesJournalService`.
  - Align discount/tax extraction rules used across invoice/journal paths.
- Verify steps:
  - For GST-inclusive orders: `net + gst == gross` after rounding, and discount is only present when actually applied.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=GstInclusiveRoundingIT,OrderFulfillmentE2ETest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If edge cases remain, document them in `HYDRATION.md` with concrete numbers and add regression tests.

Milestone 03 — Returns/credit notes correctness (GST + discount + COGS reversal) **COMPLETED** — commit `8bc3edc3edcd8abf703b24c5d1f20be2c7caf1f4`
- Implementation steps:
  - Ensure sales returns:
    - reverse revenue + output GST using invoice line fields,
    - reverse discounts correctly (contra handling),
    - restock inventory and reverse COGS using dispatch cost layers.
  - Add/extend tests for partial returns and mixed discounts.
- Verify steps:
  - Credit note journal balances and uses expected accounts:
    - Dr Revenue/Tax, Cr AR
  - COGS reversal journal is: Dr Inventory, Cr COGS.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=SalesReturnCreditNoteE2EIT,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If return semantics touch persisted state, require a backward-compatible migration plan before landing.

Milestone 04 — Mixed tax rates + zero-rated items **COMPLETED** — commit `52fd14ea969e15c25dd23df961d43fc433181ab6`
- Implementation steps:
  - Validate that mixed `SalesOrderItem.gstRate` values are handled consistently across order + dispatch + invoice + journal.
  - Ensure zero-rated items do not create GST postings.
- Verify steps:
  - Output GST postings equal Σ(line tax) and remain 0 when all rates are 0.
- Validate steps:
  - Extend existing suites (preferred): `CriticalAccountingAxesIT` and/or add a focused E2E test class.
  - Run: `cd erp-domain && mvn -B -ntp -Dtest=CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If data model cannot represent an edge case (e.g., shipping), document as non-goal (no new features).

## EPIC 03 — Procure‑to‑Pay Correctness (Purchasing → Inventory → Accounting → Settlements)
Objective:
- Ensure purchase invoicing, returns, and settlements are correct, exact-balance (zero tolerance), and idempotent.

Scope:
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (purchase + purchase return)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (supplier payments/settlements)
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/ProcureToPayE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/SettlementE2ETest.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/PurchaseReturnIdempotencyRegressionIT.java`

Milestones:

Milestone 01 — Purchase tax allocation + exact balance (zero tolerance) **COMPLETED** — commit `8ec59e6963ae985433b4db915d796e5cd740ea94`
- Implementation steps:
  - Audit tax paths and rounding:
    - `AccountingFacade.postPurchaseJournal(...)` enforces `inventoryTotal + taxTotal == totalAmount` exactly.
    - Ensure upstream (PurchasingService) constructs amounts with consistent scale/rounding so equality holds.
  - Add regression tests for “taxProvided” vs “taxComputed” paths.
- Verify steps:
  - Verify: Dr Inventory + Dr Input GST == Cr AP exactly (no tolerance).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ProcureToPayE2ETest,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If legacy fixtures break, update fixtures to reflect corrected accounting and document the delta.

Milestone 02 — Purchase returns + supplier settlements (idempotency + integrity) **COMPLETED** — commit `f74478a9eec6ee1897a8d62a3ad211041e1f7f55`
- Implementation steps:
  - Ensure purchase returns reverse inventory + input GST correctly and are idempotent (`recordPurchaseReturn(...)` checks existing movements).
  - Ensure supplier settlements:
    - enforce idempotency keys and constraints,
    - reconcile allocations to invoice/purchase outstanding updates.
- Verify steps:
  - Confirm `PartnerSettlementAllocation` uniqueness rules match DB constraints (`V48__...`, `V102__...`).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=SettlementE2ETest,PurchaseReturnIdempotencyRegressionIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - Prefer stronger idempotency checks + explicit errors over silent tolerance.

## EPIC 04 — Bug Finder / Logic Flaw Audit / Security Review
Objective:
- Find and fix bugs + security/logic flaws grounded in current code: authz boundaries, injection risks, privilege escalation, business-logic bypass, idempotency gaps, and tampering vectors.

Scope:
- Auth/tenant:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/SecurityConfig.java`
  - Controllers under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**/controller`
- High-risk operations:
  - dispatch confirm endpoints
  - `/api/v1/accounting/**` receipts, settlements, credit/debit notes, reversals

Milestones:

Milestone 01 — Tenant boundary & authorization audit (fail closed) **COMPLETED** — commit `33834dc7d85cbe4f11dd142c99724d989c779ebd`
- Implementation steps:
  - Enumerate permitAll endpoints from `SecurityConfig` and confirm they cannot be used to access company-scoped data via `X-Company-Id`.
  - Audit special authorities like `hasAuthority('dispatch.confirm')` in `DispatchController`.
  - Add security tests for cross-company access attempts (expect 403/404).
- Verify steps:
  - Confirm company scoping is enforced for authenticated flows and cannot be bypassed by changing `X-Company-Id`.
- Validate steps:
  - Run existing security/auth tests (adjust list based on repo):
    - `cd erp-domain && mvn -B -ntp -Dtest=*Security*Test,*Auth*Test test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If service accounts need access, introduce explicit, audited allow‑lists; avoid blanket allows.

Milestone 02 — Business-logic bypass + idempotency audit (tamper-proof) **COMPLETED** — commit `560c731dc6b6c32973fb1536095cf28050641369`
- Implementation steps:
  - Attempt to reproduce:
    - double posting via repeated dispatch confirms,
    - settlement duplication via idempotency key reuse,
    - inconsistent state via partial failures.
  - Add tests that assert duplicates are rejected or idempotently returned.
- Verify steps:
  - Verify DB constraints match code assumptions (e.g., `journal_reference_mappings` canonical uniqueness).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,IdempotencyConflictRegressionIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - Prefer tightening constraints + clear error messages over adding silent tolerances.

## EPIC 05 — Unified Accounting Standards (one way to post, one way to round, one way to reference)
Objective:
- Eliminate divergent accounting logic and ensure all modules follow the same standards for rounding, references, posting, and idempotency.

Scope:
- Posting entry points audit:
  - `erp-domain/src/main/java/**` call sites of `AccountingService.createJournalEntry(...)`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`
- Reference unification:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/util/SalesOrderReference.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/JournalReferenceResolver.java`
  - `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`

Milestones:

Milestone 01 — Inventory all posting paths + divergences **COMPLETED** — commit `cc6c9b982e778ef32efd8826e7e7578abc6095fd`
- Implementation steps:
  - Produce a list of every call site that posts journals and classify it:
    - uses `AccountingFacade` (preferred),
    - calls `AccountingService.createJournalEntry(...)` directly (review),
    - produces accounting-like side effects without journals (bug).
  - Decide, per call site, whether to:
    - migrate to `AccountingFacade`, or
    - keep as-is with explicit documented rationale (rare).
- Verify steps:
  - Confirm no two paths can post the same business event twice under retry.
- Validate steps:
  - Run a representative slice:
    - `cd erp-domain && mvn -B -ntp -Dtest=CriticalPathSmokeTest,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If refactor risk is high, break into multiple commits and keep behavior identical until invariant tests prove otherwise.

Milestone 02 — Canonical reference consistency + uniqueness **COMPLETED** — commit `077510684e7dadda7f55fdad6a9975124ff5b437`
- Implementation steps:
  - Fix the canonical reference uniqueness gap:
    - either enforce uniqueness at DB level for `(company_id, canonical_reference)`, or
    - change repository/query to tolerate multiple rows safely.
  - Ensure sales journals use canonical references consistently (`INV-<order>`) and mapping covers legacy `SALE-`.
- Verify steps:
  - Confirm `JournalReferenceResolver.findExistingEntry(...)` cannot throw due to duplicates.
- Validate steps:
  - Add/extend a regression test in `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/`.
  - Run: `cd erp-domain && mvn -B -ntp -Dtest=IdempotencyConflictRegressionIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If migration required, keep it backward-compatible and document rollback steps (no destructive migrations without plan).

Milestone 03 — Rounding standardization (single source of truth) **COMPLETED** — commit `b0f34b6a1a7fbebf1432a7fb222e5a503989c71b`
- Implementation steps:
  - Consolidate currency rounding semantics (2dp, HALF_UP) to a single helper (keep minimal diff).
  - Ensure all GST computations use the same intermediate scale strategy (avoid drift between order/invoice/journal).
- Verify steps:
  - Journals still balance with zero tolerance after rounding changes.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=GstInclusiveRoundingIT,CriticalAccountingAxesIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If consolidation changes behavior, re-scope to “behavior-preserving refactor” first, then correctness.

Milestone 04 — Inventory event journaling: either wire safely or keep disabled explicitly **COMPLETED** — commit `b1384dc7630ea4c85cd1abab5a935c4eb3c6e53b`
- Implementation steps:
  - `InventoryAccountingEventListener` exists but events are not published today.
  - Decide and document:
    - keep disabled and ensure no future wiring can cause double-posting, OR
    - wire it with strict idempotency and remove overlapping manual postings.
- Verify steps:
  - Add a guard test that ensures enabling event publishing does not double-post an existing flow.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=InventoryGlReconciliationIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - Prefer “explicitly disabled with tests” over “half-wired”.

## EPIC 06 — API Surface, Data Integrity, and Performance (218-path reality check)
Objective:
- Audit the large API surface (OpenAPI ~200+ paths), reduce redundancy safely (no breaking changes), and strengthen integrity/performance.

Scope:
- OpenAPI artifacts: `openapi.json`, `erp-domain/openapi.json`
- Controllers: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**/controller`
- Performance tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/performance/PerformanceBudgetIT.java`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/performance/PerformanceExplainIT.java`

Milestones:

Milestone 01 — Endpoint inventory + duplication map **COMPLETED** — commit `02973ee7ba26ef6ccbc1c560d75b495b6e1f1746`
- Implementation steps:
  - Generate and commit an endpoint inventory table (in docs, not code) grouping paths by module and marking duplicates/aliases.
  - Identify high-risk duplicates (two endpoints performing same mutation, e.g., dispatch confirm).
- Verify steps:
  - Ensure every mutation endpoint has clear idempotency/locking strategy and tenant enforcement.
- Validate steps:
  - Run smoke: `cd erp-domain && mvn -B -ntp -Dtest=CriticalPathSmokeTest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - No endpoint removal; only documentation + internal consolidation.

Milestone 02 — Data integrity audit for critical endpoints **COMPLETED** — commit `b2fcaa45e91faf0f693941d12c971dc4be1c2e4c`
- Implementation steps:
  - For critical endpoints (dispatch, purchase, settlements, returns), confirm:
    - company scoping,
    - input validation,
    - DB constraints match code assumptions,
    - transactional boundaries avoid partial writes.
  - Add regression tests for any discovered integrity bugs.
- Verify steps:
  - Re-run identified critical endpoints twice (idempotency).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,OrderFulfillmentE2ETest,ProcureToPayE2ETest,SettlementE2ETest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If a bug requires schema change, introduce migration + backfill plan; do not silently “ignore” bad data.

Milestone 03 — Performance + query plan budget **COMPLETED** — commit `009609bf63a59924218266aed1b3d715104e04cf`
- Implementation steps:
  - Run performance suites and fix any regression introduced during Task 00:
    - remove N+1 hotspots,
    - add missing indexes when justified by failing explain/budget tests,
    - avoid widening transactions.
- Verify steps:
  - Ensure changes do not loosen accounting correctness or isolation.
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=PerformanceBudgetIT,PerformanceExplainIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - Keep performance changes small and measurable; revert if risk outweighs benefit.

## EPIC 07 — Frontend Help Docs (How Accounting Works)
Objective:
- Produce concise, accurate docs that the frontend/help pages can use to explain accounting behavior.

Scope:
- New docs under `docs/` (exact path chosen in milestone; keep minimal).
- Must match actual code behavior in:
  - `SalesService.confirmDispatch(...)`
  - `AccountingFacade.*` posting methods
  - `TaxService.generateGstReturn(...)`
  - `SalesReturnService.processReturn(...)`

Milestones:

Milestone 01 — Write the accounting “mental model” docs **COMPLETED** — commit `e2be5a472e184fc04255164c30a3bfce0c37f586`
- Implementation steps:
  - Document:
    - O2C: SalesOrder → PackagingSlip → Dispatch → InventoryMovement → COGS Journal → AR/Revenue/Tax Journal → Invoice → Dealer ledger.
    - P2P: PurchaseOrder → GoodsReceipt → RawMaterialPurchase → RawMaterialMovement → Purchase Journal → Supplier ledger → Settlements.
    - GST modes and rounding rules (with concrete examples that match tests).
    - Returns: credit notes + inventory restock + COGS reversal.
    - Period lock rules and reversal policy.
  - Keep it frontend-friendly: headings, simple examples, glossary, and “what can go wrong” section.
- Verify steps:
  - Cross-check doc statements against tests (cite test classes as truth sources).
- Validate steps:
  - N/A (docs), but run a smoke suite to ensure no accidental breakage:
    - `cd erp-domain && mvn -B -ntp -Dtest=ApplicationSmokeTest,CriticalPathSmokeTest test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If docs drift from code, fix docs immediately; do not ship contradictory docs.

## EPIC 08 — Regression Matrix + Debug Sessions (No Domino Effect)
Objective:
- Prove fixes don’t break other modules; run a systematic regression matrix and capture outputs.

Scope:
- Suites:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/smoke/*`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/*`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/*`

Milestones:

Milestone 01 — Build + run the regression matrix (targeted first)
- Implementation steps:
  - Run and record (in `HYDRATION.md`) a fixed regression order:
    1) invariants → 2) sales/inventory E2E → 3) accounting E2E → 4) regression → 5) performance
  - For any failure: follow `docs/codex-cloud-ci-debugging-plan.md` and fix root cause (no patchy workarounds).
- Verify steps:
  - Confirm the same suite passes twice in a row (flake check).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT test`
  - `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,DispatchConfirmationIT test`
  - `cd erp-domain && mvn -B -ntp -Dtest=CriticalAccountingAxesIT,ProcureToPayE2ETest,SettlementE2ETest test`
  - `cd erp-domain && mvn -B -ntp -Dtest=BusinessLogicRegressionTest,IdempotencyConflictRegressionIT test`
  - `cd erp-domain && mvn -B -ntp -Dtest=PerformanceBudgetIT,PerformanceExplainIT test`
- Full suite gate:
  - `cd erp-domain && mvn -B -ntp verify`
- Rollback/recovery notes:
  - If a late fix breaks earlier milestones, revert the late fix and re-approach with smaller diffs.

Milestone 02 — Final “domino” gate (full suite twice)
- Implementation steps:
  - Run `mvn verify` twice (async if needed) and compare results; no new failures allowed.
- Verify steps:
  - Ensure no hidden nondeterminism (tests stable across two runs).
- Validate steps:
  - `cd erp-domain && mvn -B -ntp verify`
  - `cd erp-domain && mvn -B -ntp verify`
- Full suite gate:
  - Pass means: both runs green; JaCoCo gates satisfied; no flaky new failures.
- Rollback/recovery notes:
  - If flake appears, quarantine by fixing nondeterminism (time, ordering, concurrency) rather than disabling tests.

## Production Readiness Checklist (final gate)
- [ ] No new endpoints/features introduced; stabilization + unification only.
- [ ] Dispatch confirm idempotent across both endpoints; links slip ↔ invoice ↔ journals.
- [ ] Journals always balance (zero tolerance) for sales/purchases/returns/settlements/adjustments.
- [ ] GST return matches journal lines for configured GST input/output accounts.
- [ ] Period lock rules enforced; reversals behave correctly.
- [ ] Security: cross-company access attempts fail closed; high-risk endpoints require proper authority.
- [ ] Performance budgets pass (`PerformanceBudgetIT`, `PerformanceExplainIT`).
- [ ] Docs exist for frontend/help pages and match tests/code.
- [ ] Full suite passes twice: `cd erp-domain && mvn -B -ntp verify`
- [ ] `SCOPE.md` + `HYDRATION.md` updated with final decisions, findings, and commit SHAs.
