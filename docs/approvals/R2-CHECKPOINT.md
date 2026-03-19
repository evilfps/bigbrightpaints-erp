# R2 Checkpoint

## Scope
- Feature: `ERP-18 Packet 1: Roles and authorization contract cleanup`
- PR: `#122`
- Runtime candidate SHA: `61da90f91fdd2c098470058b20faf56199265b43`
- Diff base SHA: `c74a2f6cf09b45dff9398d02699e2474777b2cf6`
- Branch: `feature/erp-stabilization-program--control-plane`
- Why this is R2: this packet changes high-risk `auth`, `rbac`, and `company` runtime paths, removes dynamic role creation from provisioning/admin flows, hard-cuts `ROLE_SUPER_ADMIN` from admin and client-facing assignment surfaces, and changes the public `/api/v1/auth/me` contract to the stable frontend-safe `companyCode` shape only.

## Risk Trigger
- Triggered by changes under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/`, which are guarded as high-risk enterprise paths.
- Contract surfaces affected: tenant admin provisioning, admin user assignment, admin role catalog visibility, authorization checks via the shared role-action matrix, `/api/v1/auth/me`, OpenAPI, and frontend handoff docs.
- Failure mode if wrong: tenant admins could create or assign unsupported roles, admin/client surfaces could expose platform-owner `ROLE_SUPER_ADMIN`, or frontend/session consumers could receive stale/raw auth DTO fields and mis-handle session state.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Approval status: `approved for PR validation and merge review on ERP-18 Packet 1 scope only`
- Basis: the approver explicitly fixed the product decision for this packet to the six-role model only, with `ROLE_SUPER_ADMIN` reserved to the platform owner and never visible or assignable by admin/client surfaces; the packet also keeps the hard-cut rule of no compatibility bridges or raw DTO leakage.

## Escalation Decision
- Human escalation required: no
- Reason: packet scope, superadmin visibility/assignment policy, and the stable auth/session contract direction were explicitly decided by the approver above. Re-escalate only if scope broadens beyond Packet 1, new role types are introduced, or `/api/v1/auth/me` becomes ambiguous again.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the Packet 1 commits as a full rollback on the worker branch, rerun the focused auth/rbac proof set, and restore the previous integration-branch behavior only via that revert. Do not keep a mixed compatibility path.
- Rollback trigger:
  - admin or provisioning flows fail to resolve required fixed roles
  - any admin/client-facing surface exposes or assigns `ROLE_SUPER_ADMIN`
  - `/api/v1/auth/me` or OpenAPI drifts away from the documented `companyCode`-only response contract
  - auth/tenant-isolation integration coverage regresses on the rerun packet proof

## Expiry
- Valid until: `2026-03-26`
- Re-evaluate if: the PR head SHA changes again, Packet 1 scope expands, the auth/session contract changes, or approval/rollback ownership changes.

## Verification Evidence
- Commands run:
  - `mvn -B -ntp -Dtest=AdminUserServiceTest,TenantAdminProvisioningServiceTest,RoleServiceTest,RoleServiceRbacTenantIsolationTest,RbacSynchronizationConfigTest,RoleControllerSecurityContractTest,DealerServiceTest,AuthControllerIT,AuthTenantAuthorityIT test`
  - `mvn -B -ntp -Dtest=OpenApiSnapshotIT -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true test`
  - `mvn -B -ntp -Dtest=AdminUserServiceTest,TenantAdminProvisioningServiceTest,TenantOnboardingServiceTest,RoleServiceTest,RoleServiceRbacTenantIsolationTest,RbacSynchronizationConfigTest,RoleControllerSecurityContractTest,AuthControllerIT,AuthTenantAuthorityIT,OpenApiSnapshotIT,TS_RuntimeTenantPolicyControlExecutableCoverageTest test`
  - `bash scripts/guard_openapi_contract_drift.sh`
  - `bash scripts/guard_accounting_portal_scope_contract.sh`
  - `git diff --check`
- Result summary:
  - targeted role/auth proof passed with `103` tests
  - OpenAPI snapshot proof passed with `2` tests
  - final decisive Packet 1 proof passed with `97` tests
  - contract guards passed and `git diff --check` was clean before PR creation
  - `openapi.json` checksum after the contract cleanup: `b3d3352d68808f269b3cb424e2105a217962d66e5d559ef8743363979c153281`
- Artifacts/links:
  - `docs/code-review/flows/admin-governance.md`
  - `docs/code-review/flows/auth-identity.md`
  - `docs/code-review/flows/company-tenant-control-plane.md`
  - `docs/frontend-update-v2/auth-compatibility-regression-handoff.md`
  - `.factory/library/frontend-handoff.md`
  - `openapi.json`
  - `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/122`

## Reviewer Notes
- No `migration_v2` files changed in this packet.
- High-risk runtime logic changes are covered by updated tests under `erp-domain/src/test/java`; no waiver is being used.
