# R2 Checkpoint

## Scope
- Feature: `auth-merge-gate-hardening`
- Branch: `packet/lane02-auth-merge-gate-hardening`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthPasswordResetPublicContractIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/AdminUserSecurityIT.java`, `erp-domain/pom.xml`, and this approval record.
- Why this is R2: this packet changes public password-reset failure handling and after-commit cleanup durability on a live auth surface, while also changing which auth/admin regressions are required in `pr-fast` and `gate-fast`.

## Risk Trigger
- Triggered by auth-sensitive public forgot-password behavior changes plus merge-gate membership updates for auth/admin regressions.
- Contract surfaces affected: public forgot-password masked failure classification, after-commit token cleanup durability, AUTH-09 regression coverage, ADMIN-14 regression coverage, and the `pr-fast` / `gate-fast` merge lanes.
- Failure mode if wrong: non-persistence failures could be mislabeled as `SYS_003`, cleanup after dispatch failure could be non-durable, or required auth/admin regressions could silently fall out of the merge gate.

## Approval Authority
- Mode: orchestrator
- Approver: ERP auth merge-gate hardening mission orchestration
- Basis: the packet hardens existing auth behavior and merge-gate coverage without widening tenant boundaries, expanding privileges, or introducing migration risk.

## Escalation Decision
- Human escalation required: no
- Reason: the change preserves current-state auth contracts and tightens durability/coverage; it does not widen access or add destructive data-path risk.

## Rollback Owner
- Owner: ERP-5 PR 116 remediation worker
- Rollback method: revert the remediation commit set on `packet/lane02-auth-merge-gate-hardening`, then rerun `cd erp-domain && mvn -Dtest=PasswordResetServiceTest test`, `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`, `bash ci/check-architecture.sh`, `bash ci/check-enterprise-policy.sh`, and `bash ci/check-orchestrator-layer.sh`.

## Expiry
- Valid until: 2026-03-24
- Re-evaluate if: public reset behavior changes again, merge-gate membership changes again, or the validation evidence below is superseded before merge.

## Verification Evidence
- Commands run:
  - `cd erp-domain && mvn compile -q`
  - `cd erp-domain && mvn -Dtest=PasswordResetServiceTest,AuthPasswordResetPublicContractIT test`
  - `cd erp-domain && mvn -Dtest=SalesControllerIT,AdminUserSecurityIT test`
  - `cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true`
  - `bash ci/check-architecture.sh`
  - `bash ci/check-enterprise-policy.sh`
  - `bash ci/check-orchestrator-layer.sh`
- Result summary:
  - `mvn compile -q`: passed on 2026-03-17 with exit 0.
  - `mvn -Dtest=PasswordResetServiceTest,AuthPasswordResetPublicContractIT test`: passed on 2026-03-17 with `58` tests, `0` failures, `0` errors, `0` skipped, covering transaction-layer persistence failure classification plus masked-success handling for unexpected non-persistence runtime failures.
  - `mvn -Dtest=SalesControllerIT,AdminUserSecurityIT test`: passed on 2026-03-17 with `25` tests, `0` failures, `0` errors, `0` skipped after syncing stale company-context and tenant-runtime-policy test expectations from the merged `origin/main` state.
  - `mvn test -Pgate-fast -Djacoco.skip=true`: passed on 2026-03-17 with `728` tests, `0` failures, `0` errors, `0` skipped.
  - `bash ci/check-architecture.sh`: passed on 2026-03-17 with `OK` plus compatibility-mode warnings for unresolved cross-module imports / missing legacy orchestrator catalog.
  - `bash ci/check-enterprise-policy.sh`: passed on 2026-03-17 with `[enterprise-policy] OK`.
  - `bash ci/check-orchestrator-layer.sh`: passed on 2026-03-17 with `[orchestrator-layer] OK` plus compatibility-mode warning for missing legacy orchestrator-layer contract files.
- Artifacts/links:
  - Current merged-branch validation was executed from the `erp-domain` module on `packet/lane02-auth-merge-gate-hardening` after resolving the `origin/main` merge conflicts and replaying the narrow PR 116 remediation.
