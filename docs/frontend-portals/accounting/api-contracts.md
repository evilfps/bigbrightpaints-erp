# Accounting API Contracts

This file is the accounting portal API truth. Frontend should not infer
alternate routes from retired docs, test fixtures, or legacy controllers.

All tenant-scoped calls in this portal still use `companyCode` and
`X-Company-Code` from `docs/frontend-api/auth-and-company-scope.md`. Do not
reintroduce `companyId` or `X-Company-Id` into accounting requests.

## Onboarding And COA Preconditions

- COA bootstrap starts in superadmin onboarding, not in this portal.
- On tenant creation, frontend must assume the tenant does not have a usable
  local COA until onboarding succeeds with a `coaTemplateCode`.
- Accounting screens are considered ready only after onboarding returns:
  - `seededChartOfAccounts=true`
  - `defaultAccountingPeriodCreated=true`
  - `tenantAdminProvisioned=true`
- If those signals are absent, the UI must block accounting setup and route the
  user to superadmin remediation.

Blocking frontend state:

- Missing `seededChartOfAccounts`
- Missing `defaultAccountingPeriodCreated`
- Missing `tenantAdminProvisioned`

## Chart Of Accounts And Default Accounts

- `GET /api/v1/accounting/accounts/tree`
- `GET /api/v1/accounting/accounts`
- `POST /api/v1/accounting/accounts`
- `GET /api/v1/accounting/default-accounts`
- `PUT /api/v1/accounting/default-accounts`

Rules:

- `default-accounts` is the only editable frontend source for company-wide
  automatic posting defaults.
- Frontend must not create a second default-account editor inside catalog or
  product-specific screens.
- In GST mode, updating `taxAccountId` through `PUT /api/v1/accounting/default-accounts`
  is also the canonical way to align the company's GST output posting account.
- If a company is switched to non-GST mode, retained GST input/output/payable
  bindings are cleared at the company level; frontend must not assume older GST
  account links survive a `defaultGstRate=0` configuration change.
- Missing required defaults are blocking setup failures, not warnings.
- Treat default accounts as the fallback mapping layer below item metadata and
  above any downstream stock, sales, or accounting workflow.

Blocking frontend state:

- Missing default inventory account
- Missing finished-good valuation, COGS, revenue, discount, or tax default when
  the item does not provide its own mapping

## Product To Account Mapping

- `GET /api/v1/catalog/items`
- `POST /api/v1/catalog/items`

Rules:

- Product creation remains `POST /api/v1/catalog/items`.
- Finished goods may inherit:
  - `fgValuationAccountId`
  - `fgCogsAccountId`
  - `fgRevenueAccountId`
  - `fgDiscountAccountId`
  - `fgTaxAccountId`
  from explicit item metadata or company defaults.
- Raw materials must resolve `inventoryAccountId` from explicit metadata or the
  default inventory account.
- Frontend should show whether each resolved account came from explicit item
  metadata or inherited company defaults.
- If those mappings are incomplete, SKU readiness remains fail-closed and
  opening stock, production, packing, sales dispatch, and downstream posting
  must stay blocked.

Blocking frontend state:

- Missing `inventoryAccountId` for raw materials
- Missing `fgValuationAccountId`
- Missing `fgCogsAccountId`
- Missing `fgRevenueAccountId`
- Missing `fgDiscountAccountId`
- Missing `fgTaxAccountId` for taxable finished goods

## Tax Configuration

- GST mode requires valid input, output, and payable tax accounts.
- Non-GST mode must not persist GST-only assumptions in the UI.
- Tax configuration belongs in accounting and should not be split across
  multiple portal folders.
- Taxable finished goods must resolve a valid revenue-side tax account before
  accounting readiness can pass.

Blocking frontend state:

- GST mode with missing input account
- GST mode with missing output account
- GST mode with missing payable account
- Non-GST mode with stale retained GST account assumptions in the form draft

## Journals And Reversal

- `GET /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journal-entries`
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`

Rules:

- Manual journals use only `POST /api/v1/accounting/journal-entries`.
- Reversal uses only
  `POST /api/v1/accounting/journal-entries/{entryId}/reverse`.
- Reverse requests may carry `cascadeRelatedEntries` and `relatedEntryIds`, but
  the public path stays `/journal-entries/{entryId}/reverse`.
- Frontend should treat closed-period failures, invalid account references, and
  unbalanced line totals as blocking validation outcomes on this screen.

## Reconciliation

- `POST /api/v1/accounting/reconciliation/bank/sessions`
- `GET /api/v1/accounting/reconciliation/bank/sessions`
- `GET /api/v1/accounting/reconciliation/bank/sessions/{sessionId}`
- `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`
- `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete`
- `GET /api/v1/accounting/reconciliation/subledger`
- `GET /api/v1/accounting/reconciliation/discrepancies`
- `POST /api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve`
- `GET /api/v1/accounting/gst/reconciliation`

Request and response rules:

- Bank-session create requires `bankAccountId`, `statementDate`,
  `statementEndingBalance`, and may also send `startDate`, `endDate`,
  `accountingPeriodId`, and `note`.
- Session item updates use `addJournalLineIds`, `removeJournalLineIds`,
  optional `note`, and optional `matches[]`. Each `matches[]` item supports
  `bankItemId` plus either `journalLineId` or `journalEntryId`.
- Session completion uses optional `note` and `accountingPeriodId`.
- Session detail (`GET /reconciliation/bank/sessions/{sessionId}`) returns
  `matchedItems[]` and `unmatchedItems[]`. `matchedItems[]` carries persisted
  `bankItemId` linkage for reconciled statement lines.
- Discrepancy resolution uses `resolution`, optional `note`, and optional
  `adjustmentAccountId`.
- Discrepancy list filters are `status` and `type`.
- Use reconciliation as the close-readiness truth for bank, subledger,
  inventory, and GST discrepancy handling.

## Period Close Maker-Checker

- `GET /api/v1/accounting/periods`
- `GET /api/v1/accounting/month-end/checklist`
- `POST /api/v1/accounting/month-end/checklist/{periodId}`
- `POST /api/v1/accounting/periods/{periodId}/request-close`
- `POST /api/v1/accounting/periods/{periodId}/approve-close`
- `POST /api/v1/accounting/periods/{periodId}/reject-close`
- `POST /api/v1/accounting/periods/{periodId}/reopen`

Rules:

- Direct `POST /api/v1/accounting/periods/{periodId}/close` is not a supported
  frontend action.
- Reopen is not part of normal accounting navigation and should appear only in
  the superadmin experience.
- Frontend should treat closed-period write rejection as a blocked state, not as
  a retryable warning.

## Opening Stock

- `GET /api/v1/inventory/opening-stock`
- `POST /api/v1/inventory/opening-stock`

Rules:

- Opening stock is available only after product/account readiness is clear.
- Use the canonical idempotency headers and import identifiers required by the
  backend.
- Frontend must surface row-level validation and duplicate-batch outcomes.

## AR, AP, And Reports

- `POST /api/v1/accounting/receipts/dealer`
- `POST /api/v1/accounting/receipts/dealer/hybrid`
- `POST /api/v1/accounting/settlements/dealers`
- `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`
- `GET /api/v1/portal/finance/ledger?dealerId={dealerId}`
- `GET /api/v1/portal/finance/invoices?dealerId={dealerId}`
- `GET /api/v1/portal/finance/aging?dealerId={dealerId}`
- `GET /api/v1/reports/aging/receivables`
- `POST /api/v1/accounting/settlements/suppliers`
- `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`
- `GET /api/v1/accounting/statements/suppliers/{supplierId}`
- `GET /api/v1/accounting/aging/suppliers/{supplierId}`
- `GET /api/v1/reports/**`
- `GET /api/v1/reports/workflow-shortcuts`
- `POST /api/v1/exports/request`
- `GET /api/v1/exports/{requestId}/download`

Rules:

- AR settlement and AP settlement belong to accounting even when sales or
  supplier flows create the underlying business documents.
- Dealer receipt requires `dealerId`, `cashAccountId`, `amount`, and
  `allocations`. Hybrid receipt uses `dealerId`, `incomingLines`,
  `referenceNumber`, and `memo`.
- Dealer and supplier settlements both use `PartnerSettlementRequest` with
  `partnerType` (`DEALER` or `SUPPLIER`), `partnerId`, `settlementDate`,
  `allocations`, and optional `cashAccountId`, `discountAccountId`,
  `writeOffAccountId`, `fxGainAccountId`, `fxLossAccountId`,
  `unappliedAmountApplication`, `referenceNumber`, `memo`,
  `idempotencyKey`, and `adminOverride`.
- Auto-settle requests use `cashAccountId`, `amount`,
  `referenceNumber`, `memo`, and `idempotencyKey`.
- Reports and CSV exports stay inside accountant-facing flows.
- If export approval is required, frontend must surface approval state instead
  of bypassing governance with a direct file link.
- Settlement screens must preserve tenant, period, and ledger filters so report
  totals reconcile with the same accounting context.
- Frontend must explicitly render these blocking states:
  - pending export approval
  - rejected export request
  - expired export request
  - closed-period settlement attempt
  - missing dealer or supplier allocations

### Report reads and workflow shortcuts

`ReportController` and `WorkflowShortcutController` are guarded for
`ROLE_ADMIN` or `ROLE_ACCOUNTING`. All report responses use the shared
`ApiResponse<T>` envelope except export download, which returns file bytes.

| Route | Method | Main filters | Response semantics |
| --- | --- | --- | --- |
| `/api/v1/reports/trial-balance` | GET | `date`, `periodId`, `startDate`, `endDate`, comparative filters, `exportFormat` | `TrialBalanceDto`; closed periods use snapshot source where a snapshot exists, open/as-of windows use live/as-of journal summaries. |
| `/api/v1/reports/profit-loss` | GET | Same financial report filters as trial balance | `ProfitLossDto`; reads live journal summaries for period/range requests and reports `metadata.source=LIVE` unless the request is an explicit as-of window. |
| `/api/v1/reports/balance-sheet` | GET | Same financial report filters as trial balance | `BalanceSheetDto`; supports snapshot/live/as-of source metadata. |
| `/api/v1/reports/balance-sheet/hierarchy` | GET | none | Hierarchical balance sheet grouped by account tree. |
| `/api/v1/reports/income-statement/hierarchy` | GET | none | Hierarchical income statement grouped by account tree. |
| `/api/v1/reports/cash-flow` | GET | none | `CashFlowDto`; sensitive live finance disclosure. |
| `/api/v1/reports/gst-return` | GET | `periodId` | `GstReturnReportDto` with output, input-credit, and net-liability components. |
| `/api/v1/reports/account-statement` | GET | required `accountId`, optional `from`, `to` | `AccountStatementReportDto` with account code/name, opening balance, chronological entries, running balances, and closing balance. |
| `/api/v1/reports/aged-debtors` | GET | `periodId`, `startDate`, `endDate`, `exportFormat` | Dealer aging rollup list. |
| `/api/v1/reports/aging/receivables` | GET | `asOfDate` | AR aging read model. |
| `/api/v1/reports/inventory-valuation` | GET | optional `date` | Current or as-of inventory valuation. |
| `/api/v1/reports/inventory-reconciliation` | GET | none | Inventory valuation compared to GL. |
| `/api/v1/reports/product-costing` | GET | required `itemId` | Per-unit cost breakdown. |
| `/api/v1/reports/cost-allocation` | GET | none | Factory cost allocation history. |
| `/api/v1/reports/wastage` | GET | none | Production wastage rows. |
| `/api/v1/reports/production-logs/{id}/cost-breakdown` | GET | path `id` | Production-log cost breakdown. |
| `/api/v1/reports/monthly-production-costs` | GET | optional `year` and `month` together | With `year` + `month`, returns a period aggregate; without them, returns monthly cost rows. |
| `/api/v1/reports/reconciliation-dashboard` | GET | optional `bankAccountId`, `statementBalance` | Bank reconciliation dashboard. |
| `/api/v1/reports/balance-warnings` | GET | none | Balance anomaly warnings. |
| `/api/v1/reports/workflow-shortcuts` | GET | none | `WorkflowShortcutCatalogDto` with connected workflow step lists for order-to-invoice, procure-to-pay, and period-close/reconciliation. |

### Export request, approval, and download

Export request creation uses `POST /api/v1/exports/request` from the accounting
report screen. The request body is `ExportRequestCreateRequest`:
`reportType` is required, while `format` and serialized `parameters` are
optional. The create response is `ApiResponse<ExportRequestDto>` with `id`,
`reportType`, `parameters`, `status`, requester fields, and approval/rejection
metadata.

Tenant-admin review uses only:

- `GET /api/v1/admin/approvals`
- `POST /api/v1/admin/approvals/{originType}/{id}/decisions`

For export rows, `originType=EXPORT_REQUEST`, `ownerType=REPORTS`, and the
decision payload is `AdminApprovalDecisionRequest` with `decision=APPROVE` or
`REJECT`; `reason` is optional for exports. There is no export-specific pending
list, direct export approve/reject route, or standalone export status route.

`GET /api/v1/exports/{requestId}/download` returns the file body with
`Content-Disposition` when allowed. If the requester does not own the export,
the approval gate blocks it, or the request is otherwise invalid, the backend
returns the normal error envelope/status instead of a `downloadUrl`.

### Dealer finance and supplier PDF boundaries

Dealer finance reads are route-limited:

- Internal admin/accounting reads use `/api/v1/portal/finance/ledger`,
  `/api/v1/portal/finance/invoices`, and `/api/v1/portal/finance/aging` with a
  required `dealerId` query parameter.
- Dealer self-service reads use `/api/v1/dealer-portal/dashboard`,
  `/api/v1/dealer-portal/ledger`, `/api/v1/dealer-portal/invoices`,
  `/api/v1/dealer-portal/aging`, `/api/v1/dealer-portal/orders`, and
  `/api/v1/dealer-portal/invoices/{invoiceId}/pdf`.
- Dealer statement/aging PDF aliases under
  `/api/v1/accounting/statements/dealers/**` or
  `/api/v1/accounting/aging/dealers/**` are not shipped backend routes.

Supplier statement and supplier aging PDFs do exist, and both require
`ROLE_ADMIN`:

- `GET /api/v1/accounting/statements/suppliers/{supplierId}/pdf`
- `GET /api/v1/accounting/aging/suppliers/{supplierId}/pdf`

## Forbidden Public Routes

- Do not use `/api/v1/accounting/journals/manual`.
- Do not use `/api/v1/accounting/journals/{entryId}/reverse`.
- Do not use `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse`.
- Do not use `/api/v1/accounting/periods/{periodId}/close`.
- Do not use export status routes such as `/api/v1/exports/{requestId}/status`.
- Do not use export-specific approve/reject routes; use
  `/api/v1/admin/approvals/{originType}/{id}/decisions`.
