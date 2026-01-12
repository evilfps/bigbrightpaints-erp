# Task 10 — Master Data + Onboarding Audit (Defaults, Health, Idempotency)

## Purpose
**Accountant-level:** ensure go-live onboarding cannot produce partially configured companies where postings silently go missing or hit the wrong accounts.

**System-level:** ensure onboarding and master-data mutations are properly authenticated, company-scoped, idempotent, and validated through tests and configuration health checks.

## Scope guard (explicitly NOT allowed)
- No new onboarding “wizard” or UI.
- No auto-mapping of accounts beyond existing suggestion endpoints (suggestions are read-only).
- Do not relax validations to “make onboarding easier”; prefer strict validation with good error messages and docs.

## Milestones

### M1 — Validate onboarding contract end-to-end (docs + tests)
Deliverables:
- Confirm `erp-domain/docs/ONBOARDING_GUIDE.md` is accurate for current API behavior (paths, required fields, required env).
- Ensure the onboarding flows are covered in tests:
  - defaults configuration
  - master data bootstrap (brands/categories/products/raw materials/suppliers/dealers)
  - opening stock (journal + movements + idempotency)
  - opening AR/AP balances (journal + ledger links + idempotency)

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OnboardingFlowIT test`

Evidence to capture:
- A checklist-style confirmation that every step in the onboarding guide maps to an endpoint and is covered by a test.
- Any discovered mismatches (doc vs OpenAPI vs controller routes).

Stop conditions + smallest decision needed:
- If an onboarding step is required for correctness but has no endpoint: confirm whether an equivalent endpoint already exists elsewhere; only add a new endpoint if there is no equivalent and the gap blocks accounting integrity.

### M2 — Configuration health is a real gate (no “UP but broken”)
Deliverables:
- Ensure configuration health endpoints correctly detect missing defaults that would break postings:
  - default account mappings
  - dispatch debit/credit mappings (`ERP_DISPATCH_DEBIT_ACCOUNT_ID`, `ERP_DISPATCH_CREDIT_ACCOUNT_ID`)
  - production-enabled product metadata (WIP/semi-finished accounts) where required
- Add tests that fail when defaults are missing and pass when configured.

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Configuration* test`
- Focused (as needed): `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`

Evidence to capture:
- Sample `/api/v1/accounting/configuration/health` output for a healthy and unhealthy company.
- Tests demonstrating “fail closed” behavior.

Stop conditions + smallest decision needed:
- If a legacy flow depends on missing defaults: decide whether to (A) hard-fail with a clear error, or (B) explicitly document the flow as unsupported until defaults are set.

### M3 — Onboarding idempotency and conflict detection (no duplicate openings)
Deliverables:
- For opening stock and opening balances, ensure retries:
  - reuse the same journal entry and do not create duplicate ledger/movement rows
  - reject conflicts where the same reference number is reused with different payload signatures
- Ensure evidence queries can detect duplicates in a candidate prod DB.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=OnboardingFlowIT test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=ErpInvariantsSuiteIT test`

Evidence to capture:
- Replay tests and the invariants asserted (same journal IDs; no duplicate rows).
- SQL query outputs showing no duplicates by reference number/company.

Stop conditions + smallest decision needed:
- If idempotency behavior differs between endpoints, standardize on the existing posting contract policy (`referenceNumber`/`idempotencyKey` reuse only when the payload signature matches).
