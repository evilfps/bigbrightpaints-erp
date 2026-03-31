# Sales API Contracts

This file defines which backend surfaces sales can call directly and where the
portal must stop.

## Dealer Master

- `GET /api/v1/dealers`
- `POST /api/v1/dealers`
- dealer detail and update endpoints under `/api/v1/dealers/**`

Dealer screens own commercial identity, contacts, GST-visible business data,
addresses, credit policy reads, and dealer readiness for order creation.

## Order Lifecycle

- `GET /api/v1/sales/orders`
- `POST /api/v1/sales/orders`
- `POST /api/v1/sales/orders/{id}/confirm`
- `GET /api/v1/sales/orders/{id}`
- `GET /api/v1/sales/orders/{id}/timeline`

Rules:

- Order create and confirm happen in sales.
- Confirm should be treated as commercial confirmation and reservation intent.
- Do not infer invoice generation from order confirmation alone.

## Dispatch And Invoice Reads

- `GET /api/v1/dispatch/order/{orderId}`
- `GET /api/v1/dispatch/pending` only when sales needs queue visibility
- `GET /api/v1/invoices/{id}` only when the invoice id is already linked to the
  current order or order timeline

Rules:

- `POST /api/v1/dispatch/confirm` is factory-owned and must not be called from
  sales.
- Dispatch confirm is the only canonical posting boundary for O2C.
- Sales may read dispatch outcomes and invoice availability after factory posts
  the dispatch.
- Sales must treat `/api/v1/invoices/**` as an order-follow-up surface only. It
  does not own a global invoice inbox, invoice download center, or invoice list
  route.
- Dealer self-service invoice list, detail, and PDF access belong in the
  dealer-client portal.

## Credit And Commercial Approval

- credit reads and request APIs under `/api/v1/credit/**`

Rules:

- Sales can start or follow commercial credit workflows.
- Sales must not surface accounting settlement, journal reversal, or period
  close as a workaround for credit issues.

## Forbidden From Sales

- `POST /api/v1/dispatch/confirm`
- `POST /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`
- any period-close, settlement, or export-approval write action
