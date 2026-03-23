# Manufacturing / inventory

## Scope and evidence

This review covers dual product/catalog creation paths, raw-material and finished-good master data, opening stock and adjustment flows, production-log costing, packaging mappings, packing and bulk packing, finished-good reservation/dispatch/COGS linkage, valuation reporting, and the purchasing/accounting boundary that turns physical inventory events into AP or COGS truth.

Primary evidence:

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/controller/{CatalogController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingCatalogController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/{RawMaterialController,OpeningStockImportController,InventoryAdjustmentController,FinishedGoodController,InventoryBatchController,DispatchController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/controller/{FactoryController,ProductionLogController,PackingController,PackagingMappingController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/controller/ReportController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/controller/{PurchasingWorkflowController,RawMaterialPurchaseController}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/{CatalogService,ProductionCatalogService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/{RawMaterialService,OpeningStockImportService,InventoryAdjustmentService,FinishedGoodsWorkflowEngineService,FinishedGoodsReservationEngine,FinishedGoodsDispatchEngine,InventoryBatchTraceabilityService,InventoryMovementRecorder,InventoryValuationService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/{ProductionLogService,PackagingMaterialService,PackingService,PackingInventoryService,PackingBatchService,PackingCompletionService,BulkPackingService,CostAllocationService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/{GoodsReceiptService,PurchaseInvoiceEngine}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/{event/InventoryAccountingEventListener.java,internal/AccountingFacadeCore.java}`
- `erp-domain/src/main/resources/db/migration_v2/{V3__sales_invoice.sql,V4__inventory_production.sql,V33__payroll_payment_date_and_production_wastage_reason.sql,V35__performance_hotspot_indexes.sql,V38__size_variants_and_packing_traceability.sql}`
- `erp-domain/src/main/resources/{application.yml,application-prod.yml}`
- `openapi.json`
- Tests: `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/{ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,InventoryAccountingEventListenerIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/{CR_OpeningStockImportIdempotencyIT,CR_ManufacturingWipCostingTest,CR_InventoryGlAutomationProdOffIT,CR_FinishedGoodBatchProdGatingIT}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/manufacturing/{TS_PackingIdempotencyAndFacadeBoundaryTest,TS_BulkPackDeterministicReferenceTest}.java`, `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/inventory/TS_InventoryCogsLinkageScanContractTest.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/{production/FactoryPackagingCostingIT,production/CostAllocationVariancePolicyIT,inventory/InventoryGlReconciliationIT}.java`

Supporting runtime evidence was degraded in this session: `curl -i -s http://localhost:8081/actuator/health` failed with exit code `7`, so this review relies on static inspection plus existing regression/e2e/truth-suite coverage. Baseline suite `mvn test -Pgate-fast -Djacoco.skip=true` passed before drafting.

## Executable remediation handoff

This review feeds:

- [Lane 03 exec spec](../executable-specs/03-lane-accounting-truth-boundary/EXEC-SPEC.md)
- [Lane 05 exec spec](../executable-specs/05-lane-catalog-manufacturing/EXEC-SPEC.md)

Planning notes:

- Lane 03 opens with the prove-first boundary note in [`../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md`](../executable-specs/03-lane-accounting-truth-boundary/00-lane03-boundary-decision-note.md); Packet 0 may map manufacturing, packing, dispatch, valuation, and listener surfaces as downstream consumers of accounting truth, but it must not turn this lane-opening slice into catalog/manufacturing runtime cleanup.
- The downstream consumers called out for Packet 0 are `ProductionLogService`, `PackingService`, `PackingCompletionService`, `BulkPackingService`, `FinishedGoodsDispatchEngine`, and `InventoryValuationService`, all of which must inherit the chosen sales/purchase truth boundary instead of inventing a competing one.
- `MFG-09` is a real execution blocker for stock-bearing create flows and should be closed before broad catalog authority cleanup.
- Do not collapse packaging-workbench convergence into the same slice that fixes product, raw-material, or default-account authority paths.

## Entrypoints

| Surface | Entrypoints | Controller | Notes |
| --- | --- | --- | --- |
| Generic catalog CRUD | `POST/GET/PUT/DELETE /api/v1/catalog/brands`, `POST/GET/PUT/DELETE /api/v1/catalog/products`, `POST /api/v1/catalog/products/bulk` | `CatalogController` | Commercial/product-admin surface that manages `ProductionBrand` and `ProductionProduct`, but does not enforce inventory/accounting linkage. |
| Accounting-aware catalog | `POST /api/v1/accounting/catalog/import`, `GET/POST /api/v1/accounting/catalog/products`, `PUT /api/v1/accounting/catalog/products/{id}`, `POST /api/v1/accounting/catalog/products/bulk-variants` | `AccountingCatalogController` | Canonical manufacturing-aware product create/import path; auto-syncs stock-bearing rows and validates account metadata. |
| Raw-material ops | `GET/POST/PUT/DELETE /api/v1/catalog/products`, `GET /api/v1/raw-materials/stock{,/inventory,/low-stock}`, `GET/retired raw-material batch endpoint`, `retired raw-material intake endpoint`, `POST /api/v1/inventory/raw-materials/adjustments` | `RawMaterialController` | Raw-material master data, escape-hatch intake, batch creation, stock views, and raw-material adjustments. |
| Opening stock | `POST/GET /api/v1/inventory/opening-stock` | `OpeningStockImportController` | Multipart CSV import with replay protection and journal linkage. |
| Finished-goods ops | `GET/POST/PUT /api/v1/finished-goods`, `GET/POST /api/v1/finished-goods/{id}/batches`, `GET /api/v1/finished-goods/stock-summary`, `GET/PUT /api/v1/finished-goods/{id}/low-stock-threshold`, `GET /api/v1/finished-goods/low-stock` | `FinishedGoodController` | Finished-good CRUD, optional manual batch registration, and stock threshold views. |
| Finished-good adjustments and traceability | `GET/POST /api/v1/inventory/adjustments`, `GET /api/v1/inventory/batches/{id}/movements` | `InventoryAdjustmentController`, `InventoryBatchController` | Stock corrections plus batch-level movement/journal traceability. |
| Production and factory costing | `POST/GET /api/v1/factory/production/logs`, `GET /api/v1/factory/production/logs/{id}`, `POST /api/v1/factory/cost-allocation` | `ProductionLogController`, `FactoryController` | Production-log creation is where raw material becomes WIP truth; month-end variance allocation lives on the same factory surface. |
| Packaging mappings and packing | `GET/POST/PUT/DELETE /api/v1/factory/packaging-mappings`, `POST /api/v1/factory/packing-records`, `POST /api/v1/factory/packing-records/{productionLogId}/complete`, `POST /api/v1/factory/pack`, `GET /api/v1/factory/{unpacked-batches,production-logs/{productionLogId}/packing-history,bulk-batches/{finishedGoodId},bulk-batches/{parentBatchId}/children}` | `PackagingMappingController`, `PackingController` | Packaging BOM maintenance, standard packing, completion/wastage, and bulk-to-size packaging. |
| Reservation and dispatch boundary | `GET /api/v1/dispatch/{pending,preview/{slipId},slip/{slipId},order/{orderId}}`, `POST /api/v1/dispatch/confirm`, `POST /api/v1/dispatch/backorder/{slipId}/cancel` | `DispatchController` | Shared inventory/sales surface that turns reserved stock into shipped stock and COGS. |
| Purchasing receipt / invoice boundary | `GET/POST /api/v1/purchasing/goods-receipts`, `GET/POST /api/v1/purchasing/raw-material-purchases`, `POST /api/v1/purchasing/raw-material-purchases/returns` | `PurchasingWorkflowController`, `RawMaterialPurchaseController` | Canonical supplier receipt and AP posting boundary for raw materials. |
| Valuation, reconciliation, and manual finance overrides | `GET /api/v1/reports/{inventory-valuation,inventory-reconciliation,wastage,production-logs/{id}/cost-breakdown,monthly-production-costs}`, `POST /api/v1/accounting/inventory/{landed-cost,revaluation,wip-adjustment}` | `ReportController`, `AccountingController` | Reporting snapshots plus manual accounting-only valuation/revaluation hooks. |

The local `openapi.json` snapshot publishes the major catalog, raw-material, finished-good, inventory-adjustment, report, and accounting endpoints above.

## Data path and schema touchpoints

| Store / contract | Evidence | Used by |
| --- | --- | --- |
| `production_brands`, `production_products`, `size_variants` | `CatalogService`, `ProductionCatalogService`, `V4__inventory_production.sql`, `V38__size_variants_and_packing_traceability.sql` | Commercial catalog, manufacturing product identity, size/carton metadata, and catalog-import replay targets. |
| `raw_materials`, `raw_material_batches`, `raw_material_movements` | `RawMaterialService`, `GoodsReceiptService`, `ProductionLogService`, `PackagingMaterialService`, `V4__inventory_production.sql`, `V35__performance_hotspot_indexes.sql` | Supplier receipts, manual intake, opening stock, production consumption, packaging consumption, and raw-material traceability. |
| `finished_goods`, `finished_good_batches`, `inventory_movements` | `ProductionCatalogService.ensureCatalogFinishedGood(...)`, `FinishedGoodsWorkflowEngineService`, `PackingBatchService`, `FinishedGoodsDispatchEngine`, `V4__inventory_production.sql` | Finished-good identity, semi-finished bulk batches, packing receipts, dispatch relief, and COGS linkage. |
| `inventory_adjustments`, `inventory_adjustment_lines`, raw-material adjustment rows | `InventoryAdjustmentService`, `RawMaterialService.adjustStock(...)`, `V4__inventory_production.sql` | Replay-safe stock corrections and linked journal anchors. |
| `opening_stock_imports` | `OpeningStockImportService`, `V4__inventory_production.sql` | CSV hash/idempotency-key replay anchor, import status, counts, and import-level journal linkage. |
| `packaging_size_mappings`, `packing_records` | `PackagingMaterialService`, `PackingService`, `BulkPackingService`, `V4__inventory_production.sql`, `V38__size_variants_and_packing_traceability.sql` | Packaging BOM identity, packing execution, finished-good batch linkage, and size-specific traceability. |
| `production_logs`, `production_log_materials` | `ProductionLogService`, `CostAllocationService`, `V4__inventory_production.sql`, `V33__payroll_payment_date_and_production_wastage_reason.sql` | Raw-material issue detail, WIP/labor/overhead accumulation, wastage reason, and month-end variance allocation. |
| `inventory_reservations`, `packaging_slips`, `packaging_slip_lines` | `FinishedGoodsReservationEngine`, `FinishedGoodsDispatchEngine`, `SalesCoreEngine`, `V3__sales_invoice.sql`, `V4__inventory_production.sql` | Sales-order reservation, backorder control, dispatch replay anchors, and movement-to-COGS linkage. |
| Inventory/accounting config | `application.yml`, `application-prod.yml`, `InventoryAccountingEventListener`, `FinishedGoodsWorkflowEngineService`, `RawMaterialService` | Guards risky automatic inventory->GL posting, manual raw-material intake, and manual finished-good batch creation. |
| API contract snapshot | `openapi.json` | Confirms that the reviewed manufacturing/inventory surfaces are client-visible and contract-relevant. |

## Service chain

### 1. Dual product surfaces: one is generic, one is manufacturing-canonical

The repo exposes two product-creation paths with materially different guarantees.

#### Generic catalog CRUD (`CatalogService`)

- `CatalogService.createProduct(...)` and `updateProduct(...)` create/update `ProductionProduct` plus `size_variants` only.
- SKU generation is brand/name driven; default category is `FINISHED_GOOD`.
- This path does **not** call `ensureCatalogFinishedGood(...)`, does **not** sync `RawMaterial`, and does **not** require finished-good accounting metadata.

Operationally, that makes `/api/v1/catalog/products` a catalog-admin surface, not a safe manufacturing-stock surface.

#### Accounting-aware catalog CRUD/import (`ProductionCatalogService`)

- `createProduct(...)` / `updateProduct(...)` normalize metadata, require valid company-scoped accounts, and for non-raw-material categories call `ensureFinishedGoodAccounts(...)`.
- Required finished-good metadata is fail-closed: `fgValuationAccountId`, `fgCogsAccountId`, `fgRevenueAccountId`, and `fgTaxAccountId` must resolve either from payload metadata or company defaults.
- After saving `ProductionProduct`, the service immediately calls `ensureCatalogFinishedGood(...)` and `syncRawMaterial(...)`.
- Single-product create/update explicitly rejects color/size matrix input and directs callers to `/api/v1/accounting/catalog/products/bulk-variants`.

This is the canonical path for any SKU that will be mixed, packed, valued, reserved, or dispatched.

### 2. Catalog import and bulk-variant generation

`ProductionCatalogService.importCatalog(...)` is deliberately replay-safe and repair-friendly.

- Imports are CSV-only.
- Replay identity is the caller key if provided, else the file SHA-256 hash.
- `catalog_imports` stores `idempotency_key`, `idempotency_hash`, and `file_hash`.
- Each row is processed in its own transaction and retried once for retryable optimistic/data-integrity failures.
- Import rows can seed brands, create products, repair stale raw-material inventory accounts, and repair drifted finished-good metadata.
- `bulkCreateVariants(...)` generates deterministic `(color x size)` SKU candidates, supports dry-run, reports conflicts, and audits the create/no-op/conflict result.

`CR_CatalogImportConcurrencyIT`, `ProductionCatalogFinishedGoodInvariantIT`, and `ProductionCatalogRawMaterialInvariantIT` are the core evidence that import is both replay-safe and integrity-repairing rather than append-only.

### 3. Product-to-inventory linkage invariants

The main invariant is that stock-bearing SKUs should converge to a 1:1 linkage across `ProductionProduct`, `FinishedGood`, and, for raw-material categories, `RawMaterial`.

#### Finished goods

- `ensureCatalogFinishedGood(...)` locates or creates a `FinishedGood` by `(company, sku)`.
- It synchronizes name, unit, valuation account, COGS account, revenue account, tax account, discount account, and canonical costing-method aliasing.
- Concurrent create races are intentionally convergent: a unique-constraint failure falls back to reloading the row and applying drift repair.

#### Raw materials

- `syncRawMaterial(...)` runs only when `ProductionProduct.category` is raw-material-like.
- It creates/updates a `RawMaterial` by SKU, copies name/unit, resolves `inventoryAccountId` from metadata or company defaults, and marks packaging-like materials as `PACKAGING` when SKU/name/category carries packaging tokens.
- `RawMaterialService` then syncs the relationship in the opposite direction: `syncProductFromMaterial(...)` creates/updates a `ProductionProduct` with `category=RAW_MATERIAL` and metadata keys `linkedRawMaterialId` and `linkedRawMaterialSku`.

The healthy model is therefore bidirectional convergence, but only if callers stay on the canonical services.

### 4. Raw-material intake, receipts, and the purchasing boundary

`RawMaterialService` supports three different receipt styles with different governance strength.

#### Canonical path: purchasing goods receipt -> purchase invoice

- `GoodsReceiptService` writes the physical receipt and calls `RawMaterialService.recordReceipt(...)`.
- `recordReceipt(...)` creates the raw-material batch, creates the `RECEIPT` movement, optionally posts the inventory journal, and back-links `journalEntryId` onto the movement.
- `GoodsReceiptService` also publishes `InventoryMovementEvent` with **both** `sourceAccountId` (supplier payable) and `destinationAccountId` (inventory) populated.
- `PurchaseInvoiceEngine` later posts the AP journal and calls `linkGoodsReceiptMovementsToJournal(...)`, refusing to relink a receipt movement to a different journal.

That means the AP truth boundary is purchase invoicing, even though the raw-material movement already exists at receipt time.

#### Escape hatches: manual batch creation / intake

- `createBatch(...)` and `intake(...)` are guarded by `erp.raw-material.intake.enabled`.
- `application.yml` defaults this flag to `false`.
- Both manual paths require an idempotency key and persist a `RawMaterialIntakeRecord` with movement/journal references.

The code is intentionally telling operators that these are noncanonical paths: error messages point users back to `/api/v1/purchasing/raw-material-purchases` or supplier receipts.

### 5. Opening stock import

`OpeningStockImportService` is the sanctioned bootstrap path for day-zero balances.

- `POST /api/v1/inventory/opening-stock` accepts multipart CSV.
- Replay identity is `(company, idempotency_key)` with file-hash signature validation.
- Each row can resolve-or-create a raw material or finished good.
- Raw-material rows create `RawMaterialBatch` + `RawMaterialMovement` with `referenceType=OPENING_STOCK` and `movementType=RECEIPT`.
- Finished-good rows create `FinishedGoodBatch` + `InventoryMovement` with the same opening-stock reference semantics.
- Totals are aggregated by inventory account, then one inventory-adjustment journal is posted against an equity `OPEN-BAL` account, which the service creates if missing.
- The resulting `journalEntryId` is written back to the import row and to all created movement rows.

Important nuance: missing raw materials are created through `RawMaterialService.createRawMaterial(...)`, which then syncs back into `ProductionProduct`, but missing finished goods are created through `FinishedGoodsService.createFinishedGood(...)`, which does **not** create a matching `ProductionProduct`. Opening stock can therefore create an FG row that is inventory-visible but not catalog/production-visible.

### 6. Adjustments and batch traceability

#### Raw-material adjustments

- `RawMaterialService.adjustStock(...)` requires `idempotencyKey`, sorts lines, locks each raw material row, and persists a replay signature.
- Inventory decreases issue FIFO from available raw-material batches under lock; increases can create new stock.
- A single inventory-adjustment journal is posted and its `journalEntryId` is copied onto every raw-material movement.

#### Finished-good adjustments

- `InventoryAdjustmentService.createAdjustment(...)` also requires an idempotency key and locks all affected finished-good rows.
- Increase adjustments create explicit `ADJUSTMENT` batches.
- Decrease adjustments select allocatable batches by active costing method (`FIFO`, `LIFO`, or `WAC`) and write `ISSUE` movements with journal linkage.

#### Traceability

- `InventoryBatchTraceabilityService` supports both raw-material and finished-good batches.
- It returns movement rows with `movementType`, quantity, unit cost, reference type/id, `journalEntryId`, and `packingSlipId`.
- Source labels are inferred from `referenceType` and movement type, so purchase, production, packing, adjustment, return, and wastage histories stay legible.

### 7. Production logs are the raw-material -> WIP boundary

`ProductionLogService.createLog(...)` is the first place where manufacturing actually consumes stock and posts WIP.

- It locks the company row plus referenced brand/product.
- Each raw material in `ProductionLogRequest.materials` is locked individually.
- `issueFromBatches(...)` consumes raw-material batches in FIFO order with pessimistic locking to prevent double-consumption.
- Material issue journals debit `wipAccountId` and credit the consumed raw-material inventory accounts.
- Labor and overhead are applied through explicit metadata keys `laborAppliedAccountId` and `overheadAppliedAccountId`, both credited against WIP.
- The service ensures a semi-finished finished-good SKU `<product-sku>-BULK`, using `semiFinishedAccountId` if present and otherwise falling back to finished-good valuation.
- The mixed quantity is received into that semi-finished batch and a journal moves value from WIP into semi-finished inventory.

This entire chain fails closed when account metadata is incomplete. Missing `wipAccountId`, `semiFinishedAccountId`, `laborAppliedAccountId`, or `overheadAppliedAccountId` blocks production posting instead of guessing.

### 8. Packaging mappings, standard packing, and bulk packing

#### Packaging mappings

- `PackagingMaterialService` maps a packaging size (for example `1L` or `20L`) to one or more packaging raw materials.
- Each mapping requires a raw material that is itself classified as packaging.
- The schema enforces uniqueness per `(company, packaging_size, raw_material)`.

The important policy toggle is `erp.benchmark.require-packaging`:

- when `true`, packing fails if no packaging BOM exists for the requested size;
- when `false`, missing mappings are tolerated and the service returns zero packaging consumption/cost.

#### Standard packing (`PackingService`)

- Packing locks the production log.
- Optional caller idempotency is enforced through reserve-first semantics in `PackingIdempotencyService`.
- `PackingInventoryService` consumes semi-finished stock from the bulk batch created by production.
- `PackagingMaterialService.consumePackagingMaterial(...)` consumes packaging raw-material batches and totals packaging cost by inventory account.
- `PackingBatchService.registerFinishedGoodBatch(...)` creates the finished-good batch receipt and journal that debits FG valuation and credits the semi-finished/WIP side.
- `completePacking(...)` can post wastage through `PackingCompletionService`, which requires `wastageAccountId` metadata and credits WIP.

#### Bulk-to-size packing (`BulkPackingService`)

- The parent bulk batch is locked by id.
- `packReference` is deterministic from the bulk batch, pack lines, and idempotency key hash.
- Replay reads prior effects rather than double-posting.
- Child finished-good batches inherit bulk cost plus packaging allocations.
- Packaging consumption and child-batch valuation are posted once through the accounting facade.

`TS_PackingIdempotencyAndFacadeBoundaryTest` and `TS_BulkPackDeterministicReferenceTest` are the key replay-safety evidence here.

### 9. Finished goods, reservations, dispatch, and COGS

`FinishedGoodsWorkflowEngineService` is the operational façade over finished-good stock.

- CRUD works directly on `FinishedGood` rows.
- Manual batch registration is gated by `erp.inventory.finished-goods.batch.enabled` and is expected to stay off in production (`CR_FinishedGoodBatchProdGatingIT`).
- Stock summary and low-stock views derive available quantity as `currentStock - reservedStock`.

Reservation and dispatch are driven by sales-side packaging slips but remain inventory truth.

#### Reservation

- `FinishedGoodsReservationEngine.reserveForOrder(...)` locks finished-good rows.
- Batch selection honors the active costing method: allocatable FIFO, LIFO, or WAC order.
- Reservation decrements batch `quantityAvailable` and increments finished-good `reservedStock`.
- Backorders and reservation rebuilds are tied to `packaging_slips` and `inventory_reservations`.

#### Dispatch and COGS

- `FinishedGoodsDispatchEngine.confirmDispatch(...)` converts reserved quantity into `DISPATCH` inventory movements and updates slip/reservation status.
- `SalesCoreEngine.confirmDispatch(...)` computes cost from shipped packaging-slip lines, requires `valuationAccountId` and `cogsAccountId` on every finished good, and posts the COGS journal.
- `linkDispatchMovementsToJournal(...)` then back-links the dispatch `InventoryMovement` rows to the COGS journal entry.

So the finished-good inventory truth and the sales COGS truth are joined by packaging-slip id and journal id, not merely by product code.

### 10. Valuation, reconciliation, and manual accounting overrides

The codebase has two valuation layers.

#### Operational valuation (`modules.inventory.service.InventoryValuationService`)

- Maintains a five-minute WAC cache for finished goods.
- Invalidates cache on adjustments, packing receipts, dispatch, and other finished-good mutations.
- Chooses dispatch/adjustment batch order according to active costing method.

#### Reporting valuation (`modules.reports.service.InventoryValuationService`)

- Produces current and `asOf` snapshots for raw materials and finished goods.
- For historical valuation, it starts from current stock and reverses movements after the cutoff date rather than reading a materialized historical snapshot table.
- Enriches rows with brand/category via `ProductionProduct` lookup.

`ReportController` exposes inventory valuation, inventory reconciliation, wastage, production cost breakdown, and monthly production-cost summaries. `AccountingController` exposes manual landed-cost, inventory revaluation, and WIP-adjustment surfaces for finance-led overrides.

### 11. Why production disables inventory -> GL auto-posting

This is the key accounting-boundary finding.

- `InventoryAccountingEventListener` is active when `erp.inventory.accounting.events.enabled=true` and only auto-posts when the event includes both `sourceAccountId` and `destinationAccountId`.
- `application.yml` defaults the flag to `true`.
- `application-prod.yml` explicitly sets it to `false`.
- `GoodsReceiptService` publishes raw-material receipt events with payable and inventory accounts populated, so the listener would auto-post inventory/AP at **goods receipt time**.
- `PurchaseInvoiceEngine` later posts the purchase/AP journal and links the same goods-receipt movements to that journal.

That is why `CR_InventoryGlAutomationProdOffIT` exists: production correctness currently depends on **not** auto-posting inventory movements into GL for the purchasing path.

The manufacturing side is asymmetric:

- `InventoryMovementRecorder` publishes finished-good receipt/dispatch events,
- but those events do **not** populate source/destination accounts,
- so the listener intentionally skips them and the explicit production/packing/dispatch journals remain authoritative.

This asymmetry is workable, but fragile: raw-material receipts are listener-capable, finished-good production flows are explicit-journal-only.

## State machine and idempotency assumptions

### State assumptions

| Object | State assumptions | Evidence |
| --- | --- | --- |
| Stock-bearing product master | Any SKU intended for manufacturing or dispatch should flow through `ProductionCatalogService`, not generic `CatalogService`, so the `ProductionProduct` row converges to `FinishedGood` or `RawMaterial`. | `CatalogService`, `ProductionCatalogService`, product invariant tests |
| Raw material | SKU is unique per company and should map to exactly one `RawMaterial`; manual edits sync back into a `RAW_MATERIAL` production product with linkage metadata. | `V4__inventory_production.sql`, `RawMaterialService.syncProductFromMaterial(...)` |
| Finished good | `(company, product_code)` is unique and should be the same SKU used by production, reservation, and sales dispatch. | `V4__inventory_production.sql`, `ensureCatalogFinishedGood(...)`, `FinishedGoodsWorkflowEngineService` |
| Opening stock import | One company + one idempotency key/file signature should map to one import row and one opening-balance journal. | `opening_stock_imports` unique key, `OpeningStockImportService`, `CR_OpeningStockImportIdempotencyIT` |
| Production log | A production log assumes all required manufacturing account metadata already exists on the linked product. | `ProductionLogService.require*AccountId(...)`, `CR_ManufacturingWipCostingTest` |
| Packaging slip / reservation | Active primary/backorder slip uniqueness plus movement/journal links are the durable replay anchors for reservation and dispatch. | `V3__sales_invoice.sql`, `FinishedGoodsReservationEngine`, `FinishedGoodsDispatchEngine`, `TS_InventoryCogsLinkageScanContractTest` |
| Cost allocation | One batch/period pair should have at most one cost-variance (`CVAR`) journal. | `CostAllocationService`, `AccountingFacadeCore.findExistingCostVarianceReference(...)`, `CostAllocationVariancePolicyIT` |

### Idempotency assumptions

- **Accounting-aware catalog import:** explicit caller-visible idempotency via key-or-file-hash replay.
- **Bulk variants:** conflict-aware create with deterministic candidate generation, but not a separate replay table.
- **Raw-material manual intake / manual batch creation:** explicit idempotency key + persisted intake record.
- **Opening stock import:** explicit idempotency key + file-hash signature + unique company/key row.
- **Raw-material and finished-good adjustments:** explicit idempotency key + payload signature + durable adjustment row.
- **Packing:** optional caller-supplied idempotency key with reserve-first semantics.
- **Bulk packing:** deterministic pack reference + replay-by-prior-effects.
- **Cost allocation:** period+batch journal reference lookup acts as replay guard.
- **Production log create:** no equivalent public idempotency key was found, so duplicate client retries would depend on transaction boundaries and operator discipline rather than a first-class replay contract.
- **Dispatch confirm:** relies on packaging-slip and journal markers rather than a caller-visible idempotency key.

The overall model is uneven: adjustments/imports/packing are strongly replay-safe, but initial production-log creation and dispatch confirmation remain more inference-driven.

## Side effects, integrations, and recovery behavior

- Accounting-aware product create/import can create or repair `FinishedGood` and `RawMaterial` rows as side effects of catalog maintenance.
- Raw-material receipts, adjustments, and opening-stock imports create movement rows and attach journal ids after posting.
- Production logs create raw-material issue movements, WIP journals, and semi-finished bulk batches.
- Packing consumes semi-finished and packaging stock, creates finished-good receipt movements/batches, and can post wastage journals.
- Bulk packing creates child finished-good batches and packaging journals while preserving replay identity.
- Dispatch confirmation links `inventory_movements` to packaging slips and COGS journals.
- Cost allocation mutates production-log totals, recalculates finished-good batch unit costs, and posts cost-variance journals.
- Inventory reporting depends on both movement integrity and product/master-data linkage because report valuation enriches inventory rows through `ProductionProduct` lookups.

Recovery is strongest where explicit replay anchors exist: catalog import, opening stock import, adjustments, manual intake, packing, and bulk packing can all deterministically reject or reuse prior work. Recovery is weaker on generic catalog CRUD, production-log create, and dispatch confirm because those flows rely more on uniqueness/state markers than on a dedicated public idempotency contract.

## Risk hotspots

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| critical | accounting boundary / configuration assumption | Production safety currently depends on `erp.inventory.accounting.events.enabled=false` in prod. Goods receipts publish inventory events with payable and inventory accounts populated, while purchase invoicing later posts the AP journal and links the same receipt movements. | `GoodsReceiptService`, `PurchaseInvoiceEngine`, `InventoryAccountingEventListener`, `application-prod.yml`, `CR_InventoryGlAutomationProdOffIT` | Re-enabling the listener in production without redesign would likely double-post inventory/AP at receipt time and again at invoice time. |
| high | master-data integrity | `/api/v1/catalog/products` and `/api/v1/accounting/catalog/products` create `ProductionProduct` rows with very different guarantees. The generic catalog path does not provision `FinishedGood`, does not sync `RawMaterial`, and does not enforce finished-good account metadata. | `CatalogService`, `ProductionCatalogService`, `CatalogController`, `AccountingCatalogController` | A SKU created through the wrong endpoint can look valid in catalog/search UIs but fail later in manufacturing, valuation, reservation, or dispatch because the inventory side was never provisioned. |
| high | manufacturing posting dependency | Production, packing, and wastage logic depend on product metadata keys such as `wipAccountId`, `semiFinishedAccountId`, `laborAppliedAccountId`, `overheadAppliedAccountId`, and `wastageAccountId`. | `ProductionCatalogService`, `ProductionLogService`, `PackingCompletionService`, `CR_ManufacturingWipCostingTest` | Manufacturing posting is intentionally fail-closed; any metadata drift or incomplete product setup becomes a hard operational blocker at the exact moment stock should move. |
| high | replay gap | Production-log creation consumes raw-material stock and posts WIP journals but exposes no first-class public idempotency key comparable to imports, adjustments, or packing. | `ProductionLogController`, `ProductionLogService` | Client retries or operator resubmissions can duplicate raw-material consumption and WIP postings on the most expensive step in the flow. |
| medium | bootstrap / configuration drift | The canonical stock-bearing create paths currently fail in the seeded `MOCK` tenant because company default accounts are not configured: `POST /api/v1/accounting/catalog/products/bulk-variants` and `POST /api/v1/finished-goods` both return `VAL_007`. | live backend probes on `POST /api/v1/accounting/catalog/products/bulk-variants` and `POST /api/v1/finished-goods`, `ProductionCatalogService`, `FinishedGoodsWorkflowEngineService`, company default-account requirements in finance setup | Demo/QA environments can appear catalog-ready but still reject the manufacturing-safe creation paths that inventory, valuation, reservation, and dispatch depend on. |
| medium | bootstrap drift | Opening-stock import can create missing finished goods directly through `FinishedGoodsService.createFinishedGood(...)`, which does not create a matching `ProductionProduct`. | `OpeningStockImportService.resolveFinishedGood(...)`, `FinishedGoodsWorkflowEngineService.createFinishedGood(...)` | Day-zero stock can become inventory-visible but catalog/production-invisible, which later breaks valuation enrichment, production-product reporting, and catalog-based maintenance. |
| medium | costing completeness | Packaging BOM enforcement is optional. When `erp.benchmark.require-packaging=false`, packing with no packaging mapping returns zero packaging consumption/cost instead of failing. | `PackagingMaterialService.consumePackagingMaterial(...)` | Packing can succeed while silently under-costing finished goods and leaving packaging raw-material stock untouched. |
| medium | historical valuation model | Report-time `asOf` valuation is reconstructed by reversing later movements from current stock, not by reading a historical snapshot table. | `modules.reports.service.InventoryValuationService` | Any movement corruption, missing journal link, or manual data repair can distort historical valuation and reconciliation reports long after the operational event. |
| medium | noncanonical manual paths | Manual raw-material intake and manual finished-good batch creation exist as admin escape hatches behind feature flags. | `RawMaterialService`, `FinishedGoodsWorkflowEngineService`, `application.yml`, `CR_FinishedGoodBatchProdGatingIT` | These paths widen the drift surface because they bypass the stronger purchasing/production workflow invariants that normally create stock. |

## Security, privacy, protocol, performance, and observability notes

### Strengths

- Stock-bearing mutations use pessimistic locks on raw materials, finished goods, batches, production logs, or company rows where double-consumption would corrupt truth.
- Imports, adjustments, manual intake, packing, and bulk packing all have explicit replay or conflict semantics.
- Batch traceability includes `journalEntryId` and `packingSlipId`, which makes physical-to-financial investigation possible.
- Schema constraints backstop important invariants: SKU uniqueness, batch-code uniqueness, opening-stock idempotency, packaging-slip uniqueness, and packaging-mapping uniqueness.
- Production explicitly disables the risky inventory->GL auto-listener for the purchasing path.

### Hotspots

- The codebase intentionally exposes multiple ways to create stock-bearing master data; only one of them is manufacturing-safe.
- Runtime probing confirmed `retired raw-material intake endpoint` is intentionally blocked server-side with `BUS_004` and points callers back to `/api/v1/purchasing/raw-material-purchases`, so the purchasing receipt flow remains the canonical intake path even when the escape-hatch route is published.
- Production-log create is financially heavy but less replay-safe than neighboring operations.
- Packaging-cost completeness depends on configuration, not just data quality.
- Runtime verification was degraded in this session, so confidence comes mainly from code inspection plus tests rather than a live end-to-end walkthrough.

## Evidence notes

- `ProductionCatalogFinishedGoodInvariantIT` proves accounting-aware product create/update auto-provisions and re-syncs finished goods.
- `ProductionCatalogRawMaterialInvariantIT` proves import repairs raw-material inventory-account drift, validates account scope, and preserves costing aliases correctly across replay.
- `CR_OpeningStockImportIdempotencyIT` proves opening-stock import reuses the same result for the same key/file.
- `TS_PackingIdempotencyAndFacadeBoundaryTest` proves packing reserves idempotency before side effects and links inventory movements through the accounting facade.
- `TS_BulkPackDeterministicReferenceTest` proves bulk packing derives a deterministic reference and replays prior effects instead of double-posting.
- `CR_ManufacturingWipCostingTest` and `FactoryPackagingCostingIT` prove the intended RM -> WIP -> semi-finished -> FG receipt -> dispatch COGS chain.
- `TS_InventoryCogsLinkageScanContractTest` proves dispatch movements must carry packaging-slip and journal references.
- `InventoryGlReconciliationIT` proves receipt, shipment, and adjustment postings stay aligned with inventory-account balances.
