# User Testing

Validation surfaces, tools, setup rules, and concurrency guidance for the current `orchestrator-erp` cleanup mission.

**What belongs here:** how to validate strict runtime smoke, targeted business-flow proof, docs-only exceptions, and the highest-signal regression suites for this mission.

---

## Validation Surfaces

### 1. Strict runtime smoke

- **Type:** strict compose-backed API/runtime smoke
- **Base URL:** `http://localhost:8081`
- **Actuator:** `http://localhost:9090/actuator/health`
- **MailHog UI:** `http://localhost:8025`
- **Services:** Postgres `5433`, RabbitMQ `5672`, MailHog `8025`, app `8081`, actuator `9090`

This surface proves bootability and runtime boundary correctness, not the full business workflow by itself.

### 2. Targeted business-flow proof

- **Type:** Maven integration/regression/truthsuite proofs
- **Working directory:** `erp-domain/`
- **Primary tool:** `mvn`

This surface proves the risky business seams the strict runtime smoke does not cover cleanly yet.

### 3. Docs-only packets

- **Type:** no runtime/user-testing surface
- **Rule:** docs-only changes in this mission skip scrutiny and skip validators

Docs-only packets are not user-testing targets unless a feature explicitly overrides this.

## Validation Concurrency

- **strict-runtime:** max concurrent validators **1**
- **jvm-tests:** max concurrent validators **1**
- **docs-only:** max concurrent validators **0** (skip)

Rationale:

- the compose runtime uses fixed shared ports
- Maven/Surefire writes to the same checkout and `target/` tree
- using 70% of the observed resource headroom still leaves concurrency capped by shared-state contention rather than CPU/RAM

## Setup Steps

1. Run `bash .factory/init.sh`.
2. Ensure strict runtime env values are present for datasource, JWT, encryption, audit key, and mail settings.
3. Start the approved compose boundary from `.factory/services.yaml`.
4. Probe both:
   - `http://localhost:9090/actuator/health`
   - `GET http://localhost:8081/api/v1/auth/me`
5. Treat `GET /api/v1/auth/me` returning `200`, `401`, or `403` as the acceptable strict-smoke application-boundary proof.
6. Use targeted Maven suites for the touched risk area; do not rely on broad exploratory reruns unless a feature explicitly requires them.

## Runtime Probe Guidance

- Always pair the management-port probe with an app-boundary probe.
- `9090` proves management/readiness visibility.
- `8081/api/v1/auth/me` proves the app is actually serving requests.
- If actuator is degraded but the app boundary is alive, record both facts explicitly; do not silently treat the runtime as healthy.
- Do not use retired routes as bootstrap probes.

## High-Signal Proof Packs

### Dispatch and contract truth

- `DispatchControllerTest`
- `DispatchOperationalBoundaryIT`
- `DispatchConfirmationIT`
- `TS_O2CDispatchCanonicalPostingTest`
- `TS_O2COrchestratorDispatchRemovalRegressionTest`
- `OrderFulfillmentE2ETest`

### Accounting and concurrency

- `JournalEntryE2ETest`
- `AccountingControllerJournalEndpointsTest`
- `AccountingControllerIdempotencyHeaderParityTest`
- `CriticalAccountingAxesIT`
- `TS_RuntimeAccountingReplayConflictExecutableCoverageTest`
- `TS_RuntimeAccountingPayrollPostingExecutableCoverageTest`
- `CR_ManualJournalSafetyTest`
- `CR_PayrollIdempotencyConcurrencyTest`
- `NumberSequenceServiceIntegrationTest`
- `ReferenceNumberServiceTest`
- `TS_RuntimeReferenceNumberServiceExecutableCoverageTest`
- `OrderNumberServiceTest`
- `InvoiceNumberServiceTest`

### Security and tenant runtime

- `CompanyContextFilterControlPlaneBindingTest`
- `AuthTenantAuthorityIT`
- `TenantRuntimeEnforcementAuthIT`
- `TS_RuntimeCompanyContextFilterExecutableCoverageTest`
- `TS_RuntimeTenantRuntimeEnforcementTest`
- `TS_RuntimeTenantControlPlaneEnforcementTest`
- `CompanyControllerIT`
- `OpenApiSnapshotIT`

### Dependent-module accounting consumers

- `ProcureToPayE2ETest`
- `InventoryAccountingEventListenerIT`
- `CreditDebitNoteIT`
- `ReconciliationControlsIT`
- `TS_PeriodCloseBoundaryCoverageTest`
- `TS_PeriodCloseAtomicSnapshotTest`

### Deployment and release hardening

- `bash scripts/gate_release.sh`
- `CR_ProductionMonitoringContractTest`
- `CR_HealthEndpointProdHardeningIT`
- `CR_ActuatorProdHardeningIT`
- contract guards from `.factory/services.yaml`

## Helpful Manifest Commands

- `commands.strict-runtime-smoke-check`
- `commands.contract-guards`
- `commands.targeted-dispatch-proof`
- `commands.targeted-accounting-proof`
- `commands.targeted-security-proof`
- `commands.targeted-dependent-proof`
- `commands.targeted-deploy-hardening`
- `commands.gate-release`

## Validator Guidance

- Prefer this order of proof:
  1. strict runtime smoke
  2. targeted Maven suites for the touched risk area
  3. `gate-release` and deploy-hardening proofs when release readiness is in scope
  4. contract/OpenAPI/docs alignment checks
- If a feature only changes docs/governance, do not start services and do not run user-testing.
- If a feature touches accounting, dispatch, runtime security, CI proof, or canonical contracts, explicitly re-run the dependent proof pack for that area.
- When validating dispatch, the canonical public write is `POST /api/v1/dispatch/confirm`.
- When validating auth bootstrap, the canonical surface is `GET /api/v1/auth/me`; `/api/v1/auth/profile` is retired.
- When validating runtime policy mutation, the canonical public write surfaces are the superadmin tenant lifecycle/limits routes.

## Known Constraints

- The current strict compose path is a smoke surface only; authenticated business-flow proof still depends on targeted Maven suites unless a later feature introduces a clean bootstrap/auth fixture path.
- Direct `docker compose up` still parses the app service, so missing env values can break even dependency-only starts.
- Old library/docs guidance may still reference retired routes until the mission cleans them; prefer `validation-contract.md`, `openapi.json`, and current controller annotations if guidance disagrees.
- Running Maven outside `erp-domain/` can break `.mvn` resolution.
