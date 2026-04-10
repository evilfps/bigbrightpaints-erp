# Dealer Client Routes

Every route below belongs to the external dealer shell only.

| UI route | Purpose | Backend endpoint |
| --- | --- | --- |
| `/dealer/dashboard` | Dealer summary, order counts, credit exposure, payment status. | `GET /api/v1/dealer-portal/dashboard` |
| `/dealer/orders` | Dealer order list with status filters (read-only). | `GET /api/v1/dealer-portal/orders` |
| `/dealer/invoices` | Invoice list and payment-status tracking. | `GET /api/v1/dealer-portal/invoices` |
| `/dealer/invoices/:invoiceId` | Invoice PDF or download view for a selected invoice. | `GET /api/v1/dealer-portal/invoices/{invoiceId}/pdf` |
| `/dealer/ledger` | Dealer ledger read with running balance. | `GET /api/v1/dealer-portal/ledger` |
| `/dealer/aging` | Aging buckets and overdue insight. | `GET /api/v1/dealer-portal/aging` |
| `/dealer/support` | Support ticket list and new support request flow. | `GET, POST /api/v1/dealer-portal/support/tickets` |
| `/dealer/support/:ticketId` | Support ticket detail and thread history. | `GET /api/v1/dealer-portal/support/tickets/{ticketId}` |
| `/dealer/credit-requests/new` | Self-service credit-limit increase request. | `POST /api/v1/dealer-portal/credit-limit-requests` |

Route rules:

- No internal dealer-master edit screens in this folder.
- No accounting settlement or approval screens in this folder.
- Dealer orders are read-only; order creation and mutation happen internally via
  sales or admin portals.
- If a route primarily serves internal staff, it belongs in sales or accounting,
  not here.
- Dealer invoice list, detail, and PDF/download actions stay here even though
  internal sales can observe invoice readiness from order detail.
