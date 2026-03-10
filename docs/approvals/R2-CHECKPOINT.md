# R2 Checkpoint

## Scope
- Feature: `recovery-followup.final-hardening-comment-recheck-and-closure`
- Branch: `recovery/07-final-hardening`
- High-risk paths touched: final-validation runtime reset guidance (`scripts/reset_final_validation_runtime.sh`), PR shard routing for the final-hardening coverage gate (`ci/pr_manifests/pr_accounting.txt`), the existing validation-seed / reservation replay surfaces plus their focused regression tests (`ValidationSeedDataInitializerTest`, `FinishedGoodsReservationEngineTest`, `FinishedGoodsServiceTest`), and this governance record.
- Why this is R2: the packet closes the last PR #101 runtime/test blockers on auth/company/accounting-adjacent validation behavior, preserves caller-controlled seed credentials during local reset, and routes focused regression coverage so the changed-files gate exercises the final-hardening seed/runtime paths without widening runtime authority.

## Risk Trigger
- Triggered by edits under `scripts/reset_final_validation_runtime.sh`, `ci/pr_manifests/pr_accounting.txt`, and focused validation / inventory regression tests covering `ValidationSeedDataInitializer` plus `FinishedGoodsReservationEngine` replay paths.
- Contract surfaces affected: explicit precedence for exported `ERP_VALIDATION_SEED_PASSWORD`, local reset/runtime determinism, PR-shard changed-files coverage for final-hardening runtime changes, and idempotent reservation replay after full dispatch.
- Main risks being controlled: `.env` silently overriding an explicitly exported seed password during reset, false-negative post-reset login checks, and any coverage blind spot that would let validation-seed or reservation-replay regressions merge without the accounting PR shard exercising them.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization mission orchestration
- Basis: compatibility-preserving hardening within the approved final-hardening recovery scope; no tenant-boundary widening or destructive migration behavior is introduced.

## Escalation Decision
- Human escalation required: no
- Reason: the packet narrows validation/runtime risk, keeps seeding local-only and fail-fast, and does not grant new business or cross-tenant powers.

## Rollback Owner
- Owner: final-hardening recovery worker
- Rollback method: revert the feature commit, then rerun `MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='FinishedGoodsReservationEngineTest,ValidationSeedDataInitializerTest,FinishedGoodsServiceTest' test`, the local PR-style changed-files coverage reproduction against base `6c55ac82c66c7230733ff2f7958108b565dfc8d1`, `MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`, `bash ci/check-enterprise-policy.sh`, and `bash ci/check-codex-review-guidelines.sh` before re-review.

## Expiry
- Valid until: 2026-03-14
- Re-evaluate if: later packets widen validation seeding beyond local/mock-only use, add new auth/accounting runtime defaults, or broaden dispatch/accounting authority contracts.

## Verification Evidence
- Verification bundle: compile, focused validation/inventory regressions, PR-style accounting/persistence-smoke coverage reproduction, gate-fast, enterprise-policy, codex-review-guidelines, reset-harness precedence verification, and PR check readback were rerun for this final-hardening packet.
- Result summary: the narrowed shell fix now snapshots export state before initializing `ERP_VALIDATION_SEED_PASSWORD`, so an explicitly exported value still wins even when `.env` defines a conflicting deterministic password; the added critical-path validation/inventory tests plus accounting-manifest routing drive the local PR-style changed-files coverage reproduction green against the live PR base (`line_ratio=0.9677`, `branch_ratio=0.9167`); targeted tests, compile, and full `gate-fast` stayed green; and the remote `gh pr checks` output remains red only because GitHub has not rerun against this unpushed local head yet.
- Artifacts/links: `scripts/reset_final_validation_runtime.sh`, `ci/pr_manifests/pr_accounting.txt`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/ValidationSeedDataInitializer.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsReservationEngine.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/core/config/ValidationSeedDataInitializerTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsReservationEngineTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsServiceTest.java`.
