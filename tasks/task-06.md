# Epic 06 — Admin/Security/Multi-Company + Auditability

## Objective
Make the ERP safe to operate in production:
- strong JWT auth + RBAC (no privilege escalation)
- multi-company boundary is enforced consistently
- MFA, password policies, token revocation/blacklist are reliable
- audit logs cover financially and security-relevant actions

## Scope guard (no new features)
- Use existing auth/RBAC/company mechanisms; only close exposure gaps and add tests/audit coverage.
- Do not weaken controls to make flows “work”; fix root causes and prove with tests.

## Dependencies / parallel work
- Can run in parallel with all functional epics; treat security/tenancy as non-negotiable.

## Likely touch points (exact)
- Auth/RBAC/Admin/Company:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company/**`
- Core security + context:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/**`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audit/**`
- DB migrations (forward-only): `erp-domain/src/main/resources/db/migration/**`
- Tests:
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/auth/**`
  - `erp-domain/src/test/java/com/bigbrightpaints/erp/smoke/**`

## Step-by-step implementation plan
1) Define a permission model that matches the ERP modules:
   - map roles (ADMIN/ACCOUNTING/SALES/FACTORY/HR/DEALER) to allowed actions.
2) Audit endpoint exposure:
   - confirm only intended routes are public; everything else requires JWT and RBAC.
3) Enforce company boundaries:
   - confirm company context rules and prevent cross-company access in controllers/services.
4) Strengthen token lifecycle:
   - refresh/logout behavior, token blacklist, lockout controls, password history rules.
5) Audit logging:
   - ensure key mutations emit audit records (posting, reversals, dispatch, settlements, payroll post).
6) Add regression tests:
   - protected endpoints return 401/403 without token
   - cross-company access blocked
   - MFA/token flows behave correctly (as supported by current design)

## Acceptance criteria
- Security baseline tests exist and pass (auth required, RBAC enforced, company boundary enforced).
- Sensitive operations are auditable (who/when/what) without leaking secrets in logs.
- Token revocation/blacklist and lockout controls work under retry/concurrency conditions.

## Commands to run
- Tests: `mvn -f erp-domain/pom.xml test`
- Focused: `mvn -f erp-domain/pom.xml -Dtest=*Auth* test`
