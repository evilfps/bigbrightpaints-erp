# Sales States And Errors

## Important States

- dealer `ACTIVE`, `ON_HOLD`, `SUSPENDED`
- order `DRAFT`, `CONFIRMED`, `RESERVED`, `PENDING_DISPATCH`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `INVOICED`, `SETTLED`, `CANCELLED`
- credit request `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`
- invoice readiness `NOT_READY`, `READY`, `PARTIAL`

## Blocking Errors

- duplicate dealer code, GST number, or commercial email
- invalid price list or tax setup for the dealer
- SKU not ready for selling or reservation
- reservation failure because stock is unavailable
- credit block or exposure limit breach
- dispatch not yet confirmed by factory
- invoice unavailable because dispatch is incomplete

## UI Rules

- Separate commercial validation errors from backend faults.
- When dispatch is still pending, show a status explanation and last operational
  milestone instead of a generic failure banner.
- When invoice data is missing, show whether the blocker is dispatch, partial
  dispatch, or a read error.
