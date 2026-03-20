# R2 Checkpoint

## Scope
- Feature: `ERP-19 Packet 2: admin and tenant control-plane cleanup`
- PR: `#123`
- Review candidate SHA: `38f5c154d28568668fcd87cd752346ae41cf6468`
- PR branch: `feature/erp-stabilization-program--erp-19`
- Rebuild branch: `feature/erp-stabilization-program--erp-19-rebuild`
- Why this is R2: this packet hard-cuts approval and tenant-bootstrap control-plane contract cleanup on admin and super-admin surfaces, removes retired public aliases, keeps machine-readable export scope in the single approval inbox without exposing raw export parameters to accounting-only viewers, suppresses export decision action fields for accounting-only inbox rows while preserving stable null queue-field emission on the wire, refreshes the frontend/backend handoff docs, and updates the canonical proof on a review candidate that required fresh validation after real approval-visibility and public-contract defects were found and fixed.

## Risk Trigger
- Triggered by control-plane contract cleanup on privileged admin and super-admin routes where stale aliases or missing typed fields would cause deterministic frontend/operator drift.
- Contract surfaces affected: `GET /api/v1/admin/approvals`, `AdminApprovalItemDto`, `POST /api/v1/superadmin/tenants/onboard`, `GET /api/v1/superadmin/dashboard`, and the retired aliases `GET /api/v1/admin/exports/pending` and `GET /api/v1/companies/superadmin/dashboard`.
- Failure mode if wrong: approval consumers read removed payload fields, export approvers lose machine-readable scope before approval, frontend/operator flows keep calling dead aliases, or tenant bootstrap returns a false-success contract while provisioning a tenant admin without `ROLE_ADMIN`.

## Approval Authority
- Mode: human
- Approver: `Anas ibn Anwar`
- Approval status: `pending PR review and merge approval`
- Basis: the bounded packet proof is green on review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`, and the current PR head above that candidate is docs-only review maintenance pending explicit human merge approval.

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
- Re-evaluate if: any runtime-bearing change lands beyond review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`, the focused control-plane/OpenAPI proof is re-run on a different candidate SHA, or the bounded packet grows beyond approval/bootstrap contract cleanup.

## Residual Follow-up
- Explicit follow-up ticket: `ERP-31`
- Follow-up scope: `CompanyContextFilter` target-tenant rebinding plus the hard-cut route-family migration onto `/api/v1/superadmin/tenants/**`
- Why excluded here: that lifecycle/create-update route convergence remains outside the bounded ERP-19 packet and was intentionally not merged into this PR.

## Verification Evidence
- Focused packet suite on review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`:
  - `cd erp-domain && mvn -B -ntp -Dtest=CompanyControllerIT,SuperAdminControllerIT,AdminSettingsControllerApprovalsContractTest,AdminApprovalRbacIT,TenantOnboardingControllerTest,TenantOnboardingServiceTest,TenantAdminProvisioningServiceTest,ReportExportApprovalIT test`
  - result: `BUILD SUCCESS`
  - tests: `65 run, 0 failures, 0 errors, 0 skipped`
- Changed-files coverage proof on review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`:
  - `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base 49d97114c80a05189976d3d392c8749e2a05bc27 --src-root erp-domain/src/main/java --threshold-line 0.95 --threshold-branch 0.90 --fail-on-vacuous`
  - result: `PASS`
  - summary: `line_covered=48`, `line_total=48`, `line_ratio=1.0`, `branch_covered=26`, `branch_total=26`, `branch_ratio=1.0`, `files_with_unmapped_lines=[]`
- Snapshot refresh on review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`:
  - `cd erp-domain && mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Derp.openapi.snapshot.refresh=true -Dtest=OpenApiSnapshotIT test`
  - result: `BUILD SUCCESS`
  - tests: `2 run, 0 failures, 0 errors, 0 skipped`
- Non-mutating OpenAPI verification on review candidate `38f5c154d28568668fcd87cd752346ae41cf6468`:
  - `cd erp-domain && mvn -B -ntp -Djacoco.skip=true -Derp.openapi.snapshot.verify=true -Dtest=OpenApiSnapshotIT test`
  - result: `BUILD SUCCESS`
  - tests: `2 run, 0 failures, 0 errors, 0 skipped`
- Hygiene proof:
  - `git diff --check`
  - `git diff --cached --check`
  - result: clean
- Contract spot checks against refreshed `openapi.json`:
  - sha256: `6a5786c6a15585a3a1496bb3010b74526909f3952994d04a59101cc765c838d2`
  - total paths: `309`
  - total operations: `370`
  - admin path count: `17`
  - companies path count: `9`
  - `/api/v1/admin/exports/pending` absent
  - `/api/v1/admin/approvals` present as the single inbox
  - `AdminApprovalItemDto` exposes `originType` and `ownerType`, no longer exposes `type` or `sourcePortal`, keeps `actionType`, `actionLabel`, `approveEndpoint`, and `rejectEndpoint` as nullable strings, and export approval rows expose `reportType` for all inbox viewers while `parameters`, `requesterUserId`, and `requesterEmail` stay limited to tenant-admin responses
  - `/api/v1/companies/superadmin/dashboard` absent
  - `/api/v1/superadmin/dashboard` present
  - `TenantOnboardingResponse` exposes `bootstrapMode`, `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, and `tenantAdminProvisioned`

## Reviewer Notes
- The tenant-admin provisioning fix in this packet is behaviorally small but important: the packet now explicitly runs `RoleService.ensureRoleExists("ROLE_ADMIN")` to synchronize default permissions, then loads the persisted shared role from `RoleRepository` before persisting bootstrap admins on both tenant bootstrap paths.
- The approval inbox now retains export scope as structured fields for real approvers without leaking raw export parameters or requester identity to accounting-only viewers, and accounting-only export rows now emit explicit `null` action fields instead of dead approve/reject controls.
- Current head may include docs-only review maintenance above the review candidate; `38f5c154d28568668fcd87cd752346ae41cf6468` remains the last privileged runtime-bearing SHA covered by the proof listed above.
- Route-family hard-cut work was intentionally left out of this packet. Review should block any attempt to smuggle `/api/v1/superadmin/tenants/**` create/update migration or `CompanyContextFilter` rebinding into this PR.
- Review should use committed sources plus the rerunnable commands above; older dirty ERP-19 worktrees are source material only and are not branch truth.
