# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, known quirks.

**What belongs here:** How to manually test the application, testing tools, setup steps, known issues.

---

## Review-Only Mission Note
- For the production backend review mission, the primary validation surface is `docs/code-review/**`.
- Secondary evidence collection may use passive API/runtime probes against the existing backend on `http://localhost:8081`.
- Do not start a new app stack for this mission and do not mutate shared runtime state unless the orchestrator explicitly changes scope.
- Because worker/subagent launching is currently unreliable in this environment, validators may need to rely on direct file validation plus passive runtime evidence rather than delegated deep flow probes.

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
3. Wait for health: `curl -sf http://localhost:9090/actuator/health`
4. Auth: POST /api/v1/auth/login with credentials to get JWT token
5. Use token in Authorization: Bearer header for protected endpoints

## Known Issues
- 1 pre-existing test failure: `TS_RuntimeTenantPolicyControlExecutableCoverageTest`
- Tests use H2 for unit tests, Testcontainers PostgreSQL for integration tests
- RabbitMQ health check excluded in dev profile
- When running Maven with `-f erp-domain/pom.xml` from outside `erp-domain`, `.mvn/settings.xml` may not resolve; run commands from `erp-domain` or pass `--settings /home/realnigga/Desktop/Mission-control/erp-domain/.mvn/settings.xml` explicitly.
- On this host, Docker DB startup may fail because port `5432` is already occupied by a local PostgreSQL service; for assertion validation, prefer API integration tests (Testcontainers) over docker-compose app runtime.
- In this mission environment, spawning `user-testing-flow-validator` Task subagents can fail with invalid custom-model errors such as `Invalid model: custom:CLIProxyAPI-5.4-xhigh` (and previously `custom:CLIProxyAPI-5.3-codex-xhigh-pro`); attempt the Task launch once for evidence, then immediately execute validator flow groups directly in-session and still emit per-group flow JSON reports under `.factory/validation/<milestone>/user-testing/flows/`.
- On 2026-03-06, passive runtime setup probes showed the business app port `8081` does not reliably expose actuator health (`curl -sf http://localhost:8081/actuator/health` previously failed with curl exit `7`, and later returned HTTP `404`), while the dedicated management port `9090` is the intended actuator surface but may still return `503/DOWN` during a docs-only round. Prefer `http://localhost:9090/actuator/health` for readiness checks, but if `9090` is down or `127.0.0.1:5433` is unavailable, continue validation against `docs/code-review/**` and record the runtime surface degradation in synthesis instead of blocking the round.
- Disk space can run out during validator-driven Maven recompilation. If you hit `No space left on device`, clear regenerable build outputs such as `erp-domain/target` (and, only if necessary, the local Maven cache) after capturing the failure as environment evidence, then continue the docs-only validation round.
- Inventory integration tests that bootstrap full Spring context may fail with `ConflictingBeanDefinitionException` for bean name `inventoryValuationService` (`reports.service.InventoryValuationService` vs `inventory.service.InventoryValuationService`); until bean naming is disambiguated, prefer runtime/controller/domain suites for user-testing evidence and record the context-startup blocker explicitly in synthesis.
- For tenant/admin API integration classes under the v2 migration surface, add `-Dspring.jpa.hibernate.ddl-auto=update` in test commands to align ephemeral schema (not production code).
- `SuperAdminControllerIT#superAdmin_canSuspendActivateListAndReadUsage` can fail on lifecycle check-constraint mismatch (`ACTIVE/HOLD/BLOCKED` vs `SUSPENDED`) in current branch; use narrower tenant/admin evidence tests for dashboard/metrics, module configuration, and runtime enforcement while that branch issue is unresolved.
- `PurchasingServiceTest#createPurchase_journalPostedFirst` is currently unstable in this branch (NullPointerException in test setup: lineBreakdown null); for accounting facade validation use stable evidence tests (`TS_P2PGoodsReceiptIdempotencyTest`, `AccountingServiceStandardJournalTest`, and dispatch/payroll truth suites) until that setup path is fixed.
- superadmin-ux validation surfaced a contract mismatch: support-ticket GitHub integration currently does not send category-based `labels` in create-issue payload (`GitHubIssueClient.createIssue` sends only title/body). Until fixed, treat `VAL-ADMIN-006` as expected-fail while preserving validation of local persistence, async sync, and resolved-email behavior.

## Flow Validator Guidance: api
- Surface: backend REST API + CLI test runner (`curl`, `mvn test`) for evidence collection.
- Isolation: every flow validator must use only its assigned `namespace` prefix for any created identifiers (emails, references, trace values).
- Shared-state safety: do **not** restart Docker/services, do **not** run destructive DB resets, and avoid editing shared config during flow execution.
- Concurrency guard: avoid parallel Maven test execution in multiple validators; if running tests, execute assigned command set in isolation and keep scope to assertion-specific tests.
- Evidence minimum for each assertion: executed command/request, observed status/output, and explicit pass/fail/blocked reason.

## Flow Validator Guidance: docs
- Surface: repository documentation files (`README.md`, `docs/**`) validated via file reads and targeted content checks.
- Isolation: validators must only read docs and validation outputs; do **not** edit source files or regenerate docs during validation.
- Shared-state safety: avoid running heavy parallel validators that re-run full Maven suites simultaneously; reuse existing scrutiny/test outputs where relevant.
- Assertion checks: verify required files exist, required sections/keywords are present, and guidance explicitly matches contract language (including "NOT IMPLEMENTED" for design-only artifacts).
- Evidence minimum for each assertion: include command(s) used, exact observed section/headings/content snippets, and explicit pass/fail/blocked decision.
