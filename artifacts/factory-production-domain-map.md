# BigBright Paints ERP — Factory / Production Domain Map

> Auto-generated deep investigation of the `factory` and `production` modules in `erp-domain`.

---

## 1. Module Structure Overview

The factory/production domain spans **two modules**:

| Module | Package | Purpose |
|--------|---------|---------|
| **factory** | `modules/factory` | Core manufacturing operations: production logging, packing, cost allocation, packaging material management |
| **production** | `modules/production` | Product catalog (brands, products, SKU management), catalog import, SKU readiness checks |

### 1.1 Package Layout

**`modules/factory/`**
```
controller/    4 controllers (FactoryController, PackingController, PackagingMappingController, ProductionLogController)
domain/       11 entities + 5 repositories + 1 enum
service/      18 services
dto/          27 DTOs
event/        1 event (PackagingSlipEvent)
```

**`modules/production/`**
```
controller/    1 controller (CatalogController)
domain/        3 entities + 3 repositories
service/       3 services (CatalogService, ProductionCatalogService, SkuReadinessService)
dto/          13 DTOs
```

---

## 2. Entity Map

### 2.1 Factory Module Entities

| Entity | Table | Description |
|--------|-------|-------------|
| **ProductionPlan** | `production_plans` | Planned production orders with product name, quantity, planned date, status |
| **ProductionBatch** | `production_batches` | Actual production batch execution linked to a ProductionPlan |
| **ProductionLog** | `production_logs` | **Central entity** — records a production run (mixing step): brand, product, batch size, mixed quantity, costs, status, linked materials & packing records |
| **ProductionLogMaterial** | `production_log_materials` | Line items of raw material consumption for a production log |
| **ProductionLogStatus** | (enum) | `MIXED` → `READY_TO_PACK` → `PARTIAL_PACKED` → `FULLY_PACKED` |
| **PackingRecord** | `packing_records` | Records one packing line item: finished good, size variant, quantity packed, pieces/boxes, packaging material consumed |
| **PackingRequestRecord** | `packing_request_records` | Idempotency record for packing requests (prevents duplicate packing) |
| **PackagingSizeMapping** | `packaging_size_mappings` | Maps packaging sizes (e.g., "1L", "5L") to raw material (bucket) for auto-deduction |
| **SizeVariant** | `size_variants` | Per-product size configuration (size label, carton quantity, liters per unit) |
| **FactoryTask** | `factory_tasks` | Lightweight task tracking linked to sales orders and packaging slips |

### 2.2 Production Module Entities

| Entity | Table | Description |
|--------|-------|-------------|
| **ProductionBrand** | `production_brands` | Brand master (name, code, logo) |
| **ProductionProduct** | `production_products` | Product master with SKU, category, colors, sizes, carton sizes, pricing, GST, and JSONB metadata for account IDs |
| **CatalogImport** | `catalog_imports` | Idempotent record of catalog spreadsheet imports |

### 2.3 Key Cross-Module Entities (from inventory module, referenced by factory)

| Entity | Relevance |
|--------|-----------|
| **FinishedGood** | Mirror of ProductionProduct in inventory; tracks stock, valuation/COGS/revenue accounts |
| **FinishedGoodBatch** | Batch-level inventory for finished goods with unit cost, size label, source=PRODUCTION |
| **RawMaterial** | Raw materials with MaterialType (PRODUCTION vs PACKAGING); linked to inventory account |
| **RawMaterialBatch** | Batch-level raw material inventory with FIFO/WAC costing |
| **RawMaterialMovement** | Movement records for raw material issues/receipts (reference: PRODUCTION_LOG or PACKING_RECORD) |
| **InventoryMovement** | Movement records for finished good receipts |
| **PackagingSlip** | Dispatch document linking sales orders to finished good batches (factory → dispatch → inventory → accounting) |
| **PackagingSlipLine** | Individual line items on a packaging slip |

---

## 3. API Endpoint Map

### 3.1 Factory Module Endpoints

All under `/api/v1/factory`, require `ROLE_ADMIN` or `ROLE_FACTORY`.

#### Production Plans
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/factory/production-plans` | List all production plans |
| `POST` | `/api/v1/factory/production-plans` | Create a production plan |
| `PUT` | `/api/v1/factory/production-plans/{id}` | Update a production plan |
| `PATCH` | `/api/v1/factory/production-plans/{id}/status` | Update plan status |
| `DELETE` | `/api/v1/factory/production-plans/{id}` | Delete a production plan |

#### Factory Tasks
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/factory/tasks` | List all factory tasks |
| `POST` | `/api/v1/factory/tasks` | Create a factory task |
| `PUT` | `/api/v1/factory/tasks/{id}` | Update a factory task |

#### Dashboard
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/factory/dashboard` | Factory dashboard (efficiency, completed plans, batches logged) |

#### Cost Allocation
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/factory/cost-allocation` | Allocate monthly labor/overhead variance to fully-packed batches |

#### Production Logs
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/factory/production/logs` | Create production log (material issue + mixing) |
| `GET` | `/api/v1/factory/production/logs` | List recent production logs (top 25) |
| `GET` | `/api/v1/factory/production/logs/{id}` | Get production log detail with materials & packing records |

#### Packing
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/factory/packing-records` | Record packing (requires `Idempotency-Key` header) |
| `GET` | `/api/v1/factory/unpacked-batches` | List unpacked batches (READY_TO_PACK or PARTIAL_PACKED) |
| `GET` | `/api/v1/factory/production-logs/{id}/packing-history` | Get packing history for a production log |
| `GET` | `/api/v1/factory/bulk-batches/{finishedGoodId}` | List bulk (semi-finished) batches for a finished good |
| `GET` | `/api/v1/factory/bulk-batches/{parentBatchId}/children` | List child FG batches from a parent bulk batch |

#### Packaging Setup (Mappings)
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/factory/packaging-mappings` | List all packaging size mappings |
| `GET` | `/api/v1/factory/packaging-mappings/active` | List active packaging size mappings |
| `POST` | `/api/v1/factory/packaging-mappings` | Create packaging size mapping (ADMIN only) |
| `PUT` | `/api/v1/factory/packaging-mappings/{id}` | Update packaging size mapping (ADMIN only) |
| `DELETE` | `/api/v1/factory/packaging-mappings/{id}` | Deactivate packaging size mapping (ADMIN only) |

### 3.2 Production Module Endpoints (Catalog)

All under `/api/v1/catalog`, require `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, or `ROLE_FACTORY`.

#### Brands
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/catalog/brands` | Create brand |
| `GET` | `/api/v1/catalog/brands` | List brands (optional `?active=true`) |
| `GET` | `/api/v1/catalog/brands/{brandId}` | Get brand |
| `PUT` | `/api/v1/catalog/brands/{brandId}` | Update brand |
| `DELETE` | `/api/v1/catalog/brands/{brandId}` | Deactivate brand |

#### Items
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/catalog/items` | Create catalog item (ADMIN/ACCOUNTING) |
| `GET` | `/api/v1/catalog/items` | Search items (`?q=&itemClass=&includeStock=&includeReadiness=&page=&pageSize=`) |
| `GET` | `/api/v1/catalog/items/{itemId}` | Get item with optional stock & readiness |
| `PUT` | `/api/v1/catalog/items/{itemId}` | Update item (ADMIN/ACCOUNTING) |
| `DELETE` | `/api/v1/catalog/items/{itemId}` | Deactivate item |

#### Import
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/catalog/import` | Bulk import from spreadsheet (multipart, idempotent) |

### 3.3 Dispatch Endpoints (Inventory Module — Factory-Facing)

Under `/api/v1/dispatch`, require `ROLE_ADMIN` or `ROLE_FACTORY`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/dispatch/pending` | List packaging slips pending dispatch |
| `GET` | `/api/v1/dispatch/preview/{slipId}` | Get dispatch preview (ordered vs available) |
| `GET` | `/api/v1/dispatch/slip/{slipId}` | Get packaging slip details |
| `GET` | `/api/v1/dispatch/order/{orderId}` | Get packaging slip by sales order |
| `POST` | `/api/v1/dispatch/confirm` | Confirm dispatch with actual shipped quantities |
| `POST` | `/api/v1/dispatch/backorder/{slipId}/cancel` | Cancel a backorder slip |
| `PATCH` | `/api/v1/dispatch/slip/{slipId}/status` | Update slip status (PENDING → PACKING → READY) |
| `GET` | `/api/v1/dispatch/slip/{slipId}/challan/pdf` | Download delivery challan PDF |

---

## 4. Complete Production Flow

### 4.1 End-to-End Manufacturing Flow

```
ProductionPlan → ProductionLog (Create) → [READY_TO_PACK]
       ↓                                        ↓
  Material Issue                        Packing (multiple sessions)
  (RM batch consumption)                - Semi-finished consumption
  + Labor/Overhead                      - Packaging material consumption
  + WIP journal                         - FG batch creation
  + Semi-finished batch creation         - FG receipt journal
       ↓                                        ↓
  Semi-Finished Batch                  [PARTIAL_PACKED / FULLY_PACKED]
  (RawMaterial with SKU-BULK)                  ↓
                                       Wastage closure (optional)
                                       - Residual wastage journal
                                               ↓
                                       PackagingSlip (from Sales)
                                       → Dispatch Confirmation
                                       → Invoice
```

### 4.2 Phase 1: Production Plan

**Entity:** `ProductionPlan`
**Service:** `FactoryService`
**API:** `/api/v1/factory/production-plans`

- Fields: planNumber, productName, quantity, plannedDate, status, notes
- Status values: `PLANNED` → (manually updated) → `COMPLETED`
- Plans are organizational/planning artifacts; production execution happens through `ProductionLog`
- Creation is idempotent by planNumber (insertIfAbsent pattern)

### 4.3 Phase 2: Production Log Creation (Material Mixing)

**Entity:** `ProductionLog`
**Service:** `ProductionLogService`
**API:** `POST /api/v1/factory/production/logs`

**Input:** `ProductionLogRequest` — brandId, productId, batchSize, mixedQuantity, materials[], laborCost, overheadCost, optional salesOrderId

**What happens when a production log is created:**

1. **Code Generation:** Auto-generates `productionCode` (e.g., `PROD-20260328-001`)
2. **Validation:** Validates brand belongs to company, product belongs to brand
3. **Status:** Set to `READY_TO_PACK`
4. **Wastage initialization:** `wastageQuantity` = `mixedQuantity` (full amount is "wasted" until packed)
5. **Material Issue (per material in request):**
   - Locks the raw material (pessimistic lock)
   - Checks sufficient stock
   - Consumes from batches using FIFO order (or weighted average cost if configured)
   - Uses **atomic deduction** (`deductQuantityIfSufficient`) to prevent race conditions
   - Creates `RawMaterialMovement` records (type: ISSUE, reference: PRODUCTION_LOG)
   - Creates `ProductionLogMaterial` records tracking material, batch, quantity, cost
6. **Cost Calculation:**
   - `materialCostTotal` = sum of all material costs
   - `unitCost` = (materialCost + laborCost + overheadCost) / mixedQuantity
7. **Semi-Finished Batch Registration:**
   - Auto-creates a `RawMaterial` with SKU = `{productSku}-BULK` (materialType=PRODUCTION)
   - Creates a `RawMaterialBatch` with the production code as batch code
   - Sets quantity = mixedQuantity, unitCost = production log's unitCost
   - Increments raw material stock
   - Creates a RECEIPT movement
   - Posts journal: DR semi-finished inventory account / CR WIP account
8. **Accounting Journals:**
   - **Material consumption journal:** DR WIP / CR Raw material inventory accounts (multi-line)
   - **Labor/overhead journal:** DR WIP / CR labor applied / CR overhead applied accounts
9. **Optional:** Links to `SalesOrder` (salesOrderId, salesOrderNumber)

### 4.4 Phase 3: Packing

**Service:** `PackingService`
**API:** `POST /api/v1/factory/packing-records` (requires `Idempotency-Key` header)

**Input:** `PackingRequest` — productionLogId, lines[], packedDate, packedBy, closeResidualWastage

Each line in `PackingLineRequest`:
- `childFinishedGoodId` — which sellable size to pack into
- `packagingSize` — e.g., "1L", "5L", "10L"
- `quantityLiters` — total volume packed
- `piecesCount`, `boxesCount`, `piecesPerBox` — piece-level tracking

**Packing Flow (per line):**

1. **Validate:** Production log is not FULLY_PACKED
2. **Idempotency:** Reserve idempotency key, check hash for replay
3. **Resolve Allowed Sellable Sizes:** Looks up product family (variantGroupId) → resolves matching finished goods → size variants
4. **Resolve Size Variant:** Auto-creates if missing (normalized packaging size)
5. **Calculate Quantity:** `quantityLiters` or `piecesCount × litersPerUnit`
6. **Save Packing Record:** Creates `PackingRecord` with all piece/box details
7. **Consume Packaging Materials:**
   - Looks up `PackagingSizeMapping` for the size (maps to a PACKAGING-type raw material)
   - Consumes from packaging material batches (FIFO)
   - Creates `RawMaterialMovement` (type: ISSUE, reference: PACKING_RECORD)
   - Posts packaging consumption journal: DR WIP / CR packaging material inventory
8. **Consume Semi-Finished Inventory:**
   - Looks up semi-finished raw material (SKU-BULK) and its batch
   - Deducts packed quantity from semi-finished batch
   - Creates `RawMaterialMovement` (type: ISSUE, reference: PACKING_RECORD)
9. **Register Finished Good Batch:**
   - Calls `FinishedGoodBatchRegistrar.registerReceipt()`
   - Creates `FinishedGoodBatch` with unit cost = semi-finished cost + packaging cost per unit
   - Increments `FinishedGood.currentStock`
   - Creates `InventoryMovement` (type: RECEIPT)
   - Posts FG receipt journal: DR FG valuation account / CR semi-finished account + CR WIP (packaging)
   - Links journal to both inventory and raw material movements

10. **Update Production Log State:**
    - Atomically increments `totalPackedQuantity`
    - Updates status: READY_TO_PACK / PARTIAL_PACKED / FULLY_PACKED
    - Updates `wastageQuantity` = mixedQuantity - totalPackedQuantity

### 4.5 Phase 4: Wastage Closure

**Triggered by:** `closeResidualWastage = true` in packing request

When the operator decides no more packing will happen:

1. **Calculate residual wastage:** `mixedQuantity - totalPackedQuantity`
2. **Consume semi-finished wastage:** Deducts remaining from semi-finished batch (movement type: WASTAGE)
3. **Post wastage journal:** DR wastage account / CR WIP account
4. **Set status:** `FULLY_PACKED`
5. **Set wastage reason code:** `PROCESS_LOSS` or `NONE`

---

## 5. Packaging Slip Lifecycle (Factory → Dispatch → Inventory → Accounting)

The "packaging slip" in this system is **NOT** the factory packing record. It's a **dispatch document** in the inventory module.

**Entity:** `PackagingSlip` (in inventory module)
**Service:** `PackagingSlipService`, `FinishedGoodsDispatchEngine`

### 5.1 Packaging Slip Creation

- Created when a `SalesOrder` is confirmed/approved
- Links to a `SalesOrder`
- Contains `PackagingSlipLine` items referencing `FinishedGoodBatch` with ordered quantity
- Can be marked as `isBackorder = true` when insufficient stock

### 5.2 Slip Status Flow

```
PENDING → PACKING → READY → DISPATCHED
                    ↓
              (can create backorder slip)
```

### 5.3 Dispatch Confirmation

**API:** `POST /api/v1/dispatch/confirm`
**Service:** `SalesDispatchReconciliationService` → `FinishedGoodsDispatchEngine`

1. Validates slip exists and is not already dispatched
2. For each line, confirms actual shipped quantity
3. Deducts from `FinishedGoodBatch.quantityAvailable`
4. Deducts from `FinishedGood.currentStock` and `reservedStock`
5. Creates `InventoryMovement` (type: ISSUE)
6. Posts dispatch journal entries (revenue, COGS)
7. Posts GST journal
8. If shipped < ordered, creates a backorder packaging slip
9. Records transporter details, driver, vehicle number, challan reference
10. Generates delivery challan PDF

### 5.4 Factory Visibility

- `FactorySlipEventListener` listens to `PackagingSlipEvent` (Spring ApplicationEvent)
- Currently logs the event; designed to be extended for queue/notification
- `FactoryTask` can be linked to a `packagingSlipId` and `salesOrderId`

---

## 6. Material Issue Flows (Raw Material Consumption)

### 6.1 Production Material Issue

**Trigger:** Production log creation
**Method:** `ProductionLogService.issueFromBatches()`

**Flow:**
1. Lock raw material batches in FIFO order (pessimistic write lock)
2. Calculate cost using costing method: FIFO or Weighted Average Cost
3. For each batch with available quantity:
   - Calculate take quantity (min of available and remaining needed)
   - Snapshot unit cost before deduction
   - **Atomic deduction:** `rawMaterialBatchRepository.deductQuantityIfSufficient(batchId, take)` — prevents concurrent double-consumption
   - Create `RawMaterialMovement` (type: ISSUE, reference: PRODUCTION_LOG, referenceId: productionCode)
4. If insufficient total quantity across all batches → error
5. Update `RawMaterial.currentStock`
6. Link movements to the material consumption journal entry

### 6.2 Packaging Material Consumption

**Trigger:** Packing operation
**Method:** `PackagingMaterialService.consumePackagingMaterial()`

**Flow:**
1. Look up `PackagingSizeMapping` for the packaging size (e.g., "1L" → RawMaterial for 1L buckets)
2. Validate material is of type `PACKAGING`
3. Calculate required quantity: `piecesCount × unitsPerPack`
4. Consume from packaging material batches (FIFO with WAC)
5. Create `RawMaterialMovement` (type: ISSUE, reference: PACKING_RECORD)
6. Update `RawMaterial.currentStock`

### 6.3 Semi-Finished Material Flows

**Receipt (at production log creation):**
- Creates semi-finished `RawMaterial` (SKU: `{productSku}-BULK`, type: PRODUCTION)
- Creates `RawMaterialBatch` with quantity = mixedQuantity
- Creates RECEIPT movement
- Journal: DR semi-finished inventory / CR WIP

**Issue (at packing):**
- Deducts packed quantity from semi-finished batch
- Creates ISSUE movement (reference: PACKING_RECORD)
- Deducted from `RawMaterial.currentStock`

**Wastage (at closure):**
- Deducts residual wastage from semi-finished batch
- Creates WASTAGE movement

---

## 7. WIP Tracking

### 7.1 WIP Account

- Configured per product via metadata: `wipAccountId`
- Required for all finished good products before production can proceed
- Also requires: `laborAppliedAccountId`, `overheadAppliedAccountId`

### 7.2 WIP Journal Entries

| Event | Debit | Credit | Reference |
|-------|-------|--------|-----------|
| Material consumption | WIP account | Raw material inventory accounts | `{productionCode}-RM` |
| Labor/Overhead applied | WIP account | Labor applied / Overhead applied | `{productionCode}-LABOH` |
| Semi-finished receipt | Semi-finished inventory | WIP account | `{productionCode}-SEMIFG` |
| Packaging consumption | WIP account | Packaging material inventory | `{productionCode}-PACK-{id}-PACKMAT` |
| FG receipt | FG valuation | Semi-finished inventory + WIP (packaging) | Packing reference |
| Wastage | Wastage expense account | WIP account | `{productionCode}-WASTE` |

### 7.3 WIP to Finished Goods

The conversion from WIP to FG happens at packing time:
- Semi-finished inventory (representing WIP in bulk form) is consumed
- Finished good batches are created with cost = production unit cost + packaging cost per unit

---

## 8. Scrap / Wastage Handling

### 8.1 Process Loss

- Default wastage reason: `PROCESS_LOSS`
- On production log creation, `wastageQuantity` is initialized to `mixedQuantity` (everything is "wasted" until packed)
- As packing happens, wastage decreases proportionally
- When `closeResidualWastage = true`:
  - Remaining `mixedQuantity - totalPackedQuantity` is confirmed as wastage
  - Semi-finished batch is deducted by wastage amount
  - Wastage journal posted: DR wastage expense / CR WIP

### 8.2 Wastage Account

- Configured per product via metadata: `wastageAccountId`
- Required for wastage journal posting

### 8.3 Wastage Reporting

- `WastageReportDto` tracks: productionLogId, productionCode, productName, batchColour, mixedQuantity, totalPackedQuantity, wastageQuantity, wastagePercentage, wastageValue

---

## 9. Cost Allocation

### 9.1 Real-Time Cost Tracking

During production and packing, costs are tracked in real-time:

| Cost Component | When Captured | Where Stored |
|----------------|---------------|--------------|
| Material cost | Production log creation | `ProductionLog.materialCostTotal` |
| Labor cost | Production log creation | `ProductionLog.laborCostTotal` |
| Overhead cost | Production log creation | `ProductionLog.overheadCostTotal` |
| Unit cost | Production log creation | `ProductionLog.unitCost` = (material + labor + overhead) / mixedQuantity |
| Packaging cost | Packing operation | `PackingRecord.packagingCost` |
| FG batch unit cost | Packing operation | `FinishedGoodBatch.unitCost` = production unit cost + packaging cost/unit |

### 9.2 Monthly Cost Variance Allocation

**Service:** `CostAllocationService`
**API:** `POST /api/v1/factory/cost-allocation`

**Purpose:** Allocate the difference between actual monthly labor/overhead costs and what was applied during production.

**Flow:**
1. Input: year, month, actual labor cost, actual overhead cost, account IDs
2. Find all `FULLY_PACKED` production logs in the period
3. Calculate total liters produced
4. Calculate labor variance = actual labor - sum of applied labor
5. Calculate overhead variance = actual overhead - sum of applied overhead
6. Distribute variance proportionally across batches by liters produced
7. Update each batch's labor/overhead totals and recalculate unit cost
8. Update finished good batch costs
9. Post variance journals: DR finished goods / CR payroll expense + overhead expense
10. Idempotency: Skips batches that already have variance journals for the period

**Output:** `CostAllocationResponse` — batches processed, total liters, variance allocated, journal entry IDs, summary

---

## 10. Production Variance Handling

### 10.1 Cost Variance

Handled through the monthly cost allocation process (Section 9.2):
- Labor variance (actual vs applied)
- Overhead variance (actual vs applied)
- Both allocated proportionally to production volume

### 10.2 Quantity Variance

- **Mixing vs packing variance:** Tracked as `wastageQuantity = mixedQuantity - totalPackedQuantity`
- **Reason codes:** `PROCESS_LOSS` (default), `NONE` (if zero wastage)
- No explicit "scrap" entity — residual is treated as process loss

---

## 11. Inventory Effects of Production Operations

### 11.1 Production Log Creation Effects

| Inventory Item | Effect |
|----------------|--------|
| `RawMaterial.currentStock` | Decreased by consumed material quantities |
| `RawMaterialBatch.quantity` | Decreased (atomic deduction) per FIFO |
| New `RawMaterialBatch` | Created for semi-finished (SKU-BULK) |
| Semi-finished `RawMaterial.currentStock` | Increased by mixedQuantity |

### 11.2 Packing Operation Effects

| Inventory Item | Effect |
|----------------|--------|
| Packaging `RawMaterial.currentStock` | Decreased by packaging material consumed |
| Packaging `RawMaterialBatch.quantity` | Decreased (atomic deduction) |
| Semi-finished `RawMaterial.currentStock` | Decreased by packed quantity |
| Semi-finished `RawMaterialBatch.quantity` | Decreased by packed quantity |
| `FinishedGood.currentStock` | Increased by packed quantity |
| New `FinishedGoodBatch` | Created with packed quantity, unit cost, size label |
| `FinishedGoodBatch.quantityTotal` | = packed quantity |
| `FinishedGoodBatch.quantityAvailable` | = packed quantity (available for sale) |

### 11.3 Wastage Closure Effects

| Inventory Item | Effect |
|----------------|--------|
| Semi-finished `RawMaterial.currentStock` | Decreased by wastage amount |
| Semi-finished `RawMaterialBatch.quantity` | Decreased by wastage amount |

### 11.4 Dispatch Effects

| Inventory Item | Effect |
|----------------|--------|
| `FinishedGood.currentStock` | Decreased by shipped quantity |
| `FinishedGood.reservedStock` | Decreased by fulfilled reservation |
| `FinishedGoodBatch.quantityAvailable` | Decreased by shipped quantity |

---

## 12. Accounting Effects of Production Operations

### 12.1 Journal Entry Map

| # | Trigger | Reference Pattern | Debit | Credit | Module Tag |
|---|---------|-------------------|-------|--------|------------|
| 1 | Material consumption | `{prodCode}-RM` | WIP account | Raw material inventory accounts | `FACTORY_PRODUCTION` |
| 2 | Labor/Overhead applied | `{prodCode}-LABOH` | WIP account | Labor applied + Overhead applied | `FACTORY_PRODUCTION` |
| 3 | Semi-finished receipt | `{prodCode}-SEMIFG` | Semi-finished inventory | WIP account | `FACTORY_PRODUCTION` |
| 4 | Packaging consumption | `{prodCode}-PACK-{id}-PACKMAT` | WIP account | Packaging material inventory | `FACTORY_PACKING` |
| 5 | FG receipt | Packing reference | FG valuation | Semi-finished inventory + WIP (packaging) | `FACTORY_PACKING` |
| 6 | Wastage | `{prodCode}-WASTE` | Wastage expense | WIP account | `FACTORY_PACKING` |
| 7 | Cost variance | `{prodCode}-{periodKey}` | FG account | Payroll expense + Overhead expense | Cost variance |

### 12.2 Product Account Configuration (via ProductionProduct.metadata JSONB)

Required account IDs per product:

| Metadata Key | Purpose |
|-------------|---------|
| `wipAccountId` | WIP account for production costs |
| `laborAppliedAccountId` | Credit account for labor costs |
| `overheadAppliedAccountId` | Credit account for overhead costs |
| `semiFinishedAccountId` | Semi-finished inventory account (fallback: `fgValuationAccountId`) |
| `fgValuationAccountId` | Finished good valuation (asset) account |
| `fgCogsAccountId` | COGS account for dispatch |
| `fgRevenueAccountId` | Revenue account for invoicing |
| `fgDiscountAccountId` | Discount contra-revenue account |
| `fgTaxAccountId` | Tax/GST output account |
| `wastageAccountId` | Wastage expense account |

---

## 13. Approval Workflows

### 13.1 Factory-Specific Approvals

**There are NO dedicated approval workflows in the factory/production module.** Production and packing operations are executed directly by users with `ROLE_FACTORY` or `ROLE_ADMIN` roles without an approval gate.

### 13.2 Related Approval Workflows

| Workflow | Module | Description |
|----------|--------|-------------|
| Sales order auto-approval | `orchestrator` | `OrderAutoApprovalListener` auto-approves sales orders within credit limits |
| Dispatch metadata validation | `sales` | `DispatchMetadataValidator` enforces transporter/driver/vehicle/challan data |
| Export request approval | `admin` | `ExportApprovalService` for data exports |
| Accounting period close | `accounting` | Period close approval workflow |
| Credit limit override | `sales` | `CreditLimitOverrideService` |

### 13.3 Role-Based Access Control

| Role | Factory Access | Packing Access | Catalog Access | Dispatch Access |
|------|---------------|----------------|----------------|-----------------|
| `ROLE_ADMIN` | Full | Full | Full (CRUD) | Full |
| `ROLE_FACTORY` | Full | Full | Read-only | Full (with transport metadata) |
| `ROLE_ACCOUNTING` | — | Read | Full (CRUD) | Full |
| `ROLE_SALES` | — | — | Read-only | Full |

---

## 14. Service Dependency Map

### 14.1 Factory Service Dependencies

```
FactoryController
├── FactoryService (plans, tasks, dashboard)
├── CostAllocationService (monthly variance)
PackingController
├── PackingService
│   ├── PackingIdempotencyService
│   ├── PackingLineResolver
│   │   └── SizeVariantRepository
│   ├── PackingAllowedSizeService
│   │   ├── ProductionProductRepository
│   │   ├── FinishedGoodRepository
│   │   └── SizeVariantRepository
│   ├── PackagingMaterialService
│   │   └── PackagingSizeMappingRepository
│   ├── PackingInventoryService
│   │   └── PackingProductSupport
│   ├── PackingBatchService
│   │   ├── FinishedGoodBatchRegistrar
│   │   │   ├── BatchNumberService
│   │   │   └── FinishedGoodsService
│   │   ├── PackingProductSupport
│   │   └── AccountingFacade
│   ├── PackingJournalBuilder
│   ├── PackingJournalLinkHelper
│   ├── PackingReadService
│   └── ProductionLogService
PackagingMappingController
└── PackagingMaterialService
ProductionLogController
└── ProductionLogService
    ├── AccountingFacade
    ├── PackingAllowedSizeService
    └── RawMaterial* repositories
```

### 14.2 Cross-Module Dependencies

```
factory ──→ inventory (FinishedGood, FinishedGoodBatch, RawMaterial, RawMaterialBatch)
factory ──→ accounting (AccountingFacade, JournalCreationRequest)
factory ──→ production (ProductionBrand, ProductionProduct)
factory ──→ sales (SalesOrder reference)
factory ──→ company (Company, CompanyContextService)
```

---

## 15. Key Design Patterns

### 15.1 Idempotency

- **Production plans:** Idempotent by planNumber with insertIfAbsent
- **Factory tasks:** Idempotent by (salesOrderId, title) with payload equivalence check
- **Packing:** Full idempotency with key + payload hash + reserved record + replay
- **Catalog imports:** Idempotent by idempotency key with file hash

### 15.2 Concurrency Control

- **Pessimistic locking** on production logs during packing (`lockByCompanyAndId`)
- **Pessimistic locking** on raw material batches during consumption
- **Atomic quantity deduction** (`deductQuantityIfSufficient`) prevents overselling
- **Atomic packed quantity increment** (`incrementPackedQuantityAtomic`)
- **Company row-level lock** (`companyRepository.lockById`) during production log creation

### 15.3 Semi-Finished Goods Pattern

The system uses a **semi-finished raw material** as an intermediary:
- Production creates bulk material (SKU-BULK) → stored as RawMaterial
- Packing consumes from semi-finished → creates finished good batches
- This bridges the gap between "mixed" and "packed" states
- Enables partial packing across multiple sessions

### 15.4 Product Family / Variant Groups

- Products can be grouped by `variantGroupId`
- Packing resolves "allowed sellable sizes" from the product family
- Each size variant maps to a different FinishedGood
- `SizeVariant` entity tracks per-product size configuration (liters/unit, carton qty)

---

## 16. Data Model Summary

```
ProductionBrand ──1:N──→ ProductionProduct
                            │
                            │ (variantGroupId groups sizes)
                            │
                            ├──→ FinishedGood (inventory mirror)
                            │       └──→ FinishedGoodBatch
                            │
                            ├──→ SizeVariant (per product)
                            │
                            ├──→ RawMaterial (semi-finished, SKU-BULK)
                            │       └──→ RawMaterialBatch
                            │
                            └──→ [metadata: account IDs]

ProductionPlan ──1:N──→ ProductionBatch (actual production)

ProductionLog ──1:N──→ ProductionLogMaterial (raw materials consumed)
            ──1:N──→ PackingRecord (packing operations)
                        └──→ FinishedGoodBatch (created during packing)
                        └──→ SizeVariant
                        └──→ RawMaterial (packaging material)

PackagingSizeMapping: packagingSize ──→ RawMaterial (PACKAGING type)

FactoryTask: optional → SalesOrder, PackagingSlip

PackagingSlip (inventory module) ──1:N──→ PackagingSlipLine
            ──→ SalesOrder
            Line ──→ FinishedGoodBatch
```

---

## 17. Source File Index

### Factory Module

| Category | File | Lines |
|----------|------|-------|
| **Entities** | `factory/domain/ProductionLog.java` | ~210 |
| | `factory/domain/ProductionPlan.java` | ~85 |
| | `factory/domain/ProductionBatch.java` | ~75 |
| | `factory/domain/ProductionLogMaterial.java` | ~90 |
| | `factory/domain/ProductionLogStatus.java` | ~12 |
| | `factory/domain/PackingRecord.java` | ~180 |
| | `factory/domain/PackingRequestRecord.java` | ~65 |
| | `factory/domain/PackagingSizeMapping.java` | ~120 |
| | `factory/domain/SizeVariant.java` | ~100 |
| | `factory/domain/FactoryTask.java` | ~80 |
| **Controllers** | `factory/controller/FactoryController.java` | ~110 |
| | `factory/controller/PackingController.java` | ~150 |
| | `factory/controller/PackagingMappingController.java` | ~70 |
| | `factory/controller/ProductionLogController.java` | ~50 |
| **Services** | `factory/service/ProductionLogService.java` | ~700+ |
| | `factory/service/PackingService.java` | ~400+ |
| | `factory/service/CostAllocationService.java` | ~300+ |
| | `factory/service/PackagingMaterialService.java` | ~350+ |
| | `factory/service/PackingInventoryService.java` | ~120 |
| | `factory/service/PackingBatchService.java` | ~150 |
| | `factory/service/FinishedGoodBatchRegistrar.java` | ~90 |
| | `factory/service/FactoryService.java` | ~240 |
| | `factory/service/FactorySlipEventListener.java` | ~20 |
| | `factory/service/PackingAllowedSizeService.java` | ~250 |
| | `factory/service/PackingProductSupport.java` | ~150 |
| | `factory/service/PackingLineResolver.java` | ~130 |
| | `factory/service/PackingIdempotencyService.java` | ~120 |
| | `factory/service/PackingReadService.java` | ~100 |
| | `factory/service/PackingJournalBuilder.java` | ~100 |
| | `factory/service/PackingJournalLinkHelper.java` | ~70 |
| | `factory/service/BulkPackingService.java` | ~30 |
| | `factory/service/BulkPackingReadService.java` | ~250 |
| **DTOs** | `factory/dto/` | 27 files |
| **Events** | `factory/event/PackagingSlipEvent.java` | ~5 |

### Production Module

| Category | File |
|----------|------|
| **Entities** | `production/domain/ProductionBrand.java` |
| | `production/domain/ProductionProduct.java` |
| | `production/domain/CatalogImport.java` |
| **Controllers** | `production/controller/CatalogController.java` |
| **Services** | `production/service/CatalogService.java` |
| | `production/service/ProductionCatalogService.java` |
| | `production/service/SkuReadinessService.java` |
