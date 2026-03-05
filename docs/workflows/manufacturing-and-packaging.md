# Manufacturing and Packaging Workflow

**Audience:** Production planner, plant supervisor, packing team, costing/accounting

This guide explains the flow from planning to dispatch-ready finished stock.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Create production plan | **Production Planning** → `POST /api/v1/factory/production-plans` | Plan is created with quantity and planned date | Wrong SKU/product, unrealistic quantity, or missing plan metadata. Correct and update (`PUT /factory/production-plans/{id}`). |
| 2 | Log production with raw material consumption | **Production Log** → `POST /api/v1/factory/production/logs` | Raw materials are consumed, production lot is recorded, base cost starts accumulating | Missing material lines, insufficient RM stock, invalid batch/quantity. Validate RM availability before posting. |
| 3 | Pack into size/SKU variants and consume packaging materials | **Packing** → `POST /api/v1/factory/packing-records` (line-level pack records) and/or `POST /api/v1/factory/pack` (bulk-to-size conversion). Packaging mapping setup via `POST /api/v1/factory/packaging-mappings` | Parent/bulk output becomes sellable child SKUs (1L/4L/20L etc.), packaging materials deducted | Missing packaging mapping, wrong child SKU mapping, or duplicate packing submission. Configure mappings and use idempotency keys. |
| 4 | Validate cost traceability | **Cost Analysis** → `GET /api/v1/reports/production-logs/{id}/cost-breakdown` and monthly view `GET /api/v1/reports/monthly-production-costs` | You can see raw material + packaging + overhead contribution per unit | Cost looks incorrect when material usage was incomplete or pack quantities mismatch production totals. Reconcile logs before month end. |
| 5 | Release stock for dispatch | **Finished Goods Inventory / Dispatch Queue** → `GET /api/v1/finished-goods/stock-summary`, `GET /api/v1/dispatch/pending` | Packed SKUs are available to sales/dispatch flow | Stock visible but not dispatchable if order reservation/slip generation is pending. Confirm related sales order first. |

## Key Supporting APIs for Supervisors

- Production plan status update: `PATCH /api/v1/factory/production-plans/{id}/status`
- Review unpacked batches: `GET /api/v1/factory/unpacked-batches`
- Packing history per production log: `GET /api/v1/factory/production-logs/{productionLogId}/packing-history`
- Child batches from bulk: `GET /api/v1/factory/bulk-batches/{parentBatchId}/children`

## Troubleshooting Quick Notes

1. **Production log saved but FG not available:** confirm packing was completed; production logging alone may not create dispatch-size stock.
2. **Packing failed for one size:** verify child SKU exists and packaging mapping is active.
3. **Unexpected packaging material shortage:** check planned carton/size mapping and run low-stock checks before shift start.
4. **Cost trace missing packaging portion:** ensure packing entry included packaging consumption (or disable skip only when intentionally pre-consumed).
