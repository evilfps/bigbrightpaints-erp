# Dealer Client API Contracts

This file defines which backend surfaces dealer-client can call directly and
where the portal must stop.

## Dealer Dashboard And Summary

- `GET /api/v1/dealer-portal/dashboard`

Returns dealer summary, order counts, credit exposure, payment status, and
recent activity.

## Order Tracking

- `GET /api/v1/dealer-portal/orders`
- `GET /api/v1/dealer-portal/orders/{id}`

Returns order list and order detail for the authenticated dealer's company.
The backend filters by `companyCode` to ensure dealer sees only their own
orders.

## Invoice Access

- `GET /api/v1/dealer-portal/invoices`
- `GET /api/v1/dealer-portal/invoices/{id}`

Returns invoice list and detail for the dealer's company. Invoice PDF download
is also served through the invoice detail endpoint.

Rules:

- Dealer cannot access invoices for other dealers.
- Dealer cannot create, edit, or void invoices.

## Ledger And Aging

- `GET /api/v1/dealer-portal/ledger`
- `GET /api/v1/dealer-portal/aging`

Returns the dealer's account ledger entries and receivable aging summary. The
backend scopes to the dealer's `companyCode`.

## Credit Self-Service

- `GET /api/v1/dealer-portal/credit`
- `POST /api/v1/dealer-portal/credit`
- `GET /api/v1/dealer-portal/credit/{id}`

Dealer can view existing credit requests and submit new self-service credit
requests. Approval workflow remains internal to sales and accounting.

Rules:

- Dealer can only submit credit requests for their own company.
- Dealer cannot approve or override credit requests.

## Support Tickets

- `GET /api/v1/portal/support/tickets`
- `POST /api/v1/portal/support/tickets`
- `GET /api/v1/portal/support/tickets/{id}`

Dealer can create and follow support tickets. Ticket assignment and resolution
are internal to tenant-admin.

## Forbidden From Dealer Client

- Any write to `/api/v1/sales/orders`
- Any write to `/api/v1/factory/**`
- Any write to `/api/v1/accounting/journal-entries`
- Any write to `/api/v1/admin/users`
- Any read of other dealers' data via `/api/v1/dealers/**`
