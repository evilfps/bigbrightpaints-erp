# Order-to-Cash State Machines and Invariants

This document captures the current O2C (sales) state machines and the invariants
that must hold for traceability and ERP-grade correctness. It reflects existing
behavior only; no new flows are introduced.

## Sales Order Statuses
Source: `SalesService`, `SalesFulfillmentService`.

States (string values stored on `SalesOrder.status`):
- `BOOKED`: initial status after order creation.
- `RESERVED`: inventory fully reserved for the order.
- `PENDING_PRODUCTION`: shortages detected; production tasks created.
- `PENDING_INVENTORY`: shortages detected in fulfillment; inventory still needed.
- `CONFIRMED`: manual confirm endpoint sets this.
- `READY_TO_SHIP`: some slips dispatched, none backordered.
- `SHIPPED`: all slips dispatched.
- `FULFILLED`, `COMPLETED`: fulfillment orchestration treats these as terminal shipped states.
- `CANCELLED`: cancelled order; inventory reservations released.
- `REJECTED`, `ON_HOLD`, `CLOSED`: blocking statuses (dispatch denied).

Transitions (current behavior):
- Create order -> `BOOKED`, then:
  - if no shortages: `RESERVED`
  - if shortages: `PENDING_PRODUCTION`
- Confirm order -> `CONFIRMED`
- Fulfillment reserve -> `RESERVED` (all stock) or `PENDING_INVENTORY` (shortages)
- Dispatch confirmation:
  - if all slips dispatched: `SHIPPED`
  - if any slip backordered: `PENDING_PRODUCTION`
  - otherwise: `READY_TO_SHIP`
- Cancel order -> `CANCELLED` (only from `BOOKED`, `RESERVED`, `CONFIRMED`, `PENDING_PRODUCTION`)

## Packaging Slip Statuses
Source: `FinishedGoodsService`, `PackagingSlip.status`.

States:
- `PENDING`: default after creation or when reset.
- `RESERVED`: inventory reserved without shortages.
- `PENDING_PRODUCTION`: shortages require factory tasks.
- `BACKORDER`: partial fulfillment requires backorder slip.
- `PENDING_STOCK`: dispatch attempted but stock not fully available.
- `PARTIAL`: some inventory shipped, some still pending/backordered.
- `DISPATCHED`: inventory issued and slip confirmed.
- `CANCELLED`: slip cancelled (including backorder cancellations).
- Manual/update-only: `PACKING`, `READY` (set by `updateSlipStatus`).

Transitions (current behavior):
- Reserve order:
  - no shortages -> `RESERVED`
  - shortages -> `PENDING_PRODUCTION`
- Partial dispatch -> original slip `PARTIAL`, backorder slip `BACKORDER`
- Full dispatch -> `DISPATCHED`
- Cancel slip -> `CANCELLED`

## Invoice Statuses
Source: `InvoiceSettlementPolicy`, `Invoice.status`.

States:
- `DRAFT`: created but not issued.
- `ISSUED`: invoice is posted/active.
- `PARTIAL`: outstanding amount reduced but not zero.
- `PAID`: outstanding amount cleared.
- `VOID`, `REVERSED`: exceptional statuses.

Transitions (current behavior):
- Issue invoice -> `ISSUED`
- Settlement/payment:
  - outstanding == 0 -> `PAID`
  - 0 < outstanding < total -> `PARTIAL`
- Void -> `VOID`
- Credit note (full reversal) -> `VOID`

## Dealer Ledger Payment Statuses
Source: `DealerLedgerEntry.paymentStatus`.

States:
- `UNPAID`: invoice still fully outstanding.
- `PARTIAL`: invoice partially settled.
- `PAID`: invoice fully settled.

Payment status is derived from the related invoice’s outstanding amount and
tracked via `amountPaid`, `dueDate`, and `invoiceNumber`.

## Cross-Module Invariants
These invariants must hold for a canonical O2C flow:
- Sales order → packaging slip(s) exists for reservation/dispatch.
- On dispatch:
  - slip status `DISPATCHED`
  - slip has `invoiceId`, `journalEntryId` (AR), `cogsJournalEntryId`
  - order has `fulfillmentInvoiceId`, `salesJournalEntryId`, `cogsJournalEntryId`
- Invoice:
  - linked to sales order and journal entry
  - `totalAmount == subtotal + taxTotal`
  - `outstandingAmount` updated via settlement policy
- Dealer ledger entry:
  - linked to the AR journal entry
  - `invoiceNumber` and `dueDate` populated for aging
  - `paymentStatus` reflects invoice outstanding (UNPAID/PARTIAL/PAID)

## Idempotency and Retry Safety
- Sales order creation uses an idempotency key derived from request fields.
- Journal posting uses reference numbers for idempotent reuse.
- Dispatch confirmation is safe to re-run:
  - inventory confirmation is skipped once slip is `DISPATCHED`
  - AR/COGS journals are not duplicated when already linked.

## Rounding Policy (GST)
- Line totals are rounded to currency precision.
- `gstRoundingAdjustment` captures any remainder when distributing order-level tax.
- Order total must equal `subtotal + gstTotal` within tolerance.
