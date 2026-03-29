# Accounting Workflows

## 1. Tenant Bootstrap To Accounting Readiness

1. Superadmin loads `GET /api/v1/superadmin/tenants/coa-templates`, selects
   `coaTemplateCode`, and completes `POST /api/v1/superadmin/tenants/onboard`.
2. Accounting opens the portal only after onboarding returns:
   `seededChartOfAccounts=true`,
   `defaultAccountingPeriodCreated=true`, and
   `tenantAdminProvisioned=true`.
3. Accounting verifies `GET /api/v1/accounting/default-accounts` and GST or tax
   readiness before any inventory or journal workflow is enabled.
4. If seeded COA, the default period, or tenant-admin bootstrap is missing,
   block the portal and route the user to superadmin remediation.

## 2. COA Review And Missing-Account Create

1. Open `/accounting/gl/chart-of-accounts`.
2. Load `GET /api/v1/accounting/accounts/tree` first, then
   `GET /api/v1/accounting/accounts` for list/search support.
3. If a needed posting account is missing, create it only with
   `POST /api/v1/accounting/accounts`.
4. Return to the COA tree and verify the newly created account appears in the
   correct hierarchy before allowing default-account or item-mapping work.

## 3. Default Accounts And GST Or Tax Setup

1. Open `/accounting/gl/default-accounts`.
2. Configure company default inventory, valuation, COGS, revenue, discount,
   and tax accounts through `PUT /api/v1/accounting/default-accounts`.
3. Open `/accounting/tax`.
4. Load GST return or reconciliation reads to confirm the current company tax
   mode.
5. In GST mode, require GST input, GST output, and GST payable accounts before
   allowing readiness-sensitive flows.
6. In non-GST mode, clear GST-only assumptions from the UI and do not preserve
   stale GST account ids in drafts.
7. Keep downstream setup blocked if required defaults or tax accounts are
   missing.

## 4. Product To Account Mapping And Stock Readiness

1. Accounting or catalog user creates the product on
   `POST /api/v1/catalog/items`.
2. For finished goods, verify valuation, COGS, revenue, discount, and tax
   accounts resolve from item metadata or company defaults.
3. For raw materials, verify `inventoryAccountId` resolves from item metadata or
   the default inventory account.
4. Show whether each resolved account came from explicit item setup or company
   default accounts so finance can correct the right layer.
5. If readiness is blocked, prevent opening stock, production use, packing,
   dispatch, and downstream sales/accounting assumptions.
6. If readiness is clear, opening stock can be imported on
   `POST /api/v1/inventory/opening-stock`.

## 5. Opening Stock Bootstrap

1. Upload opening stock only for accounting-ready SKUs.
2. Require the canonical import identifiers and idempotency values.
3. Surface row-level failures, partial-success results, and duplicate-batch
   outcomes.
4. After import, guide the user to inventory and journal verification so the
   opening balance is reconciled immediately.

## 6. Manual Journals And Single Reverse Path

1. List or open a journal entry.
2. Create journals only on `POST /api/v1/accounting/journal-entries`.
3. If correction is required, submit
   `POST /api/v1/accounting/journal-entries/{entryId}/reverse`.
4. If related documents must reverse too, send `cascadeRelatedEntries=true` or
   `relatedEntryIds` through the same canonical reverse route.
5. Show the new reversal entry and updated linked-reference chain in the journal
   detail experience.
6. If backend rejects the entry because the period is closed, keep the user on
   the same screen and surface the blocked-period reason instead of suggesting a
   manual workaround.

## 7. Purchase Invoice To AP Settlement

1. Treat PO and GRN as upstream prerequisites from the purchasing flow.
2. Start accounting-side AP work only after a GRN-linked purchase invoice
   exists.
3. Open `/accounting/ap/settlements`.
4. Review supplier statement and supplier aging for the target supplier.
5. Submit `POST /api/v1/accounting/settlements/suppliers` or
   `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`.
6. Refresh supplier aging, supplier statement, and reconciliation views after
   the settlement posts.

## 8. Dealer Receipt, AR Settlement, And Reconciliation

1. Open `/accounting/ar/receipts` to post `POST /api/v1/accounting/receipts/dealer`
   or `POST /api/v1/accounting/receipts/dealer/hybrid`.
2. Open `/accounting/ar/settlements` to post
   `POST /api/v1/accounting/settlements/dealers` or
   `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`.
3. Refresh dealer ledger, dealer invoices, dealer aging, and aged receivables
   after each clearing action.
4. Open `/accounting/reconciliation/bank` to create and complete bank
   reconciliation sessions.
5. Open `/accounting/reconciliation/subledger` to review subledger mismatch
   results and resolve discrepancies.
6. Compare settlement totals against period-filtered reports and aging views.
7. Resolve discrepancies before close.

## 9. Period Close Maker-Checker

1. Review the month-end checklist and reconciliation status.
2. Submit `request-close`.
3. Checker reviews and approves or rejects through the approval flow.
4. Direct close is never offered as a fallback action.
5. Reopen is superadmin-only and must not appear in normal accounting shells.

## 10. Reports, Export Request, Approval, And Download

1. Load trial balance, P&L, balance sheet, GST, reconciliation, and settlement
   reports with explicit period filters.
2. Preserve those filters during export.
3. Submit `POST /api/v1/exports/request` from the accounting report screen.
4. Show the request state in the accounting screen as `PENDING`, `APPROVED`,
   `REJECTED`, or `EXPIRED`.
5. Route approval decisions to the tenant-admin approval inbox, not to
   accounting page chrome.
6. Poll or recheck `GET /api/v1/exports/{requestId}/download` before enabling
   the actual download action.
7. If approval is pending, rejected, or expired, keep the user in the report
   screen with the current request status and reason.
