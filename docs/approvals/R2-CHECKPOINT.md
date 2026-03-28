# R2 Checkpoint

## Scope
- Feature: `ERP-23 finished-good stock truth hard cut + catalog item canonicalization`
- Branch: `mdanas7869292/erp-23-hard-cut-finished-good-stock-truth-onto-inventory-and`
- PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/161`
- Review candidate:
  - delete the retired manual FG batch write seam and non-prod gating path
  - keep one canonical FG stock-truth flow into inventory
  - remove product-era catalog duplicate DTO/service vocabulary in favor of `CatalogItem*`
  - apply `migration_v2/V171__drop_finished_good_batch_legacy_bulk_flag.sql`
- Why this is R2: this packet modifies stock-truth write surfaces and a live `migration_v2` table contract (`finished_good_batches`). A wrong cut can corrupt FG movement/accounting linkage or break runtime expectations during deployment.

## Risk Trigger
- Triggered by:
  - `erp-domain/src/main/resources/db/migration_v2/V171__drop_finished_good_batch_legacy_bulk_flag.sql`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/**`
- Contract surfaces affected:
  - `GET /api/v1/catalog/items`
  - `POST /api/v1/catalog/items`
  - `GET /api/v1/catalog/items/{itemId}`
  - `PUT /api/v1/catalog/items/{itemId}`
  - inventory/factory FG stock write/read internals behind dispatch, packing, opening stock, and production log flows
- Failure mode if wrong:
  - stale code reading removed `finished_good_batches.is_bulk` fails at runtime
  - inventory traceability leaks semi-finished bulk assumptions into sellable FG paths
  - catalog item contract drifts back into split product/item write semantics

## Approval Authority
- Mode: human
- Approver: `ERP packet owner`
- Canary owner: `ERP-23 packet owner`
- Approval status: `pending green CI + reviewer confirmation`
- Basis: migration + stock-truth hard cut requires explicit human signoff even after automated gates pass.

## Escalation Decision
- Human escalation required: yes
- Reason: the packet changes a migration-backed inventory table contract and production/inventory stock path semantics.

## Rollback Owner
- Owner: `ERP-23 packet owner`
- Rollback method:
  - preferred: restore tenant/database snapshot taken before `V171` and redeploy the pre-cut build as one coordinated rollback
  - emergency-only SQL fallback (if snapshot unavailable and pre-cut app must run): re-add nullable `finished_good_batches.is_bulk` and recreate `idx_fg_batch_bulk` in the same maintenance window before switching runtime
- Rollback trigger:
  - runtime query/ORM failures referencing `finished_good_batches.is_bulk`
  - FG movement/accounting regression on packing/dispatch/opening-stock paths
  - CI/runtime evidence proving canonical item contract mismatch after deploy candidate

## Expiry
- Valid until: `2026-04-03`
- Re-evaluate if: any new migration is added on top of `V171`, scope expands into accounting/auth/control-plane modules, or reviewer asks for additional stock-truth restructuring beyond ERP-23 boundaries.

## Verification Evidence
- Commands run:
  - `rg -n "CatalogProductRequest|CatalogProductDto|CatalogProductEntryRequest|ProductCreateRequest|ProductUpdateRequest" erp-domain/src/main erp-domain/src/test docs openapi.json`
  - `rg -n "CatalogService\\.createItem|CatalogService\\.getItem|CatalogService\\.updateItem|CatalogService\\.searchItems|createCatalogItem\\(|updateCatalogItem\\(" erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production docs`
  - `mvn -Dtest=GlobalExceptionHandlerTest,TS_RuntimeGlobalExceptionHandlerExecutableCoverageTest,OpeningStockPostingRegressionIT,ProductionCatalogFinishedGoodInvariantIT,ProductionCatalogRawMaterialInvariantIT,ProductionCatalogDiscountDefaultRegressionIT,CR_CatalogImportDeterminismIT test`
  - `bash scripts/guard_openapi_contract_drift.sh`
  - `bash scripts/guard_legacy_migration_freeze.sh`
  - `mvn -Pgate-fast -Djacoco.skip=true test`
  - `git diff --check`
  - `bash scripts/verify_local.sh`
- Result summary:
  - catalog duplicate product-era DTO/service symbols removed from runtime lane
  - canonical item vocabulary and route family retained (`CatalogItem*`, `/api/v1/catalog/items`)
  - targeted suites and `gate-fast` passed locally
  - `verify_local.sh` currently reports schema-drift findings on historical `V168/V169` auth migrations (pre-existing to this packet), not on `V171`
- Artifacts/links:
  - Worktree: `/Users/anas/Documents/Factory/bigbrightpaints-erp_worktrees/erp-23-fg-stock-truth`
  - PR: `https://github.com/anasibnanwar-XYE/bigbrightpaints-erp/pull/161`
  - Linear issue: `ERP-23`
