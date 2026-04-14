# Catalog / Setup Readiness Flow

Last reviewed: 2026-03-30

This packet documents the **catalog/setup readiness flow**: the canonical lifecycle for creating and maintaining product catalog data, evaluating SKU readiness for downstream operations, and managing packaging material definitions. It covers brand management, item CRUD, CSV import, SKU readiness evaluation, and packaging mapping setup.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | User with `ROLE_ADMIN` | Full write access to brands, items, import, packaging mappings |
| **Accounting** | User with `ROLE_ACCOUNTING` | Read/write access to brands, items, import |
| **Sales** | User with `ROLE_SALES` | Read-only access to brands and items |
| **Factory** | User with `ROLE_FACTORY` | Read-only access to brands, items, packaging mappings |

---

## 2. Entrypoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Create brand | POST | `/api/v1/catalog/brands` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Create a new brand |
| List brands | GET | `/api/v1/catalog/brands` | `ROLE_ADMIN`, `ROLE_ACCOUNTING`, `ROLE_SALES`, `ROLE_FACTORY` | List brands with optional `?active=true/false` filter |
| Get brand | GET | `/api/v1/catalog/brands/{brandId}` | Admin/Accounting/Sales/Factory | Get single brand |
| Update brand | PUT | `/api/v1/catalog/brands/{brandId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Update brand details |
| Deactivate brand | DELETE | `/api/v1/catalog/brands/{brandId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Soft-delete brand (set active=false) |
| Create item | POST | `/api/v1/catalog/items` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Create catalog item (product) |
| Search items | GET | `/api/v1/catalog/items` | Admin/Accounting/Sales/Factory | Search with filters (`?q=`, `?itemClass=`, `?includeStock=`, `?includeReadiness=`) |
| Get item | GET | `/api/v1/catalog/items/{itemId}` | Admin/Accounting/Sales/Factory | Get item detail with optional stock/readiness enrichment |
| Update item | PUT | `/api/v1/catalog/items/{itemId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Update mutable item fields |
| Deactivate item | DELETE | `/api/v1/catalog/items/{itemId}` | Admin/Accounting/Sales/Factory | Soft-delete item |
| Bulk create variants | POST | `/api/v1/catalog/items/bulk-variants` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Generate multiple color×size variants |
| Import catalog | POST | `/api/v1/catalog/import` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` | Bulk import via CSV |
| List packaging mappings | GET | `/api/v1/factory/packaging-mappings` | Admin/Factory | List all packaging size mappings |
| Get active mappings | GET | `/api/v1/factory/packaging-mappings/active` | Admin/Factory | List active mappings |
| Create mapping | POST | `/api/v1/factory/packaging-mappings` | `ROLE_ADMIN` only | Create packaging size mapping |
| Update mapping | PUT | `/api/v1/factory/packaging-mappings/{id}` | `ROLE_ADMIN` only | Update mapping |
| Delete mapping | DELETE | `/api/v1/factory/packaging-mappings/{id}` | `ROLE_ADMIN` only | Deactivate mapping |

---

## 3. Preconditions

### Brand Creation Preconditions

1. **Name unique per tenant** — Brand name must be unique within the company (case-insensitive)
2. **Valid name** — Not blank, reasonable length

### Item Creation Preconditions

1. **Brand exists and is active** — Valid brand ID pointing to active brand
2. **Valid item class** — `FINISHED_GOOD`, `RAW_MATERIAL`, or `PACKAGING_RAW_MATERIAL`
3. **SKU uniqueness** — Auto-generated SKU code must be unique per company
4. **Required fields** — `brandId`, `name`, `itemClass`, `unitOfMeasure`, `hsnCode`, `gstRate`
5. **HSN and GST valid** — HSN code provided, GST rate between 0-100%

### Import Preconditions

1. **Valid CSV format** — Proper headers and data format
2. **Idempotency key** — Optional but recommended to prevent duplicate imports

### Packaging Mapping Preconditions

1. **Packaging material exists** — Raw material of type `PACKAGING` must exist
2. **Size label valid** — Size label matches production item sizes

---

## 4. Lifecycle

### 4.1 Brand Lifecycle

```
[Start] → Validate unique name → Generate brand code → Create brand → 
[End: Brand created]
```

**Key behaviors:**
- Brand code is auto-generated (uppercase alphanumeric, max 12 chars)
- Deactivating a brand does NOT cascade to products — products remain individually active

### 4.2 Item Lifecycle

```
[Start] → Validate brand exists → Validate unique SKU → Generate SKU code → 
Create item → [Sync: Create inventory mirror] → [End: Item created]
```

**Key behaviors:**
- SKU code is auto-generated based on item class:
  - FG: `FG-{brandCode}-{name}-{color}-{size}`
  - RM: `RM-{brandCode}-{name}-{spec}-{unit}`
  - PKG: `PKG-{brandCode}-{packType}-{size}-{unit}`
- **Immutable after creation**: `brandId`, `itemClass`, `color`, `size`, `unitOfMeasure` cannot be changed
- **Inventory mirror sync**: Creating an item synchronously creates a `FinishedGood` or `RawMaterial` mirror in inventory module

### 4.3 Import Lifecycle

```
[Start] → Validate CSV format → Check idempotency → For each row:
  → Resolve/create brand → Create/update product → [Sync inventory] → [End per row]
Aggregate results → Return summary (created/updated/errors)
```

**Key behaviors:**
- Each row processed in separate transaction for isolation
- Auto-creates brands by name if they don't exist
- Creates or updates products by SKU or (brand + name)
- Auto-seeds raw-material mirrors for RM/PKG items
- Does NOT delete or deactivate products — only creates/updates

### 4.4 SKU Readiness Evaluation

SKU readiness is evaluated on-demand when `includeReadiness=true` is passed to item search/detail endpoints:

```
[Start] → Evaluate catalog stage → [Blocked? Return blockers] → 
Evaluate inventory stage → [Blocked? Return blockers] → 
[If FG: Evaluate production stage] → [Blocked? Return blockers] → 
[If FG: Evaluate packing stage] → [Blocked? Return blockers] → 
[If FG: Evaluate sales stage] → [Blocked? Return blockers] → 
[End: All stages ready]
```

**Readiness stages:**
| Stage | FG Blockers | RM/PKG Blockers |
| --- | --- | --- |
| Catalog | PRODUCT_MASTER_MISSING, PRODUCT_INACTIVE | Same |
| Inventory | FG mirror missing, valuation/COGS/revenue/tax accounts missing | RM mirror missing, inventory account missing |
| Production | WIP, labor, overhead accounts missing | N/A |
| Packing | Packaging mapping missing | N/A |
| Sales | No batch stock, discount/tax accounts missing | RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE |

### 4.5 Packaging Mapping Lifecycle

```
[Start] → Validate packaging material exists → Create mapping → 
[End: Mapping active]
```

**Key behaviors:**
- Mapping links a size label (e.g., "1L", "5L") to a packaging raw material
- Required for packing readiness — missing mapping blocks FG items from being packed

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Brand** — Created and active, visible to all authorized roles
2. **Item** — Created with generated SKU, inventory mirror synced, ready for downstream use
3. **Import** — CSV processed, products created/updated, summary returned
4. **Packaging mapping** — Created and active, available for packing operations
5. **Readiness evaluation** — All applicable stages pass, no blockers

### Current Limitations

1. **Brand deactivation does not cascade** — Deactivating a brand does NOT deactivate its products

2. **Item deactivation does not cascade** — Deactivating an item does NOT remove its inventory mirror

3. **Readiness is point-in-time** — Computed on-demand, not cached; concurrent operations can cause momentary inconsistency

4. **Import does not delete** — Can create/update but never deletes or deactivates products

5. **Search pagination capped at 100** — `pageSize` silently capped at `MAX_PAGE_SIZE = 100`

6. **Accounting metadata filtered** — Non-accounting roles see simplified readiness view (`ACCOUNTING_CONFIGURATION_REQUIRED` instead of specific account blockers)

7. **Semi-finished SKU suffix reserved** — Product creation rejects any SKU ending in `-BULK`

8. **Inventory accounting events toggle** — When `erp.inventory.accounting.events.enabled=false`, stock operations don't generate accounting events

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/catalog/items` | `CatalogController` | Primary item creation, creates inventory mirror |
| `POST /api/v1/catalog/import` | `CatalogController` | Bulk CSV import |
| `POST /api/v1/factory/packaging-mappings` | `PackagingMappingController` | Packaging size mapping setup |
| `GET /api/v1/catalog/items?includeReadiness=true` | `CatalogController` | SKU readiness evaluation |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `POST /api/v1/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| `POST /api/v1/accounting/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| `GET /api/v1/catalog/products` | Retired (hard cut) | Use `GET /api/v1/catalog/items` |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `inventory` | FG/RM mirror creation, stock data for readiness | Write (mirror sync), Read (stock, batches) |
| `factory` | Packaging mapping consumption, packaging material lookup | Read |
| `accounting` | Default account resolution, CoA seeding | Read |

---

## 8. Security Considerations

- **Class-level RBAC** — All catalog endpoints require at least one of: ADMIN, ACCOUNTING, SALES, FACTORY
- **Write restriction** — Create/update operations restricted to ADMIN and ACCOUNTING
- **Accounting metadata visibility** — Account fields filtered for non-accounting roles in readiness responses

---

## 9. Related Documentation

- [docs/modules/catalog-setup.md](../modules/catalog-setup.md) — Catalog module canonical packet
- [docs/modules/inventory.md](../modules/inventory.md) — Inventory module for stock truth
- [docs/modules/factory.md](../modules/factory.md) — Factory module for manufacturing truth
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/frontend-portals/factory/README.md](../frontend-portals/factory/README.md) — Factory frontend handoff (catalog and setup payloads, RBAC)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy product endpoints)

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Bulk variant dry-run | Supported. Pass `dryRun=true` to preview without committing changes. |
| Deactivation cascade | Brand and item deactivation do not cascade to related entities. Manual cleanup required. |
| Readiness caching | Not implemented. Values are computed on-demand. |
