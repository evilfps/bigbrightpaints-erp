# ERP LOGIC AUDIT PROGRAM REPORT

This folder contains **discovery + planning artifacts only** (no behavioral changes).

## Entry points
- `tasks/erp_logic_audit/AS_BUILT_ERP_SPEC.md` (Phase 0 gate: as-built spec)
- `tasks/erp_logic_audit/HUNT_PROGRAM.md` (Phase 1: flaw hunting framework)
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/README.md` (Phase 2: read-only probes)
- `tasks/erp_logic_audit/taskpack_investigation/` (Phase 3: investigation tasks)
- `tasks/erp_logic_audit/LOGIC_FLAWS.md` (Phase 4: confirmed flaws only)
- `tasks/erp_logic_audit/taskpack_fixes/` (Phase 5: fix taskpacks for confirmed flaws)
- `tasks/erp_logic_audit/FINDINGS_INDEX.md` (sortable views / index)
- `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` (unconfirmed leads + probes)

## Run metadata (this audit run)
- Repo: `CLI_BACKEND_epic04`
- Branch: `fix-phase5-lead015-and-lf011-014`
- HEAD SHA: `15a7380`
- Git status: **DIRTY** (untracked logs under `docs/ops_and_debug/LOGS/` + workspace artifacts like `interview/`; do not delete)

## Investigation run report (Task-03/06 large-data reconciliation)
- Task-03 SQL + accounting reports GETs re-run after dev-profile seed; inventory valuation totals 9203 while inventory control ledger balance is 68 (variance 9135) → **LEAD-018 confirmed → LF-021**.
- Orphan movement check returned FG RECEIPT movements missing `journal_entry_id` for PACKAGING references (consistent with LF-003 / prior bulk-pack linkage gap).
- Task-06 SQL probes returned no orphan documents or period integrity violations; month-end checklist + periods remain OPEN.
- Evidence: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090230Z_sql_07_inventory_control_vs_valuation.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T090237Z_accounting_reports_gets.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260114T075416Z_sql_period_integrity.txt`.

## Investigation run report (LEAD-010/011 evidence + Task-03)
- Executed Task-01/02 RUN.md probes for BBP (company_id=5), plus targeted retries for LEAD-010/011.
- LEAD-010 not confirmed: duplicate sales-order submission returned a duplicate-entry error; only one `sales_orders` row exists for the idempotency key.
- LEAD-011 confirmed and promoted to **LF-010**: retrying purchase return without `referenceNumber` produced two posted returns (duplicate journals + movements).
- Task-03 inventory valuation + COGS probes executed (SQL + accounting reports GETs); no new anomalies detected in BBP dataset.
- Recommended next investigation: `tasks/erp_logic_audit/taskpack_investigation/task-04-production-costing-wip-hunt.md`.

## Investigation run report (Task-04 + Task-05)
- Task-04: seeded production chain (WIP + discount defaults, FG product, production log with labor/overhead, packing); SQL/GET probes confirmed LF-012 (WIP over-credit), LF-013 (status stale after packing), LF-014 (FG creation 500 when discount default missing).
- Task-05: tax/rounding probes showed no invoice/journal mismatches; config health reported healthy while GST return failed with GST accounts unset → LF-011.
- New lead logged: LEAD-015 (production log list/detail 500 due to lazy-load).
- Recommended next investigation: `tasks/erp_logic_audit/taskpack_investigation/task-06-period-close-adjustments-hunt.md` and `tasks/erp_logic_audit/taskpack_investigation/task-09-ops-failure-modes-hunt.md`.

## Investigation run report (Task-09 + Task-06)
- Task-09: ops failure-mode probes (health + outbox + drift SQL) executed; no new LFs. LEAD-014 added for actuator health app-port 404 vs management-port health.
- Task-06: period lock/close probes executed (SQL + checklist + controlled POSTs). Posting into locked period blocked with/without override; close without force blocked by checklist. LEAD-016 added to confirm policy on admin override vs reopen.
- Evidence: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-09/OUTPUTS/20260113T082939Z_actuator_health.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-06/OUTPUTS/20260113T084648Z_period_lock_response.json`.

## Investigation run report (Task-08 idempotency stress)
- Parallel idempotency probes executed for sales orders, payroll runs, purchase returns, and bulk pack (MOCK company).
- Sales order + payroll run duplicate posts returned 409; conflicting payloads returned existing records (no rejection) → confirmed **LF-023**.
- Purchase return retries with same reference reused journal entry but still reduced RM stock on replay → confirmed **LF-022**.
- Bulk pack idempotency OK (single ISSUE/RECEIPT in movement check).
- Evidence: `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090838Z_sales_order_conflict_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090855Z_payroll_run_conflict_response.json`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090944Z_sql_purchase_return_reference.txt`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T090938Z_sql_raw_material_stock_after_return_2.txt`.

## Phase 5 fix run report (LEAD-015 + LF-011..LF-014)
- LEAD-015 confirmed and promoted to **LF-015**; list/detail endpoints fixed with transactional boundaries + regression test.
- LF-011..LF-014 fixes implemented with regression tests and evidence harnesses under `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-0xx/`.
- New lead logged: LEAD-017 (unpacked-batches endpoint 500 due to lazy-loaded product).
- Verification gates: `mvn -f erp-domain/pom.xml -DskipTests compile` (pass), `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29651 warnings), `mvn -f erp-domain/pom.xml test` (pass; 220 tests, 4 skipped).

## Investigation run report (Costing LEADs)
- Scope: LEAD-COST-001, LEAD-COST-002, LEAD-COST-005 only (investigation; no production logic changes).
- Evidence folder: `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/`.
- LEAD-COST-001 confirmed and promoted to **LF-016** (bulk pack missing bulk ISSUE movement + missing movement↔journal linkage).
- LEAD-COST-002 confirmed and promoted to **LF-017** (timestamp-based journal reference; retries duplicate POSTED journals).
- LEAD-COST-005 closed (as-built costing is material-only; wastage valuation aligns to `unit_cost`).

## Phase 5 fix run report (LF-016/LF-017)
- Deterministic pack reference + idempotent guard + movement↔journal linkage implemented in `BulkPackingService`.
- Regression test added: `erp-domain/src/test/java/com/bigbrightpaints/erp/regression/BulkPackMovementIdempotencyRegressionIT.java`.
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120643Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120701Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260113T120827Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_1.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084750Z_bulk_pack_after_fix_response_2.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084816Z_sql_08_bulk_pack_reference_lookup_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084831Z_sql_01_bulk_pack_child_receipts_missing_journal_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_02_bulk_pack_missing_bulk_issue_movement_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_03_bulk_pack_journal_duplicates_by_semantic_reference_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_04_bulk_pack_movements_vs_journals_linkage_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_07_bulk_pack_recent_journals_after_fix.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/costing/OUTPUTS/20260114T084832Z_sql_08_bulk_pack_movements_by_type_after_fix.txt`

## Phase 5 fix run report (LF-021/LF-022/LF-023)
- LF-021: opening stock import now posts OPEN-STOCK journal + links movement journal_entry_id; OPEN-BAL created if missing.
- LF-022: purchase return reference reuse now reuses movements + rejects conflicting payloads; movements link to journal.
- LF-023: sales order + payroll run now hash idempotency payloads and reject conflicts (HTTP 409).
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105316Z_sql_07_inventory_control_vs_valuation.txt` (BBP seed variance persists; backfill needed).
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-03/OUTPUTS/20260114T105325Z_accounting_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105215Z_sql_purchase_return_reference.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105208Z_sql_raw_material_stock_after_return_2.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105052Z_sales_order_conflict_response.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/task-08/OUTPUTS/20260114T105110Z_payroll_run_conflict_response.json`
- Verification gates: `mvn -f erp-domain/pom.xml -DskipTests compile` (pass; javax.annotation.meta.When warnings), `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29910 warnings), `mvn -f erp-domain/pom.xml test` (pass; 224 tests, 4 skipped).
- Verification gates rerun (2026-01-14): `mvn -f erp-domain/pom.xml -DskipTests compile` (pass), `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29651 warnings), `mvn -f erp-domain/pom.xml test` (pass; 220 tests, 4 skipped).

## Phase 5 fix run report (LF-001/LF-002/LF-006)
- LF-001: balance sheet + profit/loss now normalize credit-normal balances; equity derived from normalized assets minus liabilities.
- LF-002: aged debtors buckets now use invoice outstanding amounts.
- LF-006: AP reconciliation now normalizes GL liabilities by account type before tie-out.
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-001/OUTPUTS/20260114T113219Z_accounting_reports_gets.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113128Z_seed_invoice_for_aging.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-002/OUTPUTS/20260114T113227Z_aged_debtors_get.json`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-006/OUTPUTS/20260114T113234Z_sql_05_ar_ap_tieouts_after_fix.txt`
- Verification gates: `mvn -f erp-domain/pom.xml -DskipTests compile` (pass; javax.annotation.meta.When warnings), `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check` (pass; 29919 warnings), `mvn -f erp-domain/pom.xml test` (pass; 226 tests, 4 skipped).

## Phase 5 fix run report (LF-007/LF-008/LF-009)
- LF-007: payroll idempotency is now company-scoped (removed global uniqueness); regression test added.
- LF-008: orchestrator traces now persist `company_id` and trace reads enforce company scope + role; regression test added.
- LF-009: settlement idempotency index widened to allow multi-allocation under one key (partial uniques + lookup index); regression test added.
- Evidence outputs:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-007/OUTPUTS/20260116T092613Z_payroll_idempotency_cross_company.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/20260116T092619Z_orchestrator_audit_schema.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-008/OUTPUTS/20260116T092706Z_orchestrator_trace_company_scope.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/20260116T092713Z_settlement_idempotency_indexes.txt`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/lf-009/OUTPUTS/20260116T092713Z_settlement_multi_alloc_idempotency.txt`
- Verification gates: `mvn -f erp-domain/pom.xml test` (pass; 233 tests, 4 skipped).

## Lead closure sweep (LEAD-001..009, LEAD-017)
- LEAD-001 closed (invoice/purchase creation paths always set outstanding to total).
- LEAD-002 closed (over-issue rejected; stock unchanged).
- LEAD-003 closed (dispatch confirm guarded once slip is DISPATCHED).
- LEAD-004 confirmed → **LF-019** (payroll PF deduction ignored in run/posting).
- LEAD-005 closed (no cross-company journal-line mismatches).
- LEAD-006 closed (no inventory movement event publishers; orphans trace to packaging scope).
- LEAD-007 confirmed → **LF-020** (duplicate raw material batch codes allowed).
- LEAD-008 closed (no inventory revaluation event publishers).
- LEAD-009 closed (COA code convention matches reconciliation substring filters).
- LEAD-017 confirmed → **LF-018** (unpacked-batches endpoint lazy-load 500).
- Evidence: `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-001/` ... `lead-009/`, `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-017/`.

## AS-BUILT coverage summary (Phase 0 gate)
- Portals/actors mapped: Admin, Accounting, Sales, Manufacturing/Factory, Dealer.
- Core objects/IDs mapped: COA/accounts, journals/lines, dealer/supplier ledgers, inventory masters/batches/movements, orders/slips/invoices, purchases, production logs/packing, payroll runs, periods/checklist/reconciliation.
- Chain-of-evidence described (as-built + expected): document → movement(s) → journal entry → ledger/subledger → reconciliation → close/lock.
- Canonical workflows documented (as-built): O2C, P2P, Production, Payroll, Close/Lock, Onboarding.
- “Truth for reports” documented (what tables/services each report uses) with known mismatches called out in confirmed flaws.

## Confirmed logic flaws (Phase 4: LF items only)
Source: `tasks/erp_logic_audit/LOGIC_FLAWS.md`

**HIGH severity**
- LF-001 — Financial statements not sign-normalized; equity calc inconsistent with stored sign conventions (fixed Phase 5).
- LF-002 — Aged debtors ignores settlements (uses invoice total, not outstanding) (fixed Phase 5).
- LF-003 — Finished-goods inventory movements never carry `journal_entry_id` (audit chain break).
- LF-004 — FG valuation mixes `current_stock` with FIFO slices of `quantity_available` (reserved stock misvaluation).
- LF-005 — Opening stock import updates inventory without a GL opening entry (systematic inventory↔GL drift).
- LF-006 — AP reconciliation compares signed GL liabilities vs positive supplier ledger (sign mismatch) (fixed Phase 5).
- LF-019 — Payroll PF deduction ignored in payroll run/posting.
- LF-016 — Bulk-to-size packing missing bulk ISSUE movement + movement↔journal linkage (fixed Phase 5).
- LF-017 — Bulk-to-size packing journals duplicate on retry (timestamp-based reference) (fixed Phase 5).
- LF-021 — Inventory control ledger does not reconcile to inventory valuation (fixed Phase 5; backfill pending).

**MED severity**
- LF-007 — Payroll run `idempotency_key` is globally unique (cross-company collision risk) (fixed Phase 5).
- LF-008 — Orchestrator trace endpoint not company-scoped (trace leak) (fixed Phase 5).
- LF-009 — Settlement idempotency key uniqueness blocks multi-allocation settlements (fixed Phase 5).
- LF-010 — Purchase return retries without reference duplicate journals/movements.
- LF-011 — Config health ignores missing GST accounts; GST return fails (fixed Phase 5).
- LF-012 — WIP over-credited when labor/overhead included on production logs (fixed Phase 5).
- LF-013 — Production log status remains READY_TO_PACK after full packing (fixed Phase 5).
- LF-014 — Finished-good creation 500s when default discount account unset (fixed Phase 5).
- LF-015 — Production log list/detail 500s due to lazy-load on brand/product (fixed Phase 5).
- LF-018 — Unpacked-batches endpoint 500 due to lazy-load.
- LF-020 — Raw material batch codes not enforced unique.
- LF-022 — Purchase return reference reuse duplicates RM movements (fixed Phase 5).
- LF-023 — Idempotency key conflict accepted (sales order + payroll) (fixed Phase 5).

Top “HIGH” list: currently 4 items (LF-003, LF-004, LF-005, LF-019).

## Leads pending confirmation (not yet LF items)
Source: `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`
- None (all current leads triaged).

## Investigation taskpack (Phase 3)
- Count: **9** tasks under `tasks/erp_logic_audit/taskpack_investigation/`.
- Recommended order (highest risk first):
  1) `tasks/erp_logic_audit/taskpack_investigation/task-07-tenancy-rbac-portal-entrypoints-hunt.md`
  2) `tasks/erp_logic_audit/taskpack_investigation/task-08-idempotency-retry-duplication-hunt.md`
  3) `tasks/erp_logic_audit/taskpack_investigation/task-01-o2c-logic-hunt.md`
  4) `tasks/erp_logic_audit/taskpack_investigation/task-02-p2p-logic-hunt.md`
  5) `tasks/erp_logic_audit/taskpack_investigation/task-03-inventory-valuation-cogs-hunt.md`
  6) `tasks/erp_logic_audit/taskpack_investigation/task-04-production-costing-wip-hunt.md`
  7) `tasks/erp_logic_audit/taskpack_investigation/task-05-tax-rounding-and-reporting-hunt.md`
  8) `tasks/erp_logic_audit/taskpack_investigation/task-06-period-close-adjustments-hunt.md`
  9) `tasks/erp_logic_audit/taskpack_investigation/task-09-ops-failure-modes-hunt.md`
- Phase 3 status: Task-01..Task-09 completed in current audit thread.

## Fix taskpack (Phase 5; planning only)
- Count: **6** tasks under `tasks/erp_logic_audit/taskpack_fixes/`.
- Recommended order:
  1) `tasks/erp_logic_audit/taskpack_fixes/task-fix-01-sign-conventions-reports-and-reconciliation.md`
  2) `tasks/erp_logic_audit/taskpack_fixes/task-fix-02-aged-debtors-uses-outstanding.md`
  3) `tasks/erp_logic_audit/taskpack_fixes/task-fix-05-opening-stock-posting-policy.md`
  4) `tasks/erp_logic_audit/taskpack_fixes/task-fix-03-fg-movement-to-journal-linkage.md`
  5) `tasks/erp_logic_audit/taskpack_fixes/task-fix-04-fg-valuation-reservations.md`
  6) `tasks/erp_logic_audit/taskpack_fixes/task-fix-06-payroll-idempotency-company-scope.md`

## Blockers / decisions needed
- Missing doc: `erp-domain/docs/ONBOARDING_GUIDE.md` is referenced by predeploy tasks but not present in repo (onboarding contract gap).
- Inventory valuation basis: confirm whether valuation should include reserved stock and which batch quantity field is authoritative (LF-004).
- Movement↔journal linkage policy: decide which journal a movement must link to (COGS vs AR vs both) and backfill expectations (LF-003).
- Opening balances policy: define the GL counterpart of opening stock (equity vs suspense/clearing) and whether import must fail-closed without it (LF-005).
- Multi-company uniqueness guidelines: confirm remaining flows beyond payroll/settlements (LF-007 and broader).
## Latest audit tip
- Branch: fix-phase5-lead015-and-lf011-014
- Tip SHA: 05e5a36
- Lead dispositions: LF-007/LF-008/LF-009 fixed (Phase 5; evidence captured 2026-01-16); LF-018 remains open (unpacked-batches lazy-load)
