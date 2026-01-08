# Deploy Checklist

## Required Env Vars (Prod)
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `JWT_SECRET` (32+ bytes)
- `ERP_LICENSE_KEY`
- `ERP_DISPATCH_DEBIT_ACCOUNT_ID`
- `ERP_DISPATCH_CREDIT_ACCOUNT_ID`

## Common Env Vars (Non-Prod Defaults Exist)
- `SPRING_PROFILES_ACTIVE` (dev|test|prod)
- `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`
- `KAFKA_BOOTSTRAP_SERVERS`
- `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`
- `ERP_CORS_ALLOWED_ORIGINS`
- `ERP_MAIL_BASE_URL`, `ERP_MAIL_FROM`
- `ERP_SECURITY_AUDIT_PRIVATE_KEY`
- `ERP_RATE_LIMIT_RPM`, `ERP_AUTO_APPROVAL_ENABLED`
- `ERP_LICENSE_PRODUCT_ID`, `ERP_LICENSE_ALGORITHM`, `ERP_LICENSE_CREATED`, `ERP_LICENSE_DESCRIPTION`, `ERP_LICENSE_ENFORCE`

## Profiles
- dev: `application.yml` (defaults use local Postgres, RabbitMQ, Kafka)
- test: Testcontainers via `AbstractIntegrationTest` (Docker required)
- prod: `application-prod.yml` (requires explicit secrets)

## Flyway/Migrations
- Flyway runs on startup; ensure DB user has DDL privileges.
- For existing DBs: do NOT rewrite migrations; use forward-fix if needed.
- Optional: `flyway repair` only if checksum drift is known-safe.

## Operational Runbook (Boot/Migrate/Backup/Restore)
- Boot (prod-like): `JWT_SECRET=... ERP_SECURITY_ENCRYPTION_KEY=... docker compose up -d --build`
- Migrate:
  - Flyway runs automatically on startup; watch logs for `Flyway` migrate output.
  - Verify applied migrations: `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;`
- Backup (Postgres):
  - `pg_dump --format=custom --no-owner --no-acl --file=backup_$(date +%F).dump "$SPRING_DATASOURCE_URL"`
  - Store backups off-host and encrypt at rest.
- Restore (Postgres):
  - Stop app, restore into a clean DB: `pg_restore --clean --if-exists --no-owner --dbname "$SPRING_DATASOURCE_URL" backup_YYYY-MM-DD.dump`
  - Start app to re-run health checks and confirm `/actuator/health`.
- Rollback guidance:
  - Schema additions: add a forward migration to revert or neutralize (do not edit applied migrations).
  - Data backfills: write idempotent forward fixes; document compensating steps.
  - Destructive changes: restore from backup and re-apply forward fixes in a new migration.

## Startup Commands
- Build: `mvn package`
- Run (dev): `mvn spring-boot:run`
- Run (prod): `mvn -Dspring-boot.run.profiles=prod spring-boot:run`

## Health Checks
- `/actuator/health`
- `/api/integration/health`
- `/api/v1/orchestrator/health/integrations`
- `/api/v1/orchestrator/health/events`

## Outbox Operations
- Retry policy: exponential backoff (30s * 2^retry), max 5 attempts; then status=FAILED and dead_letter=true.
- Metrics: `outbox.events.pending`, `outbox.events.retrying`, `outbox.events.deadletters`.
- Health snapshot: `/api/v1/orchestrator/health/events` (pending/retrying/dead-letter counts).
- Manual replay:
  - Inspect: `SELECT id, status, retry_count, last_error, next_attempt_at FROM orchestrator_outbox WHERE dead_letter = true ORDER BY created_at DESC;`
  - Requeue one: `UPDATE orchestrator_outbox SET status='PENDING', dead_letter=false, retry_count=0, next_attempt_at=now() WHERE id='...';`
  - Requeue all: `UPDATE orchestrator_outbox SET status='PENDING', dead_letter=false, retry_count=0, next_attempt_at=now() WHERE dead_letter = true;`
  - Note: replays are at-least-once; confirm consumer idempotency before bulk requeue.

## Logs/Metrics
- File log: `logs/erp-backend.log`
- Actuator endpoints enabled for `health`, `info`, `metrics`.
