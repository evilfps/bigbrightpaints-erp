# R2 Checkpoint

## Scope
- Feature: `lane-02-auth-secrets-incident`
- Published review chain: PR #91 `review: lane 01 control-plane runtime packet`; PR #93 `review: lane 02 auth secrets incident packet`
- Base / branch lineage: `Factory-droid` -> `packet/preflight-review-and-merge-gate` -> `packet/lane01-control-plane-runtime` -> `packet/lane02-auth-secrets-incident`
- High-risk packet commits: `b4ff6e08` (`fix(company): stop exposing onboarding temp credentials`), `dbdbf27a` (`fix(auth): lock recovery contract parity`), `b130febf` (`fix(company): fail closed when onboarding credential delivery is disabled`)
- Why this is R2: the published Lane 02 packet changes auth and tenant-onboarding behavior on privileged control-plane flows, so the review branch needs explicit approval evidence tied to the final packet scope before merge.

## Risk Trigger
- Triggered by `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantOnboardingService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/dto/TenantOnboardingResponse.java`, and the password-recovery contract surfaces documented in `openapi.json`.
- Contract surfaces affected: `POST /api/v1/superadmin/tenants/onboard`, `POST /api/v1/auth/password/forgot`, `POST /api/v1/auth/password/forgot/superadmin`, and `POST /api/v1/companies/{id}/support/admin-password-reset`.
- Regression guardrails re-proved alongside the packet: `AuthControllerIT`, `AuthHardeningIT`, `AuthPasswordResetPublicContractIT`, `AuthTenantAuthorityIT`, `PasswordResetServiceTest`, `PasswordServiceTest`, `RefreshTokenServiceIT`, `TokenBlacklistServiceTest`, `AdminUserSecurityIT`, `AdminUserServiceTest`, `TenantAdminProvisioningServiceTest`, `OpenApiSnapshotIT`, and `TenantOnboardingControllerTest`.
- Main risks being controlled: temporary credential leakage, successful tenant bootstrap when credential delivery is disabled, drift between retired recovery aliases and supported reset paths, and packet widening beyond the documented Lane 02 auth/onboarding scope.

## Approval Authority
- Mode: orchestrator
- Approver: Factory-droid packet/governance orchestration for PR #93
- Basis: compatibility-preserving auth/company remediation within the approved Lane 02 packet scope, with no privilege widening or destructive migration behavior.

## Escalation Decision
- Human escalation required: no
- Reason: the packet removes credential exposure, fails closed on delivery-disabled onboarding, and locks published recovery parity without widening tenant boundaries or introducing destructive schema changes.

## Rollback Owner
- Owner: Factory-droid packet/governance worker
- Rollback method: revert `b130febf`, `dbdbf27a`, and `b4ff6e08` from `packet/lane02-auth-secrets-incident`, then rerun the targeted Lane 02 auth/admin/OpenAPI regression pack plus `mvn -T8 test -Pgate-fast -Djacoco.skip=true` before making any new merge recommendation.

## Expiry
- Valid until: 2026-03-14
- Re-evaluate if: PR #93 widens beyond the listed auth/company/OpenAPI surfaces, changes tenant-boundary semantics, or adds migration work outside the already-reviewed digest-storage compatibility path.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AuthControllerIT,AuthHardeningIT,AuthPasswordResetPublicContractIT,AuthTenantAuthorityIT,PasswordResetServiceTest,PasswordServiceTest,RefreshTokenServiceTest,TokenBlacklistServiceTest,AdminUserSecurityIT,AdminUserServiceTest,TenantAdminProvisioningServiceTest,OpenApiSnapshotIT,TenantOnboardingControllerTest'`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `bash /home/realnigga/Desktop/Mission-control/ci/lint-knowledgebase.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-architecture.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh && bash /home/realnigga/Desktop/Mission-control/ci/check-orchestrator-layer.sh && python3 /home/realnigga/Desktop/Mission-control/scripts/check_flaky_tags.py --tests-root /home/realnigga/Desktop/Mission-control/erp-domain/src/test/java --gate gate-fast && bash /home/realnigga/Desktop/Mission-control/scripts/guard_openapi_contract_drift.sh`; `git diff --name-only packet/lane01-control-plane-runtime...packet/lane02-auth-secrets-incident`; `ENTERPRISE_DIFF_BASE=86b38cde5b8910f39fb7365e2069869f83f67e60 bash /home/realnigga/Desktop/Mission-control/ci/check-enterprise-policy.sh`
- Result summary: the targeted Lane 02 regression pack passed, `gate-fast` stayed green with 395 tests and 0 failures, the final Lane 02 diff remained constrained to onboarding/auth/OpenAPI/handoff/review artifacts, and the R2 checkpoint now matches the exact PR #93 packet scope required by enterprise policy.
- Artifacts/links: `docs/code-review/executable-specs/02-lane-auth-secrets-incident/01-lane02-release-review.md`, `docs/code-review/executable-specs/02-lane-auth-secrets-incident/01-lane02-release-gate.md`, `.factory/validation/lane-02-auth-secrets-incident/scrutiny/synthesis.json`, `.factory/validation/lane-02-auth-secrets-incident/user-testing/synthesis.json`, `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/93`
