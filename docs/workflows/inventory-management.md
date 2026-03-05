# Inventory Management Workflow

**Audience:** Warehouse/store team, inventory controller, finance controller

This guide covers daily stock visibility, adjustments, opening loads, and traceability.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Monitor stock health (low stock + expiry) | **Inventory Dashboard** → FG summary `GET /api/v1/finished-goods/stock-summary`, FG low stock `GET /api/v1/finished-goods/low-stock`, RM low stock `GET /api/v1/raw-materials/stock/low-stock`, expiring batches `GET /api/v1/inventory/batches/expiring-soon?days=30` | Early warning for replenishment and expiry risk | Alerts ignored or thresholds not configured. Set FG threshold (`PUT /api/v1/finished-goods/{id}/low-stock-threshold`) and review daily. |
| 2 | Post stock adjustments (write-downs/recount) | **Inventory Adjustments** → FG `POST /api/v1/inventory/adjustments`, RM `POST /api/v1/inventory/raw-materials/adjustments` | Stock and accounting are corrected with audit trail | Missing idempotency key, wrong adjustment account, or invalid direction/type. Validate reason and account before posting. |
| 3 | Import opening stock at onboarding/restart | **Opening Stock Import** → `POST /api/v1/inventory/opening-stock` (CSV file), history via `GET /api/v1/inventory/opening-stock` | Initial stock loads with row-level success/error summary | CSV header mismatch, duplicate SKU/batch, invalid date format. Correct failed rows and re-upload. |
| 4 | Trace any batch end-to-end | **Batch Traceability** → `GET /api/v1/inventory/batches/{id}/movements`; supporting views `GET /api/v1/raw-material-batches/{rawMaterialId}` and `GET /api/v1/finished-goods/{id}/batches` | Full movement history for audit/recall/investigation | Wrong batch type filter or stale batch ID. Use product/material screen to confirm the correct batch ID first. |

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
2. **Opening stock import partially failed:** use import history response and fix only failed rows; do not blindly re-upload changed successful rows.
3. **Expiry list seems empty:** confirm batches have expiry dates populated during GRN/import.
4. **Audit asks for movement proof:** use batch movement endpoint and include source references in report export.
