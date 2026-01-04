codex# Epic 00 — Golden Paths + ERP Invariant Test Suite (Foundation)

## Objective
Create a single “truth harness” that proves the ERP works end-to-end: **golden-path E2E scenarios + invariant assertions** (balanced journals, correct links, reversible postings, no negative stock, reconciled subledgers).

## Scope guard (no new features)
- Use existing modules/endpoints/flows; only verify and fix discrepancies.
- Prefer tests/invariants first; any behavior changes must be minimal, auditable, and reversible.

## Dependencies / parallel work
- Recommended to land first. Other epics can be started, but should integrate through these tests.

## Likely touch points (exact)
- Test harness:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/test/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/smoke/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/**`
- Domain assertions (new test helpers):
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/test/support/**`
  - (new) `erp-domain/src/test/java/com/bigbrightpaints/erp/invariants/**`

## Step-by-step implementation plan
1) Inventory existing tests and map them to ERP processes:
   - order-to-cash, procure-to-pay, produce-to-stock, hire-to-pay, record-to-report.
2) Add a reusable invariant assertion library for tests:
   - `assertJournalBalanced(entryId)`
   - `assertJournalLinkedTo(sourceType, sourceId)`
   - `assertReversalCreatesBalancedInverse(originalEntryId)`
   - `assertNoNegativeStock(companyId, sku/productCode)`
   - `assertSubledgerReconciles(controlAccountId, asOfDate)` (AR/AP)
3) Define a minimal canonical dataset builder (company + accounts + defaults):
   - build once per test class (fast), reuse across scenarios.
4) Implement one golden-path scenario per core process (start minimal, expand later):
   - O2C: dealer → order → dispatch → invoice → receipt
   - P2P: supplier → raw material purchase → intake → settlement → payment
   - Production: materials → production log → packing → finished goods stock
   - Payroll: employee → attendance → payroll run → post → mark paid
5) Make scenarios deterministic:
   - pin dates to safe windows (avoid “future date” validation at month start)
   - disable or control schedulers during tests where needed
   - ensure idempotency keys are stable in test calls
6) Add a single “ERP invariants” suite entrypoint (JUnit tag/category) for CI gating.

## Acceptance criteria
- `mvn -f erp-domain/pom.xml test` runs the golden scenarios and fails on any invariant breach.
- At least 1 scenario exists for each core ERP process, even if minimal.
- Tests are deterministic (no time-of-month failures) and runnable under Testcontainers.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*SmokeTest test`
