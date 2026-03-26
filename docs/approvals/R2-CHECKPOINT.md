# R2 Checkpoint

## Scope
- Feature: `ERP-37 hard-cut superadmin control plane`
- Branch: `mdanas7869292/erp-37-hard-cut-superadmin-and-tenant-control-plane-onto-one`
- Review candidate: collapse tenant control onto `/api/v1/superadmin/tenants/{id}/...`, remove duplicate company/admin tenant-control families, hard-cut changelog ownership to superadmin writes plus authenticated reads, and ship `migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql` with matching OpenAPI/docs truth.
- Why this is R2: the packet changes tenant-boundary routing, runtime admission binding, authenticated recovery surfaces, and `migration_v2` persistence for lifecycle/quota/onboarding/support/admin-governance truth.

## Risk Trigger
- Triggered by `erp-domain/src/main/resources/db/migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/controller/{SuperAdminController,SuperAdminTenantOnboardingController,CompanyController}.java`, and the paired changelog/auth/control-plane contract surfaces.
- Contract surfaces affected: `/api/v1/superadmin/**`, `/api/v1/changelog*`, `openapi.json`, `docs/endpoint-inventory.md`, `.factory/library/frontend-handoff.md`, `docs/workflows/admin-and-tenant-management.md`, and the ERP-37 code-review flow docs.
- Failure mode if wrong: callers could integrate against retired control-plane routes, tenant binding could drift across route families, lifecycle/quota storage could disagree with runtime enforcement, or admin-governance/support recovery actions could target the wrong tenant or stale main-admin state.

## Approval Authority
- Mode: ERP packet
- Approver: `human R2 reviewer`
- Canary owner: `ERP-37 packet owner`
- Approval status: `pending green validators and human review`
- Basis: this packet modifies tenant-boundary behavior plus `migration_v2`; green tests are necessary but not sufficient.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet changes live tenant routing, persistence shape, lifecycle vocabulary, and authenticated recovery/control-plane behavior in the same branch.

## Rollback Owner
- Owner: `ERP-37 packet owner`
- Rollback method: keep the ERP-37-compatible backend live or restore the tenant/database from a pre-`V167` snapshot before redeploying an older build; do not reintroduce retired route families as compatibility shims.
- Rollback trigger:
  - runtime probes or focused regressions show any retired `/api/v1/companies/{id}/...`, `/api/v1/admin/changelog*`, or `/api/v1/admin/tenant-runtime/*` surface reappearing
  - tenant detail, support timeline, or main-admin governance drifts from the persisted company/support truth
  - lifecycle/quota behavior disagrees with the canonical `ACTIVE/SUSPENDED/DEACTIVATED` plus `quotaMaxConcurrentRequests` contract after migration

## Expiry
- Valid until: `2026-04-02`
- Re-evaluate if: scope expands beyond ERP-37 tenant-control, changelog-ownership, and `V167` migration work.

## Verification Evidence
- Commands run:
  - `git status --short --branch`
  - `git rev-parse --short HEAD`
  - `colima status`
  - `cd erp-domain && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 && MIGRATION_SET=v2 mvn -Dtest=CompanyControllerIT,SuperAdminControllerIT,TenantOnboardingControllerTest,ChangelogControllerSecurityIT,AuthPasswordResetPublicContractIT,AdminUserSecurityIT,TenantRuntimeEnforcementServiceTest,TenantRuntimeEnforcementAuthIT,SuperAdminTenantWorkflowIsolationIT,PortalInsightsControllerIT,ReportControllerSecurityIT,AuthTenantAuthorityIT,CompanyQuotaContractTest,AdminSettingsControllerTenantRuntimeContractTest,CompanyContextFilterControlPlaneBindingTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantRuntimeEnforcementTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TenantAdminProvisioningServiceTest test`
  - `cd erp-domain && export DOCKER_HOST=unix:///Users/anas/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.64.2 && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
- Result summary:
  - focused integration/truth coverage proves the canonical superadmin tenant-control plane, fail-closed onboarding delivery, authenticated changelog reads, superadmin-only changelog writes, and canonical `CompanyContextFilter` target-tenant binding.
  - the refreshed `openapi.json` now matches the live ERP-37 controller surface, including `204` superadmin changelog delete semantics.
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-37-hard-cut-superadmin-control-plane`
  - Migration: `erp-domain/src/main/resources/db/migration_v2/V167__erp37_superadmin_control_plane_hard_cut.sql`
