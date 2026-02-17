# Flyway v2 Migration Policy

## Canonical v2 path and history table
- Active migrations: `erp-domain/src/main/resources/db/migration_v2`
- Active history table: `flyway_schema_history_v2`
- Active Spring profile: `flyway-v2`.

## Hard rules
- Use Flyway v2 for new/updated migrations.
- Never add or edit `erp-domain/src/main/resources/db/migration` for new work.
- Keep `IF NOT EXISTS` and duplicate-object patterns out of new v2 migrations.
- Keep sequence numbering deterministic and monotonic.

## Allowed migration examples
- `erp-domain/src/main/resources/db/migration_v2/V2__accounting_core.sql`
- `erp-domain/src/main/resources/db/migration_v2/V10__accounting_replay_hotspot_indexes.sql`
- `erp-domain/src/main/resources/db/migration_v2/V11__accounting_replay_index_cleanup.sql`

## Forbidden migration examples
- Editing or adding new files under `erp-domain/src/main/resources/db/migration`.
- Re-using V2 filename sequence to overwrite/replace already-applied migration semantics.
- Non-deterministic `INSERT`/`UPDATE` without idempotent checks in migration scripts.

## Required migration safety checklist
1. Overlap scan: `bash scripts/flyway_overlap_scan.sh --migration-set v2`
2. Drift scan: `bash scripts/schema_drift_scan.sh --migration-set v2`
3. Legacy freeze guard before commit/deploy: `bash scripts/guard_legacy_migration_freeze.sh`
4. CI verification with `MIGRATION_SET=v2 bash scripts/verify_local.sh` and `gate_release` matrix.
5. Release matrix validation: `bash scripts/release_migration_matrix.sh --migration-set v2`.
6. For any applied schema change, add a new v2 migration only; never mutate existing migration file content.

## Convergence policy
- Only forward migrations may correct drift:
  - Create the missing constraints/indexes in new versioned files.
  - Add deterministic backfill/update scripts with guards.
  - Keep checksums reproducible and scoped.
- Do not remove old migrations or rewrite historical DDL in place.
