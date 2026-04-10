# Accounting Reference Chains

Last reviewed: 2026-04-06

This file captures the canonical document and reference chains frontend uses across onboarding, stock bootstrap, accounting workflows, and audit/provenance reads.

Reference chains define which upstream business event the frontend should use
when linking screens, toasts, audits, and support traces.

## Tenant onboarding

- `GET /api/v1/superadmin/tenants/coa-templates`
- operator selects `coaTemplateCode`
- `POST /api/v1/superadmin/tenants/onboard`
- `seededChartOfAccounts=true`
- `defaultAccountingPeriodCreated=true`
- `tenantAdminProvisioned=true`

UI implication:

- Accounting screens stay blocked until onboarding confirms all bootstrap truth
  flags.
- Do not build a local fallback that assumes a tenant can enter accounting first
  and seed COA later.
- Portal owner: `superadmin` for onboarding, then `accounting` for post-bootstrap
  setup.

## Inventory bootstrap

- catalog item create
- readiness resolution
- opening stock import
- stock movement
- journal entry

UI implication:

- Product setup and opening stock are two separate steps. Do not collapse them
  into one wizard.
- Portal owner:
  - `accounting` for readiness and opening-stock import
  - `factory` and `sales` only consume the ready result

## Product readiness and default-account mapping

- `GET /api/v1/accounting/accounts/tree`
- `GET /api/v1/accounting/accounts`
- `GET /api/v1/accounting/default-accounts`
- `PUT /api/v1/accounting/default-accounts`
- company default accounts
- item create on `POST /api/v1/catalog/items`
- readiness evaluation
- opening stock or downstream operational use

UI implication:

- Treat readiness blockers such as missing `inventoryAccountId`,
  `fgValuationAccountId`, `fgCogsAccountId`, `fgRevenueAccountId`,
  `fgDiscountAccountId`, or `fgTaxAccountId` as exact blockers. Do not let
  operators push a half-configured SKU into stock, production, dispatch, or
  manual accounting flows.
- Make the source of each resolved account explicit in UI:
  - item-level metadata
  - inherited company default
  - missing blocking dependency
- `PUT /api/v1/accounting/default-accounts` is the only company-default edit
  path; in GST mode it keeps output-tax posting aligned, and non-GST tenants
  must not retain stale GST account bindings after a GST-off change.
- Portal owner: `accounting`

## Tax and GST readiness

- GST mode selection
- GST input account
- GST output account
- GST payable account
- `defaultGstRate`
- taxable finished-good tax mapping
- readiness evaluation

UI implication:

- Frontend must keep GST or tax setup in accounting, not split it across
  superadmin, catalog, or sales screens.
- If GST mode is on, input, output, and payable accounts are all blocking
  prerequisites.
- If GST mode is off, the UI must clear GST-only assumptions instead of
  preserving stale tax-account state in drafts or cached forms.
- Portal owner: `accounting`

## O2C

- dealer
- sales order
- dispatch preparation
- dispatch confirm
- invoice
- journal
- receipt or settlement

UI implication:

- Sales can create orders and monitor readiness, but factory owns the dispatch
  confirm action.
- Factory reads dispatch queue and slip detail from:
  - `GET /api/v1/dispatch/pending`
  - `GET /api/v1/dispatch/preview/{slipId}`
  - `GET /api/v1/dispatch/slip/{slipId}`
  - `GET /api/v1/dispatch/order/{orderId}`
- Sales owns order-linked invoice readiness and optional read-only invoice
  summary from the current order only.
- Dealer-client owns the external invoice list, detail, and PDF/download flow.

## P2P

- supplier
- purchase order
- goods receipt
- purchase invoice
- supplier settlement

UI implication:

- Stock becomes true at GRN; AP becomes true at purchase invoice. Frontend must
  not present them as the same posting step.
- Portal owner:
  - purchasing flow owns PO and GRN
  - accounting owns supplier settlement, supplier statement, supplier aging, and
    reconciliation after purchase invoice exists

## Manual journals and reversals

- manual journal draft
- `POST /api/v1/accounting/journal-entries`
- posted journal
- `POST /api/v1/accounting/journal-entries/{entryId}/reverse`
- reversal journal

UI implication:

- Link manual journals and reversals through one canonical create path and one
  canonical reverse path only. Do not expose a second manual-journal path or a
  separate public cascade-reverse action.

## Journal correction

- source journal
- reverse request
- reversal journal
- optional related reversals expressed inside the same request payload

UI implication:

- Show reversal lineage as one action tree. Do not invent a second public
  "cascade reverse" route in the client.

## Period close maker-checker

- reconciliation and checklist completion
- close request
- approval inbox item in `GET /api/v1/admin/approvals`
- `POST /api/v1/accounting/periods/{periodId}/approve-close` or
  `POST /api/v1/accounting/periods/{periodId}/reject-close`
- closed period

UI implication:

- Direct close is not a supported frontend action. Render period close as a
  maker-checker flow with approval state, approver identity, and rejection
  reason tracking.
- Use `PeriodCloseRequestActionRequest` for request-close, approve-close, and
  reject-close. Use `AccountingPeriodReopenRequest` only on the reopen route.

## AR settlement and dealer finance reads

- dealer receipt or hybrid receipt
- dealer settlement or auto-settle
- dealer ledger read
- dealer invoice read
- dealer aging read
- aged receivables report
- bank or subledger reconciliation refresh

UI implication:

- Portal owner: `accounting`
- Use `POST /api/v1/accounting/receipts/dealer`,
  `POST /api/v1/accounting/receipts/dealer/hybrid`,
  `POST /api/v1/accounting/settlements/dealers`,
  `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`,
  `GET /api/v1/portal/finance/ledger?dealerId={dealerId}`,
  `GET /api/v1/portal/finance/invoices?dealerId={dealerId}`,
  and `GET /api/v1/portal/finance/aging?dealerId={dealerId}` as one connected
  clearing chain.
- Dealer settlements now use `PartnerSettlementRequest` with
  `partnerType=DEALER` and `partnerId=<dealerId>`.

## AP settlement and supplier finance reads

- supplier settlement or auto-settle
- supplier statement
- supplier aging
- reconciliation refresh

UI implication:

- Portal owner: `accounting`
- Use `POST /api/v1/accounting/settlements/suppliers`,
  `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`,
  `GET /api/v1/accounting/statements/suppliers/{supplierId}`,
  and `GET /api/v1/accounting/aging/suppliers/{supplierId}` as one connected
  AP-clearing chain.
- Supplier settlements use the same `PartnerSettlementRequest` DTO with
  `partnerType=SUPPLIER` and `partnerId=<supplierId>`.

## Purchase debit-note replay lineage

- purchase invoice
- purchase journal
- debit note request with stable `referenceNumber`
- optional `idempotencyKey`
- debit note journal with `sourceModule=DEBIT_NOTE`
- `sourceReference=<purchaseInvoiceNumber>`
- `reversalOf=<purchase journal>`
- supplier statement and audit detail refresh

UI implication:

- Treat `referenceNumber` + `idempotencyKey` as replay identifiers only for
  the same purchase document.
- If replay returns a conflict whose provenance points to another purchase
  reference chain, keep the operator on the draft and require a new correction
  reference instead of presenting the result as an already-applied success.
- When rendering the posted debit note, link back to the originating purchase
  invoice and source journal from the provenance fields.

## Export request and approval

- accounting report filters
- `POST /api/v1/exports/request`
- approval inbox item in `GET /api/v1/admin/approvals`
- approve or reject in tenant-admin
- `GET /api/v1/exports/{requestId}/download`

UI implication:

- Portal owner:
  - `accounting` for request creation and download recheck
  - `tenant-admin` for approve or reject
- Do not invent a separate export-history backend list. Keep request status on
  the originating accounting report screen unless the backend grows a dedicated
  request history surface.

## Audit Trail

All accounting transactions are recorded in the audit trail with provenance fields.

### Query Audit Trail

```
GET /api/v1/accounting/audit/transactions?filter.journalEntryId=1001
```

**Response:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "journalEntryId": 1001,
        "eventType": "POST",
        "actorEmail": "user@example.com",
        "timestamp": "2026-03-31T10:00:00Z",
        "drivingDocument": {
          "type": "INVOICE",
          "id": 42,
          "reference": "INV-2026-0042"
        },
        "linkedDocuments": [
          {
            "type": "SALES_ORDER",
            "id": 2001,
            "reference": "SO-2026-0156"
          }
        ]
      }
    ]
  }
}
```

## Provenance Fields

### drivingDocument

The primary document that triggered the transaction.

```json
{
  "drivingDocument": {
    "type": "INVOICE",
    "id": 42,
    "reference": "INV-2026-0042"
  }
}
```

### linkedDocuments

All related documents in the transaction chain.

```json
{
  "linkedDocuments": [
    { "type": "SALES_ORDER", "id": 2001, "reference": "SO-2026-0156" },
    { "type": "DEALER", "id": 101, "reference": "ACME01" }
  ]
}
```

### eventTrail

Complete history of the transaction lifecycle.

```json
{
  "eventTrail": [
    { "event": "CREATED", "timestamp": "2026-03-30T14:00:00Z", "actor": "user@example.com" },
    { "event": "POSTED", "timestamp": "2026-03-31T10:00:00Z", "actor": "user@example.com" }
  ]
}
```

## Canonical Audit Surfaces

The current canonical audit read surfaces are:

| Surface | Endpoint | Description |
|---|---|---|
| Accounting audit feed | `/api/v1/accounting/audit/events` | Paginated business-event feed restricted to the accounting module; accepts `module` and `category` query params but enforces accounting-visibility policy so non-accounting values return no rows (requires ROLE_ADMIN or ROLE_ACCOUNTING) |
| Accounting audit transactions | `/api/v1/accounting/audit/transactions` | Paginated list of accounting transactions with filters (requires ROLE_ADMIN or ROLE_ACCOUNTING) |
| Accounting audit transactions (detail) | `/api/v1/accounting/audit/transactions/{journalEntryId}` | Single transaction audit detail by journal entry ID (requires ROLE_ADMIN or ROLE_ACCOUNTING) |
| Admin audit feed | `/api/v1/admin/audit/events` | Tenant-admin audit-event feed for approval/admin surfaces |
| Platform audit feed | `/api/v1/superadmin/audit/platform-events` | Super-admin control-plane audit feed |
| ML events audit | `/api/v1/audit/ml-events` | Machine learning/analytics interaction telemetry — not an accounting surface (requires ROLE_ADMIN) |

> **Removed**: `/api/v1/accounting/audit/digest` and `/api/v1/accounting/audit-trail` were hard-removed in the audit unification hard-cut. Use `/api/v1/accounting/audit/events` or `/api/v1/accounting/audit/transactions` instead.

For more details on audit-surface ownership, see [core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) and [AUDIT_TRAIL_OWNERSHIP.md](../AUDIT_TRAIL_OWNERSHIP.md).

## Cross-Module Reference Types

| Type | Module | Description |
|---|---|---|
| `SALES_ORDER` | sales | Sales order documents |
| `INVOICE` | invoice | Invoice documents |
| `PURCHASE_ORDER` | purchasing | Purchase order documents |
| `GOODS_RECEIPT` | purchasing | Goods receipt notes |
| `PAYMENT` | accounting | Payment transactions |
| `JOURNAL_ENTRY` | accounting | Journal entries |
| `DEALER` | sales | Dealer/customer records |
| `SUPPLIER` | purchasing | Supplier records |
| `EMPLOYEE` | hr | Employee records |

## Frontend Display Guidance

1. **Navigation** — Allow users to click references to navigate to the related document.
2. **Breadcrumbs** — Show the full reference chain (e.g., "SO-2026-0156 → INV-2026-0042 → JE-1001").
3. **Audit trail** — Display the event trail to show who did what and when.
4. **Driven by** — Always show the `drivingDocument` as the primary source.

## Links

- See [`docs/frontend-portals/accounting/api-contracts.md`](../frontend-portals/accounting/api-contracts.md) for accounting-specific contracts.
- See [exports-and-approvals.md](./exports-and-approvals.md) for export and approval workflows.
