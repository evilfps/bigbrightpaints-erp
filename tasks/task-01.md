# Epic 01 — Accounting Core (GL): Journals, Ledgers, Periods, Reversals

## Objective
Make accounting ERP-grade and provable:
- all journals are **balanced**
- every posting is **linked** to a business document/event
- reversals are **correct and traceable**
- period lock/close rules are enforced
- ledgers/statements/aging match underlying journals

## Scope guard (no new features)
- Use existing accounting model and APIs; only fix incorrect/unlinked behavior and data integrity gaps.
- Prefer forward-only schema fixes (Flyway) over risky rewrites; preserve audit trails and reversibility.

## Dependencies / parallel work
- Works best after Epic 00 exists (so accounting invariants are executable).
- Can run in parallel with sales/inventory/payroll epics if interfaces are respected (linking + reversals).

## Likely touch points (exact)
- Accounting domain + APIs:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/**`
- Cross-cutting audit/versioning:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/domain/VersionedEntity.java`
- DB schema:
  - `erp-domain/src/main/resources/db/migration/**` (forward migrations only)
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/accounting/**`

## Step-by-step implementation plan
1) Inventory the accounting model (schema + entities + services):
   - accounts tree/hierarchy, journal entries/lines, periods/locking, event store, AR/AP ledgers.
2) Define and document the canonical “posting contract”:
   - reference fields required (type + id), company id, idempotency behavior, audit fields.
3) Create/strengthen automated checks:
   - unit tests for posting/reversal services
   - integration tests that assert trial balance, statements, and period enforcement.
4) Close linking gaps:
   - ensure every module that posts to GL stores a stable reference to the source document(s).
   - add DB constraints/indexes where safe (via new Flyway migrations).
5) Validate reversibility:
   - reverse/cascade reverse endpoints preserve an auditable chain and restore ledgers to expected states.
6) Performance pass on accounting hot paths:
   - account activity, journal list, trial balance, aging endpoints (pagination + indexes).

## Acceptance criteria
- For golden scenarios, every journal is balanced and linked to an originating document/event.
- Ledger/statements endpoints reconcile to journal lines (AR/AP control accounts reconcile).
- Posting fails correctly in locked/closed periods; reopening is auditable and controlled.
- Reversal flow is correct and leaves a traceable chain.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Accounting* test`
