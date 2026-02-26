# Portal Role/Permissions Matrix (Backend-Aligned)

This matrix is built from `@PreAuthorize` rules in backend controllers. It is intended to help frontend devs know which pages to show per portal.

## Role ↔ Portal Mapping

- Admin Portal → `ROLE_ADMIN` (with `ROLE_SUPER_ADMIN` inheriting admin access via hierarchy)
- Accounting Portal → `ROLE_ACCOUNTING`
- Sales Portal → `ROLE_SALES`
- Dealer Portal → `ROLE_DEALER`
- Manufacturing Portal → `ROLE_FACTORY`
- Hierarchy rule used by method security: `ROLE_SUPER_ADMIN > ROLE_ADMIN`

## Legend

- R = Read/list/view
- W = Write (create/update/delete/confirm/post)
- A = Approve/Reject/Override
- P = Extra permission (named authority)
- S = Superadmin-only (`ROLE_SUPER_ADMIN`; plain `ROLE_ADMIN` denied)
- — = Not permitted by backend role guards

## Global / Shared Pages

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Login / Auth refresh / MFA / Profile | `/api/v1/auth/*` (incl. `/api/v1/auth/mfa/*`, `/api/v1/auth/profile`) | R/W | R/W | R/W | R/W | R/W |
| Company Switcher | `/api/v1/multi-company/companies/switch` | R/W | R/W | R/W | R/W | R/W |
| Company List | `/api/v1/companies` | R | R | R | — | — |

## Admin Portal

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Users | `/api/v1/admin/users` | R/W | — | — | — | — |
| Roles (RBAC) | `/api/v1/admin/roles` | R/W | — | — | — | — |
| System Settings | `/api/v1/admin/settings` | R/W | — | — | — | — |
| Admin Approvals (credit + payroll) | `/api/v1/admin/approvals` | R | R | — | — | — |
| Portal Insights (dashboard/ops/workforce) | `/api/v1/portal/*` | R | — | — | — | — |
| Tenant Bootstrap Onboarding | `POST /api/v1/companies` | S | — | — | — | — |
| Tenant Lifecycle Control | `POST /api/v1/companies/{id}/lifecycle-state` | S | — | — | — | — |
| Tenant Runtime Policy | `PUT /api/v1/companies/{id}/tenant-runtime/policy` | S | — | — | — | — |
| Tenant Metrics | `GET /api/v1/companies/{id}/tenant-metrics` | S | — | — | — | — |
| Tenant Admin Support Password Reset | `POST /api/v1/companies/{id}/support/admin-password-reset` | S | — | — | — | — |
| Company Update (Tenant Config) | `PUT /api/v1/companies/{id}` | S | — | — | — | — |

## Accounting Portal (includes HR + inventory reconciliation)

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Chart of Accounts | `/api/v1/accounting/accounts`, `/api/v1/accounting/accounts/tree*` | R/W | R/W | — | — | — |
| Default Accounts | `/api/v1/accounting/default-accounts` | R/W | R/W | — | — | — |
| Journal Entries (list/post/reverse) | `/api/v1/accounting/journal-entries*` | R/W | R/W | — | — | — |
| Dealer Receipts | `/api/v1/accounting/receipts/dealer*` | R/W | R/W | — | — | — |
| Dealer Settlements | `/api/v1/accounting/settlements/dealers` | R/W | R/W | — | — | — |
| Supplier Payments | `/api/v1/accounting/suppliers/payments` | R/W | R/W | — | — | — |
| Supplier Settlements | `/api/v1/accounting/settlements/suppliers` | R/W | R/W | — | — | — |
| Credit Notes / Debit Notes | `/api/v1/accounting/credit-notes`, `/api/v1/accounting/debit-notes` | R/W | R/W | — | — | — |
| Accruals | `/api/v1/accounting/accruals` | R/W | R/W | — | — | — |
| GST Return | `/api/v1/accounting/gst/return` | R | R | — | — | — |
| Bad Debt Write-off | `/api/v1/accounting/bad-debts/write-off` | R/W | R/W | — | — | — |
| Sales Returns (credit notes) | `/api/v1/accounting/sales/returns` | R/W | R/W | R | — | — |
| Accounting Periods / Month-End | `/api/v1/accounting/periods*`, `/api/v1/accounting/month-end/checklist*` | R/W | R/W | — | — | — |
| Statements (Dealer/Supplier) | `/api/v1/accounting/statements/*` | R | R | — | — | — |
| Aging (Dealer/Supplier) | `/api/v1/accounting/aging/*` | R | R | — | — | — |
| Inventory Valuation / WIP Adjustments | `/api/v1/accounting/inventory/*` | R/W | R/W | — | — | — |
| Audit Digest | `/api/v1/accounting/audit/digest*` | R | R | — | — | — |
| Temporal Balances | `/api/v1/accounting/accounts/*/balance*`, `/api/v1/accounting/trial-balance/as-of` | R | R | — | — | — |
| Accounting Reports (Aging/DSO/BS/IS hierarchy) | `/api/v1/accounting/reports/*` | R | R | — | — | — |
| Financial Reports | `/api/v1/reports/*` | R | R | — | — | — |
| Accounting Configuration Health | `/api/v1/accounting/configuration/health` | R | R | — | — | — |
| Catalog Import / Product Master (Accounting) | `/api/v1/accounting/catalog/*` | R/W | R/W | — | — | — |
| Payroll Payments (Accounting Post) | `/api/v1/accounting/payroll/*` | R/W | R/W | — | — | — |
| Inventory Adjustments (shrinkage/damage) | `/api/v1/inventory/adjustments` | R/W | R/W | — | — | — |
| Opening Stock Import | `/api/v1/inventory/opening-stock` | R/W | R/W | — | — | R/W |
| Raw Material Catalog + Stock | `/api/v1/accounting/raw-materials`, `/api/v1/raw-materials/stock*` | R/W | R/W | — | — | R |
| Raw Material Intake | `/api/v1/raw-materials/intake` | R/W | R/W | — | — | — |
| Suppliers (AP master) | `/api/v1/suppliers*` | R/W | R/W | — | — | R |
| Purchasing (PO/GRN/Raw Purchases) | `/api/v1/purchasing/*` | R/W | R/W | — | — | — |
| Purchase Returns | `/api/v1/purchasing/raw-material-purchases/returns` | R/W | R/W | — | — | — |
| HR Employees | `/api/v1/hr/employees*` | R/W | R/W | — | — | — |
| HR Attendance | `/api/v1/hr/attendance*` | R/W | R/W | — | — | — |
| HR Payroll Runs (HR module) | `/api/v1/hr/payroll-runs*` | R/W | R/W | — | — | — |
| Payroll Runs & Posting (Payroll module) | `/api/v1/payroll/*` | R/W | R/W | — | — | — |
| Credit Limit Override Approvals | `/api/v1/credit/override-requests/*` | A | A | — | — | — |

## Sales Portal

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Dealers (list/search/create/update) | `/api/v1/dealers*`, `/api/v1/sales/dealers*` | R/W | R/W | R/W | — | — |
| Dealer Ledger / Aging / Invoices | `/api/v1/dealers/{id}/ledger`, `/api/v1/dealers/{id}/aging`, `/api/v1/dealers/{id}/invoices` | R | R | R | R (scoped) | — |
| Sales Orders (list) | `/api/v1/sales/orders` | R | R | R | — | R |
| Sales Orders (create/update/confirm/cancel) | `/api/v1/sales/orders/*` | W | — | W | — | — |
| Dispatch Confirm (Sales-side) | `/api/v1/sales/dispatch/confirm` | W | W | W | — | — |
| Promotions | `/api/v1/sales/promotions*` | R/W | — | R/W | R | — |
| Sales Targets | `/api/v1/sales/targets*` | R/W | — | R/W | — | — |
| Credit Requests | `/api/v1/sales/credit-requests*` | R/W | — | R/W | — | — |
| Credit Limit Override (create) | `/api/v1/credit/override-requests` | R/W | — | R/W | — | R/W |
| Invoices (list/view/pdf/email) | `/api/v1/invoices*` | R/W | R/W | R/W | — | — |
| Finished Goods (for quoting/availability) | `/api/v1/finished-goods*` | R | R | R | — | R |
| Finished Goods Stock Summary | `/api/v1/finished-goods/stock-summary` | R | R | R | — | R |
| Low Stock List | `/api/v1/finished-goods/low-stock` | R | — | R | — | R |
| Production Catalog (brands/products) | `/api/v1/production/*` | R | R | R | — | R |

## Dealer Portal

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Dealer Dashboard | `/api/v1/dealer-portal/dashboard` | — | — | — | R | — |
| Dealer Ledger | `/api/v1/dealer-portal/ledger` | — | — | — | R | — |
| Dealer Invoices | `/api/v1/dealer-portal/invoices` | — | — | — | R | — |
| Dealer Aging | `/api/v1/dealer-portal/aging` | — | — | — | R | — |
| Dealer Orders | `/api/v1/dealer-portal/orders` | — | — | — | R | — |
| Dealer Invoice PDF | `/api/v1/dealer-portal/invoices/{invoiceId}/pdf` | — | — | — | R | — |
| Promotions (view) | `/api/v1/sales/promotions` | — | — | — | R | — |

## Manufacturing Portal

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Factory Dashboard | `/api/v1/factory/dashboard` | R | — | — | — | R |
| Production Plans | `/api/v1/factory/production-plans*` | R/W | — | — | — | R/W |
| Production Batches | `/api/v1/factory/production-batches*` | R/W | — | — | — | R/W |
| Factory Tasks | `/api/v1/factory/tasks*` | R/W | — | — | — | R/W |
| Production Logs | `/api/v1/factory/production/logs*` | R/W | — | — | — | R/W |
| Cost Allocation (factory) | `/api/v1/factory/cost-allocation` | R/W | — | — | — | R/W |
| Packing Records / Unpacked Batches / Packing History | `/api/v1/factory/packing-records*`, `/api/v1/factory/unpacked-batches`, `/api/v1/factory/production-logs/{id}/packing-history` | R/W* | R/W* | — | — | R/W* |
| Bulk Pack (bulk → size) | `/api/v1/factory/pack`, `/api/v1/factory/bulk-batches/*` | R/W | R/W | — | — | R/W |
| Packaging Size Mappings (list/active) | `/api/v1/factory/packaging-mappings*` | R | — | — | — | R |
| Packaging Size Mappings (create/update/deactivate) | `/api/v1/factory/packaging-mappings/*` | W | — | — | — | — |
| Dispatch Queue / Slip / Preview | `/api/v1/dispatch/*` (GETs) | R | — | R | — | R |
| Dispatch Confirm | `/api/v1/dispatch/confirm` | W + P | — | — | — | W + P |
| Dispatch Slip Status / Backorder Cancel | `/api/v1/dispatch/slip/*`, `/api/v1/dispatch/backorder/*` | W | — | — | — | W |
| Finished Goods Catalog / Batches | `/api/v1/finished-goods*` | R/W | — | R | — | R/W |
| Finished Goods Stock Summary | `/api/v1/finished-goods/stock-summary` | R | R | R | — | R |
| Low Stock | `/api/v1/finished-goods/low-stock` | R | — | R | — | R |
| Raw Materials (catalog/stock/batches) | `/api/v1/accounting/raw-materials*`, `/api/v1/raw-materials/stock*`, `/api/v1/raw-material-batches/*` | R/W | R/W | — | — | R/W |
| Raw Material Intake | `/api/v1/raw-materials/intake` | R/W | R/W | — | — | — |
| Suppliers (view) | `/api/v1/suppliers*` | R | R | — | — | R |

*Note: Packing endpoints do not declare `@PreAuthorize` on some methods. They may rely on global security filters. If strict role gating is required, enforce it in backend or add frontend gating conservatively.

## Sales + Accounting Shared (Cross-Portal)

| Page / Feature | Backend APIs | Admin | Accounting | Sales | Dealer | Manufacturing |
|---|---|---|---|---|---|---|
| Invoices (list/detail/pdf/email) | `/api/v1/invoices*` | R/W | R/W | R/W | — | — |
| Dealer Statements / Aging (Accounting views) | `/api/v1/accounting/statements/*`, `/api/v1/accounting/aging/*` | R | R | — | — | — |
| Dealer Ledger / Invoices / Aging (party views) | `/api/v1/dealers/{id}/*` | R | R | R | R (scoped) | — |
| Sales Returns (Accounting-driven) | `/api/v1/accounting/sales/returns` | R/W | R/W | R | — | — |

## Explicit Extra Authority

- Dispatch confirmation requires `ROLE_ADMIN` or `ROLE_FACTORY` **and** authority `dispatch.confirm`:
  - `POST /api/v1/dispatch/confirm`
- Company bootstrap payload contract for frontend forms: minimal required keys are `name`, `code`, `timezone`; omitted `defaultGstRate` falls back to `18`, explicit `defaultGstRate: 0` is preserved.
