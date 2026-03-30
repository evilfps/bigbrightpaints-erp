# Order-to-Cash (O2C) Flow

Last reviewed: 2026-03-30

This packet documents the **order-to-cash flow**: the canonical commercial lifecycle from dealer onboarding through sales order creation, confirmation, dispatch, invoicing, and settlement. It covers credit management, inventory reservation, dispatch execution, invoice generation, and the accounting boundary for AR (accounts receivable).

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Dealer** | Customer entity, accesses dealer-portal for self-service | `/api/v1/dealer-portal/**` |
| **Sales User** | Creates and manages orders | `ROLE_SALES`, `ROLE_ADMIN` |
| **Admin** | Full sales lifecycle access, credit management | `ROLE_ADMIN` |
| **Accounting** | Credit limit approvals, financial oversight | `ROLE_ACCOUNTING` |
| **Factory** | Views orders for dispatch preparation | `ROLE_FACTORY` |
| **Operational Dispatch** | Confirms dispatch for fulfillment | `OPERATIONAL_DISPATCH` (custom predicate) |

---

## 2. Entrypoints

### Dealer Self-Service — `DealerPortalController` (`/api/v1/dealer-portal/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dashboard | GET | `/api/v1/dealer-portal/dashboard` | Dealer | View balance, credit, aging summary |
| Ledger | GET | `/api/v1/dealer-portal/ledger` | Dealer | View transaction history |
| Invoices | GET | `/api/v1/dealer-portal/invoices` | Dealer | View outstanding invoices |
| Invoice PDF | GET | `/api/v1/dealer-portal/invoices/{id}/pdf` | Dealer | Download invoice |
| Aging | GET | `/api/v1/dealer-portal/aging` | Dealer | View aging buckets |
| Orders | GET | `/api/v1/dealer-portal/orders` | Dealer | View own orders |
| Credit Request | POST | `/api/v1/dealer-portal/credit-limit-requests` | Dealer | Submit credit limit increase |
| Support Ticket | POST | `/api/v1/dealer-portal/support/tickets` | Dealer | Create support ticket |

### Internal Sales — `SalesController` (`/api/v1/sales/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Create Order | POST | `/api/v1/sales/orders` | SALES, ADMIN | Create sales order (idempotent) |
| List Orders | GET | `/api/v1/sales/orders` | ADMIN, SALES, FACTORY, ACCOUNTING | List orders (paginated) |
| Search Orders | GET | `/api/v1/sales/orders/search` | ADMIN, SALES, FACTORY, ACCOUNTING | Search with filters |
| Update Order | PUT | `/api/v1/sales/orders/{id}` | SALES, ADMIN | Update draft order |
| Delete Order | DELETE | `/api/v1/sales/orders/{id}` | SALES, ADMIN | Delete draft order |
| Confirm Order | POST | `/api/v1/sales/orders/{id}/confirm` | SALES, ADMIN | Confirm order (credit check + stock validation) |
| Cancel Order | POST | `/api/v1/sales/orders/{id}/cancel` | SALES, ADMIN | Cancel order (requires reason) |
| Update Status | PATCH | `/api/v1/sales/orders/{id}/status` | SALES, ADMIN | Manual status (ON_HOLD, REJECTED, CLOSED) |
| Order Timeline | GET | `/api/v1/sales/orders/{id}/timeline` | ADMIN, SALES, FACTORY, ACCOUNTING | Status history |

### Dealer Management — `DealerController` (`/api/v1/dealers/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Create Dealer | POST | `/api/v1/dealers` | ADMIN, SALES, ACCOUNTING | Create dealer |
| List Dealers | GET | `/api/v1/dealers` | ADMIN, SALES, ACCOUNTING | List dealers |
| Update Dealer | PUT | `/api/v1/dealers/{dealerId}` | ADMIN, SALES, ACCOUNTING | Update dealer |
| Dunning Hold | POST | `/api/v1/dealers/{dealerId}/dunning/hold` | ADMIN, SALES, ACCOUNTING | Evaluate dunning hold |

### Credit Management — `CreditLimitRequestController`, `CreditLimitOverrideController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Create Credit Request | POST | `/api/v1/credit/limit-requests` | SALES, ADMIN | Credit limit increase request |
| List Credit Requests | GET | `/api/v1/credit/limit-requests` | ADMIN, SALES | List requests |
| Approve Credit Request | POST | `/api/v1/credit/limit-requests/{id}/approve` | ADMIN, ACCOUNTING | Approve (increments limit) |
| Reject Credit Request | POST | `/api/v1/credit/limit-requests/{id}/reject` | ADMIN, ACCOUNTING | Reject request |
| Create Override Request | POST | `/api/v1/credit/override-requests` | ADMIN, FACTORY, SALES | Per-dispatch override |
| List Override Requests | GET | `/api/v1/credit/override-requests` | ADMIN, ACCOUNTING | List overrides |
| Approve Override | POST | `/api/v1/credit/override-requests/{id}/approve` | ADMIN, ACCOUNTING | Approve override |
| Reject Override | POST | `/api/v1/credit/override-requests/{id}/reject` | ADMIN, ACCOUNTING | Reject override |

### Dispatch (Inventory Module) — `DispatchController` (`/api/v1/dispatch/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Confirm Dispatch | POST | `/api/v1/dispatch/confirm` | OPERATIONAL_DISPATCH | Confirm dispatch (triggers AR, COGS, invoice) |

---

## 3. Preconditions

### Order Creation Preconditions

1. **Dealer must exist and be active** — valid dealer ID with status not INACTIVE
2. **Dealer must not be on dunning hold** — dealer status must not be ON_HOLD
3. **All order items must reference valid products** — product exists and is sales-ready
4. **Order number must be unique** — per company, enforced at database level
5. **Idempotency key recommended** — `Idempotency-Key` or `X-Idempotency-Key` header accepted

### Order Confirmation Preconditions

1. **Order must be in valid status** — DRAFT, RESERVED, or PENDING_PRODUCTION
2. **Credit limit must allow** — credit used (outstanding + pending) < credit limit for credit-mode orders
3. **Stock must be available** — RESERVED status or explicit override request exists
4. **No dunning hold on dealer** — dealer status must not be ON_HOLD

### Dispatch Confirmation Preconditions

1. **Order must be CONFIRMED** — status must be CONFIRMED
2. **Packaging slip must exist** — valid slip from factory
3. **Credit check passes** — unless valid override request approved
4. **Inventory available** — sufficient finished goods for the slip
5. **Period must be open** — accounting period is not closed/locked

### Credit Limit Increase Request Preconditions

1. **Dealer must exist** — valid dealer ID
2. **Amount must be positive** — greater than zero
3. **Reason required** — text explanation

---

## 4. Lifecycle

### 4.1 Dealer Onboarding Lifecycle

```
[Start] → Validate dealer data → Generate dealer code → 
Create receivable account → Create portal user account → 
[End: Dealer provisioned]
```

**Key behaviors:**
- Dealer code generated per company
- Receivable account auto-created (`AR-{DEALER_CODE}`)
- Portal user account created with `ROLE_DEALER`
- Initial credit limit set at creation time

### 4.2 Order Creation Lifecycle

```
[Start] → Validate dealer → Validate products → Validate GST → 
Validate payment mode → [Check credit (credit mode)] → 
Generate order number → Save order → [Optional: Reserve stock] → 
[End: Order in DRAFT/RESERVED/PENDING_PRODUCTION]
```

**Key behaviors:**
- Order number format: configurable, unique per company
- Stock reservation occurs if all items available → RESERVED
- Stock shortage triggers → PENDING_PRODUCTION
- Credit check fires for credit-mode orders
- Idempotency key supported (canonical + legacy resolution)

### 4.3 Order Confirmation Lifecycle

```
[Start] → Validate order status → [Re-validate dealer not on hold] → 
[Re-check credit] → Validate stock → Update status to CONFIRMED → 
[End: Order ready for dispatch]
```

**Key behaviors:**
- Credit re-checked at confirmation time
- Stock validated (not already reserved by another order)
- Triggers inventory reservation for the confirmed order

### 4.4 Dispatch Confirmation Lifecycle (Fulfillment)

```
[Start] → Validate order CONFIRMED → Validate slip → 
[Check credit override] → Execute inventory dispatch (stock reduction, FIFO/LIFO/WAC) → 
Post COGS journal → Post AR/Revenue journal → Issue invoice → 
Update dealer balance → Set order markers → Update status → [End: DISPATCHED + INVOICED]
```

**Key behaviors:**
- Runs under `SERIALIZABLE` isolation
- Inventory dispatch: reduces stock, tracks batch, records cost (FIFO/LIFO/WAC)
- COGS journal: DR COGS / CR Inventory
- AR/Revenue journal: DR AR / CR Revenue / CR GST Payable
- Invoice auto-generated, linked to slip and order
- Order status moves to DISPATCHED/INVOICED

### 4.5 Settlement Lifecycle

```
[Start] → Record payment → Allocate against invoice(s) → 
Update invoice status (PARTIAL/PAID) → Update dealer balance → 
[End: Payment applied]
```

**Key behaviors:**
- Settlement handled by accounting module via `SettlementService`
- Manual recording required — no automatic reconciliation
- Invoice status: PAID when fully settled, PARTIAL when partially paid

### 4.6 Order Closure Lifecycle

```
[Start] → Verify SETTLED → Update status to CLOSED → [End: Order complete]
```

**Key behaviors:**
- Manual status update required (`PATCH /sales/orders/{id}/status` with CLOSED)
- Only allowed after SETTLED status

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Order Created** — Sales order exists in DRAFT, RESERVED, or PENDING_PRODUCTION status
2. **Order Confirmed** — Order moves to CONFIRMED, inventory reserved
3. **Dispatched & Invoiced** — Order moved to DISPATCHED/INVOICED, invoice generated, AR posted
4. **Settled** — Payment recorded against invoice, invoice status PAID, dealer balance reduced
5. **Closed** — Order manually moved to CLOSED (optional terminal state)

### Current Limitations

1. **No automated settlement** — Payment receipt and allocation happens through accounting workflows, not automatically through sales module. Orders move to SETTLED externally.

2. **No automated order closure** — After settlement, orders must be manually closed via `PATCH /sales/orders/{id}/status` with CLOSED.

3. **No automatic payment reconciliation** — Manual settlement required; no webhook or scheduled matching.

4. **Single domain event** — Only `SalesOrderCreatedEvent` is published. Confirmation, dispatch, cancellation do not emit domain events.

5. **No shipment tracking integration** — Transport metadata captured at dispatch but no carrier integration.

6. **Dunning is rudimentary** — Scheduled task evaluates only 45+ day aging bucket; no graduated escalation.

7. **No partial fulfillment by default** — Orders with stock shortages fail unless `allowPartialFulfillment` explicitly enabled.

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `POST /api/v1/sales/orders` | `SalesController` | Primary order creation entry |
| `POST /api/v1/sales/orders/{id}/confirm` | `SalesController` | Order confirmation triggers credit/stock validation |
| `POST /api/v1/dispatch/confirm` | `DispatchController` | Dispatch triggers AR, COGS, invoice via SalesDispatchReconciliationService |
| `POST /api/v1/credit/limit-requests` | `CreditLimitRequestController` | Durable credit limit increase |
| `POST /api/v1/dealer-portal/credit-limit-requests` | `DealerPortalController` | Dealer self-service credit request |

### Non-Canonical / Deprecated Paths

| Path | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/sales/dealers` | Deprecated (frontend alias) | Use `/api/v1/dealers` |
| Legacy idempotency key resolution | Deprecated | Use canonical `Idempotency-Key` header |
| Legacy order statuses (BOOKED, SHIPPED, etc.) | Legacy compatibility | Use canonical statuses only |
| `POST /api/v1/sales/promotions` | Not integrated | Sales targets only |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `inventory` | Stock reservation, dispatch execution, batch tracking, COGS posting | Write (dispatch), Write (COGS), Read |
| `accounting` | AR/Revenue journal posting, dealer ledger updates, settlements | Write |
| `invoice` | Invoice generation during dispatch | Write (trigger via sales) |
| `auth` | Dealer portal user provisioning | Write (creates UserAccount) |
| `factory` | Packaging slip read, dispatch metadata | Read |

---

## 8. Event/Listener Boundaries

The O2C flow publishes domain events that can trigger downstream processing:

| Event | Listener | Phase | Effect on O2C |
| --- | --- | --- | --- |
| `SalesOrderCreatedEvent` | `OrderAutoApprovalListener` | `AFTER_COMMIT` | When auto-approval is enabled (`SystemSettingsService.isAutoApprovalEnabled()`), orders are automatically approved after creation. This bypasses manual approval and immediately triggers inventory reservation. If disabled, orders stay in DRAFT until manually confirmed. |
| `SalesOrderCreatedEvent` | `SalesOrderCreatedAuditListener` | `AFTER_COMMIT` | Creates audit trail marker for order creation, enabling audit verification during period close. |

**Key boundary note:** Only `SalesOrderCreatedEvent` is published. Order confirmation, dispatch, and cancellation do NOT emit domain events—they operate via direct service calls. This means downstream modules (inventory, accounting, invoice) are triggered synchronously during the HTTP request, not asynchronously via events. The `OrderAutoApprovalListener` is the only material event listener that affects O2C behavior, and it is conditional on a system setting.

---

## 9. Security Considerations

- **RBAC enforcement** — Sales vs Dealer vs Admin vs Accounting roles enforced per endpoint
- **Dealer isolation** — `/api/v1/dealer-portal/**` auto-scoped to authenticated dealer via JWT claim, no cross-dealer access
- **Credit checks** — Credit limit enforcement at order creation and confirmation
- **Company scoping** — All operations scoped to tenant via `companyCode` from JWT
- **OPERATIONAL_DISPATCH role** — Dispatch confirmation requires `OPERATIONAL_DISPATCH` predicate, not generic role

---

## 10. Related Documentation

- [docs/modules/sales.md](../modules/sales.md) — Sales module canonical packet
- [docs/modules/invoice.md](../modules/invoice.md) — Invoice module for invoice lifecycle
- [docs/modules/inventory.md](../modules/inventory.md) — Inventory for stock and dispatch truth
- [docs/modules/admin-portal-rbac.md](../modules/admin-portal-rbac.md) — Host ownership and role matrices
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/frontend-handoff-commercial.md](../frontend-handoff-commercial.md) — Commercial frontend handoff (sales, O2C, payloads, RBAC)
- [docs/adrs/ADR-006-portal-and-host-boundary-separation.md](../adrs/ADR-006-portal-and-host-boundary-separation.md) — Portal/host boundary separation (enforces `/api/v1/dealer-portal/**` vs `/api/v1/portal/**` isolation)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy paths, replacement notes)

---

## 11. Open Decisions

| Decision | Status | Notes |
| --- | --- | --- |
| Automated settlement | Not implemented | Manual settlement via accounting |
| Automated order closure | Not implemented | Manual closure required |
| Shipment tracking integration | Not implemented | Transport metadata only |
| Event-driven order confirmation | Not implemented | Direct service call pattern |
| Partial fulfillment | Not default | Requires explicit flag |
