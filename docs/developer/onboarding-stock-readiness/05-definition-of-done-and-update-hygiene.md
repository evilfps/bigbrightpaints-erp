# Definition of Done and Update Hygiene

This file defines the acceptance boundary for setup-flow changes in this area.

## Packet Goal

Keep first-time setup explicit:

- onboard tenant
- complete company defaults
- create stock-bearing products
- import opening stock for prepared SKUs
- expose readiness per SKU immediately

## Scope In

- tenant bootstrap route ownership
- company-default setup required for stock-bearing work
- stock-bearing product-create route ownership
- opening-stock contract cleanup
- readiness exposure across catalog, inventory, production, and sales
- stale tests, stale docs, and stale OpenAPI route inventories tied to this
  flow

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
- one canonical stock-bearing product-create path:
  - `POST /api/v1/catalog/products`
- one canonical opening-stock path:
  - `POST /api/v1/inventory/opening-stock`

### Opening stock is strict

- explicit `Idempotency-Key` is required
- missing or orphan SKUs fail cleanly
- missing mirror truth fails cleanly
- missing readiness prerequisites fail cleanly
- no product or mirror auto-create survives

### Readiness is explicit

- SKU responses expose `catalog`, `inventory`, `production`, and `sales`
  readiness
- blockers are machine-readable enough for frontend to render without guesswork

### Docs and tests are current-state only

- stale alias-route tests are deleted or rewritten
- stale fallback tests are deleted or rewritten
- stale docs for retired routes or fallback behavior are removed in the same
  packet
- OpenAPI inventory docs match runtime truth

## Required Proof

- focused controller/service tests for onboarding route ownership
- focused controller/service tests for canonical product-create readiness
- focused controller/service tests for strict opening-stock behavior
- focused proof that sales still depends on canonical product + inventory truth
- OpenAPI snapshot proof when public routes change
- changed-files coverage proof when covered Java files change
- `git diff --check`

## Frontend Handoff Hygiene

Frontend-facing docs must answer all of these:

- which screen owns tenant bootstrap
- which screen owns company-default completion
- which screen owns stock-bearing product setup
- which screen owns opening-stock loading
- which readiness states must be shown
- which exact orphan or not-ready SKU blockers must be surfaced

## Stop Rules

Stop and reassess if any proposal does any of the following:

- restores a weak product-write path for convenience
- lets opening stock bootstrap missing truth
- keeps compatibility fallback without explicit justification and deletion
  tracking
- preserves stale tests only because they encode retired behavior
- updates docs without deleting stale route or fallback references
