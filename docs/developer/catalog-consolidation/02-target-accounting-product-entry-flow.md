# Target Accounting Product-Entry Flow

This document defines the simplified end-to-end flow the accounting team should
get after catalog consolidation.

## Product Goal

One screen and one canonical backend flow should be enough to:

- create a product under an existing brand
- create a product while creating a new brand inline
- create one SKU or a full color x size matrix
- guarantee that production, inventory, and sales can use the result

The accounting team should not need to understand which controller family owns
which side effect.

## Canonical Public Surface

- keep only `/api/v1/catalog/**` as the public catalog host
- retire `/api/v1/accounting/catalog/**` as a public wrapper host
- retire `/api/v1/production/**` as a competing catalog browse host

Canonical endpoints:

- `GET /api/v1/catalog/brands`
- `POST /api/v1/catalog/brands`
- `GET /api/v1/catalog/products`
- `POST /api/v1/catalog/products`

## End-To-End UX

### Step 1: Brand

The user should be able to:

- search and select an existing brand
- or create a new brand inline without leaving the flow

Backend rule:

- canonical payload should carry either a resolved `brandId` or a structured
  inline brand-create request handled by the same canonical flow

### Step 2: Base Product

The user should provide:

- base product name
- category
- unit family / unit of measure
- HSN and GST basics
- base price
- optional minimum discount / minimum selling rules
- optional product metadata needed for accounting or operations

### Step 3: Variants

The user should provide:

- colors
- sizes

UI rule:

- fast input like `blue/green/black` or `1L/4L/10L/20L` is allowed as UI sugar
- canonical backend contract should still receive arrays

### Step 4: Preview

Before saving, the screen should show:

- every generated SKU
- matrix size
- duplicate/conflict warnings
- whether the flow will create finished-good or raw-material mirrors

Important math:

- `4` sizes x `4` colors = `16` SKUs

### Step 5: Save Once

One submit action should:

1. create or resolve the brand
2. create a product family / variant group
3. create each SKU variant
4. persist the canonical product master rows
5. create downstream inventory mirrors
6. ensure accounting profile readiness for finished goods

## Target Technical Shape

## One Canonical Write Flow

- single-SKU and matrix creation should land in the same write engine
- downstream mirrors must be guaranteed by that engine
- SKU generation rules must live in one place only

## Explicit Variant Grouping

The persisted model should stop implying family membership only through naming.

Add a lightweight explicit grouping mechanism such as:

- `variant_group_id`
- `base_product_name`
- or an equivalent product-family model

The exact model can stay small, but family linkage must be real.

## Downstream Guarantees

### Accounting

After save, accounting should be able to:

- search the product immediately
- view every generated SKU
- trust that the SKU is operationally complete

### Production / Factory

After save, production should be able to:

- find the SKU in product selection
- use it in production logs
- create batches without manual repair

### Inventory

After save, inventory should show:

- finished-good mirror for finished goods
- raw-material mirror for raw materials
- zero stock until production or stock-in happens

### Sales

After save, sales should be able to:

- search the SKU
- price it using product truth
- reserve or evaluate availability using inventory truth
- avoid the current failure mode where `Finished good not configured for SKU`
  appears after product creation

## Example Canonical Payload Shape

Single SKU:

```json
{
  "brandId": 12,
  "baseProductName": "Primer",
  "category": "FINISHED_GOOD",
  "unitFamily": "LITER",
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
  "unitFamily": "LITER",
  "sizes": ["1L", "4L", "10L", "20L"],
  "colors": ["BLUE", "GREEN", "BLACK", "WHITE"],
  "hsnCode": "3209",
  "gstRate": 18,
  "basePrice": 1200
}
```

## Non-Goals

This flow does not require:

- a pricing-engine redesign
- a raw-material valuation redesign
- a production-costing redesign
- sales or dispatch workflow redesign outside catalog readiness

It is a host and flow consolidation packet, not a whole manufacturing rewrite.
