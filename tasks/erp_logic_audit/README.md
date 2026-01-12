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
- Branch: `audit-inv-01-02`
- HEAD SHA: `a0dfdf97a372fa63be015c9db5fec95e39ccea39`
- Git status: **DIRTY** (untracked logs under `docs/ops_and_debug/LOGS/` + workspace artifacts like `interview/`; do not delete)

## Investigation run report (Task-01 + Task-02)
- Checked O2C chain-of-evidence: orders → slips → movements → invoices → AR + dealer ledger.
- Checked P2P chain-of-evidence: purchases → RM receipts/returns → AP + supplier ledger.
- Confirmed new LFs: none in this run.
- New leads: **LEAD-010** (sales order idempotency key not enforced at DB), **LEAD-011** (purchase return idempotency relies on optional reference).
- Recommended next investigation: `tasks/erp_logic_audit/taskpack_investigation/task-03-inventory-valuation-cogs-hunt.md`.

## AS-BUILT coverage summary (Phase 0 gate)
- Portals/actors mapped: Admin, Accounting, Sales, Manufacturing/Factory, Dealer.
- Core objects/IDs mapped: COA/accounts, journals/lines, dealer/supplier ledgers, inventory masters/batches/movements, orders/slips/invoices, purchases, production logs/packing, payroll runs, periods/checklist/reconciliation.
- Chain-of-evidence described (as-built + expected): document → movement(s) → journal entry → ledger/subledger → reconciliation → close/lock.
- Canonical workflows documented (as-built): O2C, P2P, Production, Payroll, Close/Lock, Onboarding.
- “Truth for reports” documented (what tables/services each report uses) with known mismatches called out in confirmed flaws.

## Confirmed logic flaws (Phase 4: LF items only)
Source: `tasks/erp_logic_audit/LOGIC_FLAWS.md`

**HIGH severity**
- LF-001 — Financial statements not sign-normalized; equity calc inconsistent with stored sign conventions.
- LF-002 — Aged debtors ignores settlements (uses invoice total, not outstanding).
- LF-003 — Finished-goods inventory movements never carry `journal_entry_id` (audit chain break).
- LF-004 — FG valuation mixes `current_stock` with FIFO slices of `quantity_available` (reserved stock misvaluation).
- LF-005 — Opening stock import updates inventory without a GL opening entry (systematic inventory↔GL drift).
- LF-006 — AP reconciliation compares signed GL liabilities vs positive supplier ledger (sign mismatch).

**MED severity**
- LF-007 — Payroll run `idempotency_key` is globally unique (cross-company collision risk).

Top “HIGH” list: currently 6 items (LF-001..LF-006).

## Leads pending confirmation (not yet LF items)
Source: `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`
- LEAD-001..LEAD-009 (outstanding overwrite on create; RM stock clamp; double-dispatch confirm; payroll PF drift; inventory event posting risks; batch code uniqueness; revaluation date; recon code-substring footgun).

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
- Accounting sign convention contract: confirm whether balances are stored signed by normal balance and how reports should present them (LF-001/LF-006).
- Inventory valuation basis: confirm whether valuation should include reserved stock and which batch quantity field is authoritative (LF-004).
- Movement↔journal linkage policy: decide which journal a movement must link to (COGS vs AR vs both) and backfill expectations (LF-003).
- Opening balances policy: define the GL counterpart of opening stock (equity vs suspense/clearing) and whether import must fail-closed without it (LF-005).
- Multi-company uniqueness guidelines: confirm the standard for idempotency keys and reference numbers across companies (LF-007 and broader).
