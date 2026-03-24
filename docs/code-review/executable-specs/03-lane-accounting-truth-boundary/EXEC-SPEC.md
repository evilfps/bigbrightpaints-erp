# Lane 03 Exec Spec

## Covers
- Backlog row 3
- `O2C-01`, `P2P-01`, `MFG-01`, `FIN-01`

## Why This Lane Is The ERP Core
This lane decides what event is allowed to create AR, AP, inventory, revenue, COGS, and tax truth. If it is fuzzy, later workflow or automation fixes can double-post or orphan the books.

## Primary Review Sources
- `flows/order-to-cash.md`
- `flows/procure-to-pay.md`
- `flows/manufacturing-inventory.md`
- `flows/finance-reporting-audit.md`

## Primary Code Hotspots
- `SalesCoreEngine.confirmDispatch(...)`
- `PurchaseInvoiceEngine`
- `GoodsReceiptService`
- `InventoryAccountingEventListener`
- `AccountingCoreEngineCore`
- `ReconciliationServiceCore`

## Entry Criteria
- current production config and tests prove the inventory-accounting listener remains disabled in prod-like posture
- journal and reconciliation baselines are captured before any posting-boundary work begins
- no commercial, catalog, or auth slice is sharing the same PR
- finance owners can review a written boundary decision note, not just code diffs

## Produces For Other Lanes
- the canonical AR, AP, inventory, revenue, tax, and COGS boundary model for Lanes 04, 05, and 06
- explicit release language on what automation remains disabled
- reconciliation proof that downstream workflow hardening can trust

## Packet Sequence

### Packet 0 - publish the boundary decision note
- document dispatch, purchase invoice, production, manual adjustment, and listener responsibilities in one matrix
- define which events may create accounting truth and which are supporting or repair flows only
- output: [`00-lane03-boundary-decision-note.md`](./00-lane03-boundary-decision-note.md) approved before code changes widen, with exact code surfaces, targeted proof/tests, rollback owner/rule, stop rule, and downstream guarantees for Lanes 04-06

### Packet 1 - lock the sales boundary on dispatch truth
- keep order-level posting disabled
- prove dispatch confirmation remains the only AR, revenue, tax, and COGS boundary
- preserve explicit repair or reconciliation paths instead of silent duplicate healing
- output: sales truth-boundary proof pack

### Packet 2 - lock the purchasing and inventory boundary
- keep purchase invoice as the AP truth boundary
- prevent goods receipt, listener, or inventory-event automation from creating a second posting path
- output: purchasing boundary proof and prod-off listener evidence

### Packet 3 - define manufacturing and reconciliation posture
- pin WIP, semi-finished, valuation, and manual finance override behavior so manufacturing does not invent a competing boundary
- re-run reconciliation, journal-consistency, and close-control evidence against the chosen model
- output: reconciliation pack tied to the chosen boundary note

## Frontend And Operator Handoff
- release notes name the authoritative posting event for sales and purchasing explicitly
- finance and operator teams get one short matrix that says what remains disabled, what is canonical, and what is repair-only
- no downstream lane should describe posting semantics differently from the approved boundary note

## Stop-The-Line Triggers
- any slice enables inventory-accounting listener automation
- workflow changes continue before the boundary decision note exists
- reconciliation gaps are waived because of environment noise or staging instability
- dispatch or purchase flows are redesigned together with unrelated dashboard or UX work

## Must Not Mix With
- dealer-portal polish
- packaging workflow convergence
- auth or control-plane storage changes

## Must-Pass Evidence
- dispatch canonical posting tests
- inventory GL automation prod-off tests
- reconciliation controls and critical accounting axes
- explicit proof that listener posture is unchanged in prod config

## Rollback
- revert only the boundary-enforcement change and keep old safety kill switches intact; do not couple rollback to unrelated workflow cleanup

## Exit Gate
- one canonical event exists for AR or revenue truth
- one canonical event exists for AP or inventory truth
- listener and manual repair surfaces do not create duplicate posting paths
- reconciliation evidence matches the selected boundary model
