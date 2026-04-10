# Dealer Client API Contracts

This file defines which backend surfaces dealer-client can call directly and where the portal must stop.

## Dealer Dashboard And Summary

- `GET /api/v1/dealer-portal/dashboard`

Returns dealer summary, order counts, credit exposure, payment status, and recent activity for the authenticated dealer only.

The dealer portal should call only dealer-safe backend surfaces, typically under
the dealer portal namespace.

## Orders

- `GET /api/v1/dealer-portal/orders`

Rules:

- Orders are read-only in this portal.
- Order creation and mutation are handled internally via sales or admin portals;
  the dealer portal does not expose order write actions.

## Invoices

- `GET /api/v1/dealer-portal/invoices`
- `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf`

Rules:

- Invoice status is visible here after internal dispatch and posting complete.
- Dealer portal must not offer finance correction or manual settlement actions.
- Dealer portal owns the external invoice list and PDF/download flow.
- Do not assume a separate dealer invoice-detail REST endpoint unless runtime adds
  one.
- Internal sales may read invoice state for a current order, but that does not
  create shared ownership of the dealer invoice inbox.

## Ledger And Aging

- dealer ledger and aging reads under `/api/v1/dealer-portal/**`

Rules:

- Ledger and aging are read-only.
- The portal should explain overdue and current balance clearly without exposing
  accounting internals such as journal identifiers unless backend explicitly
  provides a dealer-safe reference.

## Support And Credit Request

- `GET /api/v1/dealer-portal/support/tickets`
- `POST /api/v1/dealer-portal/support/tickets`
- `GET /api/v1/dealer-portal/support/tickets/{ticketId}`
- `POST /api/v1/dealer-portal/credit-limit-requests`

Rules:

- Support requests are dealer-originated but resolved outside this portal.
- Credit requests are self-service submissions only.
- Dealer identity is always resolved from the authenticated dealer principal on
  `/api/v1/dealer-portal/**`; dealer-client must not send a `dealerId` in
  dealer-portal credit-request payloads.
- Approval, override, and final finance correction stay in internal portals.

## Forbidden From Dealer Portal

- any internal `/api/v1/admin/**`
- any `/api/v1/accounting/**` write action
- `POST /api/v1/dispatch/confirm`
- any factory production or packing write action
