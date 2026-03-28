# R2 Checkpoint

## Scope
- Feature: `ERP-45 Wave 1 fail-closed platform hardening`
- Branch: `mdanas7869292/erp-45-wave-1-fail-closed-blockers-and-platformapi-surface-cleanup`
- PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/166`
- Review candidate:
  - fail closed all targeted tenant control-plane mutations when the bound company context is blank or mismatched
  - keep prod crypto and secret contracts explicit with no runtime fallback path
  - keep admin/RBAC read surfaces canonical and fail closed for unknown platform roles
  - preserve the runtime-enforcement naming split between request admission and policy ownership
  - remove retired API surface and refresh OpenAPI and endpoint inventory to the current canonical routes only
- Why this is R2: this PR changes high-risk `company`, `auth`, `rbac`, and `orchestrator` runtime paths plus the prod secret contract. A wrong merge could widen authority, bypass tenant isolation, or ship a prod profile that starts without required signing material.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/**`
  - `erp-domain/src/main/resources/application-prod.yml`
- Contract surfaces affected:
  - superadmin tenant mutation flows under `/api/v1/superadmin/tenants/**`
  - admin role lookup under `/api/v1/admin/roles/{roleKey}`
  - prod actuator, health, and swagger exposure rules
  - retired orchestrator and catalog endpoints removed from the canonical API surface
- Failure mode if wrong:
  - blank or mismatched tenant context could still mutate another tenant
  - prod startup or runtime audit signing could drift into implicit fallback behavior
  - admin callers could receive fabricated RBAC data instead of a hard failure
  - stale endpoint aliases could remain callable after the hard cut

## Approval Authority
- Mode: human
- Approver: `ERP-45 owner`
- Canary owner: `ERP-45 owner`
- Approval status: `pending green CI + reviewer confirmation`
- Basis: this wave changes tenant isolation and prod security contracts, so merge still requires explicit human signoff after automated proof is green.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet set changes authorization boundaries, tenant control-plane mutation guards, and prod secret requirements.

## Rollback Owner
- Owner: `ERP-45 owner`
- Rollback method:
  - revert PR `#166` and redeploy the last green `main` build as one unit
  - if rollback is triggered after prod verification starts, restore the previous application build and previous env secret set together so prod secret and audit-signing contracts stay aligned
- Rollback trigger:
  - tenant mutation succeeds with blank or mismatched company context
  - prod profile fails hard on required secret/crypto readiness after merge
  - reviewer or CI evidence shows retired endpoints or fabricated RBAC responses still present

## Expiry
- Valid until: `2026-04-04`
- Re-evaluate if: Wave 1 scope expands, any new high-risk auth/company/orchestrator path is added, or reviewer feedback asks for further authorization or API-surface changes.

## Verification Evidence
- Commands run:
  - `MIGRATION_SET=v2 mvn -B -ntp -DskipTests compile`
  - `MIGRATION_SET=v2 mvn -B -ntp -Dtest=CompanyServiceTest,RoleControllerSecurityContractTest,TenantRuntimeRequestAdmissionServiceTest test`
  - `MIGRATION_SET=v2 mvn -B -ntp -Dtest=CR_ActuatorProdHardeningIT,CR_HealthEndpointProdHardeningIT,CR_SwaggerProdHardeningIT,CR_PayrollLegacyEndpointGatedIT,CR_DispatchOrderLookupReadOnlyIT test`
  - `bash ci/check-enterprise-policy.sh`
  - `bash ci/check-codex-review-guidelines.sh`
  - `bash scripts/gate_fast.sh`
- Result summary:
  - Wave 1 remains fail closed on tenant mutation context, RBAC unknown-role lookup, prod secret readiness, and retired endpoint removal
  - changed-file coverage follow-up is included in the same PR so the high-risk diff is fully mapped to packet-local tests
  - final command outcomes are refreshed on the PR branch before merge
- Artifacts/links:
  - Repo checkout: `/Users/anas/Documents/Factory/bigbrightpaints-erp`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/166`
  - Linear issue: `ERP-45`
