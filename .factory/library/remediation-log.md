# Remediation Log

Track cleanup, duplicate-truth removals, dead-code retirement, and production-readiness fixes for this mission.

## Logging Rules

- Add entries in chronological order.
- Log packet-level cleanup only when backed by merged code or reviewable evidence.
- Describe what was cleaned, why it was risky, and what proof shows the cleanup is real.
- Do **not** describe planned cleanup as already completed.

## Entry Template

### YYYY-MM-DD — `<feature-id>`

- **Area:**
- **Risk addressed:**
- **Cleanup/remediation performed:**
- **Duplicate-truth or dead-code impact:**
- **Evidence:**
- **Follow-up:**

## 2026-03-08 — `truth-rails.docs-baseline`

- **Area:** mission documentation baseline
- **Risk addressed:** future packets needed a single approved reference for scope, truth boundaries, frontend notes, and cleanup logging; without that baseline, later workers could drift or overstate unimplemented behavior.
- **Cleanup/remediation performed:** initialized the mission remediation scaffold, definition-of-done reference, and frontend-v2 working notes.
- **Duplicate-truth or dead-code impact:** no application cleanup was performed in this docs-only packet; this entry creates the structure future packets must use when they remove duplicate-truth or dead code.
- **Evidence:** `.factory/library/erp-definition-of-done.md`, `.factory/library/frontend-v2.md`, and this file were aligned to the approved mission scope and Flyway v2-only rule.
- **Follow-up:** future truth-rails, O2C, P2P, control, and portal packets must append concrete remediation entries as code cleanup lands.

## 2026-03-08 — `truth-rails.freeze-characterization-contracts`

- **Area:** O2C/P2P/control truth-boundary characterization coverage
- **Risk addressed:** the current packet needed executable proof around replay and linkage seams before deeper refactors, especially where duplicate-truth could be introduced by dispatch re-entry, GRN/purchase invoice crossover, return replay, settlement reference reuse, or inventory side-listeners.
- **Cleanup/remediation performed:** no production code was changed; this packet added characterization tests that freeze current behavior for invoice replay without redispatch, stock-only GRN posting, purchase-invoice journal linkage to receipt movements, replay-safe sales returns, settlement reference reuse fail-closed behavior, and listener skip behavior when inventory events lack accounting accounts.
- **Duplicate-truth or dead-code impact:** no duplicate-truth path was removed yet, but the new tests document that `InventoryAccountingEventListener` remains a side-channel risk to contain later, `InvoiceService` still depends on dispatch reconciliation for issuance, and settlement reference reuse currently fails closed instead of silently creating duplicate allocations.
- **Evidence:** `InvoiceServiceTest`, `SalesReturnServiceTest`, `PurchasingServiceGoodsReceiptTest`, `PurchaseInvoiceEngineLifecycleTest`, `InventoryAccountingEventListenerIT`, and `CR_DealerReceiptSettlementAuditTrailTest` now pin these seams with targeted assertions.
- **Follow-up:** use these tests as guardrails while splitting canonical workflow-versus-accounting truth and while containing listener-driven posting in subsequent truth-rails packets.

## Known Cleanup Watchlist For Upcoming Packets

- `SalesCoreEngine` — mixed workflow/accounting decisions make O2C truth boundaries hard to reason about.
- `InvoiceService` — invoice issuance still has dispatch-coupled risk to untangle.
- `GoodsReceiptService` — must stay stock-truth only and replay-safe.
- `PurchaseInvoiceEngine` — needs clean GRN linkage without overlapping AP truth.
- `InventoryAccountingEventListener` — duplicate-posting and side-channel accounting risk must be contained early.

## 2026-03-08 — `o2c-truth.dealer-credit-and-proforma-boundary`

- **Area:** dealer onboarding plus O2C commercial boundary handling in `DealerService` and `SalesCoreEngine`
- **Risk addressed:** proforma creation was still reserving stock immediately, cash orders were treated like credit exposure, and shortage handling created one-off factory tasks without a clean refresh path.
- **Cleanup/remediation performed:** kept dealer onboarding on the tenant-safe `DealerService` provisioning flow, made credit posture use outstanding plus pending order exposure for credit-backed proformas, normalized payment modes to explicit `CREDIT`/`CASH`/`HYBRID`, removed in-create/update reservation side effects, and replaced duplicate shortage task creation with refreshable production-requirement syncing.
- **Duplicate-truth or dead-code impact:** retired the old create/update shortage branch that reserved inventory during commercial proforma edits and removed the separate pending-task cleanup path that no longer matched the new requirement-sync behavior.
- **Evidence:** `DealerServiceTest`, `SalesServiceTest`, `ErpInvariantsSuiteIT`, and the broader `mvn -T8 test -Pgate-fast -Djacoco.skip=true` gate all pass with the new commercial-only proforma flow.
- **Follow-up:** dispatch/final-invoicing packet should keep using dispatch-time reservation/slip creation as the fulfillment boundary while preserving the new payment-mode field and production-requirement semantics.

## 2026-03-08 — `o2c-truth.phase-one-cost-carry-forward`

- **Area:** finished-goods dispatch valuation, COGS/profitability carry-forward, and report parity around reserved finished goods.
- **Risk addressed:** phase-one manufacturing already carried production plus packaging cost into `FinishedGoodBatch.unitCost`, but dispatch still re-derived cost from the active costing method, allowing weighted-average blending to override the reserved batch's carried cost and misstate COGS/profitability.
- **Cleanup/remediation performed:** changed dispatch valuation to prefer the reserved/dispatched batch unit cost whenever a concrete batch is known, preserved weighted-average fallback only for non-batch paths, added end-to-end coverage proving packaging carry-forward reaches dispatch/P&L, and aligned report inventory low-stock parity so reserved-over-stock drift is no longer hidden by reserved-quantity clamping.
- **Duplicate-truth or dead-code impact:** removed the conflicting dispatch-side costing branch that recomputed COGS from period costing policy even after reservation had already chosen a concrete batch with an actual carried cost.
- **Evidence:** `FinishedGoodsServiceTest#confirmDispatchUsesReservedBatchActualCostWhenPeriodDefaultsToWeightedAverage`, `FinishedGoodsServiceTest#dispatchUsesReservedBatchActualCostUnderLegacyWeightedAverageAliasUnderTurkishLocale`, `FactoryPackagingCostingIT`, `ReportInventoryParityIT`, `InventorySmokeIT`, `ErpInvariantsSuiteIT`, and `mvn -T8 test -Pgate-fast -Djacoco.skip=true` all pass.
- **Follow-up:** future costing packets should keep period-close or revaluation adjustments updating batch truth upstream instead of introducing new dispatch-time costing branches.

## 2026-03-08 — `p2p-truth.supplier-lifecycle-and-payable-provisioning`

- **Area:** supplier onboarding, supplier lifecycle guardrails, and supplier-driven P2P/AP transaction entry points.
- **Risk addressed:** supplier creation previously saved the supplier twice during payable provisioning, and non-active suppliers could stay visible yet still slip through later GRN, purchase-invoice, return, payment, or settlement paths with inconsistent blocker behavior.
- **Cleanup/remediation performed:** collapsed supplier onboarding into one supplier save after payable-account provisioning, added shared lifecycle guardrails with explicit business-language blocker reasons, and enforced those guards across purchase-order creation, goods-receipt progression, purchase invoicing, purchase returns, AP journal posting, supplier payments, and supplier settlements.
- **Duplicate-truth or dead-code impact:** removed the redundant intermediate supplier save in onboarding and retired the touched fallback behavior where later P2P/AP flows each decided supplier usability differently instead of sharing one lifecycle truth.
- **Evidence:** `SupplierServiceTest`, `PurchaseOrderServiceTest`, `PurchasingServiceGoodsReceiptTest`, `PurchaseInvoiceEngineLifecycleTest`, `AccountingServiceTest`, and `ErpInvariantsSuiteIT` now cover payable provisioning plus visible-but-blocked reference-only supplier behavior.
- **Follow-up:** the next P2P packets should keep reusing the shared supplier lifecycle guard so GRN, purchase invoice, settlement, and correction work stays aligned to the same reference-only semantics.

## 2026-03-08 — `p2p-truth.grn-stock-truth-boundary`

- **Area:** GRN validation and the shared raw-material receipt seam used by goods receipts.
- **Risk addressed:** the GRN path was already stock-only by context, but the shared receipt helper still carried an obsolete AP-coupled validation branch, and direct service callers could reach blank/missing GRN identifiers without explicit business validation.
- **Cleanup/remediation performed:** added service-level guards for missing purchase-order IDs and blank receipt numbers, captured the stock-only GRN receipt context in tests, and split raw-material receipt account validation so GRN contexts only require inventory truth while AP-posting contexts still fail closed on missing payable setup.
- **Duplicate-truth or dead-code impact:** removed the dead assumption that every raw-material receipt must validate payable-account posting semantics, and retired the unused `resolveReferenceNumber` helper from the touched inventory receipt service.
- **Evidence:** `PurchasingServiceGoodsReceiptTest`, `RawMaterialServiceReceiptContextTest`, `TS_P2PGoodsReceiptIdempotencyTest`, `InventoryAccountingEventListenerIT`, and `ErpInvariantsSuiteIT` all pass with the refactored GRN stock-only boundary.
- **Follow-up:** the purchase-invoice AP-truth packet should continue reusing the explicit GRN receipt linkage and keep journal linking confined to invoice posting rather than reintroducing GRN-side posting paths.

## 2026-03-08 — `p2p-truth.purchase-invoice-ap-truth-and-linkage`

- **Area:** purchase invoice issuance, GRN linkage validation, and AP journal anchoring.
- **Risk addressed:** purchase invoices still carried a dead fallback path that could try to recreate stock-side receipt effects, and AP posting could proceed even if the linked GRN had lost its stock movement or batch linkage proof.
- **Cleanup/remediation performed:** made purchase invoicing fail closed unless the linked GRN still has matching stock movements and batch linkage for every received material, then linked the posted AP journal back onto those existing GRN movements instead of creating any new receipt artifacts.
- **Duplicate-truth or dead-code impact:** removed the duplicate stock-side fallback from `PurchaseInvoiceEngine`, preventing purchase invoice issuance from recreating receipt movements or tolerating linkage drift that would overlap AP truth.
- **Evidence:** `PurchaseInvoiceEngineLifecycleTest`, `InventoryAccountingEventListenerIT`, `ErpInvariantsSuiteIT`.
- **Follow-up:** keep the remaining P2P settlement and correction packets anchored to the same GRN-to-purchase journal provenance so close blockers can fail on linkage drift instead of repairing it silently.
