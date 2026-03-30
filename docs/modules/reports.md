# Reports Module

Last reviewed: 2026-03-30

## Overview

The `reports` module provides financial and operational reporting surfaces for the BigBright ERP. It serves as the canonical query layer over accounting truth, reading from posted journal lines, closed-period snapshots, dealer/supplier subledgers, and inventory data to produce various report outputs.

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

**Current limitations:**
- No closed-period snapshot path — P&L data can change even for "closed" periods if journals are posted late
- The `ReportMetadata` may incorrectly indicate `SNAPSHOT` source while data actually comes from live journals
- This is a known correctness gap: users may assume they're seeing frozen period data when it's actually live

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

### Production Reports

- **Endpoints:**
  - `GET /api/v1/reports/wastage` — wastage report
  - `GET /api/v1/reports/production-logs/{id}/cost-breakdown` — cost breakdown per production log
  - `GET /api/v1/reports/monthly-production-costs?year={year}&month={month}` — monthly cost aggregation

**Source:** Factory production logs, raw material consumption, packaging material usage

---

### Account Statement

- **Endpoint:** `GET /api/v1/reports/account-statement`
- **Current behavior:** Dealer-only balance rollup (not the fuller `StatementService` engine)

**Current limitations:**
- Only covers dealer accounts — no supplier statement path
- Naming suggests fuller statement capability than what's actually returned

---

## Export and Approval Workflow

The reporting module includes an optional export-approval gate:

- **Request export:** `POST /api/v1/exports/request`
- **Download export:** `GET /api/v1/exports/{requestId}/download`

**Approval behavior:**
- If `exportApprovalRequired` system setting is enabled: blocks unapproved downloads
- If disabled: returns download even for REJECTED requests (with informational message)
- Admin-only approval/rejection workflow via `ExportApprovalService`

**Separate admin exports** bypass this workflow:
- Dealer/supplier statement PDFs
- Dealer/supplier aging PDFs
- Deprecated audit digest CSV

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

1. **Profit & Loss metadata** — `ReportMetadata` may claim `SNAPSHOT` source while data comes from live journals
2. **Cash flow heuristic classification** — relies on pattern matching rather than explicit cash flow tagging
3. **Balance sheet / trial balance date behavior** — after period rows are seeded, `?date=` calls risk behaving like period activity instead of cumulative as-of balances
4. **Aging split ownership** — two different services (`AgedDebtorsReportQueryService` vs `AgingReportService`) may return inconsistent data
5. **Account statement naming drift** — returns dealer-only rollup but name suggests fuller statement capability

---

## Related Documentation

- [docs/developer/accounting-flows/07-reports-truth-sources.md](../developer/accounting-flows/07-reports-truth-sources.md) — detailed truth-source mapping
- [docs/workflows/accounting-and-period-close.md](../workflows/accounting-and-period-close.md) — period close workflow (produces snapshots)
- [erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) — accounting module (source of journal truth)
- [docs/modules/inventory.md](./inventory.md) — inventory module (stock data source)
- [docs/modules/factory.md](./factory.md) — factory module (production data source)
- [docs/adrs/INDEX.md](../adrs/INDEX.md) — ADR index for architectural decisions

---

## Known Gaps and Incomplete Areas

- No dedicated flow packet for Reporting/Export (tracked in flow inventory as packet pending)
- P&L lacks snapshot branch — data can change for closed periods
- Cash flow has no date filtering — returns all-time data
- No hierarchical P&L variant (only hierarchical balance sheet exists)
- Reconciliation dashboard tied to active sessions only — no historical view
