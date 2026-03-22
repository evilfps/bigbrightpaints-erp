# Catalog Consolidation Scope And Definition Of Done

This file captures the accepted packet scope for the surviving catalog contract.

## Why This Is Parallel-Safe

This packet is limited to:

- catalog route ownership
- product/SKU create-preview-commit orchestration
- downstream inventory readiness
- OpenAPI/docs/test truth for those surfaces

It should not overlap with reports, settlement/ledger, payroll/HR, period-close,
or broader pricing/valuation redesign.

## In Scope

- keep exactly one public catalog host: `/api/v1/catalog/**`
- keep exactly one public product-create surface: `POST /api/v1/catalog/products`
- keep explicit brand creation on `POST /api/v1/catalog/brands`
- require product create to consume a resolved active `brandId`
- support canonical preview on `POST /api/v1/catalog/products?preview=true`
- remove retired write aliases `/api/v1/catalog/products/single` and
  `/api/v1/catalog/products/bulk-variants`
- persist explicit `variantGroupId` linkage
- guarantee finished-good/raw-material readiness in the same write path
- keep canonical browse/search on `GET /api/v1/catalog/products`
- rewrite stale OpenAPI/docs/tests/helpers in the same packet

## Out Of Scope

- reports
- journal / settlement cleanup
- dealer credit redesign
- payroll / HR redesign
- inventory valuation redesign
- factory costing redesign
- unrelated product-pricing policy changes

## Definition Of Done

### Public surface

- `/api/v1/catalog/**` is the only supported public catalog host
- canonical public catalog operations are `GET/POST /api/v1/catalog/brands`,
  `GET /api/v1/catalog/products`, and `POST /api/v1/catalog/products`
- no alternate public catalog browse or create surface survives alongside the
  canonical host

### Write path

- single-SKU and matrix creation both use `POST /api/v1/catalog/products`
- preview and commit use the same request shape
- product create requires an active `brandId`
- brand creation remains a separate `POST /api/v1/catalog/brands` step before
  product preview/commit

### Data model

- variant-group linkage is explicit and persisted
- grouped membership does not rely only on naming convention

### Downstream readiness

- finished-good create also creates or updates finished-good inventory truth
- raw-material create also creates or updates raw-material inventory truth
- production/factory can select the product without manual repair
- sales can use the SKU without catalog-readiness failures
- every returned member exposes `catalog`, `inventory`, `production`, and
  `sales` readiness with explicit blockers

### UX

- one screen can create one SKU or many variants
- preview exists before commit
- duplicate/conflict failures are explicit
- delimiter-based quick input stays UI-only sugar
- new-brand flow is a separate `POST /api/v1/catalog/brands` step, not an
  inline product payload fallback

### Cleanup

- stale tests for retired hosts are deleted or rewritten
- stale OpenAPI surfaces are removed
- developer docs, handoff docs, and route inventories match runtime truth
- frontend docs stop describing `/single` or `/bulk-variants` as current-state
  create paths

## Required Proof

- existing-brand flow: `GET /api/v1/catalog/brands?active=true` then
  `POST /api/v1/catalog/products`
- new-brand flow: `POST /api/v1/catalog/brands` then
  `POST /api/v1/catalog/products`
- `1 x 1 -> 1 SKU`
- `4 x 4 -> 16 SKUs`
- variant grouping persists
- finished-good mirror creation proves out
- raw-material mirror creation proves out
- readiness payload is returned for each created SKU
- sales order SKU resolution works immediately after create
- production/factory selection works immediately after create
- OpenAPI snapshot matches repo-root `openapi.json`
- `git diff --check` is clean

## Suggested Issue Text

Title:

`Catalog Surface Consolidation: one canonical product-entry flow with guaranteed downstream readiness`

Summary:

Replace the split accounting/catalog/production product flow with one canonical
`/api/v1/catalog/**` surface and one canonical write engine. The surviving flow
must support existing-brand selection, explicit new-brand creation on
`POST /api/v1/catalog/brands`, and single-SKU or matrix variant creation on
`POST /api/v1/catalog/products`. Every created SKU must be immediately ready for
downstream production, inventory, and sales use without manual repair.
- sales and production can consume the new SKU without manual repair
