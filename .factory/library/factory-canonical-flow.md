# ERP-38 Canonical Factory Flow

Mission-specific route ownership, cleanup targets, and worker guardrails for the factory hard-cut packet.

**What belongs here:** surviving public routes, delete-first targets, terminology rules, module boundaries, and validation hotspots for ERP-38.
**What does NOT belong here:** generic environment setup or service ports (use `.factory/services.yaml` and `environment.md`).

---

## Surviving Public Contract

- Setup truth: `GET/POST/PUT/... /api/v1/catalog/items`
- Packaging setup/read contract: `/api/v1/factory/packaging-mappings`
- Batch creation: `POST /api/v1/factory/production/logs`
- Pack mutation: `POST /api/v1/factory/packing-records`
- Dispatch confirm: `POST /api/v1/sales/dispatch/confirm`
- Factory dispatch reads only: `/api/v1/dispatch/{pending,preview/{slipId},slip/{slipId},order/{orderId}}`

## Delete-First Targets

- internal callers that still route through the legacy batch seam
- pack legacy idempotency compatibility (`X-Idempotency-Key`, `X-Request-Id`, payload-derived fallback, auto-generated fallback keys)
- `POST /api/v1/dispatch/confirm`
- stale orchestrator dispatch aliases or canonicalPath pointers to `/api/v1/sales/dispatch/confirm`
- stale tests/docs/OpenAPI/frontend-handoff entries for retired surfaces, including any leftover references to retired pack routes or pack-completion semantics

## Terminology Rules

- Use `Product Family`, `Production Batch`, `Pack`, `Dispatch`, and `Packaging Setup` / `Packaging Rules`.
- Do not present `bulk`, `production log`, and `production batch` as parallel operator concepts for the same step.
- Do not leave unexplained `BOM` jargon in the operator-facing contract.

## Cross-Module Boundaries

- Catalog/accounting own setup truth.
- Factory owns execution truth only.
- Production logging must keep raw-material consumption + WIP/semi-finished truth aligned.
- Packing must keep packaging-material consumption + finished-goods truth aligned.
- Dispatch posting owner remains the sales path through `SalesCoreEngine.confirmDispatch(...)`.
- Do not redesign unrelated tenant/company/superadmin/control-plane flows in this packet.

## Validation Hotspots

- `ProductionLogWipPostingRegressionIT`
- `ProductionLaborOverheadWipIT`
- `ProductionLogListDetailLazyLoadRegressionIT`
- `ProductionLogPackingStatusRegressionIT`
- `PackingControllerTest`
- `PackingServiceTest`
- `FactoryPackagingCostingIT`
- `DispatchControllerTest`
- `DispatchOperationalBoundaryIT`
- `DispatchConfirmationIT`
- `TS_O2CDispatchCanonicalPostingTest`
- `TS_O2COrchestratorDispatchRemovalRegressionTest`
- `ErpInvariantsSuiteIT`

## Published Contract Surfaces That Must Stay In Sync

- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- `docs/workflows/manufacturing-and-packaging.md`
- `docs/workflows/inventory-management.md`
- `docs/workflows/sales-order-to-cash.md`
- `docs/code-review/flows/manufacturing-inventory.md`
- `docs/code-review/flows/order-to-cash.md`
- `.factory/library/frontend-handoff.md`
- `.factory/library/frontend-v2.md`
- `README.md` once the final public flow is settled
