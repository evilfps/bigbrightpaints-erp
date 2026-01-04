# Epic 05 — Hire-to-Pay (HR/Attendance/Payroll → GL Posting → Payments)

## Objective
Make HR + payroll safe and fully integrated:
- payroll calculations are consistent (preview vs run totals)
- payroll posting to GL is correct, linked, and reversible within period rules
- advances/deductions/benefits are accounted for without hidden drift
- payroll reports/payslips match the posted reality

## Scope guard (no new features)
- Use existing HR/payroll flows; only fix consistency, linkage, posting correctness, and auditability gaps.
- No silent changes to pay/GL semantics; lock behavior with tests first.

## Dependencies / parallel work
- Depends on accounting core rules (Epic 01) and invariants harness (Epic 00).
- Can be worked on in parallel with sales/purchasing/production once posting contract is stable.

## Likely touch points (exact)
- HR/payroll:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/**`
- Accounting integration:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- Templates:
  - `erp-domain/src/main/resources/templates/payroll-sheet.html`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/hr/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**` (payroll scenarios)

## Step-by-step implementation plan
1) Document payroll run states and required invariants:
   - draft → calculated → approved → posted → paid (and any rollback paths).
2) Audit calculation sources of truth:
   - attendance inputs, allowances/deductions, advances, statutory deductions.
3) Make posting semantics explicit:
   - which accounts are debited/credited, how net pay is derived, and how advances are cleared.
4) Add/strengthen E2E tests:
   - create employee → mark attendance → create payroll run → calculate → approve → post → mark paid.
   - assert: journals balanced + linked, payroll totals match accounting totals, employee ledger (if any) matches.
5) Add reversal coverage (where supported):
   - reverse payroll posting and confirm statements return to baseline.
6) Performance pass on payroll list, run lines, and summary endpoints.

## Acceptance criteria
- A payroll golden scenario produces correct totals and correct accounting postings (balanced, linked, reversible).
- No drift between payroll run totals, posted journal amounts, and reports/payslips.
- Payroll posting respects period lock/close rules.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Payroll* test`
