# Endpoint Inventory (OpenAPI)

Source: `openapi.json`
Updated: 2026-02-16

Related behavior contract:
- `docs/PORTAL_DISCOUNT_REFERENCE_BEHAVIOR_GUIDE.md`
- `docs/ADMIN_APPROVAL_ACTION_DESCRIPTOR_MATRIX.md`
- `docs/P2P_PURCHASE_SETTLEMENT_BOUNDARY.md`
- `docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md`

Portal scope guardrail:
- HR, PURCHASING, INVENTORY, and REPORTS come under the Accounting portal in frontend scope.

## M18-S9A parity evidence

- OpenAPI snapshot: `openapi.json` (sha256 `33ddd163c102607970ff0f4c45e95f0c2a9d2965749187f68beb5c012216efa1`)
- OpenAPI total paths: `228` (includes non-v1 route `GET /api/integration/health`)
- OpenAPI total operations: `276`
- Accounting portal endpoint-map parity: `docs/accounting-portal-endpoint-map.md` carries `143` method+path entries over `119` unique paths; all are represented in this inventory and in `openapi.json`.

## Summary by module

| Module | Path count | Examples |
|---|---:|---|
| `accounting` | 60 | /api/v1/accounting/accounts, /api/v1/accounting/accounts/tree, /api/v1/accounting/accounts/tree/{type} |
| `admin` | 10 | /api/v1/admin/approvals, /api/v1/admin/notify, /api/v1/admin/roles |
| `audit` | 2 | /api/v1/audit/business-events, /api/v1/audit/ml-events |
| `auth` | 11 | /api/v1/auth/login, /api/v1/auth/logout, /api/v1/auth/me |
| `companies` | 2 | /api/v1/companies, /api/v1/companies/{id} |
| `credit` | 3 | /api/v1/credit/override-requests, /api/v1/credit/override-requests/{id}/approve, /api/v1/credit/override-requests/{id}/reject |
| `dealer-portal` | 7 | /api/v1/dealer-portal/aging, /api/v1/dealer-portal/credit-requests, /api/v1/dealer-portal/invoices |
| `dealers` | 7 | /api/v1/dealers, /api/v1/dealers/search, /api/v1/dealers/{dealerId} |
| `demo` | 1 | /api/v1/demo/ping |
| `dispatch` | 7 | /api/v1/dispatch/backorder/{slipId}/cancel, /api/v1/dispatch/confirm, /api/v1/dispatch/order/{orderId} |
| `factory` | 20 | /api/v1/factory/bulk-batches/{finishedGoodId}, /api/v1/factory/bulk-batches/{parentBatchId}/children, /api/v1/factory/cost-allocation |
| `finished-goods` | 5 | /api/v1/finished-goods, /api/v1/finished-goods/low-stock, /api/v1/finished-goods/stock-summary |
| `hr` | 11 | /api/v1/hr/attendance/bulk-mark, /api/v1/hr/attendance/date/{date}, /api/v1/hr/attendance/employee/{employeeId} |
| `inventory` | 2 | /api/v1/inventory/adjustments, /api/v1/inventory/opening-stock |
| `integration` | 1 | /api/integration/health |
| `invoices` | 5 | /api/v1/invoices, /api/v1/invoices/dealers/{dealerId}, /api/v1/invoices/{id} |
| `multi-company` | 1 | /api/v1/multi-company/companies/switch |
| `orchestrator` | 12 | /api/v1/orchestrator/dashboard/admin, /api/v1/orchestrator/dashboard/factory, /api/v1/orchestrator/dashboard/finance |
| `payroll` | 13 | /api/v1/payroll/runs, /api/v1/payroll/runs/monthly, /api/v1/payroll/runs/weekly |
| `portal` | 3 | /api/v1/portal/dashboard, /api/v1/portal/operations, /api/v1/portal/workforce |
| `production` | 2 | /api/v1/production/brands, /api/v1/production/brands/{brandId}/products |
| `purchasing` | 7 | /api/v1/purchasing/goods-receipts, /api/v1/purchasing/goods-receipts/{id}, /api/v1/purchasing/purchase-orders |
| `raw-material-batches` | 1 | /api/v1/raw-material-batches/{rawMaterialId} |
| `raw-materials` | 4 | /api/v1/raw-materials/intake, /api/v1/raw-materials/stock, /api/v1/raw-materials/stock/inventory |
| `reports` | 12 | /api/v1/reports/account-statement, /api/v1/reports/balance-sheet, /api/v1/reports/balance-warnings |
| `sales` | 17 | /api/v1/sales/credit-requests, /api/v1/sales/credit-requests/{id}, /api/v1/sales/credit-requests/{id}/approve |
| `suppliers` | 2 | /api/v1/suppliers, /api/v1/suppliers/{id} |

## High-risk duplicates / aliases (manual review)
M18-S3A decision guard source of truth:
- `docs/system-map/CROSS_MODULE_WORKFLOWS.md`

| Workflow | Canonical write endpoint(s) | Duplicate or alternate endpoint(s) | Decision |
|---|---|---|---|
| O2C | `POST /api/v1/sales/dispatch/confirm` | `POST /api/v1/dispatch/confirm` | keep |
| O2C | `POST /api/v1/sales/dispatch/confirm` | `POST /api/v1/orchestrator/dispatch`, `POST /api/v1/orchestrator/dispatch/{orderId}` | deprecate |
| O2C | `POST /api/v1/sales/dispatch/confirm` | `POST /api/v1/orchestrator/orders/{orderId}/fulfillment` (dispatch-status mutation) | merge |
| P2P | `POST /api/v1/purchasing/raw-material-purchases`, `POST /api/v1/accounting/suppliers/payments`, `POST /api/v1/accounting/settlements/suppliers` | `POST /api/v1/raw-materials/intake`, `POST /api/v1/inventory/opening-stock` | keep |
| Production-to-Pack | `POST /api/v1/factory/production/logs` | `POST /api/v1/factory/production-batches` | deprecate |
| Production-to-Pack | `POST /api/v1/factory/packing-records` | `POST /api/v1/factory/pack` | merge |
| Payroll | `POST /api/v1/payroll/runs`, `POST /api/v1/payroll/runs/{id}/calculate`, `POST /api/v1/payroll/runs/{id}/approve`, `POST /api/v1/payroll/runs/{id}/post`, `POST /api/v1/payroll/runs/{id}/mark-paid` | `POST /api/v1/hr/payroll-runs` | merge |
| Payroll | `POST /api/v1/payroll/runs*` | `POST /api/v1/orchestrator/payroll/run` | deprecate |

P2P boundary clarification (not duplicate semantics within canonical chain):
- `POST /api/v1/purchasing/raw-material-purchases` is purchase-invoice creation only.
- Supplier payment and settlement are accounting routes:
  - `POST /api/v1/accounting/suppliers/payments`
  - `POST /api/v1/accounting/settlements/suppliers`

## `accounting`

- `GET, POST` `/api/v1/accounting/accounts`
- `GET` `/api/v1/accounting/accounts/tree`
- `GET` `/api/v1/accounting/accounts/tree/{type}`
- `GET` `/api/v1/accounting/accounts/{accountId}/activity`
- `GET` `/api/v1/accounting/accounts/{accountId}/balance/as-of`
- `GET` `/api/v1/accounting/accounts/{accountId}/balance/compare`
- `POST` `/api/v1/accounting/accruals`
- `GET` `/api/v1/accounting/aging/dealers/{dealerId}`
- `GET` `/api/v1/accounting/aging/dealers/{dealerId}/pdf`
- `GET` `/api/v1/accounting/aging/suppliers/{supplierId}`
- `GET` `/api/v1/accounting/aging/suppliers/{supplierId}/pdf`
- `GET` `/api/v1/accounting/audit/digest`
- `GET` `/api/v1/accounting/audit/digest.csv`
- `GET` `/api/v1/accounting/audit/transactions`
- `GET` `/api/v1/accounting/audit/transactions/{journalEntryId}`
- `POST` `/api/v1/accounting/bad-debts/write-off`
- `POST` `/api/v1/accounting/catalog/import`
- `GET, POST` `/api/v1/accounting/catalog/products`
- `POST` `/api/v1/accounting/catalog/products/bulk-variants`
- `PUT` `/api/v1/accounting/catalog/products/{id}`
- `GET` `/api/v1/accounting/configuration/health`
- `POST` `/api/v1/accounting/credit-notes`
- `GET` `/api/v1/accounting/date-context`
- `POST` `/api/v1/accounting/debit-notes`
- `GET, PUT` `/api/v1/accounting/default-accounts`
- `GET` `/api/v1/accounting/gst/return`
- `POST` `/api/v1/accounting/inventory/landed-cost`
- `POST` `/api/v1/accounting/inventory/revaluation`
- `POST` `/api/v1/accounting/inventory/wip-adjustment`
- `GET, POST` `/api/v1/accounting/journal-entries`
- `POST` `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse`
- `POST` `/api/v1/accounting/journal-entries/{entryId}/reverse`
- `GET` `/api/v1/accounting/month-end/checklist`
- `POST` `/api/v1/accounting/month-end/checklist/{periodId}`
- `POST` `/api/v1/accounting/payroll/payments`
- `POST` `/api/v1/accounting/payroll/payments/batch`
- `GET` `/api/v1/accounting/periods`
- `POST` `/api/v1/accounting/periods/{periodId}/close`
- `POST` `/api/v1/accounting/periods/{periodId}/lock`
- `POST` `/api/v1/accounting/periods/{periodId}/reopen`
- `GET, POST` `/api/v1/accounting/raw-materials`
- `DELETE, PUT` `/api/v1/accounting/raw-materials/{id}`
- `POST` `/api/v1/accounting/receipts/dealer`
- `POST` `/api/v1/accounting/receipts/dealer/hybrid`
- `GET` `/api/v1/accounting/reports/aged-debtors`
- `GET` `/api/v1/accounting/reports/aging/dealer/{dealerId}`
- `GET` `/api/v1/accounting/reports/aging/dealer/{dealerId}/detailed`
- `GET` `/api/v1/accounting/reports/aging/receivables`
- `GET` `/api/v1/accounting/reports/balance-sheet/hierarchy`
- `GET` `/api/v1/accounting/reports/dso/dealer/{dealerId}`
- `GET` `/api/v1/accounting/reports/income-statement/hierarchy`
- `GET, POST` `/api/v1/accounting/sales/returns`
- `POST` `/api/v1/accounting/settlements/dealers`
- `POST` `/api/v1/accounting/settlements/suppliers`
- `GET` `/api/v1/accounting/statements/dealers/{dealerId}`
- `GET` `/api/v1/accounting/statements/dealers/{dealerId}/pdf`
- `GET` `/api/v1/accounting/statements/suppliers/{supplierId}`
- `GET` `/api/v1/accounting/statements/suppliers/{supplierId}/pdf`
- `POST` `/api/v1/accounting/suppliers/payments`
- `GET` `/api/v1/accounting/trial-balance/as-of`

## `admin`

- `GET` `/api/v1/admin/approvals`
- `POST` `/api/v1/admin/notify`
- `GET, POST` `/api/v1/admin/roles`
- `GET` `/api/v1/admin/roles/{roleKey}`
- `GET, PUT` `/api/v1/admin/settings`
- `GET, POST` `/api/v1/admin/users`
- `DELETE, PUT` `/api/v1/admin/users/{id}`
- `PATCH` `/api/v1/admin/users/{id}/mfa/disable`
- `PATCH` `/api/v1/admin/users/{id}/suspend`
- `PATCH` `/api/v1/admin/users/{id}/unsuspend`

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

## `companies`

- `GET, POST` `/api/v1/companies`
- `DELETE, PUT` `/api/v1/companies/{id}`

## `credit`

- `GET, POST` `/api/v1/credit/override-requests`
- `POST` `/api/v1/credit/override-requests/{id}/approve`
- `POST` `/api/v1/credit/override-requests/{id}/reject`

## `dealer-portal`

- `GET` `/api/v1/dealer-portal/aging`
- `POST` `/api/v1/dealer-portal/credit-requests`
- `GET` `/api/v1/dealer-portal/dashboard`
- `GET` `/api/v1/dealer-portal/invoices`
- `GET` `/api/v1/dealer-portal/invoices/{invoiceId}/pdf`
- `GET` `/api/v1/dealer-portal/ledger`
- `GET` `/api/v1/dealer-portal/orders`

## `dealers`

- `GET, POST` `/api/v1/dealers`
- `GET` `/api/v1/dealers/search`
- `PUT` `/api/v1/dealers/{dealerId}`
- `GET` `/api/v1/dealers/{dealerId}/aging`
- `POST` `/api/v1/dealers/{dealerId}/dunning/hold`
- `GET` `/api/v1/dealers/{dealerId}/invoices`
- `GET` `/api/v1/dealers/{dealerId}/ledger`

## `demo`

- `GET` `/api/v1/demo/ping`

## `dispatch`

- `POST` `/api/v1/dispatch/backorder/{slipId}/cancel`
- `POST` `/api/v1/dispatch/confirm`
- `GET` `/api/v1/dispatch/order/{orderId}`
- `GET` `/api/v1/dispatch/pending`
- `GET` `/api/v1/dispatch/preview/{slipId}`
- `GET` `/api/v1/dispatch/slip/{slipId}`
- `PATCH` `/api/v1/dispatch/slip/{slipId}/status`

## `factory`

- `GET` `/api/v1/factory/bulk-batches/{finishedGoodId}`
- `GET` `/api/v1/factory/bulk-batches/{parentBatchId}/children`
- `POST` `/api/v1/factory/cost-allocation`
- `GET` `/api/v1/factory/dashboard`
- `POST` `/api/v1/factory/pack`
- `GET, POST` `/api/v1/factory/packaging-mappings`
- `GET` `/api/v1/factory/packaging-mappings/active`
- `DELETE, PUT` `/api/v1/factory/packaging-mappings/{id}`
- `POST` `/api/v1/factory/packing-records`
- `POST` `/api/v1/factory/packing-records/{productionLogId}/complete`
- `GET, POST` `/api/v1/factory/production-batches`
- `GET` `/api/v1/factory/production-logs/{productionLogId}/packing-history`
- `GET, POST` `/api/v1/factory/production-plans`
- `DELETE, PUT` `/api/v1/factory/production-plans/{id}`
- `PATCH` `/api/v1/factory/production-plans/{id}/status`
- `GET, POST` `/api/v1/factory/production/logs`
- `GET` `/api/v1/factory/production/logs/{id}`
- `GET, POST` `/api/v1/factory/tasks`
- `PUT` `/api/v1/factory/tasks/{id}`
- `GET` `/api/v1/factory/unpacked-batches`

## `finished-goods`

- `GET, POST` `/api/v1/finished-goods`
- `GET` `/api/v1/finished-goods/low-stock`
- `GET` `/api/v1/finished-goods/stock-summary`
- `GET, PUT` `/api/v1/finished-goods/{id}`
- `GET, POST` `/api/v1/finished-goods/{id}/batches`

## `hr`

- `POST` `/api/v1/hr/attendance/bulk-mark`
- `GET` `/api/v1/hr/attendance/date/{date}`
- `GET` `/api/v1/hr/attendance/employee/{employeeId}`
- `POST` `/api/v1/hr/attendance/mark/{employeeId}`
- `GET` `/api/v1/hr/attendance/summary`
- `GET` `/api/v1/hr/attendance/today`
- `GET, POST` `/api/v1/hr/employees`
- `DELETE, PUT` `/api/v1/hr/employees/{id}`
- `GET, POST` `/api/v1/hr/leave-requests`
- `PATCH` `/api/v1/hr/leave-requests/{id}/status`
- `GET, POST` `/api/v1/hr/payroll-runs`

## `inventory`

- `GET, POST` `/api/v1/inventory/adjustments`
- `POST` `/api/v1/inventory/opening-stock`

## `integration`

- `GET` `/api/integration/health`

## `invoices`

- `GET` `/api/v1/invoices`
- `GET` `/api/v1/invoices/dealers/{dealerId}`
- `GET` `/api/v1/invoices/{id}`
- `POST` `/api/v1/invoices/{id}/email`
- `GET` `/api/v1/invoices/{id}/pdf`

## `multi-company`

- `POST` `/api/v1/multi-company/companies/switch`

## `orchestrator`

- `GET` `/api/v1/orchestrator/dashboard/admin`
- `GET` `/api/v1/orchestrator/dashboard/factory`
- `GET` `/api/v1/orchestrator/dashboard/finance`
- `POST` `/api/v1/orchestrator/dispatch`
- `POST` `/api/v1/orchestrator/dispatch/{orderId}`
- `POST` `/api/v1/orchestrator/factory/dispatch/{batchId}`
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
- `GET` `/api/v1/portal/operations`
- `GET` `/api/v1/portal/workforce`

## `production`

- `GET` `/api/v1/production/brands`
- `GET` `/api/v1/production/brands/{brandId}/products`

## `purchasing`

- `GET, POST` `/api/v1/purchasing/goods-receipts`
- `GET` `/api/v1/purchasing/goods-receipts/{id}`
- `GET, POST` `/api/v1/purchasing/purchase-orders`
- `GET` `/api/v1/purchasing/purchase-orders/{id}`
- `GET, POST` `/api/v1/purchasing/raw-material-purchases`
- `POST` `/api/v1/purchasing/raw-material-purchases/returns`
- `GET` `/api/v1/purchasing/raw-material-purchases/{id}`

## `raw-material-batches`

- `GET, POST` `/api/v1/raw-material-batches/{rawMaterialId}`

## `raw-materials`

- `POST` `/api/v1/raw-materials/intake`
- `GET` `/api/v1/raw-materials/stock`
- `GET` `/api/v1/raw-materials/stock/inventory`
- `GET` `/api/v1/raw-materials/stock/low-stock`

## `reports`

- `GET` `/api/v1/reports/account-statement`
- `GET` `/api/v1/reports/balance-sheet`
- `GET` `/api/v1/reports/balance-warnings`
- `GET` `/api/v1/reports/cash-flow`
- `GET` `/api/v1/reports/inventory-reconciliation`
- `GET` `/api/v1/reports/inventory-valuation`
- `GET` `/api/v1/reports/monthly-production-costs`
- `GET` `/api/v1/reports/production-logs/{id}/cost-breakdown`
- `GET` `/api/v1/reports/profit-loss`
- `GET` `/api/v1/reports/reconciliation-dashboard`
- `GET` `/api/v1/reports/trial-balance`
- `GET` `/api/v1/reports/wastage`

## `sales`

- `GET, POST` `/api/v1/sales/credit-requests`
- `PUT` `/api/v1/sales/credit-requests/{id}`
- `POST` `/api/v1/sales/credit-requests/{id}/approve`
- `POST` `/api/v1/sales/credit-requests/{id}/reject`
- `GET` `/api/v1/sales/dealers`
- `GET` `/api/v1/sales/dealers/search`
- `POST` `/api/v1/sales/dispatch/confirm`
- `POST` `/api/v1/sales/dispatch/reconcile-order-markers`
- `GET, POST` `/api/v1/sales/orders`
- `DELETE, PUT` `/api/v1/sales/orders/{id}`
- `POST` `/api/v1/sales/orders/{id}/cancel`
- `POST` `/api/v1/sales/orders/{id}/confirm`
- `PATCH` `/api/v1/sales/orders/{id}/status`
- `GET, POST` `/api/v1/sales/promotions`
- `DELETE, PUT` `/api/v1/sales/promotions/{id}`
- `GET, POST` `/api/v1/sales/targets`
- `DELETE, PUT` `/api/v1/sales/targets/{id}`

## `suppliers`

- `GET, POST` `/api/v1/suppliers`
- `GET, PUT` `/api/v1/suppliers/{id}`
