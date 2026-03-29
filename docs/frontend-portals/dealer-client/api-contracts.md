# Dealer Client API Contracts

The dealer portal should call only dealer-safe backend surfaces, typically under
the dealer portal namespace.

## Orders

- dealer order list and detail endpoints under `/api/v1/dealer-portal/**`

Rules:

- Orders are read-first in this portal.
- If dealer self-service order creation is enabled, it must still remain within
  dealer-safe constraints and never expose internal pricing or approval tools
  beyond backend policy.

## Invoices

- dealer invoice list and detail endpoints under `/api/v1/dealer-portal/**`

Rules:

- Invoice status is visible here after internal dispatch and posting complete.
- Dealer portal must not offer finance correction or manual settlement actions.
- Dealer portal owns the external invoice list, detail, and PDF/download flow.
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

- dealer support endpoints under `/api/v1/dealer-portal/**`
- dealer credit request endpoints under `/api/v1/dealer-portal/**`

Rules:

- Support requests are dealer-originated but resolved outside this portal.
- Credit requests are self-service submissions only.
- Approval, override, and final finance correction stay in internal portals.

## Forbidden From Dealer Portal

- any internal `/api/v1/admin/**`
- any `/api/v1/accounting/**` write action
- `POST /api/v1/dispatch/confirm`
- any factory production or packing write action
