# Epic 04 — Procure-to-Pay (Purchasing + Raw Materials + AP + Payments)

## Objective
Make purchasing and AP fully correct and reconciled:
- raw material purchases/intake update stock correctly
- AP ledger entries and supplier settlements allocate correctly
- payments reduce AP correctly and are auditable/reversible
- GST/tax handling is consistent and traceable

## Scope guard (no new features)
- Use existing purchasing/AP flows; only fix correctness, linkage, reconciliation, and performance gaps.
- Preserve auditability and period rules; prefer forward-only migrations for integrity/index fixes.

## Dependencies / parallel work
- Depends on accounting core rules (Epic 01) and invariants harness (Epic 00).
- Can proceed in parallel with sales/production/payroll once posting contract is stable.

## Likely touch points (exact)
- Purchasing: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing/**`
- Inventory (raw materials): `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`
- Accounting/AP: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- Reports: `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/**`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/**`

## Step-by-step implementation plan
1) Define the canonical P2P flow and required documents:
   - supplier → purchase → intake (batches) → settlement → payment.
2) Verify inventory correctness for raw materials:
   - intake increases stock; returns decrease; adjustments are tracked.
3) Verify accounting correctness:
   - postings hit correct accounts (inventory/expense, AP, taxes) and are linked to the purchase/intake documents.
4) Ensure AP subledger behavior:
   - supplier statement and AP aging match journal activity.
5) Add/strengthen E2E tests for the full P2P happy path plus one reversal/return scenario.
6) Performance pass on supplier list/search, aging, and purchase history endpoints.

## Acceptance criteria
- A P2P golden scenario produces correct raw material stock and correct AP ledger/journals.
- Supplier statements/aging reconcile to AP control accounts.
- Linking and reversibility behave correctly (within period rules).

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Purchasing* test`
