# Manufacturing / Packing Flow

Last reviewed: 2026-03-30

This packet documents the **manufacturing/packing flow**: the canonical lifecycle for production execution, from production planning through material consumption, packing operations, finished-good batch creation, and cost allocation. It covers production logs, packing records, batch status progression, and the dispatch handoff boundary.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Factory operator** | User with `ROLE_FACTORY` | Create production logs, packing records |
| **Admin** | User with `ROLE_ADMIN` | Full access including packaging mappings, cost allocation |
| **Accounting** | User with `ROLE_ACCOUNTING` | Read packing records, run cost allocation |

---

## 2. Entrypoints

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List production plans | GET | `/api/v1/factory/production-plans` | `ROLE_ADMIN`, `ROLE_FACTORY` | List production plans |
| Create production plan | POST | `/api/v1/factory/production-plans` | `ROLE_ADMIN`, `ROLE_FACTORY` | Create production plan |
| Update production plan | PUT | `/api/v1/factory/production-plans/{id}` | `ROLE_ADMIN`, `ROLE_FACTORY` | Update plan details |
| Update plan status | PATCH | `/api/v1/factory/production-plans/{id}/status` | `ROLE_ADMIN`, `ROLE_FACTORY` | Change plan status |
| Delete production plan | DELETE | `/api/v1/factory/production-plans/{id}` | `ROLE_ADMIN`, `ROLE_FACTORY` | Delete plan |
| List factory tasks | GET | `/api/v1/factory/tasks` | `ROLE_ADMIN`, `ROLE_FACTORY` | List factory tasks |
| Create factory task | POST | `/api/v1/factory/tasks` | `ROLE_ADMIN`, `ROLE_FACTORY` | Create task |
| Factory dashboard | GET | `/api/v1/factory/dashboard` | `ROLE_ADMIN`, `ROLE_FACTORY` | Get efficiency metrics |
| Create production log | POST | `/api/v1/factory/production/logs` | `ROLE_ADMIN`, `ROLE_FACTORY` | Record material mixing and production |
| List production logs | GET | `/api/v1/factory/production/logs` | `ROLE_ADMIN`, `ROLE_FACTORY` | List recent logs (top 25) |
| Get production log detail | GET | `/api/v1/factory/production/logs/{id}` | `ROLE_ADMIN`, `ROLE_FACTORY` | Get log with materials and packing records |
| Record packing | POST | `/api/v1/factory/packing-records` | `ROLE_ADMIN`, `ROLE_FACTORY`, `ROLE_ACCOUNTING` | Record packing operation (requires idempotency key) |
| List unpacked batches | GET | `/api/v1/factory/unpacked-batches` | Admin/Factory/Accounting | List batches READY_TO_PACK or PARTIAL_PACKED |
| Get packing history | GET | `/api/v1/factory/production-logs/{id}/packing-history` | Admin/Factory/Accounting | Get packing history for a production log |
| List bulk batches | GET | `/api/v1/factory/bulk-batches/{finishedGoodId}` | Admin/Factory/Accounting | List bulk (semi-finished) batches |
| List child batches | GET | `/api/v1/factory/bulk-batches/{parentBatchId}/children` | Admin/Factory/Accounting | List child FG batches |
| Create packaging mapping | POST | `/api/v1/factory/packaging-mappings` | `ROLE_ADMIN` | Create packaging size mapping |
| Update packaging mapping | PUT | `/api/v1/factory/packaging-mappings/{id}` | `ROLE_ADMIN` | Update mapping |
| Delete packaging mapping | DELETE | `/api/v1/factory/packaging-mappings/{id}` | `ROLE_ADMIN` | Deactivate mapping |
| Cost allocation | POST | `/api/v1/factory/cost-allocation` | `ROLE_ADMIN`, `ROLE_FACTORY` | Allocate monthly labor/overhead variance |

---

## 3. Preconditions

### Production Log Preconditions

1. **Brand exists and active** — Valid brand ID
2. **Product exists and active** — Valid product ID belonging to brand
3. **Product is sales-ready** — SKU readiness check passes for production stage
4. **Sufficient raw material stock** — Each material in request has sufficient quantity in inventory
5. **WIP account configured** — Product metadata must include WIP account for cost posting

### Packing Preconditions

1. **Production log exists and not FULLY_PACKED** — Valid log ID, status not terminal
2. **Packaging mapping exists** — Mapping for the product's size label must exist
3. **Packaging material stock sufficient** — Packaging material has sufficient quantity
4. **Semi-finished batch exists** — Batch created during production log
5. **Idempotency key provided** — Header `Idempotency-Key` required (or `X-Idempotency-Key` legacy)

### Cost Allocation Preconditions

1. **Production logs in period are FULLY_PACKED** — Only fully packed logs can receive variance
2. **No prior allocation for period** — Idempotent — skips batches already allocated
3. **Valid account IDs provided** — Labor and overhead expense accounts must exist

---

## 4. Lifecycle

### 4.1 Production Plan Lifecycle (Planning Only)

```
[Start] → Validate unique plan number → Create plan with PLANNED status → [End: Plan created]
```

**Key behaviors:**
- Plans are organizational artifacts only — they do NOT trigger material consumption or inventory changes
- Status transitions: PLANNED → COMPLETED (manual)
- Plans use `insertIfAbsent` for idempotency

### 4.2 Production Log Lifecycle (Material Mixing)

```
[Start] → Validate brand/product → Generate production code → 
Check raw material stock → [For each material: consume from batches (FIFO)] → 
Calculate costs → Create semi-finished batch → 
Post WIP journals → Set status READY_TO_PACK → [End: Production logged]
```

**Key behaviors:**
- Production code format: `PROD-{YYYYMMDD}-{SEQ}`
- Material consumption uses FIFO order (or WAC if configured)
- Creates `RawMaterialMovement` records for traceability
- Semi-finished batch created with SKU `{productSku}-BULK` (materialType=PRODUCTION)
- **Accounting journals posted:**
  - Material consumption: DR WIP / CR raw material inventory
  - Labor/overhead: DR WIP / CR labor applied / overhead applied
  - Semi-finished receipt: DR semi-finished inventory / CR WIP

### 4.3 Packing Lifecycle

```
[Start] → Validate production log status → Reserve idempotency key → 
Resolve allowed sizes → Calculate packed quantity → 
Save packing record → [Consume packaging materials] → 
[Consume semi-finished batch] → [Register FG batch] → 
Update production log status → [End: Packing recorded]
```

**Status progression:**
```
READY_TO_PACK → PARTIAL_PACKED → FULLY_PACKED
```

**Key behaviors:**
- Packing is the transition point from factory to inventory
- Packaging materials consumed via `PackagingSizeMapping` lookup
- Finished-good batch registered with unit cost = semi-finished cost + packaging cost per unit
- FG batch created with source = PRODUCTION
- **Accounting journals posted:**
  - Packaging consumption: DR WIP / CR packaging inventory
  - FG receipt: DR FG valuation / CR semi-finished + CR WIP

### 4.4 Wastage Closure Lifecycle

Triggered when `closeResidualWastage = true` in packing request:

```
[Start] → Calculate residual = mixedQuantity - packedQuantity → 
Consume wastage from semi-finished batch → 
Post wastage journal → Set status FULLY_PACKED → [End: Wastage closed]
```

**Key behaviors:**
- Wastage reason defaults to `PROCESS_LOSS`
- Can set to `NONE` if no wastage occurred

### 4.5 Cost Allocation Lifecycle

```
[Start] → Get FULLY_PACKED logs for period → Calculate total liters → 
Compute labor/overhead variance → Distribute proportionally → 
Update batch costs → Post variance journals → [End: Variance allocated]
```

**Key behaviors:**
- Monthly variance = actual cost - applied cost
- Distributed proportionally by liters produced
- Idempotent — skips batches already allocated for the period

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Production log** — Created with material consumption, WIP journals posted, semi-finished batch created
2. **Packing record** — Created with packaging consumption, FG batch registered, status updated
3. **Production log FULLY_PACKED** — All mixed quantity packed (or wastage closed)
4. **Cost allocation** — Variance distributed to batches, journals posted

### Current Limitations

1. **Production plan is optional** — Not required before production log; serves as organizational artifact only

2. **No automated cost integration** — Cost allocation requires manual entry of actual labor/overhead costs; no automated integration with payroll or accounting systems

3. **Wastage reason limited** — Only `PROCESS_LOSS` or `NONE`

4. **Idempotency key required** — Packing without idempotency key fails; no fallback behavior

5. **Batch number generation per month** — Batch codes include month, so same SKU can have multiple batches per month

6. **Packaging mapping mandatory** — No fallback if mapping missing; packing fails

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/factory/production/logs` | `ProductionLogController` | Primary production execution entry |
| `POST /api/v1/factory/packing-records` | `PackingController` | Packing with idempotency |
| `POST /api/v1/factory/cost-allocation` | `FactoryController` | Monthly variance allocation |
| `POST /api/v1/factory/packaging-mappings` | `PackagingMappingController` | Packaging size setup |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `POST /api/v1/factory/production-batches` | Retired (hard cut) | Use `POST /api/v1/factory/production/logs` |
| `GET /api/v1/factory/production-batches` | Retired (hard cut) | Use `GET /api/v1/factory/production/logs` |
| `POST /api/v1/factory/pack` | Retired (hard cut) | Use `POST /api/v1/factory/packing-records` |
| Packing completion seam (PackingCompletionService) | Partially retired | Completion semantics removed, service survives |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `production` (catalog) | SKU resolution, readiness evaluation | Read |
| `inventory` | Raw material consumption, FG batch creation, packaging material stock | Write (consumption), Write (FG batch), Read |
| `accounting` | WIP/cost journals, variance journals | Write |

---

## 8. Event/Listener Boundaries

The manufacturing/packing flow intersects with inventory events that trigger accounting side effects:

| Event | Listener | Phase | Effect on Manufacturing |
| --- | --- | --- | --- |
| `InventoryMovementEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | When packing consumes raw materials and creates finished-good batches, this event triggers automatic inventory valuation journal entries in accounting if `erp.inventory.accounting.events.enabled=true` (default: true). This is a material coupling: raw material consumption posts DR WIP / CR raw material inventory, and FG receipt posts DR FG valuation / CR WIP. If the toggle is disabled, packing silently skips accounting side effects. |
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Triggers accounting entries for raw material and finished goods valuation changes during packing. |

**Key boundary note:** The packing operation is the transition point from factory to inventory. The `InventoryAccountingEventListener` bridges packing operations to accounting journals. This bridge is conditional on the feature flag `erp.inventory.accounting.events.enabled`. See [orchestrator.md](../modules/orchestrator.md) for the full event bridge map and configuration-guarded risks.

---

## 9. Security Considerations

- **RBAC for packing** — ADMIN, FACTORY, and ACCOUNTING can record packing
- **Admin-only for packaging mappings** — Only ADMIN can create/update/delete mappings
- **Idempotency required** — Packing requests require idempotency key to prevent duplicate batches

---

## 10. Related Documentation

- [docs/modules/factory.md](../modules/factory.md) — Factory module canonical packet
- [docs/modules/inventory.md](../modules/inventory.md) — Inventory module for stock truth
- [docs/modules/catalog-setup.md](../modules/catalog-setup.md) — Catalog for product truth
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory

---

## 11. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| Production plan usage | Optional. Production plans are not enforced before creating production logs. |
| Cost integration | Not automated. Manual entry is required for cost allocation. |
| Packing idempotency fallback | Idempotency key is required with no graceful degradation if omitted. |
