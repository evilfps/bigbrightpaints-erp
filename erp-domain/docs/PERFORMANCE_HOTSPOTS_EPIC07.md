# Epic 07 Performance Hotspots (Baseline)

## Hot endpoints (initial focus)
- `GET /api/v1/accounting/journal-entries` (journal entries with lines)
- `GET /api/v1/invoices` and `GET /api/v1/invoices/dealers/{dealerId}` (invoice lists)
- `GET /api/v1/sales/orders` (sales orders list)
- Outbox publisher fetch (orchestrator outbox polling)
- Ledger aging/report paths (dealer/supplier ledger entry reads)

## Heavy tables (initial focus)
- `journal_entries`, `journal_lines`
- `dealer_ledger_entries`, `supplier_ledger_entries`
- `inventory_movements`
- `orchestrator_outbox`
- `invoices`, `invoice_lines`
- `sales_orders`, `sales_order_items`

## Primary query patterns
- Company-scoped list queries ordered by date (orders, invoices, journals)
- Ledger entry ranges by company + partner + entry date
- Outbox polling by status + next_attempt_at
- Inventory movement lookups by reference type + id

## Performance guardrails to add
- Pagination + stable ordering for list endpoints
- Composite indexes aligned to company filters + sort columns
- Query count checks to avoid N+1 regressions
