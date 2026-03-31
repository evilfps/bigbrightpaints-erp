# Dealer Client Routes

Every route below belongs to the dealer-client shell. Do not duplicate these
screens in sales, factory, accounting, or tenant-admin.

| UI route | Purpose | Backend contract family |
| --- | --- | --- |
| `/dealer/dashboard` | Dealer summary, order count, credit exposure, payment status, recent activity. | `/api/v1/dealer-portal/dashboard` |
| `/dealer/orders` | Dealer order list, status filters, and order history. | `/api/v1/dealer-portal/orders/**` |
| `/dealer/orders/:orderId` | Order detail, timeline, dispatch status, and invoice links. | `/api/v1/dealer-portal/orders/{id}` |
| `/dealer/invoices` | Invoice list for the dealer's company, with status filters. | `/api/v1/dealer-portal/invoices/**` |
| `/dealer/invoices/:invoiceId` | Invoice detail, line items, tax breakdown, and PDF download. | `/api/v1/dealer-portal/invoices/{id}` |
| `/dealer/ledger` | Account ledger for the dealer's company, with date range filters. | `/api/v1/dealer-portal/ledger/**` |
| `/dealer/aging` | Receivable aging summary by due date bucket. | `/api/v1/dealer-portal/aging` |
| `/dealer/credit` | Self-service credit request entry and existing request status. | `/api/v1/dealer-portal/credit/**` |
| `/dealer/credit/:requestId` | Credit request detail and decision history. | `/api/v1/dealer-portal/credit/{id}` |
| `/dealer/support` | Support ticket list and new ticket creation. | `/api/v1/portal/support/tickets` |
| `/dealer/support/:ticketId` | Ticket detail and conversation thread. | `/api/v1/portal/support/tickets/{id}` |

Route rules:

- Dealer routes must always use the dealer's own `companyCode` from the session.
- Do not expose internal sales routes like `/sales/orders/new` or
  `/sales/dealers/new` to dealer users.
- Do not expose factory dispatch-confirm or production log routes to dealer
  users.
- Do not expose accounting correction, journal reversal, or admin approval
  routes to dealer users.
