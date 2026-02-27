# Rollback Runbook

Last reviewed: 2026-02-22
Owner: Release & Ops Agent

## Purpose
Provide deterministic rollback steps for application and database failures.

## Preconditions
- Release artifact/version is known.
- Prior deploy artifact is available.
- Data backup/snapshot status is confirmed.
- Incident owner and approval authority assigned.

## Trigger Conditions
- Critical functional regression in accounting/auth/payroll.
- SLO breach persisting beyond agreed mitigation window.
- Migration side effects causing service instability or data corruption risk.

## Application Rollback Steps
1. Freeze new deployments.
2. Identify last known good release artifact/tag.
3. Revert deployment to that artifact/tag.
4. Run health + critical invariant smoke checks.
5. Confirm service stabilization and error-rate drop.
6. Publish incident update with rollback timestamp and impact.

## Database Rollback Strategy
- Preferred: forward-fix migration when rollback is unsafe.
- If rollback is required:
  1. Stop write traffic or enter maintenance mode.
  2. Restore from validated backup/snapshot.
  3. Reconcile post-restore ledger and critical domain counts.
  4. Re-enable traffic after validation checks pass.

## Validation Checklist
- [ ] auth login and token refresh smoke checks
- [ ] accounting posting smoke check
- [ ] reconciliation dashboard sanity checks
- [ ] payroll critical endpoint smoke check
- [ ] outbox/scheduler health checks

## M18-S10A Evidence Standard (Rollback / Rehearsal)
Record one immutable evidence entry per rollback event or drill with these required fields:
- `evidence_id` (`rollback-drill-YYYYMMDD-<short_sha>` or `rollback-incident-<id>`)
- `release_anchor_sha` (same SHA used by all gate outputs tied to the decision)
- `trigger_reason` (regression/SLO/migration side effect)
- `rollback_scope` (application artifact, migration IDs, data restore boundary)
- `commands_run` (exact command strings/manifests + pass/fail outcome)
- `release_gate_trace` (`gate_fast`, `gate_core`, `gate_reconciliation`, `gate_release` results, or explicit `not_run` reason)
- `validation_trace` (smoke/reconciliation outcomes after rollback action)
- `timestamps_utc` (decision, start, stabilize, close)
- `artifact_links` (logs/output file paths or incident ticket links)
- `approvals` (rollback initiator, approver, expiry/SLA if applicable)
- `residual_risks` (open risks accepted at close)
- `evidence_links` (`asyncloop` entry + `docs/approvals/R2-CHECKPOINT.md` update)

## Strict-Lane Alignment (M18-S1)
- Docs-only slices may skip commit-review/subagent review only when no runtime/config/schema/test files changed in the same slice.
- Control-plane docs-only strict-lane slices must still run:
  1. `bash ci/lint-knowledgebase.sh`
  2. `bash ci/check-architecture.sh`
  3. `bash ci/check-enterprise-policy.sh`
- Runtime/config/schema/test slices stay strict-lane and must include release-lane evidence from:
  1. `bash scripts/gate_release.sh`
  2. `bash scripts/gate_reconciliation.sh`

## Unknowns and TODOs
- Production deployment platform-specific rollback command is unspecified.
  - TODO: add exact platform commands once deployment target is documented.

## Enterprise R2 Linkage
- For high-risk changes, set rollback owner and approval expiry in `docs/approvals/R2-CHECKPOINT.md` before release go/no-go and use matching `evidence_id` + `release_anchor_sha` values across both records.

## V15 Rollback/Forward-Fix Notes (2026-02-15)
- Migration: `erp-domain/src/main/resources/db/migration_v2/V15__accounting_audit_read_model_hotspot_indexes.sql`
- Primary strategy: forward-fix (preferred) if planner or migration timing issues appear after apply.
- Emergency rollback path for V15 indexes:
  1. open maintenance governance window and reduce write pressure.
  2. apply compensating migration with `DROP INDEX` for affected index names.
  3. rerun accounting audit list smoke checks and reconciliation sanity checks.
  4. keep compensating migration artifact and verification logs attached to incident record.

## V21 Rollback/Forward-Fix Notes (2026-02-22)
- Migration: `erp-domain/src/main/resources/db/migration_v2/V21__super_admin_role_seed.sql`
- Primary strategy: forward-fix (preferred). Because this migration is idempotent seed data, rollback should be handled by a compensating migration instead of editing applied history.
- Forward-fix path:
  1. open controlled maintenance window if role/permission mapping drift affects access.
  2. apply compensating `migration_v2` script to remove or correct incorrect `role_permissions` rows and permission seeds.
  3. rerun tenant admin access smoke checks and role-permission reconciliation queries.
  4. attach compensating migration artifact and verification logs to the incident/change record.
- Emergency rollback path (pre-apply only):
  1. halt deployment before V21 is applied.
  2. run Flyway v2 `validate` and confirm pending set is intact.
  3. resume rollout only after approval with corrected migration plan.

## V22 Rollback/Forward-Fix Notes (2026-02-28)
- Migration: `erp-domain/src/main/resources/db/migration_v2/V22__inventory_adjustment_reversal_chain.sql`
- Primary strategy: forward-fix (preferred). This migration introduces reversal-chain linkage (`reversal_of_adjustment_id`) and uniqueness constraints used by runtime reversal logic, so in-place rollback after writes begin is high risk.
- Forward-fix path:
  1. pause reversal operations and enter controlled maintenance window.
  2. ship a compensating `migration_v2` script to adjust index/FK behavior if needed.
  3. rerun inventory adjustment reversal smoke checks and linked journal reversal checks.
  4. attach compensating migration SQL + verification logs to incident/change evidence.
- Emergency rollback path (pre-apply only):
  1. stop rollout before V22 migration execution.
  2. run Flyway v2 `validate` to confirm pending chain consistency.
  3. resume only with approved migration plan or amended forward-fix sequence.
