# Accounting Routes

Every route below belongs in `docs/frontend-portals/accounting/`. Do not
duplicate ownership of these screens into superadmin, tenant-admin, sales,
factory, or dealer-client folders.

| UI route | Backend contract | Ownership |
| --- | --- | --- |
| `/accounting/overview` | accounting dashboard reads plus approval summaries | Accounting home and finance summary. |
| `/accounting/gl/chart-of-accounts` | `GET /api/v1/accounting/accounts/tree`, `GET /api/v1/accounting/accounts`, `POST /api/v1/accounting/accounts` | COA tree, account browse, and account creation. |
| `/accounting/gl/default-accounts` | `GET /api/v1/accounting/default-accounts`, `PUT /api/v1/accounting/default-accounts` | Company default inventory, valuation, COGS, revenue, discount, and tax accounts. |
| `/accounting/tax` | `GET /api/v1/accounting/gst/return`, `GET /api/v1/accounting/gst/reconciliation`, default-account tax reads | GST mode, tax-account safety, and GST reconciliation. |
| `/accounting/catalog/account-readiness` | `GET /api/v1/catalog/items`, `POST /api/v1/catalog/items` | Product/account mapping review. This screen owns the blocking state when COA, default accounts, or item mappings are incomplete. |
| `/accounting/gl/journals` | `GET /api/v1/accounting/journal-entries`, `POST /api/v1/accounting/journal-entries` | Manual journal list and create. |
| `/accounting/gl/journals/:entryId` | journal detail-support reads | Journal detail, linked references, and audit context. |
| `/accounting/gl/journals/:entryId/reverse` | `POST /api/v1/accounting/journal-entries/{entryId}/reverse` | Single supported journal correction path. |
| `/accounting/period-close/checklist` | `GET /api/v1/accounting/month-end/checklist`, `POST /api/v1/accounting/month-end/checklist/{periodId}` | Pre-close checklist ownership. |
| `/accounting/period-close` | `GET /api/v1/accounting/periods`, `POST /api/v1/accounting/periods/{periodId}/request-close`, `POST /api/v1/accounting/periods/{periodId}/approve-close`, `POST /api/v1/accounting/periods/{periodId}/reject-close` | Maker-checker close workflow only. |
| `/accounting/reconciliation` | reconciliation dashboard reads, GST reconciliation reads | Reconciliation summary and portal-level blockers before close. |
| `/accounting/reconciliation/bank` | `POST /api/v1/accounting/reconciliation/bank/sessions`, `GET /api/v1/accounting/reconciliation/bank/sessions` | Bank reconciliation session list and create. |
| `/accounting/reconciliation/bank/sessions/:sessionId` | `GET /api/v1/accounting/reconciliation/bank/sessions/{sessionId}`, `PUT /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items`, `POST /api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete` | Bank reconciliation detail, journal-line match edits, and completion. |
| `/accounting/reconciliation/subledger` | `GET /api/v1/accounting/reconciliation/subledger`, `GET /api/v1/accounting/reconciliation/discrepancies`, `POST /api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | Subledger mismatch review and discrepancy resolution. |
| `/accounting/inventory/opening-stock` | `GET /api/v1/inventory/opening-stock`, `POST /api/v1/inventory/opening-stock` | Controlled stock bootstrap and import audit. This screen stays blocked until readiness passes on `/accounting/catalog/account-readiness`. |
| `/accounting/ar/receipts` | `POST /api/v1/accounting/receipts/dealer`, `POST /api/v1/accounting/receipts/dealer/hybrid` | Dealer receipt posting before or alongside settlement review. |
| `/accounting/ar/settlements` | `POST /api/v1/accounting/settlements/dealers`, `POST /api/v1/accounting/dealers/{dealerId}/auto-settle`, `GET /api/v1/portal/finance/ledger`, `GET /api/v1/portal/finance/invoices`, `GET /api/v1/portal/finance/aging`, `GET /api/v1/reports/aging/receivables` | Dealer clearing, AR aging, and dealer-ledger review. |
| `/accounting/ap/settlements` | `POST /api/v1/accounting/settlements/suppliers`, `POST /api/v1/accounting/suppliers/{supplierId}/auto-settle`, `GET /api/v1/accounting/statements/suppliers/{supplierId}`, `GET /api/v1/accounting/aging/suppliers/{supplierId}` | Supplier clearing, AP statement review, and supplier aging. |
| `/accounting/reports` | `GET /api/v1/reports/**`, `POST /api/v1/exports/request`, `GET /api/v1/exports/{requestId}/download` | Financial reports, export request entry, pending/approved/rejected status, and approved downloads. |

Route ownership rules:

- COA, default accounts, tax setup, journals, reversal, reconciliation,
  settlements, and finance reports stay inside accounting.
- The route tree may show onboarding blockers, but it must not recreate tenant
  onboarding screens or COA template selection.
- Sales can read invoice and credit status, but accounting owns finance
  correction screens, export request creation, and AR/AP clearing screens.
- Factory can participate in stock bootstrap operations where backend policy
  allows it, but accounting owns the readiness, policy, and audit view.
- Superadmin owns reopen visibility and action; it does not belong in ordinary
  accounting navigation.
- Tenant-admin owns export approval decisions. Accounting owns export request
  initiation and post-approval download state only.
