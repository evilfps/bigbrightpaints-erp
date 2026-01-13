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
| LEAD-014 | LOW? | SRE / Operator | Actuator health on app port 404s (management port required) | Repro with `BASE_URL=8081` and `/actuator/health` 404; validate management port and update ops probes |
| LEAD-015 | MED? | Operator / Auditor | Production log detail endpoint 500s (lazy load) | Repro GET `/api/v1/factory/production/logs`; capture logs + add fetch/transaction probe |

---

## LEAD-001 — Invoice/purchase `outstanding_amount` overwritten to `total_amount` when zero (migration + paid-at-creation risk)

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

---

## LEAD-002 — Raw material stock is “fail-open” (negative clamps to zero), unlike finished goods (throws)

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

---

## LEAD-003 — `/api/v1/dispatch/confirm` appears to confirm dispatch twice (double side-effect risk)

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

---

## LEAD-004 — Payroll PF deduction appears inconsistent across preview/run/posting (operator truth drift)

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

---

## LEAD-005 — Inventory accounting events use `accountRepository.findById(...)` (potential cross-company account injection)

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

---

## LEAD-006 — Inventory auto-posting is AFTER_COMMIT + REQUIRES_NEW; JE failure leaves inventory committed (drift-by-design)

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

---

## LEAD-007 — Raw material batch codes appear non-unique (FIFO/traceability ambiguity)

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

---

## LEAD-008 — Inventory revaluation journals use `LocalDate.now()` (period-close / cutoff risk)

- Hypothesis:
  - Inventory revaluation postings ignore the business effective date (or source event date) and always post on “today”, potentially bypassing locked/closed periods or mis-stating cutoff.
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/event/InventoryAccountingEventListener.java` (`onInventoryValuationChanged(...)` uses `LocalDate.now()` for the journal entry date).
- Minimal probes:
  - Dev-only: backdate a physical count adjustment/revaluation into a prior period and verify journal date is “today”.
- What would count as confirmed flaw:
  - JE entry date does not match the revaluation effective date and posts into a different period.

---

## LEAD-009 — AR/AP reconciliation depends on account code substrings (configuration “footgun”)

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
  - `/actuator/health` on app port 8081 returns 404 in probe output:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082949Z_health_gets_app_port.txt`
  - `/actuator/health` on management port 19090 returns `UP`:
    - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json`
- Next probes:
  - Call `http://localhost:19090/actuator/health` and `.../readiness` for ops checks.
  - Confirm whether curl probes should accept separate app vs management base URLs.
- What would count as confirmed flaw:
  - Ops scripts/docs hardcode app port for actuator endpoints in prod deployments (causing false health failures).

## LEAD-015 — Production log detail endpoint fails with lazy-load error

- Hypothesis:
  - `GET /api/v1/factory/production/logs` and log detail can fail with `LazyInitializationException` when `ProductionLogService.toDetailDto` accesses `ProductionBrand` outside a session.
- Why this matters (ERP expectation):
  - Operators need to retrieve production logs for audit; 500s block reconciliation and packing audits.
- Evidence:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T073100Z_production_gets.txt` (internal error on list).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-04/OUTPUTS/20260113T072447Z_app_logs_after_get_log.txt` (LazyInitializationException).
- Code anchors:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:264`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java:530`
- Next probes:
  - Fetch join brand/product in repository or wrap `getLog` in a transactional boundary and retry GETs.
