# Task 03 — Auditability + Linkage Contracts (Chain of Evidence + Invariant/Test Mapping)

## Purpose
**Accountant-level:** guarantee every posting has a complete audit trail (who/what/when/why), can be traced end‑to‑end, and reconciles between subledgers and the GL.

**System-level:** define explicit “linkage contracts” for each workflow and map them to enforceable invariants + tests, so the ERP cannot return success while leaving untraceable state.

## Scope guard (explicitly NOT allowed)
- No new business workflows; do not “invent” missing modules.
- Do not relax validations to make tests pass.
- Do not add irreversible financial actions without documented rationale and evidence chain.

## Milestones

### M1 — Write linkage contracts for the core flows (O2C, P2P/AP, Production, Payroll)
Deliverables:
- In this task file, maintain the contracts below:
  - chain of evidence per flow
  - required invariants (fail‑closed)
  - exact tests that enforce each invariant (or mark missing)
- Cross-reference existing anchors:
  - `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`
  - `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`
  - `erp-domain/docs/RECONCILIATION_CONTRACTS.md`

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

Evidence to capture:
- A “contracts completed” note and a list of any “UNKNOWN/needs verify” items.

Stop conditions + smallest decision needed:
- If a contract requires a link that does not exist in schema: smallest decision is whether to add (A) a forward-only nullable link + backfill plan, or (B) a derived reference mapping (without schema change). Prefer (B) unless auditability requires persisted linkage.

### M2 — Map every invariant to enforcement (tests + runtime guards)
Deliverables:
- For each contract invariant:
  - list the enforcing test(s) (existing or to add)
  - list the enforcing runtime checks (existing or to harden later)
- Create a short “Missing tests to add” register that feeds Task 04/05.

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`

Evidence to capture:
- The missing-tests register + prioritized order (highest financial risk first).

Stop conditions + smallest decision needed:
- If enforcing an invariant would break existing intended behavior: smallest decision is whether behavior is actually unintended (bug) vs a documented exception. Default stance: fail‑closed unless exception is explicitly documented and reconciles.

### M3 — Define “evidence chain assertions” (what must be true in DB/API)
Deliverables:
- A set of concrete assertions (API responses and/or SQL checks) that prove linkage:
  - “no posted invoice without journal_entry_id”
  - “no inventory movement of financial type without journal_entry_id”
  - “all dealer/supplier ledger entries reference journal ids and same company”
- These assertions must be runnable as part of later tasks and captured as evidence.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ReconciliationControlsIT,InventoryGlReconciliationIT test`

Evidence to capture:
- The assertion list + sample outputs on a seeded dataset.

Stop conditions + smallest decision needed:
- If assertions require production data access: smallest decision is whether to run on (A) a sanitized prod snapshot, or (B) a prod-like seed dataset; prefer (B) unless investigating a prod incident.

---

## Linkage contracts (maintain as source-of-truth)

### Contract: O2C (Order‑to‑Cash)
Chain of evidence (minimum):
1) Order: `SalesOrder` / `sales_orders`
2) Reservation/dispatch artifact: `PackagingSlip` / `packaging_slips`
3) Inventory issue movements: `InventoryMovement` / `inventory_movements` (and/or RM movements where applicable)
4) Invoice: `Invoice` / `invoices`
5) Journals: `JournalEntry` + `JournalLine` / `journal_entries`, `journal_lines`
6) Dealer subledger: `DealerLedgerEntry` / `dealer_ledger_entries`
7) Settlement/receipt allocations: `PartnerSettlementAllocation` / `partner_settlement_allocations`
8) Reconciliation outputs:
   - dealer statement/aging
   - AR control reconciliation (`RECONCILIATION_CONTRACTS.md`)

Observed linkage keys (verified in code/docs):
- `sales_orders.fulfillment_invoice_id` -> `invoices.id`
- `sales_orders.sales_journal_entry_id` / `sales_orders.cogs_journal_entry_id` -> `journal_entries.id`
- `packaging_slips.invoice_id` -> `invoices.id`
- `packaging_slips.journal_entry_id` / `packaging_slips.cogs_journal_entry_id` -> `journal_entries.id`
- `invoices.journal_entry_id` -> `journal_entries.id`
- `dealer_ledger_entries.journal_entry_id` -> `journal_entries.id`
- `dealer_ledger_entries.invoice_number` -> `invoices.invoice_number`
- `partner_settlement_allocations.invoice_id` -> `invoices.id`
- `partner_settlement_allocations.journal_entry_id` -> `journal_entries.id`
- `packaging_slip_lines.packaging_slip_id` -> `packaging_slips.id`
- `packaging_slip_lines.finished_good_batch_id` -> `finished_good_batches.id`
- `inventory_movements.reference_type=SALES_ORDER` uses `reference_id = sales_orders.id`

Required invariants:
- Dispatch confirm is idempotent: retries do not double-issue stock, double-create invoices, or double-post journals (SalesOrder idempotency markers + PackagingSlip status).
- Posted invoice has a journal link (`invoices.journal_entry_id`) and is same-company.
- Dispatch sets `packaging_slips.invoice_id`, `packaging_slips.journal_entry_id`, and `packaging_slips.cogs_journal_entry_id` once posted.
- Inventory issue movements created by dispatch carry `reference_type=SALES_ORDER` and link to the posting journal when COGS is posted.
- Dealer ledger entries reference the same invoice/journal identifiers used by accounting and are same-company.
- Settlement allocations are idempotent and cannot exceed outstanding (no negative outstanding drift).

Existing tests (verify/extend as needed):
- `ErpInvariantsSuiteIT` (golden O2C + linkage + idempotency)
- `OrderFulfillmentE2ETest`, `DispatchConfirmationIT`, `DealerLedgerIT`, `SettlementE2ETest`, `GstInclusiveRoundingIT`

Gaps to verify (do not assume):
- Any “unallocated receipt” endpoints that post without allocations (must not drift subledger).
- Any alias endpoints that bypass intended RBAC.

### Contract: P2P/AP (Procure‑to‑Pay / Accounts Payable)
Chain of evidence (minimum):
1) Supplier: `Supplier` / `suppliers`
2) Purchase: `RawMaterialPurchase` / `raw_material_purchases`
3) Receipt movements/batches: `RawMaterialMovement` + `RawMaterialBatch` / `raw_material_movements`, `raw_material_batches`
4) Journals: `JournalEntry`/`JournalLine`
5) Supplier subledger: `SupplierLedgerEntry`
6) Settlement/payment allocations: `PartnerSettlementAllocation`
7) Reconciliation outputs:
   - supplier statement/aging
   - AP control reconciliation

Observed linkage keys (verified in code/docs):
- `raw_material_purchases.journal_entry_id` -> `journal_entries.id`
- `raw_material_purchases.supplier_id` -> `suppliers.id`
- `partner_settlement_allocations.purchase_id` -> `raw_material_purchases.id`
- `partner_settlement_allocations.journal_entry_id` -> `journal_entries.id`
- `supplier_ledger_entries.journal_entry_id` -> `journal_entries.id`
- `raw_material_movements.reference_type=RAW_MATERIAL_PURCHASE` uses `reference_id = raw_material_batches.batch_code`
- `raw_material_movements.reference_type=PURCHASE_RETURN` uses `reference_id = purchase return reference`

Required invariants:
- Purchase posts AP + inventory effects and is linked to journals (same-company).
- Receipt movements exist, are ordered/idempotent, and link to journals when posting is expected.
- Supplier settlements/payments are idempotent and reconcile to AP control account within tolerance.
- Purchase returns reverse inventory and AP consistently, set `raw_material_movements.journal_entry_id`, and are traceable.

Existing tests (verify/extend as needed):
- `ErpInvariantsSuiteIT` (golden P2P)
- `ProcureToPayE2ETest`, `SupplierStatementAgingIT`, `ReconciliationControlsIT`

### Contract: Production (Produce‑to‑Stock)
Chain of evidence (minimum):
1) Production log: `ProductionLog` / `production_logs`
2) Production materials: `production_log_materials`
3) Packing record: `PackingRecord` / `packing_records`
4) Finished goods batch: `FinishedGoodBatch` / `finished_good_batches`
5) Inventory movements (RM consumption + FG creation): movements tables
6) Journals (if costing/WIP enabled): `JournalEntry`/`JournalLine`
7) Reconciliation outputs:
   - inventory valuation/reconciliation
   - WIP/FG rollforward (if applicable)

Observed linkage keys (verified in code/docs):
- `packing_records.production_log_id` -> `production_logs.id`
- `packing_records.finished_good_batch_id` -> `finished_good_batches.id`
- `production_logs.production_code` used as `inventory_movements.reference_id` when `reference_type=PRODUCTION_LOG`
- `inventory_movements.reference_type=PACKING_RECORD` uses `reference_id = <production_code>-PACK-<lineIndex>`
- `inventory_movements.journal_entry_id` -> `journal_entries.id` (when costing posted)
- `finished_good_batches.parent_batch_id` links bulk-to-size child batches
- `inventory_movements.reference_type=MANUFACTURING_ORDER` uses `reference_id = finished_good_batches.public_id`
- `inventory_movements.reference_type=OPENING_STOCK` uses `reference_id = opening stock batch code`

Required invariants:
- No orphan production logs/packing records: each creates the expected movements/batches.
- Stock movements from production are linked back to production references (traceability) and same-company.
- If costing journals are posted, they are linked to production references and balanced.

Existing tests (verify/extend as needed):
- `ErpInvariantsSuiteIT` (production golden path)
- `FactoryPackagingCostingIT`, `CompleteProductionCycleTest`, `WipToFinishedCostIT`

### Contract: Payroll (Hire‑to‑Pay)
Chain of evidence (minimum):
1) Payroll run: `PayrollRun` / `payroll_runs`
2) Payroll lines/totals: payroll line tables (verify actual names)
3) Posting journal: `JournalEntry`/`JournalLine` linked to payroll run
4) Payment marking / batch payments: payroll payment artifacts (verify)
5) Reconciliation outputs:
   - payroll expense vs payable clearing
   - period close impact

Observed linkage keys (verified in code/docs):
- `payroll_runs.journal_entry_id` -> `journal_entries.id`
- `payroll_runs.journal_entry_ref_id` -> `journal_entries.id` (back-compat)
- `payroll_run_lines.payroll_run_id` -> `payroll_runs.id`
- `attendance.payroll_run_id` -> `payroll_runs.id`

Required invariants:
- Payroll run state machine is enforced: calculate → approve → post → mark-paid (no skipping).
- Posting produces a linked journal entry; reversals are balanced inverse.
- Advances/withholdings clearing is consistent between payroll math and posting.
- Mark-paid operations are idempotent and auditable.

Existing tests (verify/extend as needed):
- `ErpInvariantsSuiteIT` (hire-to-pay golden + reversal)
- `PayrollBatchPaymentIT`, `PeriodCloseLockIT`

---

## M2 invariant enforcement mapping

### O2C (Order-to-Cash)
- Invariant: Dispatch confirm is idempotent (no double issue, invoice, or journal).
  - Tests: `ErpInvariantsSuiteIT`, `DispatchConfirmationIT`, `OrderFulfillmentE2ETest`.
  - Runtime guards: `SalesService.confirmDispatch` returns early when slip already `DISPATCHED` with invoice/journals; `FinishedGoodsService.confirmDispatch` short-circuits on dispatched slips; SERIALIZABLE transaction + dealer lock.
- Invariant: Posted invoice links to journal entry and same-company.
  - Tests: `ErpInvariantsSuiteIT` (`assertJournalLinkedTo("INVOICE")`).
  - Runtime guards: `SalesService.confirmDispatch` posts AR journal and sets `invoice.setJournalEntry(companyEntityLookup.requireJournalEntry(company, arJournalEntryId))`.
- Invariant: Dispatch sets slip invoice/journal ids on post.
  - Tests: `ErpInvariantsSuiteIT` (packaging slip journal linkage), `DispatchConfirmationIT`.
  - Runtime guards: `SalesService.confirmDispatch` sets `slip.invoiceId`, `slip.journalEntryId`, `slip.cogsJournalEntryId`.
- Invariant: Inventory issue movements reference SALES_ORDER and link to posting journal when COGS posted.
  - Tests: `ErpInvariantsSuiteIT` (inventory movement linkage), `DispatchConfirmationIT`.
  - Runtime guards: `FinishedGoodsService.confirmDispatch` records `InventoryReference.SALES_ORDER`; `FinishedGoodsService.linkDispatchMovementsToJournal` updates `journalEntryId`.
- Invariant: Dealer ledger entries reference the same invoice/journal and same company.
  - Tests: `DealerLedgerIT`, `SettlementE2ETest`, `ErpInvariantsSuiteIT` (subledger reconciliation).
  - Runtime guards: `DealerLedgerService.syncInvoiceLedger` requires invoice journal + same company; `AccountingService.settleDealerInvoices` locks dealer/invoice and resyncs ledger.
- Invariant: Settlement allocations are idempotent and cannot exceed outstanding.
  - Tests: `SettlementE2ETest`, `ErpInvariantsSuiteIT` (settlement idempotency + reconciliation).
  - Runtime guards: `AccountingService.settleDealerInvoices` uses idempotencyKey + invoice locks; `InvoiceSettlementPolicy.applySettlement` prevents negative outstanding (does not hard-fail on over-allocation).

### P2P/AP (Procure-to-Pay)
- Invariant: Purchase posts AP + inventory effects and links to journals (same-company).
  - Tests: `ErpInvariantsSuiteIT` (`assertJournalLinkedTo("PURCHASE")`), `ProcureToPayE2ETest`.
  - Runtime guards: `PurchasingService.createPurchase` posts journal first, sets `RawMaterialPurchase.journalEntry`, requires supplier payable + raw material inventory accounts.
- Invariant: Receipt movements exist, ordered/idempotent, and link to journals when expected.
  - Tests: `ProcureToPayE2ETest`, `ErpInvariantsSuiteIT` (inventory movement linkage).
  - Runtime guards: `PurchasingService.createPurchase` locks invoice number; `rawMaterialService.recordReceipt` uses `InventoryReference.RAW_MATERIAL_PURCHASE`; movement `journalEntryId` set after posting.
- Invariant: Supplier settlements/payments are idempotent and reconcile to AP control.
  - Tests: `SupplierStatementAgingIT`, `ReconciliationControlsIT`, `ErpInvariantsSuiteIT` (subledger reconciliation).
  - Runtime guards: `AccountingService.settleSupplierInvoices` uses idempotencyKey + locked purchase/supplier; validates discount/write-off accounts.
- Invariant: Purchase returns reverse inventory/AP and set `journal_entry_id`, traceable.
  - Tests: `ErpInvariantsSuiteIT` (P2P return path), `ProcureToPayE2ETest`.
  - Runtime guards: `PurchasingService.recordPurchaseReturn` posts journal first, uses atomic stock deduction, `issueReturnFromBatches` sets `reference_type=PURCHASE_RETURN` + `journalEntryId`.

### Production (Produce-to-Stock)
- Invariant: No orphan production logs/packing records.
  - Tests: `ErpInvariantsSuiteIT` (production dataset), `CompleteProductionCycleTest`.
  - Runtime guards: `ProductionLogService.createLog` requires materials and posts material journal; `PackingService.recordPacking` locks log, validates lines, uses atomic packed-quantity update.
- Invariant: Stock movements link back to production references and same-company.
  - Tests: `FactoryPackagingCostingIT`, `CompleteProductionCycleTest`, `ErpInvariantsSuiteIT`.
  - Runtime guards: `ProductionLogService.registerSemiFinishedBatch` and `PackingService.registerFinishedGoodBatch` use `InventoryReference.PRODUCTION_LOG`; `PackingService.linkPackagingMovementsToJournal` uses `reference_id=<production_code>-PACK-<n>`.
- Invariant: Costing journals (WIP/FG/packaging) are linked and balanced.
  - Tests: `WipToFinishedCostIT`, `FactoryPackagingCostingIT`, `ErpInvariantsSuiteIT`.
  - Runtime guards: `ProductionLogService.registerSemiFinishedBatch` posts WIP->semi-FG and sets movement journal; `PackingService.postPackingSessionJournal` posts FG receipt + links movement journals.

### Payroll (Hire-to-Pay)
- Invariant: Payroll run state machine enforced (calculate -> approve -> post -> mark-paid).
  - Tests: `ErpInvariantsSuiteIT`, `PayrollBatchPaymentIT`, `PeriodCloseLockIT`.
  - Runtime guards: `PayrollService.calculatePayroll/approvePayroll/postPayrollToAccounting/markAsPaid` enforce status transitions.
- Invariant: Posting produces linked journal entry; reversals are balanced inverse.
  - Tests: `ErpInvariantsSuiteIT` (`assertJournalLinkedTo("PAYROLL_RUN")`).
  - Runtime guards: `PayrollService.postPayrollToAccounting` builds journal entry and sets run journal id; no explicit payroll reversal helper (reversal coverage via accounting journal reversal tests).
- Invariant: Advances/withholdings clearing consistent between payroll math and posting.
  - Tests: `ErpInvariantsSuiteIT`, `PayrollBatchPaymentIT`.
  - Runtime guards: `PayrollService.calculatePayroll` computes advances; `postPayrollToAccounting` credits `EMP-ADV` when advances > 0.
- Invariant: Mark-paid operations are idempotent and auditable.
  - Tests: `PayrollBatchPaymentIT`, `ErpInvariantsSuiteIT` (marks paid in flow).
  - Runtime guards: `PayrollService.markAsPaid` requires POSTED status; sets payment status + reference on lines (no dedicated payment artifact).

## M2 missing tests register (prioritized)
1) Dealer receipt/settlement over-allocation guard (reject or cap allocations that exceed invoice outstanding; confirm subledger + AR control stay consistent).
2) Dispatch idempotency on repeated confirm for same slip/order (verify no duplicate journals/invoices/stock movements).
3) Unallocated receipt flows (if any) must not drift dealer ledger/AR control; confirm either blocked or fully allocated.
4) Packing record packaging-material journal linkage (`InventoryReference.PACKING_RECORD` movements have `journal_entry_id` set).
5) Payroll mark-paid audit linkage (verify payment reference persistence + idempotent mark-paid behavior).

## M3 evidence chain assertions (runnable checks)
Assertions (SQL/API) - run per company where applicable:
- Invoices issued must have journal links:
  - SQL: `select count(*) as missing_invoice_journal from invoices where status='ISSUED' and journal_entry_id is null;`
- Dispatched slips must link to invoice + journals:
  - SQL: `select count(*) as missing_dispatch_links from packaging_slips where status='DISPATCHED' and (invoice_id is null or journal_entry_id is null);`
- Dispatch inventory movements must link to journal when COGS posted:
  - SQL: `select count(*) as dispatch_moves_missing_journal from inventory_movements where reference_type='SALES_ORDER' and movement_type='DISPATCH' and journal_entry_id is null;`
- Packing/purchase return RM movements must link to journals:
  - SQL: `select count(*) as packing_rm_missing_journal from raw_material_movements where reference_type='PACKING_RECORD' and journal_entry_id is null;`
  - SQL: `select count(*) as return_rm_missing_journal from raw_material_movements where reference_type='PURCHASE_RETURN' and journal_entry_id is null;`
- Dealer/Supplier ledger entries must link to journals:
  - SQL: `select count(*) as dealer_ledger_missing_journal from dealer_ledger_entries where journal_entry_id is null;`
  - SQL: `select count(*) as supplier_ledger_missing_journal from supplier_ledger_entries where journal_entry_id is null;`
- Settlement allocations must link to journals and be idempotent:
  - SQL: `select count(*) as settlement_missing_journal from partner_settlement_allocations where journal_entry_id is null;`
  - SQL: `select count(*) as settlement_missing_idempotency from partner_settlement_allocations where idempotency_key is null;`
- Payroll runs posted/paid must link to journals; lines must be linked:
  - SQL: `select count(*) as payroll_missing_journal from payroll_runs where status in ('POSTED','PAID') and journal_entry_id is null;`
  - SQL: `select count(*) as payroll_line_missing_run from payroll_run_lines where payroll_run_id is null;`
- Production packing completeness:
  - SQL: `select count(*) as packed_missing_records from production_logs pl where pl.status='FULLY_PACKED' and not exists (select 1 from packing_records pr where pr.production_log_id = pl.id);`
  - SQL: `select count(*) as packing_record_missing_batch from packing_records where finished_good_batch_id is null;`

Sample outputs (seeded dataset):
- ReconciliationControlsIT:
  - `AR Reconciliation: GL=0.00, DealerLedger=0, Variance=0.00, Reconciled=true`
  - `AP Reconciliation: GL=0.00, SupplierLedger=0, Variance=0.00, Reconciled=true`
- InventoryGlReconciliationIT:
  - `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`

## M1 contracts completion notes
- Status: contracts updated with verified linkage keys from `erp-domain/docs/CROSS_MODULE_LINKAGE_MATRIX.md`.
- UNKNOWN/needs verify:
  - Confirm unallocated receipt flows (if any) cannot drift dealer ledger or AR control.
  - Confirm dispatch idempotency uses `sales_orders.idempotency_key` consistently across slip/invoice/journal creation.
  - Confirm payroll payment artifacts table(s) and linkage to `payroll_runs` for mark-paid audit trails.
