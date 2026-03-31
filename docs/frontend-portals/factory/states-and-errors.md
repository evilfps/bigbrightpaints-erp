# Factory States And Errors

## Important States

- production `DRAFT`, `POSTED`, `CORRECTED`
- packing `DRAFT`, `POSTED`, `BLOCKED`, `READY_FOR_DISPATCH`
- dispatch `PENDING`, `PARTIALLY_DISPATCHED`, `DISPATCHED`, `FAILED`
- readiness `READY`, `BLOCKED`

## Common Blocking Errors

- SKU readiness blocked because product or account mapping is incomplete
- missing packaging mapping
- missing produced quantity or no dispatchable batch
- dispatch quantity mismatch
- duplicate or conflicting dispatch confirm
- stale slip or stale pending-queue state

## UI Rules

- Show readiness blockers as explicit dependency failures.
- Keep quantity errors attached to the dispatch slip detail screen.
- Never mark an order as dispatched in factory UI before canonical dispatch
  confirm succeeds.
- When production status changes to `CORRECTED`, invalidate stale packing and
  dispatch views derived from the old batch state and tell the user to reopen
  lineage from the corrected production record.
