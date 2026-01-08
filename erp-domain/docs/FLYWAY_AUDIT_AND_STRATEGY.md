# Flyway Audit and Strategy

## Inventory
- Total migrations: 97 (`src/main/resources/db/migration`)
- Naming pattern: `V{version}__{description}.sql` with incremental versions 1-97.

## Duplicate/Conflict Findings
- Table creation duplicated but guarded with `IF NOT EXISTS` / DO blocks:
  - `payroll_runs`: `V7__hr_tables.sql`, `V78__payroll_enhancement.sql`
  - `payroll_run_lines`: `V57__payroll_run_lines.sql`, `V78__payroll_enhancement.sql`
- Index creation duplicated but guarded:
  - `idx_invoices_dealer`: `V12__invoices.sql`, `V62__performance_indexes.sql`
  - `idx_journal_lines_account_id`: `V62__performance_indexes.sql`, `V65__performance_accounting_inventory_indexes.sql`

## Fresh DB Procedure (Safe)
1) Ensure Postgres is available and credentials are set.
2) Start the app with Flyway enabled (default).
3) On first boot, Flyway applies all migrations in order.
4) Verify schema via app health + basic endpoint smoke (see `docs/DEPLOY_CHECKLIST.md`).

## Existing DB Strategy (No Rewrites)
- Assume migrations have been applied in at least one environment.
- Do NOT edit or delete applied migration files.
- If checksum drift is detected:
  - Use `flyway repair` to align checksums only when files are unchanged from deployed versions.
  - Otherwise, add a new forward migration to correct schema.
- For new schema fixes, always add a new migration (forward-fix).

## Checksum Drift Handling
- Detect drift from startup logs or by querying: `SELECT version, checksum, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC;`
- If drift is caused by a safe, identical file (e.g., line endings), use `flyway repair` after confirming the deployed file matches.
- If drift indicates a real change, create a new forward migration that resolves the issue; do not edit the applied file.

## Forward-Fix Guidance by Migration Type
- Schema additions/constraint changes: add a forward migration that reverts or adjusts the change safely.
- Data backfills: keep migrations idempotent with guards (`WHERE`/`EXISTS`) to allow re-runs.
- Destructive operations: require a backup/restore plan; use forward migrations only after recovery safety is confirmed.

## Environment Validation Steps
- Dev/Test: boot against a clean database and confirm Flyway applies all migrations without errors.
- Prod: take backups, deploy during a maintenance window, and verify `/actuator/health` plus recent `flyway_schema_history` entries.

## Changes in This Pass
- No Flyway file edits; only audit and documentation updates.
