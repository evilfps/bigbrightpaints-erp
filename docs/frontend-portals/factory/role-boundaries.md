# Factory Role Boundaries

- `ROLE_FACTORY` owns production execution, packing execution, dispatch queue
  handling, and dispatch confirmation.
- `ROLE_SALES` can read dispatch state but must not execute dispatch confirm.
- `ROLE_ACCOUNTING` owns finance correction after dispatch posting, not factory
  execution.
- `ROLE_ADMIN` may see high-level operational status through tenant tooling, but
  does not own factory screens.
- `ROLE_DEALER` never sees internal production or dispatch execution routes.

UI rules:

- Factory can read readiness blockers from upstream modules.
- Factory must not expose COA, default-account, settlement, or journal actions.
- Any cross-portal deep link should be read-only context or a handoff, not an
  embedded foreign workflow.
