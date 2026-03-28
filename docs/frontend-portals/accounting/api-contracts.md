# Accounting API Contracts

This file is the accounting portal API truth. Frontend should not infer
alternate routes from retired docs, test fixtures, or legacy controllers.

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

## Tax Configuration

- GST mode requires valid input, output, and payable tax accounts.
- Non-GST mode must not persist GST-only assumptions in the UI.
- Tax configuration belongs in accounting and should not be split across
  multiple portal folders.
- Taxable finished goods must resolve a valid revenue-side tax account before
  accounting readiness can pass.

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

## Reconciliation

- `GET /api/v1/accounting/reconciliation/subledger`
- bank-session reconciliation endpoints
- GST reconciliation reads

Use reconciliation as the close-readiness truth for bank, subledger, inventory,
and GST discrepancy handling.

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

## Opening Stock

- `GET /api/v1/inventory/opening-stock`
- `POST /api/v1/inventory/opening-stock`

Rules:

- Opening stock is available only after product/account readiness is clear.
- Use the canonical idempotency headers and import identifiers required by the
  backend.
- Frontend must surface row-level validation and duplicate-batch outcomes.

## AR, AP, And Reports

- dealer receipt and dealer settlement endpoints
- supplier settlement endpoints
- `GET /api/v1/reports/**`

Rules:

- AR settlement and AP settlement belong to accounting even when sales or
  supplier flows create the underlying business documents.
- Reports and CSV exports stay inside accountant-facing flows.
- If export approval is required, frontend must surface approval state instead
  of bypassing governance with a direct file link.
- Settlement screens must preserve tenant, period, and ledger filters so report
  totals reconcile with the same accounting context.

## Forbidden Public Routes

- Do not use `/api/v1/accounting/journals/manual`.
- Do not use `/api/v1/accounting/journals/{entryId}/reverse`.
- Do not use `/api/v1/accounting/journal-entries/{entryId}/cascade-reverse`.
- Do not use `/api/v1/accounting/periods/{periodId}/close`.
