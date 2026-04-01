# Purchasing / Procure-to-Pay (P2P) Module Packet

Last reviewed: 2026-03-30

This packet documents the purchasing module, which owns **procure-to-pay truth** for the ERP. It covers supplier lifecycle, purchase order lifecycle, goods receipt (GRN), purchase invoice capture, purchase returns, supplier settlements, and the AP (accounts payable) boundary with accounting.

**Critical boundary note:** This module maintains two distinct truth surfaces:
- **Stock truth** — GRN creates raw material batches in the inventory module (documented in [inventory.md](inventory.md))
- **AP truth** — Purchase invoices create payable entries in the accounting module (documented in the accounting AGENTS.md)

These boundaries must remain explicit. GRN stock intake does not automatically create AP entries; AP truth is only established when a purchase invoice is captured against a received GRN.

---

## 1. Module Ownership

| Aspect | Owner |
| --- | --- |
| Package | `com.bigbrightpaints.erp.modules.purchasing` |
| Controllers | `SupplierController`, `PurchasingWorkflowController`, `RawMaterialPurchaseController` |
| Primary services | `SupplierService`, `PurchasingService`, `GoodsReceiptService`, `PurchaseInvoiceEngine`, `PurchaseReturnService`, `SupplierApprovalPolicy` |
| Domain entities | `Supplier`, `PurchaseOrder`, `PurchaseOrderLine`, `GoodsReceipt`, `GoodsReceiptLine`, `RawMaterialPurchase`, `RawMaterialPurchaseLine`, `PurchaseOrderStatusHistory` |
| Domain enums | `SupplierStatus` (PENDING, APPROVED, ACTIVE, SUSPENDED), `PurchaseOrderStatus` (DRAFT, APPROVED, PARTIALLY_RECEIVED, FULLY_RECEIVED, INVOICED, CLOSED, VOID), `GoodsReceiptStatus` (PARTIAL, RECEIVED, INVOICED) |
| DTO families | SupplierRequest/Response, PurchaseOrderRequest/Response, GoodsReceiptRequest/Response, RawMaterialPurchaseRequest/Response, PurchaseReturnRequest |
| Events | None currently published (cross-module coordination via direct service calls) |
| Repositories | `SupplierRepository`, `PurchaseOrderRepository`, `GoodsReceiptRepository`, `RawMaterialPurchaseRepository`, `PurchaseOrderStatusHistoryRepository` |

---

## 2. Canonical Host and Route Map

### Supplier Management — `SupplierController` (`/api/v1/suppliers/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/suppliers` | GET | ADMIN, ACCOUNTING, FACTORY | List all suppliers |
| `/api/v1/suppliers/{id}` | GET | ADMIN, ACCOUNTING, FACTORY | Get supplier detail |
| `/api/v1/suppliers` | POST | ADMIN, ACCOUNTING | Create supplier |
| `/api/v1/suppliers/{id}` | PUT | ADMIN, ACCOUNTING | Update supplier |
| `/api/v1/suppliers/{id}/approve` | POST | ADMIN, ACCOUNTING | Approve supplier |
| `/api/v1/suppliers/{id}/activate` | POST | ADMIN, ACCOUNTING | Activate approved supplier |
| `/api/v1/suppliers/{id}/suspend` | POST | ADMIN, ACCOUNTING | Suspend active supplier |

### Purchase Orders — `PurchasingWorkflowController` (`/api/v1/purchasing/purchase-orders/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/purchasing/purchase-orders` | GET | ADMIN, ACCOUNTING | List purchase orders |
| `/api/v1/purchasing/purchase-orders/{id}` | GET | ADMIN, ACCOUNTING | Get PO detail |
| `/api/v1/purchasing/purchase-orders` | POST | ADMIN, ACCOUNTING | Create PO |
| `/api/v1/purchasing/purchase-orders/{id}/approve` | POST | ADMIN, ACCOUNTING | Approve PO |
| `/api/v1/purchasing/purchase-orders/{id}/void` | POST | ADMIN, ACCOUNTING | Void PO |
| `/api/v1/purchasing/purchase-orders/{id}/close` | POST | ADMIN, ACCOUNTING | Close PO |
| `/api/v1/purchasing/purchase-orders/{id}/timeline` | GET | ADMIN, ACCOUNTING | PO status history |

### Goods Receipt (GRN) — `PurchasingWorkflowController` (`/api/v1/purchasing/goods-receipts/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/purchasing/goods-receipts` | GET | ADMIN, ACCOUNTING | List GRNs |
| `/api/v1/purchasing/goods-receipts/{id}` | GET | ADMIN, ACCOUNTING | Get GRN detail |
| `/api/v1/purchasing/goods-receipts` | POST | ADMIN, ACCOUNTING | Create GRN (requires Idempotency-Key) |

### Purchase Invoices (Raw Material Purchases) — `RawMaterialPurchaseController` (`/api/v1/purchasing/raw-material-purchases/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/purchasing/raw-material-purchases` | GET | ADMIN, ACCOUNTING | List purchase invoices |
| `/api/v1/purchasing/raw-material-purchases/{id}` | GET | ADMIN, ACCOUNTING | Get invoice detail |
| `/api/v1/purchasing/raw-material-purchases` | POST | ADMIN, ACCOUNTING | Capture supplier invoice |
| `/api/v1/purchasing/raw-material-purchases/returns` | POST | ADMIN, ACCOUNTING | Record purchase return |
| `/api/v1/purchasing/raw-material-purchases/returns/preview` | POST | ADMIN, ACCOUNTING | Preview return before recording |

### Supplier Settlements — Accounting Controller (`/api/v1/accounting/settlements/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/accounting/settlements/suppliers` | POST | ADMIN, ACCOUNTING | Manual supplier settlement |
| `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | POST | ADMIN, ACCOUNTING | Auto-settle supplier |

### Supplier Statements and Aging — Accounting Controller (`/api/v1/accounting/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/accounting/statements/suppliers/{supplierId}` | GET | ADMIN, ACCOUNTING | Supplier statement |
| `/api/v1/accounting/aging/suppliers/{supplierId}` | GET | ADMIN, ACCOUNTING | Supplier aging report |
| `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | GET | ADMIN | Supplier statement PDF |
| `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | GET | ADMIN | Supplier aging PDF |

---

## 3. Supplier Lifecycle

### Supplier Status Flow

```
PENDING → APPROVED → ACTIVE
    │                    │
    │                    ↓
    │                 SUSPENDED (from ACTIVE)
    │                    │
    └────────────────────┘ (reactivation path)
```

| Status | Meaning | How Reached |
| --- | --- | --- |
| `PENDING` | Newly created, awaiting approval | Initial state on supplier creation |
| `APPROVED` | Approved but not yet active | POST /suppliers/{id}/approve |
| `ACTIVE` | Fully operational for purchasing | POST /suppliers/{id}/activate |
| `SUSPENDED` | Temporarily blocked from purchasing | POST /suppliers/{id}/suspend |

### Supplier Creation

`POST /api/v1/suppliers` creates a supplier in `PENDING` status:

- Requires: supplier name, code (unique per company), payment terms, GST details, bank details
- Creates supplier-linked payable account (`AP-{SUPPLIER_CODE}`) in the chart of accounts
- **Code collision handling**: If `AP-{SUPPLIER_CODE}` already exists, the system auto-resolves by appending a numeric suffix (`AP-{CODE}-1`, `AP-{CODE}-2`, etc.) until a free code is found

### Supplier Approval

`POST /api/v1/suppliers/{id}/approve` transitions from `PENDING` → `APPROVED`:

- Requires ADMIN or ACCOUNTING role
- Validates supplier has all required fields (payment terms, GST, bank details)
- After approval, the supplier can be activated

### Supplier Activation

`POST /api/v1/suppliers/{id}/activate` transitions from `APPROVED` → `ACTIVE` (also allows `SUSPENDED` → `ACTIVE` for reactivation):

- Requires ADMIN or ACCOUNTING role
- After activation, the supplier can be used in purchase orders

### Supplier Suspension

`POST /api/v1/suppliers/{id}/suspend` transitions from `ACTIVE` → `SUSPENDED`:

- Requires ADMIN or ACCOUNTING role
- Blocks new purchase orders against the supplier
- Existing POs and GRNs remain unaffected
- Suspension is reversible (can reactivate via `/activate`)

---

## 4. Purchase Order (PO) Lifecycle

### PO Status Flow

```
DRAFT ──approve──▸ APPROVED ──receive(partial)──▸ PARTIALLY_RECEIVED
  │void                            │void                    │receive(all)
  ▼                                ▼                        ▼
 VOID                            VOID                  FULLY_RECEIVED
                                                    ──invoice──▸ INVOICED
                                                                          │close
                                                                          ▼
                                                                    CLOSED (terminal)
```

| Status | Meaning | How Reached |
| --- | --- | --- |
| `DRAFT` | Newly created, not yet approved | Initial state on PO creation |
| `APPROVED` | Approved and awaiting goods receipt | POST /purchase-orders/{id}/approve |
| `PARTIALLY_RECEIVED` | Some line items received via GRN | GRN created against PO lines |
| `FULLY_RECEIVED` | All line items received via GRN | Final GRN against PO |
| `INVOICED` | Purchase invoice captured against GRN | Purchase invoice posted |
| `CLOSED` | Physically complete, no further action | POST /purchase-orders/{id}/close |
| `VOID` | Cancelled before completion | POST /purchase-orders/{id}/void |

### PO Creation

`POST /api/v1/purchasing/purchase-orders` creates a PO in `DRAFT` status:

- Requires: supplierId, order number (unique per company), line items (raw material, quantity, unit rate)
- **No idempotency key** — PO creation relies on order number uniqueness for duplicate detection
- Order number uniqueness enforced at database level; duplicate insert throws `DATA_001` error

### PO Approval

`POST /api/v1/purchasing/purchase-orders/{id}/approve` transitions from `DRAFT` → `APPROVED`:

- Requires ADMIN or ACCOUNTING role
- Validates: supplier is in `ACTIVE` status, all line items have valid raw materials
- After approval, GRN can be created against the PO

### PO Void

`POST /api/v1/purchasing/purchase-orders/{id}/void` transitions to `VOID`:

- Requires ADMIN or ACCOUNTING role
- Requires void reason (code + text)
- **Constraint**: Can only void POs in `DRAFT` or `APPROVED` status (not after GRN creation)
- Cannot be undone

### PO Close

`POST /api/v1/purchasing/purchase-orders/{id}/close` transitions to `CLOSED`:

- Requires ADMIN or ACCOUNTING role
- **Constraint**: Can only close POs in `INVOICED` status
- Marks the PO as physically complete

### PO Timeline

`GET /api/v1/purchasing/purchase-orders/{id}/timeline` returns the status history:

- Returns `PurchaseOrderStatusHistory` entries with timestamp, fromStatus, toStatus, changedBy, reason

---

## 5. Goods Receipt (GRN) — Stock Truth Boundary

### GRN Status Flow

```
[CREATION TIME]──────►[INVOICE TIME]
      │                       │
      ▼                       ▼
 PARTIAL/RECEIVED ─────────► INVOICED
```

**Important clarification:** The `PARTIAL` vs `RECEIVED` status is determined **at GRN creation time** based on fulfillment level, not as a sequential transition.

| Status | Meaning | How Reached |
| --- | --- | --- |
| `PARTIAL` | GRN created with partial quantity relative to PO line(s) | Set at GRN creation when `fullyReceived = false` |
| `RECEIVED` | GRN created with full quantity for all PO line(s) | Set at GRN creation when `fullyReceived = true` |
| `INVOICED` | Purchase invoice has been captured against this GRN | Applied when purchase invoice is posted (not a sequential transition from RECEIVED) |

**Status determination at creation:** The `GoodsReceiptService` evaluates whether the GRN fulfills all purchase order lines at creation time:
- If the GRN quantity for any line is less than the remaining PO quantity → status = `PARTIAL`
- If the GRN quantity equals the remaining PO quantity for all lines → status = `RECEIVED`

The `INVOICED` status is applied later when a purchase invoice is captured against the GRN lines — this is not a sequential transition from `RECEIVED`, but rather an independent status update applied when invoicing occurs.

### GRN Creation

`POST /api/v1/purchasing/goods-receipts` creates a GRN:

**Idempotency Requirements:**
- **Requires** `Idempotency-Key` header (or body field `idempotencyKey`)
- **Explicitly rejects** `X-Idempotency-Key` header with 400 error
- Key resolution uses `IdempotencyHeaderUtils.resolveBodyOrHeaderKey()`
- A key from either source is required (stricter than sales order creation)

**Link to Purchase Order:**
- GRN must reference a line item from an `APPROVED` purchase order
- Quantity received must not exceed the open (unreceived) quantity on the PO line
- Multiple GRNs can be created against the same PO line (partial receiving)

**Stock Truth Creation:**
- GRN creates `RawMaterialBatch` entities in the inventory module
- Batch fields: raw material, batch code (generated), quantity, cost per unit, supplier, received date, manufactured date, expiry date, source = PURCHASE
- Batch number generation: `RM-{SKU}-{YYYYMM}-{SEQ}` per company, per SKU, per month

**Accounting Integration:**
- GRN creation validates the accounting period is open via `AccountingPeriodService.requireOpenPeriod()`
- GRN does **not** create AP entries — AP truth is only established when a purchase invoice is captured
- This is the explicit stock-truth vs AP-truth boundary

### Key Distinction: GRN Stock Truth vs Purchase Invoice AP Truth

| Aspect | GRN | Purchase Invoice |
| --- | --- | --- |
| **Truth type** | Stock truth | AP (accounts payable) truth |
| **What it creates** | RawMaterialBatch in inventory | JournalEntry with debit (raw material/expense) and credit (AP) |
| **When created** | Goods received at warehouse | Supplier invoice captured in system |
| **Module ownership** | Purchasing triggers, inventory owns batches | Purchasing triggers, accounting owns journal |
| **Does it auto-create the other?** | No — invoice must be captured separately | No — GRN must exist first to link against |

The GRN → purchase invoice relationship is one-to-many: a single GRN can be invoiced partially across multiple invoices, or a single invoice can reference multiple GRNs.

---

## 6. Purchase Invoice (Raw Material Purchase) Lifecycle

### Purchase Invoice Capture

`POST /api/v1/purchasing/raw-material-purchases` captures a supplier invoice:

- Requires: supplierId, invoice number, invoice date, line items (linked GRN lines, quantity, rate)
- Links each line to a specific GRN line
- Quantity captured cannot exceed quantity received in the referenced GRN

### Accounting Posting

`PurchaseInvoiceEngine.createPurchase()` performs:

1. **Validation**:
   - Supplier exists and is in `ACTIVE` status
   - All referenced GRN lines exist and belong to the same supplier
   - Quantities do not exceed received quantities
   - Accounting period is open

2. **Journal Entry Creation** (via `AccountingFacade.postPurchaseJournal`):
   - **Debit**: Raw material expense account or inventory valuation account
   - **Credit**: Supplier payable account (`AP-{SUPPLIER_CODE}`)
   - **Tax**: GST input credit journal (if applicable)
   - Journal entry linked to `RawMaterialPurchase`

3. **Status Updates**:
   - Referenced GRN lines marked as invoiced
   - PO status may advance to `PARTIALLY_RECEIVED` or `FULLY_RECEIVED`

### Purchase Invoice vs Sales Invoice

- **Sales invoices** are auto-generated during dispatch confirmation (in the sales module)
- **Purchase invoices** are manually captured by the accounts payable team
- Both create journal entries but in opposite directions (AR vs AP)

---

## 7. Purchase Returns

### Return Creation

`POST /api/v1/purchasing/raw-material-purchases/returns` records a purchase return:

- Requires: supplierId, purchase invoice reference, return lines (linked to original invoice lines), return quantity, return reason
- **Constraint**: Return quantity/value cannot exceed the outstanding payable for the supplier

### Return Preview

`POST /api/v1/purchasing/raw-material-purchases/returns/preview` shows the expected journal entry before committing:

- Calculates: return amount, tax reversal, updated payable balance
- Allows AP team to review before confirming

### Return Accounting

`PurchaseReturnService.recordPurchaseReturn()` performs:

1. **Validation**:
   - Original purchase invoice exists and is posted
   - Return quantity does not exceed original invoice quantity
   - Return does not exceed outstanding payable

2. **Journal Entry Creation** (via `AccountingFacade.postPurchaseReturn`):
   - **Debit**: Supplier payable account
   - **Credit**: Raw material expense or purchase return account
   - **Tax reversal**: Reverses input credit for the returned amount
   - Links reversal metadata to original purchase

3. **Stock Reversal** (via inventory):
   - Reduces raw material batch quantity
   - Records inventory movement with reference type `PURCHASE_RETURN`

---

## 8. Supplier Settlements

### Manual Settlement

`POST /api/v1/accounting/settlements/suppliers` records a manual supplier payment:

- Requires: supplierId, cash account, amount, settlement date, reference number, memo
- Allocates payment against open AP entries (purchase invoices)
- Creates settlement journal entry

### Auto-Settle

`POST /api/v1/accounting/suppliers/{supplierId}/auto-settle` automatically allocates payments:

- Uses canonical FIFO-style allocation (oldest open items first)
- Requires: amount, settlement date
- Creates settlement journal entries for each allocated invoice
- Supports idempotency via `Idempotency-Key` header

### Supplier Statements

`GET /api/v1/accounting/statements/suppliers/{supplierId}` returns:

- Statement period (from/to dates)
- Opening balance
- Line items: date, reference, debit (invoice), credit (payment/return), running balance
- Closing balance

### Supplier Aging

`GET /api/v1/accounting/aging/suppliers/{supplierId}` returns:

- Outstanding amount split into aging buckets (e.g., 0-30, 31-60, 61-90, 90+ days)
- Configurable bucket boundaries

---

## 9. Cross-Module Boundaries

| Boundary | Direction | Nature |
| --- | --- | --- |
| **Purchasing → Inventory** | Outbound | GRN creates RawMaterialBatch entities; purchase returns reduce batch quantity |
| **Purchasing → Accounting** | Outbound | Purchase invoice posts AP journal; purchase return posts reversal journal; settlements post payment journal |
| **Inventory → Purchasing** | Inbound | Raw material batches created by GRN; batch traceability available to purchasing |
| **Accounting → Purchasing** | Read | Supplier balance, aging, statements read from accounting ledger |

---

## 10. Idempotency Expectations

### GRN Idempotency

| Aspect | Behavior |
| --- | --- |
| **Key source** | `Idempotency-Key` header or body `idempotencyKey` field |
| **Key requirement** | Required — missing key throws 400 error |
| **Legacy header** | `X-Idempotency-Key` explicitly rejected with 400 error |
| **Resolution** | `IdempotencyHeaderUtils.resolveBodyOrHeaderKey()` |
| **Implementation** | `GoodsReceiptService` uses shared `IdempotencyReservationService` for reservation and race reconciliation |
| **Test coverage** | `TS_P2PGoodsReceiptIdempotencyTest`, `PurchasingWorkflowControllerTest` |

### PO Creation Idempotency

| Aspect | Behavior |
| --- | --- |
| **Idempotency key** | Not supported |
| **Duplicate detection** | Relies on purchase order number uniqueness (database unique constraint) |
| **Retry behavior** | Client must generate unique order number on retry, or handle `DATA_001` duplicate error |

### Purchase Invoice Idempotency

| Aspect | Behavior |
| --- | --- |
| **Idempotency key** | Not currently implemented |
| **Duplicate detection** | Relies on invoice number uniqueness per supplier |

### Auto-Settle Idempotency

| Aspect | Behavior |
| --- | --- |
| **Key source** | `Idempotency-Key` header |
| **Behavior** | Standard idempotency pattern via `SettlementService` |

---

## 11. Known Limitations

1. **PO creation has no idempotency key** — Clients must handle duplicate order number errors manually
2. **Purchase invoice has no idempotency key** — Clients must handle duplicate invoice number errors manually
3. **GRN quantity limits are line-level only** — No PO-level over-receipt validation across all lines
4. **No partial GRN against single PO line** — A GRN line must map to exactly one PO line (no splitting)
5. **Purchase return stock reversal is simple** — Returns reduce batch quantity directly; no batch-level traceability for returned items
6. **Supplier balance is ledger-derived** — `SupplierResponse.balance` is computed from accounting ledger, not cached on the supplier entity
7. **No supplier-specific payment terms validation** — Payment terms are stored but not enforced in the PO lifecycle
8. **No automated dunning for suppliers** — Unlike dealer dunning (45+ day evaluation), supplier aging does not trigger automated hold actions

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

---

## 12. Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](MODULE-INVENTORY.md) — module inventory
- [docs/modules/inventory.md](inventory.md) — stock truth boundary (RawMaterialBatch creation from GRN)
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/flows/procure-to-pay.md](../flows/procure-to-pay.md) — canonical procure-to-pay flow (behavioral entrypoint)
- [docs/workflows/purchase-to-pay.md](../workflows/purchase-to-pay.md) — historical operational workflow guide (superseded by this packet)
- [docs/developer/accounting-flows/03-purchasing-boundary.md](../developer/accounting-flows/03-purchasing-boundary.md) — internal truth-path documentation
- [docs/frontend-handoff-commercial.md](../frontend-handoff-commercial.md) — Commercial frontend handoff (P2P payloads, supplier management)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (PO idempotency, GRN headers)
