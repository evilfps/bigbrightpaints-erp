# Frontend Handoff — Operational Surfaces

> **⚠️ NON-CANONICAL / REFERENCE ONLY**
> 
> This document is **not** the canonical source for frontend contracts. The authoritative frontend documentation is now at:
> - **[docs/frontend-portals/README.md](../frontend-portals/README.md)** — portal ownership map
> - **[docs/frontend-api/README.md](../frontend-api/README.md)** — shared API contracts
> 
> This file is retained for reference only. If it disagrees with `docs/frontend-portals/` or `docs/frontend-api/`, the canonical docs win.

Last reviewed: 2026-03-30

This packet documents the frontend contract for **operational surfaces** — catalog/setup, inventory, and factory/manufacturing. It explains canonical hosts, payload families, RBAC assumptions, and read/write boundaries.

This packet defers to the canonical module and flow docs for implementation truth and is not a second source of truth.

---

## 1. Scope Overview

| Surface | Module | Canonical Doc |
| --- | --- | --- |
| Catalog and setup | `production` (CatalogController) | [docs/modules/catalog-setup.md](modules/catalog-setup.md) |
| Inventory management | `inventory` (FinishedGoodController, RawMaterialController, etc.) | [docs/modules/inventory.md](modules/inventory.md) |
| Factory/manufacturing | `factory` (FactoryController, PackingController) | [docs/modules/factory.md](modules/factory.md) |

---

## 2. Canonical Host Prefixes

All operational endpoints use the same host prefix:

```
/api/v1/
```

### 2.1 Catalog/Setup Routes

| Route | Method | Actor | Read/Write |
| --- | --- | --- | :---: |
| `/api/v1/catalog/brands` | GET | ADMIN, ACCOUNTING, SALES, FACTORY | Read |
| `/api/v1/catalog/brands` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/catalog/brands/{brandId}` | GET/PUT/DELETE | ADMIN, ACCOUNTING, SALES, FACTORY | Read/Write |
| `/api/v1/catalog/items` | GET | ADMIN, ACCOUNTING, SALES, FACTORY | Read |
| `/api/v1/catalog/items` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/catalog/items/{itemId}` | GET/PUT | ADMIN, ACCOUNTING | Read/Write |
| `/api/v1/catalog/items/{itemId}` | DELETE | ADMIN, ACCOUNTING, SALES, FACTORY | Write |
| `/api/v1/catalog/import` | POST | ADMIN, ACCOUNTING | Write |

### 2.2 Inventory Routes

| Route | Method | Actor | Read/Write |
| --- | --- | :---: | :---: |
| `/api/v1/finished-goods` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Read |
| `/api/v1/finished-goods/{id}` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Read |
| `/api/v1/finished-goods/{id}/batches` | GET | ADMIN, FACTORY, SALES | Read |
| `/api/v1/finished-goods/stock-summary` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Read |
| `/api/v1/finished-goods/low-stock` | GET | ADMIN, FACTORY, SALES | Read |
| `/api/v1/finished-goods/{id}/low-stock-threshold` | GET | ADMIN, FACTORY, SALES, ACCOUNTING | Read |
| `/api/v1/finished-goods/{id}/low-stock-threshold` | PUT | ADMIN, FACTORY, ACCOUNTING | Write |
| `/api/v1/raw-materials/stock` | GET | ADMIN, ACCOUNTING, FACTORY | Read |
| `/api/v1/raw-materials/stock/inventory` | GET | ADMIN, ACCOUNTING, FACTORY | Read |
| `/api/v1/raw-materials/stock/low-stock` | GET | ADMIN, ACCOUNTING, FACTORY | Read |
| `/api/v1/inventory/adjustments` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/inventory/adjustments` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/inventory/raw-materials/adjustments` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/inventory/batches/{id}/movements` | GET | ADMIN, FACTORY, ACCOUNTING, SALES | Read |
| `/api/v1/inventory/batches/expiring-soon` | GET | ADMIN, ACCOUNTING, FACTORY, SALES | Read |
| `/api/v1/inventory/opening-stock` | POST | ADMIN, ACCOUNTING, FACTORY | Write |
| `/api/v1/inventory/opening-stock` | GET | ADMIN, ACCOUNTING, FACTORY | Read |

### 2.3 Factory Routes

| Route | Method | Actor | Read/Write |
| --- | --- | :---: | :---: |
| `/api/v1/factory/production-plans` | GET | ADMIN, FACTORY | Read |
| `/api/v1/factory/production-plans` | POST | ADMIN, FACTORY | Write |
| `/api/v1/factory/production-plans/{id}` | PUT/DELETE | ADMIN, FACTORY | Write |
| `/api/v1/factory/tasks` | GET/POST | ADMIN, FACTORY | Read/Write |
| `/api/v1/factory/dashboard` | GET | ADMIN, FACTORY | Read |
| `/api/v1/factory/production/logs` | GET | ADMIN, FACTORY | Read |
| `/api/v1/factory/production/logs` | POST | ADMIN, FACTORY | Write |
| `/api/v1/factory/production/logs/{id}` | GET | ADMIN, FACTORY | Read |
| `/api/v1/factory/packing-records` | POST | ADMIN, FACTORY, ACCOUNTING | Write |
| `/api/v1/factory/unpacked-batches` | GET | ADMIN, FACTORY, ACCOUNTING | Read |
| `/api/v1/factory/packaging-mappings` | GET | ADMIN, FACTORY | Read |
| `/api/v1/factory/packaging-mappings` | POST | ADMIN only | Write |
| `/api/v1/factory/packaging-mappings/{id}` | PUT/DELETE | ADMIN only | Write |
| `/api/v1/factory/cost-allocation` | POST | ADMIN, FACTORY | Write |

---

## 3. RBAC Summary

### 3.1 Role Permissions by Surface

| Role | Catalog | Inventory (Read) | Inventory (Write) | Factory (Read) | Factory (Write) | Packaging Mapping |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| `ROLE_ADMIN` | Full | Full | Full | Full | Full | Full (CRUD) |
| `ROLE_ACCOUNTING` | Full (read) / Brand-item (write) | Full | Adjustments, opening stock, thresholds | Packing only | Packing only | — |
| `ROLE_SALES` | Read-only | Read (FG only) | — | — | — | — |
| `ROLE_FACTORY` | Read-only | Read | Opening stock | Full | Full | Read-only |
| `ROLE_DEALER` | — | — | — | — | — | — |

### 3.2 Key RBAC Boundaries

1. **Catalog write operations** — Restricted to `ROLE_ADMIN` and `ROLE_ACCOUNTING`. Read is wider (includes SALES, FACTORY).

2. **Inventory write operations** — Factory role can write opening stock and low-stock thresholds. Accounting can do adjustments.

3. **Factory packing** — Both FACTORY and ACCOUNTING roles can record packing (shared surface for cost tracking).

4. **Packaging mappings** — Admin-only for create/update/delete. Factory and accounting can read.

---

## 4. Payload Families

### 4.1 Catalog Payloads

**Brand payloads:**
- `CatalogBrandRequest` — `name` (required), `logoUrl`, `description`, `active`
- `CatalogBrandDto` — `id`, `publicId`, `name`, `code`, `logoUrl`, `description`, `active`
- `ProductionBrandDto` — Extends brand with `productCount`

**Item payloads:**
- `CatalogItemRequest` — `brandId`, `name`, `itemClass` (FINISHED_GOOD/RAW_MATERIAL/PACKAGING_RAW_MATERIAL), `unitOfMeasure`, `hsnCode`, `gstRate`, plus optional `color`, `size`, `basePrice`, `minDiscountPercent`, `minSellingPrice`, `metadata`, `active`
- `CatalogItemDto` — Full item with `id`, `publicId`, `brandId`, `brandName`, `code`, `itemClass`, stock enrichment (`CatalogItemStockDto`), readiness enrichment (`SkuReadinessDto`)

**Import payloads:**
- Multipart file upload (`file`) + `Idempotency-Key` header
- `CatalogImportResponse` — `rowsProcessed`, `brandsCreated`, `productsCreated`, `productsUpdated`, `rawMaterialsSeeded`, `errors[]`

### 4.2 Inventory Payloads

**Stock summary:**
- `StockSummaryDto` — `currentStock`, `reservedStock`, `availableStock`, `weightedAverageCost`
- Low-stock response includes threshold comparison

**Batch payloads:**
- `FinishedGoodBatchDto` — batch code, quantities, unit cost, manufactured date, expiry date, source
- `RawMaterialBatchDto` — batch code, quantity, cost, supplier, dates
- Batch traceability: `InventoryBatchMovementDto` per movement

**Adjustment payloads:**
- `InventoryAdjustmentRequest` — `type` (DAMAGED/SHRINKAGE/OBSOLETE/RECOUNT_UP), `adjustmentAccountId` (required), `adjustmentDate`, `reason`, `adminOverride`, `idempotencyKey` (required), `lines[]` (finishedGoodId, quantity, unitCost)
- `RawMaterialAdjustmentRequest` — `direction` (increase/decrease), `adjustmentAccountId`, `lines[]` (rawMaterialId, quantity, unitCost), `reason`, `Idempotency-Key`

**Opening stock:**
- `POST /api/v1/inventory/opening-stock` — Multipart CSV + `Idempotency-Key` header + `openingStockBatchKey` query param
- Enforces SKU readiness before creating batches

### 4.3 Factory Payloads

**Production log:**
- `ProductionLogRequest` — `brandId`, `productId`, `batchSize`, `mixedQuantity`, `materials[]` (rawMaterialId, quantity), `laborCost`, `overheadCost`, optional `salesOrderId`
- Response includes `productionCode`, status, material cost, unit cost

**Packing:**
- `PackingRequest` — `productionLogId`, `lines[]` (quantity, size), `packedDate`, `packedBy`, `closeResidualWastage`
- Requires `Idempotency-Key` header
- Response includes created FG batches, consumption records

**Packaging mapping:**
- `PackagingSizeMappingRequest` — `sizeLabel`, `packagingMaterialId`, `piecesPerPack`, `active`

---

## 5. Read/Write Boundaries

### 5.1 Catalog Setup

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| List/search brands | ADMIN, ACCOUNTING, SALES, FACTORY | — |
| Create brand | ADMIN, ACCOUNTING | — |
| Update brand | ADMIN, ACCOUNTING, SALES, FACTORY | All roles can update all brand fields (name, logoUrl, description, active) — no field-level restrictions |
| Deactivate brand | ADMIN, ACCOUNTING, SALES, FACTORY | Soft delete |
| List/search items | ADMIN, ACCOUNTING, SALES, FACTORY | Supports filters: `q`, `itemClass`, `includeStock`, `includeReadiness` |
| Create item | ADMIN, ACCOUNTING | — |
| Update item | ADMIN, ACCOUNTING | Immutable fields: brandId, itemClass, color, size, unitOfMeasure |
| Deactivate item | ADMIN, ACCOUNTING, SALES, FACTORY | Soft delete; does not cascade to inventory |
| Import catalog | ADMIN, ACCOUNTING | Requires `Idempotency-Key` + CSV file |

### 5.2 Inventory

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| List finished goods | ADMIN, FACTORY, SALES, ACCOUNTING | — |
| List batches | ADMIN, FACTORY, SALES | Excludes ACCOUNTING for batch list (security) |
| Stock summary | ADMIN, FACTORY, SALES, ACCOUNTING | — |
| Low stock items | ADMIN, FACTORY, SALES | Excludes ACCOUNTING |
| Low stock threshold read | ADMIN, FACTORY, SALES, ACCOUNTING | — |
| Low stock threshold write | ADMIN, FACTORY, ACCOUNTING | — |
| Raw material stock | ADMIN, ACCOUNTING, FACTORY | — |
| Adjustments (read) | ADMIN, ACCOUNTING | — |
| Adjustments (write) | ADMIN, ACCOUNTING | Requires idempotency key |
| Opening stock (write) | ADMIN, ACCOUNTING, FACTORY | Requires idempotency key + batch key |

### 5.3 Factory

| Action | Allowed Roles | Notes |
| --- | :---: | --- |
| Production plans (CRUD) | ADMIN, FACTORY | — |
| Factory tasks (CRUD) | ADMIN, FACTORY | — |
| Dashboard | ADMIN, FACTORY | — |
| Production logs (read) | ADMIN, FACTORY | Top 25 recent |
| Production logs (write) | ADMIN, FACTORY | No idempotency — beware of duplicates |
| Packing (write) | ADMIN, FACTORY, ACCOUNTING | Requires `Idempotency-Key` |
| Packing (read) | ADMIN, FACTORY, ACCOUNTING | — |
| Packaging mappings (read) | ADMIN, FACTORY | — |
| Packaging mappings (write) | ADMIN only | — |
| Cost allocation | ADMIN, FACTORY | Monthly variance distribution |

---

## 6. Host/Path Ownership Summary

| Surface | Host Family | Ownership |
| --- | :--- | :--- |
| Catalog | `/api/v1/catalog/**` | Production module |
| Finished goods | `/api/v1/finished-goods/**` | Inventory module |
| Raw materials | `/api/v1/raw-materials/**` | Inventory module |
| Inventory adjustments | `/api/v1/inventory/**` | Inventory module |
| Dispatch | `/api/v1/dispatch/**` | Inventory module (controller), Sales (commercial ownership) |
| Factory production | `/api/v1/factory/**` | Factory module |

---

## 7. Cross-Module Seams for Frontend Awareness

### 7.1 Catalog → Inventory Sync

- Creating a catalog item automatically creates a `FinishedGood` (for FINISHED_GOOD) or `RawMaterial` (for RAW_MATERIAL/PACKAGING_RAW_MATERIAL) mirror
- The inventory mirror is created synchronously within the same transaction
- Frontend should not assume "catalog created" means "stock available" — stock is a separate concern

### 7.2 Factory → Inventory Handoff

- Factory packing creates finished-good batches in the inventory module
- Semi-finished goods are modeled as raw materials with SKU suffix `-BULK`
- Packaging slip creation happens in inventory, not factory

### 7.3 Inventory → Accounting Events

- Stock movements (receipt, issue, adjustment) can trigger accounting events when `erp.inventory.accounting.events.enabled=true`
- Frontend should be aware that inventory mutations may have delayed accounting impact
- Feature flag controls whether inventory events post to GL

---

## 8. Known Safety Gaps for Frontend

| Gap | Description | Mitigation |
| --- | --- | --- |
| Production log creation lacks idempotency | Duplicate calls create duplicate logs with duplicate material consumption | Frontend must deduplicate at client side |
| Packing idempotency blocks on failure | If packing fails after idempotency reservation, retry with same key fails | Use new idempotency key or resolve orphaned record |
| Inventory accounting events fail silently | Listener runs AFTER_COMMIT; failures don't roll back inventory | Monitor logs for accounting event failures |
| WAC cache staleness | Weighted average cost cached for 5 minutes | Frontend may see stale costs during high-frequency operations |
| Accounting metadata filtered by role | Non-ACCOUNTING roles see simplified readiness view | Frontend must authenticate with appropriate role for full detail |

---

## 9. Deprecation Notes

| Surface | Status | Replacement |
| --- | :--- | :--- |
| Raw material CRUD endpoints (`/api/v1/raw-materials/**`) | Retired | Use `/api/v1/catalog/items` with `itemClass=RAW_MATERIAL` |
| Raw material intake endpoint (legacy) | Retired | Use `/api/v1/catalog/items` for product; GRN for intake |
| X-Idempotency-Key header for packing | Rejected | Use canonical `Idempotency-Key` header only |

---

## Cross-References

- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/modules/catalog-setup.md](modules/catalog-setup.md) — catalog/setup module doc
- [docs/modules/inventory.md](modules/inventory.md) — inventory module doc
- [docs/modules/factory.md](modules/factory.md) — factory module doc
- [docs/flows/catalog-setup-readiness.md](flows/catalog-setup-readiness.md) — catalog/setup flow
- [docs/flows/inventory-management.md](flows/inventory-management.md) — inventory flow
- [docs/flows/manufacturing-packing.md](flows/manufacturing-packing.md) — manufacturing/packing flow
- [docs/accounting-portal-frontend-engineer-handoff.md](accounting-portal-frontend-engineer-handoff.md) — accounting portal handoff (for comparison)
