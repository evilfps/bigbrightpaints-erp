# Procure-to-Pay (P2P) Flow

Last reviewed: 2026-03-30

This packet documents the **procure-to-pay flow**: the canonical purchasing lifecycle from supplier onboarding through purchase order creation, goods receipt (GRN), purchase invoice capture, and supplier payment/settlement. It covers the stock truth boundary (GRN → inventory) and the AP truth boundary (purchase invoice → accounting).

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Full purchasing lifecycle access | `ROLE_ADMIN` |
| **Accounting** | Supplier approval, invoice capture, settlements | `ROLE_ACCOUNTING` |
| **Factory** | View POs and GRNs for production planning | `ROLE_FACTORY` |

---

## 2. Entrypoints

### Supplier Management — `SupplierController` (`/api/v1/suppliers/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Suppliers | GET | `/api/v1/suppliers` | ADMIN, ACCOUNTING, FACTORY | List all suppliers |
| Get Supplier | GET | `/api/v1/suppliers/{id}` | ADMIN, ACCOUNTING, FACTORY | Get supplier detail |
| Create Supplier | POST | `/api/v1/suppliers` | ADMIN, ACCOUNTING | Create supplier |
| Update Supplier | PUT | `/api/v1/suppliers/{id}` | ADMIN, ACCOUNTING | Update supplier |
| Approve Supplier | POST | `/api/v1/suppliers/{id}/approve` | ADMIN, ACCOUNTING | Approve supplier |
| Activate Supplier | POST | `/api/v1/suppliers/{id}/activate` | ADMIN, ACCOUNTING | Activate approved supplier |
| Suspend Supplier | POST | `/api/v1/suppliers/{id}/suspend` | ADMIN, ACCOUNTING | Suspend active supplier |

### Purchase Order — `PurchasingWorkflowController` (`/api/v1/purchasing/purchase-orders/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List POs | GET | `/api/v1/purchasing/purchase-orders` | ADMIN, ACCOUNTING | List purchase orders |
| Get PO | GET | `/api/v1/purchasing/purchase-orders/{id}` | ADMIN, ACCOUNTING | Get PO detail |
| Create PO | POST | `/api/v1/purchasing/purchase-orders` | ADMIN, ACCOUNTING | Create PO |
| Approve PO | POST | `/api/v1/purchasing/purchase-orders/{id}/approve` | ADMIN, ACCOUNTING | Approve PO |
| Void PO | POST | `/api/v1/purchasing/purchase-orders/{id}/void` | ADMIN, ACCOUNTING | Void PO |
| Close PO | POST | `/api/v1/purchasing/purchase-orders/{id}/close` | ADMIN, ACCOUNTING | Close PO |
| PO Timeline | GET | `/api/v1/purchasing/purchase-orders/{id}/timeline` | ADMIN, ACCOUNTING | Status history |

### Goods Receipt (GRN) — `PurchasingWorkflowController` (`/api/v1/purchasing/goods-receipts/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List GRNs | GET | `/api/v1/purchasing/goods-receipts` | ADMIN, ACCOUNTING | List GRNs |
| Get GRN | GET | `/api/v1/purchasing/goods-receipts/{id}` | ADMIN, ACCOUNTING | Get GRN detail |
| Create GRN | POST | `/api/v1/purchasing/goods-receipts` | ADMIN, ACCOUNTING | Create GRN (requires Idempotency-Key) |

### Purchase Invoice — `RawMaterialPurchaseController` (`/api/v1/purchasing/raw-material-purchases/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| List Invoices | GET | `/api/v1/purchasing/raw-material-purchases` | ADMIN, ACCOUNTING | List purchase invoices |
| Get Invoice | GET | `/api/v1/purchasing/raw-material-purchases/{id}` | ADMIN, ACCOUNTING | Get invoice detail |
| Capture Invoice | POST | `/api/v1/purchasing/raw-material-purchases` | ADMIN, ACCOUNTING | Capture supplier invoice |
| Create Return | POST | `/api/v1/purchasing/raw-material-purchases/returns` | ADMIN, ACCOUNTING | Record purchase return |
| Preview Return | POST | `/api/v1/purchasing/raw-material-purchases/returns/preview` | ADMIN, ACCOUNTING | Preview return |

### Supplier Settlements — Accounting Controller (`/api/v1/accounting/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Manual Settlement | POST | `/api/v1/accounting/settlements/suppliers` | ADMIN, ACCOUNTING | Manual payment allocation |
| Auto-Settle | POST | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | ADMIN, ACCOUNTING | Auto-allocate payments |
| Supplier Statement | GET | `/api/v1/accounting/statements/suppliers/{supplierId}` | ADMIN, ACCOUNTING | View statement |
| Supplier Aging | GET | `/api/v1/accounting/aging/suppliers/{supplierId}` | ADMIN, ACCOUNTING | View aging |

---

## 3. Preconditions

### Supplier Creation Preconditions

1. **Supplier name required** — non-empty name
2. **Supplier code unique** — per company, auto-prefixed with `AP-`
3. **Payment terms required** — valid payment terms code
4. **GST details optional but validated** — if provided, must be valid format

### Purchase Order Preconditions

1. **Supplier must be ACTIVE** — approved and activated before PO creation
2. **Line items valid** — raw materials must exist
3. **Order number unique** — enforced at database level (no idempotency key)
4. **Quantities positive** — greater than zero

### GRN Preconditions

1. **PO must be APPROVED** — only against approved purchase orders
2. **Idempotency key required** — `Idempotency-Key` header or body field required
3. **Quantity not over-received** — quantity cannot exceed open PO line quantity
4. **Period open** — accounting period must be open
5. **Supplier active** — supplier must be in ACTIVE status

### Purchase Invoice Preconditions

1. **GRN must exist** — must reference existing GRN lines
2. **GRN not already fully invoiced** — quantity available for invoicing
3. **Supplier matches** — GRN and invoice must be for the same supplier
4. **Quantities valid** — cannot exceed received quantities
5. **Period open** — accounting period must be open

### Settlement Preconditions

1. **Supplier exists** — valid supplier ID
2. **Amount positive** — greater than zero
3. **Settlement date valid** — not future date for auto-settle

---

## 4. Lifecycle

### 4.1 Supplier Onboarding Lifecycle

```
[Start] → Validate supplier data → Generate supplier code → 
Create payable account (AP-{CODE}) → [End: Supplier PENDING]
```

**Key behaviors:**
- Supplier code prefixed with `AP-` for payable account
- Account auto-created in chart of accounts
- Code collision resolved by appending numeric suffix

### 4.2 Supplier Approval Lifecycle

```
[PENDING] → Validate required fields → [APPROVED] → [End: Awaiting activation]

[APPROVED] → Activate → [ACTIVE] → [End: Ready for purchasing]
```

**Key behaviors:**
- Requires payment terms, GST, bank details
- Activation required before PO creation

### 4.3 Purchase Order Lifecycle

```
[Start] → Validate supplier ACTIVE → Validate line items → 
Generate order number → Save order → [End: DRAFT]

[DRAFT] → Approve → [APPROVED] → [End: Ready for GRN]

[APPROVED] → Receive (partial) → [PARTIALLY_RECEIVED]
[APPROVED] → Receive (full) → [FULLY_RECEIVED]

[FULLY_RECEIVED] → Invoice → [INVOICED]
[INVOICED] → Close → [CLOSED]
```

**Key behaviors:**
- PO creation has no idempotency — relies on order number uniqueness
- Void allowed only in DRAFT or APPROVED status
- Close allowed only in INVOICED status

### 4.4 Goods Receipt (GRN) — Stock Truth Boundary

```
[Start] → Validate PO APPROVED → Validate quantities → 
Generate batch code → Create RawMaterialBatch → 
Update PO status → [End: PARTIAL or RECEIVED]
```

**Key behaviors:**
- GRN creates `RawMaterialBatch` in inventory module
- Batch code format: `RM-{SKU}-{YYYYMM}-{SEQ}`
- Status determined at creation: PARTIAL if any line partial, RECEIVED if all full
- **Does NOT create AP entries** — stock truth only

### 4.5 Purchase Invoice — AP Truth Boundary

```
[Start] → Validate GRN exists → Validate quantities → 
Create journal entry → Update GRN status → [End: INVOICED]
```

**Key behaviors:**
- Links to GRN lines for quantity validation
- Creates journal entry: DR Raw Material / CR Supplier Payable (AP)
- GST input credit journal created if applicable
- Status set to INVOICED on GRN lines

### 4.6 Purchase Return Lifecycle

```
[Start] → Validate original invoice → Validate return quantity → 
Create reversal journal → Reduce batch quantity → [End: Return recorded]
```

**Key behaviors:**
- Return quantity cannot exceed outstanding payable
- Creates reversal journal: DR Supplier Payable / CR Purchase Return
- Reverses input credit for returned amount

### 4.7 Supplier Settlement Lifecycle

```
[Start] → Validate supplier → Validate amount → 
Allocate to open invoices (FIFO) → Create payment journal → 
[End: Supplier paid]
```

**Key behaviors:**
- Manual: specific invoice allocation
- Auto-settle: FIFO allocation of oldest open items
- Creates settlement journal entries

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Supplier Active** — Supplier moved to ACTIVE status, ready for purchasing
2. **PO Approved** — Purchase order approved, ready for goods receipt
3. **GRN Created** — Goods received, raw material batches created in inventory
4. **Invoice Captured** — Purchase invoice created, AP journal posted
5. **Settled** — Payment recorded against AP, supplier balance reduced

### Current Limitations

1. **PO creation has no idempotency** — Clients must handle duplicate order number errors
2. **Purchase invoice has no idempotency** — Clients must handle duplicate invoice number errors
3. **GRN quantity limits line-level only** — No PO-level over-receipt validation
4. **No partial GRN against single PO line** — Cannot split a GRN line across multiple PO lines
5. **Purchase return stock reversal simple** — Direct quantity reduction, no batch traceability
6. **No automated dunning for suppliers** — Unlike dealer dunning
7. **Supplier balance ledger-derived** — Not cached on supplier entity

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/suppliers` | `SupplierController` | Supplier creation entry |
| `POST /api/v1/purchasing/purchase-orders` | `PurchasingWorkflowController` | PO creation |
| `POST /api/v1/purchasing/goods-receipts` | `PurchasingWorkflowController` | GRN (stock truth boundary) |
| `POST /api/v1/purchasing/raw-material-purchases` | `RawMaterialPurchaseController` | Purchase invoice (AP truth) |
| `POST /api/v1/accounting/suppliers/{id}/auto-settle` | Accounting | Auto-settlement |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `X-Idempotency-Key` header on GRN | Rejected (400 error) | Use `Idempotency-Key` |
| PO creation idempotency | Not supported | Rely on order number uniqueness |
| Purchase invoice idempotency | Not supported | Rely on invoice number uniqueness |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `inventory` | RawMaterialBatch creation, batch quantity reduction on return | Write (batch create), Write (reduce) |
| `accounting` | AP journal posting, supplier ledger updates, settlement journals | Write |

## 8. Event/Listener Boundaries

The P2P flow intersects with the inventory→accounting event bridge at the GRN (stock truth) boundary:

| Event | Listener | Phase | Effect on P2P |
| --- | --- | --- | --- |
| `InventoryMovementEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | When GRN creates a `RawMaterialBatch`, this event triggers automatic inventory valuation journal entries in accounting if `erp.inventory.accounting.events.enabled=true` (default: true). This is a material hidden coupling: if the toggle is disabled, inventory movements silently skip accounting side effects. |
| `InventoryValuationChangedEvent` | `InventoryAccountingEventListener` | `AFTER_COMMIT` | Triggers accounting entries for raw material valuation changes. |

**Key boundary note:** The GRN is the stock truth boundary—`RawMaterialBatch` is created in the inventory module. The `InventoryAccountingEventListener` (when enabled) automatically creates corresponding accounting entries for raw material valuation. This bridge is conditional on the feature flag `erp.inventory.accounting.events.enabled`. See [orchestrator.md](../modules/orchestrator.md) for the full event bridge map and configuration-guarded risks.

---

## 9. Security Considerations

- **RBAC enforcement** — Admin and Accounting roles for purchasing operations
- **Company scoping** — All operations scoped to tenant
- **Supplier isolation** — Data scoped by company, no cross-tenant access

---

## 10. Related Documentation

- [docs/modules/purchasing.md](../modules/purchasing.md) — Purchasing module canonical packet
- [docs/modules/inventory.md](../modules/inventory.md) — Inventory for stock truth (RawMaterialBatch)
- [docs/modules/core-idempotency.md](../modules/core-idempotency.md) — Idempotency helpers
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/frontend-handoff-commercial.md](../frontend-handoff-commercial.md) — Commercial frontend handoff (P2P payloads, supplier management)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (PO idempotency, GRN headers)

### Relevant ADRs
- [ADR-003-outbox-pattern-for-cross-module-events.md](../adrs/ADR-003-outbox-pattern-for-cross-module-events.md) — Cross-module event bridges (P2P uses inventory→accounting event bridges for GRN→journal entry)
- [ADR-004-layered-audit-surfaces.md](../adrs/ADR-004-layered-audit-surfaces.md) — Audit trail layers (AP journal posting and settlement create audit markers)

---

## 11. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| PO idempotency | Relies on order number uniqueness only. No additional idempotency protection. |
| Invoice idempotency | Relies on invoice number uniqueness only. No additional idempotency protection. |
| Supplier dunning | Not implemented. Manual aging review required. |
| Partial GRN per line | Not supported. One GRN line per PO line. |
