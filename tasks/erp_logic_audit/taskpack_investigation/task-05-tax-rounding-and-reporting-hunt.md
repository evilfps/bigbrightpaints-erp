# Task 05 — Tax, Rounding, and Reporting Logic Hunt (GST/VAT)

## Scope
- Workflows: sales tax calculation, invoice tax totals, sales journal tax lines, GST return report.
- Portals: Accounting, Sales.
- Modules (primary): `sales`, `invoice`, `accounting`, `reports`.

## ERP expectation
- Invoice tax logic is consistent across:
  - document headers and lines
  - journal postings (tax accounts)
  - GST return report calculations
- Rounding policy is consistent and does not accumulate hidden variance.

## Where to inspect in code
- Order GST calculation:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`mapOrderItems`, GST treatment/inclusive logic)
- Dispatch/invoice tax calculation:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`confirmDispatch`)
- Tax posting:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postSalesJournal`)
- GST return computation:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/TaxService.java`

## Evidence to gather

### SQL probes
- Arithmetic mismatches:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/10_tax_and_totals_variances.sql`

### GET-only API probes
- Reports + GST return:
  - `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

## What counts as a confirmed flaw (LF)
- Document totals are internally inconsistent outside tolerance (and code path shows deterministic arithmetic error).
- GST return mismatches posted tax journals for the same period and configured accounts (outside tolerance).

## Why tests might still pass
- Tests may not cover inclusive tax and discount allocation edge cases.
- Report-level reconciliation is often missing or uses only small fixtures that don’t trigger rounding drift.

## Deliverable
- Confirmed LF items in `tasks/erp_logic_audit/LOGIC_FLAWS.md` with evidence + repro.
- Any policy ambiguity (jurisdiction/rules) recorded as LEADs/questions in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`.

