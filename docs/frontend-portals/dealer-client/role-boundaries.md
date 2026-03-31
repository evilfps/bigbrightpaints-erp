# Dealer Client Role Boundaries

- `ROLE_DEALER` owns self-service visibility: orders, invoices, ledger, aging,
  credit requests, and support tickets for their own company.
- `ROLE_SALES` owns internal dealer master editing and order creation.
- `ROLE_FACTORY` owns production, packing, dispatch preparation, and dispatch
  confirmation.
- `ROLE_ACCOUNTING` owns settlements, journals, reversals, and financial
  correction.
- `ROLE_ADMIN` owns tenant-wide approval shell and tenant administration.
- `ROLE_SUPER_ADMIN` owns platform control plane and tenant lifecycle.

UI rules:

- Dealer must never see sales-only route controls.
- Dealer must never see factory dispatch-confirm buttons.
- Dealer must never see accounting correction or admin approval workflows.
- All data access is scoped to the dealer's own `companyCode`.
