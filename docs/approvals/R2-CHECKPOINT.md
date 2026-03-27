# R2 Checkpoint

## Scope
- Feature: `ERP-21 portal/dealer portal split hard-cut cleanup`
- Branch: `anasibnanwar1/erp-21-packet-4-portal-split-cleanup`
- Review candidate: keep one canonical split-host contract for portal finance and dealer portal finance (`/api/v1/portal/finance/**` vs `/api/v1/dealer-portal/**`) after rebasing onto current `main`, with no compatibility bridges.
- Why this is R2: this change set touches high-risk auth/company/admin/accounting codepaths and canonical finance route contracts; regressions could cross tenant boundaries, break finance APIs, or route requests to the wrong portal surface.

## Risk Trigger
- Triggered by changed high-risk paths under:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
- Contract surfaces affected:
  - `GET /api/v1/portal/finance/**`
  - `GET /api/v1/dealer-portal/**`
  - split support-host contract used by portal/dealer support workflows
- Failure mode if wrong:
  - finance traffic lands on wrong host/prefix
  - tenant/company scope enforcement drifts
  - policy and reconciliation gates report false pass/fail due contract drift

## Approval Authority
- Mode: human
- Approver: `ERP packet reviewer`
- Canary owner: `ERP-21 packet owner`
- Approval status: `pending CI green + human review`

## Escalation Decision
- Human escalation required: yes
- Reason: the branch updates canonical finance host routing and high-risk backend modules in one rebased packet.

## Rollback Owner
- Owner: `ERP-21 packet owner`
- Rollback method: revert PR `#163` as a unit; do not add compatibility shims or dual-route fallbacks.
- Rollback trigger:
  - any portal/dealer finance route mismatch
  - policy gate regressions on auth/company/accounting/orchestrator scopes
  - tenant scope leakage across portal boundaries

## Expiry
- Valid until: `2026-04-03`
- Re-evaluate if: scope extends beyond ERP-21 split cleanup or migration/auth surfaces change again before merge.

## Verification Evidence
- Commands run:
  - `bash scripts/gate_fast.sh`
  - `bash scripts/gate_reconciliation.sh`
  - `bash scripts/gate_release.sh`
  - `bash ci/check-enterprise-policy.sh`
- Result summary:
  - rebased ERP-21 packet onto current `main` and resolved split-contract conflicts
  - fixed OpenAPI endpoint inventory drift and schema drift allowlist for v168/v169 checks
  - applied required spotless normalization so release gate is green on current mainline policy
  - local policy gate now passes with updated R2 checkpoint context
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-21-p4-portal-split-review`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/163`
  - Linear issue: `ERP-21`
