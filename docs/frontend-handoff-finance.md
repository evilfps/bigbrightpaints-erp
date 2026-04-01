# Frontend Handoff — Finance, Reporting, and HR Surfaces

> ⚠️ **NON-CANONICAL / REFERENCE ONLY**
> 
> This document is **superseded** by the canonical frontend documentation at:
> - [docs/frontend-portals/](frontend-portals/README.md) — portal ownership, routes, API contracts, workflows, role boundaries
> - [docs/frontend-api/](frontend-api/README.md) — shared API contracts, bootstrap rules, tenant scoping
>
> This legacy handoff file is retained for reference only. Do not use it as the source of truth.

Last reviewed: 2026-03-30

This packet documents the frontend contract for **finance, reporting, and HR surfaces** — accounting journals, period management, payroll operations, and financial/operational reporting. It explains canonical hosts, payload families, RBAC assumptions, read/write boundaries, and the approval workflow for report exports.

This packet defers to the canonical module and flow docs for implementation truth and is not a second source of truth.

---

## 1. Scope Overview

| Surface | Module | Canonical Doc |
| --- | --- | --- |
| Accounting / Period Close | `accounting` (AccountingController) | [docs/flows/accounting-period-close.md](flows/accounting-period-close.md) |
| HR / Payroll | `hr` (HrController, HrPayrollController) | [docs/modules/hr.md](modules/hr.md), [docs/flows/hr-payroll.md](flows/hr-payroll.md) |
| Reporting / Export | `reports` (ReportController) | [docs/modules/reports.md](modules/reports.md), [docs/flows/reporting-export.md](flows/reporting-export.md) |

---

## 2. Canonical Host Prefixes

All finance, reporting, and HR endpoints use the same host prefix:

```
/api/v1/
```

---

## 3. Accounting / Period Close Routes

### 3.1 Journal Entry Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/accounting/journals` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/journals` | POST | ADMIN, ACCOUNTING | Write (create draft) |
| `/api/v1/accounting/journals/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/journals/{id}` | PUT | ADMIN, ACCOUNTING | Write (update draft) |
| `/api/v1/accounting/journals/{id}` | DELETE | ADMIN, ACCOUNTING | Write (delete draft) |
| `/api/v1/accounting/journals/{id}/post` | POST | ADMIN, ACCOUNTING | Write (post to ledger) |
| `/api/v1/accounting/journals/{id}/reverse` | POST | ADMIN, ACCOUNTING | Write (reverse posted) |
| `/api/v1/accounting/journals/{id}/approve` | POST | ADMIN | Write (approve) |
| `/api/v1/accounting/journals/{id}/reject` | POST | ADMIN | Write (reject) |

### 3.2 Period Management Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/accounting/periods` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/periods` | POST | ADMIN | Write |
| `/api/v1/accounting/periods/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/periods/{id}/lock` | POST | ADMIN | Write |
| `/api/v1/accounting/periods/{id}/unlock` | POST | ADMIN | Write |
| `/api/v1/accounting/periods/{id}/close-request` | POST | ACCOUNTING | Write (request close) |
| `/api/v1/accounting/periods/{id}/close-approve` | POST | ADMIN | Write (approve close) |
| `/api/v1/accounting/periods/{id}/close-finalize` | POST | ADMIN | Write (finalize close) |

### 3.3 Reconciliation Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/accounting/reconciliation/sessions` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/reconciliation/sessions` | POST | ADMIN, ACCOUNTING | Write (start session) |
| `/api/v1/accounting/reconciliation/sessions/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/reconciliation/sessions/{id}/match` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/accounting/reconciliation/sessions/{id}/unmatch` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/accounting/reconciliation/sessions/{id}/adjustments` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/accounting/reconciliation/sessions/{id}/complete` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/accounting/reconciliation/dealers/{dealerId}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/reconciliation/suppliers/{supplierId}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/reconciliation/run` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/accounting/gst/reconciliation` | GET | ADMIN, ACCOUNTING | Read |

### 3.4 Accounting Support Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/accounting/ledgers` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/ledgers/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/accounts` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/accounting/accounts` | POST | ADMIN | Write |
| `/api/v1/accounting/accounts/{id}` | PUT | ADMIN | Write |
| `/api/v1/migration/opening-balance` | POST | ADMIN | Write |
| `/api/v1/migration/tally-import` | POST | ADMIN | Write |

---

## 4. HR / Payroll Routes

### 4.1 Employee Management Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/hr/employees` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/employees` | POST | ADMIN | Write |
| `/api/v1/hr/employees/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/employees/{id}` | PUT | ADMIN | Write |
| `/api/v1/hr/employees/{id}` | DELETE | ADMIN | Write (soft-delete) |

### 4.2 Leave Management Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/hr/leave-requests` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/leave-requests` | POST | ADMIN | Write |
| `/api/v1/hr/leave-requests/{id}/approve` | POST | ADMIN | Write |
| `/api/v1/hr/leave-requests/{id}/reject` | POST | ADMIN | Write |
| `/api/v1/hr/leave-types` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/leave-types` | POST | ADMIN | Write |
| `/api/v1/hr/employees/{employeeId}/leave-balances` | GET | ADMIN, ACCOUNTING | Read |

### 4.3 Attendance Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/hr/attendance/date/{date}` | POST | ADMIN | Write |
| `/api/v1/hr/attendance/bulk-mark` | POST | ADMIN | Write |
| `/api/v1/hr/attendance/bulk-import` | POST | ADMIN | Write |
| `/api/v1/hr/attendance/summary` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/salary-structures` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/hr/salary-structures` | POST | ADMIN | Write |
| `/api/v1/hr/salary-structures/{id}` | PUT | ADMIN | Write |

### 4.4 Payroll Run Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/payroll/runs` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/payroll/runs` | POST | ADMIN, ACCOUNTING | Write (create) |
| `/api/v1/payroll/runs/weekly` | POST | ADMIN, ACCOUNTING | Write (create weekly) |
| `/api/v1/payroll/runs/monthly` | POST | ADMIN, ACCOUNTING | Write (create monthly) |
| `/api/v1/payroll/runs/{id}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/payroll/runs/{id}/calculate` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/payroll/runs/{id}/approve` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/payroll/runs/{id}/post` | POST | ADMIN, ACCOUNTING | Write (post to accounting) |
| `/api/v1/payroll/runs/{id}/mark-paid` | POST | ADMIN, ACCOUNTING | Write (record payment) |
| `/api/v1/payroll/summary/weekly` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/payroll/summary/monthly` | GET | ADMIN, ACCOUNTING | Read |

---

## 5. Reporting / Export Routes

### 5.1 Financial Report Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/reports/trial-balance` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/profit-loss` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/balance-sheet` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/balance-sheet/hierarchy` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/cash-flow` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/gst-return` | GET | ADMIN, ACCOUNTING | Read |

### 5.2 Operational Report Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/reports/aged-debtors` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/aging/receivables` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/inventory-valuation` | GET | ADMIN, ACCOUNTING, FACTORY | Read |
| `/api/v1/reports/inventory-reconciliation` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/wastage` | GET | ADMIN, FACTORY | Read |
| `/api/v1/reports/production-logs/{id}/cost-breakdown` | GET | ADMIN, FACTORY | Read |
| `/api/v1/reports/monthly-production-costs` | GET | ADMIN, FACTORY | Read |
| `/api/v1/reports/reconciliation-dashboard` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/reports/balance-warnings` | GET | ADMIN, ACCOUNTING | Read |

### 5.3 Export Workflow Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/exports/request` | POST | ADMIN, ACCOUNTING | Write |
| `/api/v1/exports/{requestId}` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/exports/{requestId}/download` | GET | ADMIN, ACCOUNTING | Read |
| `/api/v1/exports/{requestId}/approve` | POST | ADMIN | Write |
| `/api/v1/exports/{requestId}/reject` | POST | ADMIN | Write |

### 5.4 Statement Export Routes

| Route | Method | Actor | Read/Write |
| --- | :---: | :---: | :---: |
| `/api/v1/accounting/statements/dealers/{dealerId}/pdf` | GET | ADMIN | Read |
| `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | GET | ADMIN | Read |
| `/api/v1/accounting/aging/dealers/{dealerId}/pdf` | GET | ADMIN | Read |
| `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | GET | ADMIN | Read |

---

## 6. RBAC Summary

### 6.1 Role Permissions by Surface

| Role | Accounting (Journals) | Accounting (Periods/Reconciliation) | HR/Payroll | Financial Reports | Operational Reports | Export Approval |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| `ROLE_ADMIN` | Full | Full | Full | Full | Full | Full |
| `ROLE_ACCOUNTING` | Full | Full | Full | Full | Read (limited) | — |
| `ROLE_SALES` | — | — | — | — | — | — |
| `ROLE_FACTORY` | — | — | — | — | Read (production) | — |
| `ROLE_DEALER` | — | — | — | — | — | — |

### 6.2 Key RBAC Boundaries

1. **Admin owns period close** — Only `ROLE_ADMIN` can lock/unlock periods, approve and finalize period close. `ROLE_ACCOUNTING` can request close but needs admin approval.

2. **HR module is gated** — HR/Payroll endpoints return `MODULE_DISABLED` if the tenant has the HR module disabled (controlled by `ModuleGatingService`). Default is paused for new tenants (ERP-33).

3. **Reports require ADMIN or ACCOUNTING** — All financial and operational reports require `ROLE_ADMIN` or `ROLE_ACCOUNTING`. Production reports additionally allow `ROLE_FACTORY`.

4. **Export approval requires ADMIN** — Only `ROLE_ADMIN` can approve or reject export requests. However, if `exportApprovalRequired` system setting is disabled, downloads are available even for REJECTED requests.

5. **Statement exports are admin-only** — Dealer/supplier statement PDFs and aging PDFs require `ROLE_ADMIN` and bypass the export approval workflow.

6. **Portal vs Admin ownership** — Portal finance views (`/api/v1/portal/finance/ledger`, `/api/v1/portal/finance/invoices`, `/api/v1/portal/finance/aging`) are for admin internal use. Dealer self-service uses `/api/v1/dealer-portal/ledger`, `/api/v1/dealer-portal/invoices`, `/api/v1/dealer-portal/aging`.

---

## 7. Read/Write Boundaries

### 7.1 Accounting Boundaries

- **Journals**: Created as DRAFT, then POSTED to become immutable. Posted journals can be reversed but not edited. The period must be OPEN to create/post journals.
- **Periods**: Lock prevents new journal entries. Unlock requires ADMIN. Close workflow: ACCOUNTING requests → ADMIN approves → ADMIN finalizes.
- **Reconciliation**: Sessions are created per bank account. Match/unmatch links bank entries to book entries. Completed sessions lock the reconciliation state.

### 7.2 HR/Payroll Boundaries

- **Employees**: Admin-only CRUD. Accounting can read but not modify.
- **Leave**: Admin-only request approval. Employees do not have direct API access.
- **Attendance**: Admin-only marking. Accounting can view summaries.
- **Payroll runs**: ADMIN or ACCOUNTING can create/calculate/approve. Posting to accounting and marking as paid completes the lifecycle.
- **Module gating**: If HR module is disabled, all `/api/v1/payroll/**` endpoints return `MODULE_DISABLED`.

### 7.3 Reporting Boundaries

- **Read-only**: All report endpoints are GET only. No mutation.
- **Export workflow**: Request export → (optional approval) → generate → download.
- **Source-of-truth variations**:
  - Trial Balance / Balance Sheet: Closed periods use snapshots, open periods use live journals
  - P&L: Always reads live (no snapshot branch)
  - Cash Flow: Heuristic classification, no date filtering, always live

---

## 8. Cross-Module Handoffs

### 8.1 HR → Accounting Handoff

```
Payroll run POSTED → Creates journal entries via AccountingFacade
  └─> DR SALARY-EXP / DR WAGE-EXP (expense)
  └─> CR SALARY-PAYABLE, PF-PAYABLE, ESI-PAYABLE, TDS-PAYABLE, PROFESSIONAL-TAX-PAYABLE (liabilities)
  └─> CR EMP-ADV (if advances)
```

- **Host**: `/api/v1/payroll/runs/{id}/post` (HR) → `/api/v1/accounting/journals` (Accounting)
- **Required accounts**: SALARY-EXP, WAGE-EXP, SALARY-PAYABLE, PF-PAYABLE, ESI-PAYABLE, TDS-PAYABLE, PROFESSIONAL-TAX-PAYABLE, EMP-ADV

### 8.2 Accounting → HR Payment Handoff

```
Bank payment recorded → Mark payroll as PAID
  └─> Payment journal created at /api/v1/accounting/payroll/payments/batch
  └─> HR marks run as PAID via /api/v1/payroll/runs/{id}/mark-paid
```

- **Two-phase**: Posting creates liability, payment recording clears it

### 8.3 Reports → Accounting/Inventory/Factory Handoff

| Report | Source Modules |
| --- | --- |
| Trial Balance | accounting (journal lines, period snapshots) |
| Profit & Loss | accounting (journal lines) |
| Balance Sheet | accounting (journal lines, snapshots) |
| Cash Flow | accounting (journal lines, heuristic classification) |
| GST Return | accounting (sales/purchase journal lines) |
| Aged Debtors | sales (dealer ledger) |
| Aged Receivables | accounting (journal lines) |
| Inventory Valuation | inventory (stock batches) |
| Inventory Reconciliation | inventory + accounting |
| Production Reports | factory (production logs) |

---

## 9. Known Limitations and Caveats

1. **HR module paused by default** — New tenants have HR disabled (ERP-33). Must be enabled by super-admin via tenant configuration.

2. **Indian regulatory context only** — Payroll statutory deductions (PF, ESI, TDS, professional tax) are India-specific.

3. **P&L snapshot gap** — P&L always reads live data; there is no snapshot branch for closed periods. Metadata may incorrectly indicate SNAPSHOT source.

4. **Cash flow limitations** — No date filtering, no snapshot branch, heuristic classification may miscategorize transactions.

5. **Aging split ownership** — Two different services (`AgedDebtorsReportQueryService` vs `AgingReportService`) may return inconsistent data.

6. **Accounting-host payment seam** — Payroll posting creates journal entries in accounting, but payment recording requires separate accounting journal creation. This is the canonical design, not a gap.

---

## 10. Related Documentation

- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/modules/hr.md](modules/hr.md) — HR module canonical packet
- [docs/modules/reports.md](modules/reports.md) — Reports module canonical packet
- [docs/flows/accounting-period-close.md](flows/accounting-period-close.md) — Accounting/period close flow packet
- [docs/flows/hr-payroll.md](flows/hr-payroll.md) — HR/payroll flow packet
- [docs/flows/reporting-export.md](flows/reporting-export.md) — Reporting/export flow packet
- [docs/frontend-handoff-platform.md](frontend-handoff-platform.md) — platform handoff (for comparison)
- [docs/frontend-handoff-operations.md](frontend-handoff-operations.md) — operations handoff (for comparison)
- [docs/frontend-handoff-commercial.md](frontend-handoff-commercial.md) — commercial handoff (for comparison)
- [docs/accounting-portal-frontend-engineer-handoff.md](accounting-portal-frontend-engineer-handoff.md) — accounting portal deep handoff
