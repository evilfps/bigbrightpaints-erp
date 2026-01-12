# AS-BUILT ERP SPEC (as implemented today)

Status: **DRAFT (audit program gate)**

This document describes how the ERP backend works today (code + existing docs), including:
- portals/actors
- core objects + identifiers
- the chain-of-evidence (document → movements → journals → ledgers → reconciliation → close/lock)
- workflow states + control points
- where reports derive their “truth”

Scope anchor: `SCOPE.md` (ERP-grade invariants + in-scope processes).

Primary repo anchors referenced here:
- `erp-domain/docs/MODULE_FLOW_MAP.md`
- `docs/API_PORTAL_MATRIX.md`
- `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
- `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
- `erp-domain/docs/*STATE_MACHINES.md`

Gate: do not add items to `tasks/erp_logic_audit/LOGIC_FLAWS.md` until this spec is materially complete.

---

## 0) Architecture + tenancy model (as-built)

**Service**
- Spring Boot/JPA/Flyway service in `erp-domain/src/main/java/com/bigbrightpaints/erp/**`.

**Multi-company scoping**
- Company context is a ThreadLocal (`erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextHolder.java`).
- Company context is set per request in `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/CompanyContextFilter.java`:
  - primary: `X-Company-Id` header
  - fallback: JWT claim `cid` when header absent
  - membership check: authenticated users must belong to the company code
  - `/actuator/**`, `/swagger/**`, `/v3/**` bypass this filter

**RBAC model (portal to role mapping)**
- See `docs/API_PORTAL_MATRIX.md`.
- Portal-level mapping is enforced via `@PreAuthorize` on controllers and permission checks in the security layer.

---

## 1) Actors / portals (as-built “UI surfaces”)

Source: `erp-domain/docs/MODULE_FLOW_MAP.md`, `docs/API_PORTAL_MATRIX.md`.

### Admin portal
- Auth/session lifecycle + user/role administration + company switching.
- Typical endpoint groups:
  - `/api/v1/auth/**`
  - `/api/v1/admin/**`
  - `/api/v1/roles/**`
  - `/api/v1/companies/**`
  - `/api/v1/multi-company/companies/switch`

### Accounting portal (includes inventory + purchasing + payroll reporting)
- Journals, periods, month-end checklist, statements/aging, reconciliation dashboards, trial balance, GST return, and finance reports.
- Includes operational inventory and purchasing endpoints used by accounting staff.

### Sales portal
- Dealers, sales orders, credit overrides, dispatch initiation/confirmation (sales-side).

### Manufacturing portal (factory / production)
- Production logs and packing records, factory tasks/plans, dispatch confirmation modal.

### Dealer portal (read-only, self-scoped)
- Dealer can only see their own orders/invoices/ledger/aging/dashboard.
- Endpoint group: `/api/v1/dealer-portal/**`.

---

## 2) Core objects and identifiers (what exists + how it’s keyed)

The ERP primarily uses:
- internal DB PKs: `id` (Long identity)
- external-ish IDs: `public_id` (UUID) on many tables
- business references: `reference_number`, `invoice_number`, `order_number`, `slip_number`, `batch_code`, `production_code`, `run_number`

### Company + chart-of-accounts
- `companies`:
  - `companies.id` (Long), `companies.public_id` (UUID), `companies.code` (unique), `companies.timezone`
  - default mappings live on Company (examples): `default_inventory_account_id`, `default_gst_rate`, `base_currency`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/domain/Company.java`
- `accounts`:
  - unique key: `(company_id, code)`
  - stored balance field: `accounts.balance` is maintained by journal posting
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/Account.java`

### Journals / posting artifacts
- `journal_entries`:
  - unique key: `(company_id, reference_number)`
  - key linkage columns: `dealer_id`, `supplier_id`, `accounting_period_id`, `reversal_of_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalEntry.java`
- `journal_lines`:
  - FK: `journal_entry_id`, `account_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/JournalLine.java`
- Subledger rows:
  - `dealer_ledger_entries` (`journal_entry_id`, `invoice_number`, `payment_status`, `amount_paid`)
  - `supplier_ledger_entries` (`journal_entry_id`)
- Allocation / linking:
  - `partner_settlement_allocations` links settlements → invoice/purchase → journal entry, with `idempotency_key`
  - `journal_reference_mappings` maps legacy → canonical references per company

### Inventory (raw materials)
- `raw_materials`:
  - unique key: `(company_id, sku)`
  - stock fields: `current_stock`, plus min/max/reorder levels
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterial.java`
- `raw_material_batches`:
  - PK: `id`, plus `public_id` (UUID)
  - business identifier: `batch_code` (generated when absent; no DB unique constraint in entity mapping)
  - cost: `cost_per_unit`; quantity: `quantity`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialBatch.java`
- `raw_material_movements`:
  - FK: `raw_material_id`, optional `raw_material_batch_id`
  - trace columns: `reference_type`, `reference_id`, `movement_type`, `journal_entry_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialMovement.java`

### Inventory (finished goods)
- `finished_goods`:
  - unique key: `(company_id, product_code)`
  - stock fields: `current_stock`, `reserved_stock`
  - costing method: `costing_method` (default “FIFO”)
  - account mappings: `valuation_account_id`, `cogs_account_id`, `revenue_account_id`, `discount_account_id`, `tax_account_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/FinishedGood.java`
- `finished_good_batches`:
  - unique key: `(finished_good_id, batch_code)`
  - quantities: `quantity_total`, `quantity_available`
  - costs: `unit_cost`
  - bulk-to-size: `parent_batch_id`, `is_bulk`, `size_label`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/FinishedGoodBatch.java`
- `inventory_movements`:
  - FK: `finished_good_id`, optional `finished_good_batch_id`
  - trace columns: `reference_type`, `reference_id`, `movement_type`, `journal_entry_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovement.java`

### Sales / dispatch / invoicing
- `sales_orders`:
  - unique key: `(company_id, order_number)`
  - status (string), idempotency key (`idempotency_key`)
  - dispatch/idempotency markers: `sales_journal_entry_id`, `cogs_journal_entry_id`, `fulfillment_invoice_id`
  - GST fields: `gst_total`, `gst_rate`, `gst_treatment`, `gst_rounding_adjustment`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/SalesOrder.java`
- `packaging_slips`:
  - unique key: `(company_id, slip_number)`
  - journal linkage: `journal_entry_id`, `cogs_journal_entry_id`
  - invoice linkage: `invoice_id`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/PackagingSlip.java`
- `invoices`:
  - unique key: `(company_id, invoice_number)`
  - journal linkage: `journal_entry_id` (FK to JournalEntry)
  - settlement hints: `outstanding_amount`, plus `invoice_payment_refs` element collection
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java`

### Purchasing (AP)
- `raw_material_purchases`:
  - unique key: `(company_id, invoice_number)`
  - journal linkage: `journal_entry_id` (FK to JournalEntry)
  - outstanding field: `outstanding_amount`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/RawMaterialPurchase.java`

### Payments / cash-bank (as represented today)
- There is no separate “bank transaction” table in the objects surfaced above; cash/bank movements are represented as **journal entries posted to cash/bank accounts**.
- Partner settlements allocate payments/discounts/write-offs/FX to invoices or purchases via:
  - `partner_settlement_allocations` (`PartnerSettlementAllocation`)
  - and update the relevant document outstanding fields (invoice/purchase) plus ledger references.
- Key posting entrypoints:
  - dealer receipts: `AccountingService#recordDealerReceipt` and `#recordDealerReceiptSplit`
  - supplier payments: `AccountingService#recordSupplierPayment`
  - dealer/supplier settlements: `AccountingService#settleDealerInvoices` and `#settleSupplierInvoices`

### Production
- `production_logs`:
  - unique key: `(company_id, production_code)`
  - cost source-of-truth fields: `material_cost_total`, `labor_cost_total`, `overhead_cost_total`, `unit_cost`
  - links: `sales_order_id` and `sales_order_number` are stored as plain columns (not a JPA relation)
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionLog.java`
- `packing_records`:
  - FK: `production_log_id`, `finished_good_id`, optional `finished_good_batch_id`
  - packaging consumption fields: `packaging_material_id`, `packaging_quantity`, `packaging_cost`
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackingRecord.java`

### Payroll
- `payroll_runs`:
  - status enum `PayrollStatus` stored on `payroll_runs.status`
  - journal linkage: `journal_entry_id` (Long) and `journal_entry_ref_id` (FK to JournalEntry, eager) for backward compatibility
  - idempotency: `idempotency_key` is marked `unique=true` at the column level
  - entity: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/PayrollRun.java`

---

## 3) Accounting engine (as-built)

### Posting entrypoint
- Canonical posting engine: `AccountingService#createJournalEntry` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`)
- Domain helpers call into it via `AccountingFacade` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java`)

### How a journal is posted today
1) Company context resolved (must exist): `companyContextService.requireCurrentCompany()`.
2) Entry date required; validated against:
   - company date rules (`validateEntryDate(...)`)
   - period lock enforcement:
     - if period lock enforced: `AccountingPeriodService.requireOpenPeriod(...)`
     - else: `AccountingPeriodService.ensurePeriod(...)`
3) Reference number is resolved via resolver/mapping: `resolveJournalReference(...)`.
4) Idempotency:
   - `(company, reference_number)` uniqueness is enforced and treated idempotently.
   - duplicate reference returns existing entry **only if** the payload “matches” (`ensureDuplicateMatchesExisting(...)`).
5) Balancing rules:
   - each line has either debit or credit (not both), no negative values
   - amounts are converted to base currency via `fxRate` when currency != base currency
   - minor FX rounding deltas are absorbed into a single line within tolerance
6) Persistence + ledger updates:
   - `journal_entries` saved with `status="POSTED"`
   - `accounts.balance` updated via atomic delta updates
   - dealer/supplier ledger entry recorded when receivable/payable account lines are present

### Reversal / void semantics
- Journal reversal: `AccountingService#reverseJournalEntry` (supports void-only and partial reversals).
- Reversal chains:
  - `journal_entries.reversal_of_id` links reversal to original
  - status transitions include `VOIDED`, `REVERSED` (as string status on entry)

---

## 4) Periods, month-end checklist, reconciliation, close/lock

### Period object + status
- `AccountingPeriod` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/AccountingPeriod.java`):
  - `status ∈ {OPEN, LOCKED, CLOSED}`
  - month-end checklist flags: `bank_reconciled`, `inventory_counted` (+ timestamps/by fields)

### Month-end checklist
- Built by `AccountingPeriodService#buildChecklist`:
  - bank reconciled (manual flag)
  - inventory counted (manual flag)
  - draft entries cleared: counts `journal_entries.status ∈ {DRAFT,PENDING}` within period date range
  - inventory reconciled to GL: `ReportService.inventoryReconciliation()`
  - AR reconciled to dealer ledger: `ReconciliationService.reconcileArWithDealerLedger()`
  - AP reconciled to supplier ledger: `ReconciliationService.reconcileApWithSupplierLedger()`
  - unbalanced journals cleared: recompute line sums within tolerance
  - documents linked to journals: counts invoices/purchases/payroll runs missing journal links
  - unposted documents cleared: counts draft invoices, purchases not POSTED, payroll runs in DRAFT/CALCULATED/APPROVED

### Close period
- `AccountingPeriodService#closePeriod`:
  - requires checklist complete unless `force=true`
  - computes net income from journal lines summarized by account type
  - posts a closing system journal: reference `PERIOD-CLOSE-YYYYMM` (idempotent)
  - sets status to CLOSED and also sets lock fields

### Reopen period
- `AccountingPeriodService#reopenPeriod`:
  - requires a reopen reason
  - sets status back to OPEN
  - auto-reverses the closing journal if present and clears `closing_journal_entry_id`

---

## 5) Canonical workflows (states + invariants + control points)

State docs:
- `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`
- `erp-domain/docs/PROCURE_TO_PAY_STATE_MACHINES.md`
- `erp-domain/docs/HIRE_TO_PAY_STATE_MACHINES.md`

### 5.1) O2C — Order to Cash (Sales + Dispatch + Invoice + AR)

**Primary services / controllers**
- Order lifecycle: `SalesService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java`)
- Inventory reservation + slips + dispatch: `FinishedGoodsService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`)
- Dispatch endpoints:
  - factory-facing: `DispatchController` (`/api/v1/dispatch/**`)
  - sales-facing: `SalesController` (`/api/v1/sales/dispatch/confirm`)

**States (as stored today)**
- Sales order statuses: see `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`
- Packaging slip statuses: see `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`
- Invoice statuses: see `erp-domain/docs/ORDER_TO_CASH_STATE_MACHINES.md`

**Canonical chain-of-evidence**
1) Sales order created (`sales_orders`) with idempotency key
2) Packaging slip created/reserved (`packaging_slips`, `packaging_slip_lines`) + reservation rows
3) Dispatch confirmation updates:
   - finished goods stock + inventory movements (`inventory_movements` with `reference_type=SALES_ORDER`)
   - COGS journal entry (`journal_entries` via `AccountingFacade.postCogsJournal`)
   - AR/Revenue/Tax journal entry (`journal_entries` via `AccountingFacade.postSalesJournal`)
4) Invoice issued (`invoices.status="ISSUED"`) and linked to AR journal entry
5) Dealer ledger entry recorded from AR line(s) (`dealer_ledger_entries`)
6) Month-end checklist and reconciliation use:
   - AR reconciliation (GL vs dealer ledger)
   - inventory reconciliation (valuation vs inventory control balance)
7) Period close locks down future posting unless reopened

**Control points**
- Credit limit enforcement during order creation and again at dispatch confirmation.
- Dispatch confirmation is designed to be idempotent by using existing slip/journal/invoice markers:
  - Packaging slip stores `journal_entry_id`, `cogs_journal_entry_id`, and `invoice_id`
  - Sales order stores `sales_journal_entry_id`, `cogs_journal_entry_id`, and `fulfillment_invoice_id`

### 5.2) P2P — Procure to Pay (Purchasing + Inventory receipt + AP)

**Primary services**
- Purchase intake: `PurchasingService#createPurchase` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java`)
- Raw material receipt (direct): `RawMaterialService#recordReceipt` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/RawMaterialService.java`)

**Canonical chain-of-evidence**
1) Purchase created (`raw_material_purchases`) with unique `invoice_number`
2) Journal posted (Dr inventory accounts, Cr supplier payable) using `AccountingFacade.postPurchaseJournal`
3) Raw material batches + movements recorded (`raw_material_batches`, `raw_material_movements`)
4) Purchase stores `journal_entry_id`; movements store `journal_entry_id`
5) Supplier ledger entry recorded from AP line(s) (`supplier_ledger_entries`)
6) Supplier settlements/payments create additional journals and allocation rows (`partner_settlement_allocations`)

**Control points**
- Supplier must have a payable account; raw materials must have inventory account mapping.
- Purchase flow posts journal first, then records receipts with `postJournal=false` to avoid duplicate journals.

### 5.3) Production — Produce to Stock (WIP + semi-finished + packing)

**Primary services**
- Production log + material issue: `ProductionLogService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java`)
- Packing + finished goods receipt: `PackingService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java`)

**Canonical chain-of-evidence**
1) Production log created (`production_logs`) with `production_code` and cost fields
2) Raw material issue recorded (`raw_material_movements` with `movement_type="ISSUE"` and production references)
3) Semi-finished receipt:
   - `inventory_movements` receipt into a semi-finished finished-good SKU
   - WIP journal posted (WIP ↔ semi-finished valuation) and linked on movement where applicable
4) Packing:
   - `packing_records` created per packing line
   - finished good batches created and inventory movements recorded (reference = `production_code`)
   - WIP → FG journal posted per packing session (includes packaging cost per unit)
5) Wastage recorded on completion; production log status becomes FULLY_PACKED

### 5.4) Payroll — Hire to Pay

State + invariant source: `erp-domain/docs/HIRE_TO_PAY_STATE_MACHINES.md`.

**Canonical chain-of-evidence**
1) Payroll run created for period (`payroll_runs.status=DRAFT`)
2) Calculate → Approve → Post:
   - posting creates a journal entry linked via `payroll_runs.journal_entry_id` and `payroll_runs.journal_entry_ref_id`
   - attendance rows link to payroll run for traceability
3) Mark paid:
   - updates payroll run/line status; may create payment journals via accounting endpoints depending on flow

### 5.5) Onboarding — company + defaults + master data + opening positions

**Note:** `erp-domain/docs/ONBOARDING_GUIDE.md` is referenced by predeploy tasks but is not present in the repo at time of audit hydration. This spec describes observed behavior from code + OpenAPI.

**Company + defaults**
- Company defaults relevant to postings live on `Company`:
  - GST accounts (`gst_input_tax_account_id`, `gst_output_tax_account_id`, `gst_payable_account_id`)
  - default accounts for automatic postings (`default_inventory_account_id`, `default_cogs_account_id`, etc.)

**Master data**
- Suppliers: `suppliers` with payable account
- Dealers: `dealers` with receivable account
- Raw materials: `raw_materials` (inventory account mapping required for posting)
- Finished goods: `finished_goods` (valuation/cogs/revenue/tax mappings used for dispatch posting)

**Opening stock**
- `POST /api/v1/inventory/opening-stock` imports CSV via `OpeningStockImportService`:
  - creates/updates raw materials and finished goods when missing
  - creates batches and movement rows with `reference_type=OPENING_STOCK`
  - stock levels are adjusted directly on the item master (`current_stock`)

### 5.6) Close / lock — reconciliation → checklist → close → lock → reopen

**Primary service**
- `AccountingPeriodService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java`)

**Canonical chain-of-evidence**
1) Period window defined by `accounting_periods.start_date` and `end_date`
2) Month-end checklist evaluated:
   - drafts cleared, unbalanced journals cleared
   - documents posted + linked to journals
   - inventory/AR/AP reconciliation within tolerance
3) Close period:
   - system closing journal posted (idempotent reference `PERIOD-CLOSE-YYYYMM`)
   - period status becomes CLOSED and is also locked for posting
4) Reopen (controlled):
   - requires reason; closing journal is auto-reversed and period status returns to OPEN

---

## 6) Where “truth” lives for reports (as-built)

### Core finance statements
Service: `ReportService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java`)

- Balance sheet:
  - uses `accounts.balance` aggregated by `AccountType` (assets and liabilities)
  - equity is computed as `assets - liabilities` (not a roll-up of equity accounts)
- Profit & loss:
  - uses `accounts.balance` aggregated by `AccountType` (revenue, cogs, expense)
- Trial balance:
  - uses `accounts.balance` across all accounts and reports total debit vs credit by sign convention

### Inventory valuation and reconciliation
- Inventory valuation:
  - raw materials: values `RawMaterial.currentStock` using FIFO slices from `raw_material_batches` (received_at ascending)
  - finished goods: values `FinishedGood.currentStock` using FIFO slices from `finished_good_batches` (manufactured_at ascending) using `quantity_available`
- Inventory reconciliation:
  - compares computed inventory valuation total to the inventory ledger balance:
    - preferred: `Company.default_inventory_account_id`
    - fallback: sum of accounts whose *name* contains “inventory”

### GST return
Service: `TaxService` (`erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java`)
- GST return is computed from posted journal lines for configured tax accounts.

### Reconciliation dashboard / month-end checklist
- Month-end checklist uses:
  - `ReportService.inventoryReconciliation()`
  - `ReconciliationService.reconcileArWithDealerLedger()`
  - `ReconciliationService.reconcileApWithSupplierLedger()`
