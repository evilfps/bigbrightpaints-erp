# R2 Checkpoint

## Scope
- Feature: `ERP-36 opening-stock v2 contract alignment`
- PR: `#139`
- PR branch: `fix/erp-36-v2-migration-followup`
- Review candidate: add `migration_v2/V166__opening_stock_batch_key_contract_alignment.sql` so v2 tenants get the same opening-stock batch-key hard cut already merged on the primary migration track, while preserving any newer v2 rows already written with explicit batch keys.
- Why this is R2: this packet rewrites durable opening-stock import history for existing v2 tenants, tightens the replay/idempotency contract to the non-null batch-key model, and drops the legacy `replay_protection_key` column after the backfill.

## Risk Trigger
- Triggered by changes under `erp-domain/src/main/resources/db/migration_v2/V166__opening_stock_batch_key_contract_alignment.sql`, `docs/runbooks/migrations.md`, and `docs/runbooks/rollback.md`.
- Contract surfaces affected: the v2 `opening_stock_imports` persistence contract used by `OpeningStockImportService`, the opening-stock replay/idempotency path keyed by `opening_stock_batch_key`, and import-history rows that previously depended on `replay_protection_key`.
- Failure mode if wrong: legacy v2 tenants can reject valid replays after the ERP-36 hard cut, duplicate batch-key collisions can be resolved against the wrong row, or rollback attempts can strand tenants on a schema/runtime mismatch.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Canary owner: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: explicit user direction to fix the missing v2 migration follow-up, local proof on the new truth-suite guard, and scope-limited runbook/R2 evidence showing this is a forward-only parity migration for already-merged ERP-36 runtime code.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet mutates existing tenant inventory-import history on the `migration_v2` track and removes legacy replay data, so merge stays gated on explicit human approval once CI is green on PR `#139`.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: if this packet must be abandoned before rollout, revert PR `#139`; if `V166` has already executed for a tenant, keep the ERP-36 hard-cut backend active and restore that tenant from a pre-`V166` snapshot/PITR before attempting any broader ERP-36 revert, following the `erp-36.opening-stock-v2-contract-alignment` entry in `docs/runbooks/rollback.md`.
- Rollback trigger:
  - opening-stock imports on v2 tenants start rejecting legitimate idempotent replays after the cutover
  - post-merge v2 rows with explicit batch keys are renamed or invalidated by the backfill/dedupe pass
  - operators need the dropped `replay_protection_key` data to diagnose or revert a tenant-specific incident
  - migration execution reveals tenant data that cannot be normalized safely under the new unique batch-key contract

## Expiry
- Valid until: `2026-03-31`
- Re-evaluate if: PR `#139` head changes beyond commit `ec9673c6`, CI reruns against a materially different candidate, or scope expands beyond the v2 opening-stock contract-alignment packet.

## Verification Evidence
- Commands run:
  - `mvn -f "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/pom.xml" -s "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/.mvn/settings.xml" -Djacoco.skip=true -Dtest=com.bigbrightpaints.erp.truthsuite.inventory.TS_OpeningStockBatchKeyV2MigrationContractTest test`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup" && bash ci/check-enterprise-policy.sh`
- Result summary:
  - targeted truth-suite proof passed locally for the new `migration_v2` contract guard
  - `bash ci/check-enterprise-policy.sh` passed locally after the R2 packet and migration/rollback runbooks were refreshed in-tree
- Artifacts/links:
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/139`
  - Head commit: `ec9673c6`
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup`
