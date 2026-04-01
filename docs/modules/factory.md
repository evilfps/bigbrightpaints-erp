# Factory / Manufacturing Module

Last reviewed: 2026-03-30

This packet documents the factory/manufacturing module, which owns **manufacturing execution truth** for the ERP. It covers production logs, packing operations, packaging mappings, batch registration, cost allocation, and the dispatch handoff boundary into sales.

Catalog/setup truth (brands, items, SKU readiness) is documented separately in [catalog-setup.md](catalog-setup.md). Stock and dispatch execution truth is documented in [inventory.md](inventory.md). The factory module creates finished-good batches and posts WIP/cost journals; inventory tracks them and manages the physical dispatch lifecycle.

---

## 1. Module Ownership

| Aspect | Owner |
| --- | --- |
| Package | `com.bigbrightpaints.erp.modules.factory` |
| Controllers | `FactoryController`, `PackingController`, `PackagingMappingController`, `ProductionLogController` |
| Primary services | `FactoryService` (plans, tasks, dashboard), `ProductionLogService` (material mixing, WIP costing), `PackingService` (packing orchestration), `CostAllocationService` (monthly variance), `PackagingMaterialService` (packaging consumption), `PackingAllowedSizeService` (size resolution), `PackingBatchService` (FG batch creation), `FinishedGoodBatchRegistrar` (batch registration), `BulkPackingService`, `BulkPackingReadService` (bulk batch queries) |
| Supporting services | `PackingIdempotencyService`, `PackingLineResolver`, `PackingProductSupport`, `PackingInventoryService`, `PackingJournalBuilder`, `PackingJournalLinkHelper`, `PackingReadService`, `PackagingSizeParser` |
| Domain entities | `ProductionPlan`, `ProductionBatch`, `ProductionLog`, `ProductionLogMaterial`, `ProductionLogStatus` (enum), `PackingRecord`, `PackingRequestRecord`, `PackagingSizeMapping`, `SizeVariant`, `FactoryTask` |
| DTO families | Production plan DTOs, production log DTOs, packing DTOs, packaging mapping DTOs, cost allocation DTOs, wastage report DTOs, factory task DTOs, bulk batch DTOs |
| Events | `PackagingSlipEvent` (Spring ApplicationEvent, consumed by `FactorySlipEventListener`) |
| Repositories | `ProductionPlanRepository`, `ProductionBatchRepository`, `ProductionLogRepository`, `ProductionLogMaterialRepository`, `PackingRecordRepository`, `PackingRequestRecordRepository`, `PackagingSizeMappingRepository`, `SizeVariantRepository`, `FactoryTaskRepository` |

---

## 2. Canonical Host and Route Map

All factory endpoints live under `/api/v1/factory/**`. Most require `ROLE_ADMIN` or `ROLE_FACTORY`. PackingController endpoints additionally grant `ROLE_ACCOUNTING`. Packaging mapping write operations are restricted to `ROLE_ADMIN` only.

### Production Plans

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/production-plans` | GET | ADMIN, FACTORY | List all production plans |
| `/api/v1/factory/production-plans` | POST | ADMIN, FACTORY | Create a production plan |
| `/api/v1/factory/production-plans/{id}` | PUT | ADMIN, FACTORY | Update a production plan |
| `/api/v1/factory/production-plans/{id}/status` | PATCH | ADMIN, FACTORY | Update plan status |
| `/api/v1/factory/production-plans/{id}` | DELETE | ADMIN, FACTORY | Delete a production plan |

### Factory Tasks

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/tasks` | GET | ADMIN, FACTORY | List all factory tasks |
| `/api/v1/factory/tasks` | POST | ADMIN, FACTORY | Create a factory task |
| `/api/v1/factory/tasks/{id}` | PUT | ADMIN, FACTORY | Update a factory task |

### Dashboard

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/dashboard` | GET | ADMIN, FACTORY | Factory dashboard (efficiency, completed plans, batches logged) |

### Cost Allocation

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/cost-allocation` | POST | ADMIN, FACTORY | Allocate monthly labor/overhead variance to fully-packed batches |

### Production Logs

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/production/logs` | POST | ADMIN, FACTORY | Create production log (material issue + mixing) |
| `/api/v1/factory/production/logs` | GET | ADMIN, FACTORY | List recent production logs (top 25) |
| `/api/v1/factory/production/logs/{id}` | GET | ADMIN, FACTORY | Get production log detail with materials and packing records |

### Packing

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/packing-records` | POST | ADMIN, FACTORY, ACCOUNTING | Record packing (requires `Idempotency-Key` header) |
| `/api/v1/factory/unpacked-batches` | GET | ADMIN, FACTORY, ACCOUNTING | List unpacked batches (READY_TO_PACK or PARTIAL_PACKED) |
| `/api/v1/factory/production-logs/{id}/packing-history` | GET | ADMIN, FACTORY, ACCOUNTING | Get packing history for a production log |
| `/api/v1/factory/bulk-batches/{finishedGoodId}` | GET | ADMIN, FACTORY, ACCOUNTING | List bulk (semi-finished) batches for a finished good |
| `/api/v1/factory/bulk-batches/{parentBatchId}/children` | GET | ADMIN, FACTORY, ACCOUNTING | List child FG batches from a parent bulk batch |

### Packaging Setup (Mappings)

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/factory/packaging-mappings` | GET | ADMIN, FACTORY | List all packaging size mappings |
| `/api/v1/factory/packaging-mappings/active` | GET | ADMIN, FACTORY | List active packaging size mappings |
| `/api/v1/factory/packaging-mappings` | POST | ADMIN only | Create packaging size mapping |
| `/api/v1/factory/packaging-mappings/{id}` | PUT | ADMIN only | Update packaging size mapping |
| `/api/v1/factory/packaging-mappings/{id}` | DELETE | ADMIN only | Deactivate packaging size mapping |

---

## 3. Manufacturing Lifecycle

The manufacturing lifecycle has four phases: planning, production logging (material mixing), packing, and optional wastage closure.

### 3.1 Production Log Status Progression

```
MIXED → READY_TO_PACK → PARTIAL_PACKED → FULLY_PACKED
```

On creation, the production log status is set to `READY_TO_PACK`. Each packing operation increments the packed quantity. When packed quantity equals or exceeds mixed quantity, status moves to `FULLY_PACKED`. When `closeResidualWastage` is requested, any remaining quantity is consumed as wastage and status moves to `FULLY_PACKED`.

### 3.2 Phase 1: Production Plan (Planning Artifact)

Production plans are organizational artifacts. They carry a plan number, product name, quantity, planned date, status (`PLANNED` → `COMPLETED`), and notes. **Plans do not trigger material consumption, journal entries, or inventory changes.** Plan status transitions are manual. Plans use `insertIfAbsent` by plan number for idempotency.

### 3.3 Phase 2: Production Log Creation (Material Mixing)

`POST /api/v1/factory/production/logs` is the primary execution entrypoint. It accepts `ProductionLogRequest` with brandId, productId, batchSize, mixedQuantity, materials list, laborCost, overheadCost, and optional salesOrderId.

**When a production log is created, the following happens atomically in a single transaction:**

1. **Code generation** — auto-generates `productionCode` (e.g., `PROD-20260328-001`)
2. **Validation** — brand belongs to company, product belongs to brand
3. **Status** — set to `READY_TO_PACK`
4. **Wastage initialization** — `wastageQuantity = mixedQuantity` (everything is "wasted" until packed)
5. **Material issue** — for each material in the request:
   - Pessimistic-locks the raw material
   - Checks sufficient stock
   - Consumes from batches using FIFO order (or WAC if configured)
   - Uses atomic deduction (`deductQuantityIfSufficient`) to prevent race conditions
   - Creates `RawMaterialMovement` records (type: ISSUE, reference: PRODUCTION_LOG)
   - Creates `ProductionLogMaterial` records
6. **Cost calculation** — `materialCostTotal` = sum of material costs; `unitCost` = (material + labor + overhead) / mixedQuantity
7. **Semi-finished batch registration** — auto-creates a `RawMaterial` with SKU `{productSku}-BULK` (materialType=PRODUCTION), creates a `RawMaterialBatch` with the production code as batch code, increments raw material stock, creates RECEIPT movement
8. **Accounting journals**:
   - **Material consumption:** DR WIP / CR raw material inventory accounts (reference: `{productionCode}-RM`)
   - **Labor/overhead:** DR WIP / CR labor applied / CR overhead applied (reference: `{productionCode}-LABOH`)
   - **Semi-finished receipt:** DR semi-finished inventory / CR WIP (reference: `{productionCode}-SEMIFG`)

### 3.4 Phase 3: Packing

`POST /api/v1/factory/packing-records` (requires `Idempotency-Key` header) accepts `PackingRequest` with productionLogId, packing lines, packedDate, packedBy, and closeResidualWastage flag.

**For each packing line:**

1. Validates production log is not FULLY_PACKED
2. Reserves idempotency key and checks hash for replay
3. Resolves allowed sellable sizes via product family (variantGroupId)
4. Resolves or auto-creates size variant
5. Calculates packed quantity from `quantityLiters` or `piecesCount × litersPerUnit`
6. Saves `PackingRecord`
7. **Consumes packaging materials** — looks up `PackagingSizeMapping` for the size, consumes from packaging material batches (FIFO), creates `RawMaterialMovement` (type: ISSUE), posts packaging consumption journal: DR WIP / CR packaging inventory (reference: `{productionCode}-PACK-{id}-PACKMAT`)
8. **Consumes semi-finished inventory** — deducts packed quantity from semi-finished batch, creates `RawMaterialMovement` (type: ISSUE)
9. **Registers finished-good batch** — creates `FinishedGoodBatch` with unit cost = semi-finished cost + packaging cost per unit, increments `FinishedGood.currentStock`, creates `InventoryMovement` (type: RECEIPT), posts FG receipt journal: DR FG valuation / CR semi-finished + CR WIP (packaging)
10. **Updates production log state** — atomically increments `totalPackedQuantity`, updates status (READY_TO_PACK / PARTIAL_PACKED / FULLY_PACKED), updates wastage

### 3.5 Phase 4: Wastage Closure

Triggered by `closeResidualWastage = true` in packing request:

1. Calculates residual wastage = `mixedQuantity - totalPackedQuantity`
2. Consumes semi-finished wastage from semi-finished batch (movement type: WASTAGE)
3. Posts wastage journal: DR wastage expense / CR WIP (reference: `{productionCode}-WASTE`)
4. Sets status to `FULLY_PACKED`
5. Sets wastage reason: `PROCESS_LOSS` (default) or `NONE`

---

## 4. Packaging Mappings

`PackagingSizeMapping` maps a packaging size label (e.g., "1L", "5L", "10L") to a raw material of type `PACKAGING`. When packing operations run, the system looks up the mapping to determine which packaging material to consume and how much.

Packaging mappings are managed at `/api/v1/factory/packaging-mappings` (ADMIN only for write operations). All factory users can read active mappings.

A packaging mapping is required before a product can be packed. Missing mappings are surfaced by the SKU readiness evaluation as `PACKAGING_MAPPING_MISSING` (see [catalog-setup.md](catalog-setup.md)).

---

## 5. Cost Allocation

### 5.1 Real-Time Cost Tracking

| Cost Component | When Captured | Where Stored |
| --- | --- | --- |
| Material cost | Production log creation | `ProductionLog.materialCostTotal` |
| Labor cost | Production log creation | `ProductionLog.laborCostTotal` |
| Overhead cost | Production log creation | `ProductionLog.overheadCostTotal` |
| Unit cost | Production log creation | `ProductionLog.unitCost` = (material + labor + overhead) / mixedQuantity |
| Packaging cost | Packing operation | `PackingRecord.packagingCost` |
| FG batch unit cost | Packing operation | semi-finished unit cost + packaging cost per unit |

### 5.2 Monthly Cost Variance Allocation

`POST /api/v1/factory/cost-allocation` distributes the difference between actual monthly labor/overhead costs and what was applied during production. The operator provides year, month, actual labor cost, actual overhead cost, and account IDs.

The service:
1. Finds all FULLY_PACKED production logs in the period
2. Calculates total liters produced
3. Computes labor and overhead variances
4. Distributes variances proportionally across batches by liters produced
5. Updates batch costs and posts variance journals (DR FG account / CR payroll expense + overhead expense)
6. Skips batches that already have variance journals for the period (idempotency)

**Caveat:** Cost allocation requires manual entry of actual costs. There is no automated integration with payroll or accounting systems.

---

## 6. Accounting Journal Map

| # | Trigger | Reference Pattern | Debit | Credit | Module Tag |
| --- | --- | --- | --- | --- | --- |
| 1 | Material consumption | `{productionCode}-RM` | WIP account | Raw material inventory accounts | `FACTORY_PRODUCTION` |
| 2 | Labor/Overhead applied | `{productionCode}-LABOH` | WIP account | Labor applied + Overhead applied | `FACTORY_PRODUCTION` |
| 3 | Semi-finished receipt | `{productionCode}-SEMIFG` | Semi-finished inventory | WIP account | `FACTORY_PRODUCTION` |
| 4 | Packaging consumption | `{productionCode}-PACK-{id}-PACKMAT` | WIP account | Packaging material inventory | `FACTORY_PACKING` |
| 5 | FG receipt | Packing reference | FG valuation | Semi-finished + WIP (packaging) | `FACTORY_PACKING` |
| 6 | Wastage | `{productionCode}-WASTE` | Wastage expense | WIP account | `FACTORY_PACKING` |
| 7 | Cost variance | `{productionCode}-{periodKey}` | FG account | Payroll + Overhead expense | `FACTORY_PRODUCTION` |

### Product Account Configuration (via ProductionProduct.metadata JSONB)

| Metadata Key | Purpose | Required For |
| --- | --- | --- |
| `wipAccountId` | WIP account for production costs | Production log creation |
| `laborAppliedAccountId` | Credit account for labor costs | Production log creation |
| `overheadAppliedAccountId` | Credit account for overhead costs | Production log creation |
| `semiFinishedAccountId` | Semi-finished inventory account (fallback: `fgValuationAccountId`) | Packing |
| `fgValuationAccountId` | Finished good valuation (asset) account | FG batch creation |
| `fgCogsAccountId` | COGS account for dispatch | Dispatch |
| `fgRevenueAccountId` | Revenue account for invoicing | Invoicing |
| `fgDiscountAccountId` | Discount contra-revenue account | Invoicing |
| `fgTaxAccountId` | Tax/GST output account | Invoicing |
| `wastageAccountId` | Wastage expense account | Wastage closure |

**Caveat:** Missing required accounts cause `ApplicationException` at runtime when the relevant factory operation is attempted. There is no startup validation or migration-time check.

---

## 7. Concurrency Control

| Mechanism | Where Used | Purpose |
| --- | --- | --- |
| **Pessimistic locking** on production logs | `PackingService` (`lockByCompanyAndId`) | Prevents concurrent packing of the same production log |
| **Pessimistic locking** on raw material batches | `ProductionLogService.issueFromBatches()` | Prevents concurrent material consumption from the same batch |
| **Atomic deduction** (`deductQuantityIfSufficient`) | `RawMaterialBatch` | Prevents overselling via concurrent double-consumption |
| **Atomic packed quantity increment** (`incrementPackedQuantityAtomic`) | `ProductionLog` | Prevents concurrent over-packing |
| **Company row-level lock** (`companyRepository.lockById`) | `ProductionLogService` | Serializes production log creation per company |

---

## 8. Idempotency Patterns

| Operation | Mechanism | Behavior |
| --- | --- | --- |
| **Packing** | `PackingRequestRecord` with key + hash + production log binding | Requires `Idempotency-Key` header. On matching key + hash replay, returns original result. On key mismatch (different payload), returns `CONCURRENCY_CONFLICT`. |
| **Production plans** | `insertIfAbsent` by plan number | Creates once; returns existing on duplicate plan number |
| **Factory tasks** | Payload equivalence (salesOrderId + title) | Skips duplicate tasks |
| **Cost allocation** | Skips batches with existing variance journals for the period | Prevents double-counting |
| **Production log creation** | **None** | No idempotency enforcement. Repeated calls create duplicate logs. |

**Important:** The `X-Idempotency-Key` and `X-Request-Id` headers are explicitly **rejected** for packing with `VALIDATION_UNSUPPORTED_HEADER`. Use the canonical `Idempotency-Key` header only.

---

## 9. Dispatch Handoff Boundary

### 9.1 Two-Layer Seam

Dispatch is a shared boundary where controller ownership and financial ownership differ:

| Layer | Owner | Location |
| --- | --- | --- |
| **Transport/controller** | `DispatchController` (inventory module) | `/api/v1/dispatch/**` |
| **Commercial/accounting** | `SalesDispatchReconciliationService` (sales module) | Service layer |

### 9.2 What Factory Creates vs What Sales Owns

| Step | Module | Service |
| --- | --- | --- |
| FG batch creation (packing) | factory | `FinishedGoodBatchRegistrar` |
| Stock increase (packing) | inventory | `FinishedGoodBatchRegistrar` (factory) → `FinishedGood` entity (inventory) |
| Reservation (on order creation) | inventory | `FinishedGoodsReservationEngine` |
| Packaging slip creation | inventory | `PackagingSlipService` |
| Dispatch confirmation | inventory/sales | `DispatchController` → `SalesDispatchReconciliationService` |
| Revenue/COGS posting | sales | `SalesDispatchReconciliationService` |
| Challan PDF generation | inventory | `DispatchController` |

Factory-role users can view pending slips and previews. Dispatch confirmation is accessible to factory users with transport metadata validation enforced. For full dispatch documentation, see [inventory.md](inventory.md).

### 9.3 FactorySlipEventListener

`FactorySlipEventListener` listens to `PackagingSlipEvent` (Spring ApplicationEvent) published when packaging slips change state. **Currently only logs the event.** No operational side effects are triggered. Designed as a placeholder for future queue/notification integration.

---

## 10. Terminology Warning: Packaging Slip vs Packing Record

| Term | Entity | Module | Purpose |
| --- | --- | --- | --- |
| **Packing record** | `PackingRecord` | factory | Records a factory packing operation: FG packed, size, quantity, packaging material consumed |
| **Packaging slip** | `PackagingSlip` | inventory | Dispatch document linking a sales order to FG batches reserved for shipment |

Despite similar names, these are separate entities in separate modules with separate lifecycles. Factory packing records are created during the packing step. Packaging slips are created during the sales/inventory reservation step and progress through dispatch and invoicing.

---

## 11. Key DTO Families

| DTO Family | Key DTOs | Purpose |
| --- | --- | --- |
| Production plan | `ProductionPlanRequest`, `ProductionPlanDto` | Plan CRUD |
| Production log | `ProductionLogRequest`, `ProductionLogDto`, `ProductionLogDetailDto`, `ProductionLogMaterialDto`, `ProductionLogPackingRecordDto` | Log creation and read |
| Packing | `PackingRequest`, `PackingLineRequest` | Packing operations |
| Packaging mapping | `PackagingSizeMappingRequest`, `PackagingSizeMappingDto` | Mapping CRUD |
| Cost allocation | `CostAllocationRequest`, `CostAllocationResponse`, `CostBreakdownDto`, `CostComponentTraceDto` | Monthly variance |
| Wastage report | `WastageReportDto` | Wastage tracking |
| Factory task | `FactoryTaskRequest`, `FactoryTaskDto` | Task tracking |
| Factory dashboard | `FactoryDashboardDto` | Aggregate dashboard stats |
| Bulk batch | `UnpackedBatchDto`, `BulkPackResponse`, `PackedBatchTraceDto` | Semi-finished batch queries |
| Size variant | `AllowedSellableSizeDto` | Allowed size resolution |
| Raw material trace | `RawMaterialTraceDto` | Material consumption trace |

---

## 12. Cross-Module Boundaries

| Boundary | Direction | Nature |
| --- | --- | --- |
| **Factory → Inventory** | Outbound | Packing creates `FinishedGoodBatch` and `InventoryMovement`; consumes `RawMaterialBatch` and creates `RawMaterialMovement`; production log creates semi-finished `RawMaterial` and `RawMaterialBatch` |
| **Factory → Accounting** | Outbound (facade) | Production log and packing post journals via `AccountingFacade`; cost allocation posts variance journals |
| **Factory → Production** | Read | `PackingAllowedSizeService` reads `ProductionProduct` and `ProductionBrand` for size resolution; production log reads catalog for SKU resolution |
| **Factory → Sales** | Read-only | Production log optionally links to `SalesOrder`; dispatch is a two-layer seam (see inventory module) |
| **Inventory → Factory** | Inbound (event) | `FactorySlipEventListener` listens to `PackagingSlipEvent` (currently no-op) |
| **Production → Factory** | Read | `SkuReadinessService` reads `PackagingSizeMappingRepository` for packing readiness |

---

## 13. Role-Based Access Control

| Role | Factory Access | Packing Access | Packaging Mapping Access | Dispatch Read | Cost Allocation |
| --- | --- | --- | --- | --- | --- |
| `ROLE_ADMIN` | Full | Full | Full (CRUD) | Full | Full |
| `ROLE_FACTORY` | Full | Full | Read-only | Full (with transport metadata) | Full |
| `ROLE_ACCOUNTING` | — | Full | — | Full | — |
| `ROLE_SALES` | — | — | — | Full | — |

---

## 14. Deprecated and Non-Canonical Surfaces

### 14.1 Explicitly Rejected Legacy Headers

`PackingController` explicitly rejects the legacy idempotency headers:
- `X-Idempotency-Key` → `VALIDATION_INVALID_INPUT` error with detail `canonicalHeader=Idempotency-Key`
- `X-Request-Id` → `VALIDATION_INVALID_INPUT` error with detail `canonicalHeader=Idempotency-Key`

These are **not** compatibility aliases — they are explicitly rejected to prevent silent migration from deprecated patterns. Use `Idempotency-Key` (the canonical header) for all packing requests.

### 14.2 ProductionBatch Entity (Unused)

The `ProductionBatch` entity exists in the factory domain but is **not actively used** in current controllers or service flows. Production execution happens through `ProductionLog`, not `ProductionBatch`. This entity may represent planned functionality that was superseded or deferred. **No replacement** — the canonical execution path is `ProductionPlan` (planning) → `ProductionLog` (execution).

### 14.3 FactorySlipEventListener (No-Op)

`FactorySlipEventListener` listens to `PackagingSlipEvent` but **currently only logs the event**. No operational side effects are triggered. Operators should not expect any automated factory response to dispatch events. **No replacement** — this is a placeholder for future extension.

### 14.4 No Approval Workflow for Factory Operations

There are **no** dedicated approval workflows in the factory/production module. Production and packing operations execute directly by users with `ROLE_FACTORY` or `ROLE_ADMIN` without an approval gate. This differs from commercial flows (sales orders, dispatch) where approvals and credit checks exist.

### 14.5 Production Plan Status Is Manual

Production plan status transitions (`PLANNED` → `COMPLETED`) are **manually triggered** via `PATCH /api/v1/factory/production-plans/{id}/status`. There is no automated status progression, no workflow guard, and no validation that the production has actually occurred before marking a plan as completed.

---

## 15. Critical Replay and Config Caveats

Operators and maintainers must understand these safety characteristics, which depend on config or partial protection rather than hard guarantees.

### 15.1 Packing Idempotency Record May Block on Failure

Packing idempotency records are reserved **before** the packing operation executes. If packing fails after reservation (e.g., material consumption fails, journal posting fails), the idempotency record remains in the database. A subsequent retry with the same `Idempotency-Key` and different payload will receive `CONCURRENCY_CONFLICT`. Operators must use a new idempotency key or resolve the orphaned record. **There is no automated cleanup for orphaned idempotency records.**

### 15.2 Production Log Creation Has No Idempotency Enforcement

`POST /api/v1/factory/production/logs` does **not** enforce an idempotency key. Repeated identical calls will create duplicate production logs with duplicate material consumption, duplicate journal entries, and duplicate semi-finished batch creation — all in the same transaction. Operators must implement client-side deduplication for production log creation.

### 15.3 Product Metadata Account IDs Are Runtime-Required, Not Schema-Enforced

Required account IDs (e.g., `wipAccountId`, `laborAppliedAccountId`, `fgValuationAccountId`) are enforced at runtime when the relevant factory operation is attempted, not at catalog setup time or via schema constraints. Missing accounts cause `ApplicationException`. **There is no startup validation or admin tool** to discover missing accounts before operations fail.

### 15.4 Wastage Closure Is Optional, Not Fail-Closed

The `closeResidualWastage` flag on the packing request defaults to `false`. When `false`, the production log remains at `PARTIAL_PACKED` or `READY_TO_PACK` even if no more packing is expected. Operators **must explicitly close wastage** to finalize a production log. **There is no automatic timeout, no scheduled job, and no background process** that closes wastage.

### 15.5 Semi-Finished Inventory Modeled as Raw Material

The semi-finished intermediary is modeled as a `RawMaterial` with SKU suffix `-BULK` and `materialType=PRODUCTION`. This is a design choice, not a separate entity type. Semi-finished goods appear in the raw material stock summary alongside actual raw materials, which can be confusing when querying inventory.

### 15.6 Packaging Mapping and Size Variant Auto-Creation

`PackagingMaterialService` auto-creates `PackagingSizeMapping` entries if a mapping is missing during packing. `PackingLineResolver` auto-creates `SizeVariant` entries if missing. These are config-dependent behaviors, not explicit setup guarantees. The system silently compensates for missing configuration during packing operations. Operators should verify packaging mappings and size variants exist before packing to avoid unexpected auto-creation.

### 15.7 Cost Allocation Is Manual

Monthly cost variance allocation requires manual entry of actual labor/overhead costs. There is no automated integration with payroll or accounting systems. Operators must periodically run cost allocation as part of their period-end close routine.

### 15.8 Production Log Creation Is a Single Transaction

Material consumption, journal posting, and semi-finished batch creation all happen in one transaction. If the accounting post fails, the entire production log creation rolls back and all stock effects are reverted. This provides strong consistency but means a failure leaves no partially committed data.

### 15.9 Packing Operations Are Not Fully Transactional Across Lines

Each packing request can create multiple FG batches and post multiple journals. A failure in one line does not automatically roll back previous lines. The entire request fails if the idempotency reservation fails, but partial completion can occur within a multi-line packing request.

---

## 16. Known Limitations

- **No location/warehouse tracking** — production and inventory are tracked per product/batch, not by physical location or bin/warehouse
- **No serial number tracking** — batch-level traceability is available; individual serial numbers are not supported
- **No physical count workflow** — adjustments exist, but there is no dedicated physical count or cycle-count flow
- **No production scheduling** — production plans have a `plannedDate` but no scheduling or resource allocation logic
- **No approval gates for factory operations** — production and packing execute directly without approval
- **No automated wastage closure** — operators must explicitly close wastage to finalize production logs
- **No cost allocation automation** — requires manual actual cost entry each period
- **Wastage reporting is embedded** — no dedicated factory wastage report endpoint; accessible through the general report module

---

## Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — module inventory (factory entry)
- [docs/modules/catalog-setup.md](catalog-setup.md) — catalog and setup readiness (production module)
- [docs/modules/inventory.md](inventory.md) — stock truth, batches, dispatch execution
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory (Manufacturing/Packing flow)
- [docs/flows/manufacturing-packing.md](../flows/manufacturing-packing.md) — canonical manufacturing/packing flow (behavioral entrypoint)
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/CONVENTIONS.md](../CONVENTIONS.md) — documentation conventions
