# R2 Checkpoint

## Scope
- Feature: `ERP-19 Packet 2: admin and tenant control-plane cleanup`
- PR: `#123`
- Review candidate SHA: `f5e4df70b93a3f4cdd3beacc426484230e017ab5`
- PR branch: `feature/erp-stabilization-program--erp-19`
- Rebuild branch: `feature/erp-stabilization-program--erp-19-rebuild`
- Why this is R2: this packet hard-cuts approval and tenant-bootstrap control-plane contract cleanup on admin and super-admin surfaces, removes retired public aliases, restores machine-readable export scope in the single approval inbox, refreshes the frontend/backend handoff docs, and updates the canonical OpenAPI snapshot on a review candidate that required fresh proof after a real tenant-admin provisioning defect was found.

## Risk Trigger
- Triggered by control-plane contract cleanup on privileged admin and super-admin routes where stale aliases or missing typed fields would cause deterministic frontend/operator drift.
- Contract surfaces affected: `GET /api/v1/admin/approvals`, `AdminApprovalItemDto`, `POST /api/v1/superadmin/tenants/onboard`, `GET /api/v1/superadmin/dashboard`, and the retired aliases `GET /api/v1/admin/exports/pending` and `GET /api/v1/companies/superadmin/dashboard`.
- Failure mode if wrong: approval consumers read removed payload fields, export approvers lose machine-readable scope before approval, frontend/operator flows keep calling dead aliases, or tenant bootstrap returns a false-success contract while provisioning a tenant admin without `ROLE_ADMIN`.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Approval status: `pending PR review and merge approval`
- Basis: the bounded packet proof is green on review candidate `f5e4df70b93a3f4cdd3beacc426484230e017ab5`, but the PR branch still needs the rebuilt head pushed and reviewed on GitHub before merge.

## Escalation Decision
- Human escalation required: yes
- Reason: this packet changes privileged control-plane routes and approval/bootstrap contracts, and the default release gate for R2 remains explicit human approval on the GitHub PR after proof is replayable from committed sources.

## Rollback Owner
- Owner: `Anas ibn Anwar`
- Rollback method: revert the ERP-19 packet commit(s), refresh `openapi.json`, and rerun the focused control-plane proof plus OpenAPI snapshot verification before restoring the prior PR head.
- Rollback trigger:
  - approval inbox consumers fail on typed `originType` / `ownerType`
  - retired alias callers hit unexpected live traffic after merge
  - seeded tenant onboarding returns success while tenant-admin provisioning is not durable
  - OpenAPI snapshot or frontend handoff docs drift from the merged route surface

## Expiry
- Valid until: `2026-03-27`
- Re-evaluate if: the PR head moves beyond `f5e4df70b93a3f4cdd3beacc426484230e017ab5`, the focused control-plane/OpenAPI proof is re-run on a different candidate SHA, or the bounded packet grows beyond approval/bootstrap contract cleanup.

## Residual Follow-up
- Explicit follow-up ticket: `ERP-30`
- Follow-up scope: `CompanyContextFilter` target-tenant rebinding plus the hard-cut route-family migration onto `/api/v1/superadmin/tenants/**`
- Why excluded here: that lifecycle/create-update route convergence remains outside the bounded ERP-19 packet and was intentionally not merged into this PR.

## Verification Evidence
- Focused packet suite on review candidate `f5e4df70b93a3f4cdd3beacc426484230e017ab5`:
  - `cd erp-domain && mvn -B -ntp -Dtest=CompanyControllerIT,SuperAdminControllerIT,AdminSettingsControllerApprovalsContractTest,AdminApprovalRbacIT,TenantOnboardingControllerTest,ReportExportApprovalIT test`
  - result: `BUILD SUCCESS`
  - tests: `41 run, 0 failures, 0 errors, 0 skipped`
  - completed: `2026-03-20 02:04:09 +05:30`
- OpenAPI snapshot proof on review candidate `f5e4df70b93a3f4cdd3beacc426484230e017ab5`:
  - `cd erp-domain && mvn -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - result: `BUILD SUCCESS`
  - tests: `2 run, 0 failures, 0 errors, 0 skipped`
  - completed: `2026-03-20 02:05:00 +05:30`
- Hygiene proof:
  - `git diff --check`
  - `git diff --cached --check`
  - result: clean before packet commit
- Contract spot checks against refreshed `openapi.json`:
  - sha256: `602c45d8f90acd2b4a2f4faa208f8bae4ef36169bdf837fca7d6b80243556990`
  - total paths: `309`
  - total operations: `370`
  - admin path count: `17`
  - companies path count: `9`
  - `/api/v1/admin/exports/pending` absent
  - `/api/v1/admin/approvals` present as the single inbox
  - `AdminApprovalItemDto` exposes `originType` and `ownerType`, no longer exposes `type` or `sourcePortal`, and export approval rows now expose machine-readable `reportType`, `parameters`, `requesterUserId`, and `requesterEmail`
  - `/api/v1/companies/superadmin/dashboard` absent
  - `/api/v1/superadmin/dashboard` present
  - `TenantOnboardingResponse` exposes `bootstrapMode`, `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, and `tenantAdminProvisioned`

## Reviewer Notes
- The tenant-admin provisioning fix in this packet is behaviorally small but important: the packet now reattaches `ROLE_ADMIN` from `RoleRepository` before persisting bootstrap admins so the join row is written durably on both tenant bootstrap paths.
- The approval inbox now retains export scope as structured fields so approvers do not have to parse `summary` to inspect report type, raw parameters, or requester identity.
- Route-family hard-cut work was intentionally left out of this packet. Review should block any attempt to smuggle `/api/v1/superadmin/tenants/**` create/update migration or `CompanyContextFilter` rebinding into this PR.
- Review should use committed sources plus the rerunnable commands above; older dirty ERP-19 worktrees are source material only and are not branch truth.
