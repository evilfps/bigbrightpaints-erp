# ADR-005: Flyway V2 Hard-Cut Migration Posture

Last reviewed: 2026-04-02

## Status

Accepted

## Context

The orchestrator-erp backend migrated from an earlier Flyway migration track to a v2 track. The v2 migrations include significant schema changes: auth-v2 scoped accounts, superadmin control-plane tables, opening stock batch-key alignment, HR/Payroll module pause scaffolding, and password reset delivery tracking.

Many of these migrations are **forward-only**: they alter data in ways that cannot be reversed with a simple SQL `ALTER TABLE ... DROP COLUMN` or `UPDATE ... SET` rollback. For example, V168/V169 rewrite auth accounts to be company-scoped, and V167 introduces the superadmin control-plane schema that downstream code depends on.

The repo runs migrations under the `flyway-v2` Spring profile, activated via `SPRING_PROFILES_ACTIVE=prod,flyway-v2`. The v1 migration track still exists in the repo but is no longer the active path.

## Decision

1. **Flyway v2 is the active migration track.** All new schema changes must be added as v2 migrations. The v1 track is legacy and must not receive new migrations.
2. **Profile-activated migration execution.** Migrations only run when the `flyway-v2` profile is active. This prevents accidental migration execution in development or test environments that do not set the profile.
3. **Forward-only posture with snapshot/PITR recovery.** Many v2 migrations intentionally do not support ad-hoc SQL rollback. Recovery from a failed migration requires database snapshot or point-in-time recovery (PITR) rather than Flyway undo scripts.
4. **Runbook requirements.** Every migration must be accompanied by updates to `docs/runbooks/migrations.md` (forward plan, dry-run commands) and `docs/runbooks/rollback.md` (snapshot/PITR recovery steps).
5. **Migration safety gates in CI.** High-risk migration paths (especially under `db/migration_v2/`) require R2 checkpoint evidence as documented in `docs/approvals/R2-CHECKPOINT.md` per the enterprise policy check.
6. **The v1 track is preserved but inactive.** The legacy migration files remain in the repository for reference but are not executed by the active application configuration.

## Alternatives Rejected

1. **Reversible (undo) migrations** — the data transformations in v2 are too complex for reliable SQL undo scripts; incorrect undo scripts are more dangerous than forward-only migrations with snapshot recovery.
2. **Dual-track parity** — maintaining both v1 and v2 tracks in parallel would double migration maintenance and increase the risk of divergence.
3. **Blue-green database deployments** — adds infrastructure complexity (dual databases, data sync) disproportionate to the current deployment model.
4. **Removing v1 migrations from the repo** — they contain useful historical context about the original schema design and should be preserved for reference.

## Consequences

- New engineers can understand the active migration posture without guessing whether v1 or v2 is authoritative.
- Rollback planning is explicit: snapshot/PITR rather than ad-hoc SQL, which must be reflected in operational runbooks.
- The forward-only posture means that migration deployment must be treated as a coordinated cut, not a routine deploy.
- CI and enterprise policy checks enforce that high-risk migration changes carry proper evidence and approval.

## Cross-references

- [docs/runbooks/migrations.md](../runbooks/migrations.md) — migration forward plans and dry-run commands
- [docs/runbooks/rollback.md](../runbooks/rollback.md) — rollback strategies
- [docs/RELIABILITY.md](../RELIABILITY.md) — migration safety section
- [docs/approvals/R2-CHECKPOINT.md](../approvals/R2-CHECKPOINT.md) — high-risk change approval evidence
- [docs/SECURITY.md](../SECURITY.md) — security review policy
