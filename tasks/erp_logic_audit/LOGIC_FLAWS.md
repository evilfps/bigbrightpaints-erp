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
- Fix implemented (Phase 5):
  - `ReportService.balanceSheet` and `ReportService.profitLoss` now normalize balances by account type (credit-normal inverted) and derive equity from normalized assets minus liabilities.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/AccountingReportSignConventionRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-001/OUTPUTS/20260114T113219Z_accounting_reports_gets.txt`
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
- Fix implemented (Phase 5):
  - `ReportService.agedDebtors` now uses `Invoice.outstandingAmount` (falling back to total when null) for bucket values.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/AgedDebtorsOutstandingRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113128Z_seed_invoice_for_aging.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113227Z_aged_debtors_get.json`
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
- Fix implemented (Phase 5):
  - `ReconciliationService.reconcileApWithSupplierLedger` now normalizes GL liabilities by account type (credit-normal inverted) before comparing to supplier ledger totals.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/AccountingReportSignConventionRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-006/OUTPUTS/20260114T113234Z_sql_05_ar_ap_tieouts_after_fix.txt`
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
- Fix implemented (Phase 5):
  - Dropped global payroll idempotency uniqueness and rely on `(company_id, idempotency_key)` uniqueness.
  - `PayrollRun.idempotency_key` is no longer marked unique in the entity.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/PayrollIdempotencyCompanyScopeRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007/OUTPUTS/20260116T092613Z_payroll_idempotency_cross_company.txt`
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
- Fix implemented (Phase 5):
  - `orchestrator_audit` now stores `company_id` and traces are queried by company.
  - `TraceService.record` requires company code and `OrchestratorController` enforces role + company header on trace reads.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/OrchestratorTraceCompanyScopeRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/20260116T092619Z_orchestrator_audit_schema.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/20260116T092706Z_orchestrator_trace_company_scope.txt`
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
- Fix implemented (Phase 5):
  - Dropped the single-column unique index and replaced it with:
    - non-unique lookup index on `(company_id, idempotency_key)`
    - partial unique indexes on `(company_id, idempotency_key, invoice_id)` and `(company_id, idempotency_key, purchase_id)`
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/SettlementIdempotencyMultiAllocationRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/20260116T092713Z_settlement_idempotency_indexes.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/20260116T092713Z_settlement_multi_alloc_idempotency.txt`
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
- Fix implemented (Phase 5):
  - `ConfigurationHealthService.checkTaxAccounts` now emits `TAX_ACCOUNT` issues for GST_INPUT/GST_OUTPUT.
  - `CompanyAccountingSettingsService.requireTaxAccounts` throws `VALIDATION_MISSING_REQUIRED_FIELD` with missing fields.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/GstConfigurationRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-011/OUTPUTS/20260113T103754Z_company_tax_accounts_after_null.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-011/OUTPUTS/20260113T103803Z_config_health_after_null.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-011/OUTPUTS/20260113T103812Z_gst_return_after_null.txt`
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
- Fix implemented (Phase 5):
  - `ProductionLogService.createLog` uses material cost for unit cost and semi-finished postings (`postingCost = materialCost`).
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionLogWipPostingRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/20260113T104209Z_production_log_create_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-012/OUTPUTS/20260113T104233Z_wip_delta_for_log_after_fix.txt`
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
- Fix implemented (Phase 5):
  - `ProductionLogRepository.incrementPackedQuantityAtomic` now clears/flushes so refreshed reads see updated totals.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionLogPackingStatusRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-013/OUTPUTS/20260113T103940Z_production_log_detail_after_pack.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-013/OUTPUTS/20260113T103953Z_production_log_status_after_packing.txt`
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
- Fix implemented (Phase 5):
  - `ProductionCatalogService.ensureFinishedGoodAccounts` now builds a mutable defaults map so null discount does not throw.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionCatalogDiscountDefaultRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-014/OUTPUTS/20260113T103705Z_company_default_accounts_after_null.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-014/OUTPUTS/20260113T103726Z_create_product_after_fix.txt`
- Future-proof test suggestion:
  - Integration test: finished-good creation with null default discount returns a 4xx validation error (not 500).

---

## LF-015 — Production log list/detail endpoints 500 due to lazy-loaded brand/product

- Workflow + modules + portal: Production logs (`factory`) — Factory/Accounting portals
- ERP expectation:
  - Production log list/detail should return without 500s; DTO mapping should not fail due to lazy-loading.
- As-built behavior:
  - `ProductionLogService.recentLogs` and `getLog` map to DTOs outside a transaction, so lazy-loaded `ProductionBrand` access throws `LazyInitializationException` when open-in-view is disabled.
- Evidence:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:258`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095856Z_production_logs_list.txt` (HTTP 500)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095920Z_production_logs_detail.txt` (HTTP 500)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095904Z_erp_domain_app_logs.txt` (LazyInitializationException on ProductionBrand)
- Severity: **MED** (production log visibility broken; audit workflows blocked)
- Repro steps (dev):
  1) GET `/api/v1/factory/production/logs` with open-in-view disabled.
  2) GET `/api/v1/factory/production/logs/{id}`.
  3) Observe HTTP 500 and LazyInitializationException in logs.
- Fix implemented (Phase 5):
  - Added `@Transactional` to `ProductionLogService.recentLogs` and `getLog` to keep the session open during DTO mapping.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/ProductionLogEndpointLazyLoadingIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T103611Z_production_logs_list_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T103622Z_production_logs_detail_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T103637Z_erp_domain_app_logs_after_fix.txt`

---

## LF-016 — Bulk-to-size packing: missing bulk ISSUE movement and missing movement↔journal linkage

- Workflow + modules + portal: Production/bulk packing (`factory`, `inventory`, `accounting`) — Factory/Accounting portals
- ERP expectation:
  - Converting bulk inventory into sized child SKUs should produce a complete, auditable movement chain:
    - bulk batch stock decreases are recorded as ISSUE movements
    - child batch stock increases are recorded as RECEIPT movements
    - financially-impacting movements link to the posting `journal_entry_id`
- As-built behavior:
  - `BulkPackingService.pack(...)` reduces the parent bulk batch available quantity and bulk finished-good stock directly, but does **not** write an ISSUE `inventory_movements` row for the bulk deduction.
  - `BulkPackingService.createChildBatch(...)` writes child RECEIPT `inventory_movements` (`reference_type='PACKAGING'`, `reference_id='PACK-'||parent_batch_code`) but never sets `journal_entry_id`.
  - `BulkPackingService.postPackagingJournal(...)` posts a balanced journal entry and returns `journal.id()` without updating the movements.
- Evidence:
  - Code anchors:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java:222` (bulk quantity deduction)
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java:314` (child RECEIPT movement write)
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java:328` (journal posting without movement linkage)
  - SQL probe outputs (BBP company_id=5):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_01_bulk_pack_child_receipts_missing_journal.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_02_bulk_pack_missing_bulk_issue_movement.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_04_bulk_pack_movements_vs_journals_linkage.txt`
- Severity: **HIGH** (inventory audit trail and movement↔GL traceability broken; investigations become manual/fragile)
- Repro steps (dev):
  1) Login and POST `/api/v1/factory/pack` (bulk-to-size) once.
  2) Run `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/01_bulk_pack_child_receipts_missing_journal.sql`.
  3) Run `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/02_bulk_pack_missing_bulk_issue_movement.sql`.
- Fix direction (no implementation):
  - Write a parent-batch ISSUE movement for the bulk deduction under the same semantic reference.
  - Link both ISSUE/RECEIPT movements to the posted journal entry (`inventory_movements.journal_entry_id`).
  - Define a stable reference mapping between movement reference_id and journal reference_number.
- Fix implemented (Phase 5):
  - `BulkPackingService.pack` now records a parent-batch ISSUE movement and links ISSUE/RECEIPT movements to the posted journal entry.
  - Pack reference is deterministic (`PACK-<batch>-<hash>`), shared by movements and the posted journal.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackMovementIdempotencyRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084831Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
- Future-proof test suggestion:
  - Integration test: bulk pack creates both ISSUE + RECEIPT movements and sets `journal_entry_id` on both.

---

## LF-017 — Bulk-to-size packing journals duplicate on retry due to timestamp-based reference

- Workflow + modules + portal: Production/bulk packing idempotency (`factory`, `accounting`) — Factory/Accounting portals
- ERP expectation:
  - Retry of the same business action should not create multiple POSTED journals (and should not duplicate inventory side effects).
- As-built behavior:
  - `BulkPackingService.postPackagingJournal(...)` builds the journal reference number using `System.currentTimeMillis()`, so each retry generates a new `(company_id, reference_number)` and posts a new journal.
- Evidence:
  - Code anchor:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java:402` (`reference = "PACK-" + bulkBatch.getBatchCode() + "-" + System.currentTimeMillis()`)
  - SQL probe outputs (BBP company_id=5):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T110014Z_sql_07_bulk_pack_recent_journals.txt`
  - API probe responses:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105824Z_bulk_pack_response_1.json` (journalEntryId=24)
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105856Z_bulk_pack_response_2.json` (journalEntryId=25)
- Severity: **HIGH** (duplicate financial postings + stock changes under client/network retries)
- Repro steps (dev):
  1) POST `/api/v1/factory/pack` twice with the same payload.
  2) Run `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/SQL/03_bulk_pack_journal_duplicates_by_semantic_reference.sql`.
- Fix direction (no implementation):
  - Use a deterministic reference (e.g., derived from bulkBatchId + request hash or client-supplied idempotency key).
  - Persist an idempotency marker (e.g., on a BulkPack document/table) so retries return the original journal.
- Fix implemented (Phase 5):
  - Deterministic pack reference is derived from bulk batch + request hash (no timestamps).
  - Idempotency guard returns the original pack response when movements exist for the pack reference.
  - Bulk batch locking (`lockById`) keeps concurrent requests serialized for the same batch.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackMovementIdempotencyRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120701Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084816Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
- Future-proof test suggestion:
  - Integration test: repeated bulk pack request is idempotent (no extra journal entries, no extra movements).

---

## LF-018 — Unpacked batches endpoint 500 due to lazy-loaded product

- Workflow + modules + portal: Packing queue (`factory`) — Factory portal
- ERP expectation:
  - Unpacked-batches list should return reliably; queue visibility must not 500.
- As-built behavior:
  - `PackingService.listUnpackedBatches` accesses `ProductionProduct` outside a transactional session, triggering `LazyInitializationException` when open-in-view is disabled.
- Evidence:
  - API probe (BBP):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075446Z_unpacked_batches_get.txt` (HTTP 500)
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075453Z_app_logs.txt` (LazyInitializationException on ProductionProduct)
  - Code anchor:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java:190`
- Severity: **MED** (packing workflow blocked; queue visibility broken)
- Repro steps (dev):
  1) GET `/api/v1/factory/unpacked-batches`.
  2) Observe HTTP 500 and LazyInitializationException in app logs.
- Fix direction (no implementation):
  - Wrap `listUnpackedBatches` in a transactional boundary or fetch-join product/brand to avoid lazy-load failures.

---

## LF-019 — Payroll PF deduction ignored in payroll run + posting

- Workflow + modules + portal: Payroll (`hr`, `accounting`) — HR/Accounting portals
- ERP expectation:
  - Payroll preview/run lines and posted journals must reflect statutory PF deductions; net payable should be gross minus all deductions.
- As-built behavior:
  - Monthly pay summary computes PF deductions, but payroll run lines set `pfDeduction=0`, and posting credits salary payable using only gross minus advances.
- Evidence:
  - API probes (BBP):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080023Z_monthly_summary.txt` (PF deducted)
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080049Z_monthly_run_lines.txt` (pfDeduction=0; net pay unchanged)
  - Code anchors:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java:260` (sets `pfDeduction` to zero)
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java:303` (posting credits salary payable without PF)
- Severity: **HIGH** (payroll liabilities and statutory deductions misstated)
- Repro steps (dev):
  1) Create a STAFF employee with monthlySalary >= 15000 and workingDaysPerMonth=1.
  2) Mark one PRESENT attendance day.
  3) GET `/api/v1/payroll/summary/monthly?year=2026&month=1`.
  4) Create + calculate monthly payroll run; GET `/api/v1/payroll/runs/{id}/lines`.
  5) Compare PF deduction and net pay between summary and run lines.
- Fix direction (no implementation):
  - Compute PF in payroll run line calculation and include PF liability line in payroll posting (reduce salary payable accordingly).

---

## LF-020 — Raw material batch codes not enforced unique

- Workflow + modules + portal: Inventory intake (`inventory`, `purchasing`) — Accounting/Factory portals
- ERP expectation:
  - Raw material batch codes should be unique per company/material to preserve FIFO traceability and audit trails.
- As-built behavior:
  - API allows creating multiple batches with the same `batch_code` for the same material; DB schema has no uniqueness constraint.
- Evidence:
  - API probes (BBP):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080222Z_batch_create_1.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080228Z_batch_create_2.txt`
  - SQL confirmation:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080250Z_duplicate_batch_codes.txt`
  - Code anchor:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/RawMaterialService.java:395` (accepts requested batch code without uniqueness check)
- Severity: **MED** (traceability ambiguity; FIFO consumption audit gaps)
- Repro steps (dev):
  1) POST `/api/v1/raw-material-batches/{rawMaterialId}` with `batchCode=LEAD-007-DUP`.
  2) Repeat the same request.
  3) Query `raw_material_batches` by batch_code; observe duplicates.
- Fix direction (no implementation):
  - Enforce uniqueness at DB level (company_id + raw_material_id + batch_code) and add service validation.

---

## LF-021 — Inventory control ledger does not reconcile to inventory valuation

- Workflow + modules + portal: Inventory reconciliation (`inventory`, `accounting`, `reports`) — Accounting portal
- ERP expectation:
  - Inventory valuation (FIFO) should reconcile to the inventory control account within tolerance; reconciliation report should be green at close.
- As-built behavior:
  - Inventory valuation (RM + FG) totals 9203, while the inventory control account balance is 68; reconciliation variance is 9135 and fails the checklist tolerance.
- Evidence:
  - SQL probes (BBP company_id=5):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090230Z_sql_06_inventory_valuation_fifo.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090230Z_sql_07_inventory_control_vs_valuation.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090337Z_sql_inventory_account_balance.txt`
  - Report probe:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090237Z_accounting_reports_gets.txt` (inventory reconciliation variance 9135).
- Severity: **HIGH** (period close blocked; GL vs stock totals unreliable)
- Repro steps (dev):
  1) Run `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/06_inventory_valuation_fifo.sql`.
  2) Run `psql -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`.
  3) GET `/api/v1/reports/inventory-reconciliation` and verify variance exceeds tolerance.
- Fix implemented (Phase 5):
  - Opening stock import now posts a single OPEN-STOCK journal (Dr inventory by account, Cr OPEN-BAL equity) and links journal_entry_id on raw + finished movements.
  - OPEN-BAL equity account is created on demand when missing.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/OpeningStockPostingRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105316Z_sql_07_inventory_control_vs_valuation.txt` (BBP seed variance persists; backfill required).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105325Z_accounting_reports_gets.txt`
- Backfill note:
  - Seeded BBP inventory remains unreconciled until opening stock movements are backfilled to GL.

---

## LF-022 — Purchase return reference reuse duplicates raw material movements

- Workflow + modules + portal: Purchase returns (`purchasing`, `inventory`, `accounting`) — Purchasing portal
- ERP expectation:
  - Retries with the same `referenceNumber` must be idempotent across journals *and* inventory movements; replays should not change stock.
- As-built behavior:
  - Replaying the same purchase return reference reuses the journal entry but posts additional `raw_material_movements`, reducing stock on each replay.
- Evidence:
  - API responses (MOCK company_id=6):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090916Z_purchase_return_response_1.json`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090930Z_purchase_return_response_2.json`
  - Stock drift:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090908Z_sql_raw_material_stock_before_return.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090922Z_sql_raw_material_stock_after_return_1.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090938Z_sql_raw_material_stock_after_return_2.txt`
  - Movement duplication:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090944Z_sql_purchase_return_reference.txt`
- Severity: **MED** (inventory drift without financial duplication)
- Repro steps (dev):
  1) POST `/api/v1/purchasing/raw-material-purchases/returns` with a fixed `referenceNumber`.
  2) Replay the same payload with the same reference.
  3) Query `raw_material_movements` for the reference and `raw_materials.current_stock`.
- Fix implemented (Phase 5):
  - `PurchasingService.recordPurchaseReturn` now reuses movements by (company, reference) and rejects replay payload mismatches.
  - Existing movements are linked to the return journal entry when reusing a reference.
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/PurchaseReturnIdempotencyRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105141Z_sql_raw_material_stock_before_return.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105208Z_sql_raw_material_stock_after_return_2.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105215Z_sql_purchase_return_reference.txt`

---

## LF-023 — Idempotency key conflicts accepted (sales order + payroll run)

- Workflow + modules + portal: Sales order creation + payroll runs (`sales`, `hr`) — Sales + HR portals
- ERP expectation:
  - Idempotency keys should be fail-closed: a conflicting payload must be rejected (409) or require a new key.
- As-built behavior:
  - Reusing the same idempotency key with a conflicting payload returns the existing record (HTTP 200), masking client errors.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090838Z_sales_order_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090855Z_payroll_run_conflict_response.json`
- Severity: **MED** (client replay ambiguity; potential silent data mismatch)
- Repro steps (dev):
  1) POST `/api/v1/sales/orders` with `idempotencyKey=K1` and total A.
  2) Replay with the same key but a different total B.
  3) Observe HTTP 200 with the original order, not a rejection.
- Fix implemented (Phase 5):
  - Added idempotency_hash to sales orders and payroll runs; create signatures from payloads and reject conflicting replays (HTTP 409).
- Regression test:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/IdempotencyConflictRegressionIT.java`
- Fix evidence (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105052Z_sales_order_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105110Z_payroll_run_conflict_response.json`
