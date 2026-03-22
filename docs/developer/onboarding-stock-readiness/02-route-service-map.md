# Route and Service Map

This file inventories the controllers and services that own the current setup
journey.

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
  - `PUT /api/v1/companies/{id}`
  - tenant lifecycle/support/runtime routes under `/api/v1/companies/{id}/...`
- retired bootstrap aliases:
  - `POST /api/v1/companies`
  - `POST /api/v1/companies/superadmin/tenants`
  - `PUT /api/v1/companies/superadmin/tenants/{id}`

## Company Defaults

### `AccountingController`

- canonical routes:
  - `GET /api/v1/accounting/default-accounts`
  - `PUT /api/v1/accounting/default-accounts`

### `CompanyDefaultAccountsService`

- purpose:
  - own the tenant default inventory/COGS/revenue/discount/tax account set
  - validate referenced accounts inside the current company
  - provide the defaults consumed by stock-bearing product creation

## Stock-Bearing Product Entry

### `CatalogController`

- canonical routes:
  - `GET/POST /api/v1/catalog/brands`
  - `GET /api/v1/catalog/products`
  - `POST /api/v1/catalog/products`
  - `PUT /api/v1/catalog/products/{productId}`
- important behavior:
  - `POST /api/v1/catalog/products` delegates preview and commit to
    `ProductionCatalogService`

### `ProductionCatalogService`

- purpose:
  - canonical write engine for stock-bearing product entry
  - preview and commit from one request contract
  - persist `variantGroupId`
  - create/update finished-good mirrors
  - create/update raw-material mirrors
  - return readiness per created member

### `CatalogService`

- purpose:
  - brand CRUD
  - product browse/search
  - product maintenance reads and updates
  - attach readiness to catalog browse responses

### Retired create/browse seams

- retired create aliases:
  - `POST /api/v1/catalog/products/single`
  - `POST /api/v1/catalog/products/bulk-variants`
- retired duplicate browse host:
  - `GET /api/v1/production/brands`
  - `GET /api/v1/production/brands/{brandId}/products`

## Opening Stock

### `OpeningStockImportController`

- canonical routes:
  - `GET /api/v1/inventory/opening-stock`
  - `POST /api/v1/inventory/opening-stock`
- request contract:
  - multipart `file`
  - explicit `Idempotency-Key`

### `OpeningStockImportService`

- purpose:
  - parse the mixed RM/FG CSV
  - verify prepared SKU readiness through `SkuReadinessService`
  - create batches and movements only for already-ready SKUs
  - post the opening-stock journal against `OPEN-BAL`
  - persist idempotent replay state including `results[]` and `errors[]`

### Important hard-cut rules

- no `X-Idempotency-Key`
- no file-hash fallback
- no raw-material auto-create
- no finished-good auto-create
- no `OPEN-BAL` auto-create during import

## Readiness Ownership

### `SkuReadinessService`

- canonical readiness shape:
  - `catalog.ready + blockers[]`
  - `inventory.ready + blockers[]`
  - `production.ready + blockers[]`
  - `sales.ready + blockers[]`
- consumed by:
  - catalog product responses
  - opening-stock responses
  - strict import validation for prepared SKUs

### Representative blockers

- `PRODUCT_MASTER_MISSING`
- `RAW_MATERIAL_MIRROR_MISSING`
- `FINISHED_GOOD_MIRROR_MISSING`
- `RAW_MATERIAL_INVENTORY_ACCOUNT_MISSING`
- `FINISHED_GOOD_VALUATION_ACCOUNT_MISSING`
- `FINISHED_GOOD_COGS_ACCOUNT_MISSING`
- `FINISHED_GOOD_REVENUE_ACCOUNT_MISSING`
- `FINISHED_GOOD_TAX_ACCOUNT_MISSING`
- `WIP_ACCOUNT_MISSING`
- `LABOR_APPLIED_ACCOUNT_MISSING`
- `OVERHEAD_APPLIED_ACCOUNT_MISSING`
- `DISCOUNT_ACCOUNT_MISSING`
- `GST_OUTPUT_ACCOUNT_MISSING`
- `FINISHED_GOOD_GST_OUTPUT_ACCOUNT_MISMATCH`
- `NO_FINISHED_GOOD_BATCH_STOCK`
