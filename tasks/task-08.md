# Epic 08 — Reconciliation & Controls (Inventory ↔ GL, AR/AP ↔ Control Accounts)

## Objective
Ensure the ERP can detect and prevent “silent drift”:
- inventory valuation reconciles to GL inventory control accounts
- AR/AP subledgers reconcile to control accounts
- period close checks catch missing/unbalanced postings

## Scope guard (no new features)
- Use existing reports/ledgers; only fix reconciliation correctness and add invariant checks.
- Prefer explainable variance output and test coverage over new business workflows.

## Dependencies / parallel work
- Depends on the accounting and domain epics producing linked journals and stable reports.

## Likely touch points (exact)
- Reports: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/**`
- Accounting: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- Inventory: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/fullcycle/**`

## Step-by-step implementation plan
1) Define the reconciliation contracts (what “must match”):
   - inventory valuation report vs GL inventory accounts
   - dealer ledger/aging vs AR control
   - supplier ledger/aging vs AP control
2) Implement or harden reconciliation endpoints/reports:
   - ensure they are company-scoped, date-scoped, and explain variances.
3) Add “closing checklist” assertions:
   - no unposted documents
   - no unlinked journals
   - no unbalanced journals
4) Add automated reconciliation tests on seeded golden scenarios.

## Acceptance criteria
- Reconciliation endpoints return zero variance for golden scenarios (or documented acceptable tolerances).
- Period close checklist catches missing links and unbalanced postings before close/lock.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
