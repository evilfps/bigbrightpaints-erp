# R2 Checkpoint

## Scope
- Feature: `preflight-review-and-merge-gate`
- Published review chain: PR #90 `review: preflight review and merge-gate closure`; PR #91 `review: lane 01 control-plane runtime packet`
- Base / branch lineage: `Factory-droid` -> `packet/preflight-review-and-merge-gate` -> `packet/lane01-control-plane-runtime`
- High-risk packet commit: `4ef5f4e1` (`fix(auth): surface reset persistence failures and preserve revocation ordering`)
- Why this is R2: the published review stack still carries a narrow auth/security merge-gate packet that changes password-reset persistence handling and token-revocation ordering in security-sensitive paths.

## Risk Trigger
- Triggered by `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/service/PasswordResetService.java` and `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/TokenBlacklistService.java`.
- Contract surfaces affected: `POST /api/v1/auth/password/forgot` and access-token revocation enforcement on protected auth surfaces.
- Regression guardrails re-proved alongside the packet: `AdminUserServiceTest`, `AdminUserSecurityIT`, `TenantRuntimeEnforcementServiceTest`, and `TS_RuntimeTenantPolicyControlExecutableCoverageTest`.
- Main risks being controlled: false-success masking of reset-token persistence failures, same-millisecond revocation ordering drift, and accidental widening beyond the narrow merge-gate packet.

## Approval Authority
- Mode: orchestrator
- Approver: Factory-droid packet/governance orchestration for PR #90 and PR #91
- Basis: compatibility-preserving high-risk remediation required before Lane 01 review can proceed and before any Lane 02 remote publication.

## Escalation Decision
- Human escalation required: no
- Reason: this follow-up only restores governance evidence for the already-published merge-gate packet and does not widen product behavior, privileges, or migration scope.

## Rollback Owner
- Owner: Factory-droid packet/governance worker
- Rollback method: revert commit `4ef5f4e1`, refresh PR #90 / PR #91 to remove the merge-gate packet, then rerun the targeted merge-gate regression pack and `mvn test -Pgate-fast -Djacoco.skip=true` before any new merge recommendation.

## Expiry
- Valid until: 2026-03-14
- Re-evaluate if: PR #90 / PR #91 add new auth/company/orchestrator logic, the merge-gate packet scope widens beyond the listed files and guardrails, or any Lane 02 packet is prepared for remote publication.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=TokenBlacklistServiceTest,AuthPasswordResetPublicContractIT,AdminUserServiceTest,AdminUserSecurityIT,TenantRuntimeEnforcementServiceTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest'`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `git show --stat --name-only --format=fuller 4ef5f4e1`; `git diff -- openapi.json .factory/library/frontend-handoff.md docs/frontend-update-v2 docs/code-review/executable-specs`; `REVIEW_POLICY_DIFF_BASE=b93c91aa6d703d75708df66a5bb6f805f7e47154 bash /home/realnigga/Desktop/Mission-control/scripts/enforce_codex_review_policy.sh`; `ENTERPRISE_DIFF_BASE=b93c91aa6d703d75708df66a5bb6f805f7e47154 bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh`
- Result summary: the targeted merge-gate regression pack stayed green, `gate-fast` passed with 395 tests and 0 failures, commit `4ef5f4e1` remained limited to the two production files plus three regression tests, packet parity evidence stayed attached, and the repo-root review-policy / R2 governance artifacts now exist for the published preflight and Lane 01 review chain.
- Artifacts/links: `docs/code-review/executable-specs/00-preflight-review-merged-auth-company-admin-hardening.md`, `docs/code-review/executable-specs/00-current-auth-merge-gate.md`, `docs/code-review/executable-specs/00-current-auth-merge-gate-release-gate.md`, `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/90`, `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/91`, `AGENTS.md`, `docs/SECURITY.md`, `docs/agents/PERMISSIONS.md`, `docs/agents/CATALOG.md`
