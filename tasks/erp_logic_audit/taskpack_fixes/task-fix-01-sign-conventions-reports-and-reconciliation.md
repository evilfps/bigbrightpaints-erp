# Fix Taskpack — Sign Conventions for Financial Statements + AP Reconciliation

Confirmed flaw cluster: **LF-001 + LF-006**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Normalize sign conventions across:
  - `ReportService.balanceSheet()` and `ReportService.profitLoss()` (LF-001).
  - `ReconciliationService.reconcileApWithSupplierLedger()` (LF-006).
- Ensure the “chain-of-evidence” remains intact: journals → account balances → reports/reconciliation.

## ERP expectation (what “correct” means)
- Stored account balances may be signed by normal balance (debit-normal positive, credit-normal negative), but:
  - Financial statement outputs must be consistent with the chosen reporting convention.
  - Trial balance, balance sheet, P&L, and reconciliation dashboards must agree within tolerance on the same dataset.
- AP reconciliation must compare like-for-like (same sign basis and same population of balances).

## Non-goals
- No COA redesign.
- No UI changes.
- No new business features (only correctness + invariant enforcement).

## Primary evidence (baseline + after)
- Runtime/API:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (trial balance vs balance sheet vs P&L).
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (reconciliation dashboard / month-end checklist endpoints as applicable).
- SQL:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql` (AP tie-out).
  - (Optional) add a focused SQL snippet that compares report totals to trial balance totals for the same period.

## Milestones (implementation plan)
### M1 — Decide and document sign contract (hard gate)
- Decide one canonical internal contract:
  - (A) “Stored signed balances” (credit-normal negative) and reports normalize for display, or
  - (B) “Stored unsigned balances” (store by normal balance), and adjust posting engine accordingly (higher migration risk).
- Document the decision in:
  - `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md` (sign conventions section).
  - `erp-domain/docs/RECONCILIATION_CONTRACTS.md` (tie-out sign basis).

### M2 — Add tests that pin down the contract
- Add an integration test that posts a minimal set of journals (revenue, payable, cash) and asserts:
  - Trial balance totals and columns follow the contract.
  - Balance sheet equation holds under the displayed sign convention.
  - P&L net income matches journal-derived net movement.
- Add a reconciliation test that posts a supplier purchase + payment and asserts AP tie-out passes within tolerance.

### M3 — Implement report + reconciliation normalization
- Update:
  - `ReportService.balanceSheet()` / `profitLoss()` to normalize consistently (per M1 contract).
  - `ReconciliationService.reconcileApWithSupplierLedger()` to compare normalized values and avoid sign inversion variance.
- Ensure any “equity” calculation uses the same basis as the report output.

### M4 — Regression proof (evidence + drift queries)
- Run the evidence scripts and save output (no secrets) under `docs/ops_and_debug/LOGS/`.
- Re-run `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql` on a seeded dataset and confirm AP variance is within tolerance.

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-001: balance sheet + P&L are consistent with trial balance sign conventions on the same dataset.
- LF-006: AP reconciliation compares normalized values; variance is within tolerance on a golden P2P scenario.
- New tests fail on pre-fix code and pass post-fix.
- Evidence outputs captured and linked from `docs/ops_and_debug/EVIDENCE.md`.

