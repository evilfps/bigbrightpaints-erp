# HYDRATION

## Completed Epics
- Epic 03: branch `epic-03-production-stock`, tip `3f2370c38c0152153369507159e5ae26ca1fa048`.
- Epic 04: branch `epic-04-p2p-ap`, tip `c5dd42334a397b1137d821bd81f50b1504debca4`.
- Epic 05: branch `epic-05-hire-to-pay`, tip `dd1589c00634f9a122ebc9d35caf5114ada1f561`.
- Epic 06: branch `epic-06-admin-security`, tip `dabaeebc8de027491f0974050032bb86afbee5cc`.
- Epic 07: branch `epic-07-performance-scalability`, tip `96c0c71c0d751f3767cfbfb43e970842da9112b5`.

## Repo / Worktree State
- Worktree: `/home/realnigga/Desktop/CLI_BACKEND_epic04`
- Branch: `epic-07-performance-scalability`
- Dirty: clean

## Environment Setup
- No new installs; Docker/Testcontainers working.

## Commands Run (Latest)
- `mvn -f erp-domain/pom.xml test` (PASS; final Epic 07 full pass; Tests run 200, Failures 0, Errors 0, Skipped 4).
- `docker build -f erp-domain/Dockerfile -t erp-domain-app:epic07 .` (PASS).
- `docker run` (db/rabbit/mailhog/app) (PASS; app started with prod profile).

## Warnings / Notes
- Checkstyle baseline warnings: 29192 (failOnViolation=false).
- Test logs include expected warnings (Testcontainers auth config, dynamic agent, invalid company IDs, negative balances, dispatch mapping, sequence contention, HTML-to-PDF CSS parsing); no failures.
- Docker compose CLI not available; used manual `docker run` flow to verify prod-like boot. App logs include bean post-processor warnings, SMTP username empty, and dispatch mapping not configured.

## Resume Instructions (Epic 08)
1. Create and checkout `epic-08-reconciliation-controls` off the current tip.
2. Re-read `tasks/task-08.md` and latest `erp-domain/docs/STABILIZATION_LOG.md`.
3. Run gates after each milestone: compile, checkstyle (failOnViolation=false), full `mvn test`, plus reconciliation/control tests.
4. Update `erp-domain/docs/STABILIZATION_LOG.md` and push after each milestone.
