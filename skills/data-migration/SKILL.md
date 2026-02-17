---
name: data-migration
description: Plan Flyway v2 migrations with dry-run, validation, and rollback drills.
---

# Skill: data-migration

## Boundaries
- Allowed: migration planning, v2 migration scripts, predeploy scan updates, rollback docs.
- Not allowed: direct production DB operations without explicit human approval.

## Procedure
1. Confirm migration need, affected tables, and backward compatibility goals.
2. Add migration under `erp-domain/src/main/resources/db/migration_v2`.
3. Run overlap/drift scans and release migration matrix scripts.
4. Draft rollback and validation steps in `docs/runbooks/migrations.md`.
5. Mark any environment dependency as `unspecified` with TODO.
6. Escalate at R2 for production rollout.

## Required tools/commands
- `bash scripts/flyway_overlap_scan.sh --migration-set v2`
- `bash scripts/schema_drift_scan.sh --migration-set v2`
- `bash scripts/release_migration_matrix.sh --migration-set v2`

## Outputs
- New migration file(s)
- Validation evidence
- Updated migration/rollback runbooks
