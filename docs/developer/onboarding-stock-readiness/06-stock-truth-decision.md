# Wave 3 Stock-Truth Decision

This document is the accepted Wave 3 stock-truth contract for ERP-47.

It is intentionally decision-only. Wave 4 may implement against this decision, but Wave 3 does not rewrite stock truth paths.

## Decision

### 1. Authoritative stock source

- Raw materials and semi-finished bulk stock:
  `RawMaterialBatch.quantity` is the authoritative on-hand stock by batch.
- Finished goods:
  `FinishedGoodBatch.quantityTotal` is the authoritative on-hand stock by batch.
- Finished-good availability for new reservations:
  `FinishedGoodBatch.quantityAvailable` is the authoritative available stock by batch.
- Finished-good reservations:
  `InventoryReservation.reservedQuantity` is the authoritative reserved-state source.

### 2. Derived state

- `RawMaterial.currentStock` is derived aggregate state and must converge to the sum of open `RawMaterialBatch.quantity`.
- `FinishedGood.currentStock` is derived aggregate state and must converge to the sum of open `FinishedGoodBatch.quantityTotal`.
- `FinishedGood.reservedStock` is derived aggregate state and must converge to active `InventoryReservation.reservedQuantity`.
- `InventoryMovement` and `RawMaterialMovement` are immutable audit and replay lineage. They are not the operational stock source of truth.
- Report/accounting valuation snapshots are read models derived from authoritative batch state plus movement history for as-of reconstruction.

### 3. Explicit non-decisions for Wave 3

- No schema change is accepted in this wave.
- No worker may replace batch truth with movement truth in this wave.
- No worker may add a second "repair" writer for aggregate stock fields in this wave.
- No worker may use report/accounting valuation code as the write owner for operational stock.

## Writer Matrix

The table below enumerates every runtime write path that currently mutates stock-bearing fields or stock lineage.

| Item type | Code surface | Writes aggregate fields | Writes batch rows | Writes movement rows | Wave 4 constraint |
| --- | --- | --- | --- | --- | --- |
| Raw material | `OpeningStockImportService.handleRawMaterial(...)` | increments `RawMaterial.currentStock` | creates `RawMaterialBatch.quantity` | creates `RawMaterialMovement(RECEIPT)` | keep batch insert as authoritative; aggregate update must become synchronized derived state |
| Raw material | `RawMaterialService.recordReceipt(...)` | increments `RawMaterial.currentStock` | creates `RawMaterialBatch.quantity` | creates `RawMaterialMovement(RECEIPT)` | preserve GRN/intake semantics, but derive aggregate from batch truth |
| Raw material | `RawMaterialService.createRawMaterialAdjustmentInternal(...)` | mutates `RawMaterial.currentStock` | creates or updates `RawMaterialBatch.quantity` | creates `RawMaterialMovement(ADJUSTMENT_*)` | adjustment remains a canonical writer, but aggregate mutation must not drift from batch totals |
| Raw material | `ProductionLogService.consumeMaterial(...)` | decrements `RawMaterial.currentStock` | decrements `RawMaterialBatch.quantity` via FIFO issue | creates `RawMaterialMovement(ISSUE)` | keep batch issue as canonical consumption truth |
| Semi-finished bulk | `ProductionLogService.registerSemiFinishedBatch(...)` | increments `RawMaterial.currentStock` for synthetic semi-finished SKU | creates `RawMaterialBatch.quantity` | creates `RawMaterialMovement(RECEIPT)` | treat semi-finished bulk like raw-material batch truth, not a special second model |
| Semi-finished bulk | `PackingInventoryService.consumeSemiFinishedBatch(...)` and `recordWastage(...)` | decrements synthetic `RawMaterial.currentStock` | decrements `RawMaterialBatch.quantity` | creates `RawMaterialMovement(ISSUE)` | batch rows remain authoritative for bulk stock |
| Packaging material | `PackagingMaterialService.consumePackagingMaterials(...)` | decrements `RawMaterial.currentStock` | decrements `RawMaterialBatch.quantity` | creates `RawMaterialMovement(ISSUE)` | no separate packaging-only truth path |
| Raw material | `PurchaseReturnService.issueReturnFromBatches(...)` | upstream purchase-return flow consumes stock | decrements `RawMaterialBatch.quantity` | creates `RawMaterialMovement(RETURN)` | purchase returns stay batch-led |
| Finished good | `OpeningStockImportService.handleFinishedGood(...)` | increments `FinishedGood.currentStock` | creates `FinishedGoodBatch.quantityTotal/quantityAvailable` | creates `InventoryMovement(RECEIPT)` | keep batch insert as authoritative; aggregate must converge from batch totals |
| Finished good | `FinishedGoodBatchRegistrar.registerReceipt(...)` | increments `FinishedGood.currentStock` | creates `FinishedGoodBatch.quantityTotal/quantityAvailable` | creates `InventoryMovement(RECEIPT)` | this is the canonical finished-good receipt writer for packing/manufacturing output |
| Finished good reservation | `FinishedGoodsReservationEngine.allocateItem(...)` | increments `FinishedGood.reservedStock` | decrements `FinishedGoodBatch.quantityAvailable` | creates `InventoryMovement(RESERVE)` | reservation truth must come from reservation rows plus batch availability, not from aggregate field alone |
| Finished good reservation | `FinishedGoodsReservationEngine.releaseReservationsForOrder(...)`, `rebuildReservationsFromSlip(...)`, `recalculateReservationState(...)` | recalculates/decrements `FinishedGood.reservedStock` | restores/recalculates `FinishedGoodBatch.quantityAvailable` | creates `InventoryMovement(RELEASE)` on release | keep reservation rebuild/recalc as the reservation synchronizer; do not invent additional reserved-stock writers |
| Finished good dispatch | `FinishedGoodsDispatchEngine.markSlipDispatched(...)` and dispatch replay paths | decrements `FinishedGood.currentStock` and `FinishedGood.reservedStock` | decrements `FinishedGoodBatch.quantityTotal` | creates `InventoryMovement(DISPATCH)` | dispatch remains the canonical outbound finished-good writer |
| Finished good | `InventoryAdjustmentService.applyMovements(...)` | mutates `FinishedGood.currentStock` | creates or consumes `FinishedGoodBatch.quantityTotal/quantityAvailable` | creates `InventoryMovement(ADJUSTMENT_*)` | keep inventory adjustments explicit and batch-led |
| Finished good | `SalesReturnService.restockFinishedGood(...)` | increments `FinishedGood.currentStock` | creates `FinishedGoodBatch.quantityTotal/quantityAvailable` | creates `InventoryMovement(RETURN)` | sales returns remain explicit stock re-entry, not report-driven repair |

## Reader Matrix

The table below enumerates the important stock readers that Wave 4 must keep aligned.

| Reader class | Current read source | Role after this decision |
| --- | --- | --- |
| `CatalogService.toItemStock(...)` | `RawMaterial.currentStock`, `FinishedGood.currentStock`, `FinishedGood.reservedStock` | catalog keeps reading derived aggregates for operator speed; Wave 4 must ensure those fields are synchronized from authoritative batch/reservation state |
| `ProductionCatalogService.assertRawMaterialMirrorDeletionSafe(...)` | aggregate stock plus existence of batches/movements/reservations | deletion safety remains fail-closed, but aggregate fields are evidence only when consistent with batch truth |
| `ProductionCatalogService.assertFinishedGoodMirrorDeletionSafe(...)` | aggregate stock, reservations, batch/movement existence | same rule as above |
| `FinishedGoodsService.getStockSummary()` | `FinishedGood.currentStock`, `FinishedGood.reservedStock`, inventory costing helper | stock summary remains a derived read model |
| `FinishedGoodsService.getLowStockItems(...)` | derived available stock from aggregate fields | low-stock checks remain derived reads |
| `InventoryValuationQueryService.currentSnapshot(...)` | batch rows for valuation, aggregates for current quantity, movement rows for as-of adjustments | report valuation is a consumer of stock truth; it must not become a writer |
| `ReportService.inventoryValuation(...)` | `InventoryValuationQueryService` snapshots | report surface is derived only |
| `AccountingPeriodSnapshotService.createSnapshotForPeriod(...)` | `InventoryValuationQueryService.snapshotAsOf(...)` | accounting snapshots remain derived outputs, not operational truth |
| `SalesCoreEngine` dispatch readiness checks | `FinishedGood.currentStock` and slip allocation state | commercial validation remains a consumer of synchronized stock state |

## Accepted Wave 4 Implementation Constraints

- Introduce one canonical synchronization boundary for each derived aggregate:
  raw material on-hand, finished-good on-hand, and finished-good reserved stock.
- Wave 4 may centralize these synchronizations, but it may not create fallback writers outside the canonical stock write services listed above.
- Do not move operational truth into `ReportService`, `InventoryValuationQueryService`, `AccountingPeriodSnapshotService`, or accounting listener code.
- Do not reopen the FG canonical mutation flow:
  `POST /api/v1/factory/production/logs` -> `POST /api/v1/factory/packing-records` -> `POST /api/v1/dispatch/confirm`.
- Do not bypass DI or create new public wrapper/core layers while implementing stock synchronization.

## Migration Impact

- Wave 3 introduces no migration.
- Wave 4 must treat this decision as a behavioral migration plan:
  aggregate stock fields become synchronized derived state, not independently trusted truth.
- If a data backfill is required in Wave 4, the reconciliation target is:
  `batch sums == aggregate stock fields` and `reservation sums == reserved stock`.

## Test Impact

- Wave 3 adds architecture guards for hidden accounting cores, renamed valuation surfaces, and the DI-only workflow engine seam.
- Wave 4 must add executable invariants for:
  `sum(RawMaterialBatch.quantity) == RawMaterial.currentStock`,
  `sum(FinishedGoodBatch.quantityTotal) == FinishedGood.currentStock`,
  `sum(active InventoryReservation.reservedQuantity) == FinishedGood.reservedStock`,
  and report/accounting snapshots matching the same authoritative stock contract.

## Rollback Impact

- Wave 3 rollback is code/docs only: revert the seam cleanup, class rename, and this decision note if a dependent packet proves the decision wrong.
- No persisted data changes are introduced in Wave 3.
- If Wave 4 needs rollback after implementing this decision, revert synchronization logic first and leave batch/movement history intact for audit and recomputation.
