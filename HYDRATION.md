# HYDRATION

## Overnight Runner State
- Branch: `accounting-correctness-v1`
- Current epic/milestone pointer: `tasks/task-00.md → EPIC 01 → Milestone 01` (pending)
- Last commit SHA: `25673232fd12ae5b8490df154a89cdd575cfd593`
- Next actions: expand `tasks/task-00.md` per cross-module mandate; commit/push plan; start EPIC 01 / Milestone 01 implementation + tests.
- Working tree status: pre-existing diffs present (unrelated); avoid touching unrelated files.

## Current State
- Worktree: `/home/realnigga/Desktop/CLI_BACKEND_epic04`
- Branch: `accounting-correctness-v1`
- Current milestone pointer: `tasks/task-00.md → EPIC 01 → Milestone 01` (pending: dispatch confirm idempotency)
- Working tree: pre-existing diffs present; proceeding without touching unrelated changes.

## Async Verify
- Command: `nohup bash -lc 'cd erp-domain && mvn -B -ntp verify' > /tmp/task00-verify.log 2>&1 & echo $! > /tmp/task00-verify.pid`
- PID: `39125`
- Log: `/tmp/task00-verify.log`
- Status: FINISHED (started 2026-01-25T01:45:24+05:30)
- Last observed: BUILD SUCCESS; Tests run: 394, Failures: 0, Errors: 0, Skipped: 4.

## Triage Commands
- First failing test in log: `grep -nE "FAILURE|ERROR|Failed" /tmp/task00-verify.log`
- Surefire TXT scan: `grep -nH -E "FAILURE|ERROR|Caused by" erp-domain/target/surefire-reports/*.txt`
- Surefire XML scan: `grep -nH -E "<failure|<error" erp-domain/target/surefire-reports/*.xml`

## Completed Milestones (with commit SHAs)
- EPIC 00 / Milestone 00 — Baseline async verify (PASS): `025eb146ee99712b6dabd3ddd5becac697237f60` (verify + hydration kickoff), `1034d5ff3eea8a62b6baa8f748015f177a35c2a3` (record baseline state).
- EPIC 00 / Milestone 02 — Tighten invariant coverage (PASS): `25673232fd12ae5b8490df154a89cdd575cfd593`.

## Open Findings (bugs / security issues / logic flaws)
- HIGH — Inventory accounting domain events appear unused (risk: future double-posting if wired later): `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/event/InventoryMovementEvent.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/event/InventoryValuationChangedEvent.java`.
- HIGH — `journal_reference_mappings` does not enforce uniqueness on `(company_id, canonical_reference)` but repository assumes single-row Optional (risk: runtime failure): `erp-domain/src/main/resources/db/migration/V88__journal_reference_mappings.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalReferenceMappingRepository.java`.
- MEDIUM — GST-inclusive rounding deltas can be misclassified as “discount” in legacy journal/invoice flows (risk: wrong discount postings): `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesJournalService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceService.java`.
- MEDIUM — `InventoryAccountingEventListener` uses `LocalDate.now()` instead of company timezone / event date for valuation re-posting (period correctness risk): `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`.
- MEDIUM — Tenant guard: `CompanyContextFilter.validateCompanyAccess(...)` allows company selection for unauthenticated requests and for non-`UserPrincipal` principals (requires audit of public endpoints): `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`.
- LOW — Sales dispatch posting uses invoice number as a journal reference (canonical reference is order-number-based); safe today but increases idempotency complexity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java:1751`.
- LOW/MEDIUM — Duplicate dispatch confirmation entry points and multiple inventory dispatch implementations increase drift risk:
  - Endpoints: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java` (`/api/v1/sales/dispatch/confirm`) and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java` (`/api/v1/dispatch/confirm`)
  - Inventory flow variants: `FinishedGoodsService.markSlipDispatched(...)` vs `FinishedGoodsService.confirmDispatch(...)` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`)

## Decisions Log
- Treat `POST /api/v1/sales/dispatch/confirm` (`SalesService.confirmDispatch(...)`) as the authoritative cross-module flow for shipped-quantity accounting (AR/Revenue/Tax + COGS + invoice creation).
- Keep changes “stabilization only”: fixes and tests, no new endpoints/workflows.
- Prefer strengthening invariants/tests over widening tolerances (posting tolerances remain strict).
- Proceed with Task 00 despite pre-existing worktree diffs; avoid unrelated edits and isolate milestone changes.
- Baseline async verify passed; Milestone 01 triage not triggered.
- Milestone 02 tests failed due to LazyInitialization in `ErpInvariantsSuiteIT`; fix by fetching AR journal reference via repository.
- Milestone 02 assertions added for dispatch linkage, AR reference uniqueness, and GST tax accounts.

## Test Status Log
- 2026-01-24: `cd erp-domain && mvn -B -ntp verify` (PASS) — Tests run: 394, Failures: 0, Errors: 0, Skipped: 4; JaCoCo gates met.
- 2026-01-25: `cd erp-domain && mvn -B -ntp verify` (PASS) — Tests run: 394, Failures: 0, Errors: 0, Skipped: 4; JaCoCo gates met.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp verify' > /tmp/task00-verify.log 2>&1 & echo $! > /tmp/task00-verify.pid` (PASS) — PID 27360; Tests run: 394, Failures: 0, Errors: 0, Skipped: 4.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp verify' > /tmp/task00-verify.log 2>&1 & echo $! > /tmp/task00-verify.pid` (PASS) — PID 39125; Tests run: 394, Failures: 0, Errors: 0, Skipped: 4.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test' > /tmp/task00-m02-tests-1.log 2>&1 & echo $! > /tmp/task00-m02-tests-1.pid` (FAIL) — PID 30407; Error: `ErpInvariantsSuiteIT.orderToCash_goldenPath` LazyInitializationException.
- 2026-01-25: `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT#orderToCash_goldenPath test` (FAIL) — LazyInitializationException (rerun 1).
- 2026-01-25: `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT#orderToCash_goldenPath test` (FAIL) — LazyInitializationException (rerun 2).
- 2026-01-25: `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT#orderToCash_goldenPath test` (PASS) — Post-fix rerun 1.
- 2026-01-25: `cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT#orderToCash_goldenPath test` (PASS) — Post-fix rerun 2.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test' > /tmp/task00-m02-tests-2.log 2>&1 & echo $! > /tmp/task00-m02-tests-2.pid` (PASS) — PID 35096; Tests run: 19, Failures: 0, Errors: 0, Skipped: 0.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test' > /tmp/task00-m02-tests-3.log 2>&1 & echo $! > /tmp/task00-m02-tests-3.pid` (PASS) — PID 36465; Tests run: 19, Failures: 0, Errors: 0, Skipped: 0.
- 2026-01-25: `nohup bash -lc 'cd erp-domain && mvn -B -ntp -Dtest=ErpInvariantsSuiteIT,CriticalAccountingAxesIT test' > /tmp/task00-m02-tests-4.log 2>&1 & echo $! > /tmp/task00-m02-tests-4.pid` (PASS) — PID 37799; Tests run: 19, Failures: 0, Errors: 0, Skipped: 0.

## Next Actions (explicit)
1. Begin EPIC 01 / Milestone 01: audit `SalesService.confirmDispatch(...)` for partial-state recovery.
2. Add/extend idempotency tests for dispatch confirm.
3. Run `cd erp-domain && mvn -B -ntp -Dtest=OrderFulfillmentE2ETest,ErpInvariantsSuiteIT test` and start async verify.

## Historical (prior work references)
- Epic 03: branch `epic-03-production-stock`, tip `3f2370c38c0152153369507159e5ae26ca1fa048`.
- Epic 04: branch `epic-04-p2p-ap`, tip `c5dd42334a397b1137d821bd81f50b1504debca4`.
- Epic 05: branch `epic-05-hire-to-pay`, tip `dd1589c00634f9a122ebc9d35caf5114ada1f561`.
- Epic 06: branch `epic-06-admin-security`, tip `dabaeebc8de027491f0974050032bb86afbee5cc`.
- Epic 07: branch `epic-07-performance-scalability`, tip `96c0c71c0d751f3767cfbfb43e970842da9112b5`.
- Epic 08: branch `epic-08-reconciliation-controls`, tip `afe04b5561d9d6510d61bce58640da2dfbec5010`.
- Epic 09: branch `epic-09-operational-readiness`, tip `ca3851aea88ca5b791e65b896a1419a741283c49`.
- Epic 10: branch `epic-10-cross-module-traceability`, tip `c94755d70bcb5ba452ae64ddd7d8a6b96b50d392`.
