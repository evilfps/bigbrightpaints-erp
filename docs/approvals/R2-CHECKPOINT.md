# R2 Checkpoint

## Scope
- Feature: `recovery-review.o2c-feedback-and-coverage`
- Branch: `recovery/03-o2c-truth`
- High-risk paths touched: O2C dispatch, challan, credit-posture, and fulfillment truth surfaces under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`, plus paired O2C tests and the `db/migration_v2` truth-rails migrations already carried on this branch.
- Why this is R2: the packet remediates PR #97 review findings on canonical dispatch, commercial-only proformas, challan eligibility, and finance/CODE-RED proof for a branch that touches high-risk sales, inventory, invoice, and migration-backed O2C flows.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/`, along with O2C E2E/CODE-RED/controller/service regressions and changed-coverage additions.
- Contract surfaces affected: dispatch confirmation logistics metadata, delivery challan generation, proforma credit posture, shortage-to-production synchronization, dispatch valuation call sites, and order confirmation behavior when production requirements remain open.
- Main risks being controlled: challans emitted before real dispatch, double-escaped dispatch documents, silent shortage skips for missing finished-good masters, stale packaging-slip assumptions in finance proof tests, enterprise-policy rejection for missing R2 evidence, and changed-files coverage misses on the O2C packet.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving remediation on the active recovery branch with no privilege widening, no tenant-boundary change, and no destructive migration behavior introduced by this packet.

## Escalation Decision
- Human escalation required: no
- Reason: the packet narrows existing O2C behavior to the intended truth model, updates stale tests to match current boundaries, and preserves the existing authorization and data-boundary model.

## Rollback Owner
- Owner: recovery-review O2C worker
- Rollback method: revert the recovery-review commit, then rerun `bash ci/check-enterprise-policy.sh`, `bash scripts/run_test_manifest.sh --profile codered --label codered-finance --manifest ci/pr_manifests/pr_codered_finance.txt`, and the targeted O2C Maven suite before re-opening PR #97.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: the packet grows beyond review-remediation into new sales/inventory workflows, widens approval authority, changes tenant/accounting boundaries, or adds new `db/migration_v2` behavior beyond the carried truth-rails migrations.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='DeliveryChallanPdfServiceTest,DispatchControllerTest,SalesServiceTest,SalesFulfillmentServiceTest,DispatchOperationalBoundaryIT,InvoiceServiceTest,FactoryPackagingCostingIT,OrderFulfillmentE2ETest,CR_SalesDispatchInvoiceAccounting' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Dtest='DeliveryChallanPdfServiceTest,DispatchArtifactPathsTest,DispatchDtoContractTest,DispatchControllerTest,InvoiceServiceTest,SalesControllerIT,SalesControllerIdempotencyHeaderTest,SalesFulfillmentServiceTest,SalesServiceTest,SalesProformaBoundaryServiceTest,DispatchConfirmRequestTest,SalesOrderDtoContractTest,SalesOrderRequestTest,SalesOrderTest,SalesTargetGovernanceServiceTest,TS_truthsuite_o2c_Approval_RuntimeTest,DispatchOperationalBoundaryIT,FactoryPackagingCostingIT,OrderFulfillmentE2ETest,CR_SalesDispatchInvoiceAccounting' test`; `python3 scripts/changed_files_coverage.py --jacoco erp-domain/target/site/jacoco/jacoco.xml --diff-base eedd5a5737450235882009645565dd22a0c89391 --src-root erp-domain/src/main/java --threshold-line 0.95 --threshold-branch 0.90 --fail-on-vacuous ...`
- Result summary: PR #97 review comments were remediated in code, stale O2C controller/E2E/CODE-RED expectations were realigned to the canonical dispatch truth, new focused unit tests raised stacked-base changed coverage materially (from ~0.815/0.521 to ~0.930/0.740 locally), and the branch now carries scope-specific R2 evidence for the high-risk O2C packet. Remaining local changed-files coverage debt is concentrated in stacked O2C diff lines still awaiting additional branch-coverage work.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/DeliveryChallanPdfService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/InventoryValuationService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsDispatchEngine.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesFulfillmentService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesProformaBoundaryService.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/service/DeliveryChallanPdfServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchControllerTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/SalesProformaBoundaryServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_SalesDispatchInvoiceAccounting.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/OrderFulfillmentE2ETest.java`

---

## Scope
- Feature: `recovery-followup.p2p-comment-recheck-and-closure`
- Branch: `recovery/04-p2p-truth`
- High-risk paths touched: P2P purchasing and accounting truth surfaces under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/` and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/`, plus focused purchasing/accounting regression tests and the packet-local frontend handoff nit.
- Why this is R2: the packet remediates PR #98 review findings on supplier lifecycle fail-closed behavior, replay ordering across high-risk accounting/P2P write paths, purchase-return replay stability, and changed-coverage proof for AP-truth and settlement code that touches enterprise-protected accounting paths.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingFacadeCore.java`, and P2P purchasing domain/service files that enforce supplier lifecycle and GRN-to-AP truth boundaries.
- Contract surfaces affected: supplier payment replay, supplier settlement replay, purchase-journal replay short-circuiting, purchase-return replay short-circuiting before mutable supplier lifecycle checks, and the packet-local review correction for supplier onboarding flow documentation.
- Main risks being controlled: idempotent supplier payment/settlement retries failing closed after legitimate prior success, purchase journal or purchase return replay being blocked by later supplier lifecycle changes instead of returning canonical truth, missing R2 audit evidence for enterprise-policy enforcement, and changed-coverage debt on the P2P packet.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving recovery remediation on the active stacked branch with no privilege widening, no tenant-boundary change, and no destructive migration behavior.

## Escalation Decision
- Human escalation required: no
- Reason: the packet restores the shipped idempotency contract and governance evidence without expanding permissions, tenant access, or migration risk.

## Rollback Owner
- Owner: recovery-review P2P worker
- Rollback method: revert the recovery-review commit, then rerun `bash ci/check-enterprise-policy.sh`, `bash ci/check-codex-review-guidelines.sh`, and the targeted P2P Maven suite before re-opening PR #98.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: the packet grows beyond review remediation into new accounting or purchasing workflows, widens approval authority, changes tenant/accounting boundaries, or introduces migration behavior.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-codex-review-guidelines.sh`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='PurchaseInvoiceEngineLifecycleTest,PurchasingServiceGoodsReceiptTest,AccountingServiceTest,CR_PurchasingToApAccountingTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='PurchaseReturnIdempotencyRegressionIT' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- Result summary: the packet now records scope-specific R2 evidence for the high-risk accounting/P2P paths, preserves replay-safe supplier payment and settlement behavior even if a supplier becomes non-transactional after the original success, keeps purchase-return replay resolution ahead of mutable supplier lifecycle rejection, keeps the packet-local supplier onboarding handoff polish corrected, and adds focused regression coverage so the touched P2P paths stay branch-locally reviewable.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `.factory/library/frontend-handoff.md`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingFacadeCore.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseReturnService.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/CR_PurchasingToApAccountingTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchaseInvoiceEngineLifecycleTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/purchasing/domain/SupplierLifecycleGuardTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/PurchaseReturnIdempotencyRegressionIT.java`

---

## Scope
- Feature: `recovery-review.portal-live-comment-closure`
- Branch: `recovery/06-portal-boundaries`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/service/RoleService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/config/RbacSynchronizationConfig.java`, plus the focused auth/RBAC regression suites under `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/rbac/config/`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/`.
- Why this is R2: the packet closes the remaining live PR #100 review scope on high-risk auth/company/RBAC paths by tightening super-admin isolation for tenant audit workflows and guaranteeing startup RBAC permission synchronization after role seeders complete.

## Risk Trigger
- Triggered by review-driven remediation under `core/security`, `modules/rbac`, and auth/runtime regression tests on a portal-boundary recovery branch.
- Contract surfaces affected: company-context enforcement for `/api/v1/audit/**`, startup synchronization of system-role default permissions, and the branch-local runtime truth coverage proving those boundaries.
- Main risks being controlled: tenant-attached super-admins reaching tenant audit workflows through role hierarchy, first-boot system roles being created without their default permissions when seeders run before RBAC synchronization, and enterprise-policy rejection if this auth/company/RBAC review packet ships without R2 evidence.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving review remediation on the active stacked branch with no privilege widening, no destructive migration behavior, and no tenant-boundary expansion beyond fail-closed hardening.

## Escalation Decision
- Human escalation required: no
- Reason: the packet narrows super-admin tenant reach and restores deterministic startup permission backfill without broadening APIs, tenant scope, or operational authority.

## Rollback Owner
- Owner: recovery-review portal worker
- Rollback method: revert the recovery-review commit, then rerun `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CompanyContextFilterControlPlaneBindingTest,SuperAdminTenantWorkflowIsolationIT,RbacSynchronizationConfigTest,TS_RuntimePortalBoundaryDelegatedCoverageTest' test`, `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`, and `cd /home/realnigga/Desktop/Mission-control && DIFF_BASE=$(git rev-parse origin/recovery/05-corrections-control) GATE_FAST_REQUIRE_DIFF_BASE=true bash scripts/gate_fast.sh` before re-opening PR #100.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: the packet grows beyond review-remediation into new auth/RBAC/company workflows, widens super-admin or tenant authority, or introduces schema/runtime changes outside the current comment-closure scope.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CompanyContextFilterControlPlaneBindingTest,SuperAdminTenantWorkflowIsolationIT,RbacSynchronizationConfigTest,TS_RuntimePortalBoundaryDelegatedCoverageTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control && bash ci/check-codex-review-guidelines.sh`; `cd /home/realnigga/Desktop/Mission-control && DIFF_BASE=$(git rev-parse origin/recovery/05-corrections-control) GATE_FAST_REQUIRE_DIFF_BASE=true bash scripts/gate_fast.sh`
- Result summary: the packet fail-closes `/api/v1/audit/**` for platform-only super-admin sessions while leaving root control-plane flows intact, backfills default permissions for seeded system roles after startup seeders finish, and adds focused unit/integration/truth-lane coverage so the narrowed PR #100 review scope stays branch-locally reproducible.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/service/RoleService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/config/RbacSynchronizationConfig.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/CompanyContextFilterControlPlaneBindingTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/SuperAdminTenantWorkflowIsolationIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/rbac/config/RbacSynchronizationConfigTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/TS_RuntimePortalBoundaryDelegatedCoverageTest.java`

---

## Scope
- Feature: `recovery-review.portal-envelope-and-audit-denial-followup`
- Branch: `recovery/06-portal-boundaries`
- High-risk paths touched: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/DealerService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, and the focused auth/portal regression suites under `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/`.
- Why this is R2: the follow-up closes the remaining PR #100 auth/company/RBAC review surface on a portal-boundary recovery branch by restoring the repository-standard dealer-read error envelope and preserving the audit-specific super-admin tenant denial contract without widening tenant workflow access.

## Risk Trigger
- Triggered by review-driven remediation on high-risk auth/company/RBAC paths plus a frontend-sensitive dealer API error contract on the active stacked branch.
- Contract surfaces affected: dealer read-miss error responses for credit utilization, aging, and ledger endpoints; super-admin denials on `/api/v1/audit/**`; and the focused regression coverage that locks both behaviors to the intended branch-local contract.
- Main risks being controlled: leaking Spring `ResponseStatusException` payloads instead of the standard `ApiResponse` envelope for dealer reads, accidentally collapsing audit denials into the earlier generic `SUPER_ADMIN_PLATFORM_ONLY` branch, and shipping the carried auth/company/RBAC follow-up without packet-local R2 evidence.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving review remediation on the active recovery branch with no privilege widening, no tenant-boundary expansion, and no destructive schema behavior.

## Escalation Decision
- Human escalation required: no
- Reason: the packet narrows error/denial behavior back to the intended fail-closed contracts, preserves current request and success-response shapes, and does not broaden any super-admin or tenant authority.

## Rollback Owner
- Owner: recovery-review portal worker
- Rollback method: revert the follow-up commit, then rerun `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`, `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CompanyContextFilterControlPlaneBindingTest,SuperAdminTenantWorkflowIsolationIT,DealerPortalControllerSecurityIT,DealerServiceTest' test`, and `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true` before re-opening PR #100.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: later packets widen the portal/auth scope beyond review remediation, change dealer read contracts again, or alter super-admin tenant isolation on additional control-plane or business surfaces.

## Verification Evidence
- Commands run: `cd /home/realnigga/Desktop/Mission-control && bash ci/check-enterprise-policy.sh`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -Djacoco.skip=true -Dtest='CompanyContextFilterControlPlaneBindingTest,SuperAdminTenantWorkflowIsolationIT,DealerPortalControllerSecurityIT,DealerServiceTest' test`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 compile -q`; `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`; `cd /home/realnigga/Desktop/Mission-control && gh pr checks 100 --repo anasibnanwar-XYE/bigbrightpaints-erp || true`.
- Result summary: dealer read misses now return through the standard `ApplicationException`/`ApiResponse` contract instead of leaking Spring error payloads, tenant-attached super-admin requests on `/api/v1/audit/**` remain fail-closed with the audit-specific `SUPER_ADMIN_TENANT_WORKFLOW_DENIED` reason instead of the generic platform-only branch, and the packet keeps the carried portal auth/company/RBAC hardening review-ready with branch-local evidence.
- Artifacts/links: `docs/approvals/R2-CHECKPOINT.md`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/DealerService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/service/DealerServiceTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/CompanyContextFilterControlPlaneBindingTest.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/SuperAdminTenantWorkflowIsolationIT.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/sales/DealerPortalControllerSecurityIT.java`

---

## Scope
- Feature: `recovery-followup.corrections-control-linkage-and-close-blockers`
- Branch: `recovery/05-corrections-control`
- High-risk paths touched: accounting correction linkage, purchase/sales return truth surfaces, and accounting period-close blocker enforcement under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/`.
- Why this is R2: the packet changes enterprise accounting correction controls and period-close fail-closed behavior on posted-document paths.

## Risk Trigger
- Triggered by edits under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/`, and `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/`.
- Contract surfaces affected: correction journal linkage, posted-document correction previews, purchase return replay truth, and accounting period-close blocker checks.
- Main risks being controlled: silent posted-document mutation, unlinked correction journals, and replay paths that lose linkage metadata or bypass close blockers.

## Approval Authority
- Mode: orchestrator
- Approver: ERP truth-stabilization recovery-review orchestration
- Basis: compatibility-preserving recovery remediation in enterprise accounting paths with no privilege widening, tenant-boundary expansion, or destructive migration behavior.

## Escalation Decision
- Human escalation required: no
- Reason: the packet tightens existing accounting controls and linkage invariants without widening access or mutating tenant boundaries.

## Rollback Owner
- Owner: recovery-review corrections-control worker
- Rollback method: revert the corrections-control recovery commit, then rerun the targeted correction suites and `MIGRATION_SET=v2 mvn -Pgate-fast -Djacoco.skip=true test` before merge.

## Expiry
- Valid until: 2026-03-16
- Re-evaluate if: additional correction flows, tenant-crossing journal behaviors, or new migration-backed linkage fields are added.

---

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
