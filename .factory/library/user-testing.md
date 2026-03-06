# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, known quirks.

**What belongs here:** How to manually test the application, testing tools, setup steps, known issues.

---

## Security/Auth Mission Note
- Primary validation surface for this mission is **backend test evidence plus targeted API probes**.
- Start the local app only when a feature or validator needs runtime-assisted evidence.
- Preserve auth/admin contracts wherever possible; if a shape changes, verify the documented handoff note matches the runtime/API behavior.
- If delegated helper launches fail, perform the validation directly in-session and emit the expected validation artifacts manually.

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
2. Confirm the local PostgreSQL dependency on `5432` is reachable.
3. When runtime evidence is needed, start `rabbitmq` and `mailhog`.
4. Start the backend on `8081/9090`.
5. Wait for health: `curl -sf http://localhost:9090/actuator/health`.
6. Exercise auth/admin endpoints with `curl`.

## Known Issues
- Delegated validator/reviewer helpers may fail with `Invalid model: custom:CLIProxyAPI-5.4-xhigh`; attempt once if required by procedure, then fall back to direct in-session validation.
- Local runtime surfaces may be down until the backend is started explicitly for this mission.
- Local PostgreSQL on `5432` is reused by this mission; do not start another database on the same port.
- If runtime validation is unavailable, continue with compile/test evidence and record the limitation explicitly in synthesis.
- Inventory full-context tests may still hit `ConflictingBeanDefinitionException` around `inventoryValuationService`; prefer the auth/admin-targeted suites and `gate-fast` unless the feature truly needs that broader context.

## Flow Validator Guidance: api
- Verify login, refresh, logout, public forgot/reset, admin force-reset, support reset, must-change-password corridor, privileged user-control boundaries, and admin settings authz when the relevant feature claims those assertions.
- Preserve evidence for both allowed and denied boundary cases.
- Re-check frontend-sensitive auth/admin payloads whenever a DTO or error contract changes.
