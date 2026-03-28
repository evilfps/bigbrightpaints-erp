# Manufacturing and Packaging Workflow

**Audience:** Production planner, plant supervisor, packing team, costing/accounting

This guide explains the flow from planning to dispatch-ready finished stock.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Create production plan | **Production Planning** → `POST /api/v1/factory/production-plans` | Plan is created with quantity and planned date | Wrong SKU/product, unrealistic quantity, or missing plan metadata. Correct and update (`PUT /factory/production-plans/{id}`). |
| 2 | Log production with raw material consumption | **Production Log** → `POST /api/v1/factory/production/logs` | Raw materials are consumed, production lot is recorded, base cost starts accumulating | Missing material lines, insufficient RM stock, invalid batch/quantity. Validate RM availability before posting. |
| 3 | Pack into size/SKU variants and consume packaging materials | **Packing** → `POST /api/v1/factory/packing-records` with required `Idempotency-Key`. Packaging Setup / Rules live on `POST /api/v1/factory/packaging-mappings` | Production output becomes sellable child SKUs (1L/4L/20L etc.), packaging materials deducted, and the batch can reach `FULLY_PACKED` with repeated packing-record submissions only | Missing, inactive, or unusable Packaging Setup / Rules, wrong child SKU mapping, missing `Idempotency-Key`, or legacy replay headers. Update Packaging Setup and retry with the canonical header only. |
| 4 | Validate cost traceability | **Cost Analysis** → `GET /api/v1/reports/production-logs/{id}/cost-breakdown` and monthly view `GET /api/v1/reports/monthly-production-costs` | You can see raw material + packaging + overhead contribution per unit | Cost looks incorrect when material usage was incomplete or pack quantities mismatch production totals. Reconcile logs before month end. |
| 5 | Release stock for dispatch | **Finished Goods Inventory / Dispatch Queue** → `GET /api/v1/finished-goods/stock-summary`, `GET /api/v1/dispatch/pending` | Packed SKUs are available to sales/dispatch flow | Stock visible but not dispatchable if order reservation/slip generation is pending. Confirm related sales order first. |

## Key Supporting APIs for Supervisors

- Production plan status update: `PATCH /api/v1/factory/production-plans/{id}/status`
- Review unpacked batches: `GET /api/v1/factory/unpacked-batches`
- Packing history per production log: `GET /api/v1/factory/production-logs/{productionLogId}/packing-history`
- Parent semi-finished bulk batches for a selected sellable FG: `GET /api/v1/factory/bulk-batches/{finishedGoodId}`
- Child batches from bulk: `GET /api/v1/factory/bulk-batches/{parentBatchId}/children`

Boundary note: the only packing mutation is `POST /api/v1/factory/packing-records`. The two `/bulk-batches/*` endpoints are read-only lookup/traceability helpers and do not create, update, or complete packing.

## Troubleshooting Quick Notes

1. **Production log saved but FG not available:** confirm the remaining pack quantity was recorded on `POST /api/v1/factory/packing-records`; production logging alone may not create dispatch-size stock.
2. **Packing failed for one size:** verify the child SKU exists and the Packaging Setup rule is active and usable.
3. **Unexpected packaging material shortage:** check planned carton/size mapping and run low-stock checks before shift start.
4. **Cost trace missing packaging portion:** packing now fails closed when Packaging Setup is missing, inactive, or unusable. Fix the Packaging Setup rule, then retry the pack.
