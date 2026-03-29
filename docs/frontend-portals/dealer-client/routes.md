# Dealer Client Routes

Every route below belongs to the external dealer shell only.

| UI route | Purpose | Backend contract family |
| --- | --- | --- |
| `/dealer/dashboard` | Dealer summary, open orders, open invoices, overdue aging, support shortcuts. | dealer portal dashboard reads |
| `/dealer/orders` | Dealer order list with status filters. | dealer portal order reads |
| `/dealer/orders/:orderId` | Dealer order detail, shipment status, and invoice readiness. | dealer portal order reads, dispatch read summary |
| `/dealer/invoices` | Invoice list and payment-status tracking. | dealer portal invoice reads |
| `/dealer/invoices/:invoiceId` | Invoice detail and download or print actions where allowed. | dealer portal invoice reads |
| `/dealer/ledger` | Dealer ledger read with running balance. | dealer portal finance reads |
| `/dealer/aging` | Aging buckets and overdue insight. | dealer portal finance reads |
| `/dealer/support` | Support ticket list and new support request flow. | dealer portal support endpoints |
| `/dealer/support/:ticketId` | Support detail and threaded updates where allowed. | dealer portal support endpoints |
| `/dealer/credit-requests` | Credit request list and current exposure view. | dealer portal credit endpoints |
| `/dealer/credit-requests/new` | Self-service credit request submission. | dealer portal credit endpoints |

Route rules:

- No internal dealer-master edit screens in this folder.
- No accounting settlement or approval screens in this folder.
- If a route primarily serves internal staff, it belongs in sales or accounting,
  not here.
- Dealer invoice list, detail, and PDF/download actions stay here even though
  internal sales can observe invoice readiness from order detail.
