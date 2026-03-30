# Reporting / Export Flow

Last reviewed: 2026-03-30

This packet documents the **reporting and export flow**: the canonical lifecycle for financial reports, operational reports, and the export request/approval workflow. It covers trial balance, P&L, balance sheet, cash flow, GST, aging reports, inventory reports, production reports, and the export approval gate.

This flow is **behavior-first** and **code-grounded**. Where the backend is incomplete, blocked, or intentionally partial, the packet explicitly states the current limitation instead of presenting partial behavior as complete.

---

## 1. Actors

| Actor | Role | Authorization Scope |
| --- | --- | --- |
| **Admin** | Full reporting and export access | `ROLE_ADMIN` |
| **Accounting** | Financial reports, export approval | `ROLE_ACCOUNTING` |
| **Sales** | Sales-related reports | `ROLE_SALES` |
| **Factory** | Production reports | `ROLE_FACTORY` |

---

## 2. Entrypoints

### Financial Reports — `ReportController` (`/api/v1/reports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Trial Balance | GET | `/api/v1/reports/trial-balance` | ADMIN, ACCOUNTING | Trial balance report |
| Profit & Loss | GET | `/api/v1/reports/profit-loss` | ADMIN, ACCOUNTING | Income statement |
| Balance Sheet | GET | `/api/v1/reports/balance-sheet` | ADMIN, ACCOUNTING | Balance sheet |
| Balance Sheet Hierarchy | GET | `/api/v1/reports/balance-sheet/hierarchy` | ADMIN, ACCOUNTING | Hierarchical view |
| Cash Flow | GET | `/api/v1/reports/cash-flow` | ADMIN, ACCOUNTING | Cash flow statement |
| GST Return | GET | `/api/v1/reports/gst-return` | ADMIN, ACCOUNTING | GST return data |

### Operational Reports — `ReportController` (`/api/v1/reports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Aged Debtors | GET | `/api/v1/reports/aged-debtors` | ADMIN, ACCOUNTING | Dealer aging |
| Aged Receivables | GET | `/api/v1/reports/aging/receivables` | ADMIN, ACCOUNTING | AR aging |
| Inventory Valuation | GET | `/api/v1/reports/inventory-valuation` | ADMIN, ACCOUNTING, FACTORY | Current valuation |
| Inventory Valuation (as-of) | GET | `/api/v1/reports/inventory-valuation?date={date}` | ADMIN, ACCOUNTING | Historical valuation |
| Inventory Reconciliation | GET | `/api/v1/reports/inventory-reconciliation` | ADMIN, ACCOUNTING | Valuation vs GL |
| Wastage Report | GET | `/api/v1/reports/wastage` | ADMIN, FACTORY | Production wastage |
| Production Cost | GET | `/api/v1/reports/production-logs/{id}/cost-breakdown` | ADMIN, FACTORY | Cost per log |
| Monthly Production Costs | GET | `/api/v1/reports/monthly-production-costs?year={year}&month={month}` | ADMIN, FACTORY | Monthly aggregation |

### Dashboard Reports — `ReportController` (`/api/v1/reports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Reconciliation Dashboard | GET | `/api/v1/reports/reconciliation-dashboard` | ADMIN, ACCOUNTING | Bank reconciliation view |
| Balance Warnings | GET | `/api/v1/reports/balance-warnings` | ADMIN, ACCOUNTING | Anomalous balances |

### Export Workflow — `ReportController` (`/api/v1/exports/**`)

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Request Export | POST | `/api/v1/exports/request` | ADMIN, ACCOUNTING | Request report export |
| Get Export Status | GET | `/api/v1/exports/{requestId}` | ADMIN, ACCOUNTING | Check status |
| Download Export | GET | `/api/v1/exports/{requestId}/download` | ADMIN, ACCOUNTING | Download file |
| Approve Export | POST | `/api/v1/exports/{requestId}/approve` | ADMIN | Approve export |
| Reject Export | POST | `/api/v1/exports/{requestId}/reject` | ADMIN | Reject export |

### Statement Exports — Various Controllers

| Entrypoint | Method | Path | Actor | Purpose |
| --- | --- | --- | --- | --- |
| Dealer Statement PDF | GET | `/api/v1/accounting/statements/dealers/{dealerId}/pdf` | ADMIN | Download statement |
| Supplier Statement PDF | GET | `/api/v1/accounting/statements/suppliers/{supplierId}/pdf` | ADMIN | Download statement |
| Dealer Aging PDF | GET | `/api/v1/accounting/aging/dealers/{dealerId}/pdf` | ADMIN | Download aging |
| Supplier Aging PDF | GET | `/api/v1/accounting/aging/suppliers/{supplierId}/pdf` | ADMIN | Download aging |

---

## 3. Preconditions

### Financial Report Preconditions

1. **Period exists** — valid period ID or date range
2. **Period open or closed** — closed periods use snapshot, open periods use live
3. **RBAC** — ADMIN or ACCOUNTING role required

### Export Request Preconditions

1. **Report type valid** — supported report type
2. **Parameters valid** — date range, period, etc.
3. **Idempotency optional** — can provide Idempotency-Key header

### Export Download Preconditions

1. **Export request exists** — valid request ID
2. **File ready** — status is READY
3. **Approved if required** — if exportApprovalRequired enabled, must be APPROVED

### Export Approval Preconditions

1. **Request exists** — valid request ID
2. **Status PENDING_APPROVAL** — waiting for approval
3. **ADMIN role** — only ADMIN can approve/reject

---

## 4. Lifecycle

### 4.1 Financial Report Lifecycle

```
[Start] → Validate RBAC → Resolve period/window → 
[If closed: Read snapshot] or [If open: Read live journals] → 
Format output → [End: Report data]
```

**Key behaviors:**
- **Trial Balance**: Closed = snapshot, Open = live journals
- **P&L**: Always reads live journals (no snapshot branch)
- **Balance Sheet**: Closed = snapshot + current earnings, Open = live + current earnings
- **Cash Flow**: Heuristic classification, no date filtering, always live

### 4.2 Operational Report Lifecycle

```
[Start] → Validate RBAC → Resolve parameters → 
Query source data → Format output → [End: Report data]
```

**Key behaviors:**
- **Aged Debtors**: From DealerLedgerRepository (dealer-centric)
- **Aged Receivables**: From JournalLineRepository (account-centric) — different source!
- **Inventory Valuation**: From InventoryValuationService (batch-based)
- **Inventory Reconciliation**: Compares inventory valuation vs GL inventory account

### 4.3 Export Request Lifecycle

```
[Start] → Validate report type → Create request → 
[End: PENDING_APPROVAL]

[PENDING_APPROVAL] → Approve → [APPROVED]
[PENDING_APPROVAL] → Reject → [REJECTED]

[APPROVED] → Generate file → [READY]
[REJECTED] → (if approval disabled, still downloadable)
```

**Key behaviors:**
- **Export approval gate**: If system setting `exportApprovalRequired` enabled, blocks REJECTED downloads
- **If disabled**: Returns download even for REJECTED (with informational message)
- **Admin bypass**: Statement and aging PDFs bypass approval workflow

### 4.4 GST Return Lifecycle

```
[Start] → Validate period → Aggregate sales GST (output) → 
Aggregate purchase GST (input) → Apply returned quantity ratio → 
Calculate net liability → [End: GST data]
```

**Key behaviors:**
- Output tax from sales journal lines
- Input tax from purchase journal lines
- Line-level GST components preferred, falls back to aggregate split

---

## 5. Completion Boundary / Current Definition of Done

The flow is complete when:

1. **Report Generated** — Data returned for the requested report type
2. **Export Requested** — Export request created with status
3. **Export Approved** — Request approved (if approval required)
4. **Export Ready** — File generated and available for download

### Current Limitations

1. **P&L has no snapshot branch** — Data can change even for closed periods

2. **Cash flow has no date filtering** — Returns all-time data, not filtered by period

3. **Cash flow heuristic classification** — Relies on pattern matching, may miscategorize

4. **Aging has split ownership** — Two different endpoints may return inconsistent data

5. **Balance sheet date behavior drift** — After period rows seeded, date param may behave like activity instead of cumulative balance

6. **Reconciliation dashboard tied to sessions** — Only shows current session, no historical

7. **Inventory reconciliation no date filter** — Always returns current state

8. **Export approval configurable** — Can be bypassed when disabled

---

## 6. Canonical vs Non-Canonical Paths

### Canonical Paths

| Path | Owner | Notes |
| --- | --- | --- |
| `GET /api/v1/reports/trial-balance` | `ReportController` | Trial balance with snapshot/live branching |
| `GET /api/v1/reports/balance-sheet` | `ReportController` | Balance sheet |
| `GET /api/v1/reports/gst-return` | `ReportController` | GST return |
| `POST /api/v1/exports/request` | `ReportController` | Export request |
| `GET /api/v1/exports/{id}/download` | `ReportController` | Export download |

### Non-Canonical / Deprecated Paths

| Path | Status | Notes |
| --- | --- | --- |
| `GET /api/v1/reports/cash-flow` | Non-canonical | No date filtering, heuristic classification |
| `GET /api/v1/reports/aged-debtors` vs `/api/v1/reports/aging/receivables` | Split ownership | May be inconsistent |
| `GET /api/v1/reports/account-statement` | Non-canonical | Returns dealer-only rollup, name suggests more |
| Audit digest CSV export | Deprecated | Use standard export workflow |

---

## 7. Cross-Module Dependencies

| Module | Dependency | Direction |
| --- | --- | --- |
| `accounting` | Journal lines, periods, snapshots, dealer/supplier ledgers | Read |
| `inventory` | Stock batches, valuation data | Read |
| `factory` | Production logs, cost data | Read |
| `sales` | Commercial data for operational reports | Read |
| `admin` | Export approval service | Read |

---

## 8. Event/Listener Boundaries

The reporting/export flow consumes data from upstream modules and enforces an approval gate on sensitive exports:

| Event | Listener | Phase | Effect on Reporting |
| --- | --- | --- | --- |
| `JournalEntryPostedEvent` | Report queries | Read-time | Financial reports (trial balance, P&L, balance sheet) read journal entries. For open periods, they read live data; for closed periods, they may read snapshots. This means report accuracy depends on journal posting completion. |
| Export request status change | Export audit | Sync | Export download activity is audit-logged for compliance. Approve/reject actions are tracked. |

**Access-control boundaries:**

| Boundary | Description |
| --- | --- |
| **Export approval gate** | If system setting `exportApprovalRequired` is enabled, exports must be APPROVED before download. ADMIN approves/rejects. If disabled, exports are downloadable even in REJECTED status (with informational message). |
| **RBAC** | Reports require ADMIN or ACCOUNTING roles. |
| **Statement PDFs bypass approval** | Dealer and supplier statement PDFs, aging PDFs bypass the approval workflow entirely—they are directly downloadable by ADMIN. |
| **Split aging ownership** | `GET /api/v1/reports/aged-debtors` (DealerLedgerRepository) vs `GET /api/v1/reports/aging/receivables` (JournalLineRepository) may return inconsistent data—this is a known architectural split. |

**Key boundary note:** Reports are consumers of data created by other flows. For closed periods, reports read snapshot data which is stable. For open periods, reports read live journals which can change. This is a fundamental source-of-truth distinction that affects report interpretation—users should understand whether they're viewing stable closed-period data or dynamic open-period data.

---

## 9. Security Considerations

- **RBAC** — ADMIN and ACCOUNTING roles required
- **Company scoping** — All reports scoped to tenant
- **Export audit logging** — Download activity logged
- **Export approval gate** — Configurable approval requirement (see Event/Listener section)

---

## 10. Related Documentation

- [docs/modules/reports.md](../modules/reports.md) — Reports module canonical packet
- [docs/modules/inventory.md](../modules/inventory.md) — Inventory module
- [docs/modules/factory.md](../modules/factory.md) — Factory module
- [docs/flows/FLOW-INVENTORY.md](FLOW-INVENTORY.md) — Flow inventory
- [docs/developer/accounting-flows/07-reports-truth-sources.md](../developer/accounting-flows/07-reports-truth-sources.md) — Truth source mapping

---

## 11. Open Decisions

| Decision | Status | Notes |
| --- | --- | --- |
| P&L snapshot branch | Not implemented | Always live data |
| Cash flow date filtering | Not implemented | Returns all-time data |
| Cash flow explicit tagging | Not implemented | Heuristic classification only |
| Unified aging source | Not implemented | Split ownership remains |
| Historical reconciliation | Not implemented | Current session only |
