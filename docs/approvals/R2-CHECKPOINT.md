# R2 Checkpoint

Last reviewed: 2026-04-04

## Scope
- Feature: review branch regression remediation for the orchestrator code-red cleanup packet
- Branch: external review branch `review-orchestrator-erp-code-red-cleanup` in the local review worktree
- PR: external review candidate — https://github.com/evilfps/bigbrightpaints-erp/pull/new/review/orchestrator-erp-code-red-cleanup
- Review candidate:
  - restore accounting controller compatibility for matching request-body and `Idempotency-Key` values on receipt and settlement endpoints while continuing to fail closed on legacy `X-Idempotency-Key`
  - surface tenant control-plane target lookup and tenant lifecycle lookup outages as `503 SYS_002` in `CompanyContextFilter` instead of misclassifying them as `403` authorization failures
  - expand unit and truth-suite coverage for accounting idempotency resolution plus company control-plane outage handling
- Why this is R2: the packet modifies executable code under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/` and `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/`, both of which are high-risk accounting/company control-plane paths guarded by enterprise policy.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
- Contract surfaces affected:
  - accounting receipt/settlement idempotency-key resolution for matching header/body replay keys
  - super-admin tenant control-plane error classification for target-tenant lookup and lifecycle lookup failures
  - regression/truth-suite assertions that guard those two runtime contracts
- Failure mode if wrong:
  - accounting clients that still send a matching body `idempotencyKey` would start failing with `400`
  - tenant lookup outages would be misreported as permission denials, suppressing retries and obscuring real platform availability incidents

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: approved for branch-local remediation
- Basis: the fixes preserve existing compatibility and narrow failure handling without widening permissions, tenant boundaries, schema, or migrations. The risk is bounded by focused controller/filter tests plus truth-suite coverage.

## Escalation Decision
- Human escalation required: no
- Reason: this remediation is compatibility-preserving and fail-closed. It does not widen privileges, cross-tenant access, or irreversible data behavior, and the rollback path is a straightforward revert of the local fixes.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the local remediation in the review worktree
  - after merge: revert the regression-fix commit(s) and rerun the same accounting/security validation slice
- Rollback trigger:
  - accounting receipt or settlement endpoints stop accepting matching header/body replay keys that previously worked
  - tenant control-plane lookup outages stop returning `503 SYS_002`
  - targeted unit/truth-suite validation regresses after merge

## Expiry
- Valid until: 2026-04-18
- Re-evaluate if: the packet expands beyond the accounting/controller + company/security regression fixes, or if additional auth/RBAC/schema/migration paths are added.

## Test Waiver
- Not applicable — executable code and tests changed, and targeted validators were run.

## Verification Evidence
- Commands run:
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/review-orchestrator-erp-code-red-cleanup/erp-domain && MIGRATION_SET=v2 mvn -q spotless:check -DspotlessFiles='src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java,src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java,src/test/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingControllerIdempotencyHeaderParityTest.java,src/test/java/com/bigbrightpaints/erp/modules/auth/CompanyContextFilterControlPlaneBindingTest.java,src/test/java/com/bigbrightpaints/erp/truthsuite/accounting/TS_AccountingControllerIdempotencyHeaderParityRuntimeCoverageTest.java,src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimeAccountingReplayConflictExecutableCoverageTest.java'`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/review-orchestrator-erp-code-red-cleanup/erp-domain && MIGRATION_SET=v2 mvn -q -Djacoco.skip=true -Dtest='AccountingControllerIdempotencyHeaderParityTest,CompanyContextFilterControlPlaneBindingTest,TS_AccountingControllerIdempotencyHeaderParityRuntimeCoverageTest,TS_RuntimeAccountingReplayConflictExecutableCoverageTest' test`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/review-orchestrator-erp-code-red-cleanup && bash ci/check-codex-review-guidelines.sh`
  - `cd /Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/review-orchestrator-erp-code-red-cleanup && bash ci/check-enterprise-policy.sh`
- Result summary:
  - targeted spotless validation passes for all edited production and test files
  - targeted controller/filter/truth-suite tests pass after the compatibility and outage-classification fixes
  - codex review policy and enterprise policy checks are expected to pass once this scope-specific R2 checkpoint update is included in the packet
- Artifacts/links:
  - repo checkout: local review worktree for the external orchestrator cleanup branch
  - review source: user-provided PR creation URL for the external review branch
