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
- Branch: `debug-07-performance-ops-evidence`
- Tip: `fca29015fab211f7bca80546cf00f807c52b9e4c`
- Dirty: untracked logs present under `docs/ops_and_debug/LOGS` (pre-existing).

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `docker exec -e PGPASSWORD=erp erp_db psql -U erp -d erp_domain -c "SELECT status, dead_letter, COUNT(*) ..."` (PASS).
- `docker exec -e PGPASSWORD=erp erp_db pg_dump -U erp -d erp_domain --format=custom --no-owner --no-acl -f /tmp/erp_domain.dump` (PASS).
- `docker exec -e PGPASSWORD=erp erp_db dropdb -U erp --if-exists erp_domain_restore_test` (PASS).
- `docker exec -e PGPASSWORD=erp erp_db createdb -U erp erp_domain_restore_test` (PASS).
- `docker exec -e PGPASSWORD=erp erp_db pg_restore -U erp -d erp_domain_restore_test /tmp/erp_domain.dump` (PASS).
- `docker exec -e PGPASSWORD=erp erp_db psql -U erp -d erp_domain_restore_test -c "SELECT COUNT(*) FROM flyway_schema_history;"` (PASS).
- `mvn -f erp-domain/pom.xml -DskipTests compile` (PASS).
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (PASS; 29454 violations reported).
- `mvn -f erp-domain/pom.xml test` (PASS; Tests run 213, Failures 0, Errors 0, Skipped 4).
- `mvn -f erp-domain/pom.xml -Dtest=OrchestratorControllerIT,IntegrationCoordinatorTest test` (PASS; Tests run 8, Failures 0, Errors 0, Skipped 0).

## Warnings / Notes
- Checkstyle baseline warnings (29454) persisted with failOnViolation=false.
- Testcontainers auth config warnings and dynamic agent loading notices persisted.
- Test logs include expected warnings (invalid company IDs, negative balances, dispatch mapping, sequence contention/duplicate key retries, HTML-to-PDF CSS parsing); no failures.
- Outbox queries returned zero pending/retrying/dead-letter events on seeded dataset; stuck retry count 0.

## Current Task
- Task 07 (performance + ops evidence) on `debug-07-performance-ops-evidence`.
- M1 complete + verified; commit `6b3c89b8e283b6608151f11e99a91c1a538b10ab`.
- M2 complete + verified; commit `9577ca5fb73c325c38e81a42dd17ec3dabef1811`.
- M3 complete + verified; commit `fca29015fab211f7bca80546cf00f807c52b9e4c`.

## Resume Instructions (Task 07)
1. Run final verification gates: compile, checkstyle, `mvn test`, plus focused tests (`-Dtest=PerformanceBudgetIT,PerformanceExplainIT,OrchestratorControllerIT,IntegrationCoordinatorTest` if task requires full focused set).
2. Update `docs/ops_and_debug/EVIDENCE.md`, `erp-domain/docs/STABILIZATION_LOG.md`, and `HYDRATION.md` with final verification evidence.
3. Commit final verification updates, push branch, and produce Task 07 completion report.
