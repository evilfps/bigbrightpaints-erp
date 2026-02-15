# Migration Runbook (Flyway v2)

Last reviewed: 2026-02-15
Owner: Data Migration Agent

## Purpose
Standardize safe migration planning, validation, and rollback drills.

## Policy
- New schema/data changes must be in: `erp-domain/src/main/resources/db/migration_v2`.
- Do not edit applied migration files.
- Use guard scripts and matrix evidence before release.

## Pre-Merge Checklist
- [ ] migration file added in `migration_v2`
- [ ] migration naming and ordering validated
- [ ] dry-run rationale documented
- [ ] backward/forward compatibility impact noted
- [ ] rollback or forward-fix strategy documented

## Local Validation Commands
1. `bash scripts/flyway_overlap_scan.sh --migration-set v2`
2. `bash scripts/schema_drift_scan.sh --migration-set v2`
3. `bash scripts/release_migration_matrix_v2.sh`
4. `bash scripts/verify_local.sh`

## Staging Drill
1. Restore staging-like dataset/snapshot.
2. Apply pending migration set.
3. Run critical smoke checks and reconciliation checks.
4. Execute rollback drill (restore or forward-fix rehearsal).
5. Record outcomes and timings.

## Production Gate (Human Approval Required)
- R2 approval required for production migration execution.
- Required evidence:
  - migration diff summary
  - validation outputs
  - rollback owner and plan
  - expected downtime/impact (if any)

## Failure Handling
- If migration fails before side effects: stop and investigate; do not rerun blindly.
- If partial side effects exist: execute predefined rollback or forward-fix strategy.
- Open incident and preserve logs/artifacts for audit.

## Unknowns and TODOs
- Exact production DB backup/restore tooling is unspecified in this repo.
  - TODO: link authoritative DB operations runbook and SRE ownership.

## Enterprise R2 Linkage
- For any migration in `migration_v2`, update `docs/approvals/R2-CHECKPOINT.md` in the same change set and record rehearsal evidence.

## V15 Execution Notes (2026-02-15)
- Migration: `erp-domain/src/main/resources/db/migration_v2/V15__accounting_audit_read_model_hotspot_indexes.sql`
- Change type: performance indexes for accounting audit read-model (`journal_entries`, `journal_lines`, `invoices`, `raw_material_purchases`).
- Safety profile:
  - runs with `-- flyway:executeInTransaction=false`.
  - uses `CREATE INDEX CONCURRENTLY` to reduce write blocking on hot tables.
- Required rollout posture:
  - run during controlled low-write window.
  - monitor index build progress and query latency during apply.
  - if one index build fails, do not rerun blindly; capture failure and apply forward-fix migration.
