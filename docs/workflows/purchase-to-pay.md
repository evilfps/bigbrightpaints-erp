# Purchase to Pay (P2P) Workflow

**Audience:** Procurement, stores/warehouse, accounts payable team

This guide covers supplier onboarding through final supplier settlement.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Create supplier | **Supplier Master** → `POST /api/v1/suppliers` | Supplier is created with payment/GST/bank details | Invalid GST/state code, duplicate supplier code, or incomplete bank data. Correct and retry. |
| 2 | Create purchase order (PO) | **Purchase Order** → `POST /api/v1/purchasing/purchase-orders` | PO saved with line items and planned quantities | Wrong raw material, invalid unit cost/qty, or missing supplier approval. Validate supplier and line details. |
| 3 | Approve PO and receive goods (GRN) | Approve: `POST /api/v1/purchasing/purchase-orders/{id}/approve` → GRN: `POST /api/v1/purchasing/goods-receipts` | Inventory increases, PO status updates to partial/full received, accounting entry posts | GRN fails for excess quantity, missing idempotency key, or invalid batch dates. Match GRN quantities to PO and retry once. |
| 4 | Capture supplier invoice | **Supplier Invoice Capture** → `POST /api/v1/purchasing/raw-material-purchases` | Supplier payable is recorded against received material | Duplicate reference, wrong material/purchase link, or amount mismatch. Recheck invoice number and base document references. |
| 5 | Settle supplier open items | Manual: `POST /api/v1/accounting/settlements/suppliers` | Settlement journal is posted and open AP entries are cleared | Wrong cash account, invalid allocation, or settlement exceeds outstanding. Review supplier statement before posting. |
| 6 | Auto-settle supplier open items | `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle` | Open AP entries are cleared using canonical FIFO-style allocation | Amount not fully allocatable, stale references, or currency/rate mismatch. Use supplier statement and aging report before settling. |

## Supporting Views

- PO list/timeline: `GET /api/v1/purchasing/purchase-orders`, `GET /api/v1/purchasing/purchase-orders/{id}/timeline`
- GRN register: `GET /api/v1/purchasing/goods-receipts`
- Supplier statement: `GET /api/v1/accounting/statements/suppliers/{supplierId}`
- Supplier aging: `GET /api/v1/accounting/aging/suppliers/{supplierId}`

## Exceptions You Should Handle

- **Return to supplier:** `POST /api/v1/purchasing/raw-material-purchases/returns` (cannot exceed outstanding)
- **Void/close PO:** `POST /api/v1/purchasing/purchase-orders/{id}/void`, `POST /api/v1/purchasing/purchase-orders/{id}/close`

## Troubleshooting Quick Notes

1. **GRN not posting to stock:** check PO approval and line-level quantity limits.
2. **Supplier settlement not reducing payable:** verify settlement allocations and re-run after refreshing supplier statement.
3. **Return rejected:** return quantity/value exceeds outstanding payable; reconcile supplier ledger first.
4. **Duplicate GRN risk:** always pass `Idempotency-Key` in goods receipt requests.
