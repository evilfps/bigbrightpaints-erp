# R2 Checkpoint

## Scope
- Feature: `ERP-42 auth-v2 hard-cut canonicalization`
- Branch: `auth-v2-hard-cut`
- Review candidate: hard-cut auth identity to one scoped account per `(normalized_email, auth_scope_code)`, scope password-reset/MFA/session state to that account, align the auth packet with the merged ERP-37 superadmin control plane, and delete duplicate compatibility surfaces in the same packet.
- Related packet dependency: merged ERP-37 control-plane work on `main` at `251f809885267ea5a1706565c0c0be89ab5eb528`.
- Why this is R2: this packet rewrites login identity, password reset, refresh-token attribution, platform-scope control, and `migration_v2` auth storage. A wrong result can lock out real users, cross scopes, mis-route resets, or strand the superadmin control plane against the wrong auth contract.

## Risk Trigger
- Triggered by `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/**`, and `erp-domain/src/main/resources/db/migration_v2/V168__auth_v2_scoped_accounts.sql` plus `V169__auth_v2_single_company_account.sql`.
- Contract surfaces affected:
  - `POST /api/v1/auth/login`
  - `POST /api/v1/auth/password/forgot`
  - `POST /api/v1/auth/password/reset`
  - `/api/v1/admin/settings`
  - ERP-37 superadmin tenant-control and tenant-runtime policy flows that now run on the rebased auth-v2 packet
- Failure mode if wrong:
  - same-email different-company users bleed into each other
  - reset or MFA state crosses scope boundaries
  - platform-scope superadmin loses canonical company control access
  - `migration_v2` leaves ambiguous auth rows or refresh-token attribution behind

## Approval Authority
- Mode: human
- Approver: `human auth/control-plane reviewer`
- Canary owner: `ERP-42 packet owner`
- Approval status: `pending green CI and human review`
- Basis: this packet changes live auth identity and superadmin control semantics; green tests and policy gates are necessary but not sufficient.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet changes the canonical login/reset identity model, platform auth code semantics, and the tenant control plane that ERP-37 just merged onto `main`.

## Rollback Owner
- Owner: `ERP-42 packet owner`
- Rollback method: revert the auth-v2 packet before merge, or restore the tenant/database from a pre-`V168`/`V169` snapshot if those migrations have already executed. Do not reintroduce email-only reset, multi-company auth fallbacks, or split superadmin control routes as a rollback shortcut.
- Rollback trigger:
  - login or forgot-password targets the wrong scope
  - superadmin platform control loses canonical tenant-management access
  - refresh-token/session attribution crosses scopes
  - `migration_v2` produces ambiguous scoped identities or non-deterministic token mapping

## Expiry
- Valid until: `2026-04-03`
- Re-evaluate if: scope expands beyond the auth-v2 hard cut, the ERP-37 integration surface changes again on `main`, or new migration steps are added beyond `V168`/`V169`.

## Verification Evidence
- Commands run:
  - `git status --short --branch`
  - `mvn -B -ntp -Dtest=AuthTenantAuthorityIT test`
  - `mvn -B -ntp -Dtest=TenantRuntimeEnforcementServiceTest,CompanyControllerIT test`
  - `mvn -B -ntp -Dtest=TS_RuntimeTenantPolicyControlExecutableCoverageTest test`
  - `mvn -B -ntp -Dtest=AdminUserServiceTest test`
  - `bash ci/check-enterprise-policy.sh`
  - `mvn -B -ntp -Dtest=AdminSettingsControllerTenantRuntimeContractTest,PasswordResetServiceTest,TS_RuntimePasswordResetServiceExecutableCoverageTest test`
  - `mvn -B -ntp -Dtest=AuthPlatformScopeCodeIT,AuthTenantAuthorityIT,AuthPasswordResetPublicContractIT,AdminUserServiceTest,CompanyControllerIT,CompanyContextFilterControlPlaneBindingTest,SuperAdminControllerTest,SuperAdminTenantControlPlaneServiceTest,CompanyServiceTest,TenantAdminProvisioningServiceTest,TenantOnboardingServiceTest,PasswordResetServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimePasswordResetServiceExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeCompanyControllerExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_AuthV2ScopedAccountsMigrationContractTest test`
  - `mvn -B -ntp -Dtest=AuthPlatformScopeCodeIT,AuthTenantAuthorityIT,AuthPasswordResetPublicContractIT,AdminSettingsControllerTenantRuntimeContractTest,AdminUserServiceTest,CompanyControllerIT,CompanyContextFilterControlPlaneBindingTest,SuperAdminControllerTest,SuperAdminTenantControlPlaneServiceTest,CompanyServiceTest,TenantAdminProvisioningServiceTest,TenantOnboardingServiceTest,PasswordResetServiceTest,TenantRuntimeEnforcementServiceTest,TS_RuntimePasswordResetServiceExecutableCoverageTest,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeCompanyControllerExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest,TS_AuthV2ScopedAccountsMigrationContractTest test`
- Result summary:
  - rebased the auth-v2 packet onto `origin/main@251f809885267ea5a1706565c0c0be89ab5eb528` and aligned it with the merged ERP-37 superadmin control-plane semantics
  - renumbered the auth-v2 migration packet to `V168` and `V169`, updated the scoped-account migration contract, and kept the hard-cut single-company auth model as the only current-state path
  - fixed review-found edge cases including company-transfer scope collisions, platform-scope control-plane bypasses, delivery-bookkeeping reset handling, and deterministic scoped migration guards
  - hardened audit/log safety by removing request-shaped audit gaps in `AdminSettingsController` and sanitizing password-reset observability values before they hit logs
  - local enterprise policy now passes again, the focused controller/reset pack passed, and the refreshed broad auth/superadmin pack passed with `306` tests green after the latest changes
  - preserved the canonical ERP-37 company and tenant-runtime control routes under platform scope after the rebase
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/auth-v2-hard-cut`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/157`
  - Linear issue: `ERP-42`
