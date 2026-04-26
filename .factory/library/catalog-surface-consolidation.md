# Catalog Surface Consolidation

Mission-specific notes for the narrow catalog/product/SKU consolidation packet.

**What belongs here:** canonical host decisions, public-contract rules, hotspots, cleanup targets, and packet-specific guardrails.
**What does NOT belong here:** generic environment setup or service ports (use `.factory/library/environment.md` and `.factory/services.yaml`).

---

## Canonical Decisions

- Public catalog host: `/api/v1/catalog/**` only.
- Canonical brand endpoints:
  - `GET /api/v1/catalog/brands?active=true` for existing-brand selection
  - `POST /api/v1/catalog/brands` for explicit new-brand creation
- Canonical stock-bearing item endpoint: `POST /api/v1/catalog/items`
- Canonical readiness reads:
  - `GET /api/v1/catalog/items`
  - `GET /api/v1/catalog/items/{itemId}` with `includeReadiness=true`
- Item create must consume a pre-resolved active `brandId`.
- Do not preserve inline brand-create fallback fields in the stock-bearing item contract.
- Do not preserve competing retired product-create or bulk-create setup hosts.

## Packet Guardrails

- No compatibility bridges.
- No wrapper delegation between old and new public hosts.
- No dual-route behavior.
- No speculative expansion into reports, settlement/ledger, payroll/HR, period-close, runtime-control-plane, or broad pricing/inventory valuation redesign.
- Preserve reserved SKU semantics such as `-BULK`.
- Delimiter parsing is UI-only convenience; backend must accept arrays and reject packed multi-value tokens.

## Historical Split Truth (Pre-Consolidation)

- Historically, `AccountingCatalogController` -> `ProductionCatalogService` owned the stronger downstream-ready write path under a retired accounting catalog host.
- Historically, `CatalogController` -> `CatalogService` owned the public catalog host, but its product write path was weaker and exposed a retired bulk-create surface.
- Historically, `ProductionCatalogController` exposed competing browse under `/api/v1/production/**`.
- Historically, `CatalogService` and `ProductionCatalogService` implemented competing SKU/product creation rules before the canonical public host was collapsed to `/api/v1/catalog/**`.

## Downstream Readiness Requirements

- Finished-good create must seed finished-good truth with valuation/COGS/revenue/discount/tax readiness in the same write path.
- Raw-material create must seed raw-material truth with the inventory/account linkage needed for downstream material usage.
- Newly created items must be discoverable via the canonical `/api/v1/catalog/items` read surface, with readiness visible when setup or factory-adjacent users need it.
- Sales order resolution must succeed without `Unknown SKU` / missing finished-good style failures.
- Factory selection must succeed with the canonical brand/product identifiers without manual repair.

## Hotspot Files

- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/controller/AccountingCatalogController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/controller/CatalogController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/controller/ProductionCatalogController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/CatalogService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/domain/ProductionProduct.java`
- `erp-domain/src/main/resources/db/migration_v2/*`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/production/**`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionCatalogFinishedGoodInvariantIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionCatalogRawMaterialInvariantIT.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesCoreEngine.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java`

## Cleanup Targets

Update or remove stale catalog-route truth in the same packet across:

- repo-root `openapi.json`
- `docs/developer/catalog-consolidation/*`
- files named by `docs/developer/catalog-consolidation/04-update-hygiene.md`
- `.factory/library/frontend-handoff.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- route-anchored tests/helpers that still assert retired public hosts

## Known Current Drift To Remove

- Catalog-consolidation docs still contain pre-clarification text that implies inline brand creation inside the stock-bearing item contract.
- `erp-domain/docs/endpoint_inventory.tsv` still inventories retired catalog hosts.
- Existing tests/helpers still assert legacy accounting and production catalog paths in places.
