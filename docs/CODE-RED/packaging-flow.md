# CODE-RED Manufacturing & Packaging Flow (Bulk -> Size SKUs)

This document is the single source of truth for manufacturing/packaging behavior in V1.
All implementation, tests, and ops procedures must align with this flow.

## 1) Conceptual Model (Locked)
Product Hierarchy (Tree View)

Each manufactured product is represented as a single production product, with multiple physical forms:

- Production Product: Safari Emulsion White
- Bulk inventory (liters) -- semi-finished output of production
- Packed inventory (size SKUs) -- finished goods derived from bulk
  - Safari Emulsion White - 1L
  - Safari Emulsion White - 5L
  - Safari Emulsion White - 10L

Bulk and size SKUs are different forms of the same product, not different products.

## 2) Bulk Production (Factory)
Bulk Production Output

Factory produces Safari Emulsion White in bulk.
Production creates a bulk batch:

- Quantity: liters (e.g., 200 L)
- Unit cost per liter (from WIP / production costing)
- Batch identity preserved (QC, shade, traceability)

Resulting inventory state:

- Safari Emulsion White (Bulk): 200 L
- Safari Emulsion White (10L / 5L / 1L): 0 units

This bulk batch is the only source from which finished packs can be created.

## 3) Packaging Setup (Per-Product, Deterministic)
Packaging Variants (Per Product)

For each production product, the system defines packaging variants.
Example for Safari Emulsion White:

- 1L variant
- 5L variant
- 10L variant

Each variant defines:

- liters_per_unit
- child finished-good SKU
- packaging BOM (raw materials required)

Packaging BOM (Per Variant)

Each size variant has an explicit BOM:

- bucket
- lid
- label
- carton (optional)
- other packaging raw materials

This BOM is per product and per size, never global.

## 4) Packing Operation (Explicit Batch-Based)
Step 1: Select Bulk Batch

Operator selects Safari Emulsion White.
System lists available bulk batches:

- Batch ID
- Available liters
- Cost per liter

Operator explicitly selects one bulk batch.
This guarantees traceability: these finished goods came from this batch.

Step 2: Select Sizes & Quantities

System auto-loads:

- All active size variants for the product
- Packaging BOM for each size
- Current availability and cost of packaging RMs

Operator enters pack quantities:

- 10L -> 10 units
- 5L -> 8 units

Step 3: System Validations (Fail-Closed)

Before allowing pack confirmation, the system must validate:

Bulk availability
- Required liters = Sum(size_qty * liters_per_unit)
- Must be <= available liters in selected batch

Packaging RM availability
- Required RM = Sum(size_qty * units_per_pack) for each BOM component
- Must be available in inventory

Variant validity
- Each size SKU must have a valid packaging variant
- No implicit or size-only fallback allowed

If any validation fails -> packing fails closed.

## 5) Costing Model (Simple, Deterministic)
Bulk Cost Basis

Each bulk batch has:

- Cost per liter (from production/WIP)

Example:

- Bulk batch: 200 L @ 120/L

Per-Size Cost Calculation (At Pack Time)

For each size variant:

- Bulk cost per pack
  - bulk_cost = liters_per_unit * bulk_cost_per_liter

- Packaging cost per pack
  - packaging_cost = Sum(component_unit_cost * units_per_pack)

- Final cost per pack
  - final_unit_cost = bulk_cost + packaging_cost

This cost is:

- computed once at packing time
- stored on the child batch / inventory movement
- auditable and reproducible

No later mystery splits, no averaging across sizes.

## 6) Inventory Effects (Atomic)
On successful pack confirmation:

Bulk Inventory
- Bulk batch liters decrease exactly by required liters

Packaging RM Inventory
- Packaging raw materials decrease exactly per BOM

Finished Goods Inventory
- Size SKUs increase exactly by pack quantities
- Each size SKU gets its own batch linked to:
  - bulk batch
  - packing record

## 7) Accounting Effects (Canonical)
All accounting entries are posted via AccountingFacade:

- Bulk inventory credited (liters consumed)
- Packaging RM inventory credited
- Finished goods inventory debited (size SKUs)
- Cost flows are balanced and period-locked

Packing is a conversion, not revenue recognition.
No sales or COGS posting occurs here.

## 8) Sales & Dispatch (Downstream)
Packaging slips and sales orders operate only on size SKUs.

Bulk inventory is never sold or reserved.

Dispatch consumes size SKU batches and posts COGS based on their packed cost.

## 9) System Guarantees (CODE-RED Invariants)
- Bulk batch identity is never lost.
- Packing is deterministic and idempotent.
- No size SKU can exist without a packaging variant.
- Costs are explainable from:
  - bulk batch cost
  - packaging BOM costs
- Inventory always reconciles:
  - liters in -> liters out
  - units in -> units out

## 10) Why This Model Is Correct
- Matches real factory workflows.
- Matches accounting expectations.
- Avoids ambiguous bulk/size mappings.
- Supports multiple sizes from one batch.
- Scales to audits, disputes, and traceability.

## 11) Module Ownership & Cross-Module Touchpoints
Factory
- ProductionLogService creates bulk batches (SKU-BULK).
- BulkPackingService converts bulk batch -> size SKUs using variant + BOM.

Inventory
- FinishedGoodBatch records bulk and size batches.
- InventoryMovement records bulk issues and size receipts.

Accounting
- AccountingFacade posts conversion journals for packing.

Sales
- Packaging slips and dispatch operate only on size SKUs.

## 12) Required Data Model (Hard Cutover)
- product_packaging_variants:
  - production_product_id, size_label, liters_per_unit, child_finished_good_id, active
- product_packaging_components:
  - variant_id, raw_material_id, units_per_pack, carton_size (optional), active

Legacy size-only mappings are removed and must not be referenced.

## 13) Hard Cutover Rules
- No fallback to legacy mappings.
- If a variant or BOM is missing, packing fails closed.
- All packing requests require a pack idempotency key.

