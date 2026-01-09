# Accounting Onboarding Guide

This guide describes the admin-only onboarding flows for starting fresh from a manual/Tally migration.
All endpoints below require an admin account with `portal:accounting` and `onboarding.manage`.

## 1) Configure company defaults (required)

Set the default accounting mappings used for postings.

Endpoint:

- `PUT /api/v1/accounting/default-accounts`

Example payload:

```json
{
  "inventoryAccountId": 1001,
  "cogsAccountId": 5001,
  "revenueAccountId": 4001,
  "discountAccountId": 4002,
  "taxAccountId": 2101
}
```

Validate readiness:

- `GET /api/v1/accounting/configuration/health`

Account suggestions (read-only):

- `GET /api/v1/accounting/onboarding/account-suggestions`

This returns current default account IDs plus candidate lists for inventory, COGS, revenue, tax, WIP, and semi-finished selection.

## 2) Master data bootstrap

### Brands

- `GET /api/v1/accounting/onboarding/brands`
- `POST /api/v1/accounting/onboarding/brands`
- `PUT /api/v1/accounting/onboarding/brands/{brandId}`

### Product categories

- `GET /api/v1/accounting/onboarding/categories`
- `POST /api/v1/accounting/onboarding/categories`
- `PUT /api/v1/accounting/onboarding/categories/{categoryId}`

### Finished goods products

- `GET /api/v1/accounting/onboarding/products`
- `POST /api/v1/accounting/onboarding/products`
- `PUT /api/v1/accounting/onboarding/products/{productId}`

Example product create:

```json
{
  "brandName": "Big Bright",
  "brandCode": "BBP",
  "productName": "Interior Paint",
  "category": "FINISHED_GOOD",
  "unitOfMeasure": "UNIT",
  "customSkuCode": "PAINT-INT-001",
  "basePrice": 100.00,
  "gstRate": 0,
  "minDiscountPercent": 0,
  "minSellingPrice": 95.00,
  "metadata": {
    "wipAccountId": 1300,
    "semiFinishedAccountId": 1200
  }
}
```

For production-enabled products, include `metadata.wipAccountId` and `metadata.semiFinishedAccountId` so configuration health remains green.
Use the account suggestions endpoint to pick WIP/semi-finished asset accounts if you do not already have defaults.

### Product variants (size/color)

- `POST /api/v1/accounting/onboarding/products/variants`

### Raw materials

- `GET /api/v1/accounting/onboarding/raw-materials`
- `POST /api/v1/accounting/onboarding/raw-materials`
- `PUT /api/v1/accounting/onboarding/raw-materials/{id}`

Example raw material:

```json
{
  "name": "Steel Drum",
  "sku": "RM-DRUM-001",
  "unitType": "UNIT",
  "materialType": "PACKAGING",
  "reorderLevel": 0,
  "minStock": 0,
  "maxStock": 0
}
```

## 3) Trading partners

### Suppliers

- `GET /api/v1/accounting/onboarding/suppliers`
- `POST /api/v1/accounting/onboarding/suppliers`
- `PUT /api/v1/accounting/onboarding/suppliers/{id}`

### Dealers

- `GET /api/v1/accounting/onboarding/dealers`
- `POST /api/v1/accounting/onboarding/dealers`
- `PUT /api/v1/accounting/onboarding/dealers/{dealerId}`

## 4) Opening stock

Creates inventory batches, movements, and a balanced journal entry.

- `POST /api/v1/accounting/onboarding/opening-stock`

Example payload:

```json
{
  "referenceNumber": "OPEN-001",
  "entryDate": "2026-01-10",
  "offsetAccountId": 3001,
  "memo": "Opening stock",
  "finishedGoods": [
    {
      "productCode": "PAINT-INT-001",
      "quantity": 10,
      "unitCost": 12.50,
      "batchCode": "FG-OPEN-001",
      "manufacturedDate": "2026-01-10"
    }
  ],
  "rawMaterials": [
    {
      "sku": "RM-DRUM-001",
      "quantity": 50,
      "unitCost": 2.00,
      "unit": "UNIT",
      "batchCode": "RM-OPEN-001",
      "materialType": "PACKAGING"
    }
  ]
}
```

Retries are safe when you reuse the same reference number and batch codes.

## 5) Opening AR/AP (optional)

One partner per request.

- `POST /api/v1/accounting/onboarding/opening-balances/dealers`
- `POST /api/v1/accounting/onboarding/opening-balances/suppliers`

Example dealer opening balance:

```json
{
  "referenceNumber": "OPEN-AR-001",
  "entryDate": "2026-01-10",
  "offsetAccountId": 3001,
  "memo": "Opening receivable",
  "lines": [
    { "partnerId": 101, "amount": 1500.00, "memo": "Opening balance" }
  ]
}
```

## 6) Verify reconciliation

Use existing reports to confirm postings:

- `GET /api/v1/reports/inventory-reconciliation`
- `GET /api/v1/reports/reconciliation-dashboard`

For period close readiness, review the accounting period checklist in the accounting UI or the period APIs.
