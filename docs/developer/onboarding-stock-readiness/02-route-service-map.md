# Route and Service Map

This file inventories the controllers and services that own the current setup journey and the downstream execution handoff.

## Tenant Bootstrap

### `SuperAdminTenantOnboardingController`

- canonical routes:
  - `GET /api/v1/superadmin/tenants/coa-templates`
  - `POST /api/v1/superadmin/tenants/onboard`
- purpose:
  - expose the only seeded tenant bootstrap flow

### `TenantOnboardingService`

- purpose:
  - create company
  - seed chart of accounts
  - seed `OPEN-BAL`
  - set default account pointers
  - provision the first admin
  - ensure an open accounting period
  - initialize default system settings

### `CompanyController`

- retained routes in this packet:
  - `GET /api/v1/companies`
- depublished routes in this packet:
  - `/api/v1/companies/{id}` no longer exposes a live delete operation
- retired bootstrap aliases:
  - `POST /api/v1/companies`
  - `POST /api/v1/companies/superadmin/tenants`
  - `PUT /api/v1/companies/superadmin/tenants/{id}`

### `SuperAdminController`

- canonical tenant control routes adjacent to onboarding:
  - `GET /api/v1/superadmin/tenants`
  - `GET /api/v1/superadmin/tenants/{id}`
  - `PUT /api/v1/superadmin/tenants/{id}/lifecycle`
  - `PUT /api/v1/superadmin/tenants/{id}/limits`
  - `PUT /api/v1/superadmin/tenants/{id}/modules`
  - `POST /api/v1/superadmin/tenants/{id}/support/warnings`
  - `POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`

## Company Defaults

### `AccountingController`

- canonical routes:
  - `GET /api/v1/accounting/default-accounts`
  - `PUT /api/v1/accounting/default-accounts`

### `CompanyDefaultAccountsService`

- purpose:
  - own the tenant default inventory/COGS/revenue/discount/tax account set
  - validate referenced accounts inside the current company
  - provide the defaults consumed by stock-bearing item creation and update

## Stock-Bearing Item Entry

### `CatalogController`

- canonical routes:
  - `GET/POST /api/v1/catalog/brands`
  - `GET/POST /api/v1/catalog/items`
  - `GET/PUT/DELETE /api/v1/catalog/items/{itemId}`
  - `POST /api/v1/catalog/import` (adjunct import path)
- important behavior:
  - `CatalogService` owns the surviving item setup and readiness reads
  - `ProductionCatalogService.importCatalog(...)` remains import-only and must still land on the same item truth

### `CatalogService`

- purpose:
  - brand CRUD
  - item create/read/update/deactivate
  - attach readiness to canonical item reads
  - keep finished-good and raw-material mirrors aligned with item writes

### Retired setup hosts

- retired product-write hosts:
  - `legacy product-create route`
  - `legacy preview product route`
  - `legacy single-product route`
  - `legacy bulk-product route`
- retired accounting setup host:
  - `legacy accounting-prefixed product setup routes`

## Opening Stock

### `OpeningStockImportController`

- canonical routes:
  - `GET /api/v1/inventory/opening-stock`
  - `POST /api/v1/inventory/opening-stock`
- request contract:
  - multipart `file`
  - explicit `Idempotency-Key`
  - explicit `openingStockBatchKey`

### `OpeningStockImportService`

- purpose:
  - parse the mixed RM/FG CSV
  - verify prepared SKU readiness through `SkuReadinessService`
  - create batches and movements only for already-ready SKUs
  - post the opening-stock journal against `OPEN-BAL`
  - persist idempotent replay state keyed by explicit `Idempotency-Key` and `openingStockBatchKey`

## Execution Handoff

### `ProductionLogController` + `ProductionLogService`

- canonical route:
  - `POST /api/v1/factory/production/logs`
- purpose:
  - log the production batch and raw-material consumption
  - create the canonical batch record that becomes ready to pack

### `PackingController` + `PackingService`

- canonical route:
  - `POST /api/v1/factory/packing-records`
- supporting reads:
  - `GET /api/v1/factory/unpacked-batches`
  - `GET /api/v1/factory/production-logs/{productionLogId}/packing-history`
- hard-cut rules:
  - `Idempotency-Key` is required
  - `X-Idempotency-Key` is rejected
  - `X-Request-Id` is rejected
  - retired `/api/v1/factory/pack` and `/api/v1/factory/packing-records/{productionLogId}/complete` mutations stay retired

### `DispatchController`

- surviving routes:
  - `GET /api/v1/dispatch/pending`
  - `GET /api/v1/dispatch/preview/{slipId}`
  - `GET /api/v1/dispatch/slip/{slipId}`
  - `GET /api/v1/dispatch/order/{orderId}`
- purpose:
  - expose prepared-slip and challan reads only
  - keep factory/operator dispatch views redacted where required

### `DispatchController` + `SalesDispatchReconciliationService`

- canonical route:
  - `POST /api/v1/dispatch/confirm`
- purpose:
  - own the only surviving dispatch-confirm write
  - preserve downstream inventory and accounting posting consequences on the canonical dispatch path

## Readiness Ownership

### `SkuReadinessService`

- canonical readiness shape:
  - `catalog.ready + blockers[]`
  - `inventory.ready + blockers[]`
  - `production.ready + blockers[]`
  - `sales.ready + blockers[]`
- consumed by:
  - catalog item responses
  - opening-stock responses
  - pre-execution operator checks before batch -> pack -> dispatch
