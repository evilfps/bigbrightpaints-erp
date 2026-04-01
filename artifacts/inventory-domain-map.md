# Inventory Domain Map — BigBright Paints ERP

> Auto-generated domain analysis of `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/`

---

## 1. Domain Entities & Data Models

### 1.1 FinishedGood
**Table:** `finished_goods` | **Unique:** `(company_id, product_code)`

| Field | Type | Purpose |
|---|---|---|
| `id` / `publicId` (UUID) | Long / UUID | Internal + external identity |
| `company` | Company (FK) | Multi-tenant ownership |
| `productCode` | String | Unique product identifier (e.g. "EMUL-10L-WHITE") |
| `name` | String | Display name |
| `unit` | String | Unit of measure (default "UNIT") |
| `currentStock` | BigDecimal | On-hand quantity |
| `reservedStock` | BigDecimal | Quantity reserved for sales orders |
| `costingMethod` | String | "FIFO" (default), "LIFO", "WAC" |
| `inventoryType` | InventoryType | STANDARD (GST) or PRIVATE (non-GST) |
| `lowStockThreshold` | BigDecimal | Alert threshold (default 100) |
| `valuationAccountId` | Long (FK) | GL account for inventory asset |
| `cogsAccountId` | Long (FK) | GL account for cost of goods sold |
| `revenueAccountId` | Long (FK) | GL account for revenue |
| `discountAccountId` | Long (FK) | GL account for discounts |
| `taxAccountId` | Long (FK) | GL account for tax |

**Invariants:** `currentStock` and `reservedStock` cannot go negative.

### 1.2 FinishedGoodBatch
**Table:** `finished_good_batches` | **Unique:** `(finished_good_id, batch_code)`

| Field | Type | Purpose |
|---|---|---|
| `finishedGood` | FinishedGood (FK) | Parent product |
| `batchCode` | String | Unique batch code |
| `quantityTotal` | BigDecimal | Total quantity in batch |
| `quantityAvailable` | BigDecimal | Available (unreserved) quantity |
| `unitCost` | BigDecimal | Cost per unit for this batch |
| `manufacturedAt` | Instant | Production timestamp |
| `expiryDate` | LocalDate | Expiry date |
| `inventoryType` | InventoryType | STANDARD or PRIVATE |
| `source` | InventoryBatchSource | PRODUCTION, PURCHASE, or ADJUSTMENT |
| `sizeLabel` | String | e.g. "1L", "4L", "20L" |

**Key method:** `allocate(requested)` — atomically deducts from `quantityAvailable`, returns actual allocated amount (clamped to available).

### 1.3 RawMaterial
**Table:** `raw_materials` | **Unique:** `(company_id, sku)`

| Field | Type | Purpose |
|---|---|---|
| `company` | Company (FK) | Multi-tenant ownership |
| `name` / `sku` | String | Display name / unique stock code |
| `unitType` | String | Unit of measure |
| `currentStock` | BigDecimal | On-hand quantity |
| `privateStock` | BigDecimal | Private (non-GST) stock |
| `reorderLevel` / `minStock` / `maxStock` | BigDecimal | Stock planning thresholds |
| `inventoryAccountId` | Long (FK) | GL account for inventory asset |
| `inventoryType` | InventoryType | STANDARD (GST) or PRIVATE |
| `materialType` | MaterialType | PRODUCTION or PACKAGING |
| `costingMethod` | String | "FIFO" (default) |
| `gstRate` | BigDecimal | GST rate percentage |

### 1.4 RawMaterialBatch
**Table:** `raw_material_batches`

| Field | Type | Purpose |
|---|---|---|
| `rawMaterial` | RawMaterial (FK) | Parent material |
| `batchCode` | String | Unique batch code |
| `quantity` | BigDecimal | Current quantity in batch |
| `unit` / `costPerUnit` | String / BigDecimal | Unit info |
| `supplier` / `supplierName` | Supplier (FK) / String | Supplier reference |
| `receivedAt` / `manufacturedAt` | Instant | Timestamps |
| `expiryDate` | LocalDate | Expiry |
| `source` | InventoryBatchSource | PURCHASE, PRODUCTION, or ADJUSTMENT |

### 1.5 PackagingSlip
**Table:** `packaging_slips` | **Unique:** `(company_id, slip_number)`

| Field | Type | Purpose |
|---|---|---|
| `company` | Company (FK) | Multi-tenant |
| `salesOrder` | SalesOrder (FK) | Linked sales order |
| `slipNumber` | String | Unique slip identifier |
| `status` | String | Status lifecycle (see §4) |
| `isBackorder` | boolean | True if this is a backorder slip |
| `lines` | List\<PackagingSlipLine\> | Child line items |
| `dispatchedAt` / `confirmedAt` / `confirmedBy` | Instant / String | Dispatch audit |
| `transporterName` / `driverName` / `vehicleNumber` / `challanReference` | String | Logistics metadata |
| `journalEntryId` / `cogsJournalEntryId` | Long (FK) | Accounting journal references |
| `invoiceId` | Long (FK) | Invoice reference |

### 1.6 PackagingSlipLine
**Table:** `packaging_slip_lines`

| Field | Type | Purpose |
|---|---|---|
| `packagingSlip` | PackagingSlip (FK) | Parent slip |
| `finishedGoodBatch` | FinishedGoodBatch (FK) | The batch being shipped |
| `orderedQuantity` | BigDecimal | Quantity ordered |
| `shippedQuantity` | BigDecimal | Quantity actually shipped |
| `quantity` | BigDecimal | Allocated/reserved quantity |
| `unitCost` | BigDecimal | Cost per unit at dispatch |
| `backorderQuantity` | BigDecimal | Unshipped quantity going to backorder |
| `notes` | String | Notes |

### 1.7 InventoryReservation
**Table:** `inventory_reservations`

| Field | Type | Purpose |
|---|---|---|
| `finishedGood` | FinishedGood (FK) | Reserved FG |
| `finishedGoodBatch` | FinishedGoodBatch (FK) | Specific batch |
| `rawMaterial` | RawMaterial (FK) | (Optional) Reserved RM |
| `referenceType` | String | Reference type (e.g. "SALES_ORDER") |
| `referenceId` | String | Reference ID |
| `quantity` | BigDecimal | Original reserved quantity |
| `reservedQuantity` | BigDecimal | Currently remaining reserved |
| `fulfilledQuantity` | BigDecimal | Quantity dispatched/fulfilled |
| `status` | String | RESERVED, PARTIAL, FULFILLED, CANCELLED, BACKORDER |

### 1.8 InventoryMovement (Finished Goods)
**Table:** `inventory_movements`

| Field | Type | Purpose |
|---|---|---|
| `finishedGood` | FinishedGood (FK) | Product moved |
| `finishedGoodBatch` | FinishedGoodBatch (FK) | Batch moved |
| `referenceType` / `referenceId` | String | Source reference |
| `movementType` | String | RESERVE, RELEASE, DISPATCH, RECEIPT, ADJUSTMENT_IN, ADJUSTMENT_OUT |
| `quantity` / `unitCost` | BigDecimal | Movement details |
| `packingSlipId` | Long | Linked packaging slip |
| `journalEntryId` | Long (FK) | Accounting journal |

### 1.9 RawMaterialMovement
**Table:** `raw_material_movements`

| Field | Type | Purpose |
|---|---|---|
| `rawMaterial` | RawMaterial (FK) | Material moved |
| `rawMaterialBatch` | RawMaterialBatch (FK) | Batch moved |
| `referenceType` / `referenceId` | String | Source reference |
| `movementType` | String | RECEIPT, ADJUSTMENT_IN, ADJUSTMENT_OUT |
| `quantity` / `unitCost` | BigDecimal | Movement details |
| `journalEntryId` | Long (FK) | Accounting journal |
| `packingRecord` | PackingRecord (FK) | Link to factory packing step |

### 1.10 InventoryAdjustment (Finished Goods)
**Table:** `inventory_adjustments`

| Field | Type | Purpose |
|---|---|---|
| `company` | Company (FK) | Multi-tenant |
| `referenceNumber` | String | Unique reference |
| `adjustmentDate` | LocalDate | Date of adjustment |
| `type` | InventoryAdjustmentType | DAMAGED, SHRINKAGE, OBSOLETE, RECOUNT_UP |
| `reason` | String | Explanation |
| `status` | String | DRAFT → POSTED |
| `totalAmount` | BigDecimal | Total financial impact |
| `journalEntryId` | Long (FK) | Accounting journal |
| `lines` | List\<InventoryAdjustmentLine\> | Per-FG line items |
| `idempotencyKey` / `idempotencyHash` | String | Idempotency control |

### 1.11 InventoryAdjustmentLine
**Table:** `inventory_adjustment_lines`

| Field | Type | Purpose |
|---|---|---|
| `adjustment` | InventoryAdjustment (FK) | Parent |
| `finishedGood` | FinishedGood (FK) | Product adjusted |
| `quantity` | BigDecimal | Amount adjusted |
| `unitCost` / `amount` | BigDecimal | Cost details |
| `note` | String | Note |

### 1.12 RawMaterialAdjustment
**Table:** `raw_material_adjustments` | **Unique:** `(company_id, reference_number)`

Same structure as InventoryAdjustment but for raw materials. Has `direction` (INCREASE/DECREASE) in the request, `lines` of type RawMaterialAdjustmentLine.

### 1.13 RawMaterialIntakeRecord
**Table:** `raw_material_intake_requests` | **Unique:** `(company_id, idempotency_key)`

Idempotency record for raw material batch intake. Tracks `rawMaterialId`, `rawMaterialBatchId`, `rawMaterialMovementId`, `journalEntryId`.

### 1.14 OpeningStockImport
**Table:** `opening_stock_imports` | **Unique:** `(company_id, idempotency_key)`, `(company_id, opening_stock_batch_key)`

Tracks opening stock CSV imports. Records counts of raw materials, finished goods, batches created. Stores errors/results as JSON.

---

## 2. Enums & Constants

### 2.1 InventoryType
```java
STANDARD  // GST applicable (default)
PRIVATE   // Non-GST / Private stock (samples, internal use)
```

### 2.2 MaterialType
```java
PRODUCTION  // Used in manufacturing (pigments, resins, solvents)
PACKAGING   // Used in packing step (buckets, cans, cartons, lids)
```

### 2.3 InventoryBatchSource
```java
PRODUCTION  // Batch from manufacturing
PURCHASE    // Batch from supplier purchase
ADJUSTMENT  // Batch from inventory adjustment or opening stock
```

### 2.4 InventoryAdjustmentType
```java
DAMAGED     // Damaged goods write-off
SHRINKAGE   // Unexplained loss
OBSOLETE    // Obsolete stock write-off
RECOUNT_UP  // Positive recount adjustment (increases stock)
```
*All types except RECOUNT_UP decrease stock.*

### 2.5 InventoryReference (Constants)
```java
PRODUCTION_LOG         // referenceId = production log code
RAW_MATERIAL_PURCHASE  // referenceId = purchase receipt reference
OPENING_STOCK          // referenceId = opening stock batch code
SALES_ORDER            // referenceId = sales order database ID
MANUFACTURING_ORDER    // referenceId = FG batch public ID (manual manufacturing receipts)
PURCHASE_RETURN        // Purchase returns
PACKING_RECORD         // Factory packing step
GOODS_RECEIPT          // Goods receipt number
RAW_MATERIAL_ADJUSTMENT // Raw material adjustments
```

---

## 3. API Endpoints

### 3.1 Finished Goods (`/api/v1/finished-goods`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/` | ADMIN, FACTORY, SALES, ACCOUNTING | List all finished goods |
| GET | `/{id}` | ADMIN, FACTORY, SALES, ACCOUNTING | Get finished good detail |
| GET | `/{id}/batches` | ADMIN, FACTORY, SALES | List batches for a FG |
| GET | `/stock-summary` | ADMIN, FACTORY, SALES, ACCOUNTING | Stock summary with unit costs |
| GET | `/low-stock` | ADMIN, FACTORY, SALES | Low stock items |
| GET | `/{id}/low-stock-threshold` | ADMIN, FACTORY, SALES, ACCOUNTING | Get threshold |
| PUT | `/{id}/low-stock-threshold` | ADMIN, FACTORY, ACCOUNTING | Update threshold |

### 3.2 Dispatch (`/api/v1/dispatch`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/pending` | ADMIN, FACTORY, SALES | List pending (non-dispatched) packaging slips |
| GET | `/preview/{slipId}` | ADMIN, FACTORY | Dispatch preview with GST calculations |
| GET | `/slip/{slipId}` | ADMIN, FACTORY, SALES | Get packaging slip details |
| GET | `/order/{orderId}` | ADMIN, FACTORY, SALES | Get slip by sales order |
| POST | `/confirm` | OPERATIONAL_DISPATCH | Confirm dispatch with actual shipped quantities |
| POST | `/backorder/{slipId}/cancel` | ADMIN, FACTORY | Cancel a backorder slip |
| PATCH | `/slip/{slipId}/status` | ADMIN, FACTORY | Update slip status (PENDING → RESERVED etc.) |
| GET | `/slip/{slipId}/challan/pdf` | ADMIN, FACTORY | Download delivery challan PDF |

### 3.3 Raw Materials (`/api/v1`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/raw-materials/stock` | ADMIN, ACCOUNTING, FACTORY | Stock summary |
| GET | `/raw-materials/stock/inventory` | ADMIN, ACCOUNTING, FACTORY | Full inventory snapshot |
| GET | `/raw-materials/stock/low-stock` | ADMIN, ACCOUNTING, FACTORY | Low stock materials |
| POST | `/inventory/raw-materials/adjustments` | ADMIN, ACCOUNTING | Create raw material adjustment |
| GET | `/inventory/batches/expiring-soon` | ADMIN, ACCOUNTING, FACTORY, SALES | Expiring batches (default 30 days) |

### 3.4 Inventory Adjustments (`/api/v1/inventory/adjustments`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/` | ADMIN, ACCOUNTING | List all adjustments |
| POST | `/` | ADMIN, ACCOUNTING | Create FG inventory adjustment |

### 3.5 Batch Traceability (`/api/v1/inventory/batches`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/{id}/movements` | ADMIN, FACTORY, ACCOUNTING, SALES | Batch movement history (traceability) |

### 3.6 Opening Stock Import (`/api/v1/inventory`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/opening-stock` | ADMIN, ACCOUNTING, FACTORY | Import opening stock via CSV |
| GET | `/opening-stock` | ADMIN, ACCOUNTING, FACTORY | Import history |

---

## 4. State Machines & Status Lifecycles

### 4.1 PackagingSlip Status
```
PENDING ──→ PENDING_STOCK ──→ PENDING_PRODUCTION ──→ RESERVED ──→ DISPATCHED
  │                │                   │                  │
  └────────────────┴───────────────────┴──────────→ CANCELLED
                                                              ↑
                                                         BACKORDER ──→ CANCELLED
```

**Valid transitions (via PATCH):**
- `PENDING` → PENDING, PENDING_STOCK, PENDING_PRODUCTION, RESERVED
- `PENDING_STOCK` → PENDING_STOCK, PENDING_PRODUCTION, RESERVED
- `PENDING_PRODUCTION` → PENDING_PRODUCTION, RESERVED
- `RESERVED` → RESERVED, PENDING_STOCK, PENDING_PRODUCTION

**DISPATCHED can only be set via dispatch confirmation (POST /confirm).**

### 4.2 InventoryReservation Status
```
RESERVED ──→ PARTIAL ──→ FULFILLED
   │              ↑
   └──→ BACKORDER ┘
   │
   └──→ CANCELLED
```

- **RESERVED**: Initial state after allocation
- **PARTIAL**: Partially fulfilled (some shipped, some remaining)
- **FULFILLED**: All quantity shipped
- **BACKORDER**: Quantity not yet shipped, awaiting production
- **CANCELLED**: Released without shipping

### 4.3 InventoryAdjustment Status
```
DRAFT → POSTED
```
Created as DRAFT, immediately posted with journal entry.

### 4.4 RawMaterialAdjustment Status
```
DRAFT → POSTED
```

### 4.5 Movement Types
**Finished Goods (InventoryMovement):** RESERVE, RELEASE, DISPATCH, RECEIPT, ADJUSTMENT_IN, ADJUSTMENT_OUT

**Raw Materials (RawMaterialMovement):** RECEIPT, ADJUSTMENT_IN, ADJUSTMENT_OUT

---

## 5. Core Business Workflows

### 5.1 Stock Reservation (FinishedGoodsReservationEngine.reserveForOrder)
**Trigger:** Sales order approval

1. Lock sales order and existing packaging slips for update
2. Find or create primary PackagingSlip for the order
3. If existing slip matches order items, synchronize reservations (idempotent replay)
4. For each SalesOrderItem:
   - Lock the FinishedGood by productCode
   - Select batches using costing method (FIFO/LIFO/WAC)
   - Allocate from each batch (deduct `quantityAvailable`)
   - Increase `FinishedGood.reservedStock`
   - Create PackagingSlipLine with allocated batch + quantity
   - Create InventoryReservation (status=RESERVED)
   - Record RESERVE movement
5. If any item has insufficient stock → report shortage, set slip status to PENDING_PRODUCTION
6. If fully allocated → set slip status to RESERVED

### 5.2 Dispatch Confirmation (FinishedGoodsDispatchEngine.confirmDispatch)
**Trigger:** Factory confirms actual shipped quantities via POST /dispatch/confirm

1. Lock packaging slip for update
2. If already DISPATCHED → return existing confirmation (idempotent)
3. Collect reservation data for the sales order
4. For each PackagingSlipLine with confirmation:
   - Validate shipped quantity ≤ ordered quantity
   - Resolve unit cost via costing method (WAC or batch-specific)
   - Require non-zero dispatch cost (if stock on hand)
   - Deduct from `FinishedGood.currentStock` and `reservedStock`
   - Deduct from `FinishedGoodBatch.quantityTotal`
   - Record DISPATCH movement
   - Apply reservation fulfillment (set FULFILLED/PARTIAL/BACKORDER)
5. Set slip status to DISPATCHED (if any shipped) or PENDING_STOCK (if all backordered)
6. Set logistics metadata (transporter, driver, vehicle, challan reference)
7. If backorder quantities exist → create backorder PackagingSlip (status=BACKORDER)
8. Returns `DispatchPosting` records with (inventoryAccountId, cogsAccountId, totalCost) for accounting

### 5.3 Reservation Release (FinishedGoodsReservationEngine.releaseReservationsForOrder)
**Trigger:** Order cancellation

1. Find all non-terminal reservations for the order
2. For each active reservation:
   - Release batch quantity (add back to `quantityAvailable`)
   - Deduct from `reservedStock`
   - Record RELEASE movement
   - Set status to CANCELLED
3. Cancel non-dispatched packaging slips

### 5.4 Backorder Cancellation (PackagingSlipService.cancelBackorderSlip)
**Trigger:** POST /dispatch/backorder/{slipId}/cancel

1. Validate slip is BACKORDER status
2. Release reserved quantities for backorder lines
3. Restore batch availability and FG reserved stock
4. Set slip status to CANCELLED
5. Sync order status (may revert to READY_TO_SHIP if primary slip dispatched)

### 5.5 Raw Material Receipt (RawMaterialService.recordReceipt)
**Trigger:** Purchase order goods receipt, manual intake

1. Lock raw material
2. Create RawMaterialBatch with quantity, cost, supplier, dates
3. Increase `RawMaterial.currentStock`
4. Record RECEIPT movement
5. Post journal entry (debit inventory, credit accounts payable)

### 5.6 Raw Material Adjustment (RawMaterialService.adjustStock)
**Trigger:** POST /inventory/raw-materials/adjustments

1. Idempotency check via key + signature
2. Lock all affected raw materials (sorted by ID to prevent deadlock)
3. For INCREASE direction:
   - Add quantity to `currentStock`
   - Create new batch with source=ADJUSTMENT
   - Record ADJUSTMENT_IN movement
4. For DECREASE direction:
   - Validate sufficient stock
   - Deduct from `currentStock`
   - Issue from batches FIFO
   - Record ADJUSTMENT_OUT movement
5. Post journal entry via AccountingFacade

### 5.7 Finished Good Inventory Adjustment (InventoryAdjustmentService.createAdjustment)
**Trigger:** POST /inventory/adjustments

1. Idempotency check with retry on optimistic lock / data integrity violation
2. Lock finished goods (sorted by ID)
3. For RECOUNT_UP:
   - Add to `currentStock`
   - Create adjustment batch (source=ADJUSTMENT)
   - Record ADJUSTMENT_IN movement
4. For DAMAGED/SHRINKAGE/OBSOLETE:
   - Validate available stock (current - reserved)
   - Deduct from `currentStock`
   - Consume from batches using costing method
   - Record ADJUSTMENT_OUT movement
5. Post standardized journal entry

### 5.8 Opening Stock Import (OpeningStockImportService.importOpeningStock)
**Trigger:** POST /inventory/opening-stock (CSV file upload)

1. Idempotency and batch-key validation
2. Parse CSV rows with columns: type, sku, unit, quantity, unit_cost, batch_code, manufactured_at, expiry_date
3. For each row:
   - Validate SKU readiness (catalog + inventory ready)
   - RAW_MATERIAL: create batch, increase stock, record RECEIPT movement
   - FINISHED_GOOD: create batch, increase stock, record RECEIPT movement
4. Post consolidated opening stock journal entry (debit inventory accounts, credit OPEN-BAL equity)
5. Save import record with results/errors JSON

---

## 6. Valuation Logic

### 6.1 Costing Methods
Supports three methods resolved via `CostingMethodService.resolveActiveMethod()`:
- **FIFO** (First In, First Out) — oldest batches consumed first
- **LIFO** (Last In, First Out) — newest batches consumed first
- **WAC** (Weighted Average Cost) — average cost across all batches

### 6.2 Weighted Average Cost (WAC)
`InventoryValuationService.currentWeightedAverageCost()`:
- Calculated via `FinishedGoodBatchRepository.calculateWeightedAverageCost()` (SQL aggregate)
- Cached in-memory for 5 minutes (`WAC_CACHE_MILLIS`)
- Invalidated on any stock mutation (dispatch, adjustment, reservation release)

### 6.3 Dispatch Unit Cost Resolution
`InventoryValuationService.resolveDispatchUnitCost()`:
- If company active method is WAC → use weighted average cost
- Otherwise → use batch-specific `unitCost`
- Zero-cost dispatch blocked when stock on hand exists

### 6.4 Stock Summary Unit Cost
`InventoryValuationService.stockSummaryUnitCost()`:
- For WAC: returns weighted average cost directly
- For FIFO/LIFO: iterates batches in order, accumulating value against on-hand quantity
- Returns blended unit cost (total value / total quantity)

---

## 7. Events Published

### 7.1 InventoryMovementEvent
**Published by:** `InventoryMovementRecorder` (only for RESERVE, RELEASE, DISPATCH, RECEIPT movement types)

**Fields:**
- `movementType`: RECEIPT, ISSUE, TRANSFER, ADJUSTMENT_IN, ADJUSTMENT_OUT, SCRAP, RETURN_TO_VENDOR, RETURN_FROM_CUSTOMER
- `inventoryType`: RAW_MATERIAL, FINISHED_GOOD, WORK_IN_PROGRESS
- `itemId`, `itemCode`, `itemName`
- `quantity`, `unitCost`, `totalCost`
- `sourceAccountId`, `destinationAccountId` (GL accounts)
- `movementId` (for idempotency)
- `referenceNumber`, `movementDate`, `memo`
- `relatedEntityId`, `relatedEntityType`

**Mapping:**
- "RECEIPT" movement type → `MovementType.RECEIPT`
- "DISPATCH" movement type → `MovementType.ISSUE`
- Other movement types (RESERVE, RELEASE) → event not published

### 7.2 InventoryValuationChangedEvent
**Defined but publishing not observed in current inventory services.**
Likely published by accounting module subscribers or other modules.

**Fields:**
- `inventoryType`: RAW_MATERIAL, FINISHED_GOOD, WORK_IN_PROGRESS
- `oldValue` / `newValue` (total inventory value)
- `oldUnitCost` / `newUnitCost`
- `reason`: COST_METHOD_CHANGE, LANDED_COST_ADJUSTMENT, MARKET_REVALUATION, PHYSICAL_COUNT_ADJUSTMENT, STANDARD_COST_UPDATE, PURCHASE_PRICE_VARIANCE, SCRAP_WRITEOFF, INTERCOMPANY_TRANSFER

---

## 8. Cross-Module Dependencies

| Dependency | Direction | Purpose |
|---|---|---|
| **Sales** (SalesOrder, SalesOrderItem, Dealer) | Inbound | Drives reservation, dispatch, packaging slips |
| **Company** (Company) | Inbound | Multi-tenancy, timezone, default accounts |
| **Accounting** (AccountingFacade, CostingMethodService, GstService) | Outbound | Journal entries, costing methods, GST calculation |
| **Purchasing** (Supplier) | Inbound | Raw material batch supplier reference |
| **Factory** (PackingRecord) | Inbound | Raw material movements linked to packing steps |
| **Production** (ProductionProduct, ProductionBrand, SkuReadinessService) | Outbound | Sync raw material → product catalog, SKU readiness checks |

---

## 9. Key Service Architecture

```
FinishedGoodsService (facade)
  └── FinishedGoodsWorkflowEngineService (composition root)
        ├── FinishedGoodsReservationEngine (reserve/release)
        ├── FinishedGoodsDispatchEngine (dispatch/confirm)
        ├── PackagingSlipService (slip lifecycle, backorders)
        ├── InventoryValuationService (WAC, costing)
        ├── InventoryMovementRecorder (movement + events)
        └── BatchNumberService (sequence generation)

RawMaterialService (standalone)
  ├── recordReceipt (batch creation + journal)
  ├── adjustStock (increase/decrease + journal)
  └── createBatch/intake (idempotent manual intake)

InventoryAdjustmentService (standalone)
  └── createAdjustment (FG adjustments + journal)

OpeningStockImportService (standalone)
  └── importOpeningStock (CSV import + consolidated journal)

InventoryBatchTraceabilityService (read-only)
  └── getBatchMovementHistory

InventoryBatchQueryService (read-only)
  └── listExpiringSoonBatches

DeliveryChallanPdfService (read-only)
  └── renderDeliveryChallanPdf (Thymeleaf → PDF)
```

---

## 10. Idempotency Patterns

All write endpoints use idempotency controls:

| Operation | Idempotency Mechanism |
|---|---|
| Raw material adjustment | `idempotencyKey` + `idempotencyHash` on RawMaterialAdjustment |
| FG inventory adjustment | `idempotencyKey` + `idempotencyHash` on InventoryAdjustment |
| Raw material intake | `idempotencyKey` + `idempotencyHash` on RawMaterialIntakeRecord |
| Opening stock import | `idempotencyKey` + `openingStockBatchKey` on OpeningStockImport |
| Dispatch confirmation | PackagingSlip status check (DISPATCHED = already done) |
| Reservation | Slip line matching + reservation synchronization |

---

## 11. Security & Role Matrix

| Role | Access |
|---|---|
| ROLE_ADMIN | Full access to all inventory endpoints |
| ROLE_ACCOUNTING | Adjustments, stock views, low stock, thresholds |
| ROLE_FACTORY | Dispatch, stock views, opening stock, low stock |
| ROLE_SALES | FG listing, stock summary, low stock, packaging slips, batch traceability |
| OPERATIONAL_DISPATCH | Dispatch confirmation (composite check) |

Factory-only view (ROLE_FACTORY without elevated roles) redacts cost/financial data from responses.

---

## 12. Notable Design Patterns

1. **Pessimistic Locking**: All write flows lock entities (`lockByCompanyAndId`, `lockByCompanyAndProductCode`) in sorted ID order to prevent deadlocks
2. **Idempotent Writes**: Every mutation endpoint has idempotency keys + hash-based request deduplication
3. **Costing Method Strategy**: Batch selection (FIFO/LIFO/WAC) resolved at runtime per company
4. **WAC Caching**: In-memory cache with 5-minute TTL, invalidated on stock mutations
5. **Dual Stock Tracking**: `currentStock` (aggregate) + `quantityAvailable` per batch (allocatable)
6. **Soft Backorder**: Backorder slips created automatically when partial shipment occurs
7. **Company Timezone**: All timestamps use `CompanyTime.now(company)` / `CompanyClock`
8. **Event-Driven GL**: InventoryMovementEvent published for accounting module to consume
