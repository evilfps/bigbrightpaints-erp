# Fix Taskpack — Aged Debtors Must Use Outstanding Amounts

Confirmed flaw: **LF-002**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Correct the aged debtors report to age **outstanding** receivables, not original invoice totals.

## ERP expectation (what “correct” means)
- Aging buckets represent the current collectible balance (after receipts/allocations/credit notes/write-offs).
- Aging total ties to AR control (within tolerance) and to the dealer subledger (within tolerance), for the same cutoff date.

## Primary evidence (baseline + after)
- API:
  - Create invoice → settle partially → call aged debtors endpoint and verify bucket changes.
  - Use `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh` (where it includes aged debtors) or call the specific report endpoint directly.
- SQL:
  - Extend `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/05_ar_ap_tieouts.sql` usage with an invoice-level outstanding vs aging comparison if needed.

## Milestones (implementation plan)
### M1 — Define the aging “source of truth”
- Decide and document whether aged debtors should be based on:
  - `Invoice.outstanding_amount` (document field), or
  - dealer ledger derived outstanding, or
  - a hybrid (prefer ledger if present).
- Document in `erp-domain/docs/RECONCILIATION_CONTRACTS.md`.

### M2 — Add a targeted regression test
- Add an integration test:
  - issue invoice for X
  - record settlement for Y
  - assert aged debtors total decreases by Y and matches invoice outstanding.

### M3 — Implement report correction
- Update `ReportService.agedDebtors()` to use the defined source-of-truth outstanding amount.
- Ensure null/rounding handling is consistent with other reports.

### M4 — Evidence + tie-outs
- Re-run aged debtors and confirm it matches outstanding at dealer level and total level.
- Capture outputs under `docs/ops_and_debug/LOGS/` and reference in `docs/ops_and_debug/EVIDENCE.md`.

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-002 is eliminated: aged debtors changes when invoices are settled and matches outstanding balances.
- A focused test fails pre-fix and passes post-fix.
- Aged debtors total ties to AR control + dealer ledger within tolerance for a golden scenario.

