# Task 11 — Endpoint Hygiene (Aliases, Deprecations, Unsafe Endpoints)

## Purpose
**Accountant-level:** remove or constrain API paths that allow financially-impacting actions without proper linkage/traceability or that can create ambiguous accounting outcomes.

**System-level:** reduce operational risk by making the API surface consistent: one canonical path per workflow, deprecated aliases clearly marked, and unused endpoints removed safely.

## Scope guard (explicitly NOT allowed)
- No new endpoint families.
- Do not change request/response shapes unless required for correctness and backed by tests.
- Do not remove endpoints unless you have concrete evidence they are unused (or they are provably unsafe and have a clear canonical replacement).

## Milestones

### M1 — Produce an “API surface + deprecation matrix”
Deliverables:
- A concise matrix listing:
  - canonical endpoints per workflow (O2C/P2P/Payroll/Onboarding/Inventory adjustments)
  - alias/duplicate endpoints and their canonical replacements
  - whether OpenAPI marks them deprecated today (and whether docs agree)
- Align or correct: `erp-domain/docs/API_NOTES.md` and `erp-domain/docs/DUPLICATES_REPORT.md`.

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test`

Evidence to capture:
- The produced matrix and a short summary of mismatches found (OpenAPI vs code vs docs).
- OpenAPI snapshot test output.

Stop conditions + smallest decision needed:
- If a deprecation decision depends on unknown client usage, prefer “mark deprecated + keep” over removal.

### M2 — Eliminate duplicate side-effect paths (dispatch confirm is the priority)
Primary target:
- `POST /api/v1/dispatch/confirm` currently triggers dispatch confirmation via `SalesService.confirmDispatch` and then calls `FinishedGoodsService.confirmDispatch` again to build the response.

Deliverables:
- Make dispatch confirmation flow have a single authoritative side-effect path (inventory movements + journals) and keep the controller response purely “read the current state”.
- Add regression tests proving:
  - no double inventory movement creation
  - no double journal posting
  - response is stable after a retry

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=DispatchConfirmationIT test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`

Evidence to capture:
- Before/after behavior described in terms of persisted rows (movements, journals) on retry.
- Test output showing retry safety.

Stop conditions + smallest decision needed:
- If two endpoints are both used by different roles (factory vs sales), keep both but ensure they share a single service method and consistent authorization/idempotency.

### M3 — Deprecate/remove true aliases (only with evidence)
Candidates to evaluate (verify usage first):
- `/api/v1/sales/dealers` and `/api/v1/sales/dealers/search` (alias of `/api/v1/dealers*`)
- `/api/v1/hr/payroll-runs` (overlaps with `/api/v1/payroll/runs`)
- `/api/v1/orchestrator/dispatch/{orderId}` (alias of `/api/v1/orchestrator/dispatch`)

Deliverables:
- For each candidate:
  - either remove (if proven unused), or
  - mark deprecated in OpenAPI + docs and add a focused test to ensure canonical path remains correct.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OpenApiSnapshotIT test`

Evidence to capture:
- Proof of unused endpoints (repo-wide search + absence in tests/docs; if available, request logs).
- OpenAPI diff and updated docs references.

Stop conditions + smallest decision needed:
- If removal risk is non-zero, ship only deprecation first and schedule removal for a later release after usage confirmation.
