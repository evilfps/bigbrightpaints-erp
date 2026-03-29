# R2 Checkpoint

## Scope
- Feature: `ERP-39 integrated security remediation wave`
- Branch: `packet/erp-39-hardcut-integration`
- Baseline head: `53873362b0f9e10ab9e7b587ee6aa79163023e7a`
- Review candidate:
  - hard-fail suspended tenant runtime access and tighten tenant/accounting scope checks
  - remove raw settlement idempotency keys from public audit and linked-reference surfaces
  - preserve signed inventory revaluation deltas and reject settlement-date replay drift
  - defer implicit auto-settlement replay keys to resolved allocation state
  - harden export ownership, dealer credit exposure, supplier read visibility, runtime error redaction, and CI/release guard paths
  - add `migration_v2/V176__opening_stock_content_fingerprint.sql` so opening-stock replay protection survives caller-supplied batch or idempotency-key churn
- Why this is R2: the packet changes live accounting, tenant access, company onboarding, and schema enforcement paths. A wrong cut can break settlement replay guarantees, expose sensitive audit data, reject valid tenant traffic, or let duplicate opening-stock imports through during rollout.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
  - `erp-domain/src/main/resources/db/migration_v2/V176__opening_stock_content_fingerprint.sql`
- Contract surfaces affected:
  - settlement replay/idempotency semantics
  - accounting audit detail and linked-reference payloads
  - inventory revaluation posting polarity
  - tenant lifecycle enforcement and onboarding policy sync
  - opening-stock replay protection across repeated file imports
- Failure mode if wrong:
  - valid revaluation write-downs could post with the wrong sign
  - settlement replays could silently accept different effective dates or different resolved allocation sets
  - audit surfaces could continue leaking raw client idempotency keys
  - suspended tenants could keep runtime access or onboarding policy could drift from current controls
  - opening-stock imports could be replayed under new batch keys after the migration lands

## Approval Authority
- Mode: human
- Approver: `ERP-39 owner`
- Canary owner: `ERP-39 owner`
- Approval status: `pending human review; draft PR #173 is the current candidate`
- Basis: this is a destructive hard-cut across accounting, tenant policy, and migration surfaces, so technical green alone is not sufficient for deployment approval.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet changes accounting replay controls plus a `migration_v2` contract, so deployment should not rely on automated gate success alone.

## Rollback Owner
- Owner: `ERP-39 owner`
- Rollback method:
  - before merge: abandon `packet/erp-39-hardcut-integration` and do not promote the artifact
  - after merge but before deploy: revert the ERP-39 packet commits together; do not keep the migration/docs changes while dropping the runtime fixes
  - after deploy: keep the ERP-39-compatible backend live unless the database is first restored to a pre-`V176` snapshot/PITR state
  - do not hand-edit settlement replay rows, tenant policy state, or opening-stock fingerprints toward mixed legacy/current behavior
- Rollback trigger:
  - accounting audit consumers require the removed raw idempotency key surface
  - settlement replay rejects valid same-date replays or still accepts drifted effective dates/open-item sets
  - opening-stock import replay behavior diverges from the verified fingerprint-based contract
  - tenant runtime access or onboarding policy enforcement regresses after deploy

## Expiry
- Valid until: `2026-04-05`
- Re-evaluate if: scope widens beyond ERP-39, another `migration_v2` change lands, or the approver/canary/rollback owners change.

## Verification Evidence
- Commands run:
  - `MIGRATION_SET=v2 mvn -q -Dtest=AccountingServiceTest#settleDealerInvoices_replayPartnerMismatchIncludesPartnerDetails+settleDealerInvoices_idempotentReplayReturnsSameCashAmount+settleSupplierInvoices_replayPartnerMismatchIncludesPartnerDetails test`
  - `MIGRATION_SET=v2 mvn -q -Dtest=AccountingServiceTest,AccountingAuditTrailServiceTest,SettlementServiceTest,TruthRailsSharedDtoContractTest,LandedCostRevaluationIT,AccountingControllerJournalEndpointsTest,AccountingControllerExceptionHandlerTest test`
  - `ENTERPRISE_DIFF_BASE=53873362b0f9e10ab9e7b587ee6aa79163023e7a bash ci/check-enterprise-policy.sh`
- Result summary:
  - integrated replay/date guard regressions are covered and green on the branch head
  - the integrated accounting, audit, settlement, controller, and revaluation suite passed on `28d43192345ffd0981677c966f174facb7acb597`
  - enterprise-policy is expected to pass once this checkpoint and the matching migration/rollback runbook updates are included in the PR diff
- Artifacts/links:
  - repo checkout: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-39-hardcut-integration`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/173`
  - Linear issue: `https://linear.app/orchestratorerp/issue/ERP-39/security-findings-verification-and-grouped-remediation-triage-from`
