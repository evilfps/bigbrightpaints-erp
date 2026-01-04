# Epic 09 — Operational Readiness (Migrations, Backups, Monitoring, Outbox)

## Objective
Make the system safe to deploy and recover:
- Flyway strategy is forward-only and documented (no risky rewrites)
- backup/restore procedures are tested and documented
- health/readiness reflects real dependencies (DB, messaging, required settings)
- outbox/event publishing is observable and safe under retries

## Scope guard (no new features)
- Use existing deployment topology; only improve safety, observability, and runbooks.
- Outbox changes must preserve at-least-once safety and be test-proven.

## Dependencies / parallel work
- Can run in parallel with other epics; coordinate if changing readiness/health semantics.


## Likely touch points (exact)
- Ops/config:
  - `docker-compose.yml`
  - `.env.example`
  - `erp-domain/src/main/resources/application*.yml`
  - `erp-domain/Dockerfile`
- Outbox/orchestrator:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
- DB migrations: `erp-domain/src/main/resources/db/migration/**`
- Docs:
  - `erp-domain/docs/DEPLOY_CHECKLIST.md`
  - `erp-domain/docs/FLYWAY_AUDIT_AND_STRATEGY.md`

## Step-by-step implementation plan
1) Define operational runbooks:
   - boot, migrate, backup, restore, verify, rollback guidance (per migration class).
2) Validate Flyway procedure across environments:
   - checksum drift handling, repair guidance, forward-fix strategy.
3) Harden readiness/health:
   - avoid “UP but broken” states; clearly report missing required configuration.
4) Outbox reliability:
   - metrics for pending/dead-letter events, retry behavior, and manual replay guidance.
5) Add minimal operator checks:
   - smoke script (curl health + docs + one authenticated call)

## Acceptance criteria
- There is a documented, repeatable procedure for deploy + migrate + rollback guidance.
- Health endpoints are meaningful (not just “process is running”).
- Outbox has observable metrics and clear operational handling for stuck/dead-letter events.

## Commands to run
- Boot: `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
- Health: `curl http://localhost:9090/actuator/health`
- Tests: `mvn -f erp-domain/pom.xml test`
