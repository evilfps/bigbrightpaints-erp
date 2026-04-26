# BigBright ERP Developer Workflow & Onboarding Guide

> ⚠️ **NON-CANONICAL**: This document is superseded by the module packets and flow packets in the new docs tree. For current developer documentation, see [docs/INDEX.md](INDEX.md), [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md), and [docs/flows/FLOW-INVENTORY.md](flows/FLOW-INVENTORY.md).

This is the day-1 guide for backend developers working in this repository.

## 1) Team workflow

### Branch strategy
- Base your work on `Factory-droid` unless your task says otherwise.
- Use short-lived branches with prefixes:
  - `feature/<scope>`
  - `fix/<scope>`
  - `refactor/<scope>`
  - `docs/<scope>`
- `sync/*` is reserved for branch convergence workflows in CI; don’t use it for feature work.
- Keep PR scope tight (single feature or bugfix). Avoid mixed concerns.

### Commit conventions
Follow the existing commit style:
- `feat(accounting): ...`
- `fix(auth): ...`
- `refactor(inventory): ...`
- `docs(<area>): ...`
- `validate(<milestone>): ...`

Use imperative subject lines and include the **why** in the body for non-trivial changes.

### PR process
1. Update from latest base branch.
2. Run required checks locally (see testing section).
3. Open PR with:
   - clear scope
   - risk/rollback notes
   - test evidence (commands + outcomes)
4. Ensure CI gates relevant to your target branch pass.

### Code review expectations
- Prefer small, reviewable diffs.
- Reviewers expect:
  - correct tenant scoping (`CompanyContextHolder`/company-aware repositories)
  - `ApplicationException` + `ErrorCode` for business errors
  - idempotency for create/update endpoints that can be retried
  - no direct cross-module bypasses where facade/service contracts exist
- Address comments with follow-up commits; squash before merge if requested.

---

## 2) Local development setup (clone → run app)

## Prerequisites
- Java 21
- Maven 3.8+
- Docker + Docker Compose
- PostgreSQL/RabbitMQ/MailHog are run via Compose in this repo

## Step-by-step
1. Clone and enter repo:
   ```bash
   git clone <repo-url> Mission-control
   cd Mission-control
   ```
2. Create env file:
   ```bash
   cp .env.example .env
   ```
3. Set minimum required secrets in `.env`:
   - `JWT_SECRET` (>= 32 bytes)
   - `ERP_SECURITY_ENCRYPTION_KEY` (>= 32 bytes)
   - optionally `ERP_SECURITY_AUDIT_PRIVATE_KEY` for local parity
4. Start dependencies:
   ```bash
   docker compose up -d db rabbitmq mailhog
   ```
5. Compile:
   ```bash
   cd erp-domain
   mvn compile -q
   ```
6. Run fast truth-suite gate:
   ```bash
   mvn test -Pgate-fast -Djacoco.skip=true
   ```
7. Run app locally (dev profile):
   ```bash
   SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
   ```

## Local service endpoints
- API: `http://localhost:8081`
- Swagger UI (dev): `http://localhost:8081/swagger-ui`
- Actuator health: `http://localhost:9090/actuator/health`
- MailHog UI: `http://localhost:8025`
- RabbitMQ UI: `http://localhost:15672`

## Environment/profile notes
- `application.yml` defaults profile to `prod`; explicitly set `SPRING_PROFILES_ACTIVE=dev` for local app runs.
- `prod` profile disables Swagger and uses stricter runtime checks.
- `flyway-v2` profile points Flyway to `db/migration_v2`.
- Compose app container defaults to `SPRING_PROFILES_ACTIVE=prod,flyway-v2`.

## Database & migrations
- Migration set in active production-like flow is `db/migration_v2`.
- Latest migration currently is `V45__raw_material_adjustments.sql`; add new scripts as `V46__...` onward.
- Do not modify historical migration files after they are established.

## MailHog usage
- App mail defaults can route to MailHog (`mailhog:1025` in Compose app).
- In local non-container run, point `SPRING_MAIL_HOST/PORT` to your target SMTP (MailHog or real relay).

---

## 3) Testing guide

## Fast baseline gate (required before PR)
From repo root equivalent via services manifest command:
```bash
cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true
```

## Unit-focused suite (excluding IT/codered)
```bash
cd erp-domain && mvn test -Djacoco.skip=true '-Dtest=!*IT,!*ITCase,!*codered*' -pl .
```

## Integration tests only
```bash
cd erp-domain && mvn test -Djacoco.skip=true -Dtest='*IT,*ITCase'
```

## Full local Maven test run
```bash
cd erp-domain && mvn test -Djacoco.skip=true
```
(Use when you intentionally want broader coverage/time cost.)

## CI-aligned scripts (repo root)
- `bash scripts/gate_fast.sh`
- `bash scripts/gate_core.sh`
- `bash scripts/gate_release.sh`
- `bash scripts/gate_reconciliation.sh`

## Testcontainers setup
Integration tests extend `AbstractIntegrationTest` and use `PostgreSQLContainer("postgres:16-alpine")` by default.
- To use containerized DB automatically: no extra flags needed.
- To run against external DB:
  - set `ERP_TEST_DB_URL`
  - optional `ERP_TEST_DB_USERNAME`, `ERP_TEST_DB_PASSWORD`

## Writing new tests: conventions
- Place tests under mirrored package paths in `src/test/java`.
- Unit tests:
  - class name suffix: `*Test` or `*Tests`
  - mock collaborators; avoid container startup.
- Integration tests:
  - suffix: `*IT` or `*ITCase`
  - cover repository/controller/service wiring and DB behavior.
- Truthsuite tests live under `com.bigbrightpaints.erp.truthsuite` and are part of `gate-fast`.
- Naming pattern: `method_condition_expectedOutcome`.

---

## 4) Module development guide

## Module layout pattern
Under `com.bigbrightpaints.erp.modules.<module>`:
- `controller/` — HTTP adapters only
- `service/` — business logic
- `domain/` — entities + repositories
- `dto/` — request/response records
- optional `event/`, `config/`

## Adding a new module (high-level)
1. Create package under `modules/<new-module>` with `controller/service/domain/dto`.
2. Add entities/repositories in `domain` with company scoping where applicable.
3. Add service layer with transactional boundaries.
4. Add controller endpoints under `/api/v1/...`.
5. Wire authorization (`@PreAuthorize`) and rely on context filters/interceptors.
6. Add migrations in `db/migration_v2` for schema changes.
7. Add unit + integration tests.

## Adding a new entity
- Extend `VersionedEntity` for optimistic locking unless there is a specific reason not to.
- Prefer explicit `@Table(name = "...")` and stable column names.
- For tenant data: include `company` relation (`@ManyToOne`) and enforce company-scoped query methods.

## Adding a new service
- Keep services focused and small; split orchestration vs helpers if class grows.
- Prefer constructor injection.
- Annotate mutating operations with `@Transactional` in services, not controllers.
- Use existing facades for cross-module actions (example: accounting posting via `AccountingFacade` / `AccountingService`).

## Adding a new controller
- Keep controller logic thin: validate, map, delegate, return DTO.
- Return `ApiResponse<T>` pattern used across modules.
- Add role guards with `@PreAuthorize`.
- If operation is retry-sensitive (create/import/post): support idempotency headers.

## Adding a migration
- Add new file to `erp-domain/src/main/resources/db/migration_v2/` as next version.
- Prefer additive/forward-safe changes.
- Include constraints/indexes in same migration where needed.

## DTO conventions
- Use Java `record` DTOs for requests/responses.
- Add Jakarta validations (`@NotNull`, `@NotBlank`, `@DecimalMin`, etc.).
- Keep API field names explicit; avoid leaking entity internals.

## Error handling conventions
- Use `ApplicationException` + `ErrorCode` for business/validation failures.
- Do not throw raw `IllegalArgumentException` / `IllegalStateException` for business paths.
- Add details via `.withDetail(...)` when useful for clients/debugging.

## Idempotency conventions
- Use `Idempotency-Key` only. Reject legacy `X-Idempotency-Key` on public write surfaces unless a route explicitly documents a fail-closed legacy-reject contract.
- Normalize/resolve keys via shared utilities (`IdempotencyHeaderUtils`, `IdempotencyUtils`, `IdempotencyReservationService`, `IdempotencySignatureBuilder`).
- Detect mismatched payload-on-retry and return conflict-style `ApplicationException`.

## Audit trail conventions
- Use `AuditService` for operational/audit events.
- Use `AccountingComplianceAuditService` for accounting compliance transitions (period transitions, manual overrides, close/reopen lifecycle).
- Keep references (journal refs, period labels, request IDs) in metadata for traceability.

---

## 5) Deployment notes

## Production configuration checklist
- Use `SPRING_PROFILES_ACTIVE=prod`. The production profile group already adds
  `flyway-v2`; do not point production-like runtime at the legacy migration
  chain.
- Set required secrets/envs:
  - `JWT_SECRET`
  - `ERP_SECURITY_ENCRYPTION_KEY`
  - `ERP_SECURITY_AUDIT_PRIVATE_KEY`
  - datasource/rabbit credentials
  - mail settings
  - CORS origins (`ERP_CORS_ALLOWED_ORIGINS`)
- Ensure `erp.environment.validation.enabled=true` in production environments.

## Important env vars (non-exhaustive)
- Runtime/security: `JWT_SECRET`, `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL`, `ERP_SECURITY_ENCRYPTION_KEY`, `ERP_SECURITY_AUDIT_PRIVATE_KEY`
- DB: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- Flyway v2 overrides: `SPRING_DATASOURCE_V2_URL`, `SPRING_DATASOURCE_V2_USERNAME`, `SPRING_DATASOURCE_V2_PASSWORD`
- Mail: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `ERP_MAIL_FROM`, `ERP_MAIL_BASE_URL`
- Platform controls: `ERP_CORS_ALLOWED_ORIGINS`, `ERP_RATE_LIMIT_RPM`, `ERP_EXPORT_REQUIRE_APPROVAL`, `ERP_GITHUB_*`

## CI/CD pipeline overview (`.github/workflows/ci.yml`)
- Global env: `MIGRATION_SET=v2`
- PR workflow runs:
  - `CI Config Check`
  - `Docs Lint`
  - `Module Boundary Check`
  - `High-Risk Change Control`
  - `Secrets Scan`
  - changed-file routing, compile/package, routed parallel shards, changed-code coverage, and `PR Ship Gate`
- `main` branch pushes run `Gate Core`.
- Release/tag/manual paths run `Gate Release` and `Gate Reconciliation`.
- Scheduled/manual deep checks run `Gate Quality`.
- The lane contract lives in [`docs/ci-cd-contract.md`](ci-cd-contract.md).

---

## 6) Debugging tips

## Common issues
- **401/403 on tenant routes**
  - Verify JWT carries the canonical `companyCode` claim and matches request headers.
  - Check `CompanyContextFilter` logs for mismatch diagnostics.
- **Module suddenly forbidden**
  - Tenant module gating may disable optional modules (`MANUFACTURING`, `HR_PAYROLL`, etc.).
  - Check enabled modules via superadmin/company APIs.
- **Idempotency conflicts**
  - Same key with different payload returns conflict; reuse exact payload or send new key.
- **Period operations blocked**
  - LOCKED/CLOSED periods block checklist updates and postings needing open period.
  - Reopen requires `ROLE_SUPER_ADMIN`.

## Tracing journal entries end-to-end
1. Capture source reference from operation response (for example `sourceReference`, receipt/order/payment reference).
2. Query journals:
   - `GET /api/v1/accounting/journals?sourceModule=...`
   - `GET /api/v1/accounting/journal-entries`
3. Inspect transaction audit trail:
   - `GET /api/v1/accounting/audit/transactions`
   - `GET /api/v1/accounting/audit/transactions/{journalEntryId}`
4. For compliance events, check accounting compliance audit records and metadata (period/journal actions).

## Debugging tenant context issues
- Verify the authenticated scoped account is bound to the target company (`UserPrincipal` company context).
- Use only `X-Company-Code` for authenticated tenant context; `X-Company-Id` is unsupported and fails closed.
- For control-plane endpoints (`/api/v1/companies/*/tenant-runtime/policy`, lifecycle routes), confirm superadmin authority.
- Watch `CompanyContextFilter` warnings:
  - mismatched header claims
  - missing company claim
  - unauthorized company access

## Useful checks during debugging
```bash
# compile sanity
cd erp-domain && mvn compile -q

# fast behavioral confidence
cd erp-domain && mvn test -Pgate-fast -Djacoco.skip=true

# app health
curl -sf http://localhost:9090/actuator/health
```
