# R2 Checkpoint

Last reviewed: 2026-04-09

## Scope
- Feature: `refactor-accounting-role-cleanup` integration review
- Branch: `refactor-accounting-role-cleanup` (base: `894023e51`)
- PR: pending
- Review candidate:
  - keep the accounting audit visibility narrowing so the accounting audit feed remains `ACCOUNTING`-only at runtime
  - keep the reconciliation cleanup direction by replacing the inheritance-only `ReconciliationServiceCore` split with `ReconciliationService` plus `ReconciliationOperations`, and by removing the leftover legacy request shim
  - keep the Tally import observability hardening and the docs/runtime corrections that now match the integrated branch truth
- Why this is R2: the current diff changes high-risk accounting runtime surfaces under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**` and `erp-domain/src/main/java/com/bigbrightpaints/erp/core/auditaccess/**`, so merge confidence still requires explicit evidence and rollback posture.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/auditaccess/AuditVisibilityPolicy.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/BankReconciliationSessionService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationOperations.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TallyImportService.java`
- Contract surfaces affected:
  - accounting audit read visibility for the accounting audit events feed
  - reconciliation runtime behavior and bank-reconciliation session flow after removal of the legacy request bridge
  - Tally import observability on previously silent fallback/error-swallowing paths
  - branch-level policy and changed-files coverage evidence consumed by `bash ci/check-enterprise-policy.sh` and `bash scripts/gate_fast.sh`
- Failure mode if wrong:
  - accounting audit visibility widens again beyond the intended accounting-only scope
  - reconciliation cleanup leaves dead legacy paths or regresses the current explicit bank-reconciliation flow
  - Tally import failures remain opaque to operators
  - reviewers get misleading merge confidence from stale governance evidence

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: branch-local integration candidate pending PR review
- Basis: the repo is still pre-deployment with no external users, so current-state hard-cut cleanup is acceptable, but this branch still changes accounting runtime and audit visibility and therefore keeps explicit evidence in the same lane.

## Escalation Decision
- Human escalation required: no
- Reason: the lane tightens visibility and removes legacy cleanup debt without widening tenant boundaries or introducing destructive migration work; standard code review remains the merge gate.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the integrated packet stack together if audit, reconciliation, Tally, or docs truth becomes disputed
  - after merge: revert the packet stack and re-run compile, targeted accounting tests, docs lint, enterprise policy, and gate-fast to confirm rollback parity
- Rollback trigger:
  - audit visibility no longer stays accounting-only
  - reconciliation session flow or checklist diagnostics regress after the cleanup extraction
  - Tally import diagnostics become silent again
  - enterprise policy or gate-fast fails on the integrated lane

## Expiry
- Valid until: 2026-04-23
- Re-evaluate if: the lane expands beyond audit, reconciliation, Tally, and docs cleanup or introduces auth/RBAC/company-boundary changes.

## Verification Evidence
- Commands run:
  - `cd erp-domain && mvn -B -ntp -DskipTests compile`
  - `cd erp-domain && mvn -B -ntp -Djacoco.skip=true -Dtest='AuditVisibilityPolicyTest,ReconciliationServiceTest,BankReconciliationSessionServiceTest,TallyImportServiceTest,AccountingPeriodServiceTest,TS_AccountingPeriodCloseChecklistSafetyContractTest' test`
  - `bash ci/lint-knowledgebase.sh`
  - `bash ci/check-enterprise-policy.sh`
  - `bash scripts/gate_fast.sh`
- Result summary:
  - compile passed on the integrated lane after stacking audit, reconciliation, Tally, and docs packets
  - targeted audit, reconciliation, checklist, and Tally tests passed for the kept code paths
  - knowledgebase lint and enterprise-policy-check passed with the integrated docs and governance updates
  - gate-fast returned OK for the current branch diff, but changed-files coverage finished in compatibility mode because branch coverage was `109/126 = 0.865`, below the strict `0.90` threshold
- Artifact note:
  - changed-files coverage evidence is recorded inline in this checkpoint; no local artifact path is required for lint or review
