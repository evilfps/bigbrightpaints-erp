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
- Branch: `debug-03-auditability-linkage` (Task 03 M1 complete, tip `d9ef08f`)
- Dirty: no

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30804 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 206, Failures 0, Errors 0, Skipped 4).

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

## Resume Instructions (Post Epic 10)
1. Task 03 M1 complete on `debug-03-auditability-linkage` at `d9ef08f`.
2. Run Task 03 M2: update invariant enforcement mapping + missing tests register in `tasks/debugging/task-03-auditability-and-linkage-contracts.md`.
3. Run gates + focused test `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`, log to `docs/ops_and_debug/LOGS/`.
