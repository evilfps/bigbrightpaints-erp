# R2 Checkpoint

## Scope
- Feature: `factory-droid.integration.validated-recovery-stack`
- Branch: `recovery/08-engineer-shareout`
- High-risk paths touched: current-state integration across `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/**`, the paired regression/test surface under `erp-domain/src/test/java/**`, CI manifest/governance files, and this approval record.
- Why this is R2: the packet integrates the already validated recovery stack onto the current `Factory-droid` branch tip so the final branch-as-trunk certification can run against real current ancestry; it changes accounting, auth, inventory, sales, and purchasing runtime truth in one packet and therefore requires explicit same-diff approval evidence.

## Risk Trigger
- Triggered by integrating the repaired PR96-PR105 runtime surface onto `Factory-droid` with high-risk edits under accounting, security, purchasing, invoice, inventory, and sales modules.
- Contract surfaces affected: invoice-to-dispatch truth rails, settlement and period-close correction flow, portal/security boundaries, reservation replay truth, sales/purchase return handling, and the PR business-slice / changed-coverage governance used to certify the branch.
- Main risks being controlled: dropping a validated fix while rebasing onto current `Factory-droid`, silently regressing accounting replay or return behavior during the integration lift, and merging a high-risk branch packet without same-diff approval evidence tied to the actual final integration head.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization mission orchestration
- Basis: controlled integration of an already validated stacked recovery branch into the current `Factory-droid` base; no compatibility bridge or second runtime path is introduced.

## Escalation Decision
- Human escalation required: no
- Reason: the packet is a current-state integration of already reviewed fixes onto the live branch tip, with local compile and CI-governance proof rerun before remote certification; it does not add new tenant scope, migration behavior, or user-facing fallback modes.

## Rollback Owner
- Owner: Factory-droid integration worker
- Rollback method: revert `35f256cb`, rerun `mvn -B -ntp -DskipTests compile` from `erp-domain`, rerun `python3 -m unittest testing.ci.test_pr_review_ci_packet`, rerun `ENTERPRISE_DIFF_BASE=56598edf1735aaa5fea41b10eda7e6a060f93f4e bash ci/check-enterprise-policy.sh`, and rerun the PR109 CI workflow before re-review.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: current `Factory-droid` moves again before merge, additional high-risk runtime files are added to the integration packet, or any remote CI failure points to a runtime regression rather than branch ancestry/governance.

## Verification Evidence
- Verification bundle: final integration branch rebuilt from current `Factory-droid`, validated stack files overlaid from `35f256cb`, local compile, local CI-packet regression proof, local enterprise-policy repro/fix, and live fork PR/Actions execution on PR109.
- Result summary: the final integration branch is now based directly on `Factory-droid` and PR109 is `MERGEABLE`. Local proof passed with `mvn -B -ntp -DskipTests compile` in `erp-domain` and `python3 -m unittest testing.ci.test_pr_review_ci_packet` (`15` tests, `OK`). The policy failure was reproduced locally with `ENTERPRISE_DIFF_BASE=56598edf1735aaa5fea41b10eda7e6a060f93f4e bash ci/check-enterprise-policy.sh` and resolved by updating this same-diff checkpoint. Remote Actions are now running on PR109 head `35f256cb7a1b6e7d78b96aa60df99969988d3eee`, including push-triggered run `23044636428` and manual final-integration run `23044639601`.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `ci/pr_manifests/pr_business_slice.txt`, `scripts/changed_files_coverage.py`, `testing/ci/test_pr_review_ci_packet.py`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/util/LegacyDispatchInvoiceLinkMatcher.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/core/util/LegacyDispatchInvoiceLinkMatcherTest.java`.
