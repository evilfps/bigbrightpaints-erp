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
