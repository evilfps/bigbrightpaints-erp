# R2 Checkpoint

Last reviewed: 2026-04-16

## Scope
- Feature: `tenant-admin-backend-hard-cut` slices 1-2 + slice 8 pending-status index hardening
- Branch: codex/tenant-admin-hardcut-s1 (base: `fc3266800`)
- PR: pending
- Review candidate:
  - keep tenant-admin user assignment constrained to `ROLE_ACCOUNTING`, `ROLE_FACTORY`, `ROLE_SALES`, `ROLE_DEALER`
  - keep tenant-admin `ROLE_ADMIN` / `ROLE_SUPER_ADMIN` assignment denied with explicit access-denied auditing
  - keep tenant-admin custom/unknown role creation removed from the admin users workflow surface
  - keep superadmin settings/roles/notify control-plane hosts platform-scope-only for superadmin callers
  - keep denied role-mutation audit body extraction fail-closed when request body cache is unavailable
  - keep normalized pending-status predicates (`upper(trim(status))='PENDING'`) index-backed for tenant-admin approval inbox/dashboard queries
- Why this is R2: this packet changes high-risk auth/RBAC and superadmin control-plane enforcement behavior under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/AdminUserService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/RequestBodyCachingFilter.java`; it also changes `erp-domain/src/main/resources/db/migration_v2/` in slice 8 to keep normalized pending query predicates index-backed.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/service/AdminUserService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/RequestBodyCachingFilter.java`
  - `erp-domain/src/main/resources/db/migration_v2/V183__credit_pending_status_norm_indexes.sql`
- Contract surfaces affected:
  - tenant-admin create/update user role validation and assignment behavior
  - role-escalation denial semantics for tenant-admin actors
  - audit failure metadata for blocked privileged role attempts
  - platform-scope host enforcement for superadmin settings/role/notify control-plane routes
  - denied-path role target extraction behavior on oversized/uncached request bodies
  - tenant-admin dashboard and approval inbox pending-status lookup performance under normalized status semantics
- Failure mode if wrong:
  - tenant-admin could assign privileged or unknown roles
  - tenant-scoped superadmin sessions could execute platform-only notify/settings/roles endpoints
  - denied-path auditing could attempt raw request-stream reads instead of failing closed
  - audit trail for blocked role escalation could become inconsistent
  - tenant-admin dashboard approval summary could degrade under polling load if normalized status predicates are not index-backed

## Approval Authority
- Mode: orchestrator
- Approver: Droid mission orchestrator
- Canary owner: Droid mission orchestrator
- Approval status: branch-local integration candidate pending PR review
- Basis: this is a hard-cut tenant-admin RBAC tightening with no compatibility bridge; rollout remains pre-deployment but still requires explicit R2 evidence because auth/RBAC semantics changed.

## Escalation Decision
- Human escalation required: no
- Reason: this packet narrows tenant-admin privileges, hardens platform-only superadmin boundaries, and keeps denied-path parsing fail-closed; it does not widen tenant boundaries or introduce destructive migration behavior.

## Rollback Owner
- Owner: Droid mission orchestrator
- Rollback method:
  - before merge: revert the packet if tenant-admin role assignment or superadmin platform-only host contracts regress
  - after merge: revert packet and rerun focused security/auth tests plus enterprise policy gates
- Rollback trigger:
  - any non-allowlisted role can be assigned from the admin users API surface
  - tenant-scoped superadmin can access superadmin settings/roles/notify control-plane hosts
  - denied role-mutation audit extraction no longer fails closed when request body cache is unavailable
  - tenant-admin privileged role denial/audit behavior diverges from the contract
  - policy gate fails after integrating this packet

## Expiry
- Valid until: 2026-04-23
- Re-evaluate if: scope expands into broader auth, company-control-plane, or migration-path changes.

## Verification Evidence
- Scope-to-evidence mapping:
  - tenant-admin role allowlist + escalation denial contract: commit `5fe768a5d` and targeted tests `AdminUserServiceTest`, `AuthTenantAuthorityIT#admin_cannot_create_tenant_admin_user+tenant_admin_can_still_create_non_privileged_user`
  - platform-only superadmin host deny (`settings`, `roles`, `notify`): `docs/approvals/evidence/2026-04-16-r2-slice2/TEST-com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.xml` testcase `tenant_scoped_super_admin_cannot_access_platform_only_superadmin_hosts`
  - denied role-mutation body extraction fail-closed: `docs/approvals/evidence/2026-04-16-r2-slice2/com.bigbrightpaints.erp.core.security.RequestBodyCachingFilterTest.txt`
  - normalized pending-status predicate index hardening: migration `V183__credit_pending_status_norm_indexes.sql` + focused verification (`AdminApprovalServiceTest`, `AdminDashboardSecurityIT`)
- Commands run:
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=RequestBodyCachingFilterTest,CompanyContextFilterControlPlaneBindingTest,AuthTenantAuthorityIT#tenant_scoped_super_admin_cannot_access_platform_only_superadmin_hosts test`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -q -Dtest=AdminApprovalServiceTest,AdminDashboardSecurityIT test`
  - `bash ci/check-codex-review-guidelines.sh`
  - `bash ci/check-enterprise-policy.sh`
- Result summary:
  - focused security/auth tests passed for this slice (`RequestBodyCachingFilterTest`, `CompanyContextFilterControlPlaneBindingTest`, `AuthTenantAuthorityIT` targeted method)
  - tenant-scoped superadmin deny contract now explicitly covered on canonical notify POST call
  - normalized pending-status queries remain behavior-compatible and now have dedicated expression indexes for dashboard/inbox load paths
  - policy gates (`check-codex-review-guidelines`, `check-enterprise-policy`) passed
- Artifact note:
  - evidence bundle index: `docs/approvals/evidence/2026-04-16-r2-slice2/README.md`
  - test evidence: `docs/approvals/evidence/2026-04-16-r2-slice2/com.bigbrightpaints.erp.core.security.RequestBodyCachingFilterTest.txt`
  - test evidence: `docs/approvals/evidence/2026-04-16-r2-slice2/com.bigbrightpaints.erp.modules.auth.CompanyContextFilterControlPlaneBindingTest.txt`
  - test evidence: `docs/approvals/evidence/2026-04-16-r2-slice2/com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.txt`
  - testcase anchor: `docs/approvals/evidence/2026-04-16-r2-slice2/TEST-com.bigbrightpaints.erp.modules.auth.AuthTenantAuthorityIT.xml`
  - policy evidence: `docs/approvals/evidence/2026-04-16-r2-slice2/check-enterprise-policy.txt`
  - policy evidence: `docs/approvals/evidence/2026-04-16-r2-slice2/check-codex-review-guidelines.txt`
