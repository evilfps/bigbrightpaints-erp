# Production Costing Rules and Sources

This document captures the current costing behavior for production and packing
flows. It reflects existing behavior only; no new flows are introduced.

## Inputs and Sources
- Raw material unit cost is sourced from `RawMaterialBatch.costPerUnit` at intake
  (or opening stock). Consumption is FIFO at the batch level.
- Packaging material cost is sourced via `PackagingSizeMapping` and issued from
  raw material batches (FIFO) using `PackagingMaterialService`.
- Labor and overhead costs are provided via `/api/v1/factory/cost-allocation`
  (`CostAllocationRequest`) and applied monthly.

## Production Log Costing
- On log creation, materials are issued and `ProductionLog.materialCostTotal`
  is set to the sum of raw material movement costs.
- `ProductionLog.unitCost` = (material + labor + overhead) / mixed quantity
  using `ProductionLogService` cost rounding.
- Labor and overhead defaults can be supplied on the log request and are later
  overwritten by monthly cost allocation.

## Semi-finished (Bulk) Batches
- `registerSemiFinishedBatch` creates a bulk `FinishedGoodBatch` with
  `unitCost = ProductionLog.unitCost` and records an inventory receipt movement.
- If total cost > 0, a WIP -> semi-finished journal is posted using the product
  `wipAccountId` and the semi-finished valuation account.

## Packing to Finished Goods
- Base unit cost is taken from the consumed semi-finished batch when available;
  otherwise `ProductionLog.unitCost` is used.
- Packaging cost per unit = total packaging cost / packed quantity (0 if
  no mapping or no consumption).
- `FinishedGoodBatch.unitCost = baseUnitCost + packagingCostPerUnit`.
- Packing journal values:
  - Production value = (materialCostTotal / mixed quantity) * packed quantity.
  - Packaging value = packagingCostPerUnit * packed quantity.
  - Debits finished goods inventory; credits semi-finished inventory for
    production value and WIP for packaging value.

## Cost Allocation (Labor/Overhead)
- Allocation runs per month across `FULLY_PACKED` logs (`CostAllocationService`).
- Labor and overhead are split by liters produced; `ProductionLog.unitCost` is
  recalculated as (material + labor + overhead) / packed quantity.
- Finished good batch costs are updated with packaging cost per unit.
- Journals move costs from labor/overhead expense accounts into finished goods
  inventory.

## Bulk Packing (Parent -> Child)
- Child unit cost = (bulk unit cost per liter * size liters) + packaging cost
  per unit (`BulkPackingService`).
- Journals credit bulk and packaging inventory and debit child FG inventory.

## Dispatch/COGS Cost Basis
- `FinishedGood.costingMethod` drives dispatch valuation (default FIFO; WAC
  available).
- Dispatch uses `FinishedGoodBatch.unitCost` for FIFO, or weighted-average cost
  across available batches for WAC.

## Taxes/GST
- Production/packing flows do not compute GST separately.
- Any GST impact is captured in `RawMaterialBatch.costPerUnit` at intake and
  flows into material/packaging costs.

## Rounding
- Production log unit cost uses 6-decimal `HALF_UP`.
- Packaging cost per unit uses 4-decimal `HALF_UP`.
- Journal amounts are rounded to 2 decimals with `HALF_UP`.
