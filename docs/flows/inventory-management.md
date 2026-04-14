# Inventory Management Flow

Last reviewed: 2026-03-30

This packet documents the **inventory management flow**: the canonical lifecycle for stock truth, batch management, adjustments, opening stock import, and traceability. It covers finished goods and raw material stock summaries, low-stock alerts, batch traceability, stock adjustments, and the opening stock import process.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | User with `ROLE_ADMIN` | Full inventory access, adjustments, opening stock |
| **Accounting** | User with `ROLE_ACCOUNTING` | Stock summaries, adjustments, opening stock |
| **Factory** | User with `ROLE_FACTORY` | Stock summaries, batch traceability |
| **Sales** | User with `ROLE_SALES` | Stock summaries, batch traceability |
| **Operational dispatch** | User with `OPERATIONAL_DISPATCH` role | Confirm dispatch, manage packaging slips |

---

## 2. Entrypoints

### Finished Goods Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List finished goods | GET | `/api/v1/finished-goods` | Admin/Factory/Sales/Accounting | List all finished goods |
| Get FG detail | GET | `/api/v1/finished-goods/{id}` | Admin/Factory/Sales/Accounting | Get FG detail |
| List FG batches | GET | `/api/v1/finished-goods/{id}/batches` | Admin/Factory/Sales | List batches for FG |
| Stock summary | GET | `/api/v1/finished-goods/stock-summary` | Admin/Factory/Sales/Accounting | FG stock summary |
| Low stock items | GET | `/api/v1/finished-goods/low-stock` | Admin/Factory/Sales | Get low-stock items |
| Get low-stock threshold | GET | `/api/v1/finished-goods/{id}/low-stock-threshold` | Admin/Factory/Sales/Accounting | Get threshold |
| Update threshold | PUT | `/api/v1/finished-goods/{id}/low-stock-threshold` | Admin/Factory/Accounting | Update threshold |

### Raw Material Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| RM stock summary | GET | `/api/v1/raw-materials/stock` | Admin/Accounting/Factory | RM aggregate summary |
| RM inventory list | GET | `/api/v1/raw-materials/stock/inventory` | Admin/Accounting/Factory | Per-material stock |
| RM low stock | GET | `/api/v1/raw-materials/stock/low-stock` | Admin/Accounting/Factory | Low-stock raw materials |
| RM adjustment | POST | `/api/v1/inventory/raw-materials/adjustments` | Admin/Accounting | Raw material adjustment |

### Adjustment Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List adjustments | GET | `/api/v1/inventory/adjustments` | Admin/Accounting | List FG adjustments |
| Create adjustment | POST | `/api/v1/inventory/adjustments` | Admin/Accounting | Create FG adjustment |

### Batch Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Batch movements | GET | `/api/v1/inventory/batches/{id}/movements` | Admin/Factory/Accounting/Sales | Batch movement history |
| Expiring batches | GET | `/api/v1/inventory/batches/expiring-soon` | Admin/Accounting/Factory/Sales | Batches expiring within N days |

### Opening Stock Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Import opening stock | POST | `/api/v1/inventory/opening-stock` | Admin/Accounting/Factory | Import CSV for initial stock |
| Import history | GET | `/api/v1/inventory/opening-stock` | Admin/Accounting/Factory | List import history |

### Dispatch Endpoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Pending slips | GET | `/api/v1/dispatch/pending` | Admin/Factory/Sales | Pending packaging slips |
| Dispatch preview | GET | `/api/v1/dispatch/preview/{slipId}` | Admin/Factory | Preview dispatch |
| Confirm dispatch | POST | `/api/v1/dispatch/confirm` | `OPERATIONAL_DISPATCH` | Confirm dispatch execution |
| Slip detail | GET | `/api/v1/dispatch/slip/{slipId}` | Admin/Factory/Sales | Get packaging slip |
| Slip by order | GET | `/api/v1/dispatch/order/{orderId}` | Admin/Factory/Sales | Get slip by sales order |
| Update slip status | PATCH | `/api/v1/dispatch/slip/{slipId}/status` | Admin/Factory | Update status |
| Cancel backorder | POST | `/api/v1/dispatch/backorder/{slipId}/cancel` | Admin/Factory | Cancel backorder |
| Challan PDF | GET | `/api/v1/dispatch/slip/{slipId}/challan/pdf` | Admin/Factory | Generate delivery challan |

---

## 3. Preconditions

### FG Adjustment Preconditions

1. **Idempotency key provided** — Header `Idempotency-Key` (or legacy `X-Idempotency-Key` or body `idempotencyKey`)
2. **Valid adjustment type** — `DAMAGED`, `SHRINKAGE`, `OBSOLETE`, `RECOUNT_UP`
3. **Valid FG and quantity** — Finished good exists, quantity positive
4. **Sufficient stock for negative** — For DAMAGED/SHRINKAGE/OBSOLETE, sufficient batch stock available

### RM Adjustment Preconditions

1. **Idempotency key provided** — Same as FG
2. **Valid direction** — `INCREASE` or `DECREASE`
3. **Valid adjustment account** — Account ID provided
4. **Valid batch and quantity** — Raw material batch exists, quantity positive

### Opening Stock Import Preconditions

1. **Valid CSV format** — Proper headers (SKU, quantity, batchCode, manufacturedAt, expiryDate, etc.)
2. **Valid items** — Items exist in catalog, valid quantities
3. **Readiness gating** — FG items must have accounts configured before import succeeds

### Dispatch Preconditions

1. **Packaging slip exists** — Valid slip ID from sales order
2. **Slip is pending** — Status is PENDING or CONFIRMED (not CANCELLED)
3. **Caller has dispatch role** — `OPERATIONAL_DISPATCH` role required for confirm
4. **Stock available** — Sufficient batch stock for reserved quantity

---

## 4. Lifecycle

### 4.1 Stock Summary Lifecycle

```
[Start] → Query finished goods → For each: calculate available = onHand - reserved → 
Return summary → [End: Stock data returned]
```

**Key behaviors:**
- Stock is denormalized on FG/RM entities (`current_stock`, `reserved_stock` columns)
- Updated atomically during reservation, dispatch, adjustment, and import

### 4.2 Low Stock Detection Lifecycle

```
[Start] → Query items → Filter where availableStock < lowStockThreshold → 
Return low-stock items → [End: Low-stock list]
```

**Key behaviors:**
- Default threshold = 100 (can be overridden per item)
- Both FG and RM support low-stock queries

### 4.3 Batch Traceability Lifecycle

```
[Start] → Resolve batch ID → Query movements ordered by timestamp → 
Classify each by source (production/purchase/adjustment) → 
Return movement history → [End: Traceability data]
```

**Key behaviors:**
- Movement types: RECEIPT, ISSUE, ADJUSTMENT_IN, ADJUSTMENT_OUT, WASTAGE
- Can filter by `batchType=RAW_MATERIAL` or `batchType=FINISHED_GOOD` when ID ambiguous

### 4.4 FG Adjustment Lifecycle

```
[Start] → Reserve idempotency key → Validate FG exists → 
[If RECOUNT_UP: create adjustment batch] → 
[If negative: consume from batches using costing method] → 
Post accounting journal → Return adjustment → [End: Adjustment complete]
```

**Key behaviors:**
- Positive adjustments (RECOUNT_UP) create new batch
- Negative adjustments consume existing batches (FIFO/LIFO/WAC based on costing method)
- Accounting journal posted synchronously via `AccountingFacade.postInventoryAdjustment()`

### 4.5 Opening Stock Import Lifecycle

```
[Start] → Validate CSV format → For each row: 
  → Resolve SKU → Validate accounts (FG only) → Create batch → 
  → Post opening-stock journal → [End: Import complete]
```

**Key behaviors:**
- Creates both FG and RM batches
- For FG: readiness gating requires accounts before import succeeds
- Journal: DR inventory / CR `OPEN-BAL` (opening balance) account
- Returns summary with rows processed, batches created, errors

### 4.6 Dispatch Lifecycle

```
[Start] → Validate slip exists → Validate status → Check stock → 
Reduce reserved quantity → Create inventory movements → 
Post dispatch journal (COGS) → Update slip status → [End: Dispatch confirmed]
```

**Key behaviors:**
- Dispatch is the transition point from inventory to sales/customer
- Reserved quantity moved to shipped
- COGS journal posted: DR COGS / CR FG valuation

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Stock summary** — Current stock, reserved, and available quantities returned
2. **Low stock detection** — Items below threshold identified
3. **Batch traceability** — Full movement history returned with classification
4. **Adjustment** — Stock updated, accounting journal posted, idempotency ensured
5. **Opening stock import** — Batches created, opening-stock journal posted
6. **Dispatch** — Stock reduced, COGS posted, slip status updated

### Current Limitations

1. **WAC cache 5-minute TTL** — Weighted average cost cached; can be slightly stale

2. **Zero-cost dispatch fails** — Dispatch with zero cost against products with stock fails hard — prevents silent zero-cost COGS

3. **Batch ID ambiguity** — When batch ID could be either RM or FG, caller must specify type

4. **Opening stock readiness gating** — FG items without accounts fail import (by design, but means legacy imports need account pre-setup)

5. **Inventory accounting events toggle** — When `erp.inventory.accounting.events.enabled=false`, stock operations don't generate accounting events

6. **No batch merge** — Cannot merge multiple batches into one

7. **No stock transfer** — No mechanism to transfer stock between locations or companies

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/inventory/adjustments` | `InventoryAdjustmentController` | FG adjustment with idempotency |
| `POST /api/v1/inventory/raw-materials/adjustments` | `RawMaterialController` | RM adjustment |
| `POST /api/v1/inventory/opening-stock` | `OpeningStockImportController` | CSV import for initial stock |
| `GET /api/v1/inventory/batches/{id}/movements` | `InventoryBatchController` | Batch traceability |
| `POST /api/v1/dispatch/confirm` | `DispatchController` | Dispatch execution |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `/api/v1/dispatch/confirm` (legacy) | Retired | Use `POST /api/v1/dispatch/confirm` |
| Legacy intake endpoints | No replacement | The raw-material intake path was disabled via `erp.raw-material.intake.enabled=false`. Stock intake for raw materials now occurs through the [procure-to-pay flow](procure-to-pay.md) via GRN (Goods Receipt Note) processing. |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `factory` | Batch creation (production source) | Read (batch source) |
| `sales` | Reservations, dispatch execution, packaging slips | Write (reservations), Read (slips) |
| `purchasing` | GRN stock intake | Read (batch source) |
| `accounting` | Adjustment journals, COGS journals, opening-stock journals | Write |

---

## 8. Event/Listener Boundaries

The inventory management flow is the primary source of inventory events that trigger accounting side effects:

| Event | Listener | Phase | Effect on Inventory |
| --- | --- | --- | --- |
| `InventoryMovementEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Inventory movements (adjustments, dispatch, consumption, opening stock) trigger automatic inventory valuation journal entries in accounting if `erp.inventory.accounting.events.enabled=true` (default: true). This is the primary event bridge that connects inventory truth to accounting truth. If disabled, movements silently skip accounting side effects, causing incomplete period-end balances. |
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Triggers accounting entries for valuation changes when inventory is adjusted or consumed. |
| `InventoryMovementEvent` | `InventoryAuditListener` | `AFTER_COMMIT` | Creates audit trail markers for inventory movements, enabling traceability during reconciliation. |

**Key boundary note:** The inventory module is the source of truth for stock movements. The `InventoryAccountingEventListener` is the primary bridge to accounting—if the feature flag is disabled, the accounting side effects are silently skipped, which can cause period-end inventory balances to be incomplete without any error at the inventory layer. This is a "fail-open" behavior that operators should be aware of.

Access-control boundaries:
- **OPERATIONAL_DISPATCH** — Dispatch confirmation requires this specific role predicate, not generic ADMIN
- **RBAC by operation** — Adjustments require ADMIN or ACCOUNTING roles; dispatch requires OPERATIONAL_DISPATCH
- **Idempotency required** — Adjustments and packing require idempotency key to prevent duplicates

See [orchestrator.md](../modules/orchestrator.md) for the full event bridge map and configuration-guarded risks.

---

## 9. Security Considerations

- **Accounting metadata visibility** — Some stock data filtered for non-accounting roles
- **Company scoping** — All inventory operations scoped to tenant via JWT

> **Note:** RBAC and idempotency requirements are documented in [Section 8: Event/Listener Boundaries](#8-eventlistener-boundaries) where the access-control boundaries are detailed.

---

## 10. Related Documentation

- [docs/modules/inventory.md](../modules/inventory.md) — Inventory module canonical packet
- [docs/modules/factory.md](../modules/factory.md) — Factory module for production batches
- [docs/modules/sales.md](../modules/sales.md) — Sales module for dispatch boundary
- [docs/modules/purchasing.md](../modules/purchasing.md) — Purchasing for GRN stock intake
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/frontend-portals/factory/README.md](../frontend-portals/factory/README.md) — Factory frontend handoff (inventory payloads, RBAC)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy dispatch paths, raw-material intake toggle)

---

## 11. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Batch merge | Not implemented. No merge capability for duplicate batches. |
| Stock transfer | Not implemented. No inter-location or inter-company transfer capability. |
| Zero-cost dispatch | Blocked. Fails hard to prevent silent COGS loss. |
| Intake toggle | Configurable. Can be disabled via `erp.raw-material.intake.enabled`. |
