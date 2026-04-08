# Sales API Contracts

Last reviewed: 2026-04-08

This file defines which backend surfaces sales can call directly and where the
portal must stop.

## Sales Dashboard

- `GET /api/v1/sales/dashboard`

Response contract (`ApiResponse<SalesDashboardDto>.data`):

- `recentOrdersCount` (`integer`)
- `totalRevenue` (`number`)
- `totalReceivables` (`number`)
- `pendingOrders` (`integer`)

## Dealer Master

- `GET /api/v1/dealers?status=&page=&size=`
- alias: `GET /api/v1/sales/dealers?status=&page=&size=`
- `GET /api/v1/dealers/{dealerId}`
- `POST /api/v1/dealers`
- `POST /api/v1/dealers/{dealerId}/dunning/hold`
- dealer search and update endpoints under `/api/v1/dealers/**`

Dealer screens own commercial identity, contacts, GST-visible business data,
addresses, credit policy reads, and dealer readiness for order creation.

Rules:

- Omitting `page` and `size` returns the full active-only directory.
- Send `status=ALL` to include non-active dealers in the directory.
- When `page` and/or `size` are sent, the backend still returns a plain
  `DealerResponse[]` slice without total-count metadata.
- `GET /api/v1/dealers/{dealerId}` returns dealer detail (`200`) and returns
  `404` when the dealer id does not exist.
- `POST /api/v1/dealers` returns `201 Created`.
- `DealerResponse` and `DealerLookupResponse` include monetary fields
  `creditLimit` and `outstandingBalance` (both numeric).
- `POST /api/v1/dealers/{dealerId}/dunning/hold` is an explicit hold action
  (no threshold query/body inputs) and returns `dealerId`, `dunningHeld`,
  `status`, and `alreadyOnHold`.

## Order Lifecycle

- `GET /api/v1/sales/orders`
- `GET /api/v1/sales/orders/search?status=&dealerId=&orderNumber=&fromDate=&toDate=&page=&size=`
- `POST /api/v1/sales/orders`
- `POST /api/v1/sales/orders/{id}/confirm`
- `GET /api/v1/sales/orders/{id}`
- `GET /api/v1/sales/orders/{id}/timeline`

Rules:

- Order create and confirm happen in sales.
- `POST /api/v1/sales/orders` returns `201 Created` when the request opts into the
  draft lifecycle contract (`paymentTerms` present and/or any item includes
  `finishedGoodId`); legacy payloads continue to receive `200 OK`.
- `POST /api/v1/sales/orders` returns `422 Unprocessable Entity` when dealer
  credit posture would be exceeded and no approved override headroom can cover
  the request.
- `SalesOrderRequest` now accepts `paymentTerms` and
  `SalesOrderItemRequest.finishedGoodId`.
- Order search treats `orderNumber` as a case-insensitive contains filter.
- Order search normalizes legacy stored statuses:
  - `DRAFT` also matches `BOOKED`
  - `DISPATCHED` also matches `SHIPPED` and `FULFILLED`
  - `SETTLED` also matches `COMPLETED`
- Confirm should be treated as commercial confirmation and reservation intent.
- Order timeline rows expose canonical + alias fields:
  `toStatus`/`status`, `changedBy`/`actor`, and `changedAt`/`timestamp`.
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
- canonical override mutation routes:
  - `POST /api/v1/credit/override-requests` (`201 Created`)
  - `POST /api/v1/credit/override-requests/{id}/approve` (`200 OK`)
  - `POST /api/v1/credit/override-requests/{id}/reject` (`200 OK`)

Rules:

- Sales can start or follow commercial credit workflows.
- Canonical override create payload uses `requestedAmount` + `reason` and may
  include `dealerId`, `salesOrderId`, and/or `packagingSlipId` for identity
  resolution context.
- `requestedBy` is server-derived from the authenticated principal; clients must
  not send requester identity fields.
- `dispatchAmount` is a legacy alias for `requestedAmount` and should not be
  used by new clients.
- Override `requiredHeadroom` is computed using the same exposure posture as
  order creation (`outstandingBalance + pendingOrderExposure + requestedAmount - creditLimit`).
- Approved override headroom contributes to effective dealer credit posture in
  `POST /api/v1/sales/orders`; `422` applies only when approved headroom still
  cannot cover the request.
- Sales must not surface accounting settlement, journal reversal, or period
  close as a workaround for credit issues.

## Forbidden From Sales

- `POST /api/v1/dispatch/confirm`
- `POST /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`
- any period-close, settlement, or export-approval write action
