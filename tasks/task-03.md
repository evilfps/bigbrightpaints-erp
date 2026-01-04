# Epic 03 — Plan/Produce-to-Stock (Production + Packaging + Inventory + Costing)

## Objective
Make factory/production flows correct and fully integrated:
- raw material consumption is reflected in inventory and (where applicable) GL/WIP
- packing creates finished goods batches with correct cost basis/valuation
- valuation/COGS postings stay traceable and consistent with movements

## Scope guard (no new features)
- Use existing production/packing/inventory flows; only fix correctness, linkage, and costing/valuation drift.
- Any changes must keep downstream sales/COGS posting behavior consistent and test-proven.

## Dependencies / parallel work
- Depends on accounting posting contract (Epic 01) for any GL integration.
- Can be implemented in parallel with sales/purchasing once invariant tests exist (Epic 00).

## Likely touch points (exact)
- Factory + production:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production/**`
- Inventory/costing:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/production/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/inventory/**`

## Step-by-step implementation plan
1) Map the production model and state transitions:
   - plans → batches → logs → packing records → finished goods batches.
2) Define costing rules and where they are sourced:
   - unit costs on batches, allocation inputs, GST/packaging effects.
3) Verify inventory movement correctness:
   - raw material decreases on consumption
   - finished goods increases on packing completion
   - movements are linked to their source records and (if required) to journal entries.
4) Add/strengthen E2E tests:
   - create raw materials + stock → production log consumes → packing produces FG → verify stock and valuation.
5) Add reconciliation assertions:
   - inventory valuation matches GL control accounts (if enabled)
   - no negative stock across consumption/dispatch edge cases.
6) Performance pass on production dashboards and stock queries.

## Acceptance criteria
- A production/packing golden scenario results in correct raw material + finished goods stock changes.
- Any corresponding accounting postings are balanced, linked, and reversible (where supported).
- Cost basis is stable and traceable from FG batches through to dispatch/COGS.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Production* test`
