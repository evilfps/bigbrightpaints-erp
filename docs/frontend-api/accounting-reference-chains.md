# Accounting Reference Chains

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

## Inventory bootstrap

- catalog item create
- readiness resolution
- opening stock import
- stock movement
- journal entry

UI implication:

- Product setup and opening stock are two separate steps. Do not collapse them
  into one wizard.

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

## P2P

- supplier
- purchase order
- goods receipt
- purchase invoice
- supplier settlement

UI implication:

- Stock becomes true at GRN; AP becomes true at purchase invoice. Frontend must
  not present them as the same posting step.

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
