# Fix Taskpack — Payroll Idempotency Key Must Be Company-Scoped

Confirmed flaw: **LF-007**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Adjust payroll run idempotency uniqueness semantics to prevent cross-company collisions.

## ERP expectation (what “correct” means)
- Multi-company isolation includes idempotency:
  - identical idempotency keys in different companies must not collide.
  - within the same company, idempotency keys should enforce exactly-once creation.

## Primary evidence (baseline + after)
- Code:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/domain/PayrollRun.java` (`@Column(unique=true)` on `idempotency_key`).
- DB:
  - Confirm existing unique index/constraint semantics for `payroll_runs.idempotency_key`.

## Milestones (implementation plan)
### M1 — Define migration approach (hard gate)
- Decide schema approach:
  - change to a composite unique index `(company_id, idempotency_key)`, and
  - drop the global unique constraint (safe migration order required).
- Ensure forward-only Flyway migration strategy with rollback notes.

### M2 — Add multi-company idempotency test
- Add an integration test:
  - create payroll run in Company A with key K
  - create payroll run in Company B with key K (must succeed)
  - create second payroll run in Company A with key K (must be idempotent or rejected per API contract).

### M3 — Implement changes + verify API behavior
- Update entity/schema/repository usage to align with company-scoped uniqueness.
- Ensure conflict paths are deterministic and do not leak cross-company information.

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-007 eliminated: same idempotency key works across companies; collisions prevented only within company.
- Migration is safe and forward-only; tests prove isolation.

