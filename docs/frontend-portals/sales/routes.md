# Sales Routes

Last reviewed: 2026-04-02

Every route below belongs to the sales shell. Do not duplicate these screens in
factory, accounting, tenant-admin, or dealer-client.

| UI route | Purpose | Backend contract family |
| --- | --- | --- |
| `/sales/dashboard` | Sales summary, dealer health, order funnel, credit alerts. | sales dashboard reads, dealer summary reads |
| `/sales/dealers` | Dealer list, filter, segmentation, and status overview. | `/api/v1/dealers/**` |
| `/sales/dealers/new` | Dealer creation and commercial setup. | `/api/v1/dealers/**` |
| `/sales/dealers/:dealerId` | Dealer detail, addresses, GST, commercial limits, and order history. | `/api/v1/dealers/**` |
| `/sales/orders` | Sales-order list, filters, and queue management. | `/api/v1/sales/orders/**` |
| `/sales/orders/new` | Sales-order creation from ready SKUs and dealer context. | `POST /api/v1/sales/orders` |
| `/sales/orders/:orderId` | Order detail, pricing, reservation, dispatch-read status, and order-linked invoice summary state. | `/api/v1/sales/orders/**`, `GET /api/v1/dispatch/order/{orderId}`, `GET /api/v1/invoices/{id}` when launched from the current order only |
| `/sales/orders/:orderId/timeline` | Full order timeline from draft through dispatch and invoice. | `/api/v1/sales/orders/{id}/timeline` |
| `/sales/credit` | Credit usage, block reasons, escalation entry, and override request visibility. | `/api/v1/credit/**` |
| `/sales/credit/:requestId` | Credit-request detail and decision history. | `/api/v1/credit/**` |

Route rules:

- `/sales/dealers` defaults to the full active dealer directory. Use
  `status=ALL` only when the UI intentionally needs non-active dealers.
- Dealer-directory calls may pass `page` and `size` for windowing, but the
  backend still returns a plain list with no total-count metadata.
- Sales can render dispatch status reads, but dispatch confirmation stays in the
  factory portal.
- Sales can render invoice status reads and an order-linked invoice summary, but
  it does not own a standalone invoice list, invoice download center, invoice
  correction, or settlement screen.
- Dealer self-service order and invoice screens belong in
  `docs/frontend-portals/dealer-client/`, not here.
- Do not create `/sales/invoices` as a top-level route in this portal.
