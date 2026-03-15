# R2 Checkpoint

## Scope
- Feature: `lane01-tenant-runtime-canonicalization`
- Branch: `packet/lane01-tenant-runtime-canonicalization-pr`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/**`
- Why this is R2: this packet changes the live tenant runtime control plane by retiring the admin-side writer, narrowing privileged entrypoints, and changing auth/filter/portal enforcement behavior that gates tenant access at runtime.

## Risk Trigger
- Triggered by canonicalization of tenant runtime policy ownership onto the company-scoped endpoint and removal of the stale `PUT /api/v1/admin/tenant-runtime/policy` contract.
- Contract change: runtime metrics now read from canonical enforcement snapshots and counters, `CompanyContextFilter` owns canonical admission attributes, and portal fallback enforcement now consumes the same request-scoped runtime truth.
- Failure mode if wrong: tenants could be blocked or admitted incorrectly, metrics could drift from enforcement truth, or callers could keep targeting a retired privileged writer path.

## Approval Authority
- Mode: orchestrator
- Approver: Factory packet review orchestration
- Basis: the change reduces privileged-path surface area, preserves a single canonical runtime-policy codepath, and ships with packet-level validation evidence for auth-tenant routing plus gate coverage.

## Escalation Decision
- Human escalation required: no
- Reason: this packet removes stale control-plane behavior rather than expanding permissions, introducing migrations, or altering destructive data flows; the main risk is runtime access correctness, which is already covered by packet tests and CI gates.

## Rollback Owner
- Owner: Factory-droid integration worker
- Rollback method: revert the packet commits on `packet/lane01-tenant-runtime-canonicalization-pr`, rerun `MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true test`, rerun `ENTERPRISE_DIFF_BASE=refs/remotes/origin/main bash ci/check-enterprise-policy.sh`, and revalidate the recorded `pr-auth-tenant`, `gate-fast`, and `gate-core` evidence before re-review.

## Expiry
- Valid until: 2026-03-29
- Re-evaluate if: any additional auth/company/admin/portal runtime paths are added, the canonical company-scoped writer changes again, or the CI routing/gate evidence for this packet is superseded by a follow-up.

## Verification Evidence
- Commands run: `MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest=OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true test` (`BUILD SUCCESS`, `2` tests, `0` failures/errors, using Colima-backed Docker for Testcontainers), `bash scripts/gate_fast.sh` (`exit 0` in `.factory/validation/lane01-tenant-runtime-canonicalization/scrutiny/synthesis.json`), `bash scripts/gate_core.sh` (`exit 0` in `.factory/validation/lane01-tenant-runtime-canonicalization/scrutiny/synthesis.json`), `bash scripts/run_test_manifest.sh --profile pr-fast --label auth-tenant --manifest ci/pr_manifests/pr_auth_tenant.txt` (`exit 0` in `.factory/validation/lane01-tenant-runtime-canonicalization/scrutiny/synthesis.json`), `ENTERPRISE_DIFF_BASE=refs/remotes/origin/main bash ci/check-enterprise-policy.sh` (`OK`).
- Result summary: the packet hard-cuts tenant runtime policy writes to the canonical company-scoped path, removes the retired admin writer and DTO, aligns runtime enforcement consumers on shared request attributes plus canonical snapshots/counters, refreshes `openapi.json`, and routes the packet into `pr-auth-tenant` coverage.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `.factory/validation/lane01-tenant-runtime-canonicalization/scrutiny/synthesis.json`, `.factory/validation/lane01-tenant-runtime-canonicalization/user-testing/synthesis.json`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/TenantRuntimeEnforcementService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/service/TenantRuntimeEnforcementInterceptor.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/OpenApiSnapshotIT.java`, and `openapi.json`.
