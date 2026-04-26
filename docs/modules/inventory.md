# Inventory Module

Last reviewed: 2026-03-30

This packet documents the inventory module, which owns **stock truth** for finished goods and raw materials. It covers stock summaries, batches, adjustments, opening stock import, valuation, traceability, dispatch execution, and the inventory-facing accounting event bridge.

The inventory module is the authoritative stock boundary. Catalog/setup truth (brands, items, SKU readiness) is documented separately in [catalog-setup.md](catalog-setup.md). Factory/manufacturing execution truth (production logs, packing, packaging mappings) is documented in the factory module packet (pending). Sales dispatch coordination is a shared seam documented here from the inventory side.

---

## 1. Module Ownership

| Aspect | Owner |
| --- | --- |
| Package | `com.bigbrightpaints.erp.modules.inventory` |
| Controllers | `FinishedGoodController`, `RawMaterialController`, `InventoryAdjustmentController`, `InventoryBatchController`, `OpeningStockImportController`, `DispatchController` |
| Primary services | `FinishedGoodsWorkflowEngineService` (facade), `FinishedGoodsReservationEngine`, `FinishedGoodsDispatchEngine`, `InventoryValuationService`, `InventoryAdjustmentService`, `OpeningStockImportService`, `PackagingSlipService`, `InventoryBatchTraceabilityService`, `InventoryBatchQueryService`, `RawMaterialService`, `BatchNumberService`, `InventoryMovementRecorder` |
| Domain entities | `FinishedGood`, `FinishedGoodBatch`, `RawMaterial`, `RawMaterialBatch`, `InventoryMovement`, `InventoryReservation`, `InventoryAdjustment`, `InventoryAdjustmentLine`, `RawMaterialAdjustment`, `RawMaterialAdjustmentLine`, `PackagingSlip`, `PackagingSlipLine`, `OpeningStockImport`, `RawMaterialMovement`, `RawMaterialIntakeRecord` |
| Domain enums | `InventoryType` (STANDARD / PRIVATE), `MaterialType` (PRODUCTION / PACKAGING), `InventoryBatchSource` (PRODUCTION / PURCHASE / ADJUSTMENT), `InventoryAdjustmentType` (DAMAGED / SHRINKAGE / OBSOLETE / RECOUNT_UP) |
| DTO families | Stock summary DTOs, batch DTOs, adjustment DTOs, packaging-slip DTOs, opening-stock-import DTOs, dispatch DTOs, traceability DTOs |
| Events | `InventoryMovementEvent`, `InventoryValuationChangedEvent` |
| Repositories | `FinishedGoodRepository`, `FinishedGoodBatchRepository`, `RawMaterialRepository`, `RawMaterialBatchRepository`, `InventoryMovementRepository`, `InventoryReservationRepository`, `PackagingSlipRepository`, `OpeningStockImportRepository`, `RawMaterialMovementRepository`, `RawMaterialIntakeRepository`, `InventoryAdjustmentRepository`, `RawMaterialAdjustmentRepository` |

---

## 2. Canonical Host and Route Map

### Finished Goods

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/finished-goods` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | List all finished goods |
| `/api/v1/finished-goods/{id}` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Get finished good detail |
| `/api/v1/finished-goods/{id}/batches` | GET | ADMIN, FACTORY, SALES | List batches for a finished good |
| `/api/v1/finished-goods/stock-summary` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Finished goods stock summary |
| `/api/v1/finished-goods/low-stock` | GET | ADMIN, FACTORY, SALES | Low stock items |
| `/api/v1/finished-goods/{id}/low-stock-threshold` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Get low-stock threshold |
| `/api/v1/finished-goods/{id}/low-stock-threshold` | PUT | ADMIN, FACTORY, ACCOUNTING | Update low-stock threshold |

### Raw Materials

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/raw-materials/stock` | GET | ADMIN, ACCOUNTING, FACTORY | Raw material stock summary |
| `/api/v1/raw-materials/stock/inventory` | GET | ADMIN, ACCOUNTING, FACTORY | Raw material inventory list |
| `/api/v1/raw-materials/stock/low-stock` | GET | ADMIN, ACCOUNTING, FACTORY | Low stock raw materials |
| `/api/v1/inventory/raw-materials/adjustments` | POST | ADMIN, ACCOUNTING | Raw material stock adjustment |

### Inventory Adjustments

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/inventory/adjustments` | GET | ADMIN, ACCOUNTING | List finished good adjustments |
| `/api/v1/inventory/adjustments` | POST | ADMIN, ACCOUNTING | Create finished good adjustment |

### Batch Traceability and Expiry

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/inventory/batches/{id}/movements` | GET | ADMIN, FACTORY, ACCOUNTING, SALES | Batch movement history (traceability) |
| `/api/v1/inventory/batches/expiring-soon` | GET | ADMIN, ACCOUNTING, FACTORY, SALES | Batches expiring within N days |

### Opening Stock Import

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/inventory/opening-stock` | POST | ADMIN, ACCOUNTING, FACTORY | Import opening stock (multipart CSV) |
| `/api/v1/inventory/opening-stock/preview` | POST | ADMIN, ACCOUNTING, FACTORY | Validate opening stock CSV without creating batches, movements, or journals |
| `/api/v1/inventory/opening-stock` | GET | ADMIN, ACCOUNTING, FACTORY | Import history |

### Dispatch

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/dispatch/pending` | GET | ADMIN, FACTORY, SALES | Pending packaging slips |
| `/api/v1/dispatch/preview/{slipId}` | GET | ADMIN, FACTORY | Dispatch preview |
| `/api/v1/dispatch/confirm` | POST | OPERATIONAL_DISPATCH role | Confirm dispatch |
| `/api/v1/dispatch/slip/{slipId}` | GET | ADMIN, FACTORY, SALES | Packaging slip detail |
| `/api/v1/dispatch/order/{orderId}` | GET | ADMIN, FACTORY, SALES | Slip by sales order |
| `/api/v1/dispatch/slip/{slipId}/status` | PATCH | ADMIN, FACTORY | Update slip status |
| `/api/v1/dispatch/backorder/{slipId}/cancel` | POST | ADMIN, FACTORY | Cancel backorder |
| `/api/v1/dispatch/slip/{slipId}/challan/pdf` | GET | ADMIN, FACTORY | Delivery challan PDF |

---

## 3. Stock Summaries

### Finished Goods Stock Summary

`GET /api/v1/finished-goods/stock-summary` returns a `StockSummaryDto` per finished good containing:

- `currentStock` — total on-hand quantity
- `reservedStock` — quantity reserved against active sales orders
- `availableStock` — `currentStock − reservedStock`
- `weightedAverageCost` — unit cost computed by the active costing method (see [Valuation](#6-valuation))

Stock levels are denormalized on the `FinishedGood` entity (`current_stock`, `reserved_stock` columns) and updated atomically during reservation, dispatch, adjustment, and opening-stock import.

### Raw Material Stock Summary

`GET /api/v1/raw-materials/stock` returns an aggregate `StockSummaryDto` with `totalMaterials`, `lowStockMaterials`, `criticalStockMaterials`, and `totalBatches`. `GET /api/v1/raw-materials/stock/inventory` returns per-material `InventoryStockSnapshot` rows.

### Low Stock Detection

Finished goods and raw materials both expose low-stock queries. For finished goods, an item is low-stock when `availableStock < lowStockThreshold`. The threshold defaults to 100 and can be overridden per item via the PUT endpoint.

---

## 4. Batches and Traceability

### Batch Entities

The inventory module has two parallel batch hierarchies:

- **`FinishedGoodBatch`** — belongs to a `FinishedGood`. Tracks `quantityTotal`, `quantityAvailable`, `unitCost`, `manufacturedAt`, `expiryDate`, `source` (PRODUCTION / PURCHASE / ADJUSTMENT), `sizeLabel`, and `inventoryType` (STANDARD / PRIVATE). Unique on `(finished_good_id, batch_code)`.
- **`RawMaterialBatch`** — belongs to a `RawMaterial`. Tracks `quantity`, `costPerUnit`, `supplier`, `receivedAt`, `manufacturedAt`, `expiryDate`, `source`, and `inventoryType`.

Batches are created by:
- **Factory packing** — finished good batches registered during the packing step (source = PRODUCTION)
- **Purchasing GRN** — raw material batches registered during goods receipt (source = PURCHASE)
- **Opening stock import** — both finished good and raw material batches created from CSV import (source = ADJUSTMENT)
- **Inventory adjustments** — adjustment batches for stock corrections (source = ADJUSTMENT)

### Batch Number Generation

`BatchNumberService` generates deterministic batch codes:
- **Raw material**: `RM-{SKU}-{YYYYMM}-{SEQ}` (per company, per SKU, per month)
- **Finished good**: `{COMPANY_CODE}-{SKU}-FG-{YYYYMM}-{SEQ}` (per company, per SKU, per month)
- **Packaging slips**: `{COMPANY_CODE}-PS-{SEQ}`

### Traceability

`GET /api/v1/inventory/batches/{id}/movements` returns full movement history for a batch:

- Batch metadata (type, code, product, dates, quantities, unit cost, source)
- Ordered list of `InventoryBatchMovementDto` entries, each containing: movement type, quantity, unit cost, total cost, timestamp, source classification, reference type/id, linked journal entry id, and linked packaging slip id

Movement sources are classified as `production`, `purchase`, or `adjustment` based on the reference type and movement type.

When a batch ID is ambiguous across raw material and finished good tables, the caller must specify `batchType=RAW_MATERIAL` or `batchType=FINISHED_GOOD`.

### Expiring Batches

`GET /api/v1/inventory/batches/expiring-soon?days=30` returns both raw material and finished good batches expiring within the specified window. Results are sorted by expiry date.

---

## 5. Adjustments

### Finished Good Adjustments

`POST /api/v1/inventory/adjustments` creates a finished good stock adjustment:

- Requires `Idempotency-Key` header (or `X-Idempotency-Key` legacy alias, or body-level `idempotencyKey` field)
- Supports adjustment types: DAMAGED, SHRINKAGE, OBSOLETE, RECOUNT_UP
- Each adjustment line specifies a finished good, quantity, and optional unit cost
- For positive adjustments (RECOUNT_UP), the service creates an adjustment batch and records movement type `ADJUSTMENT_IN`
- For negative adjustments (DAMAGED, SHRINKAGE, OBSOLETE), the service consumes batches using the costing method order and records movement type `ADJUSTMENT_OUT`
- **Accounting integration**: `InventoryAdjustmentService` posts to `AccountingFacade.postInventoryAdjustment()` synchronously, creating a journal entry and linking it back to both the adjustment and its movements
- **Idempotency**: Uses `IdempotencyReservationService` with normalized key and signature verification

### Raw Material Adjustments

`POST /api/v1/inventory/raw-materials/adjustments` creates a raw material stock adjustment:

- Also requires idempotency key
- Supports direction-based adjustments (increase/decrease) with adjustment account and reason
- Each line specifies a raw material batch, quantity, and direction
- RBAC restricted to ADMIN and ACCOUNTING roles

---

## 6. Valuation

### Costing Methods

The inventory module supports three costing methods for finished goods:

| Method | Behavior |
| --- | --- |
| **FIFO** | Batches consumed oldest-first by `manufacturedAt` |
| **LIFO** | Batches consumed newest-first |
| **WAC** (Weighted Average Cost) | Computed as aggregate `SUM(batch_quantity × batch_unit_cost) / SUM(batch_quantity)` across all non-empty batches |

The active costing method is resolved per-finished-good with a fallback chain:
1. Company-level active costing method (from `CostingMethodService`, period-aware)
2. Per-finished-good `costingMethod` field (defaults to FIFO)

`CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod()` normalizes the method string.

### Valuation Computation

`InventoryValuationService` provides:

- **`stockSummaryUnitCost(fg)`** — Computes unit cost for stock summary display. For WAC, returns cached weighted average. For FIFO/LIFO, iterates batches in method order and computes a blended unit cost from the on-hand quantity.
- **`resolveDispatchUnitCost(fg, batch)`** — Resolves the cost to use when dispatching. For WAC, uses weighted average regardless of which batch is dispatched. For FIFO/LIFO, uses the specific batch unit cost.
- **`requireNonZeroDispatchCost(fg, unitCost, shippedQty)`** — Fails if a dispatch would post zero cost against a product with on-hand stock, preventing silent zero-cost COGS entries.
- **WAC cache** — Weighted average cost is cached for 5 minutes per finished good ID. `invalidateWeightedAverageCost()` clears the cache after mutations.

### Inventory → Accounting Events

Two Spring events bridge inventory to accounting:

**`InventoryMovementEvent`** — Published by `InventoryMovementRecorder` for physical stock movements:
- Movement types: RECEIPT, ISSUE, TRANSFER, ADJUSTMENT_IN, ADJUSTMENT_OUT, SCRAP, RETURN_TO_VENDOR, RETURN_FROM_CUSTOMER
- Only RECEIPT and ISSUE trigger the event today (via `InventoryMovementRecorder.recordFinishedGoodMovement()`)
- Consumed by `InventoryAccountingEventListener.onInventoryMovement()` in the accounting module

**`InventoryValuationChangedEvent`** — Published for cost revaluations and method changes:
- Reasons: COST_METHOD_CHANGE, LANDED_COST_ADJUSTMENT, MARKET_REVALUATION, PHYSICAL_COUNT_ADJUSTMENT, STANDARD_COST_UPDATE, PURCHASE_PRICE_VARIANCE, SCRAP_WRITEOFF, INTERCOMPANY_TRANSFER
- Consumed by `InventoryAccountingEventListener.onInventoryValuationChanged()` in the accounting module

### Accounting Event Listener Configuration

`InventoryAccountingEventListener` is gated by:

```properties
erp.inventory.accounting.events.enabled=true  # default: true (matchIfMissing)
```

When enabled, the listener:
- Skips zero-cost movements
- Skips canonical workflow movements (GOODS_RECEIPT, SALES_ORDER, PACKAGING_SLIP reference types) — these are handled by their own posting paths
- Skips movements without source/destination accounts
- Uses `REQUIRES_NEW` transaction propagation (fires after the inventory transaction commits)
- Performs idempotency checks against `JournalEntryRepository` by reference number before creating any journal entry
- Sets and clears `CompanyContextHolder` per invocation to prevent thread pollution

**Caveat**: Because the listener runs in `AFTER_COMMIT` with `REQUIRES_NEW`, a failure in the accounting posting does **not** roll back the inventory mutation. The inventory stock change succeeds even if the GL posting fails. The listener logs errors but does not retry or dead-letter.

---

## 7. Opening Stock Import

### Overview

`POST /api/v1/inventory/opening-stock` accepts a multipart CSV upload to create initial batches for both finished goods and raw materials. `POST /api/v1/inventory/opening-stock/preview` accepts the same CSV and `openingStockBatchKey`, but only validates and calculates row results. Preview also runs the duplicate-content replay guard used by import and only peeks at generated batch-code candidates; it does not reserve number-sequence values.

### Endpoint Contract

| Parameter | Required | Purpose |
| --- | --- | --- |
| `Idempotency-Key` header | Yes | Prevents duplicate imports |
| `openingStockBatchKey` param | Yes | Logical batch identifier; must be unique per company |
| `file` part (CSV) | Yes | Opening stock rows |

Preview does not require `Idempotency-Key` because it never writes inventory, accounting, import history, or number-sequence state.

### CSV Quantity Rules

Opening stock is posted against the concrete prepared SKU, not the parent product family. A parent such as `Ultra Paint` may group `Ultra Paint / Black / 1L` and `Ultra Paint / Black / 20L`, but opening stock must be entered per variant SKU.

Rows can use direct unit entry:

```csv
type,sku,batch_code,quantity,unit_cost
FINISHED_GOOD,ULTRA-BLACK-1L,FG-OPEN-001,120,2.50
```

Rows can also use box entry. The backend calculates canonical SKU quantity as `boxes * pieces_per_box` and stores that final quantity on the SKU batch and movement:

```csv
type,sku,batch_code,boxes,pieces_per_box,unit_cost
FINISHED_GOOD,ULTRA-BLACK-1L,FG-OPEN-001,10,12,2.50
```

If both `quantity` and box fields are sent, `quantity` must already equal `boxes * pieces_per_box`; otherwise the row is rejected.

### Prerequisites and Readiness Gates

Opening stock import enforces SKU readiness checks before creating any batches:

1. **Catalog readiness** — the SKU must have a valid catalog entry (brand, item, product code)
2. **Inventory readiness** — the SKU must have the required inventory configuration (unit, accounts)
3. **Production readiness** — the SKU must have valid production configuration (for finished goods)
4. **Sales readiness** — the SKU must have valid sales configuration (for finished goods)

If any readiness check fails, the import returns per-row errors with the specific blockers (e.g., `"SKU PAINT-001 is not inventory-ready for opening stock: MISSING_VALUATION_ACCOUNT, MISSING_COGS_ACCOUNT"`).

### Accounting Configuration Gate

The import requires these accounts to be configured per finished good before creating batches:
- Valuation account
- COGS account
- Revenue account
- Tax account
- Discount account
- WIP account
- Labor applied account
- Overhead applied account

If accounting configuration is incomplete, the import fails with a clear error message.

### Feature Flag

Opening stock import is gated by:

```properties
erp.inventory.opening-stock.enabled=false  # default: disabled
```

In production profile (`isProdProfile()`), the import is rejected if this flag is `false`. Non-production profiles bypass this check.

### Idempotency and Batch Key

- The `Idempotency-Key` prevents re-processing the same upload
- The `openingStockBatchKey` prevents logical duplicates (one batch key per company)
- Both are enforced: reusing an idempotency key with a different batch key triggers an error
- Previous successful imports are returned as idempotent responses

### RBAC and Data Sanitization

- Write access: ADMIN, ACCOUNTING, FACTORY roles
- Read (history): ADMIN, ACCOUNTING, FACTORY roles
- Non-admin/non-accounting users receive sanitized responses where accounting-specific metadata (account references, financial details) is redacted via `SkuReadinessService.sanitizeForCatalogViewer()`
- Error messages containing account names are sanitized for non-accounting users

---

## 8. Reservations and Dispatch

### Reservation Engine

`FinishedGoodsReservationEngine` handles stock reservation when a sales order is created:

1. Resolves the company context and locks the finished good by product code (pessimistic lock)
2. Evaluates the active costing method to determine batch selection order (FIFO / LIFO / WAC)
3. Selects available batches in costing-method order and allocates quantity
4. Creates a `PackagingSlip` with `PackagingSlipLine` entries for each allocated batch
5. Records `InventoryMovement` entries and publishes `InventoryMovementEvent`
6. Updates `finishedGood.reservedStock` and `finishedGoodBatch.quantityAvailable`
7. Returns the packaging slip DTO plus any shortage information

If insufficient stock is available, the engine creates a partial slip and reports shortages.

### Dispatch Engine

`FinishedGoodsDispatchEngine` handles the actual stock dispatch:

1. Confirms dispatch against a packaging slip with actual shipped quantities
2. Decrements `finishedGood.currentStock` and `finishedGood.reservedStock`
3. Decrements `finishedGoodBatch.quantityAvailable`
4. Records dispatch movements with resolved unit costs
5. May create a backorder slip for unshipped quantities
6. Links movements to the journal entry for accounting traceability

### Dispatch Controller Ownership

`DispatchController` lives in the `inventory` module package at `/api/v1/dispatch/**`. However, the confirmation flow delegates to `SalesDispatchReconciliationService` (sales module) for the authoritative commercial and accounting side effects. This is a two-layer seam:

- **Transport/controller ownership**: `inventory.DispatchController`
- **Commercial/accounting ownership**: `sales.SalesDispatchReconciliationService`

For factory-role users, the dispatch controller enforces transport metadata validation (transporter name, vehicle number, challan reference) unless the slip is already dispatched (replay tolerance).

---

## 9. Inventory Movements

`InventoryMovement` records are the immutable audit trail for every finished good stock change:

| Field | Purpose |
| --- | --- |
| `finishedGood` | The product |
| `finishedGoodBatch` | The specific batch (nullable for aggregate movements) |
| `referenceType` | Source: PRODUCTION_LOG, OPENING_STOCK, SALES_ORDER, GOODS_RECEIPT, PACKING_RECORD, RAW_MATERIAL_PURCHASE, MANUFACTURING_ORDER, PURCHASE_RETURN, RAW_MATERIAL_ADJUSTMENT |
| `referenceId` | Domain identifier for the source document |
| `movementType` | RECEIPT, DISPATCH, ADJUSTMENT_IN, ADJUSTMENT_OUT |
| `quantity` | Quantity moved |
| `unitCost` | Cost per unit at movement time |
| `journalEntryId` | Linked GL journal entry (nullable until posted) |
| `packingSlipId` | Linked packaging slip (for dispatch movements) |

For raw materials, `RawMaterialMovement` serves the same purpose.

---

## 10. Key DTO Families

| DTO Family | Key DTOs | Purpose |
| --- | --- | --- |
| Stock summary | `StockSummaryDto`, `InventoryStockSnapshot` | Current stock levels and valuation |
| Batch | `FinishedGoodBatchDto`, `RawMaterialBatchDto` | Batch-level stock information |
| Adjustment | `InventoryAdjustmentDto`, `InventoryAdjustmentRequest`, `InventoryAdjustmentLineDto`, `RawMaterialAdjustmentDto`, `RawMaterialAdjustmentRequest` | Stock corrections |
| Traceability | `InventoryBatchTraceabilityDto`, `InventoryBatchMovementDto`, `InventoryExpiringBatchDto` | Movement history and expiry |
| Packaging slip | `PackagingSlipDto`, `PackagingSlipLineDto` | Dispatch container |
| Dispatch | `DispatchConfirmationRequest`, `DispatchConfirmationResponse`, `DispatchPreviewDto` | Dispatch workflow |
| Opening stock | `OpeningStockImportResponse`, `OpeningStockImportHistoryItem` | Initial stock import |
| Low stock | `FinishedGoodLowStockThresholdDto`, `FinishedGoodLowStockThresholdRequest` | Threshold management |

---

## 11. Cross-Module Boundaries

| Boundary | Direction | Nature |
| --- | --- | --- |
| **Sales → Inventory** | Inbound | Order creation triggers reservation (`FinishedGoodsReservationEngine.reserveForOrder()`); dispatch confirmation calls into sales reconciliation |
| **Purchasing → Inventory** | Inbound | GRN creates raw material batches and records movements; purchase returns deduct stock |
| **Factory → Inventory** | Inbound | Packing step creates finished good batches; production log records raw material consumption |
| **Inventory → Accounting** | Outbound (event) | `InventoryMovementEvent` and `InventoryValuationChangedEvent` bridge to `InventoryAccountingEventListener` |
| **Inventory → Accounting** | Outbound (facade) | `InventoryAdjustmentService` calls `AccountingFacade.postInventoryAdjustment()` synchronously |
| **Production → Inventory** | Read | `CatalogController` and `SkuReadinessService` read inventory entities for readiness evaluation |
| **Dispatch controller ↔ Sales service** | Shared seam | Controller is in inventory; reconciliation service is in sales |

---

## 12. Fail-Closed Blockers and Caveats

### Configuration-Gated Safety

| Configuration | Default | Impact |
| --- | --- | --- |
| `erp.inventory.accounting.events.enabled` | `true` | Controls automatic GL posting from inventory events. When disabled, inventory mutations succeed without journal entries, creating a **silent accounting gap**. |
| `erp.inventory.opening-stock.enabled` | `false` | Blocks opening stock import in production profile. Non-production profiles bypass this gate. |

### Accounting Event Reliability

The `InventoryAccountingEventListener` runs in `AFTER_COMMIT` + `REQUIRES_NEW`. This means:
- Inventory mutations **succeed even if the GL posting fails**
- Failed GL postings are **logged but not retried** and do **not** enter a dead-letter queue
- Operators must monitor logs for inventory accounting failures; there is no automated reconciliation for missed events

### Dispatch Cost Validation

`InventoryValuationService.requireNonZeroDispatchCost()` prevents zero-cost dispatches against products with on-hand stock. However, this guard only fires during dispatch — if batches are created without unit costs (e.g., via opening stock import with incomplete cost data), the error surfaces late in the pipeline.

### WAC Cache Staleness

The weighted average cost cache has a 5-minute TTL. During high-frequency batch creation (e.g., bulk packing), the cache may return stale costs for up to 5 minutes. `invalidateWeightedAverageCost()` is called after mutations, but concurrent reads may still see the previous value.

### Readiness Gates for Opening Stock

Opening stock import enforces SKU readiness checks (catalog, inventory, production, sales readiness). A SKU that is not fully configured will fail the import with per-row errors listing the specific blockers. This is a fail-closed gate — partial imports are not supported.

### Dispatch Two-Layer Seam

Dispatch confirmation is a shared boundary:
- The `DispatchController` (inventory module) receives the request
- The actual financial posting is owned by `SalesDispatchReconciliationService` (sales module)
- Factory-role users see redacted views (cost/accounting fields removed) unless they also hold ADMIN or ACCOUNTING roles
- Transport metadata validation is enforced only for factory-role users; admin/accounting users bypass it

### Negative Stock Prevention

Both `FinishedGood.setCurrentStock()` and `RawMaterial.setCurrentStock()` reject negative values at the entity level. Batch allocation (`FinishedGoodBatch.allocate()`) prevents over-allocation. These guards are in-memory and depend on pessimistic locking at the service level to prevent TOCTOU races.

---

## 13. Known Limitations

- **No location/warehouse tracking** — inventory is tracked per finished good / raw material only, without bin, warehouse, or location granularity.
- **No serial number tracking** — batches are the finest granularity for traceability; individual serial numbers are not supported.
- **No inventory count / physical count workflow** — adjustments exist, but there is no dedicated physical count or cycle-count flow.
- **Raw material batch allocation** is simpler than finished goods: raw material batches do not track `quantityAvailable` separately from `quantity`, so consumption directly reduces the batch quantity.
- **No stock transfer workflow** — `InventoryMovementEvent` includes a `TRANSFER` type, but no transfer endpoint or service is implemented.
- **Accounting event listener failures** are fire-and-forget: no retry, no dead-letter, no automated reconciliation.

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

---

## Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — module inventory
- [docs/modules/catalog-setup.md](catalog-setup.md) — catalog and setup readiness (production module)
- [docs/platform/config-feature-toggles.md](../platform/config-feature-toggles.md) — inventory-related configuration switches
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure used by adjustments and opening stock
- [docs/flows/inventory-management.md](../flows/inventory-management.md) — canonical inventory management flow (behavioral entrypoint)
- [docs/frontend-portals/factory/README.md](../frontend-portals/factory/README.md) — Factory frontend handoff (inventory payloads, RBAC, stock operations)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy dispatch paths, raw-material intake)
