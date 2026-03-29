# Portal Matrix

| Folder | Primary route base | Backend prefixes this portal owns | Core screens | Must not own |
|---|---|---|---|---|
| `superadmin` | `/platform/*` | `/api/v1/superadmin/tenants/**`, `/api/v1/superadmin/changelog`, `/api/v1/superadmin/dashboard` | tenant list, tenant detail, tenant onboarding, lifecycle, limits, modules, support recovery, platform changelog | tenant user CRUD, approval inbox, accounting, factory, dealer self-service |
| `tenant-admin` | `/tenant/*` | `/api/v1/admin/users/**`, `GET /api/v1/admin/approvals`, `PUT /api/v1/admin/exports/{requestId}/approve`, `PUT /api/v1/admin/exports/{requestId}/reject`, `/api/v1/portal/support/tickets`, `/api/v1/changelog`, `GET /api/v1/auth/me` for shell bootstrap | session bootstrap, users, user detail, export approvals, support tickets, tenant changelog | superadmin onboarding, accounting journal work, dispatch confirm, production execution |
| `accounting` | `/accounting/*` | `/api/v1/accounting/**`, `GET /api/v1/reports/**`, `GET|POST /api/v1/inventory/opening-stock`, `GET|POST /api/v1/catalog/items` for finance readiness | COA, default accounts, GST or tax setup, product-account readiness, journals, reversals, reconciliation, period close, opening stock, AR/AP settlement, finance reports | tenant onboarding, tenant user CRUD, dispatch confirm, dealer self-service |
| `sales` | `/sales/*` | `/api/v1/sales/**`, `/api/v1/dealers/**`, `/api/v1/credit/**`, `GET /api/v1/dispatch/order/{orderId}`, order-linked `GET /api/v1/invoices/{id}` only | dealer master, sales orders, reservation visibility, commercial credit escalation, order-linked invoice follow-up, sales dashboards | dispatch confirm, standalone invoice inbox, production execution, journals, settlements, admin approvals |
| `factory` | `/factory/*` | `/api/v1/factory/**`, `GET /api/v1/dispatch/pending`, `GET /api/v1/dispatch/preview/{slipId}`, `GET /api/v1/dispatch/slip/{slipId}`, `GET /api/v1/dispatch/order/{orderId}`, `POST /api/v1/dispatch/confirm` | production logs, packing records, packaging mappings, batch lineage, dispatch preparation, dispatch detail, dispatch confirm | COA/default accounts/tax setup, settlements, tenant admin, dealer self-service, invoice browsing |
| `dealer-client` | `/dealer/*` | `/api/v1/dealer-portal/**`, dealer-scoped support and credit-request endpoints | dealer dashboard, order tracking, invoice review, ledger, aging, support, self-service credit request | internal dealer master, dispatch execution, accounting correction, admin approvals |

Placement rules:

- If the page mutates tenant lifecycle, limits, modules, or support recovery for another tenant, it belongs in `superadmin`.
- If the page manages users for the currently scoped tenant, it belongs in `tenant-admin`.
- If the page consumes `GET /api/v1/admin/approvals` for export decisions, it belongs in `tenant-admin`.
- If the page owns COA, default accounts, tax setup, product-account readiness, journal correction, reconciliation, or period close, it belongs in `accounting`.
- If the page owns dealer commercial intent, order capture, reservation visibility, or credit escalation, it belongs in `sales`.
- If the page owns production, packing, packaging, or dispatch confirmation, it belongs in `factory`.
- If the page is dealer-facing and self-service only, it belongs in `dealer-client`.
- If the page uses `GET /api/v1/auth/me` only for shell bootstrap, document the shell behavior in the relevant portal; do not create a duplicate auth-profile folder or screen.
- If a feature needs `ROLE_SUPER_ADMIN`, do not place it under `/tenant/*`.
