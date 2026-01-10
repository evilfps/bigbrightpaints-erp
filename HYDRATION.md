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
- Branch: `debug-01-module-map` (Task 01 M1 complete, tip `49135b9`)
- Dirty: no

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 30804 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 206, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test` (PASS; Tests run 1, Failures 0, Errors 0, Skipped 0).
- `jq + comm openapi vs endpoint_inventory.tsv diff` (PASS; see evidence log for mismatches).

## Warnings / Notes
- Checkstyle baseline warnings (30804) persisted with failOnViolation=false.
- Endpoint inventory mismatch: openapi has endpoints missing from endpoint_inventory.tsv; inventory-only includes `/api/integration/health` (see evidence log).

## Resume Instructions (Post Epic 10)
1. Task 01 M1 complete on `debug-01-module-map` at `49135b9`.
2. Next milestone: Task 01 M2 in `tasks/debugging/task-01-architecture-and-module-map.md` (financial touchpoints + evidence chain).
3. After M2 run gates: compile, checkstyle (failOnViolation=false), full test suite, and `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`.
4. Update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md`, then commit with message `debug-01: M2 <summary>`.
5. Continue Task 01 M3 once M2 is complete and verified.
