# Definition of Done and Update Hygiene

This file defines the acceptance boundary for setup-flow changes in this area.

## Packet Goal

Keep first-time setup and execution handoff explicit:

- onboard tenant
- complete company defaults
- create stock-bearing items
- review readiness per SKU
- import opening stock for prepared SKUs
- run one canonical batch -> pack -> dispatch story

## Scope In

- tenant bootstrap route ownership
- company-default setup required for stock-bearing work
- stock-bearing item route ownership
- opening-stock contract cleanup
- readiness exposure across catalog, inventory, production, and sales
- canonical execution handoff to production logs, packing records, and factory dispatch confirm
- stale tests, stale docs, and stale OpenAPI route inventories tied to this flow

## Scope Out

- reports cleanup
- settlement or ledger redesign beyond opening-stock journal truth
- payroll / HR cleanup
- period-close cleanup
- unrelated production refactors
- unrelated control-plane or UX redesign

## Definition of Done

### Canonical hosts

- one canonical tenant bootstrap path:
  - `POST /api/v1/superadmin/tenants/onboard`
- one canonical stock-bearing item setup path:
  - `POST /api/v1/catalog/items`
- one canonical opening-stock path:
  - `POST /api/v1/inventory/opening-stock`
- one canonical execution path:
  - `POST /api/v1/factory/production/logs`
  - `POST /api/v1/factory/packing-records`
  - `POST /api/v1/dispatch/confirm`

### Opening stock is strict

- explicit `Idempotency-Key` is required
- explicit `openingStockBatchKey` is required
- the same `openingStockBatchKey` cannot be applied twice under a fresh `Idempotency-Key`
- missing or orphan SKUs fail cleanly
- missing mirror truth fails cleanly
- missing readiness prerequisites fail cleanly
- no product or mirror auto-create survives

### Readiness is explicit

- item responses expose `catalog`, `inventory`, `production`, and `sales` readiness
- blockers are machine-readable enough for frontend to render without guesswork
- setup truth on `/api/v1/catalog/items` stays discoverable before factory execution

### Execution story is current-state only

- stale batch, pack, or dispatch alias-route docs are deleted or rewritten
- factory/operator docs point to production logs then packing records
- dispatch docs point to factory-owned `POST /api/v1/dispatch/confirm` plus operational lookup on the remaining `/api/v1/dispatch/**` routes
- stale docs for retired setup hosts are removed in the same packet

## Required Proof

- focused controller/service tests for onboarding route ownership
- focused controller/service tests for canonical item setup and readiness
- focused controller/service tests for strict opening-stock behavior
- focused proof that production, packing, and dispatch docs point to one operator story
- OpenAPI snapshot proof when public routes change
- changed-files coverage proof when covered Java files change
- `git diff --check`

## Frontend Handoff Hygiene

Frontend-facing docs must answer all of these:

- which screen owns tenant bootstrap
- which screen owns company-default completion
- which screen owns stock-bearing item setup
- which screen owns opening-stock loading
- which screen owns production batch logging
- which screen owns packing
- which screen owns factory dispatch confirmation
- which readiness states must be shown
- which exact setup, pack, or dispatch blockers must be surfaced

## Stop Rules

Stop and reassess if any proposal does any of the following:

- restores retired `legacy product routes` or `legacy accounting-prefixed product setup routes` setup hosts
- lets opening stock bootstrap missing truth
- restores a second pack mutation or a factory-owned dispatch-confirm write
- preserves stale tests only because they encode retired behavior
- updates docs without deleting stale route or fallback references
