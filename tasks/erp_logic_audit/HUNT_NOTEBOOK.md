# Hunt Notebook (LEADS / hypotheses / probes)

Status: **ACTIVE**

Rules:
- Use this notebook for suspected issues that are **not yet confirmed**.
- Convert to an LF item in `tasks/erp_logic_audit/LOGIC_FLAWS.md` only after evidence is collected.

## Lead triage table (copy/sort)

| ID | Potential severity | Perspective(s) | Short title | Next probe(s) |
|---:|--------------------|----------------|-------------|---------------|
| LEAD-001 | HIGH? | Auditor / Operator | Invoice/purchase outstanding overwritten on create | Add focused test or manual persist + inspect `outstanding_amount` |
| LEAD-002 | HIGH? | Operator / Backend | Raw material stock clamps to zero (silent negative) | Create over-issue RM flow; verify stock/ledger outcomes |
| LEAD-003 | HIGH? | Backend / SRE | Dispatch confirm double-invokes confirmation | Call `/api/v1/dispatch/confirm` once; inspect movements/journals created |
| LEAD-004 | HIGH? | Operator / Auditor | Payroll PF deduction inconsistencies (preview/run/posting) | Compare preview vs run totals; inspect payroll journal lines |
| LEAD-005 | HIGH? | Security / Backend | Inventory accounting events accept non-scoped account IDs | Attempt cross-company accountId injection (dev-only) |
| LEAD-006 | HIGH? | SRE / Backend | AFTER_COMMIT inventory posting drift on failure | Force JE creation failure; verify inventory changed but JE missing |
| LEAD-007 | MED? | Operator | Raw material batch codes not enforced unique | Try create duplicate batch_code; check FIFO/traceability impact |
| LEAD-008 | MED? | Auditor / Close | Inventory revaluation journals use `LocalDate.now()` | Revalue dated into closed period; inspect JE entry_date |
| LEAD-009 | MED? | Operator / Auditor | AR/AP reconciliation depends on account code substrings | Change COA codes; verify recon returns false positives/negatives |
| LEAD-010 | HIGH? | Backend / Operator | Sales order idempotency key not enforced at DB | Closed: duplicate attempt rejected; unique index present (see evidence) |
| LEAD-011 | MED? | Backend / Operator | Purchase return idempotency relies on optional reference | Confirmed → LF-010 (duplicate returns created without reference) |
| LEAD-012 | MED? | Auditor / Operator | Production WIP postings unverified (no production logs) | Closed → LF-012 |
| LEAD-013 | MED? | Auditor / Operator | GST return blocked by missing tax account config | Closed → LF-011 |
| LEAD-014 | LOW? | SRE / Operator | Actuator health on app port 404s (management port required) | Closed: prod config binds actuator to management port; app-port 404 expected |
| LEAD-015 | MED? | Operator / Auditor | Production log detail endpoint 500s (lazy load) | Closed → LF-015 |
| LEAD-016 | LOW? | Auditor / Operator | Admin override does not bypass locked period posting | Closed: period lock requires reopen; admin override only affects date constraints |
| LEAD-017 | MED? | Operator / Auditor | Unpacked batches endpoint 500s (lazy load) | Repro GET `/api/v1/factory/unpacked-batches`; capture logs + add transactional/fetch probe |
| LEAD-018 | HIGH? | Auditor / Accounting | Inventory reconciliation variance (ledger vs valuation) | Trace inventory control balance vs valuation inputs; verify opening + movement JE coverage |
| LEAD-COST-001 | HIGH | Auditor / Backend | Bulk packing: missing bulk ISSUE + movement↔journal link | Confirmed → LF-016 (see `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_01_bulk_pack_child_receipts_missing_journal.txt`; `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_02_bulk_pack_missing_bulk_issue_movement.txt`) |
| LEAD-COST-002 | HIGH | Backend / Auditor | Bulk packing journal reference non-idempotent (duplicates on retry) | Confirmed → LF-017 (see `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T110014Z_sql_07_bulk_pack_recent_journals.txt`) |
| LEAD-COST-005 | MED? | Auditor / Accounting | Wastage journal uses material-only valuation (labor/overhead excluded) | Closed (as-built: production unit_cost is material-only; see `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105157Z_sql_05_wastage_journal_value_vs_cost_components.txt`) |

---

## LEAD-001 — Invoice/purchase `outstanding_amount` overwritten to `total_amount` when zero (migration + paid-at-creation risk)

- Status: **CLOSED** — Creation paths always set outstanding to total; no API accepts `outstanding_amount=0` on create.

- Hypothesis:
  - Creating an `Invoice` or `RawMaterialPurchase` with `outstanding_amount = 0` (already paid, migrated historical invoice, credit note, or forced “paid at creation”) will be silently overwritten to `total_amount` by `@PrePersist`.
- Why this matters (ERP expectation):
  - Outstanding should reflect settlement state, not be forced to equal total.
  - You need to support onboarding/migration and edge flows (write-off, paid-at-creation, credit memo) without silently re-opening receivables/payables.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/domain/Invoice.java` (`prePersist()` sets `outstandingAmount = totalAmount` when `outstandingAmount == null || == 0`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/domain/RawMaterialPurchase.java` (`prePersist()` same pattern).
- Minimal probes (prefer read-only, but this one likely needs a controlled write in dev or a test):
  - Add a focused integration/unit test that persists an invoice/purchase with `outstandingAmount=0` and asserts DB value remains 0 (expected) vs becomes total (current).
  - If doing manually in dev: create via repository or seed SQL insert (dev only), then query `invoices.outstanding_amount`.
- What would count as confirmed flaw:
  - Repro shows `outstanding_amount` becomes non-zero despite explicit 0.
- Why tests might still pass:
  - The app’s happy path likely never creates invoices/purchases with outstanding 0 at creation; it sets outstanding to total and only updates later.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/20260114T080457Z_invoice_controller_excerpt.txt` (no create endpoint for invoices)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/20260114T080503Z_invoice_service_outstanding_amount.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/20260114T080444Z_raw_material_purchase_request.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/OUTPUTS/20260114T080449Z_purchasing_service_outstanding_amount.txt`

---

## LEAD-002 — Raw material stock is “fail-open” (negative clamps to zero), unlike finished goods (throws)

- Status: **CLOSED** — Over-issue is rejected with 400; stock stays unchanged.

- Hypothesis:
  - Raw material stock can go negative in services, but the entity setter clamps it back to zero, hiding the error and allowing workflows to “succeed” while silently losing the deficit signal.
- Why this matters (ERP expectation):
  - If non-negative inventory is enforced, it must fail-closed (reject over-issue), not silently coerce.
  - Silent coercion breaks auditability and can cause valuation/COGS drift.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterial.java`:
    - `setCurrentStock(...)` uses `currentStock.max(BigDecimal.ZERO)` (clamp).
  - Contrast:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/FinishedGood.java`:
      - `setCurrentStock(...)` throws if negative; `adjustStock(...)` fail-closed.
- Minimal probes:
  - Dev-only: attempt RM issue/consume > on-hand and observe whether request fails (expected) vs succeeds and sets stock to 0 (suspected).
  - SQL drift check: `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql` (look for variance signals that correlate with over-issue).
- What would count as confirmed flaw:
  - Successful over-issue resulting in clamped stock, with movements recorded as if valid.
- Why tests might still pass:
  - Golden-path tests may not include negative stock attempts or race conditions that create temporary negative balances.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/20260114T075521Z_raw_material_stock.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/20260114T075548Z_production_log_over_issue_response.txt` (HTTP 400)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-002/OUTPUTS/20260114T075558Z_raw_material_stock_after_over_issue.txt`

---

## LEAD-003 — `/api/v1/dispatch/confirm` appears to confirm dispatch twice (double side-effect risk)

- Status: **CLOSED** — Controller double-call is guarded; `FinishedGoodsService.confirmDispatch` returns early once slip is DISPATCHED.

- Hypothesis:
  - Dispatch confirm controller calls `SalesService.confirmDispatch(...)` and then calls `FinishedGoodsService.confirmDispatch(...)` directly, which may cause duplicate inventory/journal side effects if both paths perform confirmation logic.
- Why this matters (ERP expectation):
  - Confirm dispatch is a financially-impacting boundary: it must be exactly-once or safely idempotent across all derived artifacts (movements, journals, ledger updates).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/controller/DispatchController.java` (`confirmDispatch(...)` calls both services).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`confirmDispatch(...)` calls into `FinishedGoodsService.confirmDispatch(...)`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java` (`confirmDispatch(...)`).
- Minimal probes:
  - Dev-only: call `/api/v1/dispatch/confirm` once and then run:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/11_idempotency_duplicates.sql`
    - a targeted query for `inventory_movements` count by `(reference_type, reference_id, movement_type)` for the slip.
- What would count as confirmed flaw:
  - Duplicate movements and/or duplicate journals created from a single confirm action (or evidence that idempotency markers diverge between the two call paths).
- Why tests might still pass:
  - Idempotency markers may currently prevent duplicates in the seeded scenarios, but this remains a regression trap if either path changes.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T075616Z_pending_packaging_slips.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T075647Z_dispatch_slip_2.txt` (no lines in seed slip)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T080510Z_dispatch_controller_confirm_excerpt.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T080514Z_sales_service_confirm_dispatch_excerpt.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-003/OUTPUTS/20260114T080518Z_finished_goods_confirm_dispatch_excerpt.txt`

---

## LEAD-004 — Payroll PF deduction appears inconsistent across preview/run/posting (operator truth drift)

- Status: **CONFIRMED → LF-019**

- Hypothesis:
  - Payroll preview calculations omit PF (or other statutory) deductions, while payroll run lines include PF and payroll posting does not reduce salary payable by PF, creating multiple “truths” for the same run.
- Why this matters (ERP expectation):
  - Operators rely on preview to match what will post; payroll journal should reflect net payable after all deductions.
- Code anchors:
  - Preview (no PF observed in line item):
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollCalculationService.java` (`calculateEmployeePay(...)` sets `totalDeductions = advanceDeduction` only).
  - Run line DTO exposes PF:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java` (`PayrollRunLineDto` includes `pfDeduction`).
  - Posting to GL ignores PF:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/service/PayrollService.java` (`postPayrollToAccounting(...)` credits salary payable = gross - advances, not net after PF).
- Minimal probes:
  - Dev-only: run preview vs create payroll run; compare `totalNetPay` and per-employee net.
  - Inspect the created payroll `JournalEntry` lines for the run and compare to `PayrollRunLine.totalDeductions` and `totalNetPay`.
- What would count as confirmed flaw:
  - Any reproducible mismatch between preview and run totals, or salary payable credit not equaling net payable after all deductions.
- Why tests might still pass:
  - Existing tests may not assert payroll preview equality, PF policy, or posting line correctness beyond “journal balanced”.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080023Z_monthly_summary.txt` (PF deduction present)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-004/OUTPUTS/20260114T080049Z_monthly_run_lines.txt` (pfDeduction=0)

---

## LEAD-005 — Inventory accounting events use `accountRepository.findById(...)` (potential cross-company account injection)

- Status: **CLOSED** — No cross-company journal line/account mismatches observed in dataset.

- Hypothesis:
  - Event-driven posting uses raw `account_id` values without verifying the account belongs to the event’s company, risking cross-company ledger contamination if IDs are misconfigured or forged in internal event creation.
- Why this matters (ERP expectation):
  - Ledger postings must never mix companies; enforcing this only at request filters is insufficient when internal event handlers run.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`:
    - `accountRepository.findById(event.inventoryAccountId())` (no company predicate).
    - Movement handler uses `event.sourceAccountId()` / `event.destinationAccountId()` directly in journal lines (no company validation).
- Minimal probes:
  - Dev-only (controlled): emit an inventory event with an accountId from another company and observe whether:
    - JE creation succeeds, and
    - the resulting journal entry is company-scoped but references a foreign-company account_id.
  - SQL: `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/09_tenancy_cross_company_links.sql` (extend if needed to include journal_lines.account_id company mismatch checks).
- What would count as confirmed flaw:
  - A journal entry exists for Company A with journal lines pointing to an account belonging to Company B.
- Why tests might still pass:
  - Seed/test data likely uses a single company, and event account IDs are configured correctly.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/20260114T080314Z_journal_line_account_company_mismatch.txt` (0 rows)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-005/OUTPUTS/20260114T080336Z_inventory_accounting_event_listener_excerpt.txt`

---

## LEAD-006 — Inventory auto-posting is AFTER_COMMIT + REQUIRES_NEW; JE failure leaves inventory committed (drift-by-design)

- Status: **CLOSED** — No inventory movement events are emitted in current code paths; no direct repro of after-commit drift.

- Hypothesis:
  - Inventory operations can commit successfully, while the subsequent accounting auto-post fails and is caught/logged, leaving permanent inventory↔GL drift until manual repair.
- Why this matters (ERP expectation):
  - For financially-impacting inventory events, posting should be atomic or explicitly marked “unposted” with a fail-closed control (or a guaranteed retry/outbox pattern).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java`:
    - `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`
    - `@Transactional(propagation = REQUIRES_NEW)`
    - catches exceptions and logs, then continues.
- Minimal probes:
  - Dev-only: force `AccountingService.createJournalEntry(...)` to fail (e.g., locked period, invalid account ID) and verify inventory movement exists but no JE exists.
  - SQL: `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql` and `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/01_orphans_documents_without_journal.sql`.
- What would count as confirmed flaw:
  - Repro shows committed inventory changes with missing JE after the handler logs an error.
- Why tests might still pass:
  - Tests usually run on happy-path configs where accounting posting succeeds; failure modes aren’t asserted.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/20260114T080351Z_inventory_movement_event_search.txt` (no publishers)
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/20260114T080403Z_orphans_movements_without_journal.txt` (legacy PACKAGING rows tied to LF-016)

---

## LEAD-007 — Raw material batch codes appear non-unique (FIFO/traceability ambiguity)

- Status: **CONFIRMED → LF-020**

- Hypothesis:
  - The system allows multiple RM batches with the same `batch_code`, weakening auditability and potentially confusing FIFO consumption and operator workflows.
- Why this matters (ERP expectation):
  - Batch identifiers should be unique per item per company (at least), so auditors can trace which batch was consumed.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialBatch.java`:
    - `@Table(name="raw_material_batches")` with `@Column(name="batch_code", nullable=false)` but no uniqueness annotation.
  - DB contract should be verified in Flyway migrations (confirm whether a unique index exists).
- Minimal probes:
  - SQL: inspect constraints/indexes for `raw_material_batches` and attempt (dev-only) to create a duplicate `batch_code`.
- What would count as confirmed flaw:
  - Duplicate `batch_code` rows exist for the same company/material and downstream consumption/reporting becomes ambiguous.
- Why tests might still pass:
  - Seed data likely generates unique codes and does not stress operator-entered duplicates.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080222Z_batch_create_1.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080228Z_batch_create_2.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-007/OUTPUTS/20260114T080250Z_duplicate_batch_codes.txt`

---

## LEAD-008 — Inventory revaluation journals use `LocalDate.now()` (period-close / cutoff risk)

- Status: **CLOSED** — No revaluation event publishers found in current code paths.

- Hypothesis:
  - Inventory revaluation postings ignore the business effective date (or source event date) and always post on “today”, potentially bypassing locked/closed periods or mis-stating cutoff.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java` (`onInventoryValuationChanged(...)` uses `LocalDate.now()` for the journal entry date).
- Minimal probes:
  - Dev-only: backdate a physical count adjustment/revaluation into a prior period and verify journal date is “today”.
- What would count as confirmed flaw:
  - JE entry date does not match the revaluation effective date and posts into a different period.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-008/OUTPUTS/20260114T080355Z_inventory_reval_event_search.txt`

---

## LEAD-009 — AR/AP reconciliation depends on account code substrings (configuration “footgun”)

- Status: **CLOSED** — Current COA uses AR/AP code conventions; reconciliation aligns with configured accounts.

- Hypothesis:
  - Reconciliation chooses AR/AP accounts by `Account.code` containing “AR/RECEIVABLE” and “AP/PAYABLE”. If a company’s COA uses different code conventions, reconciliation becomes misleading.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/ReconciliationService.java`:
    - AR filters: `code contains ("AR" or "RECEIVABLE")`
    - AP filters: `code contains ("AP" or "PAYABLE")`
- Minimal probes:
  - Dev-only: create a COA where AR/AP control accounts do not match these substrings and observe reconciliation behavior (expected: explicit config-driven selection; current: implicit substring).
- What would count as confirmed flaw:
  - Reconciliation reports “reconciled” while ignoring the true control accounts, or reports variance due purely to code naming rather than balance.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/20260114T080530Z_ar_ap_accounts.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-009/OUTPUTS/20260114T080539Z_reconciliation_service_ar_filter_excerpt.txt`

---

## LEAD-010 — Sales order idempotency key not enforced at DB (concurrency duplicates possible)

- Status: NOT CONFIRMED. Duplicate attempt returned a duplicate-entry error; only one row exists for the idempotency key.
- Hypothesis:
  - Sales order creation relies on an application-level `findByCompanyAndIdempotencyKey` check without a unique constraint, so concurrent requests can create duplicate orders with the same idempotency key.
- Why this matters (ERP expectation):
  - Idempotency keys should enforce exactly-once creation for externally retried requests; duplicates can double-reserve stock and duplicate downstream postings.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`createOrder` uses `findByCompanyAndIdempotencyKey`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/domain/SalesOrder.java` (`idempotency_key` has no uniqueness constraint).
  - `erp-domain/src/main/resources/db/migration/V55__sales_order_idempotency.sql` (partial unique index on `sales_orders(company_id, idempotency_key)`).
- Minimal probes:
  - SQL: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-01/SQL/06_o2c_idempotency_duplicates.sql`.
  - Dev-only: concurrent POSTs to `/api/v1/sales/orders` with the same `idempotencyKey` and payload.
- What would count as confirmed flaw:
  - Duplicate `sales_orders` rows sharing the same `idempotency_key` for a company.
- Evidence collected:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-01/OUTPUTS/20260112T130652Z_lead10_order_resp_1.json` (duplicate entry error).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-01/OUTPUTS/20260112T130652Z_lead10_sales_orders_by_idempotency.txt` (single row).

---

## LEAD-011 — Purchase return idempotency relies on optional reference (retry duplicates possible)

- Status: CONFIRMED → `LF-010` (duplicate returns created when `referenceNumber` omitted).
- Hypothesis:
  - Purchase return requests without a stable `referenceNumber` generate a new reference on each call, allowing duplicate journals and movements on retries.
- Why this matters (ERP expectation):
  - Return postings must be exactly-once; retries should not duplicate inventory and AP reversals.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/service/PurchasingService.java` (`recordPurchaseReturn` chooses generated reference when `referenceNumber` absent).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postPurchaseReturn` idempotency keyed by reference).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/dto/PurchaseReturnRequest.java` (`referenceNumber` is optional).
- Minimal probes:
  - Dev-only: submit the same purchase return payload twice without `referenceNumber` and compare resulting `journal_entries` and `raw_material_movements`.
  - SQL: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/SQL/04_p2p_settlement_idempotency_duplicates.sql` (for duplicate idempotency signal) plus a targeted purchase return reference scan if needed.
- What would count as confirmed flaw:
  - Duplicate purchase return journals/movements created from a retry without a stable reference.
- Evidence collected:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_return_resp_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_return_resp_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_journals_for_returns.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-02/OUTPUTS/20260112T130652Z_lead11_movements_for_returns.txt`

---

## LEAD-012 — Production WIP postings unverified (no production logs)

- Status: **CLOSED → LF-012**

- Hypothesis:
  - WIP postings for production logs may drift (especially when labor/overhead are present), but the BBP dataset has no production logs to validate the posting chain.
- Why this matters (ERP expectation):
  - Production WIP should tie out between RM consumption, semi-finished receipt, packing, and wastage journals.
- Evidence gap:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260112T153126Z_09_production_gets.txt` shows zero production logs.
  - Task-04 SQL probes return zero rows due to missing production data.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java` (material issue + semi-finished journals).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java` (WIP -> FG and wastage journals).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postMaterialConsumption`).
- Next probes:
  - Seed a production log with non-zero labor/overhead and at least one packing record.
  - Re-run task-04 SQL probes to confirm WIP debit/credit alignment and journal linkage.

- Closure evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072603Z_02_production_wip_debit_credit_delta.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072933Z_11_production_journal_lines.txt`

---

## LEAD-013 — GST return blocked by missing tax account config

- Status: **CLOSED → LF-011**

- Hypothesis:
  - BBP company lacks GST input/output tax account configuration, preventing GST return generation and masking tax/reporting checks.
- Why this matters (ERP expectation):
  - GST/VAT reporting should be available once configured; missing config should be surfaced as a setup blocker with clear remediation.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260112T153335Z_05_tax_reports_gets.txt` shows `GST tax accounts not configured for company BBP`.
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260112T153335Z_03_gst_return_journal_snapshot.txt` shows zero output/input tax (no configured accounts).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/CompanyAccountingSettingsService.java` (`requireTaxAccounts`).
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java` (`generateGstReturn`).
- Next probes:
  - Configure GST input/output accounts for BBP and rerun task-05 SQL + `/api/v1/accounting/gst/return`.
  - Compare GST return output to journal lines for the same period.

- Closure evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260113T073400Z_tax_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-05/OUTPUTS/20260113T073455Z_03_company_tax_accounts.txt`

## LEAD-014 — Actuator health endpoint only available on management port (app port returns 404)

- Hypothesis:
  - Ops probes that target the app port for `/actuator/health` return 404 in prod profile because management endpoints are bound to the management port.
- Why this matters (ERP expectation):
  - Health probes should be reliable; using the wrong port can mask readiness issues or trigger false alarms.
- Evidence:
  - `/actuator/health` on management port returns `UP`:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092137Z_health_management.txt`
  - `/actuator/health` on app port returns 404:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092141Z_health_app.txt`
  - Prod config binds management endpoints to a separate port:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092202Z_application_prod_yml_management.txt`
  - Compose runs `SPRING_PROFILES_ACTIVE=prod` and maps `MANAGEMENT_PORT`:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092533Z_docker_compose_management.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-014/OUTPUTS/20260113T092547Z_docker_compose_profiles.txt`
- Disposition:
  - **CLOSED** — Expected behavior: prod profile binds actuator endpoints to the management port; app-port 404 is correct. Ops checks should target the management port.

## LEAD-015 — Production log detail endpoint fails with lazy-load error

- Hypothesis:
  - `GET /api/v1/factory/production/logs` and log detail can fail with `LazyInitializationException` when `ProductionLogService.toDetailDto` accesses `ProductionBrand` outside a session.
- Why this matters (ERP expectation):
  - Operators need to retrieve production logs for audit; 500s block reconciliation and packing audits.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095856Z_production_logs_list.txt` (HTTP 500).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095920Z_production_logs_detail.txt` (HTTP 500).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-015/OUTPUTS/20260113T095904Z_erp_domain_app_logs.txt` (LazyInitializationException on ProductionBrand).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java` (`recentLogs`, `getLog`).
- Disposition:
  - **CONFIRMED → LF-015** — fixed with transactional boundary + regression test.

---

## LEAD-016 — Admin override does not bypass locked period posting

- Hypothesis:
  - `adminOverride=true` on journal entry posting does not allow posting into a locked period; only reopening the period enables posting.
- Why this matters (ERP expectation):
  - If policy requires an authorized override path (auditable), the current behavior may be stricter than expected and block emergency adjustments.
- Evidence:
  - Locked period rejects posting with `adminOverride=true`:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092350Z_journal_locked_override_response.json`
  - Open period accepts posting with `adminOverride=true` (control):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092356Z_journal_open_override_response.json`
  - Lock/reopen actions:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092343Z_period_lock_response.json`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092403Z_period_reopen_response.json`
  - Code anchors show `requireOpenPeriod` enforces OPEN regardless of override:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092627Z_accounting_service_period_check.txt`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-016/OUTPUTS/20260113T092631Z_accounting_period_require_open.txt`
- Disposition:
  - **CLOSED** — Expected control: locked periods require reopen; admin override only relaxes entry-date constraints, not period lock enforcement.

## LEAD-017 — Unpacked batches endpoint fails with lazy-load error

- Status: **CONFIRMED → LF-018**

- Hypothesis:
  - `GET /api/v1/factory/unpacked-batches` can fail with `LazyInitializationException` when `PackingService.listUnpackedBatches` accesses `ProductionProduct` outside a session.
- Why this matters (ERP expectation):
  - Operators rely on the unpacked-batches queue for packing workflows; 500s block packing and downstream stock updates.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075446Z_unpacked_batches_get.txt` (HTTP 500).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/OUTPUTS/20260114T075453Z_app_logs.txt` (LazyInitializationException on ProductionProduct).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java:190`

---

## LEAD-018 — Inventory reconciliation variance (ledger vs valuation)

- Hypothesis:
  - Inventory reconciliation is materially out of balance because the inventory control account is not being updated for seeded/opening inventory or because movements are not linked to journals (ledger balance far below valuation).
- Why this matters (ERP expectation):
  - Month-end close expects inventory valuation to reconcile to the inventory control account within tolerance; a large variance blocks close and erodes auditability.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075752Z_07_inventory_control_vs_valuation.txt` (inventory value 9183 vs ledger 53; variance 9130).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075408Z_01_accounting_reports_gets.txt` (inventory reconciliation report variance 9130).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T075752Z_06_inventory_valuation_fifo.txt` (FIFO valuation total 9183, matches report).
- Next probes:
  - Inspect inventory control account balance source (`companies.default_inventory_account_id`) and list journals impacting it for the period.
  - Cross-check opening stock imports and movement↔journal links for any inventory value not posted to GL.

---

## LEAD-COST-001 — Bulk packing writes child RECEIPTs but misses bulk ISSUE and journal linkage

- Status: **CONFIRMED → LF-016**

- Hypothesis:
  - BulkPackingService records child FG RECEIPT movements but does not record the corresponding bulk ISSUE movement, and does not link movements to the created journal entry.
- Why this matters (ERP expectation):
  - Bulk-to-size conversion must be fully traceable: bulk stock decreases must have an auditable movement; and movements should link to the financial posting journal.
- Evidence:
  - Code anchors:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java`
      - `pack(...)`: deducts bulk batch quantity + bulk FG stock directly (no ISSUE `inventory_movements` write).
      - `createChildBatch(...)`: writes RECEIPT `inventory_movements` (`reference_type='PACKAGING'`, `reference_id='PACK-'||parent_batch_code`) with no `journal_entry_id`.
      - `postPackagingJournal(...)`: posts journal entry and returns `journal.id()` without linking movements.
  - SQL outputs (BBP company_id=5):
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_01_bulk_pack_child_receipts_missing_journal.txt` (child RECEIPT movements with `journal_entry_id` NULL)
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_02_bulk_pack_missing_bulk_issue_movement.txt` (0 ISSUE movements for the semantic pack reference)
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_04_bulk_pack_movements_vs_journals_linkage.txt` (journals exist but no movement linkage)
  - API probe:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105824Z_bulk_pack_response_1.json`
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105856Z_bulk_pack_response_2.json`
- Fix implemented (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`

---

## LEAD-COST-002 — Bulk packing journal reference is non-deterministic (duplicates on retry)

- Status: **CONFIRMED → LF-017**

- Hypothesis:
  - BulkPackingService uses `System.currentTimeMillis()` in journal references, allowing duplicate journals on retry.
- Evidence:
  - Code anchor:
    - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/BulkPackingService.java`
      - `postPackagingJournal(...)`: `reference = "PACK-" + bulkBatch.getBatchCode() + "-" + System.currentTimeMillis()`
- SQL:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105920Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T110014Z_sql_07_bulk_pack_recent_journals.txt`
- Fix implemented (Phase 5):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120701Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_07_bulk_pack_recent_journals_after_fix.txt`

---

## LEAD-COST-005 — Wastage valuation journal uses material-only cost (labor/overhead excluded)

- Status: **CLOSED (as-built costing policy is material-only)**

- Hypothesis:
  - Wastage valuation journal uses only material cost and omits labor/overhead.
- Evidence / rationale:
  - `ProductionLogService` sets `postingCost = materialCost` and `unit_cost = postingCost / mixedQty` (labor/overhead are stored on the log but not capitalized into inventory valuation).
  - `PackingService.postCompletionEntries(...)` computes wastage value using `materialUnitCost` (derived from material_cost_total / mixed_quantity).
  - SQL snapshot shows `logged_unit_cost == material_unit_cost` for the most recent wastage log:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T105157Z_sql_05_wastage_journal_value_vs_cost_components.txt`
