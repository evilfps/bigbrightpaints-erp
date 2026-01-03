# Production State Machines and Flow Map

This document captures the current production and packing lifecycle as
implemented in `factory`/`production` modules. It reflects existing behavior
only; no new flows are introduced.

## Production Plan
Source: `FactoryService`, `ProductionPlan`.

Lifecycle (string values stored on `ProductionPlan.status`):
- `PLANNED` (default)
- Additional statuses are accepted as freeform strings via `updatePlanStatus`.

Notes:
- Plans are scheduling artifacts only. They do not post inventory or journals.

## Production Batch (deprecated)
Source: `FactoryService.logBatch` (deprecated), `ProductionBatch`.

Notes:
- Batch logging is a lightweight path and should not be used alongside
  `ProductionLogService#createLog` for the same production run.
- When used, it can register finished good batches directly.

## Production Log
Source: `ProductionLogService`, `ProductionLogStatus`.

States (enum values):
- `MIXED` (legacy default on the entity)
- `READY_TO_PACK`
- `PARTIAL_PACKED`
- `FULLY_PACKED`

Transitions (current behavior):
- `createLog` -> `READY_TO_PACK`
- `recordPacking`:
  - `READY_TO_PACK` -> `PARTIAL_PACKED` when packed quantity is > 0 but < mixed quantity
  - `READY_TO_PACK`/`PARTIAL_PACKED` -> `FULLY_PACKED` when packed quantity >= mixed quantity
- `completePacking` -> `FULLY_PACKED` and computes wastage

## Packing Record
Source: `PackingService.recordPacking`, `PackingRecord`.

Behavior:
- Each packing session creates one or more `PackingRecord` rows.
- A packing record points to its `ProductionLog`, optional packaging material
  consumption, and the created finished good batch.

## Finished Good Batch
Source: `ProductionLogService.registerSemiFinishedBatch`,
`PackingService.registerFinishedGoodBatch`.

Behavior:
- Semi-finished (bulk) batch: created on production log receipt under
  SKU `<productSku>-BULK` and recorded as a `FinishedGoodBatch` with `bulk=true`.
- Finished good batches: created per packing session with FIFO costing and
  optional `parentBatch` pointing to the bulk batch.

## Inventory Linkage
- Raw material consumption creates `RawMaterialMovement` entries with
  `referenceType = PRODUCTION_LOG` and `referenceId = productionCode`.
- Packaging material consumption creates `RawMaterialMovement` entries with
  `referenceType = PACKING_RECORD` and reference IDs derived from the
  production code and packing line.
- Semi-finished receipts and packing sessions create `InventoryMovement`
  entries with `referenceType = PRODUCTION_LOG`, linked to journals when
  posting is enabled.
