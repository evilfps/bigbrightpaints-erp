# Ops / deployment / runtime

## Scope and evidence

This review covers the runtime topology, profile boundaries, environment and secret posture, health and management surfaces, observability hooks, resilience/recovery behavior, and operational assumptions for the backend deployment footprint.

Primary evidence:

- `docker-compose.yml`
- `.env.example`
- `.env.prod.template`
- `README.md`
- `docs/developer-guide.md`
- `erp-domain/Dockerfile`
- `erp-domain/scripts/ops_smoke.sh`
- `erp-domain/src/main/resources/{application.yml,application-dev.yml,application-prod.yml,application-flyway-v2.yml,application-seed.yml,application-seed-flyway-v2.yml,application-benchmark.yml}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/security/{SecurityConfig,JwtProperties,CryptoService,LicensingGuard}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/{SystemSettingsService,SmtpPropertiesValidator,EmailProperties}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/health/{RequiredConfigHealthIndicator,ConfigurationHealthIndicator,ConfigurationHealthService}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/config/CorsConfig.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/EnterpriseAuditTrailService.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/{config/DispatchMappingHealthIndicator.java,service/EventPublisherService.java,scheduler/OutboxPublisherJob.java}`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/{sales/service/SalesCoreEngine.java,accounting/event/AccountingEventStore.java}`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/codered/{CR_ActuatorProdHardeningIT,CR_ProductionMonitoringContractTest}.java`
- `.factory/services.yaml`
- `.github/workflows/ci.yml`
- `scripts/gate_release.sh`

Supporting runtime evidence gathered in this session:

- `curl -i -sS http://localhost:8081/actuator/health` returned HTTP `404` with `"No static resource actuator/health."`.
- `curl -i -sS http://localhost:9090/actuator/health` returned HTTP `200` with `{"status":"UP","groups":["liveness","readiness"]}`.
- `curl -i -sS http://localhost:9090/actuator/health/readiness` returned HTTP `200` with `{"status":"UP"}`.
- `curl -i -sS http://localhost:9090/actuator/metrics` and `/actuator/prometheus` both returned HTTP `403` anonymously.
- A TCP probe to `127.0.0.1:5433` succeeded in this session, while earlier mission validation artifacts under `.factory/validation/**` recorded `ConnectionRefusedError` on the same port.

## Runtime boundaries

### Compose topology and service ownership

`docker-compose.yml` defines a single, all-in-one stack with no Compose `profiles:` segmentation. Starting the file as written brings up the full local/runtime dependency set together:

| Surface | Port(s) | Source of truth | Operational meaning |
| --- | --- | --- | --- |
| PostgreSQL | `5432` (host override via `DB_PORT`) | `docker-compose.yml` | Hard state store for all ERP data and Flyway migration state. Largest blast radius. |
| RabbitMQ | `5672`, management `15672` | `docker-compose.yml` | Hard async/event dependency for orchestrator outbox publication and broker-backed integrations. |
| MailHog | `1025`, UI `8025` | `docker-compose.yml` | Local-only SMTP sink; not a production mail posture. |
| App API | `8081` | `docker-compose.yml`, `application-prod.yml` | Main REST/API surface. |
| Management/Actuator | `9090` | `docker-compose.yml`, `application-prod.yml` | Separate health/metrics/prometheus/tracing surface. |
| Validation / benchmark Postgres | `5433` | `.factory/services.yaml`, `application-benchmark.yml` | Not part of Compose runtime; used by benchmark/validation tooling and therefore outside the main production blast radius. |

Two important deployment boundaries fall out of that layout:

1. The repository models the app and its stateful dependencies as one local blast-radius unit; there is no Compose-level split between mandatory services (`db`, `rabbitmq`, `app`) and optional/local-only aids (`mailhog`).
2. The app artifact itself has multiple profile personalities, but they are selected through Spring profiles, not different manifests. The same image can behave as `dev`, `prod`, `seed`, `flyway-v2`, or `benchmark` depending on env.

### Profile boundaries

- `application.yml` defaults Spring to `prod`, and `spring.profiles.group.prod` auto-adds `flyway-v2`, so a production-like boot implicitly points Flyway at `db/migration_v2`.
- `application-dev.yml` keeps the API on `8081`, makes Swagger public, disables Rabbit health, and keeps the runtime friendlier for local work.
- `application-prod.yml` moves the API to `8081`, management to `9090`, disables Swagger/OpenAPI, enables graceful shutdown, exposes metrics/prometheus/OTLP, and turns `erp.inventory.accounting.events.enabled` off by default.
- `application-seed.yml` and `application-seed-flyway-v2.yml` are bootstrap overlays for first-boot seeding, not steady-state runtime modes.
- `application-benchmark.yml` is a separate non-production runtime on `8082` / management `9091` and DB `5433`; it deliberately weakens date-validation and CORS rules for benchmark use.

### Release/runtime boundaries

- Local runtime uses Compose plus `.env` injection.
- CI release validation in `.github/workflows/ci.yml` does **not** deploy the Compose stack. `gate-release` spins a GitHub Actions Postgres service on `5432`, and `scripts/gate_release.sh` can fall back to an isolated temporary container on `55432` when needed.
- That means failures on the mission's validator port `5433` reduce local review/test confidence, but they do not describe the official release pipeline's database dependency.
- TLS termination, reverse proxying, backup policy, firewalling, and secret distribution are assumed external; the repository does not define infrastructure-as-code for those boundaries.

## Env and secrets

### Secret posture strengths

- `docker-compose.yml` refuses to start the app unless `JWT_SECRET` and `ERP_SECURITY_ENCRYPTION_KEY` are provided.
- `JwtProperties` hard-fails non-test runtime when the JWT secret is missing, shorter than 32 bytes, or still using a known placeholder.
- `CryptoService` hard-fails if `erp.security.encryption.key` is absent.
- `SmtpPropertiesValidator` fail-closes production boots (`prod & !seed`) when SMTP host/user/password are missing and SMTP auth is enabled.
- `RequiredConfigHealthIndicator` also marks readiness down when JWT, encryption, license, or SMTP material is incomplete.

### Secret posture gaps

- `docker-compose.yml` gives `ERP_SECURITY_AUDIT_PRIVATE_KEY` a default of `dev-audit-private-key`, while `EnterpriseAuditTrailService` only checks for non-blank input. A production-like deployment launched from Compose without overriding that value would share a static audit-signing secret.
- `.env.prod.template` still uses placeholder DB, SMTP, JWT, encryption, license, GitHub, and audit values, and keeps RabbitMQ at `guest/guest`. The posture is template-driven rather than secret-manager-driven.
- Secret distribution is plain env-file / process-environment based. There is no repository evidence of Docker secrets, Vault/KMS integration, or per-service secret scoping.
- `ERP_ENVIRONMENT_VALIDATION_ENABLED` defaults to `false` in `docker-compose.yml` even though `.env.prod.template` sets it to `true`. If an operator reuses Compose defaults outside a tightly controlled local environment, configuration safety checks start from the relaxed posture.

### CORS and protocol controls

`SystemSettingsService` + `CorsConfig` make CORS runtime-configurable from persisted system settings instead of static YAML only. That is flexible, but it also means the deployed DB contents become part of the runtime security boundary.

- In prod, wildcard origins are rejected and HTTP origins are rejected unless the origin is an explicitly allowed Tailscale address and `erp.cors.allow-tailscale-http-origins=true`.
- When `erp.environment.validation.enabled=false`, non-prod/private-network HTTP origins become acceptable, which is useful for LAN testing but weakens protocol strictness.
- `management.endpoints.web.cors.allowed-origins` is separately scoped to `MANAGEMENT_CORS_ALLOWED_ORIGINS`, so the ops surface can and should be fenced differently from the public API surface.

## Health endpoints and management ports

The runtime intentionally separates business traffic from operational traffic:

- API traffic is expected on `8081`.
- Actuator health, readiness, metrics, and Prometheus are expected on `9090` in prod.
- `SecurityConfig` permits `/actuator/health` and `/actuator/health/**`, but does not open metrics or Prometheus anonymously.

Current session evidence matches that split:

| Probe | Observed result | Implication |
| --- | --- | --- |
| `http://localhost:8081/actuator/health` | HTTP `404` | App-port health probes are not reliable on the prod-like local surface. |
| `http://localhost:9090/actuator/health` | HTTP `200`, `UP` | Management port is the real health source in this session. |
| `http://localhost:9090/actuator/health/readiness` | HTTP `200`, `UP` | Readiness group is live and routable. |
| `http://localhost:9090/actuator/metrics` | HTTP `403` | Metrics are exposed but not anonymously readable. |
| `http://localhost:9090/actuator/prometheus` | HTTP `403` | Prometheus scrape surface exists but is protected here. |

Health composition in `application-prod.yml` is substantial:

- `liveness`: `livenessState`, `ping`, `diskSpace`
- `readiness`: `readinessState`, `db`, `rabbit`, `diskSpace`, `requiredConfig`, `configuration`, `dispatchMapping`

That gives ops useful fail-closed signals for missing secrets, missing dispatch-account wiring, and company/accounting bootstrap problems, but two constraints matter:

1. Compose does not declare an `app` healthcheck or restart policy, so these readiness signals are not fed back into container orchestration inside `docker-compose.yml`.
2. Repository guidance is inconsistent: `README.md`, `.factory/services.yaml`, and earlier validation artifacts still treat `8081/actuator/health` as the canonical probe, while `docs/developer-guide.md` and `erp-domain/scripts/ops_smoke.sh` correctly target `9090`.

## Observability

The repo has a meaningful but incomplete observability story.

### What is present

- Actuator exposes `health`, `info`, `metrics`, and `prometheus` on the dedicated management port in prod.
- OTLP tracing is wired through `management.otlp.tracing.endpoint` with `management.tracing.sampling.probability`.
- `logging.file.name=logs/erp-backend.log` gives a file-based log target in addition to console output.
- Business metrics exist in code: `SalesCoreEngine` emits `erp.business.orders.processed*`, `AccountingEventStore` emits `erp.business.journals.created*`, `EventPublisherService` registers `outbox.events.*` gauges, and `TenantRuntimeEnforcementService` publishes `tenant.runtime.requests.*` gauges.
- `CR_ProductionMonitoringContractTest` hard-checks the existence of the production monitoring settings and metric names, so the monitoring contract is partly guarded in tests.

### What is missing or fragile

- The Dockerfile only `EXPOSE`s `8081`, not `9090`, so container platforms that infer network surfaces from image metadata will miss the management port unless operators override them explicitly.
- `management.info.env.enabled=false` hides env detail in prod, which is good for privacy, but also means runtime debugging leans heavily on health indicators and logs.
- The observability surface is inconsistent across docs and validators: some tools probe `8081`, others probe `9090`, and benchmark mode uses `9091`.
- The repository does not include dashboarding, alert rules, scrape config, or log shipping manifests, so the hooks stop at emitter endpoints rather than a full operational pipeline.

## Runtime dependencies and blast radius analysis

| Dependency / surface | Hard or soft | Blast radius if degraded | Evidence |
| --- | --- | --- | --- |
| PostgreSQL `5432` | Hard | Total ERP outage plus migration/boot failure; readiness fails and all transactional modules lose state access. | `docker-compose.yml`, `application*.yml`, `ConfigurationHealthService` |
| RabbitMQ `5672` | Hard for readiness / async flows | Outbox publication, async integrations, and some orchestration paths degrade or backlog; readiness includes `rabbit`. | `docker-compose.yml`, `application-prod.yml`, `EventPublisherService` |
| Management port `9090` | Operationally hard, business-soft | APIs may still serve, but health/metrics/tracing become blind, slowing incident response and autoscaling decisions. | `application-prod.yml`, runtime curls, `CR_ActuatorProdHardeningIT` |
| SMTP / MailHog | Soft for core ERP, hard for notifications | Login still works, but password reset, credential delivery, and support notifications fail or queue. | `docker-compose.yml`, `SmtpPropertiesValidator`, `EmailProperties` |
| RabbitMQ UI `15672` and MailHog UI `8025` | Soft/local-only | No direct business outage, but exposed UIs widen attack surface if not network-restricted. | `docker-compose.yml` |
| Validation/benchmark DB `5433` | Non-production support surface | Review confidence, benchmark runs, and local validator probes degrade; core production path is unaffected. | `.factory/services.yaml`, `application-benchmark.yml`, `.github/workflows/ci.yml` |

The biggest structural blast-radius issue is that the repository's default deployment story is a single Compose boundary. There is no repo-level orchestration that isolates broker, database, app, and local mail tooling into separate fault domains or rollout strategies.

## Resilience and recovery

### Built-in resilience strengths

- `depends_on` with health-gated startup prevents the app container from starting until Postgres and RabbitMQ are healthy.
- Prod config enables graceful shutdown with `server.shutdown=graceful` and a 30-second shutdown phase timeout.
- Persistent volumes exist for Postgres and RabbitMQ, so container recreation does not automatically wipe database or broker state.
- `EventPublisherService` + `OutboxPublisherJob` provide durable outbox retries, dead-letter accounting, and ShedLock-backed publish coordination.
- `EnterpriseAuditTrailService` maintains retry queues for business-audit persistence.
- Readiness includes `requiredConfig`, `configuration`, and `dispatchMapping`, so missing runtime prerequisites can fail closed before operators trust the node.

### Recovery limits and assumptions

- `docker-compose.yml` has no `restart:` policy and no `app` healthcheck, so operator recovery is still manual at the container/runtime layer.
- Only Postgres and RabbitMQ have persistent volumes. App logs, container filesystem state, and MailHog messages are ephemeral.
- Earlier orchestrator review evidence shows ambiguous outbox states are preserved for manual reconciliation rather than replayed automatically; that is safer than duplicate side effects, but it means recovery depends on operator intervention.
- GitHub ticket sync, digest jobs, and some other scheduled flows log or persist failure markers but do not offer a first-class replay/control-plane surface.
- The repo assumes external reverse proxying, TLS, firewalling, and backups. None of those recovery controls are codified here.

Operationally, the system is resilient at the application-semantic layer (idempotency, outbox, cached configuration health), but only moderately resilient at the deployment layer (no orchestrator-managed restarts, no declared backup/restore workflow, no automated recovery surface for stuck async state).

## Degraded review surfaces and operational implications

The mission environment has shown **intermittent and inconsistent** review surfaces rather than one stable runtime picture.

| Surface | Expected by config/docs | Degraded evidence already recorded in mission artifacts | Current session observation | Operational implication |
| --- | --- | --- | --- | --- |
| `8081` health | `README.md` and `.factory/services.yaml` still point to `http://localhost:8081/actuator/health` | Earlier foundation/commercial validation runs recorded curl exit `7`; current mission brief called out intermittent `8081` failures | HTTP `404` on `/actuator/health` | App-port health guidance is wrong or stale for prod-like runtime. Reviewers and operators can get false negatives even when the app is alive. |
| `9090` management | `application-prod.yml`, `docs/developer-guide.md`, and `ops_smoke.sh` expect health here | Mission guidance previously recorded `9090` as unavailable during some review passes | HTTP `200` for `/actuator/health`, HTTP `403` for metrics/prometheus | The management port exists, but its intermittent availability means best-effort runtime evidence is less trustworthy than static config evidence. |
| `5433` validator DB | `.factory/services.yaml` and `application-benchmark.yml` use it as a non-prod support surface | Earlier validation artifacts recorded `ConnectionRefusedError` | TCP connect succeeded in this session | Local validation/benchmark readiness is unstable across sessions; this hurts evidence collection and confidence, but it does not describe the official release path. |

The degraded-state implication is not just “some probes failed.” It changes the confidence model:

- health evidence must be interpreted against the correct port boundary (`9090`, not `8081`);
- intermittent `9090`/`5433` reachability means passive probe success today does not prove steady-state operational reliability;
- docs-only assertions remain verifiable, but resilience claims beyond static configuration should be treated as partially evidenced rather than fully runtime-proven.

## Ops / runtime risks

| Severity | Category | Finding | Evidence | Why it matters |
| --- | --- | --- | --- | --- |
| high | security / integrity | Compose defaults `ERP_SECURITY_AUDIT_PRIVATE_KEY` to `dev-audit-private-key`, while `EnterpriseAuditTrailService` only checks that the value is non-blank. | `docker-compose.yml`, `EnterpriseAuditTrailService` | A production-like deployment launched from Compose without overriding this value would share a static audit-signing secret across environments, weakening trust in signed audit payloads. |
| high | observability / runtime drift | Repository guidance disagrees on the canonical health probe: some surfaces still probe `8081`, but prod config and live management health are on `9090`. | `README.md`, `.factory/services.yaml`, `docs/developer-guide.md`, `erp-domain/scripts/ops_smoke.sh`, runtime curls | Health automation and incident triage can fail or flap even when the service is otherwise up, because the wrong port is being probed. |
| medium | deployment boundary | The image exposes only `8081`; the management port `9090` is configured in Spring and Compose but not declared in the Docker image metadata. | `erp-domain/Dockerfile`, `application-prod.yml`, `docker-compose.yml` | Platforms that depend on image metadata or default port discovery can miss the management surface entirely, silently removing health/metrics access. |
| medium | security / protocol | Compose defaults disable environment validation and therefore loosen CORS acceptance for private-network HTTP origins; prod template expects validation on. | `docker-compose.yml`, `.env.prod.template`, `SystemSettingsService`, `SystemSettingsServiceCorsTest` | Reusing local Compose defaults in a shared or semi-production environment can weaken origin hardening and allow unsafe operational shortcuts to persist unnoticed. |
| medium | resilience | Compose wires startup health for DB/Rabbit only; there is no app healthcheck, restart policy, or codified recovery workflow. | `docker-compose.yml` | Service recovery relies on manual operator action even though the app emits meaningful readiness signals. |
| medium | security / secrets | `.env.prod.template` still carries placeholder DB/SMTP/JWT/encryption/license/GitHub values and Rabbit `guest/guest`; there is no in-repo secret manager or Docker secret usage. | `.env.prod.template`, `JwtProperties`, `SmtpPropertiesValidator`, `LicensingGuard` | The deployment posture is only as strong as external secret replacement discipline. A missed substitution can boot weakly or fail late. |
| medium | blast radius | One Compose file starts DB, broker, mail sink, and app together with no service-class isolation and no optional-service profile gating. | `docker-compose.yml` | Operational mistakes or host-level resource pressure affect the whole runtime unit instead of isolated components. |
| low | validation confidence | `5433` is a non-prod validator/benchmark dependency with intermittent reachability across sessions. | `.factory/services.yaml`, `application-benchmark.yml`, `.factory/validation/**`, current TCP probe | Runtime review evidence gathered from validator infrastructure can be inconsistent, so later synthesis should not overclaim end-to-end resilience proof from local probes alone. |
