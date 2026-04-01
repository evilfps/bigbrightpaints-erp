# Dealer Client Role Boundaries

- `ROLE_DEALER` owns every route in this folder.
- `ROLE_SALES`, `ROLE_FACTORY`, `ROLE_ACCOUNTING`, `ROLE_ADMIN`, and
  `ROLE_SUPER_ADMIN` must not share the dealer shell for their internal work.
- Dealer users can read dealer-facing status and create support or credit
  requests.
- Dealer users cannot approve credit, settle invoices, reverse journals, manage
  users, or execute dispatch.

UI rules:

- Never mix internal action menus into dealer detail screens.
- Any escalation from the dealer portal should create a request, not reveal the
  internal approval or correction UI.
- All data access is scoped to the dealer's own `companyCode`.
