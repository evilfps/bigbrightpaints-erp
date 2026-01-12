# Task 08 — Predeploy Consistency (Cross‑Module Invariants + Idempotency)

## Purpose
**Accountant-level:** ensure every financially-impacting action is **balanced, traceable, and reversible (or explicitly documented as irreversible)**, and that retries/duplicates cannot silently distort the books.

**System-level:** eliminate cross-module drift by enforcing invariants at boundaries (controllers/services/DB) and by expanding tests so “success” cannot be returned while linkage is missing or state is inconsistent.

## Scope guard (explicitly NOT allowed)
- No new business workflows or new modules.
- No new UI/portals.
- Do not change accounting semantics unless a failing invariant test proves the issue and the fix is minimal.
- No destructive migration rewrites; Flyway is forward-only.

## Known high-risk inconsistencies to verify first (evidence-driven)
- `POST /api/v1/dispatch/confirm` currently calls `SalesService.confirmDispatch(...)` and then calls `FinishedGoodsService.confirmDispatch(...)` again (double-work risk; future divergence risk).
- Alias endpoints exist but are not consistently deprecated in OpenAPI/docs (examples: `/api/v1/sales/dealers`, `/api/v1/hr/payroll-runs`, `/api/v1/orchestrator/dispatch/{orderId}`).
- “Unallocated” AP/AR journal endpoints exist with no allocations/tests (`POST /api/v1/accounting/receipts/dealer`, `POST /api/v1/accounting/suppliers/payments`) and can create subledger drift if used for settlements.

## Milestones

### M1 — Establish a predeploy invariant baseline + gap register
Deliverables:
- A short “gap register” note capturing:
  - missing link risks (doc→journal, movement→journal, ledger→journal)
  - idempotency risks (retry duplicates)
  - endpoint duplication/alias risks
  - any areas where docs do not match code/OpenAPI
- Confirm current baseline gates are green before changing anything (so regressions are attributable).

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`

Evidence to capture:
- `mvn test` summary + failing test details (if any).
- The created/updated gap register note with concrete repo anchors (class/method names, endpoint paths).

Stop conditions + smallest decision needed:
- If baseline tests are flaky (time-of-month/date drift): choose “fail-closed” fixes (pin dates in tests) over weakening validations.

### M2 — Close idempotency holes for posting/confirm endpoints (tests first)
Deliverables:
- Extend invariant tests to replay (same request twice) for every “posting edge”:
  - sales order create
  - dispatch confirm
  - dealer settlement
  - supplier settlement
  - payroll run create/post/mark-paid
  - onboarding opening stock + opening balances
- For each replay, assert:
  - same journal entry IDs (or same reference mapping) and no duplicate lines
  - no duplicate inventory movements / reservations
  - no duplicate settlement allocation rows

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`
- Focused (as needed): `mvn -f erp-domain/pom.xml -Dtest=*Settlement* test`

Evidence to capture:
- Test cases added (names) + replay assertions (what was compared).
- DB evidence (if needed): counts of journals/movements/allocations before/after replay.

Stop conditions + smallest decision needed:
- If a replay currently creates duplicates and fixing requires new API fields: prefer switching callers/tests to an existing idempotency mechanism already supported (referenceNumber/idempotencyKey) instead of adding new request shapes.

### M3 — Make linkage failures impossible to ship (invariants + lightweight DB checks)
Deliverables:
- Add/extend invariant assertions that fail fast when:
  - a posted invoice/purchase/payroll run has a null/missing journal link
  - an inventory movement tied to dispatch/production/opening stock has no journal link when posting is expected
  - a ledger entry exists without a journal entry reference (or wrong company)
- Add a small set of “orphan detection” SQL queries to the ops evidence pack (to be run on a candidate prod DB).

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Reconciliation* test`

Evidence to capture:
- The SQL query pack (and sample output on a seeded DB).
- A short note on any “expected null link” exceptions (should be zero for posted artifacts).

Stop conditions + smallest decision needed:
- If linkage is missing in historical data: choose between (A) safe forward backfill migration, or (B) bounded ops backfill script + documented one-time run.
