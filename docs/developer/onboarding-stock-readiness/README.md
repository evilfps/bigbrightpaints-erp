# Hard-Cut Onboarding, Stock-Bearing Product Setup, and Opening Stock

This folder is the developer-facing packet for the current setup journey from
tenant bootstrap to usable stock.

## Purpose

Use this doc set to understand the surviving runtime truth:

- tenant bootstrap is canonical on `POST /api/v1/superadmin/tenants/onboard`
- company defaults required for stock-bearing work are explicit follow-up setup,
  not hidden repair
- stock-bearing product entry is canonical on `POST /api/v1/catalog/products`
- opening stock is canonical on `POST /api/v1/inventory/opening-stock`
- readiness is explicit per SKU as `catalog`, `inventory`, `production`, and
  `sales`

## Product Stance

This packet is hard-cut.

- no bootstrap aliases
- no weak product-create aliases
- no duplicate browse/search hosts for the same setup task
- no opening-stock fallback idempotency
- no opening-stock auto-create repair path

## Doc Set

- [01-current-state-flow.md](./01-current-state-flow.md)
  Current runtime journey from bootstrap to readiness visibility.
- [02-route-service-map.md](./02-route-service-map.md)
  Canonical controller/service ownership for the setup flow.
- [03-target-simplified-user-flow.md](./03-target-simplified-user-flow.md)
  Explicit operator journey and frontend/API ownership.
- [04-delete-first-duplicates.md](./04-delete-first-duplicates.md)
  Retired seams and delete-first rules for future cleanup in this area.
- [05-definition-of-done-and-update-hygiene.md](./05-definition-of-done-and-update-hygiene.md)
  Scope, proof, stop rules, and doc hygiene.

## Current-State Contract

- `POST /api/v1/superadmin/tenants/onboard` seeds the tenant, chart of
  accounts, open period, first admin, and `OPEN-BAL`
- `GET/PUT /api/v1/accounting/default-accounts` owns tenant stock-bearing
  default-account completion
- `PUT /api/v1/companies/{id}` is the super-admin correction path for company
  metadata such as timezone, state code, and default GST rate
- `GET/POST /api/v1/catalog/brands`, `GET /api/v1/catalog/products`, and
  `POST /api/v1/catalog/products` are the only surviving operator-facing setup
  routes for brand and SKU creation
- `POST /api/v1/catalog/products?preview=true` and
  `POST /api/v1/catalog/products` share the same request shape
- `POST /api/v1/inventory/opening-stock` requires explicit `Idempotency-Key`
  and only accepts prepared SKUs

## Related Docs

- [../catalog-consolidation/README.md](../catalog-consolidation/README.md)
  Catalog surface cleanup packet for the surviving product-entry contract.
- [../../accounting-portal-frontend-engineer-handoff.md](../../accounting-portal-frontend-engineer-handoff.md)
  Frontend/API handoff that must stay aligned with this flow.
- [../../endpoint-inventory.md](../../endpoint-inventory.md)
  OpenAPI-derived route inventory that must match runtime truth.
