# Delete-First Duplicates

This file records the seams that are already retired or must stay retired in the setup and execution flow.

## 1. Tenant Bootstrap Aliases

### Retired

- `POST /api/v1/companies`
- `POST /api/v1/companies/superadmin/tenants`
- `PUT /api/v1/companies/superadmin/tenants/{id}`

### Keep

- `POST /api/v1/superadmin/tenants/onboard`

## 2. Retired Stock-Bearing Setup Hosts

### Retired

- `legacy product-browse route`
- `legacy product-create route`
- `legacy preview product route`
- `legacy single-product route`
- `legacy bulk-product route`
- `legacy accounting-prefixed product setup routes`

### Keep

- `GET /api/v1/catalog/items`
- `GET /api/v1/catalog/items/{itemId}`
- `POST /api/v1/catalog/items`
- `PUT /api/v1/catalog/items/{itemId}`
- `DELETE /api/v1/catalog/items/{itemId}`

## 3. Duplicate Browse Hosts

### Retired

- `GET /api/v1/production/brands`
- `GET /api/v1/production/brands/{brandId}/products`

### Keep

- `GET /api/v1/catalog/brands`
- `GET /api/v1/catalog/items`

## 4. Opening-Stock Repair Behavior

### Retired

- raw-material auto-create during import
- finished-good auto-create during import
- `OPEN-BAL` auto-create during import
- legacy `X-Idempotency-Key`
- file-hash fallback when no explicit key is supplied

### Keep

- explicit `Idempotency-Key`
- fail-fast validation for orphan or not-ready SKUs
- import only after canonical item setup is complete

## 5. Execution Duplicates

### Retired

- `POST /api/v1/factory/production-batches`
- `POST /api/v1/factory/pack`
- `POST /api/v1/factory/packing-records/{productionLogId}/complete`
- `POST /api/v1/dispatch/confirm`

### Keep

- `POST /api/v1/factory/production/logs`
- `POST /api/v1/factory/packing-records`
- `POST /api/v1/dispatch/confirm`
- `GET /api/v1/dispatch/{pending,preview/{slipId},slip/{slipId},order/{orderId}}`

## Priority Rule

If future work reopens this area, deletion wins over compatibility glue. A proposal that restores any of the retired routes or fallback behaviors should be treated as a regression, not as a convenience feature.
