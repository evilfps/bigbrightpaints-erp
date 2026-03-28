# Dealer Client States And Errors

## Important States

- order `SUBMITTED`, `CONFIRMED`, `PENDING_DISPATCH`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `INVOICED`, `SETTLED`, `CANCELLED`
- invoice `OPEN`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`
- support request `OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`
- credit request `PENDING`, `APPROVED`, `REJECTED`

## Common Errors

- unauthorized dealer-to-dealer access attempt
- order unavailable because tenant or dealer status blocks access
- invoice not ready because dispatch is incomplete
- ledger or aging temporarily unavailable
- support submission failure
- credit request submission rejected by validation rules

## UI Rules

- Keep security failures distinct from empty-state messaging.
- Show order, invoice, ledger, and aging empty states separately so dealers
  understand what is missing.
- When credit is exhausted, explain the self-service request path without
  exposing internal override workflows.
