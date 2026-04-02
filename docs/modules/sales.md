# Sales / Order-to-Cash Module Packet

Last reviewed: 2026-04-02

This packet documents the sales module, which owns **commercial lifecycle truth** for the ERP. It covers dealer/customer management, order lifecycle, credit controls, dispatch coordination, dealer self-service, and the canonical order-to-cash path through dispatch and settlement boundaries.

Stock and dispatch execution truth is documented separately in [inventory.md](inventory.md). Factory/manufacturing execution truth (production logs, packing, packaging mappings) is documented in [factory.md](factory.md). The sales module coordinates the commercial side of dispatch; inventory executes the stock side effects.

---

## What This Module Owns

| Surface | Ownership |
| --- | --- |
| Dealer/customer CRUD and provisioning | `DealerService`, `SalesCoreEngine` |
| Order lifecycle (create → confirm → dispatch → invoice → settle) | `SalesCoreEngine`, `SalesOrderLifecycleService`, `SalesOrderCrudService` |
| Credit limit enforcement on order creation and confirmation | `SalesCoreEngine` |
| Durable credit-limit increase requests (dealer self-service and internal) | `CreditLimitRequestService` |
| Per-dispatch credit-limit override requests | `CreditLimitOverrideService` |
| Dispatch reconciliation: AR/revenue journal, COGS journal, invoice issuance | `SalesCoreEngine.confirmDispatch()` via `SalesDispatchReconciliationService` |
| Sales fulfillment orchestration | `SalesFulfillmentService` |
| Sales returns (goods return + reversal journals) | `SalesReturnService` |
| Dunning (overdue hold evaluation) | `DunningService` |
| Dealer self-service portal (dashboard, ledger, invoices, aging, orders, credit requests, support tickets) | `DealerPortalService`, `DealerPortalController` |
| Promotions and sales targets | `SalesCoreEngine` via `SalesService` |
| Sales dashboard | `SalesDashboardService` |
| Order idempotency (legacy + canonical key resolution) | `SalesCoreEngine`, `SalesIdempotencyService` |

---

## Primary Controllers and Routes

### Internal/Admin Sales Routes — `SalesController` (`/api/v1/sales/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/sales/orders` | POST | SALES, ADMIN | Create sales order (idempotent) |
| `/api/v1/sales/orders` | GET | ADMIN, SALES, FACTORY, ACCOUNTING | List orders (paginated) |
| `/api/v1/sales/orders/search` | GET | ADMIN, SALES, FACTORY, ACCOUNTING | Search orders with filters (`orderNumber` contains match; canonical status filters normalize legacy stored statuses) |
| `/api/v1/sales/orders/{id}` | PUT | SALES, ADMIN | Update draft order |
| `/api/v1/sales/orders/{id}` | DELETE | SALES, ADMIN | Delete draft order |
| `/api/v1/sales/orders/{id}/confirm` | POST | SALES, ADMIN | Confirm order (triggers credit check + stock validation) |
| `/api/v1/sales/orders/{id}/cancel` | POST | SALES, ADMIN | Cancel order (requires reason code) |
| `/api/v1/sales/orders/{id}/status` | PATCH | SALES, ADMIN | Manual status update (ON_HOLD, REJECTED, CLOSED only) |
| `/api/v1/sales/orders/{id}/timeline` | GET | ADMIN, SALES, FACTORY, ACCOUNTING | Order status history timeline |
| `/api/v1/sales/dealers` | GET | ADMIN, SALES, ACCOUNTING | List dealers (alias for `/api/v1/dealers`; optional `status`, `page`, `size`) |
| `/api/v1/sales/dealers/search` | GET | ADMIN, SALES, ACCOUNTING | Search dealers (alias) |
| `/api/v1/sales/dashboard` | GET | ADMIN, SALES, FACTORY, ACCOUNTING | Sales dashboard |
| `/api/v1/sales/promotions` | GET/POST | ADMIN, SALES | List/create promotions |
| `/api/v1/sales/promotions/{id}` | PUT/DELETE | SALES, ADMIN | Update/delete promotion |
| `/api/v1/sales/targets` | GET | ADMIN, SALES | List sales targets |
| `/api/v1/sales/targets` | POST | ADMIN | Create sales target |
| `/api/v1/sales/targets/{id}` | PUT/DELETE | ADMIN | Update/delete target |
| `/api/v1/sales/dispatch/reconcile-order-markers` | POST | FINANCIAL_DISPATCH | Reconcile stale order-level dispatch markers |

### Dealer Management Routes — `DealerController` (`/api/v1/dealers/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/dealers` | POST | ADMIN, SALES, ACCOUNTING | Create dealer |
| `/api/v1/dealers` | GET | ADMIN, SALES, ACCOUNTING | List dealers (default active-only; optional `status`, `page`, `size`) |
| `/api/v1/dealers/search` | GET | ADMIN, SALES, ACCOUNTING | Search dealers with filters |
| `/api/v1/dealers/{dealerId}` | PUT | ADMIN, SALES, ACCOUNTING | Update dealer |
| `/api/v1/dealers/{dealerId}/dunning/hold` | POST | ADMIN, SALES, ACCOUNTING | Evaluate dunning hold |

### Credit Limit Request Routes — `CreditLimitRequestController` (`/api/v1/credit/limit-requests/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/credit/limit-requests` | GET | ADMIN, SALES | List credit limit requests |
| `/api/v1/credit/limit-requests` | POST | SALES, ADMIN | Create credit limit increase request |
| `/api/v1/credit/limit-requests/{id}/approve` | POST | ADMIN, ACCOUNTING | Approve request (increments dealer credit limit) |
| `/api/v1/credit/limit-requests/{id}/reject` | POST | ADMIN, ACCOUNTING | Reject request |

### Credit Limit Override Routes — `CreditLimitOverrideController` (`/api/v1/credit/override-requests/**`)

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/credit/override-requests` | POST | ADMIN, FACTORY, SALES | Request per-dispatch credit override |
| `/api/v1/credit/override-requests` | GET | ADMIN, ACCOUNTING | List override requests |
| `/api/v1/credit/override-requests/{id}/approve` | POST | ADMIN, ACCOUNTING | Approve override |
| `/api/v1/credit/override-requests/{id}/reject` | POST | ADMIN, ACCOUNTING | Reject override |

### Dealer Self-Service Portal — `DealerPortalController` (`/api/v1/dealer-portal/**`)

All dealer-portal endpoints require `ROLE_DEALER` and automatically scope data to the authenticated dealer.

| Route | Method | Purpose |
| --- | --- | --- |
| `/api/v1/dealer-portal/dashboard` | GET | Dealer dashboard (balance, credit, aging) |
| `/api/v1/dealer-portal/ledger` | GET | Full ledger with running balance |
| `/api/v1/dealer-portal/invoices` | GET | All invoices with outstanding amounts |
| `/api/v1/dealer-portal/invoices/{id}/pdf` | GET | Download own invoice PDF |
| `/api/v1/dealer-portal/aging` | GET | Outstanding balance with aging buckets |
| `/api/v1/dealer-portal/orders` | GET | Own orders |
| `/api/v1/dealer-portal/credit-limit-requests` | POST | Submit credit-limit increase request |

### Dealer Support Tickets — `DealerPortalSupportTicketController` (`/api/v1/dealer-portal/support/tickets/**`)

| Route | Method | Purpose |
| --- | --- | --- |
| `/api/v1/dealer-portal/support/tickets` | POST | Create support ticket |
| `/api/v1/dealer-portal/support/tickets` | GET | List own tickets |
| `/api/v1/dealer-portal/support/tickets/{ticketId}` | GET | Get own ticket |

### Dispatch Confirmation — `DispatchController` (inventory module, `/api/v1/dispatch/**`)

The dispatch write surface lives in the inventory module controller but delegates to `SalesDispatchReconciliationService` for the authoritative commercial and accounting side effects. See [inventory.md](inventory.md) for the full dispatch route table. The canonical write endpoint is:

| Route | Method | Roles | Purpose |
| --- | --- | --- | --- |
| `/api/v1/dispatch/confirm` | POST | OPERATIONAL_DISPATCH | Confirm dispatch (delegates to sales reconciliation) |

---

## Key Services and Engines

### `SalesCoreEngine`

The central engine for order and dealer management. Handles:

- **Dealer CRUD**: create, update, soft-delete (status = INACTIVE), receivable account provisioning
- **Order lifecycle**: create, update, confirm, cancel, status transitions
- **Credit enforcement**: credit-limit checks on order creation and confirmation for credit-mode orders
- **Dispatch confirmation**: the authoritative `confirmDispatch()` method runs under `SERIALIZABLE` isolation, performing inventory dispatch, AR/revenue journal posting, COGS journal posting, invoice issuance, and dealer balance updates
- **Idempotency**: multi-strategy idempotency key resolution (canonical `Idempotency-Key` header, legacy `X-Idempotency-Key` header, body field, legacy default-payment-mode and split-payment signatures)
- **Proforma boundary**: `SalesProformaBoundaryService` determines whether an order has sufficient finished goods or should be placed in `PENDING_PRODUCTION`
- **GST handling**: order-level GST treatment, rate resolution, inclusive/exclusive calculation

### `SalesOrderLifecycleService`

Thin facade over `SalesCoreEngine` for lifecycle operations: confirm, cancel, status update, timeline, dispatch check, trace ID attachment, and orchestrator workflow status.

### `SalesFulfillmentService`

Orchestrates the full fulfillment chain in one atomic transaction:

1. Reserve inventory (`FinishedGoodsService.reserveForOrder()`)
2. Confirm dispatch via `SalesService.confirmDispatch()` (which runs inventory dispatch + COGS posting + AR/revenue journal + invoice issuance)
3. Returns fulfillment result with all posted entries

**Important**: Invoice always owns AR/Revenue/Tax posting. If `issueInvoice=true`, `postSalesJournal` is automatically disabled to prevent double posting.

### `SalesReturnService`

Handles sales returns: goods return to inventory, reversal journal entries, and credit note generation. Crosses into inventory (batch return) and accounting (reversal postings).

### `DealerService`

Dealer management service handling:

- Dealer CRUD with auto-provisioning of portal user accounts (`ScopedAccountBootstrapService`)
- Dealer-directory reads with optional `status`, `page`, and `size`; omitting
  pagination returns the full active-only directory, while `status=ALL` lifts
  the default status filter
- Stable dealer-directory windowing order when `page`/`size` is supplied:
  `name ASC, id ASC`
- Auto-generation of dealer codes and receivable accounts
- Credit utilization calculation (outstanding + pending order exposure vs credit limit)
- Credit status classification: `WITHIN_LIMIT`, `NEAR_LIMIT` (≥80%), `OVER_LIMIT`
- Aging summary and ledger views for dealer detail pages

### `DealerPortalService`

Dealer self-service operations. Resolves the authenticated dealer from the security context, then scopes all data (dashboard, ledger, invoices, aging, orders) to that dealer. Also provides requester identity for credit-limit requests.

### `CreditLimitRequestService`

Durable credit-limit increase requests:

- Created by sales/admin internally or by dealers via the portal
- Each request records `dealerId`, `amountRequested`, `reason`, `requesterUserId`, `requesterEmail`
- Approval increments the dealer's credit limit by `amountRequested`
- Rejection leaves the limit unchanged
- Both approval and rejection require a decision reason
- Approval and rejection require ADMIN or ACCOUNTING role
- `requesterUserId` is nullable for historical rows

### `CreditLimitOverrideService`

Per-dispatch credit-limit override requests:

- Created when a dispatch would exceed the dealer's credit limit
- Linked to a specific packaging slip and/or sales order
- Calculates headroom gap and validates the override amount
- Approval allows the dispatch to proceed despite credit limit breach
- Has expiry behavior (`EXPIRED` status for stale requests)

### `DunningService`

Overdue payment management:

- `evaluateDealerHold()`: evaluates a single dealer's aging against thresholds and places on `ON_HOLD` if overdue
- Scheduled daily automation: evaluates all dealers for the 45+ day aging bucket
- Sends overdue reminder emails when hold is placed

### `SalesService`

High-level facade that delegates to `SalesCoreEngine` for order operations and `SalesDispatchReconciliationService` for dispatch.

### `SalesDashboardService`

Provides dashboard data: active dealer count, order counts bucketed by status (open, in_progress, dispatched, completed, cancelled), pending credit request count.

---

## DTO Families

### Order DTOs

| DTO | Purpose |
| --- | --- |
| `SalesOrderRequest` | Order creation/update payload with items, GST treatment, payment mode, idempotency key |
| `SalesOrderDto` | Order response with status, amounts, GST, payment mode |
| `SalesOrderItemRequest` | Line item with product code, quantity, unit price, GST rate |
| `SalesOrderItemDto` | Line item response |
| `SalesOrderSearchFilters` | Search parameters (status, dealer, order number, date range, pagination) |
| `SalesOrderStatusHistoryDto` | Status transition record |
| `CancelRequest` | **SalesController inner record** — Cancellation reason code and reason text |
| `StatusRequest` | **SalesController inner record** — Manual status update target |

### Dealer DTOs

| DTO | Purpose |
| --- | --- |
| `CreateDealerRequest` | Dealer creation payload (name, email, phone, GST, credit limit, payment terms, region) |
| `DealerResponse` | Full dealer response with outstanding balance, receivable account, portal email |
| `DealerDto` | Simplified dealer view (legacy, used by `SalesCoreEngine`) |
| `DealerLookupResponse` | Search result with credit status, receivable account info, payment terms |
| `DealerPortalCreditLimitRequestCreateRequest` | Dealer portal credit request payload (amount, reason) |

### Credit DTOs

| DTO | Purpose |
| --- | --- |
| `CreditLimitRequestCreateRequest` | Credit limit increase request (dealerId, amount, reason) |
| `CreditLimitRequestDto` | Credit limit request response with status |
| `CreditLimitRequestDecisionRequest` | Approval/rejection decision with reason |
| `CreditLimitOverrideRequestCreateRequest` | Per-dispatch override request (slip/order, override amount, reason) |
| `CreditLimitOverrideRequestDto` | Override request response |
| `CreditLimitOverrideDecisionRequest` | Override approval/rejection |

### Dispatch DTOs

| DTO | Purpose |
| --- | --- |
| `DispatchConfirmRequest` | Internal dispatch confirmation request (slip ID, order ID, lines, overrides, transport metadata) |
| `DispatchConfirmResponse` | Internal dispatch result (journal IDs, invoice ID, COGS postings) |
| `DispatchMarkerReconciliationResponse` | Reconciliation result for stale order-level markers |

### Other DTOs

| DTO | Purpose |
| --- | --- |
| `PromotionRequest` / `PromotionDto` | Promotion CRUD |
| `SalesTargetRequest` / `SalesTargetDto` | Sales target CRUD |
| `SalesDashboardDto` | Dashboard aggregation |

---

## Key Entities

| Entity | Table | Notes |
| --- | --- | --- |
| `Dealer` | `dealers` | Tenant-scoped, unique code per company, links to portal `UserAccount` and receivable `Account`, GST registration, payment terms, region |
| `SalesOrder` | `sales_orders` | Tenant-scoped, unique order number, status lifecycle, idempotency key/hash, payment mode, GST treatment, financial markers (journal/invoice IDs) |
| `SalesOrderItem` | `sales_order_items` | Line items with product code, quantity, unit price, GST rate. Related to parent `SalesOrder` via `@ManyToOne` FK. |
| `SalesOrderStatusHistory` | `sales_order_status_histories` | Append-only status transition log |
| `CreditRequest` | `credit_requests` | Durable credit limit increase requests with requester identity |
| `CreditLimitOverrideRequest` | `credit_limit_override_requests` | Per-dispatch override requests linked to slip/order |
| `Promotion` | (promotions) | Sales promotions |
| `SalesTarget` | (sales targets) | Sales targets |

---

## Order Status Lifecycle

### Canonical Workflow Statuses

These statuses progress through the order lifecycle automatically:

| Status | Meaning | Transition Trigger |
| --- | --- | --- |
| `DRAFT` | Order created, not yet confirmed | Initial status on creation |
| `RESERVED` | Stock available, not yet confirmed | Auto-assessed on creation/update if no shortages |
| `PENDING_PRODUCTION` | Stock shortage, needs production | Auto-assessed on creation/update if shortages found |
| `PENDING_INVENTORY` | Inventory pending | Orchestrator workflow status |
| `PROCESSING` | Being processed | Orchestrator workflow status |
| `READY_TO_SHIP` | Ready for shipment | Orchestrator workflow status |
| `CONFIRMED` | Order confirmed by sales | `POST /sales/orders/{id}/confirm` |
| `DISPATCHED` | Goods dispatched | Set by dispatch confirmation |
| `INVOICED` | Invoice issued | Set during dispatch confirmation |
| `SETTLED` | Payment settled | External settlement process |
| `CANCELLED` | Order cancelled | `POST /sales/orders/{id}/cancel` (requires reason code) |

### Manual Statuses

These can only be set via `PATCH /sales/orders/{id}/status`:

| Status | Meaning | Constraint |
| --- | --- | --- |
| `ON_HOLD` | Manually held | Can be set on any non-terminal order |
| `REJECTED` | Order rejected | Terminal status |
| `CLOSED` | Order closed | Only after `SETTLED` |

### Terminal Statuses

Once reached, no further workflow transitions: `CANCELLED`, `REJECTED`, `CLOSED`.

### Non-Canonical / Legacy Statuses

The `VALID_ORDER_STATUSES` set also includes: `BOOKED`, `SHIPPED`, `FULFILLED`, `COMPLETED`. These are accepted by the status normalization logic for backward compatibility but are **not** part of the canonical lifecycle. `BOOKED` is treated as an alias for pre-confirmation state in some flows. `SHIPPED`, `FULFILLED`, and `COMPLETED` are dashboard classification statuses that map into the `dispatched` or `completed` buckets.

---

## Credit Controls

### Credit Limit Enforcement

Credit checks fire in two places:

1. **Order creation** (`SalesCoreEngine.createOrder()`): For credit-mode orders, enforces credit limit before saving the order. If the check fails, the engine attempts idempotent replay before throwing.
2. **Order confirmation** (`SalesCoreEngine.confirmOrder()`): Re-checks credit limit before allowing confirmation.

The credit check evaluates:

- **Outstanding balance**: current dealer ledger balance from `DealerLedgerService`
- **Pending order exposure**: sum of total amounts for non-terminal orders on the same dealer
- **Credit used** = outstanding balance + pending order exposure
- **Available credit** = credit limit − credit used (floored at zero)

If credit used exceeds the limit, a `CreditLimitExceededException` is thrown.

### Credit Status Classification

| Status | Threshold |
| --- | --- |
| `WITHIN_LIMIT` | credit used < 80% of credit limit |
| `NEAR_LIMIT` | credit used ≥ 80% but < 100% |
| `OVER_LIMIT` | credit used ≥ 100% |

### Credit Limit Requests (Durable Increase)

These are permanent credit-limit increases:

- **Created by**: sales/admin via `/api/v1/credit/limit-requests` or dealers via `/api/v1/dealer-portal/credit-limit-requests`
- **Approved by**: admin or accounting via `/api/v1/credit/limit-requests/{id}/approve`
- **Effect on approval**: dealer credit limit incremented by `amountRequested`
- **Audit**: approval and rejection decisions are audited with old/new limits

### Credit Limit Overrides (Per-Dispatch)

These are temporary exceptions allowing a single dispatch to exceed the credit limit:

- **Created by**: admin/factory/sales via `/api/v1/credit/override-requests`
- **Approved by**: admin or accounting
- **Linked to**: a specific packaging slip and/or sales order
- **Lifecycle**: PENDING → APPROVED / REJECTED / EXPIRED
- **Effect**: allows the referenced dispatch to proceed despite credit limit breach

### Dunning

- Manual evaluation: `POST /api/v1/dealers/{dealerId}/dunning/hold` with configurable overdue days and minimum amount thresholds
- Automated daily: `DunningService` scheduled task evaluates all dealers against the 45+ day aging bucket
- Effect: sets dealer status to `ON_HOLD` and sends overdue reminder email

---

## Dispatch and O2C Completion Boundary

### Dispatch Ownership (Two-Layer Seam)

Dispatch is explicitly a two-layer seam:

| Layer | Owner | Surface |
| --- | --- | --- |
| **Transport/controller location** | `DispatchController` (inventory module) | `POST /api/v1/dispatch/confirm` |
| **Commercial/accounting ownership** | `SalesCoreEngine.confirmDispatch()` via `SalesDispatchReconciliationService` | Same endpoint, delegated call |

The inventory controller receives the HTTP request, validates factory dispatch metadata (transporter, vehicle, challan for factory-role users), and delegates to `SalesDispatchReconciliationService.confirmDispatch()` which calls `SalesCoreEngine.confirmDispatch()`.

### What Dispatch Confirmation Does

`SalesCoreEngine.confirmDispatch()` runs under `SERIALIZABLE` isolation and performs:

1. **Validates** the packaging slip and sales order exist and are in a valid state
2. **Checks credit limit** (unless `adminOverrideCreditLimit` is set with a valid override request)
3. **Dispatches inventory** via `FinishedGoodsService.confirmDispatch()` — FIFO/LIFO/WAC cost layer resolution, stock reduction, batch tracking
4. **Posts COGS journal** — debit COGS account, credit inventory account, per slip line
5. **Posts AR/Revenue journal** — debit AR (dealer receivable), credit revenue, credit GST payable
6. **Issues invoice** — auto-creates invoice linked to the slip and order
7. **Updates dealer balance** via `DealerLedgerService`
8. **Sets order markers** — `salesJournalEntryId`, `fulfillmentInvoiceId`, dispatch flags on the order
9. **Transitions order status** to `DISPATCHED` or `INVOICED`

### Current O2C Completion Boundary

The canonical O2C path today:

```
Order Created (DRAFT/RESERVED/PENDING_PRODUCTION)
  → Order Confirmed (CONFIRMED)
  → Dispatch Confirmed (DISPATCHED + INVOICED)
  → [Settlement] (SETTLED)
  → [Manual Close] (CLOSED)
```

**Definition of done for O2C today**: An order is considered commercially complete when it has been dispatched, invoiced, and the invoice has been paid/settled. The settlement step is tracked via external accounting processes (not an automated sales module operation). An order can be manually closed after settlement.

### What Is Not Yet Automated

- **Settlement**: Payment receipt and allocation against invoices is handled through accounting settlement workflows, not directly through the sales module
- **Automated close**: Orders must be manually moved to `CLOSED` after settlement via `PATCH /sales/orders/{id}/status`
- **Shipment tracking**: No automated carrier/tracking integration; transport metadata is captured at dispatch but not tracked

---

## Dealer Self-Service Portal

### Host Ownership

| Host | Owner | Scope |
| --- | --- | --- |
| `/api/v1/dealer-portal/**` | `DealerPortalController` | Dealer self-service, `ROLE_DEALER` only |
| `/api/v1/dealer-portal/support/tickets/**` | `DealerPortalSupportTicketController` | Dealer support tickets, `ROLE_DEALER` only |

All dealer-portal endpoints require `ROLE_DEALER` and automatically scope data to the authenticated dealer's identity. Cross-dealer data access is prevented at the service layer.

### Self-Service Capabilities

Dealers can:

- View their dashboard (balance, credit, aging)
- View their full ledger with running balance
- View and download their invoices (PDF)
- View their outstanding balance with aging buckets
- View their orders
- Submit credit-limit increase requests
- Create and view support tickets

### Isolation Boundaries

- Data scoping: `DealerPortalService.getCurrentDealer()` resolves the dealer from the authenticated user's identity, then all queries filter by that dealer
- Invoice PDF access: dealer can only download their own invoices; cross-dealer access returns 404
- Credit requests: dealer-scoped, auto-populated with the current dealer's ID

---

## Events

| Event | Trigger | Consumer |
| --- | --- | --- |
| `SalesOrderCreatedEvent` | Order creation | Published via Spring `ApplicationEventPublisher`; used for cross-module coordination (e.g., orchestrator, metrics) |

The sales module currently publishes only one domain event. Other coordination happens through direct service calls rather than event-driven patterns.

---

## Cross-Module Boundaries

| Boundary | Direction | Nature |
| --- | --- | --- |
| **Sales → Inventory** | Outbound | Order creation triggers stock reservation; dispatch confirmation triggers stock dispatch and COGS posting |
| **Sales → Accounting** | Outbound | Dispatch confirmation posts AR/revenue and COGS journals; sales returns post reversal journals |
| **Sales → Invoice** | Outbound | Dispatch confirmation auto-issues invoices |
| **Sales → Auth** | Outbound | Dealer provisioning creates portal user accounts with `ROLE_DEALER` |
| **Inventory → Sales** | Inbound | `DispatchController` delegates to `SalesDispatchReconciliationService` for commercial side effects |
| **Accounting → Sales** | Read | `DealerLedgerService`, `StatementService` provide dealer balance/aging data to sales services |

---

## Deprecated and Non-Canonical Surfaces

### Dealer Alias Routes

`GET /api/v1/sales/dealers` and `GET /api/v1/sales/dealers/search` are
**frontend convenience aliases** that delegate to `DealerService`. The canonical
dealer routes are at `/api/v1/dealers`. The alias routes exist because the
frontend currently calls the `/sales/dealers` path. Both sets of routes produce
identical results, including the dealer-directory compatibility rules:

- omit `page` and `size` to return the full active-only directory
- pass `status=ALL` to include non-active dealers
- if `page` and/or `size` is supplied, the backend returns a sliced
  `DealerResponse[]` list without total-count metadata

### Legacy Payment Mode Idempotency

The `SalesCoreEngine` resolves idempotency across three signature strategies:

1. **Canonical**: current request signature with explicit payment mode
2. **Legacy default-payment**: signature computed with default payment mode (`CREDIT`) for orders created before payment mode was tracked
3. **Legacy split-replay**: signature for orders that previously used `SPLIT` payment mode

This backward compatibility exists to prevent duplicate orders when replaying requests from older frontend versions. The `X-Idempotency-Key` header is a legacy alias for `Idempotency-Key`; both are accepted but `Idempotency-Key` is canonical.

### Legacy/Compatibility Order Statuses

The statuses `BOOKED`, `SHIPPED`, `FULFILLED`, and `COMPLETED` are accepted by status normalization but are not part of the canonical lifecycle. They exist for backward compatibility with older data. Dashboard bucketing maps them into canonical categories:

- `BOOKED` → `in_progress`
- `SHIPPED`, `FULFILLED` → `dispatched`
- `COMPLETED` → `completed`

The same normalization is used by `GET /api/v1/sales/orders/search`, so
canonical search filters continue to find older rows stored under legacy status
names.

### Credit Request Nullable Requester Fields

`CreditRequest.requesterUserId` is nullable for historical rows created before requester tracking was added. New requests always populate both `requesterUserId` and `requesterEmail`.

---

## Current Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

1. **No automated settlement**: Payment receipt and allocation against invoices happens through accounting workflows, not the sales module. Orders move to `SETTLED` externally.
2. **No automated order closure**: After settlement, orders must be manually closed via the status endpoint.
3. **Single domain event**: Only `SalesOrderCreatedEvent` is published. Order confirmation, dispatch, and cancellation do not emit domain events, limiting event-driven extension.
4. **No shipment tracking integration**: Transport metadata is captured at dispatch but not integrated with carrier systems.
5. **Dunning is rudimentary**: The scheduled task evaluates 45+ day aging only; there is no graduated dunning escalation.
6. **Proforma boundary is assessment-only**: `SalesProformaBoundaryService` assesses commercial availability but does not reserve stock or post accounting entries during order creation. Orders with shortages are placed in `PENDING_PRODUCTION` without triggering production automatically.
7. **Factory task cancellation**: Order cancellation cancels linked factory tasks, but there is no bidirectional status sync between factory tasks and sales orders beyond the initial cancellation.
8. **No partial fulfillment**: `SalesFulfillmentService` does not support partial fulfillment by default; orders with stock shortages fail unless `allowPartialFulfillment` is explicitly enabled.

---

## Cross-References

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/inventory.md](inventory.md) — stock truth, dispatch execution, packaging slip lifecycle
- [docs/modules/factory.md](factory.md) — manufacturing execution, packing, packaging mappings
- [docs/modules/admin-portal-rbac.md](admin-portal-rbac.md) — admin/portal/RBAC host ownership and role matrices
- [docs/modules/core-idempotency.md](core-idempotency.md) — shared idempotency infrastructure
- [docs/modules/core-security-error.md](core-security-error.md) — security filter chain and error contract
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/flows/order-to-cash.md](../flows/order-to-cash.md) — canonical order-to-cash flow (behavioral entrypoint)
- [docs/frontend-handoff-commercial.md](../frontend-handoff-commercial.md) — Commercial frontend handoff (O2C payloads, hosts, RBAC, dealer self-service boundaries)
- [docs/adrs/ADR-006-portal-and-host-boundary-separation.md](../adrs/ADR-006-portal-and-host-boundary-separation.md) — Portal and host boundary separation (admin vs dealer self-service ownership)
- [docs/deprecated/INDEX.md](../deprecated/INDEX.md) — Deprecated surfaces registry (legacy order statuses, dealer alias routes)
