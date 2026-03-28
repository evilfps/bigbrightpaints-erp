# Accounting Role Boundaries

- `ROLE_ACCOUNTING` owns day-to-day finance setup, journals, reversal,
  reconciliation, opening stock review, settlements, and reports where backend
  policy allows.
- `ROLE_ADMIN` can access accounting screens and owns approve and reject close
  actions surfaced from `GET /api/v1/admin/approvals` where backend maker-checker
  policy requires tenant-admin authority.
- `ROLE_SUPER_ADMIN` is required for reopen and must be rendered only in the
  superadmin portal.
- `ROLE_FACTORY` may participate in opening-stock operations where backend
  policy allows, but not in COA, default accounts, tax setup, or journal
  governance.
- `ROLE_SALES` and `ROLE_DEALER` must never see manual journal, reversal, or
  period-close controls.
- Tenant-admin may expose the approval shell, but accounting owns the finance
  screens and finance remediation context.
- Sales, factory, and dealer portals may display accounting-derived status, but
  they do not own corrective accounting action.

UI ownership rules:

- Keep accounting navigation focused on finance actions and finance state.
- Do not place tenant onboarding, COA template selection, or tenant lifecycle
  actions in this portal even when accounting is blocked on those prerequisites.
- Do not expose superadmin shell features, tenant-user management, or factory
  execution controls in this portal.
- Where a screen depends on approval inbox state, deep-link into the shared
  approval experience without duplicating approval ownership rules.
