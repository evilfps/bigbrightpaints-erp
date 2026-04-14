# Hard-Cut Onboarding, Stock-Bearing Item Setup, and Opening Stock

This folder is the developer-facing packet for the current setup journey from tenant bootstrap to usable stock and execution readiness.

## Purpose

Use this doc set to understand the surviving runtime truth:

- tenant bootstrap is canonical on `POST /api/v1/superadmin/tenants/onboard`
- company defaults required for stock-bearing work are explicit follow-up setup, not hidden repair
- stock-bearing item entry is canonical on `POST /api/v1/catalog/items`
- readiness is explicit per SKU as `catalog`, `inventory`, `production`, and `sales`
- opening stock is canonical on `POST /api/v1/inventory/opening-stock`
- ready stock flows into the canonical execution story: `POST /api/v1/factory/production/logs` -> `POST /api/v1/factory/packing-records` -> `POST /api/v1/dispatch/confirm`

## Product Stance

This packet is hard-cut.

- no bootstrap aliases
- no retired product-create setup hosts
- no duplicate browse/search hosts for the same setup task
- no opening-stock fallback idempotency
- no second pack or dispatch-confirm write owner

## Doc Set

- [01-current-state-flow.md](./01-current-state-flow.md)
  Current runtime journey from bootstrap to readiness visibility and execution handoff.
- [02-route-service-map.md](./02-route-service-map.md)
  Canonical controller/service ownership for setup plus the execution handoff.
- [03-target-simplified-user-flow.md](./03-target-simplified-user-flow.md)
  Explicit operator journey and frontend/API ownership.
- [04-delete-first-duplicates.md](./04-delete-first-duplicates.md)
  Retired seams and delete-first rules for future cleanup in this area.
- [05-definition-of-done-and-update-hygiene.md](./05-definition-of-done-and-update-hygiene.md)
  Scope, proof, stop rules, and doc hygiene.
- [06-stock-truth-decision.md](./06-stock-truth-decision.md)
  Wave 3 stock-truth decision: authoritative writers, derived fields, and Wave 4 constraints.

## Current-State Contract

- `POST /api/v1/superadmin/tenants/onboard` seeds the tenant, chart of accounts, open period, first admin, and `OPEN-BAL`
- `GET/PUT /api/v1/accounting/default-accounts` owns tenant stock-bearing default-account completion
- `GET/POST /api/v1/catalog/brands`, `GET /api/v1/catalog/items`, and `POST /api/v1/catalog/items` are the surviving operator-facing setup routes for brand selection and stock-bearing item creation
- `GET /api/v1/catalog/items` and `GET /api/v1/catalog/items/{itemId}` with `includeReadiness=true` keep readiness visible before factory execution
- `POST /api/v1/inventory/opening-stock` requires explicit `Idempotency-Key` plus explicit `openingStockBatchKey`, and only accepts prepared SKUs
- `POST /api/v1/factory/production/logs`, `POST /api/v1/factory/packing-records`, and `POST /api/v1/dispatch/confirm` are the only surviving operator write surfaces for batch -> pack -> dispatch

## Related Docs

- [../catalog-consolidation/README.md](../catalog-consolidation/README.md)
  Catalog surface cleanup packet for the surviving item-entry contract.
- [../../frontend-portals/accounting/README.md](../../frontend-portals/accounting/README.md)
  Canonical accounting portal handoff that must stay aligned with this flow.
- [../../frontend-api/README.md](../../frontend-api/README.md)
  Canonical frontend API contract index for current route and DTO truth.
- [../../endpoint-inventory.md](../../endpoint-inventory.md)
  OpenAPI-derived route inventory that must match runtime truth.
