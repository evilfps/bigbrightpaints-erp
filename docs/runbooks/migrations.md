# Migration Runbook (Flyway v2)

Last reviewed: 2026-02-18
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

## Strict-Lane Alignment (M18-S1)
- Docs-only slices may skip commit-review/subagent review and must show evidence for `bash ci/lint-knowledgebase.sh`.
- Runtime/config/schema/test slices remain strict-lane and require reviewer evidence plus lane gates.
- For release-ops strict-lane slices touching `.github/workflows/`, `scripts/`, `docker-compose.yml`, or `erp-domain/Dockerfile`, run both:
  1. `bash scripts/gate_release.sh`
  2. `bash scripts/gate_reconciliation.sh`

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
  - rollout is split into one-index-per-migration steps (`V15`..`V18`) to minimize partial-failure impact.
  - index builds are transactional; schedule apply during low-write window.
- Required rollout posture:
  - run during controlled low-write window.
  - monitor index build progress and query latency during apply.
  - if one index build fails, do not rerun blindly; capture failure and apply forward-fix migration.

## V15 Checksum Transition Safeguard
- If any environment previously applied an earlier local variant of `V15` before this branch converged, run Flyway v2-scoped `validate` first and expect a checksum mismatch at `V15`.
- Use explicit v2 chain settings (do not run bare `flyway repair`):
```bash
mvn -B -ntp -f erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:validate \
  -Dflyway.url=jdbc:postgresql://$PGHOST:$PGPORT/<db_name> \
  -Dflyway.user=$PGUSER \
  -Dflyway.password=$PGPASSWORD \
  -Dflyway.defaultSchema=${FLYWAY_GUARD_SCHEMA:-public} \
  -Dflyway.locations=filesystem:$(pwd)/erp-domain/src/main/resources/db/migration_v2 \
  -Dflyway.table=flyway_schema_history_v2
```
- If `validate` reports checksum mismatch at `V15`, run approved v2-scoped repair and continue:
```bash
mvn -B -ntp -f erp-domain/pom.xml org.flywaydb:flyway-maven-plugin:repair \
  -Dflyway.url=jdbc:postgresql://$PGHOST:$PGPORT/<db_name> \
  -Dflyway.user=$PGUSER \
  -Dflyway.password=$PGPASSWORD \
  -Dflyway.defaultSchema=${FLYWAY_GUARD_SCHEMA:-public} \
  -Dflyway.locations=filesystem:$(pwd)/erp-domain/src/main/resources/db/migration_v2 \
  -Dflyway.table=flyway_schema_history_v2
```
- Then run v2-scoped `migrate`; `V16`..`V18` are idempotent (`CREATE INDEX IF NOT EXISTS`) so mixed pre-convergence index presence will not fail on duplicate index names.
- Reference: `docs/db/FLYWAY_V2_TRANSIENT_CHECKSUM_REPAIR.md` (same v2 settings pattern).
