# Target Accounting Product-Entry Flow

This document records the intended accounting-facing flow that now matches the
surviving runtime contract.

## Product Goal

One screen and one canonical backend flow should be enough to:

- select an existing active brand or create a new brand explicitly first
- preview one SKU or a full color × size matrix
- commit that exact planned candidate set once
- guarantee production, inventory, and sales readiness without a second host

## Canonical Public Surface

- keep only `/api/v1/catalog/**` as the public catalog host
- keep brand creation separate from product creation
- keep preview and commit on the same canonical product endpoint

Canonical endpoints:

- `GET /api/v1/catalog/brands?active=true`
- `POST /api/v1/catalog/brands`
- `GET /api/v1/catalog/products`
- `POST /api/v1/catalog/products?preview=true`
- `POST /api/v1/catalog/products`

## End-To-End UX

### Step 1: Brand

The user can either:

- search/select an existing active brand from
  `GET /api/v1/catalog/brands?active=true`, or
- create a new brand on `POST /api/v1/catalog/brands` and then use the returned
  `brandId`

Backend rule:

- the product-entry payload must contain a resolved active `brandId`
- the separate brand-create step supplies that `brandId` before preview or
  commit

### Step 2: Base Product

The user provides:

- `baseProductName`
- `category`
- `unitOfMeasure`
- `hsnCode`
- `gstRate`
- optional `basePrice`
- optional `minDiscountPercent`
- optional `minSellingPrice`
- optional metadata needed by accounting or operations

### Step 3: Variants

The user provides canonical arrays:

- `colors[]`
- `sizes[]`

UI rule:

- delimiter-based quick input is UI-only convenience
- backend preview/commit must receive arrays and reject packed multi-value
  tokens

### Step 4: Preview

`POST /api/v1/catalog/products?preview=true` should show:

- every generated SKU member
- candidate count
- duplicate/conflict diagnostics
- shared `variantGroupId`
- downstream-effect summary for finished-good/raw-material mirrors

Important math:

- `4` sizes × `4` colors = `16` candidate members

### Step 5: Commit Once

`POST /api/v1/catalog/products` should:

1. validate the active `brandId`
2. persist the shared variant-group identity
3. create each SKU member
4. persist canonical product truth
5. create downstream inventory mirrors
6. return the committed member set

## Technical Shape

### One canonical write flow

- single-SKU and matrix creation land in the same write engine
- preview and commit use the same request shape
- downstream mirrors are guaranteed by that same write path
- SKU generation rules live in one place only

### Explicit variant grouping

Grouped members must persist a real shared identifier such as `variantGroupId`.
Family membership must not depend only on naming or SKU conventions.

## Downstream Guarantees

### Accounting

After commit, accounting can:

- browse the product family through `GET /api/v1/catalog/products`
- inspect the committed SKU members from the create response
- rely on the same public host used by the rest of the product-entry flow

### Production / Factory

After commit, production can:

- find the product through canonical browse/search
- use the stable brand/product identifiers in selection flows
- continue into production logs without manual catalog repair

### Inventory

After commit, inventory should already contain:

- finished-good truth for finished-good members
- raw-material truth for raw-material members
- zero-stock defaults until stock-in or production occurs

### Sales

After commit, sales can:

- resolve the SKU through canonical catalog browse/search
- use the ready inventory mirror and pricing/tax metadata
- avoid catalog-readiness failures caused by missing mirrors

## Example Canonical Payload Shape

Single SKU:

```json
{
  "brandId": 12,
  "baseProductName": "Primer",
  "category": "FINISHED_GOOD",
  "unitOfMeasure": "LITER",
  "sizes": ["1L"],
  "colors": ["WHITE"],
  "hsnCode": "3209",
  "gstRate": 18
}
```

Matrix create:

```json
{
  "brandId": 12,
  "baseProductName": "Premium Emulsion",
  "category": "FINISHED_GOOD",
  "unitOfMeasure": "LITER",
  "sizes": ["1L", "4L", "10L", "20L"],
  "colors": ["BLUE", "GREEN", "BLACK", "WHITE"],
  "hsnCode": "3209",
  "gstRate": 18,
  "basePrice": 1200
}
```

## Non-Goals

This flow does not reintroduce:

- alternate public catalog hosts
- a public bulk-create route
- pricing, valuation, or costing redesign beyond catalog readiness
