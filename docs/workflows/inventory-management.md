# Inventory Management Workflow

**Audience:** Warehouse/store team, inventory controller, finance controller

This guide covers daily stock visibility, adjustments, opening loads, and traceability.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Monitor stock health (low stock + expiry) | **Inventory Dashboard** → FG summary `GET /api/v1/finished-goods/stock-summary`, FG low stock `GET /api/v1/finished-goods/low-stock`, RM low stock `GET /api/v1/raw-materials/stock/low-stock`, expiring batches `GET /api/v1/inventory/batches/expiring-soon?days=30` | Early warning for replenishment and expiry risk | Alerts ignored or thresholds not configured. Set FG threshold (`PUT /api/v1/finished-goods/{id}/low-stock-threshold`) and review daily. |
| 2 | Post stock adjustments (write-downs/recount) | **Inventory Adjustments** → FG `POST /api/v1/inventory/adjustments`, RM `POST /api/v1/inventory/raw-materials/adjustments` | Stock and accounting are corrected with audit trail | Missing idempotency key, wrong adjustment account, or invalid direction/type. Validate reason and account before posting. |
| 3 | Import opening stock at onboarding/restart for prepared SKUs only | **Opening Stock Import** → `POST /api/v1/inventory/opening-stock` (CSV file, with `Idempotency-Key` header and query param `openingStockBatchKey`), history via `GET /api/v1/inventory/opening-stock` | Initial stock loads with row-level `results[]` and `errors[]`, plus per-SKU readiness details when a row is blocked | Missing key, missing `openingStockBatchKey`, orphan SKU, missing mirror/account readiness, CSV header mismatch, duplicate SKU/batch, or invalid date format. Fix readiness first; retry the same batch with the original `Idempotency-Key`, or use a new `Idempotency-Key` plus a new `openingStockBatchKey` only for a materially distinct follow-up import. |
| 4 | Trace any batch end-to-end | **Batch Traceability** → `GET /api/v1/inventory/batches/{id}/movements`; supporting views `GET /api/v1/catalog/items/{itemId}?includeStock=true&includeReadiness=true`, `GET /api/v1/raw-materials/stock/inventory`, and `GET /api/v1/finished-goods/{id}/batches` | Full movement history for audit/recall/investigation | Wrong batch type filter or stale batch ID. Use the canonical item/material views first to confirm the correct batch ID. |

## Adjustment Types to Use Correctly

### Finished Goods (`POST /api/v1/inventory/adjustments`)
- `DAMAGED` → physical damage / leakage
- `SHRINKAGE` → shortage/loss
- `OBSOLETE` → expired/non-sellable
- `RECOUNT_UP` → positive correction when physical count is higher

### Raw Materials (`POST /api/v1/inventory/raw-materials/adjustments`)
- `INCREASE` → gain/recount up
- `DECREASE` → loss/write-down

## Troubleshooting Quick Notes

1. **Stock turns negative in process checks:** check whether prior dispatch/consumption was posted twice; verify idempotency keys.
2. **Opening stock import partially failed:** use the returned readiness stage and blockers from `errors[]`, fix only failed rows, and do not blindly re-upload successful rows.
3. **Expiry list seems empty:** confirm batches have expiry dates populated during GRN/import.
4. **Audit asks for movement proof:** use batch movement endpoint and include source references in report export.
