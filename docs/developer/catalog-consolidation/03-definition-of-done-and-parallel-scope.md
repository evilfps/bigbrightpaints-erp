# Catalog Consolidation Scope And Definition Of Done

This file captures the accepted packet scope for the surviving stock-bearing setup contract.

## Why This Is Parallel-Safe

This packet is limited to:

- catalog route ownership for brands and items
- readiness-aware item setup and maintenance
- downstream execution handoff clarity
- OpenAPI/docs/test truth for those surfaces

It should not overlap with reports, settlement/ledger, payroll/HR, period-close, or broader pricing/valuation redesign.

## In Scope

- keep exactly one public catalog setup host: `/api/v1/catalog/**`
- keep exactly one stock-bearing item-create surface: `POST /api/v1/catalog/items`
- keep explicit brand creation on `POST /api/v1/catalog/brands`
- keep readiness-aware item reads on `GET /api/v1/catalog/items` and `GET /api/v1/catalog/items/{itemId}`
- remove retired setup hosts `legacy product routes` and `legacy accounting-prefixed product setup routes`
- keep the downstream operator story explicit: `POST /api/v1/factory/production/logs` -> `POST /api/v1/factory/packing-records` -> `POST /api/v1/sales/dispatch/confirm`
- rewrite stale docs/tests/helpers in the same packet

## Out Of Scope

- reports
- journal / settlement cleanup
- dealer credit redesign
- payroll / HR redesign
- inventory valuation redesign
- unrelated factory costing redesign
- unrelated product-pricing policy changes

## Definition Of Done

### Public surface

- `/api/v1/catalog/**` is the only supported public setup host for stock-bearing item work
- canonical public setup operations are `GET/POST /api/v1/catalog/brands`, `GET/POST /api/v1/catalog/items`, and `GET/PUT/DELETE /api/v1/catalog/items/{itemId}`
- no alternate public setup host survives alongside the canonical item surface

### Write path

- stock-bearing setup uses `POST /api/v1/catalog/items`
- item maintenance uses `PUT /api/v1/catalog/items/{itemId}`
- item create/update requires an active `brandId`
- readiness review stays on the same public host as setup

### Downstream operator handoff

- ready items can move into `POST /api/v1/factory/production/logs`
- packing uses `POST /api/v1/factory/packing-records` only
- final dispatch posting uses `POST /api/v1/sales/dispatch/confirm` only
- `/api/v1/dispatch/**` remains read-only operational lookup

### Cleanup

- stale tests for retired setup hosts are deleted or rewritten
- stale OpenAPI surfaces are removed
- developer docs, handoff docs, and route inventories match runtime truth
- frontend docs stop describing `legacy product routes` or `legacy accounting-prefixed product setup routes` as current setup paths

## Required Proof

- existing-brand flow: `GET /api/v1/catalog/brands?active=true` then `POST /api/v1/catalog/items`
- new-brand flow: `POST /api/v1/catalog/brands` then `POST /api/v1/catalog/items`
- readiness is visible on item list/detail reads
- production, packing, and sales dispatch docs point to one batch -> pack -> dispatch story
- OpenAPI snapshot matches repo-root `openapi.json`
- `git diff --check` is clean

## Suggested Issue Text

Title:

`Catalog Surface Consolidation: one canonical item setup flow with readiness and operator handoff clarity`

Summary:

Keep `/api/v1/catalog/items` as the only stock-bearing setup host, remove stale `legacy product routes` and `legacy accounting-prefixed product setup routes` guidance, keep readiness visible on canonical item reads, and ensure the developer story flows cleanly from item setup into `POST /api/v1/factory/production/logs`, `POST /api/v1/factory/packing-records`, and sales-owned `POST /api/v1/sales/dispatch/confirm`.
