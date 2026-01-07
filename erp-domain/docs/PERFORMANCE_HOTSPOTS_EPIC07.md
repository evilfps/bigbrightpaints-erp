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

## Performance budgets (targets)
Assumptions:
- p95 server-side latency, warm DB, page size 100 unless noted.
- Targets are for JSON responses; PDFs may add up to +500 ms.

### List endpoints (p95)
- `GET /api/v1/sales/orders`: <= 500 ms
- `GET /api/v1/invoices`: <= 500 ms
- `GET /api/v1/invoices/dealers/{dealerId}`: <= 500 ms
- `GET /api/v1/accounting/journal-entries`: <= 700 ms
- Orchestrator outbox poll query: <= 200 ms (DB query only)

### Reports (bounded end-to-end)
- `GET /api/v1/accounting/statements/dealers/{dealerId}`: <= 3 s
- `GET /api/v1/accounting/statements/suppliers/{supplierId}`: <= 3 s
- `GET /api/v1/accounting/aging/dealers/{dealerId}`: <= 3 s
- `GET /api/v1/accounting/aging/suppliers/{supplierId}`: <= 3 s
- `GET /api/v1/accounting/reports/aging/receivables`: <= 4 s
- `GET /api/v1/accounting/reports/aging/dealer/{dealerId}/detailed`: <= 4 s
- `GET /api/v1/accounting/reports/balance-sheet/hierarchy`: <= 3 s
- `GET /api/v1/accounting/reports/income-statement/hierarchy`: <= 3 s
- `GET /api/v1/accounting/trial-balance/as-of`: <= 3 s
- `GET /api/v1/accounting/reports/dso/dealer/{dealerId}`: <= 2 s
