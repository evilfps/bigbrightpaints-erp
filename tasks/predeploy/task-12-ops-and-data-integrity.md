# Task 12 — Ops + Data Integrity (Predeploy Runbooks, Go/No-Go Gates)

## Purpose
**Accountant-level:** ensure operators can prove (with evidence) that the deployed system’s books are consistent: no missing links, no reconciliation drift, and period close discipline is enforceable.

**System-level:** ensure deploy is repeatable and recoverable: migrations are safe, backup/restore works, health checks are meaningful, and outbox/event retry behavior is observable.

## Scope guard (explicitly NOT allowed)
- No infrastructure rewrites (keep current Spring Boot/Flyway/Postgres/Docker Compose patterns).
- No new observability stack; only incremental ops/runbook improvements consistent with existing docs.

## Milestones

### M1 — Produce a “predeploy go/no-go checklist” that is executable
Deliverables:
- A checklist that an operator can run in order and record evidence:
  - build
  - full test suite
  - boot via Docker Compose (prod-like)
  - health checks and smoke checks
  - reconciliation endpoints and close checklist checks

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Boot + health:
  - `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
  - `curl -fsS http://localhost:9090/actuator/health`

Evidence to capture:
- Exact commands + outputs (redact secrets).
- `/actuator/health` response including groups.

Stop conditions + smallest decision needed:
- If boot is “UP but broken”, expand required config health gating (do not weaken health checks).

### M2 — Data integrity query pack (prod candidate DB)
Deliverables:
- A small, high-signal SQL query pack to run against a candidate prod DB to detect:
  - unbalanced journals in period
  - posted docs missing journal links (invoice/purchase/payroll/adjustments)
  - ledger entries missing journal links
  - inventory movements missing journal links where expected
  - outbox dead letters / retry backlog
- Document how to run queries via Docker Compose `psql` (copy/paste ready).

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Optional (if DB is up): run the query pack and save outputs.

Evidence to capture:
- SQL outputs saved into the evidence folder.
- Any remediation actions required (should be zero for a clean candidate).

Stop conditions + smallest decision needed:
- If queries find drift: decide whether drift is (A) historical and requires a one-time backfill, or (B) an active bug requiring code fix before deploy.

### M3 — Backup/restore + Flyway rehearsal (rollback safety)
Deliverables:
- Confirm backup/restore is tested and documented (pg_dump/pg_restore).
- Confirm Flyway procedure for existing DBs is documented and safe (checksum drift handling, forward-fix strategy).
- Ensure operator smoke script is runnable and documented (`erp-domain/scripts/ops_smoke.sh`).

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- If using Docker Compose DB:
  - `docker exec -e PGPASSWORD=erp erp_db pg_dump -U erp -d erp_domain --format=custom --no-owner --no-acl -f /tmp/erp_domain.dump`
  - `docker exec -e PGPASSWORD=erp erp_db createdb -U erp erp_domain_restore_test`
  - `docker exec -e PGPASSWORD=erp erp_db pg_restore -U erp -d erp_domain_restore_test /tmp/erp_domain.dump`

Evidence to capture:
- Backup/restore command output and confirmation queries.
- Flyway history snapshot query output.

Stop conditions + smallest decision needed:
- If migrations require repair: decide whether `flyway repair` is safe (checksum drift known-safe) or blocked (requires forward-fix migration instead).
