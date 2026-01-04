# Epic 02 — Order-to-Cash (Sales → Dispatch → Invoice → Receipts → AR)

## Objective
Make the full sales lifecycle safe and ERP-correct:
- sales orders and idempotency are correct
- dispatch mutates inventory exactly once (no double-posting)
- invoicing produces correct journals and AR ledger entries
- receipts/settlements reduce AR correctly
- everything is traceable and reversible (where supported)

## Scope guard (no new features)
- Use existing sales/dispatch/invoice flows; only fix correctness, linkage, and idempotency issues.
- Do not change accounting semantics without invariant tests proving behavior.

## Dependencies / parallel work
- Depends on accounting invariants (Epic 01) and test harness (Epic 00) to prove correctness.
- Can proceed in parallel with production/purchasing/payroll, but must not break the shared posting contract.

## Likely touch points (exact)
- Sales: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/**`
- Inventory dispatch + finished goods: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`
- Invoicing: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/**`
- Accounting integration: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/sales/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/fullcycle/**`

## Step-by-step implementation plan
1) Document the state machines and invariants:
   - order statuses, dispatch states, invoice states, payment/settlement states.
2) Verify linking across entities:
   - order ↔ dispatch/packaging slip ↔ invoice ↔ journal entry ↔ dealer ledger.
3) Audit idempotency/retry safety:
   - confirm endpoints and background jobs don’t double-apply inventory/journals.
4) Lock in totals/rounding rules used by:
   - order totals, invoice totals, tax totals, and posted journal line amounts.
5) Add/strengthen golden-path E2E tests:
   - create dealer → create order → confirm → dispatch confirm → invoice → receipt/settlement.
   - assert: stock changes, AR ledger movement, journals balanced + linked.
6) Add reversal/exception coverage:
   - invoice reverse/cascade reverse
   - sales return or dispatch rollback flow (depending on what the system supports)

## Acceptance criteria
- For a canonical O2C scenario, the system produces:
  - correct inventory movements (no negative stock; no duplicate dispatch)
  - correct journals (balanced, linked, reversible)
  - correct AR aging/statement output for the dealer
- Idempotency is proven by repeating the same request (or retry simulation) without double-posting.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Sales* test`
