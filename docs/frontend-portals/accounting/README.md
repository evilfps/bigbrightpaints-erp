# Accounting Portal

This portal is the tenant finance workspace. It owns chart of accounts, company
default posting accounts, GST and tax setup, manual journals, the single public
reverse flow, reconciliation, period-close maker-checker, opening stock review,
AR/AP settlement, and accountant-facing reports.

The accounting portal is available only after tenant onboarding completes the
finance bootstrap. Frontend should treat the onboarding response as the source
of truth that the tenant-local chart of accounts and default accounting period
already exist.

## Portal Ownership

- browse and create chart of accounts
- maintain company default posting accounts
- maintain GST and tax-account readiness
- verify product-to-account mapping and SKU readiness
- create manual journals through the canonical journal-entry route
- reverse journals through the canonical reverse route
- run bank, subledger, inventory, and GST reconciliation
- drive request-close, approve-close, and reject-close workflow
- review and import opening stock only after accounting readiness is complete
- review dealer and supplier settlement flows
- own finance reports and export-governed downloads

## What Starts Here

- COA maintenance starts here only after superadmin onboarding seeds the tenant
  chart from the selected template.
- Automatic product posting rules start here through company default accounts
  and item-level overrides.
- Period close starts here only as maker-checker workflow, never as direct
  close.

## Canonical Rules

- Tenant onboarding seeds chart of accounts first. Frontend must assume
  onboarding already returned `seededChartOfAccounts=true` before accounting
  setup begins.
- The accounting portal must fail closed if tenant bootstrap does not confirm
  `seededChartOfAccounts`, `defaultAccountingPeriodCreated`, and
  `tenantAdminProvisioned`.
- Company default posting accounts are the shared source for automatic product
  account inheritance.
- Manual journals use only
  `POST /api/v1/accounting/journal-entries`.
- Journal reversal uses only
  `POST /api/v1/accounting/journal-entries/{entryId}/reverse`.
- Direct `POST /api/v1/accounting/periods/{periodId}/close` is not a frontend
  action.
- Supported close flow is
  `request-close -> approve-close|reject-close`, with reopen reserved for
  superadmin.
- Product setup remains `POST /api/v1/catalog/items`; opening stock remains
  `POST /api/v1/inventory/opening-stock`.
- Finished-good and raw-material account readiness stays fail-closed until
  mapping is valid.

## Boundary Rules

- Superadmin owns tenant onboarding, COA template choice, tenant lifecycle, and
  reopen authority.
- Tenant-admin owns tenant-wide user management and the approval shell.
- Sales owns dealer, order, and commercial execution screens.
- Factory owns production, packing, and dispatch execution.
- Dealer-client owns self-service invoices, ledger visibility, and credit
  requests.

Accounting owns the financial truth after onboarding is complete. It does not
own tenant creation, tenant user lifecycle, or dispatch execution even when
those flows create accounting side effects.
