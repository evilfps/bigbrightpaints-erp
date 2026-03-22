# Delete-First Duplicates

This file records the seams that are already retired or must stay retired in
this setup flow.

## 1. Tenant Bootstrap Aliases

### Retired

- `POST /api/v1/companies`
- `POST /api/v1/companies/superadmin/tenants`
- `PUT /api/v1/companies/superadmin/tenants/{id}`

### Keep

- `POST /api/v1/superadmin/tenants/onboard`

## 2. Weak Product-Create Aliases

### Retired

- `POST /api/v1/catalog/products/single`
- `POST /api/v1/catalog/products/bulk-variants`

### Keep

- `POST /api/v1/catalog/products?preview=true`
- `POST /api/v1/catalog/products`

## 3. Duplicate Browse Hosts

### Retired

- `GET /api/v1/production/brands`
- `GET /api/v1/production/brands/{brandId}/products`

### Keep

- `GET /api/v1/catalog/brands`
- `GET /api/v1/catalog/products`

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
- import only after canonical product setup is complete

## 5. Hidden Identity Seams

Do not reintroduce setup flows that depend on ambiguous product names or ad hoc
code matching when canonical SKU truth already exists.

### Keep

- canonical SKU-based product truth
- canonical SKU-based finished-good and raw-material mirrors
- readiness surfaced directly from canonical truth

## Priority Rule

If future work reopens this area, deletion wins over compatibility glue. A
proposal that restores any of the retired routes or fallback behaviors should
be treated as a regression, not as a convenience feature.
