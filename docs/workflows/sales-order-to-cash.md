# Sales Order to Cash (O2C) Workflow

**Audience:** Sales team, dispatch team, finance/accounting

This guide explains how to complete one full customer order cycle: from dealer creation to period close.

## End-to-End Steps

| Step | What to do | Screen + API mapping | What to expect | What can go wrong (and quick fix) |
|---|---|---|---|---|
| 1 | Create customer/dealer master | **Dealer Master** → `POST /api/v1/dealers` (or alias list via `GET /api/v1/sales/dealers`) | Dealer is available for order booking with credit limit and GST details | Duplicate code/email, invalid GST/state code, or missing credit terms. Fix master data and retry. |
| 2 | Place sales order | **Sales Order Entry** → `POST /api/v1/sales/orders` | Order is created in draft/open state with items and value | Missing products, invalid quantity, missing idempotency header/body key for repeat-safe submission. Validate line data and resubmit. |
| 3 | Reserve stock by confirming order | **Order Actions** → `POST /api/v1/sales/orders/{id}/confirm` | Order moves to confirmed state and inventory reservation logic runs | Not enough stock or order already closed/cancelled. Recheck stock (`/api/v1/finished-goods/stock-summary`) or update order. |
| 4 | Generate packaging slip | **Dispatch Preparation** → `GET /api/v1/dispatch/order/{orderId}` or queue view `GET /api/v1/dispatch/pending` | Packaging slip is visible with lines to pick/pack | Slip not found if order not yet confirmed, or already fully dispatched. Reconfirm order status/timeline (`GET /api/v1/sales/orders/{id}/timeline`). |
| 5 | Dispatch goods | **Factory Dispatch Confirmation** → `POST /api/v1/dispatch/confirm` | Dispatch status updates, stock is reduced, accounting posting is triggered | Dispatch permission, quantity mismatch, or slip status conflict. Resolve blockers and retry the canonical factory dispatch confirmation. |
| 6 | Auto-invoice generation | **Invoice Register** → `GET /api/v1/invoices`, dealer drill-in `GET /api/v1/portal/finance/invoices?dealerId=` | Invoice appears automatically after successful dispatch | Invoice missing when dispatch is partial/failed. Check dispatch result and retry confirmation only once (idempotent flow). |
| 7 | Receive customer payment | **Receipt Entry** → `POST /api/v1/accounting/receipts/dealer` | Cash/bank receipt is posted and linked to open invoices | Allocation list missing/invalid, wrong dealer account, or amount mismatch. Re-enter receipt with proper invoice allocation lines. |
| 8 | Settle open invoices | **Settlement** → `POST /api/v1/accounting/settlements/dealers` or quick auto-settle `POST /api/v1/accounting/dealers/{dealerId}/auto-settle` | Outstanding AR reduces and settlement journal is posted | Settlement can fail on over-allocation or invalid references. Use canonical dealer finance reads `GET /api/v1/portal/finance/ledger?dealerId=` or `GET /api/v1/portal/finance/aging?dealerId=` first. |
| 9 | Close accounting period | **Period Close** → request `POST /api/v1/accounting/periods/{periodId}/request-close`, approve `POST /api/v1/accounting/periods/{periodId}/approve-close`, finalize `POST /api/v1/accounting/periods/{periodId}/close` | Period is formally closed after checklist and approvals | Open reconciliation discrepancies, incomplete checklist, or maker-checker conflicts. Resolve checklist/recon and use separate approver. |

## Operational Checks Before Marking O2C Complete

- Order lifecycle is visible from `GET /api/v1/sales/orders/{id}/timeline`
- Packaging slip shows dispatched status in dispatch views
- Invoice exists for every dispatched order
- Dealer outstanding reduces after settlement
- Period close request appears in approvals (`GET /api/v1/admin/approvals`)

## Troubleshooting Quick Notes

1. **Order created but cannot dispatch:** confirm order first (`/sales/orders/{id}/confirm`) and verify packaging slip exists.
2. **Dispatch blocked by credit:** use the right lane. Permanent dealer headroom goes through credit-limit requests (`/api/v1/credit/limit-requests/{id}/approve`); one-off dispatch exceptions go through the override workflow.
3. **Payment posted but invoice still open:** run explicit dealer settlement step (auto-settle or manual settle API).
4. **Period close rejected:** check bank/subledger/GST reconciliation and discrepancy list before re-requesting close.
