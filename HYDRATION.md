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

## Repo / Worktree State
- Worktree: `/home/realnigga/Desktop/CLI_BACKEND_epic04`
- Branch: `debug-06-security-rbac-boundaries`
- Dirty: clean (after M1 commit)

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 204, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AdminUserSecurityIT test` (PASS; Tests run 7, Failures 0, Errors 0, Skipped 0).

## Warnings / Notes
- Checkstyle baseline warnings (29454) persisted with failOnViolation=false.
- Testcontainers auth config warnings and dynamic agent loading notices persisted.
- Test logs include expected warnings (invalid company IDs, negative balances, dispatch mapping, sequence contention/duplicate key retries, HTML-to-PDF CSS parsing); no failures.

## Current Task
- Task 06 (security/RBAC + company boundaries) on `debug-06-security-rbac-boundaries`.
- M1 complete + verified; commit pending `debug-06: M1 rbac alignment` (update SHA after commit).

## Resume Instructions (Task 06)
1. Confirm M1 commit SHA and proceed to M2 in `tasks/debugging/task-06-security-rbac-and-company-boundaries.md`.
2. Run M2 gates + focused tests, then update evidence/stabilization/hydration.
