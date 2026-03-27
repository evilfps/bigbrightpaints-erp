# R2 Checkpoint

## Scope
- Feature: `ERP-37 post-merge admin email confirmation availability hardening`
- Branch: `mdanas7869292/erp-37-email-confirm-availability-hotfix`
- Review candidate: fail closed when `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` is asked to claim an email address that became occupied after request creation, and return a controlled validation error instead of surfacing a persistence-level unique-constraint failure.
- Related prior packet: merged ERP-37 control-plane packet via commit `ee8d28a66772fef115f9ec66eed487181e2f1ff2`.
- Why this is R2: the follow-up edits a superadmin recovery/control-plane path that mutates admin login identity, so a wrong result would turn a canonical confirmation flow into a user-visible `500` and leave the request in a retry-unsafe state.

## Risk Trigger
- Triggered by `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/service/SuperAdminTenantControlPlaneService.java` and `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/service/SuperAdminTenantControlPlaneServiceTest.java`.
- Contract surfaces affected: `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm` and the canonical admin email-change confirmation semantics introduced by ERP-37.
- Failure mode if wrong: a competing account can claim the requested email between request and confirm, causing the superadmin confirmation path to throw a persistence-level unique-constraint failure instead of a controlled validation error.

## Approval Authority
- Mode: human
- Approver: `human R2 reviewer`
- Canary owner: `ERP-37 follow-up packet owner`
- Approval status: `pending green validators and human review`
- Basis: this packet modifies a high-risk superadmin identity-mutation flow; green tests are necessary but not sufficient.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet changes the live superadmin admin-email confirmation path that governs tenant admin login identity.

## Rollback Owner
- Owner: `ERP-37 follow-up packet owner`
- Rollback method: revert the hotfix commit on top of `main` if the new availability guard proves incorrect; do not bypass the confirmation flow with persistence fallbacks or compatibility shims.
- Rollback trigger:
  - the confirmation endpoint rejects a free target email
  - the confirmation endpoint still leaks a persistence-level unique-constraint failure for a claimed target email
  - token revocation or request-consumption behavior changes outside the intended availability guard

## Expiry
- Valid until: `2026-04-03`
- Re-evaluate if: scope expands beyond the confirmation-time email-availability guard and its focused regression proof.

## Verification Evidence
- Commands run:
  - `git status --short --branch`
  - `git diff --check`
  - `cd erp-domain && MIGRATION_SET=v2 mvn -B -ntp -Dtest=com.bigbrightpaints.erp.modules.company.service.SuperAdminTenantControlPlaneServiceTest -DfailIfNoTests=false test`
- Result summary:
  - `confirmAdminEmailChange(...)` now re-checks `requestedEmail` availability immediately before mutating the admin record.
  - if another user has claimed the target email, the service now returns the same controlled validation error used during request creation instead of surfacing a persistence-layer unique-constraint failure.
  - focused regression coverage proves the save path is not reached when the target email becomes occupied after request creation.
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-37-hard-cut-superadmin-control-plane`
  - Hotfix PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/159`
