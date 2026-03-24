# R2 Checkpoint

## Scope
- Feature: `ERP-36 opening-stock v2 contract alignment`
- PR: `#140`
- PR branch: `fix/erp-36-v166-ordering`
- Review candidate: update `migration_v2/V166__opening_stock_batch_key_contract_alignment.sql` so it drops the legacy partial batch-key/replay indexes before rewriting rows, then strengthen the truth-suite contract guard to lock the critical migration order.
- Why this is R2: this packet edits a merged `migration_v2` file in place to prevent tenant-upgrade failure on mixed legacy/new-history opening-stock data, while still dropping the legacy replay contract after the hard cut.

## Risk Trigger
- Triggered by changes under `erp-domain/src/main/resources/db/migration_v2/V166__opening_stock_batch_key_contract_alignment.sql`, `docs/runbooks/migrations.md`, and `docs/runbooks/rollback.md`.
- Contract surfaces affected: the v2 `opening_stock_imports` persistence contract used by `OpeningStockImportService`, the opening-stock replay/idempotency path keyed by `opening_stock_batch_key`, and import-history rows that previously depended on `replay_protection_key`.
- Failure mode if wrong: `V166` can fail before its dedupe pass on tenants whose legacy `idempotency_key` collides with an already-present batch key, leaving those tenants stuck mid-upgrade or forcing manual schema repair.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Canary owner: `Anas ibn Anwar`
- Approval status: `pending green CI and explicit merge approval`
- Basis: explicit user direction to fix the post-merge V166 review finding, local proof on the tightened truth-suite ordering guard, and scope-limited runbook/R2 evidence for the in-place migration correction.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet edits an already-merged `migration_v2` artifact that can block tenant upgrades, so merge stays gated on explicit human approval once CI is green on the follow-up PR.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: if this packet must be abandoned before rollout, revert the follow-up PR; if `V166` has already executed for a tenant, keep the ERP-36 hard-cut backend active and restore that tenant from a pre-`V166` snapshot/PITR before attempting any broader ERP-36 revert, following the `erp-36.opening-stock-v2-contract-alignment` entry in `docs/runbooks/rollback.md`.
- Rollback trigger:
  - opening-stock imports on v2 tenants start rejecting legitimate idempotent replays after the cutover
  - post-merge v2 rows with explicit batch keys are renamed or invalidated by the backfill/dedupe pass
  - operators need the dropped `replay_protection_key` data to diagnose or revert a tenant-specific incident
  - migration execution reveals tenant data that cannot be normalized safely under the new unique batch-key contract

## Expiry
- Valid until: `2026-03-31`
- Re-evaluate if: the follow-up PR head changes beyond the reviewed candidate, CI reruns against a materially different packet, or scope expands beyond the V166 ordering fix and truth-suite hardening.

## Verification Evidence
- Commands run:
  - `mvn -f "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/pom.xml" -s "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup/erp-domain/.mvn/settings.xml" -Djacoco.skip=true -Dtest=com.bigbrightpaints.erp.truthsuite.inventory.TS_OpeningStockBatchKeyV2MigrationContractTest test`
  - `cd "/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup" && bash ci/check-enterprise-policy.sh`
- Result summary:
  - targeted truth-suite proof covers the critical order: drop legacy unique index before batch-key rewrite, keep replay column until the end, then recreate the final unique index
  - `bash ci/check-enterprise-policy.sh` passed locally after the follow-up packet refreshed the migration/rollback runbooks and R2 evidence
- Artifacts/links:
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/140`
  - Head commit: `d1970d1c`
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-36-strict-cleanup-followup`
