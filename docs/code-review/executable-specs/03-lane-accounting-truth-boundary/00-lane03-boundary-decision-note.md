# Lane 03 packet 0 boundary decision note

This is the prove-first packet that opens Lane 03 from the current sales / dispatch / orders pain without widening into runtime changes. It records the canonical accounting-truth boundaries, the exact code surfaces that own or threaten them, the proof pack that must stay green, and the rollback / stop conditions that later Lane 03 packets must inherit.

## 1. Header
- lane: `lane-03-accounting-truth-boundary`
- slice name: `lane03-prove-sales-dispatch-orders-accounting-truth`
- finding IDs: `O2C-01`, `P2P-01`, `MFG-01`, `FIN-01`
- implementer: `Factory-droid packet-governance worker`
- reviewer: `Factory-droid Lane 03 finance/commercial reviewer`
- QA owner: `Factory-droid accounting-truth regression pack owner`
- release approver: `Factory-droid Lane 03 lane owner`
- branch: `Factory-droid lineage prove-first packet (runtime implementation branch not opened yet)`
- target environment: repository source review plus `MIGRATION_SET=v2` targeted proof tests on the local branch

## 2. Lane Start Gate
- Lane 08 local-testing-confidence is complete enough to trust the layered local/PR confidence workflow before opening a new P0 lane.
- Backlog row 3 still maps exactly to [`EXEC-SPEC.md`](./EXEC-SPEC.md), the four upstream review docs, and the four stable finding IDs above.
- The prod-like guardrail is unchanged at lane open: `erp.inventory.accounting.events.enabled=false` remains the expected posture and no packet in this slice is allowed to enable it.
- This packet is proof-only. No sales workflow redesign, listener automation change, catalog/manufacturing authority change, governance-finance cleanup, or orchestrator work may share the same slice.

## 3. Why This Slice Exists
- Sales / dispatch / orders are the current shipment pain, but the observed problem is not only a sales bug: it is a truth-boundary question spanning sales, inventory, purchasing, accounting, reconciliation, and period close.
- The existing review set already shows a likely canonical model: dispatch confirmation owns AR/revenue/tax/COGS truth, purchase invoicing owns AP truth, the inventory-accounting listener must stay prod-off, and reconciliation / period close must consume that model rather than redefine it.
- Later Lane 03 packets need one written decision note they can inherit. Without it, later fixes can silently widen into duplicate-posting repairs, catalog/manufacturing redesign, or finance governance drift.

## 4. Scope
- make the canonical Lane 03 accounting-truth boundaries explicit before any runtime implementation packet opens
- identify the exact routes, controllers, services, listeners, config flags, and tests that define or challenge those boundaries
- record the currently suspected drift, the exact proof set that proves or disproves it, the rollback owner/rule, and the stop rule
- state the downstream consumers that must treat this packet as authoritative input instead of redefining truth locally
- do **not** change product code, `openapi.json`, migrations, feature flags, or runtime behavior in this slice
- do **not** mix Lane 04 commercial hardening, Lane 05 catalog/manufacturing authority work, Lane 06 finance-governance cleanup, or Lane 07 orchestrator/ops fixes into this proof packet

## 5. Caller Map

| Truth slice | Exact code surfaces and routes | Authoritative boundary decision | Suspected drift to prove/disprove | Downstream consumers that must not redefine truth |
| --- | --- | --- | --- | --- |
| Sales dispatch -> AR / revenue / tax / COGS | `POST /api/v1/sales/dispatch/confirm`, `POST /api/v1/dispatch/confirm`, `POST /api/v1/sales/dispatch/reconcile-order-markers`, `SalesController`, `DispatchController`, `SalesDispatchReconciliationService.confirmDispatch(...)`, `SalesCoreEngine.confirmDispatch(...)`, `FinishedGoodsService.confirmDispatch(...)`, `AccountingFacadeCore.postCogsJournal(...)`, `AccountingFacadeCore.postSalesJournal(...)`, `InvoiceService.issueInvoiceForOrder(...)` | Physical dispatch confirmation is the only canonical event allowed to create sales-side accounting truth. | Order-level markers, invoice issuance, or replay repair may be mistaken for the truth boundary when they are only downstream or recovery paths. | `InvoiceService`, `DealerLedgerService.syncInvoiceLedger(...)`, `StatementService`, `AgingReportService`, `DunningService`, `DealerPortalService`, `EnterpriseDashboardService`, Lane 04 commercial workflow packets |
| Order confirmation and marker repair | `POST /api/v1/sales/orders/{id}/confirm`, `SalesCoreEngine.canonicalOrderStatus(...)`, `SalesJournalService.postSalesJournal(...)`, `SalesFulfillmentService`, `SalesOrder` order/slip financial markers | Order confirmation is commercial state only; it must not become a second accounting boundary. Marker repair is recovery-only. | Status aliases, order-level journal markers, or fulfillment helper options could be treated as alternate posting truth. | search/history/reporting views, approval flows, and later Lane 04 order workflow hardening |
| Goods receipt -> AP / inventory linkage | `POST /api/v1/purchasing/goods-receipts`, `POST /api/v1/purchasing/raw-material-purchases`, `GoodsReceiptService`, `PurchaseInvoiceEngine`, `AccountingCoreEngineCore.enforceSupplierSettlementPostingParity(...)`, `SupplierLedgerService`, `StatementService` | Goods receipt is the physical stock boundary; purchase invoice posting is the only canonical AP truth boundary. | GRN movement publication plus payable/inventory account metadata can look like a second posting path, especially if listener automation is ever re-enabled. | `AccountingCoreEngineCore.{recordSupplierPayment,settleSupplierInvoices,autoSettleSupplier}(...)`, supplier statements/aging, month-end checklist, Lane 04 P2P settlement packets |
| Inventory automation and manual inventory journals | `InventoryAccountingEventListener`, `erp.inventory.accounting.events.enabled` in `application.yml` / `application-prod.yml`, `GoodsReceiptService` event publication, `FinishedGoodsDispatchEngine.confirmDispatch(...)`, `POST /api/v1/accounting/inventory/{landed-cost,revaluation,wip-adjustment}` | Inventory automation stays non-authoritative for purchase/AP truth in prod-like posture; manual inventory journals are finance repair/adjustment tools, not a second canonical operational boundary. | Listener activation or manual journal convenience can silently create duplicate posting authority outside the approved canonical events. | Lane 05 manufacturing/catalog packets, finance operators, valuation/reconciliation/reporting consumers |
| Period close, reconciliation, and reporting | `POST /api/v1/accounting/periods/{id}/{request-close,approve-close}`, `AccountingPeriodServiceCore`, `ReconciliationServiceCore`, `ReportService`, `TemporalBalanceService`, `InventoryValuationService`, `CriticalAccountingAxesIT`, `ReconciliationControlsIT` | Period close and reconciliation consume the chosen dispatch/purchase boundaries; they do not get to invent alternate truth because a downstream report or discrepancy exists. | Close-control or reconciliation code might appear to “heal” drift that actually comes from the wrong upstream posting boundary. | balance sheet / trial balance / reconciliation dashboard / statements / aged debtors / inventory valuation / audit exports, plus Lane 06 finance-governance packets |
| Manufacturing / packing / valuation consumers | `ProductionLogService`, `PackingService`, `PackingCompletionService`, `BulkPackingService`, `InventoryValuationService`, `FinishedGoodsDispatchEngine`, `FactoryPackagingCostingIT`, `CR_ManufacturingWipCostingTest` | Manufacturing, packing, and valuation remain downstream consumers of the chosen accounting boundary for this packet; they do not redefine AP or sales truth here. | WIP, packing, or valuation-side fixes can accidentally widen into boundary redesign if Packet 0 does not stop them first. | Lane 05 catalog/manufacturing authority packets, cost allocation, stock valuation, packaging/workbench follow-up |

## 6. Invariant Pack
- `SalesCoreEngine.confirmDispatch(...)` remains the only sales-side boundary that may create AR, revenue, tax, COGS, invoice linkage, and dealer-ledger truth.
- `SalesJournalService.postSalesJournal(...)` and other order-level helpers remain explicitly noncanonical for sales posting truth.
- `PurchaseInvoiceEngine` remains the only purchasing-side boundary that may finalize AP truth and journal-link goods receipt inventory movements.
- `InventoryAccountingEventListener` remains disabled in prod-like posture until a later Lane 03 packet explicitly proves a redesign that removes duplicate-posting risk.
- Manual inventory journal routes (`landed-cost`, `revaluation`, `wip-adjustment`) stay repair / finance-only tools and must not be described as operational truth boundaries.
- `AccountingPeriodServiceCore` and `ReconciliationServiceCore` must fail closed on unlinked / uninvoiced / mismatched state instead of waiving the upstream truth question.
- Lane 04, Lane 05, and Lane 06 may consume this packet's matrix, but they must not restate the canonical posting event differently.

## 7. Implementation Plan
1. Publish this boundary-decision packet before any Lane 03 runtime implementation packet opens.
2. Attach the exact proof command and named test inventory that every later Lane 03 packet must keep green or replace with a stronger proof set.
3. Update the authoritative flow, backlog, risk-register, and lane-spec surfaces so reviewers can trace the same boundary note from every planning document.
4. Stop here and split if anyone tries to turn this docs/proof packet into runtime remediation or cross-lane cleanup.

## 8. Proof Pack
- full baseline already expected for the branch lineage:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
- targeted Lane 03 proof pack:
  - `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=TS_O2CDispatchCanonicalPostingTest,TS_InventoryCogsLinkageScanContractTest,TS_P2PPurchaseJournalLinkageTest,TS_P2PPurchaseSettlementBoundaryTest,CR_InventoryGlAutomationProdOffIT,CriticalAccountingAxesIT,ReconciliationControlsIT,TS_PeriodCloseBoundaryCoverageTest,TS_PeriodCloseAtomicSnapshotTest'`
- exact proof responsibilities inside that pack:
  - `TS_O2CDispatchCanonicalPostingTest` proves dispatch confirmation still owns COGS + AR posting order and does not defer truth to order confirmation.
  - `TS_InventoryCogsLinkageScanContractTest` proves inventory/dispatch linkage still traces to the dispatch-side accounting boundary.
  - `TS_P2PPurchaseJournalLinkageTest` proves purchase journaling stays the journal-link boundary for receipt movements.
  - `TS_P2PPurchaseSettlementBoundaryTest` proves supplier settlement continues to consume purchase/AP truth instead of inventing a second journal source.
  - `CR_InventoryGlAutomationProdOffIT` proves prod-like safety still depends on the inventory-accounting listener staying disabled.
  - `CriticalAccountingAxesIT` and `ReconciliationControlsIT` prove reconciliation, AR/AP parity, and core accounting axes stay aligned with the chosen model.
  - `TS_PeriodCloseBoundaryCoverageTest` and `TS_PeriodCloseAtomicSnapshotTest` prove close-control/post-close snapshot behavior stays fail-closed around the chosen accounting truth.
- artifact path policy for this docs-only packet:
  - use the generated Surefire reports under `erp-domain/target/surefire-reports/**` for the named tests; this packet does not introduce extra runtime snapshot artifacts because it is deliberately proof-first and docs-only.

## 9. Validation-First Evidence

| Finding | Current prove-first verdict | Why the suspicion remains active | Evidence that must stay attached before runtime work widens |
| --- | --- | --- | --- |
| `O2C-01` | `dispatch confirmation is the canonical sales-side accounting truth boundary` | order confirmation, invoice issuance, and marker-reconciliation all touch downstream financial anchors, so reviewers must keep proving they are downstream/repair-only rather than alternate posting truth | `SalesCoreEngine.confirmDispatch(...)`, `SalesJournalService.postSalesJournal(...)`, `TS_O2CDispatchCanonicalPostingTest`, `TS_InventoryCogsLinkageScanContractTest`, `InvoiceService.issueInvoiceForOrder(...)` |
| `P2P-01` | `purchase invoice posting is the canonical AP truth boundary` | goods receipt already publishes payable/inventory movement context, so enabling listener automation or treating GRN as posted truth would duplicate AP/inventory journals | `GoodsReceiptService`, `PurchaseInvoiceEngine`, `InventoryAccountingEventListener`, `TS_P2PPurchaseJournalLinkageTest`, `TS_P2PPurchaseSettlementBoundaryTest`, `CR_InventoryGlAutomationProdOffIT` |
| `MFG-01` | `manufacturing and packing stay downstream consumers until Packet 3 explicitly defines their posture` | production, packing, and valuation flows already move stock/cost, but Packet 0 is not allowed to let them claim a competing AP or sales posting boundary | `ProductionLogService`, `PackingService`, `FinishedGoodsDispatchEngine`, `CR_ManufacturingWipCostingTest`, `FactoryPackagingCostingIT`, plus the stop rule below |
| `FIN-01` | `reconciliation and period-close enforce the chosen model; they do not redefine it` | bank/sub-ledger reconciliation, temporal reports, and period snapshots can expose drift, but they cannot be used to bless a duplicate posting path | `AccountingPeriodServiceCore`, `ReconciliationServiceCore`, `CriticalAccountingAxesIT`, `ReconciliationControlsIT`, `TS_PeriodCloseBoundaryCoverageTest`, `TS_PeriodCloseAtomicSnapshotTest` |

- reviewer sign-off: `Factory-droid Lane 03 finance/commercial reviewer` must treat the matrix above as the authoritative proof-first packet input for later Lane 03 slices.
- lane owner acknowledgement: later Lane 03 runtime packets may narrow one boundary at a time, but they must not reopen Packet 0's canonical model without replacing this proof set and re-running the stop/rollback decision.

## 10. Rollback Pack
- rollback owner: `Factory-droid Lane 03 lane owner` with finance-review sign-off
- what gets reverted first: this decision note and its cross-links in the flow/backlog/risk-register surfaces if a later proof packet disproves the canonical matrix
- what data is forward-only: none; this packet is docs-only and does not change schema, runtime state, or production data
- what wrapper, flag, or alias remains available during rollback: `erp.inventory.accounting.events.enabled=false`, order-truth sales-journal disablement, and the `/request-close` + `/approve-close` maker-checker period-close flow all remain intact while the packet is re-decided
- rollback trigger threshold: any later targeted proof that shows a different canonical event owns sales/AP truth, or any attempted packet that widens into runtime changes before this proof note is accepted
- rollback rehearsal evidence: rerun the targeted Lane 03 proof pack after reverting the note/reference updates to confirm the documentation rollback matches the still-green technical proof
- expected RTO: under 30 minutes for docs rollback plus targeted proof rerun
- expected RPO: none

## 11. Stop Rule
- stop immediately if a packet starts changing sales confirmation, dispatch reservation, goods-receipt journaling, listener enablement, manufacturing authority, report semantics, or period-close workflow in the same slice as this prove-first packet
- stop immediately if a packet tries to mix catalog/manufacturing repair, governance-finance cleanup, or orchestrator/ops remediation into the sales/dispatch/orders accounting-truth opening slice
- stop immediately if anyone attempts to use degraded runtime evidence, a missing listener proof, or an unreviewed report discrepancy as a waiver for the canonical-boundary proof set above

## 12. Exit Gate
- the Lane 03 proof-first packet names one canonical sales-side event, one canonical purchasing-side event, and the exact listener/manual surfaces that are explicitly noncanonical
- the targeted Lane 03 proof pack is green on the current branch lineage and still matches the code-surface matrix in this note
- rollback owner, rollback trigger, and stop rule are explicit before any runtime implementation packet opens
- downstream guarantees for Lane 04 / Lane 05 / Lane 06 are written and linked from the authoritative flow/backlog/risk-register surfaces
- no catalog/manufacturing, governance-finance, or orchestrator lane work has been mixed into this packet

## 13. Handoff
- next lane: Lane 03 Packet 1 (`lock the sales boundary on dispatch truth`), then Packet 2 (`lock the purchasing and inventory boundary`), then Packet 3 (`define manufacturing and reconciliation posture`)
- remaining transitional paths: order-marker reconciliation, manual inventory journals, and report/reconciliation discrepancy handling remain repair/consumer paths only until a later packet explicitly and narrowly changes them
- operator or frontend note: finance, sales, and operator consumers should read dispatch confirmation as the only sales posting truth and purchase invoicing as the only AP posting truth; downstream portals, statements, and dashboards are consumers of that model, not sources of authority
- compatibility window and wrapper duration: keep the current prod-off inventory-listener posture and existing order-truth posting disablement until a later Lane 03 packet explicitly proves a different safe posture
- consumer sign-off needed before cutover: Lane 03 finance reviewer plus the downstream Lane 04/Lane 05/Lane 06 packet owners that consume this boundary note
- deprecation or removal cutoff: none in Packet 0; this slice only defines the truth boundary that later packets must obey
