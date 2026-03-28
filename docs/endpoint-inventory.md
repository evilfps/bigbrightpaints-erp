# Endpoint Inventory (OpenAPI)

Source: `openapi.json`
Updated: 2026-03-28

Related behavior contract:
- `docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md`
- `docs/AUDIT_TRAIL_OWNERSHIP.md`
- `docs/accounting-portal-endpoint-map.md`
- `docs/accounting-portal-frontend-engineer-handoff.md`

Portal scope guardrail:
- HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

## Canonical API contract gate

- Canonical machine contract source: repo-root `openapi.json`.
- OpenAPI snapshot: `openapi.json` (sha256 `a37e423cf87722fb85e8116d3efbe5ec25f24a830a9dca4ccadecffe5b8d5ff0`)
- OpenAPI total paths: `280`
- OpenAPI total operations: `332`
- Guard remediation flow: if parity drifts, regenerate this inventory from canonical `openapi.json`, then rerun `bash scripts/guard_openapi_contract_drift.sh` and `bash scripts/guard_accounting_portal_scope_contract.sh`.

## Summary by module

| Module | Path count | Examples |
|---|---:|---|
| `accounting` | 65 | /api/v1/accounting/accounts, /api/v1/accounting/accounts/tree, /api/v1/accounting/accounts/tree/{type} |
| `admin` | 14 | /api/v1/admin/approvals, /api/v1/admin/exports/{requestId}/approve, /api/v1/admin/exports/{requestId}/reject |
| `audit` | 2 | /api/v1/audit/business-events, /api/v1/audit/ml-events |
| `auth` | 11 | /api/v1/auth/login, /api/v1/auth/logout, /api/v1/auth/me |
| `catalog` | 5 | /api/v1/catalog/brands, /api/v1/catalog/items, /api/v1/catalog/import |
| `changelog` | 2 | /api/v1/changelog, /api/v1/changelog/latest-highlighted |
| `companies` | 2 | /api/v1/companies, /api/v1/companies/{id} |
| `credit` | 6 | /api/v1/credit/limit-requests, /api/v1/credit/limit-requests/{id}/approve, /api/v1/credit/override-requests |
| `dealer-portal` | 7 | /api/v1/dealer-portal/aging, /api/v1/dealer-portal/credit-limit-requests, /api/v1/dealer-portal/dashboard |
| `dealers` | 4 | /api/v1/dealers, /api/v1/dealers/search, /api/v1/dealers/{dealerId} |
| `demo` | 1 | /api/v1/demo/ping |
| `dispatch` | 8 | /api/v1/dispatch/confirm, /api/v1/dispatch/order/{orderId}, /api/v1/dispatch/slip/{slipId}/status |
| `exports` | 2 | /api/v1/exports/request, /api/v1/exports/{requestId}/download |
| `factory` | 19 | /api/v1/factory/bulk-batches/{finishedGoodId}, /api/v1/factory/bulk-batches/{parentBatchId}/children, /api/v1/factory/cost-allocation |
| `finished-goods` | 6 | /api/v1/finished-goods, /api/v1/finished-goods/low-stock, /api/v1/finished-goods/stock-summary |
| `hr` | 17 | /api/v1/hr/attendance/bulk-import, /api/v1/hr/attendance/bulk-mark, /api/v1/hr/attendance/date/{date} |
| `integration` | 1 | /api/integration/health |
| `inventory` | 5 | /api/v1/inventory/adjustments, /api/v1/inventory/batches/expiring-soon, /api/v1/inventory/batches/{id}/movements |
| `invoices` | 4 | /api/v1/invoices, /api/v1/invoices/{id}, /api/v1/invoices/{id}/email |
| `migration` | 1 | /api/v1/migration/tally-import |
| `orchestrator` | 9 | /api/v1/orchestrator/dashboard/admin, /api/v1/orchestrator/dashboard/factory, /api/v1/orchestrator/dashboard/finance |
| `payroll` | 13 | /api/v1/payroll/runs, /api/v1/payroll/runs/monthly, /api/v1/payroll/runs/weekly |
| `portal` | 6 | /api/v1/portal/dashboard, /api/v1/portal/finance/aging, /api/v1/portal/finance/ledger |
| `purchasing` | 12 | /api/v1/purchasing/goods-receipts, /api/v1/purchasing/goods-receipts/{id}, /api/v1/purchasing/purchase-orders |
| `raw-materials` | 3 | /api/v1/raw-materials/stock, /api/v1/raw-materials/stock/inventory, /api/v1/raw-materials/stock/low-stock |
| `reports` | 17 | /api/v1/reports/account-statement, /api/v1/reports/aged-debtors, /api/v1/reports/aging/receivables |
| `sales` | 16 | /api/v1/sales/dashboard, /api/v1/sales/dealers, /api/v1/sales/dealers/search |
| `superadmin` | 17 | /api/v1/superadmin/changelog, /api/v1/superadmin/changelog/{id}, /api/v1/superadmin/dashboard |
| `suppliers` | 5 | /api/v1/suppliers, /api/v1/suppliers/{id}, /api/v1/suppliers/{id}/activate |
| `support` | 4 | /api/v1/portal/support/tickets, /api/v1/dealer-portal/support/tickets, /api/v1/portal/support/tickets/{ticketId} |

## `accounting`

- `GET, POST` `/api/v1/accounting/accounts`
- `GET` `/api/v1/accounting/accounts/tree`
- `GET` `/api/v1/accounting/accounts/tree/{type}`
- `GET` `/api/v1/accounting/accounts/{accountId}/activity`
- `GET` `/api/v1/accounting/accounts/{accountId}/balance/as-of`
- `GET` `/api/v1/accounting/accounts/{accountId}/balance/compare`
- `POST` `/api/v1/accounting/accruals`
- `GET` `/api/v1/accounting/aging/suppliers/{supplierId}`
- `GET` `/api/v1/accounting/aging/suppliers/{supplierId}/pdf`
- `GET` `/api/v1/accounting/audit-trail`
- `GET` `/api/v1/accounting/audit/digest`
- `GET` `/api/v1/accounting/audit/digest.csv`
- `GET` `/api/v1/accounting/audit/transactions`
- `GET` `/api/v1/accounting/audit/transactions/{journalEntryId}`
- `POST` `/api/v1/accounting/bad-debts/write-off`
- `GET` `/api/v1/accounting/configuration/health`
- `POST` `/api/v1/accounting/credit-notes`
- `GET` `/api/v1/accounting/date-context`
- `POST` `/api/v1/accounting/dealers/{dealerId}/auto-settle`
- `POST` `/api/v1/accounting/debit-notes`
- `GET, PUT` `/api/v1/accounting/default-accounts`
- `GET` `/api/v1/accounting/gst/reconciliation`
- `GET` `/api/v1/accounting/gst/return`
- `POST` `/api/v1/accounting/inventory/landed-cost`
- `POST` `/api/v1/accounting/inventory/revaluation`
- `POST` `/api/v1/accounting/inventory/wip-adjustment`
- `GET, POST` `/api/v1/accounting/journal-entries`
- `POST` `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse`
- `POST` `/api/v1/accounting/journal-entries/{entryId}/reverse`
- `GET` `/api/v1/accounting/journals`
- `POST` `/api/v1/accounting/journals/manual`
- `POST` `/api/v1/accounting/journals/{entryId}/reverse`
- `GET` `/api/v1/accounting/month-end/checklist`
- `POST` `/api/v1/accounting/month-end/checklist/{periodId}`
- `POST` `/api/v1/accounting/opening-balances`
- `POST` `/api/v1/accounting/payroll/payments`
- `POST` `/api/v1/accounting/payroll/payments/batch`
- `GET, POST` `/api/v1/accounting/periods`
- `PUT` `/api/v1/accounting/periods/{periodId}`
- `POST` `/api/v1/accounting/periods/{periodId}/approve-close`
- `POST` `/api/v1/accounting/periods/{periodId}/close`
- `POST` `/api/v1/accounting/periods/{periodId}/lock`
- `POST` `/api/v1/accounting/periods/{periodId}/reject-close`
- `POST` `/api/v1/accounting/periods/{periodId}/reopen`
- `POST` `/api/v1/accounting/periods/{periodId}/request-close`
- `POST` `/api/v1/accounting/receipts/dealer`
- `POST` `/api/v1/accounting/receipts/dealer/hybrid`
- `POST` `/api/v1/accounting/reconciliation/bank`
- `GET, POST` `/api/v1/accounting/reconciliation/bank/sessions`
- `GET` `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}`
- `POST` `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`
- `PUT` `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`
- `GET` `/api/v1/accounting/reconciliation/discrepancies`
- `POST` `/api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve`
- `GET` `/api/v1/accounting/reconciliation/inter-company`
- `GET` `/api/v1/accounting/reconciliation/subledger`
- `GET, POST` `/api/v1/accounting/sales/returns`
- `POST` `/api/v1/accounting/sales/returns/preview`
- `POST` `/api/v1/accounting/settlements/dealers`
- `POST` `/api/v1/accounting/settlements/suppliers`
- `GET` `/api/v1/accounting/statements/suppliers/{supplierId}`
- `GET` `/api/v1/accounting/statements/suppliers/{supplierId}/pdf`
- `POST` `/api/v1/accounting/suppliers/{supplierId}/auto-settle`
- `GET` `/api/v1/accounting/trial-balance/as-of`

## `admin`

- `GET` `/api/v1/admin/approvals`
- `PUT` `/api/v1/admin/exports/{requestId}/approve`
- `PUT` `/api/v1/admin/exports/{requestId}/reject`
- `POST` `/api/v1/admin/notify`
- `GET, POST` `/api/v1/admin/roles`
- `GET` `/api/v1/admin/roles/{roleKey}`
- `GET, PUT` `/api/v1/admin/settings`
- `GET, POST` `/api/v1/admin/users`
- `PUT, DELETE` `/api/v1/admin/users/{id}`
- `PATCH` `/api/v1/admin/users/{id}/mfa/disable`
- `PATCH` `/api/v1/admin/users/{id}/suspend`
- `PATCH` `/api/v1/admin/users/{id}/unsuspend`
- `POST` `/api/v1/admin/users/{userId}/force-reset-password`
- `PUT` `/api/v1/admin/users/{userId}/status`

## `audit`

- `GET` `/api/v1/audit/business-events`
- `GET, POST` `/api/v1/audit/ml-events`

## `auth`

- `POST` `/api/v1/auth/login`
- `POST` `/api/v1/auth/logout`
- `GET` `/api/v1/auth/me`
- `POST` `/api/v1/auth/mfa/activate`
- `POST` `/api/v1/auth/mfa/disable`
- `POST` `/api/v1/auth/mfa/setup`
- `POST` `/api/v1/auth/password/change`
- `POST` `/api/v1/auth/password/forgot`
- `POST` `/api/v1/auth/password/reset`
- `GET, PUT` `/api/v1/auth/profile`
- `POST` `/api/v1/auth/refresh-token`

## `catalog`

- `GET, POST` `/api/v1/catalog/brands`
- `GET, PUT, DELETE` `/api/v1/catalog/brands/{brandId}`
- `POST` `/api/v1/catalog/import`
- `GET, POST` `/api/v1/catalog/items`
- `GET, PUT, DELETE` `/api/v1/catalog/items/{itemId}`

## `changelog`

- `GET` `/api/v1/changelog`
- `GET` `/api/v1/changelog/latest-highlighted`

## `companies`

- `GET` `/api/v1/companies`
- `DELETE` `/api/v1/companies/{id}`

## `credit`

- `GET, POST` `/api/v1/credit/limit-requests`
- `POST` `/api/v1/credit/limit-requests/{id}/approve`
- `POST` `/api/v1/credit/limit-requests/{id}/reject`
- `GET, POST` `/api/v1/credit/override-requests`
- `POST` `/api/v1/credit/override-requests/{id}/approve`
- `POST` `/api/v1/credit/override-requests/{id}/reject`

## `dealer-portal`

- `GET` `/api/v1/dealer-portal/aging`
- `POST` `/api/v1/dealer-portal/credit-limit-requests`
- `GET` `/api/v1/dealer-portal/dashboard`
- `GET` `/api/v1/dealer-portal/invoices`
- `GET` `/api/v1/dealer-portal/invoices/{invoiceId}/pdf`
- `GET` `/api/v1/dealer-portal/ledger`
- `GET` `/api/v1/dealer-portal/orders`

## `dealers`

- `GET, POST` `/api/v1/dealers`
- `GET` `/api/v1/dealers/search`
- `PUT` `/api/v1/dealers/{dealerId}`
- `POST` `/api/v1/dealers/{dealerId}/dunning/hold`

## `demo`

- `GET` `/api/v1/demo/ping`

## `dispatch`

- `POST` `/api/v1/dispatch/backorder/{slipId}/cancel`
- `POST` `/api/v1/dispatch/confirm`
- `GET` `/api/v1/dispatch/order/{orderId}`
- `GET` `/api/v1/dispatch/pending`
- `GET` `/api/v1/dispatch/preview/{slipId}`
- `GET` `/api/v1/dispatch/slip/{slipId}`
- `GET` `/api/v1/dispatch/slip/{slipId}/challan/pdf`
- `PATCH` `/api/v1/dispatch/slip/{slipId}/status`

## `exports`

- `POST` `/api/v1/exports/request`
- `GET` `/api/v1/exports/{requestId}/download`

## `factory`

- `GET` `/api/v1/factory/bulk-batches/{finishedGoodId}`
- `GET` `/api/v1/factory/bulk-batches/{parentBatchId}/children`
- `POST` `/api/v1/factory/cost-allocation`
- `GET` `/api/v1/factory/dashboard`
- `GET, POST` `/api/v1/factory/packaging-mappings`
- `GET` `/api/v1/factory/packaging-mappings/active`
- `PUT, DELETE` `/api/v1/factory/packaging-mappings/{id}`
- `POST` `/api/v1/factory/packing-records`
- `GET` `/api/v1/factory/production-logs/{productionLogId}/packing-history`
- `GET, POST` `/api/v1/factory/production-plans`
- `PUT, DELETE` `/api/v1/factory/production-plans/{id}`
- `PATCH` `/api/v1/factory/production-plans/{id}/status`
- `GET, POST` `/api/v1/factory/production/logs`
- `GET` `/api/v1/factory/production/logs/{id}`
- `GET, POST` `/api/v1/factory/tasks`
- `PUT` `/api/v1/factory/tasks/{id}`
- `GET` `/api/v1/factory/unpacked-batches`

Factory operator note: treat `/api/v1/factory/packaging-mappings` as the Packaging Setup / Rules contract. Pack requests fail closed when a size is missing active, usable packaging setup, when `Idempotency-Key` is missing, or when legacy replay headers are sent.
Bulk operator note: `/api/v1/factory/bulk-batches/{finishedGoodId}` is read-only and resolves semi-finished inventory through canonical catalog product-family mapping; `-BULK` stays internal raw-material truth and is not a finished-good contract.

## `finished-goods`

- `GET` `/api/v1/finished-goods`
- `GET` `/api/v1/finished-goods/low-stock`
- `GET` `/api/v1/finished-goods/stock-summary`
- `GET` `/api/v1/finished-goods/{id}`
- `GET` `/api/v1/finished-goods/{id}/batches`
- `GET, PUT` `/api/v1/finished-goods/{id}/low-stock-threshold`

## `hr`

- `POST` `/api/v1/hr/attendance/bulk-import`
- `POST` `/api/v1/hr/attendance/bulk-mark`
- `GET` `/api/v1/hr/attendance/date/{date}`
- `GET` `/api/v1/hr/attendance/employee/{employeeId}`
- `POST` `/api/v1/hr/attendance/mark/{employeeId}`
- `GET` `/api/v1/hr/attendance/summary`
- `GET` `/api/v1/hr/attendance/summary/monthly`
- `GET` `/api/v1/hr/attendance/today`
- `GET, POST` `/api/v1/hr/employees`
- `GET` `/api/v1/hr/employees/{employeeId}/leave-balances`
- `PUT, DELETE` `/api/v1/hr/employees/{id}`
- `GET, POST` `/api/v1/hr/leave-requests`
- `PATCH` `/api/v1/hr/leave-requests/{id}/status`
- `GET` `/api/v1/hr/leave-types`
- `GET, POST` `/api/v1/hr/payroll-runs`
- `GET, POST` `/api/v1/hr/salary-structures`
- `PUT` `/api/v1/hr/salary-structures/{id}`

## `integration`

- `GET` `/api/integration/health`

## `inventory`

- `GET, POST` `/api/v1/inventory/adjustments`
- `GET` `/api/v1/inventory/batches/expiring-soon`
- `GET` `/api/v1/inventory/batches/{id}/movements`
- `GET, POST` `/api/v1/inventory/opening-stock`
- `POST` `/api/v1/inventory/raw-materials/adjustments`

## `invoices`

- `GET` `/api/v1/invoices`
- `GET` `/api/v1/invoices/{id}`
- `POST` `/api/v1/invoices/{id}/email`
- `GET` `/api/v1/invoices/{id}/pdf`

## `migration`

- `POST` `/api/v1/migration/tally-import`

## `orchestrator`

- `GET` `/api/v1/orchestrator/dashboard/admin`
- `GET` `/api/v1/orchestrator/dashboard/factory`
- `GET` `/api/v1/orchestrator/dashboard/finance`
- `GET` `/api/v1/orchestrator/health/events`
- `GET` `/api/v1/orchestrator/health/integrations`
- `POST` `/api/v1/orchestrator/orders/{orderId}/approve`
- `POST` `/api/v1/orchestrator/orders/{orderId}/fulfillment`
- `POST` `/api/v1/orchestrator/payroll/run`
- `GET` `/api/v1/orchestrator/traces/{traceId}`

## `payroll`

- `GET, POST` `/api/v1/payroll/runs`
- `GET, POST` `/api/v1/payroll/runs/monthly`
- `GET, POST` `/api/v1/payroll/runs/weekly`
- `GET` `/api/v1/payroll/runs/{id}`
- `POST` `/api/v1/payroll/runs/{id}/approve`
- `POST` `/api/v1/payroll/runs/{id}/calculate`
- `GET` `/api/v1/payroll/runs/{id}/lines`
- `POST` `/api/v1/payroll/runs/{id}/mark-paid`
- `POST` `/api/v1/payroll/runs/{id}/post`
- `GET` `/api/v1/payroll/summary/current-month`
- `GET` `/api/v1/payroll/summary/current-week`
- `GET` `/api/v1/payroll/summary/monthly`
- `GET` `/api/v1/payroll/summary/weekly`

## `portal`

- `GET` `/api/v1/portal/dashboard`
- `GET` `/api/v1/portal/finance/aging`
- `GET` `/api/v1/portal/finance/invoices`
- `GET` `/api/v1/portal/finance/ledger`
- `GET` `/api/v1/portal/operations`
- `GET` `/api/v1/portal/workforce`

## `purchasing`

- `GET, POST` `/api/v1/purchasing/goods-receipts`
- `GET` `/api/v1/purchasing/goods-receipts/{id}`
- `GET, POST` `/api/v1/purchasing/purchase-orders`
- `GET` `/api/v1/purchasing/purchase-orders/{id}`
- `POST` `/api/v1/purchasing/purchase-orders/{id}/approve`
- `POST` `/api/v1/purchasing/purchase-orders/{id}/close`
- `GET` `/api/v1/purchasing/purchase-orders/{id}/timeline`
- `POST` `/api/v1/purchasing/purchase-orders/{id}/void`
- `GET, POST` `/api/v1/purchasing/raw-material-purchases`
- `POST` `/api/v1/purchasing/raw-material-purchases/returns`
- `POST` `/api/v1/purchasing/raw-material-purchases/returns/preview`
- `GET` `/api/v1/purchasing/raw-material-purchases/{id}`

## `raw-materials`

- `GET` `/api/v1/raw-materials/stock`
- `GET` `/api/v1/raw-materials/stock/inventory`
- `GET` `/api/v1/raw-materials/stock/low-stock`

## `reports`

- `GET` `/api/v1/reports/account-statement`
- `GET` `/api/v1/reports/aged-debtors`
- `GET` `/api/v1/reports/aging/receivables`
- `GET` `/api/v1/reports/balance-sheet`
- `GET` `/api/v1/reports/balance-sheet/hierarchy`
- `GET` `/api/v1/reports/balance-warnings`
- `GET` `/api/v1/reports/cash-flow`
- `GET` `/api/v1/reports/gst-return`
- `GET` `/api/v1/reports/income-statement/hierarchy`
- `GET` `/api/v1/reports/inventory-reconciliation`
- `GET` `/api/v1/reports/inventory-valuation`
- `GET` `/api/v1/reports/monthly-production-costs`
- `GET` `/api/v1/reports/production-logs/{id}/cost-breakdown`
- `GET` `/api/v1/reports/profit-loss`
- `GET` `/api/v1/reports/reconciliation-dashboard`
- `GET` `/api/v1/reports/trial-balance`
- `GET` `/api/v1/reports/wastage`

## `sales`

- `GET` `/api/v1/sales/dashboard`
- `GET` `/api/v1/sales/dealers`
- `GET` `/api/v1/sales/dealers/search`
- `POST` `/api/v1/sales/dispatch/reconcile-order-markers`
- `GET, POST` `/api/v1/sales/orders`
- `GET` `/api/v1/sales/orders/search`
- `PUT, DELETE` `/api/v1/sales/orders/{id}`
- `POST` `/api/v1/sales/orders/{id}/cancel`
- `POST` `/api/v1/sales/orders/{id}/confirm`
- `PATCH` `/api/v1/sales/orders/{id}/status`
- `GET` `/api/v1/sales/orders/{id}/timeline`
- `GET, POST` `/api/v1/sales/promotions`
- `PUT, DELETE` `/api/v1/sales/promotions/{id}`
- `GET, POST` `/api/v1/sales/targets`
- `PUT, DELETE` `/api/v1/sales/targets/{id}`

## `superadmin`

- `POST` `/api/v1/superadmin/changelog`
- `DELETE, PUT` `/api/v1/superadmin/changelog/{id}`
- `GET` `/api/v1/superadmin/dashboard`
- `GET` `/api/v1/superadmin/tenants`
- `GET` `/api/v1/superadmin/tenants/coa-templates`
- `POST` `/api/v1/superadmin/tenants/onboard`
- `GET` `/api/v1/superadmin/tenants/{id}`
- `PUT` `/api/v1/superadmin/tenants/{id}/admins/main`
- `POST` `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/confirm`
- `POST` `/api/v1/superadmin/tenants/{id}/admins/{adminId}/email-change/request`
- `POST` `/api/v1/superadmin/tenants/{id}/force-logout`
- `PUT` `/api/v1/superadmin/tenants/{id}/lifecycle`
- `PUT` `/api/v1/superadmin/tenants/{id}/limits`
- `PUT` `/api/v1/superadmin/tenants/{id}/modules`
- `POST` `/api/v1/superadmin/tenants/{id}/support/admin-password-reset`
- `PUT` `/api/v1/superadmin/tenants/{id}/support/context`
- `POST` `/api/v1/superadmin/tenants/{id}/support/warnings`

## `suppliers`

- `GET, POST` `/api/v1/suppliers`
- `GET, PUT` `/api/v1/suppliers/{id}`
- `POST` `/api/v1/suppliers/{id}/activate`
- `POST` `/api/v1/suppliers/{id}/approve`
- `POST` `/api/v1/suppliers/{id}/suspend`

## `support`

- `GET, POST` `/api/v1/portal/support/tickets`
- `GET` `/api/v1/portal/support/tickets/{ticketId}`
- `GET, POST` `/api/v1/dealer-portal/support/tickets`
- `GET` `/api/v1/dealer-portal/support/tickets/{ticketId}`
