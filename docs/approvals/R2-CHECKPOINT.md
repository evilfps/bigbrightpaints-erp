# R2 Checkpoint

## Scope
- Feature: `portal-boundaries.role-action-matrix-and-blocker-language`
- Branch: `mission/erp-truth-stabilization-20260308`
- High-risk paths touched: RBAC/security fallback handling, sales/admin/invoice/dealer portal controllers, dispatch controller, and portal-boundary integration/security tests.
- Why this is R2: the packet changes explicit role boundaries and blocker-language contracts on tenant-facing dispatch, approval, and dealer portal surfaces that affect authz and accounting-adjacent workflows.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/controller/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/domain/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/`.
- Contract surfaces affected: operational dispatch vs final dispatch posting, dealer and sales/admin role maps, credit override review, dealer-portal dealer-only enforcement, and the top-level access-denied message contract.
- Main risks being controlled: privilege drift between controllers, stale frontend/backend role documentation, and technical or generic blockers that obscure the correct business owner for dispatch/posting actions.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization mission orchestration
- Basis: compatibility-preserving RBAC hardening within active mission scope; no tenant-boundary widening, migration changes, or destructive persistence changes were introduced.

## Escalation Decision
- Human escalation required: no
- Reason: the packet narrows drift and clarifies existing ownership boundaries without granting new cross-tenant or destructive powers.

## Rollback Owner
- Owner: portal-boundaries feature worker
- Rollback method: revert the feature commit, then rerun the portal-boundary targeted suite plus `MIGRATION_SET=v2 mvn -Pgate-fast -Djacoco.skip=true test` before merge.

## Expiry
- Valid until: 2026-03-13
- Re-evaluate if: later packets widen dispatch posting privileges, add new portal-facing approval roles, or change tenant/auth boundary handling.

## Verification Evidence
- Verification summary bundle: targeted portal/auth contract tests, compile, targeted invariants/security tests, gate-fast, and codex-review-guidelines were rerun for this portal-boundaries packet.
- Result summary: the portal-boundary targeted suite passed with 29 tests and 0 failures, compile succeeded, the feature-required `InvoiceControllerSecurityContractTest` plus `ErpInvariantsSuiteIT` target set passed with 13 tests and 0 failures, the full fast gate finished green with 395 tests and 0 failures, and the codex review guideline check passed.
- Artifacts/links: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/PortalRoleActionMatrix.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/exception/CoreFallbackExceptionHandler.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/SalesController.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/controller/CreditLimitOverrideController.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/AdminApprovalRbacIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/SalesControllerIT.java`
