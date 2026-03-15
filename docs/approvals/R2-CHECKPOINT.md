# R2 Checkpoint

## Scope
- Feature: `auth-merge-gate-regression-coverage`
- Branch: `packet/lane01-tenant-runtime-canonicalization-pr`
- High-risk paths touched: auth/admin regression security surfaces under `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthPasswordResetPublicContractIT.java` and `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/AdminUserSecurityIT.java`, merge-gate profile selection in `erp-domain/pom.xml`, and this approval record.
- Why this is R2: this packet strengthens merge-gate enforcement for AUTH-09 and ADMIN-14 regressions on auth/admin control boundaries, so approval evidence must be attached in the same diff to prove the gate catches these regressions before merge.

## Risk Trigger
- Triggered by security-sensitive auth/admin regression coverage updates that alter merge-gate execution membership.
- Contract surfaces affected: public forgot-password persistence-failure contract (AUTH-09), tenant-admin foreign-user masked non-blocking lock behavior (ADMIN-14), and the gate-fast test lane.
- Main risks being controlled: regressions silently dropping out of gate-fast scope, accidental flaky/quarantine routing, and missing high-risk governance evidence for auth/admin merge-gate updates.

## Approval Authority
- Mode: orchestrator
- Approver: ERP auth merge-gate hardening mission orchestration
- Basis: compatibility-preserving hardening that does not widen tenant boundaries or privilege scope.

## Escalation Decision
- Human escalation required: no
- Reason: this packet only updates regression coverage eligibility and governance evidence; no privilege expansion, tenant-boundary widening, or migration risk is introduced.

## Rollback Owner
- Owner: security backend worker (session `12cd9f51-a6f8-46ee-81a1-4c74c4b4c0e8`)
- Rollback method: revert the packet commit, then rerun `cd erp-domain && MIGRATION_SET=v2 mvn -Pgate-fast -Djacoco.skip=true -Dtest=AuthPasswordResetPublicContractIT,AdminUserSecurityIT test`, `bash scripts/gate_fast.sh`, `bash ci/check-enterprise-policy.sh`, and `bash ci/check-codex-review-guidelines.sh`.

## Expiry
- Valid until: 2026-03-23
- Re-evaluate if: any auth/admin regression test path, gate-fast profile include list, or enterprise-policy requirements change before merge.

## Verification Evidence
- Commands run: `cd erp-domain && MIGRATION_SET=v2 mvn -Pgate-fast -Djacoco.skip=true -Dtest=AuthPasswordResetPublicContractIT,AdminUserSecurityIT test` (pass: `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`); `bash scripts/gate_fast.sh` (pass with `catalog-validation.json`/`flake-guard.json`/`changed-coverage.json` emitted under `artifacts/gate-fast/` and gate summary `OK`); `bash ci/check-enterprise-policy.sh` (pass with high-risk enforcement line `high-risk paths changed; enforcing R2 enterprise controls`); `bash ci/check-codex-review-guidelines.sh` (pass with wrapper summary `codex-review-guidelines OK`).
- Result summary: AUTH-09 and ADMIN-14 regression suites are now tagged `critical` and explicitly included in `pr-fast` + `gate-fast` surefire include lists, gate-fast executes both suites in the merge lane, and required policy/review gates complete successfully for this packet.
- Artifacts/links: `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/AuthPasswordResetPublicContractIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/AdminUserSecurityIT.java`, `erp-domain/pom.xml`, `scripts/gate_fast.sh`, `ci/check-enterprise-policy.sh`, `ci/check-codex-review-guidelines.sh`, `docs/approvals/R2-CHECKPOINT.md`.
