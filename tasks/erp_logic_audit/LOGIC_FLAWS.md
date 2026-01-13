# LOGIC FLAWS (confirmed only)

Status: **ACTIVE**

Policy:
- If suspected but not proven, record as a **LEAD** in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`.
- Only add an LF item once evidence is strong (code pointer + reproducible probe output/log).

---

## LF-001 — Balance Sheet / P&L reports are not sign-normalized; equity calculation inconsistent with stored balance conventions

- Workflow + modules + portal: R2R / reporting (`reports`, `accounting`) — Accounting portal
- ERP expectation:
  - Financial statement outputs should present correct sign conventions (revenue/credit-normal accounts as positive values for reporting) and compute equity using correct formula/inputs.
- As-built behavior:
  - `ReportService.balanceSheet()` sums `Account.balance` by type and sets `equity = assets - liabilities`.
  - `ReportService.profitLoss()` sums `Account.balance` by type and computes `grossProfit = revenue - cogs`, `netIncome = grossProfit - expenses`.
  - However, trial-balance logic and account validation indicate **credit-normal balances are stored as negative numbers** (debit-credit deltas applied directly).
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java`:
    - `balanceSheet()` / `profitLoss()` use `aggregateAccountType(...)` without sign normalization.
    - `toTrialBalanceRow(...)` explicitly normalizes by `AccountType.isDebitNormalBalance()` (negates credit-normal balances).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/AccountType.java` (debit-normal types vs credit-normal types).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java`:
    - account deltas are computed as `debit - credit` and applied to `accounts.balance` via `AccountRepository.updateBalanceAtomic(...)`.
  - Probe(s) to capture runtime evidence:
    - GET `/api/v1/reports/trial-balance` vs `/api/v1/reports/balance-sheet` and `/api/v1/reports/profit-loss`
    - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`
- Severity: **HIGH** (core financial statements misleading)
- Repro steps (dev):
  1) Post any standard sales journal with credited revenue and credited tax, and a purchase journal with credited payable.
  2) Call `/api/v1/reports/trial-balance` and confirm credits appear in credit column (trial balance normalizes).
  3) Call `/api/v1/reports/profit-loss` and `/api/v1/reports/balance-sheet` and observe sign/derived totals inconsistent with trial balance conventions.
- Fix direction (no implementation):
  - Normalize report outputs to report-friendly signs (e.g., convert credit-normal stored balances to positive amounts for display).
  - Compute equity using consistent basis (either sum of equity accounts with sign normalization, or net assets method using normalized liabilities).
  - Add invariant tests asserting that report totals reconcile to trial balance within tolerance.
- Future-proof test suggestion:
  - Add a deterministic “mini-COA + postings” integration test that:
    - posts one revenue, one payable, one inventory journal
    - asserts trial balance balances
    - asserts balance sheet equation holds under the report’s sign conventions
    - asserts profit/loss net income matches journal-derived net income.

---

## LF-002 — Aged Debtors report ignores invoice settlements (uses total amount instead of outstanding)

- Workflow + modules + portal: O2C / AR reporting (`reports`, `invoice`, `accounting`) — Accounting portal
- ERP expectation:
  - Aging should bucket **outstanding** receivables (after settlements/receipts/credit notes), not original invoice totals.
- As-built behavior:
  - `ReportService.agedDebtors()` iterates invoices and buckets `invoice.getTotalAmount()` (not `outstandingAmount`) by due date.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java` (`agedDebtors()`):
    - `BigDecimal outstanding = Optional.ofNullable(invoice.getTotalAmount()).orElse(BigDecimal.ZERO);`
  - Invoice has an `outstanding_amount` field and settlement-related state:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java`
  - Probe(s) to capture runtime evidence:
    - create an invoice, settle it partially, then call `GET /api/v1/accounting/reports/aged-debtors` and observe no change.
- Severity: **HIGH** (AR aging materially wrong)
- Repro steps (dev):
  1) Dispatch-confirm an order to issue an invoice (status ISSUED).
  2) Record a partial dealer settlement/receipt that updates invoice outstanding.
  3) Call `/api/v1/accounting/reports/aged-debtors` and compare to `invoice.outstanding_amount` (and dealer ledger).
- Fix direction (no implementation):
  - Use `Invoice.outstandingAmount` (or ledger-derived outstanding) as the aging basis.
  - Ensure credit notes/returns reduce outstanding consistently.
- Future-proof test suggestion:
  - Integration test: invoice → partial settlement → aged debtors buckets decrease by settlement amount.

---

## LF-003 — Finished-goods inventory movements never carry `journal_entry_id` (traceability break)

- Workflow + modules + portal: O2C dispatch + inventory audit trail (`inventory`, `sales`, `accounting`) — Sales/Factory/Accounting portals
- ERP expectation:
  - Inventory movements derived from a financially-impacting operation should be traceable to the relevant journal entry (or a stable reference mapping) to satisfy audit requirements.
  - Docs explicitly list `inventory_movements.journal_entry_id` as a linkage key.
- As-built behavior:
  - Finished-goods movement creation via `FinishedGoodsService.recordMovement(...)` never sets `InventoryMovement.journalEntryId`.
  - As a result, movements for RESERVE/RELEASE/DISPATCH are saved with `journal_entry_id = NULL`.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`:
    - `recordMovement(...)` constructs `InventoryMovement` and saves without `setJournalEntryId(...)`.
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovement.java` includes `journal_entry_id`.
  - Docs/contract expecting linkage:
    - `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md` (“Inventory movements created from a posting must carry journal_entry_id.”)
    - `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md` (movement → journal linkage expectations)
  - Probe(s):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
- Severity: **HIGH** (audit chain-of-evidence broken; reconciliation investigations become manual/fragile)
- Repro steps (dev):
  1) Create and dispatch-confirm a sales order.
  2) Query `inventory_movements` for the order reference and verify `journal_entry_id` remains null.
  3) Attempt to trace movement → journal purely from DB links; the direct FK is absent.
- Fix direction (no implementation):
  - Decide a canonical linkage policy for movement → journal (COGS journal vs AR journal vs both via a mapping table).
  - Enforce linkage at write time and backfill existing rows where possible.
- Future-proof test suggestion:
  - E2E dispatch test asserts at least one movement row links to the expected COGS journal entry.

---

## LF-004 — Finished-goods inventory valuation uses `quantity_available` slices while valuing `current_stock` (reserved stock misvaluation)

- Workflow + modules + portal: Inventory valuation + month-end reconciliation (`inventory`, `reports`, `sales`) — Accounting portal
- ERP expectation:
  - Valuation should consistently value the stock quantity that the ledger expects (typically on-hand/total), using the same cost-layer basis as dispatch/COGS.
- As-built behavior:
  - Reservation reduces `finished_good_batches.quantity_available` (availability), but `finished_goods.current_stock` remains inclusive of reserved units.
  - `ReportService.valueFromFinishedGood(...)` values `FinishedGood.currentStock` using FIFO slices of `FinishedGoodBatch.quantityAvailable`.
  - This forces “extra quantity” (current_stock minus sum(quantity_available)) to be priced using the *last slice cost*, not true FIFO across reserved layers.
- Evidence:
  - Reservation decrements `quantity_available`:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java` (`allocateItem(...)` sets `batch.setQuantityAvailable(available - allocation)`).
  - Valuation uses `currentStock` as required and `quantityAvailable` as slices:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java` (`valueFromFinishedGood(...)`).
  - Probe(s):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/06_inventory_valuation_fifo.sql`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`
- Severity: **HIGH** (valuation and reconciliation can drift; reserved stock priced incorrectly)
- Repro steps (dev):
  1) Create FG with 2 batches at different costs (FIFO layers).
  2) Reserve a quantity from the early batch (reducing quantity_available).
  3) Call `/api/v1/reports/inventory-valuation` and compare to expected FIFO valuation of on-hand stock including reserved.
- Fix direction (no implementation):
  - Align valuation slice quantity source with the intended “required quantity” basis:
    - either value only available stock, or use total/on-hand batch quantity for valuation.
  - Add reconciliation tests that create multiple cost layers + reservations and assert valuation matches expected method.
- Future-proof test suggestion:
  - Integration test: reserve against multi-layer FG and assert valuation stays consistent with FIFO for total on-hand.

---

## LF-005 — Opening stock import updates inventory without a corresponding GL opening entry (systematic inventory↔GL drift)

- Workflow + modules + portal: Onboarding / opening positions (`inventory`, `accounting`, `reports`) — Admin/Accounting/Factory portals
- ERP expectation:
  - Opening stock should create an auditable chain: opening document → inventory movements/batches → opening journal entry → inventory control tie-out.
- As-built behavior:
  - `OpeningStockImportService` creates/updates item masters, batches, and movement rows for opening stock but does not post any accounting journal entry.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/OpeningStockImportService.java`:
    - no use of `AccountingFacade` / `AccountingService`; it records `InventoryMovement`/`RawMaterialMovement` and adjusts `current_stock`.
  - Month-end checklist/reconciliation expects inventory valuation to tie to inventory control:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingPeriodService.java` (inventory reconciliation in checklist)
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java` (inventory reconciliation)
- Severity: **HIGH** (onboarding path can permanently break reconciliation unless manual corrective journals are posted)
- Repro steps (dev):
  1) Import opening stock via `POST /api/v1/inventory/opening-stock`.
  2) Call `/api/v1/reports/inventory-reconciliation` and observe variance unless a manual opening journal is posted.
- Fix direction (no implementation):
  - Define the opening-balance posting policy (inventory control ↔ opening equity/clearing).
  - Either post the journal as part of import (idempotently) or explicitly block import until opening journal is provided.
- Future-proof test suggestion:
  - Onboarding E2E: import opening stock and assert inventory reconciliation variance is within tolerance after the expected opening posting.

---

## LF-006 — AP reconciliation compares signed GL liabilities to positive supplier ledger totals (sign mismatch)

- Workflow + modules + portal: P2P / reconciliation / close (`accounting`, `reports`) — Accounting portal
- ERP expectation:
  - AP control accounts should reconcile to supplier subledger within tolerance using consistent sign conventions.
- As-built behavior:
  - `ReconciliationService.reconcileApWithSupplierLedger()` computes:
    - GL AP = sum of `accounts.balance` for `AccountType.LIABILITY` with code containing AP/PAYABLE
    - Supplier ledger total = sum of `supplier_ledger_entries.credit - debit` (positive for payable balance)
    - variance = GL - ledger (no sign normalization)
  - Trial balance logic indicates credit-normal accounts are stored as negative balances, so GL liabilities are typically negative.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java` (`reconcileApWithSupplierLedger`)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java` (`toTrialBalanceRow` normalizes credit-normal balances via negation)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/Account.java` warns on unusual debit balances for liabilities (implying credit-normal negative storage)
  - Probe(s):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql`
- Severity: **HIGH** (reconciliation dashboard + month-end checklist can be misleading/blocked)
- Repro steps (dev):
  1) Post a purchase journal (credit AP control, supplier context set).
  2) Call `GET /api/v1/reports/reconciliation-dashboard` (and month-end checklist) and inspect AP tie-out.
  3) Compare supplier ledger aggregate vs AP control account balances and note sign mismatch.
- Fix direction (no implementation):
  - Normalize one side to match the other (either invert GL liability balances for comparison, or store liabilities with consistent sign convention and update reporting).
  - Add reconciliation tests that assert AP tie-out passes for a golden P2P scenario.
- Future-proof test suggestion:
  - Golden P2P E2E: purchase → payment → AP reconciliation within tolerance.

---

## LF-007 — Payroll run idempotency key is globally unique (cross-company collision risk)

- Workflow + modules + portal: Payroll + multi-company (`hr`, `company`) — Accounting/Admin portals
- ERP expectation:
  - Idempotency keys should be scoped to a company (or otherwise designed to avoid cross-company collisions).
- As-built behavior:
  - `PayrollRun.idempotency_key` is declared `unique=true` (global uniqueness across all payroll runs).
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/PayrollRun.java` (`@Column(name = "idempotency_key", unique = true)`)
- Severity: **MED** (multi-company collision/DoS vector; can block legitimate runs across companies)
- Repro steps (dev):
  1) Create payroll run in Company A with idempotency key `X`.
  2) Attempt to create payroll run in Company B with idempotency key `X`.
  3) Observe DB uniqueness failure (or conflict), despite different company scope.
- Fix direction (no implementation):
  - Change uniqueness to `(company_id, idempotency_key)` semantics (schema + repository usage).
  - Add multi-company integration test for payroll idempotency isolation.
- Future-proof test suggestion:
  - Two-company test: same idempotency key allowed across companies; disallowed within same company.

---

## LF-008 — Orchestrator trace endpoint is not company-scoped (cross-company trace leak)

- Workflow + modules + portal: Orchestrator traces (`orchestrator`) — Admin/Sales/Factory (any authenticated user)
- ERP expectation:
  - Trace/audit data must be scoped to the company and restricted to intended roles.
- As-built behavior:
  - `GET /api/v1/orchestrator/traces/{traceId}` has no `@PreAuthorize`; global security requires only authentication.
  - `TraceService.getTrace(...)` queries by `traceId` only, with no company filter.
  - `orchestrator_audit` has no `company_id` column, so scoping cannot be enforced at the data layer.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java` (`trace(...)`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/service/TraceService.java` (`getTrace(...)`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/repository/AuditRecord.java` (no company field).
  - Probe(s):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-07/SQL/02_orchestrator_audit_schema.sql`
- Severity: **MED** (cross-company information disclosure via trace IDs)
- Repro steps (dev):
  1) In Company A, trigger any orchestrator flow (e.g., POST `/api/v1/orchestrator/dispatch`) and capture `traceId`.
  2) Authenticate as a user in Company B.
  3) Call `GET /api/v1/orchestrator/traces/{traceId}` and observe trace data returned.
- Fix direction (no implementation):
  - Add `company_id` to `orchestrator_audit` and persist it on trace records.
  - Require company scoping on trace queries (use `CompanyContextService` or header validation).
  - Restrict trace endpoint to admin/ops roles if appropriate.
- Future-proof test suggestion:
  - Multi-company test: trace created in Company A is not readable by Company B (403/404).

---

## LF-009 — Settlement idempotency key uniqueness blocks multi-allocation settlements

- Workflow + modules + portal: Dealer/Supplier settlements (`accounting`) — Accounting portal
- ERP expectation:
  - A settlement can allocate across multiple invoices/purchases under a single idempotency key, and retries must return the same allocation set.
- As-built behavior:
  - `AccountingService.settleDealerInvoices(...)` and `settleSupplierInvoices(...)` assign the same `idempotency_key` to every `PartnerSettlementAllocation` row in a settlement.
  - Migration `V48__settlement_idempotency_keys.sql` creates a **unique** index on `(company_id, idempotency_key)` for `partner_settlement_allocations`.
  - This makes multi-allocation settlements violate the unique index (or forces unique keys per row, breaking idempotent replay grouping).
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingService.java` (`settleDealerInvoices`, `settleSupplierInvoices`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/domain/PartnerSettlementAllocationRepository.java` (`findByCompanyAndIdempotencyKey` expects multiple rows).
  - `erp-domain/src/main/resources/db/migration/V48__settlement_idempotency_keys.sql` (unique index).
  - Probe(s):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/SQL/03_partner_settlement_idempotency_index.sql`
- Severity: **MED** (settlements can fail or require non-idempotent workarounds)
- Repro steps (dev):
  1) Create a dealer or supplier settlement request with **multiple allocations** and a single `idempotencyKey`.
  2) Observe unique index violation on `partner_settlement_allocations` (or forced one-row behavior).
- Fix direction (no implementation):
  - Introduce a settlement header table keyed by `idempotency_key`, with allocation rows linked to it.
  - Or widen the uniqueness scope to `(company_id, idempotency_key, invoice_id/purchase_id)` and enforce payload-match checks on replay.
- Future-proof test suggestion:
  - Integration test: multi-invoice settlement with a shared idempotency key, replayed safely without duplicates.

---

## LF-010 — Purchase return retries without reference create duplicate journals and inventory movements

- Workflow + modules + portal: P2P returns (`purchasing`, `accounting`, `inventory`) — Accounting portal
- ERP expectation:
  - Purchase returns must be idempotent; retried requests should not create duplicate AP and inventory reversals.
- As-built behavior:
  - `PurchasingService.recordPurchaseReturn` treats `referenceNumber` as optional and generates a new reference when omitted.
  - `AccountingFacade.postPurchaseReturn` de-duplicates only by reference number, so each retry without a reference posts a new journal.
  - Result: duplicate journals and raw material movements for identical retry payloads.
- Evidence:
  - Code:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java:215` (reference generated when missing).
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:381` (reference-only idempotency check).
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/dto/PurchaseReturnRequest.java` (`referenceNumber` optional).
  - Runtime repro (BBP):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_return_resp_1.json`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_return_resp_2.json`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_journals_for_returns.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_movements_for_returns.txt`
- Severity: **MED** (retry duplicates can double-reverse inventory and AP)
- Repro steps (dev):
  1) POST `/api/v1/purchasing/raw-material-purchases/returns` twice with the same payload and no `referenceNumber`.
  2) Observe two distinct references in the responses.
  3) Query `journal_entries` and `raw_material_movements` for those references and confirm duplicates.
- Fix direction (no implementation):
  - Require a client-supplied idempotency key or `referenceNumber` on purchase returns.
  - Enforce uniqueness at the data layer (e.g., `(company_id, reference_number)`), or derive a deterministic reference from the request payload + company.
  - Store and replay idempotency keys at the service boundary to return existing journals on retries.
- Future-proof test suggestion:
  - Integration test: submit a purchase return twice without a reference and assert only one journal and movement set is created.

---

## LF-011 — Configuration health reports OK while GST return fails when GST accounts are unset

- Workflow + modules + portal: Tax reporting/config (`accounting`, `reports`) — Accounting portal
- ERP expectation:
  - Configuration health should flag missing GST input/output accounts and GST return should surface a clear, actionable configuration error.
- As-built behavior:
  - `ConfigurationHealthService` validates production/raw material setup but does **not** validate `gst_input_tax_account_id` or `gst_output_tax_account_id`.
  - `TaxService.generateGstReturn` calls `CompanyAccountingSettingsService.requireTaxAccounts`, which throws when GST accounts are null, leading to a generic invalid-state response.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/ConfigurationHealthService.java:53`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyAccountingSettingsService.java:52`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java:36`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260113T073400Z_tax_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260113T073455Z_03_company_tax_accounts.txt`
- Severity: **MED** (tax reporting blocked + misleading health)
- Repro steps (dev):
  1) Ensure GST account IDs are null for the company.
  2) GET `/api/v1/accounting/configuration/health` (returns healthy).
  3) GET `/api/v1/accounting/gst/return` (fails with invalid state).
- Fix direction (no implementation):
  - Add GST account validation to configuration health.
  - Return a specific validation error when GST accounts are missing (avoid generic state error).
- Future-proof test suggestion:
  - Integration test: missing GST accounts should make config health unhealthy and GST return should return a clear configuration error.

---

## LF-012 — WIP is over-credited when labor/overhead are supplied on production logs

- Workflow + modules + portal: Production/WIP (`factory`, `inventory`, `accounting`) — Factory/Accounting portals
- ERP expectation:
  - WIP postings should remain balanced: labor/overhead should either be debited to WIP with an offsetting credit (labor/overhead expense) or excluded from WIP credit until cost allocation.
- As-built behavior:
  - `ProductionLogService.createLog` adds labor + overhead into `totalCost`, then `registerSemiFinishedBatch` posts a `-SEMIFG` journal that **credits WIP for the full total cost**.
  - `postMaterialJournal` only debits WIP for **material** cost (`-RM` journal). No offsetting journal debits WIP for labor/overhead at log creation.
  - Net effect: WIP is credited by labor/overhead amounts (negative WIP delta).
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:129`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:186`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java:449`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072603Z_02_production_wip_debit_credit_delta.txt` (WIP debit 25 vs credit 28; delta -3)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072933Z_11_production_journal_lines.txt` (only `-RM` and `-SEMIFG` entries)
- Severity: **MED** (WIP balance misstatement when labor/overhead included)
- Repro steps (dev):
  1) Create a production log with non-zero labor/overhead costs.
  2) Run `02_production_wip_debit_credit_delta.sql` and `11_production_journal_lines.sql`.
  3) Observe WIP credit exceeds WIP debit by labor+overhead amount.
- Fix direction (no implementation):
  - Option A: Post labor/overhead allocation at log creation (Dr WIP, Cr labor/overhead expense).
  - Option B: Exclude labor/overhead from `-SEMIFG` journal until cost allocation runs.
  - Keep unit cost and postings aligned to the chosen policy.
- Future-proof test suggestion:
  - Integration test: production log with labor/overhead yields balanced WIP debits/credits.

---

## LF-013 — Production logs remain READY_TO_PACK after full packing (status not updated)

- Workflow + modules + portal: Production packing (`factory`) — Factory/Accounting portals
- ERP expectation:
  - When `total_packed_quantity >= mixed_quantity`, status must be `FULLY_PACKED`, and the log should drop out of `unpacked-batches`.
- As-built behavior:
  - `PackingService.recordPacking` uses `incrementPackedQuantityAtomic` but immediately re-reads the log without a refresh, so `updateStatus` can evaluate a stale `totalPackedQuantity`.
  - Result: status remains `READY_TO_PACK` even when packed quantity equals mixed quantity.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java:171`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/ProductionLogRepository.java:22`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072933Z_10_production_log_status.txt` (mixed=5, packed=5, status=READY_TO_PACK)
- Severity: **MED** (workflow state inconsistent; unpacked list + downstream workflows can be wrong)
- Repro steps (dev):
  1) Record packing equal to `mixed_quantity`.
  2) Query `production_logs` or `GET /api/v1/factory/unpacked-batches`.
  3) Observe status still `READY_TO_PACK`.
- Fix direction (no implementation):
  - Refresh or reload the entity after the atomic update, or update status in the same update query.
  - Ensure `recordPacking` returns the updated status and packed quantity.
- Future-proof test suggestion:
  - Integration test: full packing sets status `FULLY_PACKED` and removes the log from unpacked list.

---

## LF-014 — Finished-good catalog creation throws 500 when default discount account is unset

- Workflow + modules + portal: Production catalog setup (`production`, `accounting`) — Admin/Factory portals
- ERP expectation:
  - Missing optional discount defaults should not crash catalog creation; system should allow creation or return a clear validation error.
- As-built behavior:
  - `ProductionCatalogService.ensureFinishedGoodAccounts` uses `Map.of` with `defaults.discountAccountId()`. If the discount default is null, `Map.of` throws `NullPointerException`, returning a 500 error instead of a validation response.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/service/ProductionCatalogService.java:619`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T071615Z_create_production_product_response.json` (500 response)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T071622Z_app_logs_tail_after_product_create.txt` (NPE in `ensureFinishedGoodAccounts`)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072133Z_default_accounts_before.json` (discountAccountId null)
- Severity: **MED** (blocks finished-good setup in default configs)
- Repro steps (dev):
  1) Ensure company default discount account is null.
  2) POST `/api/v1/accounting/catalog/products` for a finished good.
  3) Observe 500 response and NPE in logs.
- Fix direction (no implementation):
  - Build the defaults map without null values, or guard `discountAccountId` before `Map.of`.
  - Return a clear validation error if discount is required in the flow.
- Future-proof test suggestion:
  - Integration test: finished-good creation with null default discount returns a 4xx validation error (not 500).
