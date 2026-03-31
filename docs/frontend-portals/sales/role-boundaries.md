# Sales Role Boundaries

- `ROLE_SALES` owns dealer master, order execution, reservation visibility, and
  commercial credit escalation.
- `ROLE_FACTORY` owns production, packing, pending-dispatch queues, and dispatch
  confirmation.
- `ROLE_ACCOUNTING` owns settlements, journals, reversals, and financial
  correction after dispatch posts.
- `ROLE_ADMIN` owns tenant-wide approval shell and tenant administration, not
  day-to-day commercial execution.
- `ROLE_DEALER` gets the dealer-client portal only and must never see internal
  sales workflows.

UI rules:

- Sales must not render factory-only execution controls.
- Sales must not render accounting-only correction or settlement controls.
- Shared status chips are fine, but action ownership must remain portal-specific.
