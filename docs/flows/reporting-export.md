# Reporting / Export Flow

Last reviewed: 2026-04-26

This packet documents the **reporting and export flow**: financial report reads, account statements, workflow shortcut guidance, and the export approval/download gate. It is written for client and implementation readers who need the current shipped behavior, not a list of planned or retired routes.

Reports and exports are part of the connected accounting system. Reports read accounting-owned truth; export decisions are controlled by the tenant-admin approval inbox; sensitive disclosure remains role-gated.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Finance/report operator and export approver | `ROLE_ADMIN` |
| **Accounting** | Finance/report operator and export requester | `ROLE_ACCOUNTING` |

`ReportController` and `WorkflowShortcutController` are guarded by the shared report/accounting disclosure policy. Current report routes under `/api/v1/reports/**` require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.

---

## 2. Entrypoints

### Financial Reports — `ReportController` (`/api/v1/reports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Trial Balance | GET | `/api/v1/reports/trial-balance` | ADMIN, ACCOUNTING | Trial balance report; supports period/range/as-of inputs. |
| Profit & Loss | GET | `/api/v1/reports/profit-loss` | ADMIN, ACCOUNTING | Income statement; reads live journal truth. |
| Balance Sheet | GET | `/api/v1/reports/balance-sheet` | ADMIN, ACCOUNTING | Balance sheet; supports period/range/as-of inputs. |
| Balance Sheet Hierarchy | GET | `/api/v1/reports/balance-sheet/hierarchy` | ADMIN, ACCOUNTING | Hierarchical balance sheet. |
| Income Statement Hierarchy | GET | `/api/v1/reports/income-statement/hierarchy` | ADMIN, ACCOUNTING | Hierarchical income statement. |
| Cash Flow | GET | `/api/v1/reports/cash-flow` | ADMIN, ACCOUNTING | Cash flow statement. |
| GST Return | GET | `/api/v1/reports/gst-return` | ADMIN, ACCOUNTING | GST return report data. |
| Account Statement | GET | `/api/v1/reports/account-statement?accountId={accountId}` | ADMIN, ACCOUNTING | Account activity statement by account id and optional dates. |

### Operational Accounting Reports — `ReportController` (`/api/v1/reports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Aged Debtors | GET | `/api/v1/reports/aged-debtors` | ADMIN, ACCOUNTING | Dealer aging rollup. |
| Aged Receivables | GET | `/api/v1/reports/aging/receivables` | ADMIN, ACCOUNTING | AR aging from accounting read model. |
| Inventory Valuation | GET | `/api/v1/reports/inventory-valuation` | ADMIN, ACCOUNTING | Current or as-of inventory valuation. |
| Inventory Reconciliation | GET | `/api/v1/reports/inventory-reconciliation` | ADMIN, ACCOUNTING | Inventory valuation compared to GL. |
| Product Costing | GET | `/api/v1/reports/product-costing?itemId={itemId}` | ADMIN, ACCOUNTING | Per-unit product cost breakdown. |
| Cost Allocation | GET | `/api/v1/reports/cost-allocation` | ADMIN, ACCOUNTING | Factory cost allocation history. |
| Wastage Report | GET | `/api/v1/reports/wastage` | ADMIN, ACCOUNTING | Production wastage report. |
| Production Cost | GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | ADMIN, ACCOUNTING | Cost breakdown for a production log. |
| Monthly Production Costs | GET | `/api/v1/reports/monthly-production-costs` | ADMIN, ACCOUNTING | Monthly production cost rows or period aggregate. |
| Reconciliation Dashboard | GET | `/api/v1/reports/reconciliation-dashboard` | ADMIN, ACCOUNTING | Bank reconciliation dashboard. |
| Balance Warnings | GET | `/api/v1/reports/balance-warnings` | ADMIN, ACCOUNTING | Anomalous balance warnings. |

### Workflow Shortcuts — `WorkflowShortcutController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Connected Workflow Shortcuts | GET | `/api/v1/reports/workflow-shortcuts` | ADMIN, ACCOUNTING | Returns guided step lists for order-to-invoice, procure-to-pay, and period-close/reconciliation. |

### Export Workflow — `ReportController` + `AdminApprovalController`

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Request Export | POST | `/api/v1/exports/request` | ADMIN, ACCOUNTING | Create an export request for a supported report type. |
| Approval Inbox | GET | `/api/v1/admin/approvals` | Tenant ADMIN | Review pending approval rows including export requests. |
| Approval Decision | POST | `/api/v1/admin/approvals/{originType}/{id}/decisions` | Tenant ADMIN | Approve or reject an approval item such as `EXPORT_REQUEST`. |
| Download Export | GET | `/api/v1/exports/{requestId}/download` | Requesting ADMIN or ACCOUNTING | Download after approval or explicit approval-gate bypass. |

There is no standalone export status endpoint, and there are no direct export approve/reject routes. Approval decisions belong to the canonical approval inbox.

### Statement and Aging Exports

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Supplier Statement PDF | GET | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | ADMIN | Download supplier statement PDF. |
| Supplier Aging PDF | GET | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | ADMIN | Download supplier aging PDF. |

Dealer finance disclosure uses internal finance reads under `/api/v1/portal/finance/**` and dealer self-service reads under `/api/v1/dealer-portal/**`. Dealer statement/aging PDF aliases under `/api/v1/accounting/statements/dealers/**` or `/api/v1/accounting/aging/dealers/**` are not current backend surfaces.

---

## 3. Preconditions

### Financial Report Preconditions

1. **Authenticated tenant context** — every report is scoped to the caller's company.
2. **RBAC** — `ROLE_ADMIN` or `ROLE_ACCOUNTING` is required.
3. **Valid filters** — period ids, date ranges, `accountId`, or `itemId` must match the selected report.

### Export Request Preconditions

1. **Report type and parameters** — the request body names the report type and serialized parameters.
2. **Requester ownership** — the created export belongs to the requesting user.
3. **Approval setting** — if export approval is required, download is blocked until approval.

### Export Approval Preconditions

1. **Approval inbox item exists** — export approvals appear with `originType=EXPORT_REQUEST`.
2. **Tenant admin actor** — tenant `ROLE_ADMIN` decides; platform super-admin is not the tenant-admin approval actor.
3. **Decision payload** — decision is `APPROVE` or `REJECT`; reason is accepted where the origin rules require or allow it.

---

## 4. Lifecycle

### 4.1 Financial Report Lifecycle

```
[Start] → Validate tenant + role → Resolve report filters →
Read accounting/reporting source truth → Return report DTO
```

**Key behaviors:**

- Trial balance uses period/range/as-of report queries and closed-period snapshot behavior where implemented.
- Profit & Loss reads live journal truth, including for closed periods.
- Balance Sheet supports snapshot/live branching where implemented.
- Account statement is a canonical account-level report keyed by `accountId`.
- Cash flow is available to admin/accounting but remains a sensitive finance report.

### 4.2 Export Approval Lifecycle

```
Report user requests export → Export request PENDING →
Tenant admin reviews inbox → Tenant admin posts decision →
Requester retries download → Approved or bypass-allowed download returns file
```

**Key behaviors:**

- The export request route returns the export request identity; callers should keep that id.
- The approval inbox route is the decision surface for tenant-admin review.
- The download route is the only current export retrieval route.
- If approval is required and the request is not approved, download is denied.
- If approval is disabled in settings, the backend may allow the download without an approved status; that is a backend policy outcome, not a frontend override.

### 4.3 Workflow Shortcut Lifecycle

`GET /api/v1/reports/workflow-shortcuts` returns connected step lists. The implemented draft/resume capability is bank reconciliation: create a bank reconciliation session, read it back while `IN_PROGRESS`, and complete it to promote the work.

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Report Generated** — the selected report returns for the tenant and authorized role.
2. **Export Requested** — `POST /api/v1/exports/request` creates a request.
3. **Export Decided** — a tenant admin applies a decision through `/api/v1/admin/approvals/{originType}/{id}/decisions`, when approval is required.
4. **Export Downloaded** — `GET /api/v1/exports/{requestId}/download` returns the file or a controlled denial.

### Current Limitations

1. **No standalone export status endpoint** — the current contract uses create, approval inbox, and download routes.
2. **No direct export approve/reject routes** — decisions are made through the approval inbox route only.
3. **Cash flow remains a sensitive live report** — use it as a finance disclosure, not as an ungated operational summary.
4. **Dealer statement PDFs are not exposed under accounting statement/aging aliases** — use portal finance or dealer portal reads for dealer finance disclosure.

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `GET /api/v1/reports/trial-balance` | `ReportController` | Trial balance report. |
| `GET /api/v1/reports/profit-loss` | `ReportController` | Live P&L report. |
| `GET /api/v1/reports/balance-sheet` | `ReportController` | Balance sheet report. |
| `GET /api/v1/reports/account-statement` | `ReportController` | Account statement by `accountId`. |
| `GET /api/v1/reports/workflow-shortcuts` | `WorkflowShortcutController` | Connected workflow guidance. |
| `POST /api/v1/exports/request` | `ReportController` | Export request. |
| `GET /api/v1/admin/approvals` | `AdminApprovalController` | Approval inbox. |
| `POST /api/v1/admin/approvals/{originType}/{id}/decisions` | `AdminApprovalController` | Approval decision. |
| `GET /api/v1/exports/{requestId}/download` | `ReportController` | Export download. |

### Non-Canonical / Retired Paths

| Path | Status | Replacement |
| --- | --- | --- |
| Standalone export status route | Not exposed | Keep the created id; use inbox and download route. |
| Direct export approve route | Not exposed | `POST /api/v1/admin/approvals/EXPORT_REQUEST/{id}/decisions` |
| Direct export reject route | Not exposed | `POST /api/v1/admin/approvals/EXPORT_REQUEST/{id}/decisions` |
| Export-specific tenant-admin approve/reject aliases | Retired approval aliases | `POST /api/v1/admin/approvals/EXPORT_REQUEST/{id}/decisions` |
| Export-specific pending list alias | Retired pending alias | `GET /api/v1/admin/approvals` |
| Dealer statement/aging PDFs under `/api/v1/accounting/statements/dealers/**` or `/aging/dealers/**` | Not current backend surface | `/api/v1/portal/finance/**` for internal dealer finance and `/api/v1/dealer-portal/**` for dealer self-service. |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `accounting` | Journal lines, periods, snapshots, account activity, dealer/supplier ledgers | Read |
| `inventory` | Stock batches and valuation data | Read |
| `factory` | Production logs and costing data | Read |
| `sales` | Dealer and commercial data for receivables/aging context | Read |
| `admin` | Approval inbox and decision workflow for sensitive export requests | Write/read |

---

## 8. Security Considerations

- **RBAC** — report and export request/download routes require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.
- **Company scoping** — report and export records are tenant-scoped.
- **Requester-owned download** — export download checks the request belongs to the authenticated requester.
- **Approval gate** — when enabled, non-approved exports are denied at download time.
- **Admin-only PDFs** — supplier statement and supplier aging PDF exports require `ROLE_ADMIN`.

---

## 9. Related Documentation

- [Accounting Workflow Architecture](accounting-workflow-architecture.md) — client-shareable connected accounting architecture
- [Accounting / Period Close Flow](accounting-period-close.md) — period close and reconciliation workflow
- [Invoice / Dealer Finance Flow](invoice-dealer-finance.md) — dealer finance reads and settlement linkage
- [docs/modules/reports.md](../modules/reports.md) — Reports module packet
- [docs/frontend-api/exports-and-approvals.md](../frontend-api/exports-and-approvals.md) — frontend-facing export and approval contract

---

## 10. Known Limitations

> **Note**: The authoritative classification for these items is recorded in the [Authoritative Recommendations Register](../RECOMMENDATIONS.md). This section documents factual implementation status only.

| Decision | Notes |
| --- | --- |
| P&L snapshot branch | Not implemented. P&L reads live journal truth. |
| Standalone export status endpoint | Not implemented. Use the created request id and canonical download route. |
| Direct export approval aliases | Retired/not exposed. Use the approval inbox decision route. |
| Dealer statement PDF aliases under accounting | Not exposed. Use portal finance/dealer portal reads for dealer finance and supplier PDF routes for supplier exports. |
