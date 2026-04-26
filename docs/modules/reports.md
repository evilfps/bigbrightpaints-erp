# Reports Module

Last reviewed: 2026-04-26

## Overview

The `reports` module provides financial and operational reporting surfaces for the orchestrator-erp backend. It serves as the canonical query layer over accounting truth, reading from posted journal lines, closed-period snapshots, dealer/supplier subledgers, and inventory data to produce various report outputs.

**RBAC:** All report endpoints require `ROLE_ADMIN` or `ROLE_ACCOUNTING`.

## Module Ownership

- **Controllers:** `ReportController` — canonical `/api/v1/reports/**` endpoints
- **Services:** 
  - `ReportService` — primary orchestration layer
  - `ReportQuerySupport` — window resolution (LIVE/AS_OF/SNAPSHOT)
  - `BalanceSheetReportQueryService` — balance sheet query logic
  - `ProfitLossReportQueryService` — P&L query logic  
  - `TrialBalanceReportQueryService` — trial balance query logic
  - `AgedDebtorsReportQueryService` — aged debtors endpoint
  - `InventoryValuationService` — inventory valuation reports
- **DTOs:** Report payloads plus provenance metadata (`ReportMetadata`, `ReportSource`)

## Report Types and Source-of-Truth Behavior

### Trial Balance

- **Endpoint:** `GET /api/v1/reports/trial-balance`
- **Parameters:** `date`, `periodId`, `startDate`, `endDate`, `comparativeStartDate`, `comparativeEndDate`, `comparativePeriodId`, `exportFormat`

**Source-of-truth behavior:**
- For **closed periods**: Reads from stored `AccountingPeriodTrialBalanceLine` snapshot records
- For **open periods**: Reads from live journal summaries via `JournalLineRepository.summarizeByAccountWithin()`
- The snapshot provides an immutable, point-in-time view of closed periods
- If a period is closed and has no snapshot, the report may fail or return incomplete data

**Current limitations:**
- No comparative period functionality beyond optional second period parameters
- Snapshot branching is binary (closed = snapshot, open = live) — no hybrid views

---

### Profit and Loss (Income Statement)

- **Endpoint:** `GET /api/v1/reports/profit-loss`
- **Parameters:** `date`, `periodId`, `startDate`, `endDate`, `comparativeStartDate`, `comparativeEndDate`, `comparativePeriodId`, `exportFormat`

**Source-of-truth behavior:**
- **Always reads live journal summaries** — there is no snapshot branch for P&L today
- Aggregates revenue and expense accounts within the requested date/period window
- Period/range requests return `ReportMetadata.source=LIVE`; explicit as-of
  requests return `AS_OF`.

**Current limitations:**
- No closed-period snapshot path — P&L data can change even for "closed" periods if journals are posted late

---

### Balance Sheet

- **Endpoint:** `GET /api/v1/reports/balance-sheet`
- **Parameters:** `date`, `periodId`, `startDate`, `endDate`, `comparativeStartDate`, `comparativeEndDate`, `comparativePeriodId`, `exportFormat`

**Source-of-truth behavior:**
- For **closed periods**: Reads from stored snapshot (similar to trial balance)
- For **open periods**: Uses live journal summaries plus current earnings logic (accumulated profit/loss calculation)
- The "current earnings" component bridges the income statement to the balance sheet equity section

**Current limitations:**
- Shares the same snapshot-vs-live branching as trial balance
- Date-parameter behavior may drift toward "period activity" instead of cumulative "as-of balance" after period rows are seeded — see Review Hotspots

**Hierarchical variant:** `GET /api/v1/reports/balance-sheet/hierarchy` returns account hierarchy-based grouping

---

### Cash Flow

- **Endpoint:** `GET /api/v1/reports/cash-flow`

**Source-of-truth behavior:**
- Scans **posted journals directly** (not pre-aggregated summaries)
- Uses **heuristic counterparty classification** — determines cash flow categories by analyzing account and partner patterns

**Current limitations:**
- **No date-window discipline** — does not support the same date/period parameters as other financial reports
- **No snapshot branch** — always reads live data
- **Heuristic classification** may miscategorize transactions — no explicit cash flow tagging exists in journal lines
- This is the most non-canonical path in the reporting module

---

### GST Return

- **Endpoint:** `GET /api/v1/reports/gst-return`
- **Parameters:** `periodId`

**Source-of-truth behavior:**
- Reads from sales and purchase journal lines
- Builds output tax from sales lines and input tax from purchase lines
- Uses line-level `cgst_amount`, `sgst_amount`, `igst_amount` when present
- Falls back to splitting aggregate tax amount if line-level components are missing
- Reduces input tax credit by `returnedQuantity` using a retained-quantity ratio

**GST component structure:**
- Output tax: CGST, SGST, IGST per rate bucket
- Input tax (input credit): CGST, SGST, IGST per rate bucket
- Net liability = output tax - input tax credit

**Current limitations:**
- Only available for GST-configured companies (non-GST companies will fail)
- Depends on line-level GST component tracking — legacy entries without component breakdown use estimation
- No snapshot branch — always computes from live journal data

---

### Aging Reports

Aging reports split across two ownership paths:

1. **Aged Debtors** (dealer-centric):
   - **Endpoint:** `GET /api/v1/reports/aged-debtors`
   - **Service:** `AgedDebtorsReportQueryService`
   - **Source:** `DealerLedgerRepository`

2. **Aged Receivables** (account-centric):
   - **Endpoint:** `GET /api/v1/reports/aging/receivables`
   - **Service:** `AgingReportService.getAgedReceivablesReport()`
   - **Source:** Journal lines aggregated by age bucket

**Parameters:** `periodId`, `startDate`, `endDate`, `asOfDate`, `exportFormat`

**Current limitations:**
- Two ownership paths create semantic duplication — data may differ slightly between endpoints
- Aging buckets are hardcoded (typically 0-30, 31-60, 61-90, 91+ days)
- No configurable aging criteria

---

### Dashboards

#### Reconciliation Dashboard

- **Endpoint:** `GET /api/v1/reports/reconciliation-dashboard`
- **Parameters:** `bankAccountId`, `statementBalance`
- **Source:** `ReconciliationServiceCore` — reads bank reconciliation session data

Provides a real-time view of:
- Outstanding checks (issued but not cleared)
- Outstanding deposits (received but not cleared)
- Bank charges and interest
- Reconciliation discrepancies

**Current limitations:**
- Requires an active bank reconciliation session to return meaningful data
- No historical dashboard — only shows current session state

#### Balance Warnings

- **Endpoint:** `GET /api/v1/reports/balance-warnings`
- Scans for anomalous account balances (e.g., negative asset accounts, positive liability accounts)

---

### Inventory Valuation

- **Endpoints:** 
  - `GET /api/v1/reports/inventory-valuation` — current valuation
  - `GET /api/v1/reports/inventory-valuation?date={date}` — as-of valuation
- **Service:** `InventoryValuationService`
- **Source:** Inventory batch data weighted by valuation method (FIFO, weighted average)

---

### Inventory Reconciliation

- **Endpoint:** `GET /api/v1/reports/inventory-reconciliation`
- **Service:** `ReportService.inventoryReconciliation()`
- **Source:** Compares inventory valuation (from inventory batches) against the general ledger inventory account balance

**Source-of-truth behavior:**
- **Inventory value side**: Reads from `InventoryValuationService.currentSnapshot()` — aggregates all inventory batches weighted by the company's configured valuation method (FIFO, weighted average)
- **Ledger side**: Reads from `resolveInventoryLedgerBalance()` — resolves the general ledger inventory account balance via journal line aggregation
- **Variance calculation**: `inventory total value - ledger balance = variance`

**Current limitations:**
- No date filtering — always returns current-state reconciliation
- No drill-down into which specific batches or journal entries drive the variance
- Only compares the single inventory GL account — does not account for work-in-progress or raw materials sub-accounts separately

---

### Production Reports

- **Endpoints:**
  - `GET /api/v1/reports/wastage` — wastage report
  - `GET /api/v1/reports/production-logs/{id}/cost-breakdown` — cost breakdown per production log
  - `GET /api/v1/reports/monthly-production-costs?year={year}&month={month}` — monthly cost aggregation

**Source:** Factory production logs, raw material consumption, packaging material usage

---

### Account Statement

- **Endpoint:** `GET /api/v1/reports/account-statement`
- **Parameters:** required `accountId`, optional `from`, `to`
- **Current behavior:** Account-level activity statement backed by journal
  lines for the requested account and date range

**Response shape:** `AccountStatementReportDto` includes `accountId`,
`accountCode`, `accountName`, `from`, `to`, `openingBalance`,
chronological `entries[]` with debit/credit/running balance fields, and
`closingBalance`.

---

## Export and Approval Workflow

The reporting module includes an optional export-approval gate:

- **Request export:** `POST /api/v1/exports/request`
- **Download export:** `GET /api/v1/exports/{requestId}/download`
- **Approval inbox:** `GET /api/v1/admin/approvals`
- **Approval decision:** `POST /api/v1/admin/approvals/{originType}/{id}/decisions`

**Approval behavior:**
- If `exportApprovalRequired` system setting is enabled: blocks unapproved downloads
- If disabled: the backend policy may allow file download without an approved
  decision; the download response is still file bytes, not a status DTO
- Tenant-admin approval/rejection uses the generic approval decision route with
  `originType=EXPORT_REQUEST`; there are no direct export approve/reject routes

**Separate admin exports** bypass this workflow:
- Supplier statement PDFs
- Supplier aging PDFs
- Dealer invoice PDFs from dealer self-service

These are still audit-logged but don't consult `ExportApprovalService`.

---

## Cross-Module Dependencies

| Dependency | Purpose |
| --- | --- |
| `accounting` module | Journal lines, periods, snapshots, dealer/supplier ledgers |
| `inventory` module | Stock batches, valuation data |
| `factory` module | Production logs, cost data |
| `sales` module | Commercial data (used by some operational reports) |
| `admin` module | Export approval service |

---

## Review Hotspots

The following areas have known correctness or consistency concerns:

1. **Profit & Loss snapshot absence** — P&L intentionally reads live/as-of journal summaries and does not provide a closed-period snapshot branch
2. **Cash flow heuristic classification** — relies on pattern matching rather than explicit cash flow tagging
3. **Balance sheet / trial balance date behavior** — after period rows are seeded, `?date=` calls risk behaving like period activity instead of cumulative as-of balances
4. **Aging split ownership** — two different services (`AgedDebtorsReportQueryService` vs `AgingReportService`) may return inconsistent data
5. **Cash flow route shape** — current route has no request filters and returns a live statement

---

## Deprecated and Non-Canonical Surfaces

### Deprecated: Audit Digest CSV Export

The audit digest CSV export functionality is **deprecated** and is not part of
the current frontend export contract:

- **Replacement**: Use the standard export workflow (`POST /api/v1/exports/request` → `GET /api/v1/exports/{requestId}/download`) with appropriate report types
- **No replacement**: The audit digest CSV specifically is not being actively maintained; use alternative audit trails for compliance needs

### Non-Canonical: Cash Flow Report

The cash flow report (`GET /api/v1/reports/cash-flow`) is the **most non-canonical path** in the reporting module because:

- **No date filtering**: Does not support the same date/period parameters as other financial reports
- **No snapshot branch**: Always reads live data, never from closed-period snapshots
- **Heuristic classification**: Relies on pattern matching to categorize transactions rather than explicit cash flow tagging in journal lines

**Recommendation**: Be aware that cash flow data may include transactions outside the intended period and may miscategorize transactions. For accurate cash flow reporting, explicit cash flow tagging in journal lines would be needed.

### Non-Canonical: Aging Report Split

Aging reports split across **two different ownership paths**:

| Endpoint | Service | Owner |
| --- | --- | --- |
| `GET /api/v1/reports/aged-debtors` | `AgedDebtorsReportQueryService` | Dealer-centric |
| `GET /api/v1/reports/aging/receivables` | `AgingReportService` | Account-centric |

**Issue**: These may return inconsistent data due to different underlying queries
- **Canonical replacement**: Choose one path and migrate; currently both are maintained
- **No immediate replacement**: Either endpoint may be used, but be aware of potential inconsistencies

---

## Related Documentation

- [docs/developer/accounting-flows/07-reports-truth-sources.md](../developer/accounting-flows/07-reports-truth-sources.md) — detailed truth-source mapping
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — flow inventory (period close workflow reference)
- [docs/flows/reporting-export.md](../flows/reporting-export.md) — canonical reporting/export flow (behavioral entrypoint)
- [docs/flows/accounting-period-close.md](../flows/accounting-period-close.md) — accounting period close flow
- [erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) — accounting module (source of journal truth)
- [docs/modules/inventory.md](./inventory.md) — inventory module (stock data source)
- [docs/modules/factory.md](./factory.md) — factory module (production data source)
- [docs/adrs/INDEX.md](../adrs/INDEX.md) — ADR index for architectural decisions

---

## Known Gaps and Incomplete Areas

- P&L lacks snapshot branch — data can change for closed periods
- Cash flow has no date filtering — returns all-time data
- Reconciliation dashboard tied to active sessions only — no historical view
