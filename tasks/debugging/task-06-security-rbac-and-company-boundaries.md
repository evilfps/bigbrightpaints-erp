# Task 06 — Security, RBAC, and Company Boundaries (Least Privilege + Tenancy Invariants)

## Purpose
**Accountant-level:** protect financial data integrity by ensuring only authorized roles can post or view sensitive financial information, and that company/dealer boundaries prevent misstatement and data leakage.

**System-level:** verify auth/RBAC enforcement is consistent with the portal matrix (Task 02), and that company context rules cannot be bypassed through missing guards or alias endpoints.

## Scope guard (explicitly NOT allowed)
- No new auth features or new role models; only alignment/hardening of intended behavior.
- Do not bypass security in tests to “make it pass”.
- No broad rewrites of security config; keep changes localized and test-driven.

## Milestones

### M1 — RBAC alignment with portal matrix (endpoint-by-endpoint)
Deliverables:
- Cross-check `docs/API_PORTAL_MATRIX.md` against actual code:
  - `SecurityConfig` (permitAll endpoints)
  - `@PreAuthorize` usage in controllers
  - any service-level permission checks
- Identify:
  - endpoints that are only `authenticated()` but should be role-restricted
  - endpoints with overly-broad roles
  - endpoints whose portal assignment conflicts with roles/permissions

Verification gates (run after M1):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=AuthControllerIT,AdminUserSecurityIT test`

Evidence to capture:
- A “RBAC mismatches” list with safest recommended fix (fail‑closed) and the regression test to add.

Stop conditions + smallest decision needed:
- If the correct restriction is ambiguous: default to least privilege and document the assumption; do not widen access.

### M2 — Company boundary invariants (no cross-company reads/writes)
Deliverables:
- Verify `X-Company-Id` handling is consistent:
  - membership required for switching companies
  - controller/service calls always use the resolved company context
- Add/extend tests that attempt cross-company access and must be rejected.

Verification gates (run after M2):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=AdminUserSecurityIT,CompanyControllerIT test` (if present; otherwise create a minimal boundary IT in later execution runs)

Evidence to capture:
- Forbidden response evidence and DB proof that no cross-company mutation occurred.

Stop conditions + smallest decision needed:
- If company boundary enforcement is inconsistent across modules: smallest decision is whether to enforce at controller filter level (preferred) vs per-service checks. Prefer filter + minimal service assertions.

### M3 — Dealer portal restrictions (read-only, scoped)
Deliverables:
- Confirm dealer portal endpoints:
  - are read-only
  - cannot access other dealers’ data
  - cannot access accounting admin endpoints
- Add tests for dealer role attempts to call privileged endpoints and ensure 403.

Verification gates (run after M3):
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Dealer*Security*,DealerPortal* test` (adjust to actual test names)

Evidence to capture:
- Dealer portal API samples (redacted) demonstrating scoping + denial cases.

Stop conditions + smallest decision needed:
- If dealer identity scoping is unclear: smallest decision is whether the dealer is derived from JWT principal vs request param; prefer principal-derived scoping to prevent horizontal privilege escalation.

