# Accounting Routes

Every route below belongs in `docs/frontend-portals/accounting/`. Do not
duplicate ownership of these screens into superadmin, tenant-admin, sales,
factory, or dealer-client folders.

| UI route | Backend contract | Ownership |
| --- | --- | --- |
| `/accounting/overview` | accounting dashboard reads plus approval summaries | Accounting home and finance summary. |
| `/accounting/gl/chart-of-accounts` | `GET /api/v1/accounting/accounts/tree`, `GET /api/v1/accounting/accounts`, `POST /api/v1/accounting/accounts` | COA tree, account browse, and account creation. |
| `/accounting/gl/default-accounts` | `GET /api/v1/accounting/default-accounts`, `PUT /api/v1/accounting/default-accounts` | Company default inventory, valuation, COGS, revenue, discount, and tax accounts. |
| `/accounting/tax` | tax settings reads, GST reconciliation reads, default-account tax reads | GST mode and tax-account safety. |
| `/accounting/catalog/account-readiness` | `GET /api/v1/catalog/items`, `POST /api/v1/catalog/items` | Product/account mapping review and readiness blockers. |
| `/accounting/gl/journals` | `GET /api/v1/accounting/journal-entries`, `POST /api/v1/accounting/journal-entries` | Manual journal list and create. |
| `/accounting/gl/journals/:entryId` | journal detail-support reads | Journal detail, linked references, and audit context. |
| `/accounting/gl/journals/:entryId/reverse` | `POST /api/v1/accounting/journal-entries/{entryId}/reverse` | Single supported journal correction path. |
| `/accounting/period-close/checklist` | `GET /api/v1/accounting/month-end/checklist`, `POST /api/v1/accounting/month-end/checklist/{periodId}` | Pre-close checklist ownership. |
| `/accounting/period-close` | `GET /api/v1/accounting/periods`, `POST /api/v1/accounting/periods/{periodId}/request-close`, `POST /api/v1/accounting/periods/{periodId}/approve-close`, `POST /api/v1/accounting/periods/{periodId}/reject-close` | Maker-checker close workflow only. |
| `/accounting/reconciliation` | bank-session reconciliation endpoints, `GET /api/v1/accounting/reconciliation/subledger`, GST reconciliation reads, discrepancy resolution | Reconcile before close. |
| `/accounting/inventory/opening-stock` | `GET /api/v1/inventory/opening-stock`, `POST /api/v1/inventory/opening-stock` | Controlled stock bootstrap and import audit. |
| `/accounting/ar/settlements` | dealer receipt, dealer settlement, AR aging endpoints | Dealer clearing and AR review. |
| `/accounting/ap/settlements` | supplier settlement and AP aging endpoints | Supplier clearing and AP review. |
| `/accounting/reports` | `GET /api/v1/reports/**`, governed export flow | Financial reports and approved exports. |

Route ownership rules:

- COA, default accounts, tax setup, journals, reversal, reconciliation,
  settlements, and finance reports stay inside accounting.
- The route tree may show onboarding blockers, but it must not recreate tenant
  onboarding screens or COA template selection.
- Sales can read invoice and credit status, but accounting owns finance
  correction screens.
- Factory can participate in stock bootstrap operations where backend policy
  allows it, but accounting owns the readiness, policy, and audit view.
- Superadmin owns reopen visibility and action; it does not belong in ordinary
  accounting navigation.
