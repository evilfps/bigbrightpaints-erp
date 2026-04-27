# Accounting Workflow Architecture

Last reviewed: 2026-04-26

This client-shareable packet explains the connected accounting workflow now implemented in the ERP. It describes accounting as one business system: commercial and operational events create finance truth, finance users review and settle that truth, and period close/reporting read from the same accounting boundary.

The language below is intentionally simple. It documents shipped backend behavior only; it does not describe planned approval aliases, retired routes, or unsupported export status endpoints as current truth.

---

## 1. Accounting Boundary

`AccountingFacade` remains the stable backend boundary for accounting side effects from sales, purchasing, inventory, factory, HR payroll, and reporting. Behind that boundary the system keeps five clear responsibilities:

| Responsibility | What it means for the business |
| --- | --- |
| Policy | Role checks, tenant isolation, period state, sensitive-disclosure rules, and validation run before money changes. |
| Orchestration | Order-to-cash, procure-to-pay, inventory costing, settlement, reconciliation, and close steps stay linked as business flows. |
| Posting | Journals, reversals, reference protection, idempotency, and audit evidence are created by the canonical accounting owner. |
| Account resolution | Chart of accounts, default accounts, product/dealer/supplier wiring, and readiness blockers decide which accounts may be used. |
| Reporting | Financial reports, statements, aging, shortcuts, and export approval gates read accounting-owned truth instead of rebuilding alternate ledgers. |

The important business rule is: payment truth is captured first, allocation remains explicit, and journals are derived from that truth. Balances and summaries should reconcile to document-level invoices or purchases, payment events, allocation rows, and journal evidence.

---

## 2. Actors and Disclosure Boundaries

| Actor | Current backend authority | What they can do in these accounting flows |
| --- | --- | --- |
| Tenant admin / finance admin | `ROLE_ADMIN` | Use accounting reports, settlements, period close approval, export approval inbox, and admin-only PDF statement exports. |
| Accountant | `ROLE_ACCOUNTING` | Use accounting reports, journal/settlement/reconciliation work, period close request work, and export request/download checks. |
| Dealer user | `ROLE_DEALER` | Read their own dealer-portal finance views only: dashboard, invoices, ledger, aging, and own invoice PDFs. |
| Platform super-admin | `ROLE_SUPER_ADMIN` | Reopen accounting periods and manage tenant-level feature toggles; not the tenant-admin export approval actor. |

Sensitive financial disclosures stay role-safe:

- Financial report routes under `/api/v1/reports/**` are guarded for `ROLE_ADMIN` or `ROLE_ACCOUNTING`.
- Export decisions use the tenant-admin approval inbox, not direct export approve/reject routes.
- Supplier statement and aging PDFs are admin-only exports.
- Dealer self-service finance reads are self-scoped; internal dealer finance reads require admin/accounting authority and an explicit dealer id.
- Invoice issuance after dispatch is not approval-gated.
- Review intelligence/anomaly behavior remains superadmin-toggle controlled, default-off, and warning-only where enabled.

---

## 3. Connected Workflow Map

### 3.1 Order to invoice to collection

1. Sales captures and confirms the order.
2. Dispatch confirmation ships actual quantities and records dispatch/accounting markers.
3. The invoice is issued from the dispatch chain; invoice issuance itself is not approval-gated.
4. Dealer receipt or settlement records payment truth through accounting routes.
5. Allocations apply payment to invoices in explicit rows; any supported on-account remainder remains traceable.
6. Dealer ledger, portal finance, aging, and reports read the surviving finance truth.

Canonical finance entrypoints:

| Purpose | Method | Path | Roles |
| --- | --- | --- | --- |
| Dealer receipt | POST | `/api/v1/accounting/receipts/dealer` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Dealer hybrid receipt | POST | `/api/v1/accounting/receipts/dealer/hybrid` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Dealer settlement | POST | `/api/v1/accounting/settlements/dealers` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Dealer auto-settle | POST | `/api/v1/accounting/dealers/{dealerId}/auto-settle` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Internal dealer ledger | GET | `/api/v1/portal/finance/ledger?dealerId={dealerId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Dealer self-service ledger | GET | `/api/v1/dealer-portal/ledger` | `ROLE_DEALER` |

### 3.2 Procure to pay

1. Supplier and purchase documents capture the payable obligation.
2. Goods receipt and purchase posting create inventory/AP/accounting truth.
3. Supplier settlement or auto-settle records the payment and allocations against open purchases.
4. Supplier statement, aging, and AP reconciliation read the supplier ledger and accounting truth.

Canonical finance entrypoints:

| Purpose | Method | Path | Roles |
| --- | --- | --- | --- |
| Supplier settlement | POST | `/api/v1/accounting/settlements/suppliers` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Supplier auto-settle | POST | `/api/v1/accounting/suppliers/{supplierId}/auto-settle` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Supplier statement | GET | `/api/v1/accounting/statements/suppliers/{supplierId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Supplier aging | GET | `/api/v1/accounting/aging/suppliers/{supplierId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Supplier statement PDF | GET | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | `ROLE_ADMIN` |
| Supplier aging PDF | GET | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | `ROLE_ADMIN` |

### 3.3 Reconciliation and period close

1. Accountants prepare the month-end checklist.
2. Bank reconciliation can be saved as an `IN_PROGRESS` session, resumed, and completed.
3. Subledger reconciliation compares AR/AP control balances against partner ledgers.
4. Open discrepancies are resolved or acknowledged through the discrepancy workflow.
5. A maker submits a period close request with a note.
6. A checker approves or rejects; approval closes the period and records close artifacts.
7. Super-admin period reopen exists for controlled correction only.

Canonical close/reconciliation entrypoints:

| Purpose | Method | Path | Roles |
| --- | --- | --- | --- |
| Checklist read | GET | `/api/v1/accounting/month-end/checklist?periodId={periodId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Checklist update | POST | `/api/v1/accounting/month-end/checklist/{periodId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Start bank session | POST | `/api/v1/accounting/reconciliation/bank/sessions` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Resume bank session | GET | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Update bank session items | PUT | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/items` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Complete bank session | POST | `/api/v1/accounting/reconciliation/bank/sessions/{sessionId}/complete` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Subledger reconciliation | GET | `/api/v1/accounting/reconciliation/subledger` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Discrepancy list | GET | `/api/v1/accounting/reconciliation/discrepancies` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Discrepancy resolution | POST | `/api/v1/accounting/reconciliation/discrepancies/{discrepancyId}/resolve` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Request close | POST | `/api/v1/accounting/periods/{periodId}/request-close` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Approve close | POST | `/api/v1/accounting/periods/{periodId}/approve-close` | `ROLE_ADMIN` |
| Reject close | POST | `/api/v1/accounting/periods/{periodId}/reject-close` | `ROLE_ADMIN` |
| Reopen period | POST | `/api/v1/accounting/periods/{periodId}/reopen` | `ROLE_SUPER_ADMIN` |

The tenant-admin approval inbox also surfaces period-close approval work through `GET /api/v1/admin/approvals` and `POST /api/v1/admin/approvals/PERIOD_CLOSE_REQUEST/{id}/decisions`.

### 3.4 Reporting, exports, and shortcuts

Financial reports are live backend reads guarded to admin/accounting. Export requests are created by report users and decided through the tenant-admin approval inbox. There is no current standalone export status route and no direct export approve/reject route.

Canonical reporting/export entrypoints:

| Purpose | Method | Path | Roles |
| --- | --- | --- | --- |
| Trial balance | GET | `/api/v1/reports/trial-balance` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Profit and loss | GET | `/api/v1/reports/profit-loss` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Balance sheet | GET | `/api/v1/reports/balance-sheet` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Cash flow | GET | `/api/v1/reports/cash-flow` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| GST return report | GET | `/api/v1/reports/gst-return` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Account statement | GET | `/api/v1/reports/account-statement?accountId={accountId}` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Workflow shortcuts | GET | `/api/v1/reports/workflow-shortcuts` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Request export | POST | `/api/v1/exports/request` | `ROLE_ADMIN`, `ROLE_ACCOUNTING` |
| Approval inbox | GET | `/api/v1/admin/approvals` | tenant `ROLE_ADMIN` |
| Approval decision | POST | `/api/v1/admin/approvals/{originType}/{id}/decisions` | tenant `ROLE_ADMIN` |
| Download export | GET | `/api/v1/exports/{requestId}/download` | requester with `ROLE_ADMIN` or `ROLE_ACCOUNTING` |

---

## 4. Draft, Save, Resume, and Next-Step Guidance

The shipped workflow-shortcut catalog is available at `GET /api/v1/reports/workflow-shortcuts`. It currently describes three connected flows: order-to-invoice, procure-to-pay, and period-close/reconciliation.

The implemented draft/resume behavior is specific: bank reconciliation sessions are draft-like artifacts. Creating a session saves `IN_PROGRESS` work, reading the session resumes it, and completing it promotes the session to `COMPLETED`. This draft behavior does not post journals, settle partners, or close periods until the explicit completion/approval step runs.

---

## 5. Routes This Architecture Does Not Use

The following routes or aliases are not current workflow truth and should not be built into client-facing process diagrams:

| Retired or unsupported surface | Current replacement |
| --- | --- |
| Standalone export status route | Keep the returned id from `POST /api/v1/exports/request`; use the approval inbox and then `GET /api/v1/exports/{requestId}/download`. |
| Direct export approve/reject aliases | `POST /api/v1/admin/approvals/EXPORT_REQUEST/{id}/decisions` |
| Export-specific tenant-admin approve/reject aliases | `POST /api/v1/admin/approvals/EXPORT_REQUEST/{id}/decisions` |
| Legacy close-request alias | `POST /api/v1/accounting/periods/{periodId}/request-close` |
| Legacy close-approve alias | `POST /api/v1/accounting/periods/{periodId}/approve-close` |
| Legacy close-finalize alias | No separate finalize route; approval completes the close. |
| Legacy bank-session prefix without `/bank` | `/api/v1/accounting/reconciliation/bank/sessions/**` |
| Dealer/supplier-specific reconciliation aliases | `GET /api/v1/accounting/reconciliation/subledger` |
| Invoice-hosted payment or settlement aliases | Accounting receipt and settlement routes under `/api/v1/accounting/**` |
| Dealer statement/aging PDFs under `/api/v1/accounting/statements/dealers/**` or `/aging/dealers/**` | Internal dealer finance reads under `/api/v1/portal/finance/**`; dealer self-service under `/api/v1/dealer-portal/**`; supplier PDFs remain under `/api/v1/accounting/**` and admin-only. |

---

## 6. Related Canonical Flow Packets

- [Accounting / Period Close Flow](accounting-period-close.md)
- [Reporting / Export Flow](reporting-export.md)
- [Invoice / Dealer Finance Flow](invoice-dealer-finance.md)
- [Order-to-Cash Flow](order-to-cash.md)
- [Procure-to-Pay Flow](procure-to-pay.md)
- [Backend Flow Inventory](FLOW-INVENTORY.md)
