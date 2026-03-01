# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, known quirks.

**What belongs here:** How to manually test the application, testing tools, setup steps, known issues.

---

## Testing Surface
- **Type**: REST API (no frontend in this mission)
- **Base URL**: http://localhost:8081
- **Swagger UI**: http://localhost:8081/swagger-ui (dev profile only)
- **Actuator**: http://localhost:9090/actuator (management port)
- **MailHog UI**: http://localhost:8025 (email testing)

## Testing Tools
- `curl` for API endpoint testing
- Docker Compose for full stack (postgres, rabbitmq, mailhog, app)
- Testcontainers for integration tests (auto-managed PostgreSQL)

## Setup Steps
1. Ensure .env file exists (init.sh creates from .env.example)
2. Start services: `docker compose up -d`
3. Wait for health: `curl -sf http://localhost:8081/actuator/health`
4. Auth: POST /api/v1/auth/login with credentials to get JWT token
5. Use token in Authorization: Bearer header for protected endpoints

## Known Issues
- 1 pre-existing test failure: `TS_RuntimeTenantPolicyControlExecutableCoverageTest`
- Tests use H2 for unit tests, Testcontainers PostgreSQL for integration tests
- RabbitMQ health check excluded in dev profile
- When running Maven with `-f erp-domain/pom.xml` from outside `erp-domain`, `.mvn/settings.xml` may not resolve; run commands from `erp-domain` or pass `--settings /home/realnigga/Desktop/Mission-control/erp-domain/.mvn/settings.xml` explicitly.
- On this host, Docker DB startup may fail because port `5432` is already occupied by a local PostgreSQL service; for assertion validation, prefer API integration tests (Testcontainers) over docker-compose app runtime.
- In this mission environment, spawning `user-testing-flow-validator` Task subagents can fail with `Invalid model: custom:CLIProxyAPI-5.3-codex-xhigh-pro`; when that happens, execute validator flow groups directly in-session and still emit per-group flow JSON reports under `.factory/validation/<milestone>/user-testing/flows/`.
- For tenant/admin API integration classes under the v2 migration surface, add `-Dspring.jpa.hibernate.ddl-auto=update` in test commands to align ephemeral schema (not production code).
- `SuperAdminControllerIT#superAdmin_canSuspendActivateListAndReadUsage` can fail on lifecycle check-constraint mismatch (`ACTIVE/HOLD/BLOCKED` vs `SUSPENDED`) in current branch; use narrower tenant/admin evidence tests for dashboard/metrics, module configuration, and runtime enforcement while that branch issue is unresolved.
- `PurchasingServiceTest#createPurchase_journalPostedFirst` is currently unstable in this branch (NullPointerException in test setup: lineBreakdown null); for accounting facade validation use stable evidence tests (`TS_P2PGoodsReceiptIdempotencyTest`, `AccountingServiceStandardJournalTest`, and dispatch/payroll truth suites) until that setup path is fixed.

## Flow Validator Guidance: api
- Surface: backend REST API + CLI test runner (`curl`, `mvn test`) for evidence collection.
- Isolation: every flow validator must use only its assigned `namespace` prefix for any created identifiers (emails, references, trace values).
- Shared-state safety: do **not** restart Docker/services, do **not** run destructive DB resets, and avoid editing shared config during flow execution.
- Concurrency guard: avoid parallel Maven test execution in multiple validators; if running tests, execute assigned command set in isolation and keep scope to assertion-specific tests.
- Evidence minimum for each assertion: executed command/request, observed status/output, and explicit pass/fail/blocked reason.
