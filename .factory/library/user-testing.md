# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, known quirks.

**What belongs here:** How to manually test the application, testing tools, setup steps, known issues.

---

## Security/Auth Mission Note
- Primary validation surface for this mission is **backend test evidence plus targeted API probes**.
- Start the local app only when a feature or validator needs runtime-assisted evidence.
- Preserve auth/admin contracts wherever possible; if a shape changes, verify the documented handoff note matches the runtime/API behavior.
- If delegated helper launches fail, perform the validation directly in-session and emit the expected validation artifacts manually.
- For orchestrator-side delegation, prefer the custom project droids approved by the user instead of built-in helper subagents.

## Testing Surface
- **Type**: REST API
- **Base URL**: http://localhost:8081
- **Actuator**: http://localhost:9090/actuator
- **MailHog UI**: http://localhost:8025

## Testing Tools
- `mvn` for compile and integration/regression suites
- `curl` for auth/admin API verification
- Docker Compose for `rabbitmq` and `mailhog` when runtime validation is needed

## Setup Steps
1. Run `/home/realnigga/Desktop/Mission-control/.factory/init.sh`.
2. When runtime evidence is needed, start the compose-backed mission services on `5433/5672/1025/8081/9090` rather than touching the unrelated local PostgreSQL on `5432`.
3. Start `db`, `rabbitmq`, and `mailhog` with `DB_PORT=5433 docker compose up -d db rabbitmq mailhog`.
4. Start the backend on `8081/9090` with the explicit Flyway v2 overrides from `.factory/services.yaml`.
5. Wait for health using either `curl -sf http://localhost:9090/actuator/health` or the fallback auth probe on `http://localhost:8081/api/v1/auth/me`.
6. Exercise auth/admin endpoints with `curl`.
7. For the compose-backed auth runtime currently on `8081`, the seeded `MOCK` tenant already has usable UAT actors (`uat.admin@example.com`, `uat.sales@example.com`, `uat.superadmin@example.com`). If their passwords drift, reset them directly in the local `erp_db` container with `crypt('<password>', gen_salt('bf'))` before user-testing.
8. To validate the **current code** on the compose-backed runtime after this milestone, rebuild and restart the app with explicit overrides so it uses Flyway v2, passes production CORS validation, and forces MailHog instead of any `.env` SMTP credentials: `SPRING_PROFILES_ACTIVE='prod,flyway-v2' ERP_CORS_ALLOWED_ORIGINS='https://app.bigbrightpaints.com' ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' DB_PORT=5433 SPRING_MAIL_HOST='mailhog' SPRING_MAIL_PORT='1025' SPRING_MAIL_USERNAME='' SPRING_MAIL_PASSWORD='' SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH='false' SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE='false' SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED='false' docker compose up -d --build app`.

## Known Issues
- Built-in helper subagent launches may fail with invalid model-alias errors; attempt once only when necessary, then fall back to direct in-session validation or approved custom project droids.
- Local runtime surfaces may be down until the backend is started explicitly for this mission.
- Local PostgreSQL on `5432` belongs to another local database; mission-owned compose runtime must use `5433` instead.
- The compose-backed `MOCK` runtime currently seeds UAT users, roles, and baseline master data, but does **not** preload linked invoice/GRN/purchase chains; milestone validators for workflow-truth assertions should expect to combine live login/API probes with targeted Maven truthsuite or service-contract evidence unless they deliberately create fresh transactional data.
- The rebuilt `backend-compose-v2` runtime on `prod,flyway-v2` can also boot against an empty local database with no seeded companies or UAT actors at all; if `docker exec erp_db psql -U erp -d erp_domain -c "select id,code from companies;"` and the same check against `app_users` both return zero rows, do **not** block on live login. Instead, use deterministic Spring Boot API/e2e suites (for example `DispatchOperationalBoundaryIT`, `DispatchConfirmationIT`, `ErpInvariantsSuiteIT`, `FactoryPackagingCostingIT`, `ReportInventoryParityIT`) or explicitly seed/reset the runtime first.
- For accounting provenance probes on the current `MOCK` runtime, prefer the canonical `/api/v1/accounting/audit/transactions` and `/api/v1/accounting/audit/transactions/{journalEntryId}` endpoints. The older `/api/v1/accounting/audit-trail` surface currently returns `500 SYS_001` in this environment.
- Starting a second local Spring Boot instance against the compose database on `127.0.0.1:5433/erp_domain` currently fails at Flyway startup because the schema is populated but only `flyway_schema_history_v2` exists; prefer the already-running compose app on `8081/9090` for runtime auth validation instead of a parallel local app process.
- Plain `docker compose up -d --build app` currently picks up `.env` `SPRING_PROFILES_ACTIVE=dev`, localhost HTTP CORS origins, and any persisted SMTP credentials, which makes the rebuilt app fail on either Flyway history detection, prod-profile origin validation, or runtime reset-email delivery. Use the explicit override command above when you need the runtime to reflect current repository code, and keep the `--build` flag because `up -d app` alone can leave you probing a stale image.
- The compose-backed app can report `503 {"status":"DOWN"}` on `http://localhost:9090/actuator/health` even while the auth/admin APIs on `8081` are usable; if that happens, confirm the target API endpoints directly before treating the runtime as unavailable.
- If runtime validation is unavailable, continue with compile/test evidence and record the limitation explicitly in synthesis.
- Inventory full-context tests may still hit `ConflictingBeanDefinitionException` around `inventoryValuationService`; prefer the auth/admin-targeted suites and `gate-fast` unless the feature truly needs that broader context.

## Flow Validator Guidance: api
- Verify login, refresh, logout, public forgot/reset, admin force-reset, support reset, must-change-password corridor, privileged user-control boundaries, and admin settings authz when the relevant feature claims those assertions.
- Preserve evidence for both allowed and denied boundary cases.
- Re-check frontend-sensitive auth/admin payloads whenever a DTO or error contract changes.
- For `o2c-truth` assertions, pair runtime-accessible API probes with deterministic O2C integration coverage: use `DealerServiceTest`/`SalesServiceTest` for dealer provisioning, credit posture, payment-mode, and shortage-to-production seams; use `DispatchControllerTest`, `DispatchOperationalBoundaryIT`, `DispatchConfirmationIT`, and `ErpInvariantsSuiteIT` for factory redaction, dispatch-owned invoicing, challan metadata/artifacts, and replay safety; use `FactoryPackagingCostingIT` plus `ReportInventoryParityIT` for carried-cost and valuation proof.
- For `VAL-RESET-003`, a temporary local `BEFORE INSERT` trigger on `password_reset_tokens` is a safe way to induce a real runtime persistence failure for a dedicated test user; the current fixed runtime surfaces that path as a controlled non-success `503 SYS_003` response instead of the normal `200 OK` forgot-password success contract.
- For `VAL-AUTHZ-005`, you can hold `SELECT ... FOR UPDATE` on the foreign target row in the local Postgres container while issuing the tenant-admin deny probe (for example `PATCH /api/v1/admin/users/3/suspend`); the fixed runtime should still return the masked `400 User not found` contract immediately rather than blocking on the foreign row lock.
- For shared-role mutation guard validation on `POST /api/v1/admin/roles`, use a system role name such as `ROLE_ADMIN` or `ROLE_SUPER_ADMIN`; arbitrary role names bypass the controller guard and fail later with `400 Unknown platform role ...`, which does not validate the intended authz boundary.
- For unknown stored tenant-lifecycle validation, protected endpoints such as `GET /api/v1/auth/me` and `GET /api/v1/admin/users` reflect the fail-closed behavior once the stored lifecycle is corrupted locally; restore both the row value and the `chk_companies_lifecycle_state` constraint after the probe.
