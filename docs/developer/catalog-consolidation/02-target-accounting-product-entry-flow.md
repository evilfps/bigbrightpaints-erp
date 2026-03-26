# Target Accounting Item-Entry Flow

This document records the accounting-facing item-entry flow that matches the surviving runtime contract.

## Product Goal

One screen and one canonical backend flow should be enough to:

- select an existing active brand or create a new brand explicitly first
- create or update one stock-bearing item at a time on the surviving host
- review readiness on the same canonical item surface before execution begins
- hand the item off into the canonical operator path without a second setup host

## Canonical Public Surface

- keep only `/api/v1/catalog/**` as the public stock-bearing setup host
- keep brand creation separate from item creation
- keep stock-bearing maintenance on `/api/v1/catalog/items`

Canonical endpoints:

- `GET /api/v1/catalog/brands?active=true`
- `POST /api/v1/catalog/brands`
- `GET /api/v1/catalog/items`
- `GET /api/v1/catalog/items/{itemId}`
- `POST /api/v1/catalog/items`
- `PUT /api/v1/catalog/items/{itemId}`
- `DELETE /api/v1/catalog/items/{itemId}`

## End-To-End UX

### Step 1: Brand

The user can either:

- search/select an existing active brand from `GET /api/v1/catalog/brands?active=true`, or
- create a new brand on `POST /api/v1/catalog/brands` and then use the returned `brandId`

Backend rule:

- the item payload must contain a resolved active `brandId`
- the separate brand-create step supplies that `brandId` before item create or update

### Step 2: Item details

The user provides a single-item payload such as:

- `brandId`
- `name`
- `itemClass`
- `unitOfMeasure`
- `hsnCode`
- `gstRate`
- optional `size`, `color`, `basePrice`, `minDiscountPercent`, `minSellingPrice`, and metadata

### Step 3: Save once

`POST /api/v1/catalog/items` should:

1. validate the active `brandId`
2. persist the canonical item truth
3. create or update downstream finished-good or raw-material mirrors as needed
4. return the saved `CatalogItemDto`

### Step 4: Readiness review

`GET /api/v1/catalog/items` and `GET /api/v1/catalog/items/{itemId}` with `includeReadiness=true` should show:

- item identity and class
- readiness state for `catalog`, `inventory`, `production`, and `sales`
- blocker details before operators reach factory execution

### Step 5: Execution handoff

Once readiness is clear, the operator path continues on:

- `POST /api/v1/factory/production/logs`
- `POST /api/v1/factory/packing-records`
- `POST /api/v1/dispatch/confirm`

`/api/v1/dispatch/**` is a read-only operational lookup surface, not a second dispatch-confirm write owner.

## Technical Shape

### One canonical write flow

- single-item setup lands on one write endpoint only
- readiness review happens on the same host as create/update
- downstream mirrors are guaranteed by the same setup path
- stock-bearing setup does not depend on retired preview/commit product routes

### Explicit operator handoff

- setup truth ends on `/api/v1/catalog/items`
- execution truth starts on production logs, then packing records, then factory-owned dispatch confirm
- no packet should present `legacy product routes` or `legacy accounting-prefixed product setup routes` as current setup truth

## Downstream Guarantees

### Accounting

After setup, accounting can:

- browse items through `GET /api/v1/catalog/items`
- inspect readiness without a separate accounting-prefixed setup host
- rely on the same stock-bearing setup host used by the rest of the operator story

### Production / Factory

After setup, factory can:

- discover ready inputs through canonical item reads
- create production batches on `POST /api/v1/factory/production/logs`
- pack sellable output through `POST /api/v1/factory/packing-records`

### Inventory

After setup, inventory should already contain the mirror truth needed for:

- raw-material consumption during production
- packaging setup validation during pack
- finished-good stock visibility before dispatch

### Sales

After setup and factory execution, sales can:

- resolve the sellable item through canonical catalog reads
- rely on packed output becoming dispatchable
- perform final dispatch posting only on `POST /api/v1/dispatch/confirm`

## Example Canonical Payload Shape

```json
{
  "brandId": 12,
  "name": "Premium Emulsion 20L White",
  "itemClass": "FINISHED_GOOD",
  "unitOfMeasure": "LITER",
  "size": "20L",
  "color": "WHITE",
  "hsnCode": "3209",
  "gstRate": 18,
  "basePrice": 1200
}
```

## Non-Goals

This flow does not reintroduce:

- alternate public catalog setup hosts
- preview/commit product-create flows under `legacy product routes`
- accounting-prefixed stock-bearing setup hosts
- factory-owned dispatch confirmation
