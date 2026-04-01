# Catalog and Setup Readiness

Last reviewed: 2026-03-30

This packet documents the catalog, setup, and readiness surfaces in the `production` module. It covers brands, items, import, SKU readiness evaluation, packaging-material definitions, payload families, and setup prerequisites that affect downstream flows.

The catalog/setup surface owns **structural product truth** — what products exist, how they are classified, and whether they are ready for downstream operations. Execution truth (production logs, packing, dispatch) is documented separately in the factory/manufacturing and inventory packets.

---

## 1. Module Ownership

| Aspect | Owner |
| --- | --- |
| Package | `com.bigbrightpaints.erp.modules.production` |
| Controller | `CatalogController` at `/api/v1/catalog/**` |
| Primary services | `CatalogService`, `ProductionCatalogService`, `SkuReadinessService` |
| Domain entities | `ProductionBrand`, `ProductionProduct`, `CatalogImport` |
| Repositories | `ProductionBrandRepository`, `ProductionProductRepository`, `CatalogImportRepository` |
| Cross-module reads | `FinishedGoodRepository`, `RawMaterialRepository`, `FinishedGoodBatchRepository`, `PackagingSizeMappingRepository` (inventory/factory) |

The `production` module is the canonical owner of product catalog setup. It reads from `inventory` (finished goods, raw materials, batches) and `factory` (packaging-size mappings) to evaluate readiness, but it does **not** own inventory stock truth, batch creation, or dispatch execution.

---

## 2. Canonical Host and Route Map

All catalog/setup endpoints live under a single host prefix:

| Route | Method | Actor | Purpose |
| --- | --- | --- | --- |
| `/api/v1/catalog/brands` | POST | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Create a brand |
| `/api/v1/catalog/brands` | GET | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | List brands (optional `?active=true/false`) |
| `/api/v1/catalog/brands/{brandId}` | GET | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Get single brand |
| `/api/v1/catalog/brands/{brandId}` | PUT | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Update brand |
| `/api/v1/catalog/brands/{brandId}` | DELETE | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Deactivate brand (soft delete) |
| `/api/v1/catalog/items` | POST | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Create a catalog item |
| `/api/v1/catalog/items` | GET | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Search items (with optional `?q=`, `?itemClass=`, `?includeStock=`, `?includeReadiness=`, `?page=`, `?pageSize=`) |
| `/api/v1/catalog/items/{itemId}` | GET | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Get single item (with optional stock/readiness enrichment) |
| `/api/v1/catalog/items/{itemId}` | PUT | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Update item mutable fields |
| `/api/v1/catalog/items/{itemId}` | DELETE | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | Deactivate item (soft delete) |
| `/api/v1/catalog/import` | POST | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Import catalog via CSV file upload |

**Class-level guard:** All `CatalogController` endpoints require at least one of `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, or `ROLE_FACTORY`. Write operations (create brand, create/update item, import) are further restricted to `ROLE_ADMIN` and `ROLE_ACCOUNTING`.

**Accounting metadata visibility:** Item search and detail responses conditionally include accounting-related metadata fields (those ending in `AccountId`) only when the caller holds `ROLE_ADMIN` or `ROLE_ACCOUNTING`. Other roles see a sanitized readiness view that replaces individual account blockers with a single `ACCOUNTING_CONFIGURATION_REQUIRED` blocker.

---

## 3. Payload Families

### 3.1 Brand Payloads

**CatalogBrandRequest** (create/update brand):

| Field | Type | Notes |
| --- | --- | --- |
| `name` | String (required) | Must be unique per company (case-insensitive) |
| `logoUrl` | String (optional) | Brand logo URL |
| `description` | String (optional) | Brand description |
| `active` | Boolean (optional) | Defaults to `true` on creation |

**CatalogBrandDto** (brand response):

| Field | Type | Notes |
| --- | --- | --- |
| `id` | Long | Internal ID |
| `publicId` | UUID | External-facing ID |
| `name` | String | Brand name |
| `code` | String | Auto-generated brand code (uppercase alphanumeric, max 12 chars, unique per company) |
| `logoUrl` | String | Brand logo URL |
| `description` | String | Brand description |
| `active` | boolean | Whether brand is active |

**ProductionBrandDto** (extended brand listing with product count):

| Field | Type | Notes |
| --- | --- | --- |
| `id` | Long | Internal ID |
| `publicId` | UUID | External-facing ID |
| `name` | String | Brand name |
| `code` | String | Auto-generated brand code |
| `productCount` | long | Number of products under this brand |

### 3.2 Item Payloads

**CatalogItemRequest** (create/update item — external API DTO):

| Field | Type | Notes |
| --- | --- | --- |
| `brandId` | Long (required) | Owning brand |
| `name` | String (required) | Product name |
| `itemClass` | String (required) | `FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL` |
| `color` | String (optional) | Default colour/variant |
| `size` | String (optional) | Size label |
| `unitOfMeasure` | String (required) | Unit (e.g. `KG`, `LTR`, `UNIT`). `@NotBlank` enforced. |
| `hsnCode` | String (required) | HSN tax code. `@NotBlank` enforced. |
| `basePrice` | BigDecimal (optional) | Base selling price |
| `gstRate` | BigDecimal (required) | GST percentage. `@NotNull`, `@DecimalMin(0.00)`, `@DecimalMax(100.00)` enforced. |
| `minDiscountPercent` | BigDecimal (optional) | Minimum discount percentage |
| `minSellingPrice` | BigDecimal (optional) | Floor selling price |
| `metadata` | Map\<String, Object\> (optional) | JSONB metadata including account assignments |
| `active` | Boolean (optional) | Defaults to `true` on creation |

**CatalogItemDto** (item response — enriched with stock and readiness):

| Field | Type | Notes |
| --- | --- | --- |
| `id` | Long | Internal ID |
| `publicId` | UUID | External-facing ID |
| `rawMaterialId` | Long (nullable) | Linked raw material ID (for RM/PKG items) |
| `brandId` | Long | Owning brand |
| `brandName` | String | Brand name |
| `brandCode` | String | Brand code |
| `name` | String | Product display name |
| `code` | String | Auto-generated SKU code |
| `itemClass` | String | `FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL` |
| `color` | String (nullable) | Default colour |
| `size` | String (nullable) | Size label |
| `unitOfMeasure` | String (nullable) | Unit |
| `hsnCode` | String (nullable) | HSN code |
| `basePrice` | BigDecimal | Base price |
| `gstRate` | BigDecimal | GST rate |
| `minDiscountPercent` | BigDecimal | Min discount |
| `minSellingPrice` | BigDecimal | Floor price |
| `metadata` | Map\<String, Object\> | Filtered metadata (accounting fields hidden for non-accounting roles) |
| `active` | boolean | Whether product is active |
| `stock` | CatalogItemStockDto (nullable) | Enriched when `includeStock=true` |
| `readiness` | SkuReadinessDto (nullable) | Enriched when `includeReadiness=true` |

**CatalogItemStockDto** (stock enrichment):

| Field | Type | Notes |
| --- | --- | --- |
| `onHandQuantity` | BigDecimal | Total on-hand stock |
| `reservedQuantity` | BigDecimal | Reserved stock (FG only) |
| `availableQuantity` | BigDecimal | Available = onHand − reserved |
| `unitOfMeasure` | String | Stock unit |

### 3.3 Bulk Variant Payloads

**BulkVariantRequest** (generate multiple color×size variants in one call):

| Field | Type | Notes |
| --- | --- | --- |
| `brandId` | Long (optional) | Existing brand ID |
| `brandName` | String (optional) | Brand name (resolved or created) |
| `brandCode` | String (optional) | Brand code hint |
| `baseProductName` | String (required) | Base product family name |
| `category` | String (required) | Item class/category |
| `colors` | List\<String\> | Color tokens |
| `sizes` | List\<String\> | Size tokens |
| `colorSizeMatrix` | List\<ColorSizeMatrixEntry\> | Per-color size overrides |
| `unitOfMeasure` | String (optional) | Default unit |
| `skuPrefix` | String (optional) | SKU prefix override |
| `basePrice` / `gstRate` / `minDiscountPercent` / `minSellingPrice` | BigDecimal | Pricing defaults |
| `metadata` | Map\<String, Object\> | Shared metadata |

**BulkVariantResponse** (variant generation result):

| Field | Type | Notes |
| --- | --- | --- |
| `generated` | List\<VariantItem\> | All generated SKU plans |
| `conflicts` | List\<VariantItem\> | Conflicting SKUs (duplicate in request or already existing) |
| `wouldCreate` | List\<VariantItem\> | SKUs that would be created if committed |
| `created` | List\<VariantItem\> | Actually created SKUs |

Each `VariantItem` contains: `sku`, `reason`, `productName`, `color`, `size`. Reasons include `GENERATED`, `WOULD_CREATE`, `CREATED`, `SKU_ALREADY_EXISTS`, `DUPLICATE_IN_REQUEST`, and `CONCURRENT_SKU_CONFLICT`.

### 3.4 Import Payloads

**Import request:** multipart form with `file` (CSV) plus optional `Idempotency-Key` or `X-Idempotency-Key` header.

**CatalogImportResponse**:

| Field | Type | Notes |
| --- | --- | --- |
| `rowsProcessed` | int | Total rows processed |
| `brandsCreated` | int | Brands auto-created |
| `productsCreated` | int | New products created |
| `productsUpdated` | int | Existing products updated |
| `rawMaterialsSeeded` | int | Raw material inventory mirrors auto-seeded |
| `errors` | List\<ImportError\> | Per-row errors with row number and message |

---

## 4. Domain Entities

### 4.1 ProductionBrand

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | Long (PK) | Auto-generated | Internal ID |
| `public_id` | UUID | Not null | External-facing ID |
| `company_id` | Long (FK) | Not null | Tenant scoping |
| `name` | String | Not null, unique per company (case-insensitive) | Display name |
| `code` | String | Not null, unique per company (case-insensitive) | Auto-generated code |
| `logo_url` | String | Nullable | Brand logo |
| `description` | String | Nullable | Description |
| `is_active` | boolean | Not null, default `true` | Soft delete flag |
| `created_at` / `updated_at` | Instant | Not null | Timestamps |

### 4.2 ProductionProduct

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | Long (PK) | Auto-generated | Internal ID |
| `public_id` | UUID | Not null | External-facing ID |
| `company_id` | Long (FK) | Not null | Tenant scoping |
| `brand_id` | Long (FK) | Not null | Owning brand |
| `product_name` | String | Not null | Display name |
| `category` | String | Not null | `RAW_MATERIAL` / non-RM |
| `default_colour` | String | Nullable | Default colour |
| `size_label` | String | Nullable | Size label |
| `unit_of_measure` | String | Nullable | Unit |
| `sku_code` | String | Not null, unique per company | Canonical SKU |
| `variant_group_id` | UUID | Nullable | Groups color×size variants |
| `product_family_name` | String | Nullable | Family name for variant grouping |
| `hsn_code` | String | Nullable | Tax code |
| `base_price` | BigDecimal | Not null, default 0 | Base price |
| `gst_rate` | BigDecimal | Not null, default 0 | GST percentage |
| `min_discount_percent` | BigDecimal | Not null, default 0 | Min discount |
| `min_selling_price` | BigDecimal | Not null, default 0 | Floor price |
| `metadata` | JSONB | Nullable | Extensible metadata including account IDs |
| `is_active` | boolean | Not null, default `true` | Soft delete |
| `created_at` / `updated_at` | Instant | Not null | Timestamps |

**Unique constraints:**
- `(company_id, sku_code)` — SKU uniqueness per tenant
- `(brand_id, product_name)` — product name uniqueness per brand

**Variant collections** (stored in separate join tables):
- `colors` — `production_product_colors`
- `sizes` — `production_product_sizes`
- `cartonSizes` — `production_product_carton_sizes` (size → pieces per carton)

### 4.3 CatalogImport

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | Long (PK) | Auto-generated | Internal ID |
| `company_id` | Long (FK) | Not null | Tenant scoping |
| `idempotency_key` | String(128) | Not null, unique per company | Idempotency key |
| `idempotency_hash` | String(64) | Nullable | Content hash for idempotency |
| `file_hash` | String(64) | Nullable | SHA-256 of uploaded file |
| `file_name` | String(256) | Nullable | Original filename |
| `rows_processed` | int | Not null | Count |
| `brands_created` / `products_created` / `products_updated` / `raw_materials_seeded` | int | Not null | Outcome counters |
| `errors_json` | text | Nullable | JSON-serialized per-row errors |
| `created_at` | Instant | Not null | Timestamp |

---

## 5. SKU Readiness Evaluation

SKU readiness is the cross-cutting mechanism that tells downstream flows whether a given product SKU is safe to operate on. `SkuReadinessService` evaluates readiness across six stages:

### 5.1 Readiness Stages

| Stage | Purpose | Key Blockers |
| --- | --- | --- |
| **Catalog** | Product master exists and is active | `PRODUCT_MASTER_MISSING`, `PRODUCT_INACTIVE` |
| **Inventory** | Stock mirror (FG or RM) exists with required accounts | `FINISHED_GOOD_MIRROR_MISSING`, `RAW_MATERIAL_MIRROR_MISSING`, `FINISHED_GOOD_VALUATION_ACCOUNT_MISSING`, `FINISHED_GOOD_COGS_ACCOUNT_MISSING`, `FINISHED_GOOD_REVENUE_ACCOUNT_MISSING`, `FINISHED_GOOD_TAX_ACCOUNT_MISSING`, `RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING`, `RAW_MATERIAL_CATEGORY_REQUIRED`, `FINISHED_GOOD_CATEGORY_REQUIRED` |
| **Production** | Ready for manufacturing (FG only) | Catalog blockers + inventory blockers + `WIP_ACCOUNT_MISSING`, `LABOR_APPLIED_ACCOUNT_MISSING`, `OVERHEAD_APPLIED_ACCOUNT_MISSING` |
| **Packing** | Ready for packing operations | For FG: production blockers + `PACKAGING_SIZE_MISSING` or `PACKAGING_MAPPING_MISSING`. For PKG: inventory blockers. |
| **Sales** | Ready for sales order placement | For FG: catalog + inventory blockers + `NO_FINISHED_GOOD_BATCH_STOCK`, `DISCOUNT_ACCOUNT_MISSING`, `GST_OUTPUT_ACCOUNT_MISSING` or account mismatch. RM/PKG items: `RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE`. |
| **Accounting** | Accounting configuration is complete | Aggregate of accounting-specific blockers from all other stages |

### 5.2 Readiness by Item Class

| Item Class | Expected Stock Type | Catalog Blockers | Inventory Blockers | Production Blockers | Packing Blockers | Sales Blockers |
| --- | --- | --- | --- | --- | --- | --- |
| **FINISHED_GOOD** | FinishedGood | product missing/inactive | FG mirror missing, valuation/COGS/revenue/tax account missing | catalog + inventory + WIP/labor/overhead account missing | production blockers + packaging mapping missing | catalog + inventory + no batch stock + discount/tax account |
| **RAW_MATERIAL** | RawMaterial (PRODUCTION) | product missing/inactive | RM mirror missing, inventory account missing | catalog + inventory | N/A (not applicable for RM) | `RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE` (always blocked) |
| **PACKAGING_RAW_MATERIAL** | RawMaterial (PACKAGING) | product missing/inactive | RM mirror missing, inventory account missing | catalog + inventory | inventory blockers | `RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE` (always blocked) |

### 5.3 Readiness Response Structure

**SkuReadinessDto**:

Each stage is represented as a `Stage` record containing:
- `ready` (boolean) — `true` if no blockers
- `blockers` (List\<String\>) — specific blocker codes explaining why the SKU is not ready

The full readiness DTO contains one `Stage` per evaluation dimension: `catalog`, `inventory`, `production`, `packing`, `sales`, `accounting`.

### 5.4 Visibility Rules

- Callers with `ROLE_ADMIN` or `ROLE_ACCOUNTING` see all blocker codes including specific account names.
- Other roles see a sanitized view where account-specific blockers are replaced with a single `ACCOUNTING_CONFIGURATION_REQUIRED` blocker.

### 5.5 Batch-Level Sales Readiness

The sales stage checks not only for the existence of a finished-good mirror, but also for at least one finished-good batch with `quantityAvailable > 0`. This means a newly created SKU with zero stock is correctly reported as not sales-ready, even if all other configuration is complete.

---

## 6. SKU Code Generation

The system generates canonical SKU codes based on item class:

| Item Class | SKU Pattern | Example |
| --- | --- | --- |
| `FINISHED_GOOD` | `FG-{brandCode}-{name}-{color}-{size}` | `FG-ACME-ENAMEL-RED-1LTR` |
| `RAW_MATERIAL` | `RM-{brandCode}-{name}-{spec}-{unit}` | `RM-ACME-TITANIUM-SPEC1-KG` |
| `PACKAGING_RAW_MATERIAL` | `PKG-{brandCode}-{packType}-{size}-{unit}` | `PKG-ACME-DRUM-200ML-UNIT` |

**SKU rules:**
- Alphanumeric plus hyphen only
- Max 128 characters
- Unique per company (case-insensitive)
- SKU codes ending in `-BULK` are reserved for semi-finished goods and cannot be assigned to regular products
- `brandId` and `itemClass` are immutable after creation — to change either, create a new product

**Bulk variant generation** deterministically generates SKUs for each `(color × size)` combination using the pattern `{prefix}-{brandCode}-{baseName}-{colorCode}-{sizeCode}`.

---

## 7. Catalog Import

The catalog import surface accepts CSV file uploads and performs multi-row brand + product upsert with idempotency protection.

### 7.1 Import Mechanics

1. **File validation:** Accepts CSV files only (`text/csv`, `application/csv`, `application/vnd.ms-excel`)
2. **Idempotency:** Uses `Idempotency-Key` header (or `X-Idempotency-Key` legacy header). If no key is provided, a SHA-256 hash of the file content is used. Duplicate requests with matching keys return the original result.
3. **Row processing:** Each CSV row is processed in a separate `REQUIRES_NEW` transaction for isolation
4. **Retry:** Retryable failures (optimistic lock, data integrity violations) are retried once with cache eviction
5. **Outcome tracking:** A `CatalogImport` entity records the import with outcome counters and error details

### 7.2 Import Capabilities

- Auto-creates brands by name if they don't exist
- Creates or updates products by SKU or by (brand + product name)
- Auto-seeds raw-material inventory mirrors for RM/PKG items
- Reports per-row errors without stopping the entire import

### 7.3 Import Result

The response includes counters: `rowsProcessed`, `brandsCreated`, `productsCreated`, `productsUpdated`, `rawMaterialsSeeded`, plus a list of per-row errors.

---

## 8. Setup Prerequisites for Downstream Flows

This section makes explicit the prerequisites that downstream flows depend on but that must be set up on the catalog/setup surface first.

### 8.1 Prerequisites for Manufacturing/Production

| Prerequisite | Where Set Up | Downstream Effect |
| --- | --- | --- |
| Product master exists and is active | `POST /api/v1/catalog/items` | Required before any production log can reference the SKU |
| SKU code assigned | Auto-generated or via `customSkuCode` in create command | Links catalog item to inventory FG/RM mirror |
| WIP account in metadata | Item metadata `wipAccountId` | Production cannot post WIP costs without it |
| Labor applied account | Item metadata `laborAppliedAccountId` | Production labor costing requires it |
| Overhead applied account | Item metadata `overheadAppliedAccountId` | Production overhead costing requires it |

### 8.2 Prerequisites for Packing

| Prerequisite | Where Set Up | Downstream Effect |
| --- | --- | --- |
| All manufacturing prerequisites | Catalog item creation | Packing readiness depends on production readiness |
| Packaging size label | Item `sizeLabel` field | Used to look up packaging-size mapping |
| Packaging-size mapping exists | Factory module (`/api/v1/factory/packaging-mappings`) | Must exist and be active for the item's size label |

### 8.3 Prerequisites for Sales

| Prerequisite | Where Set Up | Downstream Effect |
| --- | --- | --- |
| Product master exists and is active | Catalog item creation | Sales orders cannot reference inactive or missing products |
| Finished-good inventory mirror | Auto-seeded on item creation (FG items) | Must exist with required accounts |
| FG valuation account | FG entity `valuationAccountId` | Inventory valuation and COGS posting |
| FG COGS account | FG entity `cogsAccountId` | Cost-of-goods-sold posting on dispatch |
| FG revenue account | FG entity `revenueAccountId` | Revenue posting |
| FG tax account | FG entity `taxAccountId` | Tax posting |
| FG discount account | FG entity `discountAccountId` or company default | Discount posting |
| GST output tax account | Company `gstOutputTaxAccountId` | Required for taxable FG items |
| At least one batch with positive available stock | Created via production/packing flow | `NO_FINISHED_GOOD_BATCH_STOCK` blocks sales readiness |
| `basePrice` set | Catalog item creation | Minimum selling price validation |

### 8.4 Prerequisites for Inventory Accounting Events

| Prerequisite | Where Set Up | Downstream Effect |
| --- | --- | --- |
| Inventory accounting events enabled | `erp.inventory.accounting.events.enabled` (default `true`) | When disabled, stock adjustments and movements do not generate accounting events |
| FG accounts (valuation, COGS, revenue) | FG entity or metadata defaults | Without these, inventory-to-accounting events cannot post |

---

## 9. Cross-Module Boundaries

### 9.1 Catalog → Inventory

When a catalog item is created or updated, `ProductionCatalogService.syncInventoryTruth()` creates or updates the corresponding `FinishedGood` or `RawMaterial` mirror in the inventory module. This is a **synchronous cross-module side effect** within the same transaction.

For FG items, the system ensures finished-good mirror accounts are populated from product metadata or company defaults via `ensureFinishedGoodAccounts()`.

For RM/PKG items, the system seeds a raw-material mirror with the inventory account ID derived from metadata or company defaults.

### 9.2 Catalog → Accounting

- `CompanyDefaultAccountsService` provides default account IDs when product metadata doesn't specify them
- Product metadata fields like `fgValuationAccountId`, `fgCogsAccountId`, `fgRevenueAccountId`, `fgDiscountAccountId`, `fgTaxAccountId` store explicit account assignments

### 9.3 Catalog → Factory (Read-Only)

- `SkuReadinessService` reads `PackagingSizeMappingRepository` to verify packaging mappings exist for the item's size label
- Catalog does not create or modify packaging mappings

### 9.4 Catalog → Sales (Read-Only)

- `CatalogItemStockDto` is built from `FinishedGood` and `RawMaterial` stock data
- Sales readiness checks batch availability via `FinishedGoodBatchRepository`
- The `salesOrderItemRepository` is used by `ProductionCatalogService` for referential integrity checks

### 9.5 Catalog → Purchasing (Read-Only)

- `ProductionCatalogService` reads `PurchaseOrderRepository`, `GoodsReceiptRepository`, and `RawMaterialPurchaseRepository` for referential integrity checks during item deactivation

---

## 10. Immutability and Constraint Rules

The following fields are **immutable after creation** — attempting to change them via `CatalogItemUpdateCommand` raises `BUSINESS_INVALID_STATE`:

| Field | Rationale |
| --- | --- |
| `brandId` | Brand determines SKU code structure; changing it would break SKU identity |
| `itemClass` | Item class determines which inventory mirror type is needed (FG vs RM vs PKG) |
| `color` | Part of SKU identity |
| `size` | Part of SKU identity and packaging mapping |
| `unitOfMeasure` | Part of SKU identity |

Additionally, `skuCode` is structurally immutable: it is auto-generated on creation and is **not present** in `CatalogItemUpdateCommand`, so it cannot be changed through the update endpoint. No runtime guard is needed because the field is simply absent from the mutation DTO.

To change any of these, create a new product and deactivate the old one.

---

## 11. Known Caveats and Limitations

1. **Brand deactivation does not cascade.** Deactivating a brand does not automatically deactivate its products. Products under inactive brands remain individually active unless explicitly deactivated.

2. **Item deactivation does not cascade.** Deactivating a product does not remove or deactivate the corresponding FG/RM inventory mirror. The mirror continues to exist and hold stock.

3. **Readiness is point-in-time.** Readiness snapshots are computed on demand and not cached. A readiness check at one moment may produce different results than a check moments later if another operation modifies the product or its mirrors.

4. **Bulk variant dry-run is supported.** Pass `dryRun=true` to `createVariants()` to preview the variant plan without committing. Conflicts are returned in the `conflicts` list rather than raising an exception.

5. **Import does not delete.** Catalog import can create and update products but never deletes or deactivates them. Rows that match existing products by SKU or (brand + name) update mutable fields; they do not change immutable fields.

6. **Semi-finished SKU suffix `-BULK` is reserved.** Product creation rejects any SKU code ending in `-BULK`. This convention is used internally by the packing/manufacturing flow.

7. **Accounting metadata is filtered per role.** Non-accounting roles see a simplified readiness view. Frontend consumers that need full accounting detail must authenticate with an accounting-capable role.

8. **Search pagination caps at 100.** The `pageSize` parameter in item search is silently capped at `MAX_PAGE_SIZE = 100`.

---

## 12. Deprecated and Non-Canonical Surfaces

No deprecated or non-canonical catalog/setup endpoints have been identified in the current implementation. All catalog operations are served through the single `CatalogController` at `/api/v1/catalog/**`.

The `ProductionCatalogService` includes internal listing methods (`listBrands()`, `listBrandProducts()`, `listProducts()`) that return `ProductionProductDto` records. These are not exposed through the controller and are used internally by other services. They are not deprecated but are also not the canonical public API for catalog browsing — use `CatalogService.searchItems()` instead.

---

## Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — module inventory (production entry)
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory (Catalog/Setup Readiness flow)
- [docs/flows/catalog-setup-readiness.md](../flows/catalog-setup-readiness.md) — canonical catalog/setup readiness flow (behavioral entrypoint)
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure used by catalog import
- [docs/platform/db-migration.md](../platform/db-migration.md) — persistence and migration posture
- [docs/platform/config-feature-toggles.md](../platform/config-feature-toggles.md) — inventory accounting event toggle
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/CONVENTIONS.md](../CONVENTIONS.md) — documentation conventions
