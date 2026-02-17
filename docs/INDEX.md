# Docs Index (System of Record)

Last reviewed: 2026-02-15
Owner: Repo Cartographer + Orchestrator Agent

This is the canonical knowledge base entrypoint for agentic work in this repository.

## Repo Snapshot

### Detected stack
- Backend: Java 21 + Spring Boot 3.3.4 (`erp-domain/pom.xml`)
- Build/package manager: Maven (`erp-domain/pom.xml`)
- Persistence: Spring Data JPA + PostgreSQL (`erp-domain/pom.xml`)
- Migration tooling: Flyway (legacy `erp-domain/src/main/resources/db/migration` and active `erp-domain/src/main/resources/db/migration_v2`)
- Messaging/async: RabbitMQ + Quartz + Spring Batch + outbox/orchestrator modules
- Observability libs: Actuator + Micrometer tracing + OpenTelemetry OTLP exporter
- Test frameworks: JUnit 5 (Spring Boot test), Spring Security Test, Testcontainers (Postgres), Jacoco, PIT
- Deployment assets detected: Dockerfile + Docker Compose (`docker-compose.yml`, `erp-domain/Dockerfile`)
- CI detected: GitHub Actions (`.github/workflows/ci.yml`)

### Build / run / test (detected)
- Build jar: `cd erp-domain && mvn -B -ntp -DskipTests package`
- Run full local stack: `docker compose up --build`
- Run app directly (profile selection is environment-specific): `cd erp-domain && mvn -B -ntp spring-boot:run`
- Unit/integration tests: `cd erp-domain && mvn -B -ntp test`
- Repository harness: `bash scripts/verify_local.sh`
- Gate tiers: `bash scripts/gate_fast.sh`, `bash scripts/gate_core.sh`, `bash scripts/gate_release.sh`, `bash scripts/gate_reconciliation.sh`

### Domain mapping (requested modules -> actual paths)

| Requested domain | Canonical code path |
| --- | --- |
| accounting | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting` |
| inventory | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory` |
| sales | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales` |
| hr | `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr` |

`modules` does not exist at repository root; module overrides are defined in the closest equivalent paths above.

### Full backend module map (for agent exploration)
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/auth`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/company`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/demo`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/production`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/purchasing`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/rbac`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator` (cross-module workflow runtime)

### Frontend portal taxonomy and ownership map
- Supported frontend portals (exact set): `ADMIN`, `ACCOUNTING`, `SALES`, `FACTORY`, `DEALER`.
- Accounting Portal owns backend domains: `accounting`, `inventory`, `hr`, `reports`, `invoice`.
- Factory Portal owns backend domains: `factory`, `production`, `manufacturing`.
- TODO: if portal taxonomy changes, update this map and all `*-portal-*` docs in the same patch.

## Start Here
- Human alias map: `AGENTMAP.md`
- Alias-only legacy filename: `AGENT.MD` (not runtime)
- Agent map: `AGENTS.md`
- Final staging master plan: `docs/system-map/Goal/ERP_STAGING_MASTER_PLAN.md`
- Canonical enterprise stabilization plan: `docs/system-map/Goal/ENTERPRISE_BACKEND_STABILIZATION_PLAN.md`
- Deep enterprise deployment spec: `docs/system-map/Goal/ERP_ENTERPRISE_DEPLOYMENT_DEEP_SPEC.md`
- Architecture map: `ARCHITECTURE.md`
- Canonical architecture spec: `docs/ARCHITECTURE.md`
- Agent catalog (human): `docs/agents/CATALOG.md`
- Agent permissions: `docs/agents/PERMISSIONS.md`
- Agent lifecycle/workflow: `docs/agents/WORKFLOW.md`
- Enterprise autonomous policy: `docs/agents/ENTERPRISE_MODE.md`
- Orchestrator control plane: `docs/agents/ORCHESTRATION_LAYER.md`
- Active R2 checkpoint: `docs/approvals/R2-CHECKPOINT.md`
- R2 approval template: `docs/approvals/R2-CHECKPOINT-TEMPLATE.md`

## Risk and Reliability
- Security + governance + initial risk register: `docs/SECURITY.md`
- Reliability + observability + SLO placeholders: `docs/RELIABILITY.md`
- Rollback runbook: `docs/runbooks/rollback.md`
- Migration runbook: `docs/runbooks/migrations.md`

## CI and Mechanical Enforcement
- Existing gate workflow: `.github/workflows/ci.yml`
- Knowledge base lint: `ci/lint-knowledgebase.sh`
- Architecture boundary checks: `ci/check-architecture.sh`
- Enterprise near-deploy policy checks: `ci/check-enterprise-policy.sh`
- Orchestrator layer checks: `ci/check-orchestrator-layer.sh`
- Architecture ruleset: `ci/architecture/module-import-allowlist.txt`
- Allowlist evidence check: `ci/architecture/check-allowlist-change-evidence.sh`
- Doc lint workflow: `.github/workflows/doc-lint.yml`
- Codex review policy workflow: `.github/workflows/codex-review.yml`
- Codex autofix template workflow: `.github/workflows/codex-autofix.yml`

## Async Loop Operations
- Operational runbook: `docs/ASYNC_LOOP_OPERATIONS.md`
- Continuation prompt: `docs/ASYNC_LOOP_AGENT_HANDOFF_PROMPT.md`
- Active long-run ledger: `asyncloop`
- Orchestrator routing map: `agents/orchestrator-layer.yaml`

## Legacy + domain deep references
- Root map for module links: `erp-domain/docs/MODULE_FLOW_MAP.md`
- CODE-RED program index: `docs/CODE-RED/START_HERE.md`
- CI debugging contract: `docs/codex-cloud-ci-debugging-plan.md`

## Unknowns and TODOs
- Production deployment target (Kubernetes/VM/PaaS): unspecified.
  - TODO: inspect infra repo or deployment manifests and link definitive target here.
- Secret manager in production (Vault/KMS/SOPS/etc.): unspecified.
  - TODO: confirm with ops and add source-of-truth document link.
