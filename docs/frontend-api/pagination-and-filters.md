# Pagination And Filters

## List behavior

- Use explicit `page` and `size` query params for list-heavy accounting and
  admin screens.
- Persist filter state in the URL for operator-driven grids such as journals,
  approvals, settlements, stock, and report drill-downs.
- Avoid hidden client-only filtering that can drift from server totals or audit
  exports.

## Canonical filter examples

- Journal entries list `GET /api/v1/accounting/journal-entries`: `dealerId`,
  `supplierId`, `page`, `size`
- Journal filter list `GET /api/v1/accounting/journals`: `fromDate`, `toDate`,
  `type`, `sourceModule`
- Approvals: status, request type, created-by, requested date
- Catalog and stock: `itemClass`, readiness, stock visibility, search text
- Bank reconciliation sessions:
  `GET /api/v1/accounting/reconciliation/bank/sessions?page={page}&size={size}`
- Reconciliation discrepancies:
  `GET /api/v1/accounting/reconciliation/discrepancies?status={status}&type={type}`
- Accounting audit transactions:
  `GET /api/v1/accounting/audit/transactions?from={from}&to={to}&module={module}&status={status}&reference={reference}&page={page}&size={size}`
- Reports:
  - balance sheet, profit-loss, trial balance:
    `periodId`, `startDate`, `endDate`, `comparativeStartDate`,
    `comparativeEndDate`, `comparativePeriodId`, `exportFormat`
  - GST return and GST reconciliation: `period`
  - aged receivables: `asOfDate`
  - dealer ledger, dealer invoices, dealer aging:
    `dealerId`
  - supplier statement: `from`, `to`
  - supplier aging: `asOf`, `buckets`
- Export requests:
  - no standalone list endpoint exists today
  - keep request state from the create response, approval inbox, and download
    contract instead of inventing client-side history filters

Example paginated response:

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 42,
        "referenceNumber": "JRNL-2026-00042",
        "entryDate": "2026-03-28",
        "status": "POSTED"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

Practical rules:

- Reset `page` back to `0` when filters change materially.
- Preserve filters in the URL so support and audit teams can deep-link exactly
  what the operator saw.
- Distinguish server-backed filtering from local UI-only search chips. The
  canonical totals and export scope come from the backend query only.

## Empty-state rules

- Empty due to no data should render a clean empty state with the active filter
  summary.
- Empty due to permissions, lifecycle lock, or closed period should render a
  blocked state, not a normal empty list.
