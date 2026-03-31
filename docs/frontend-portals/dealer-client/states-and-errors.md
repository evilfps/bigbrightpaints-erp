# Dealer Client States And Errors

## Important States

- order `DRAFT`, `CONFIRMED`, `RESERVED`, `PENDING_DISPATCH`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `INVOICED`, `SETTLED`, `CANCELLED`
- invoice `DRAFT`, `ISSUED`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `CANCELLED`
- credit request `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`
- support ticket `OPEN`, `IN_PROGRESS`, `WAITING_CUSTOMER`, `RESOLVED`, `CLOSED`

## Blocking Errors

- session does not contain valid `companyCode` for the dealer's company
- invoice PDF generation failed or file not found
- credit request submission failed validation
- support ticket submission failed validation

## UI Rules

- Always show dealer-specific state explanations, not generic error codes.
- When dispatch is pending, show factory-owned status without exposing actions.
- When invoice is not ready, show dispatch-read state.
- When credit request is pending, show approval timeline.
