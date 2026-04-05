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
- **Rule:** docs-only changes in this mission skip scrutiny and runtime/user-testing validators, but still require `bash ci/lint-knowledgebase.sh` as the sole docs-only check

Docs-only packets are not user-testing targets unless a feature explicitly overrides this. The only docs-only lanes in this mission are the canonical docs/governance surfaces and `.factory/library/**` guidance-only packets.

### 4. Repo-static docs/governance validation

- **Type:** read-only repository inspection
- **Tools:** `Read`, `Grep`, `LS`, and narrowly scoped shell reads when needed
- **Use when:** a milestone validates canonical docs, retirement registry, repo hygiene, or docs-only governance rules without exercising the running app

This surface validates what a reader or release operator sees in the repo right now. It does not start services unless an assertion explicitly requires runtime proof.

## Validation Concurrency

- **strict-runtime:** max concurrent validators **1**
- **jvm-tests:** max concurrent validators **1**
- **docs-only:** max concurrent validators **0** (skip)
- **repo-static:** max concurrent validators **2**

Rationale:

- the compose runtime uses fixed shared ports
- Maven/Surefire writes to the same checkout and `target/` tree
- repo-static validation is read-only, so two validators can inspect separate assertion groups concurrently without state collisions
- using 70% of the observed resource headroom still leaves concurrency capped by shared-state contention rather than CPU/RAM

## Setup Steps

1. Run `bash .factory/init.sh`.
2. Ensure strict runtime env values are present for datasource, JWT, encryption, audit key, and mail settings. For compose-backed validation, set the datasource explicitly to `jdbc:postgresql://db:5432/erp_domain` with the compose credentials so the app container does not fall back to the host-only `localhost:5432` value from `.env`.
3. Start the approved compose boundary from `.factory/services.yaml`.
4. Probe both:
   - `http://localhost:9090/actuator/health`
   - `GET http://localhost:8081/api/v1/auth/me`
5. Treat `GET /api/v1/auth/me` returning `200`, `401`, or `403` as the acceptable strict-smoke application-boundary proof.
6. Use targeted Maven suites for the touched risk area; do not rely on broad exploratory reruns unless a feature explicitly requires them.
7. For repo-static docs/governance validation, skip service startup and inspect the canonical files, retirement registry, and governance scripts directly from the checkout.

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
- `CR_DealerReceiptSettlementAuditTrailTest`
- `CR_PayrollIdempotencyConcurrencyTest`
- `CR_PurchasingToApAccountingTest`
- `NumberSequenceServiceIntegrationTest`
- `ReferenceNumberServiceTest`
- `CR_SalesReturnCreditNoteIdempotencyTest`
- `TS_RuntimeReferenceNumberServiceExecutableCoverageTest`
- `OrderNumberServiceTest`
- `InvoiceServiceTest`
- Negative no-sleep scan for the current accounting-core hard-cut: `erp-domain/src/main/java/com/bigbrightpaints/erp/core/service/NumberSequenceService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/OrderNumberService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/invoice/service/InvoiceNumberService.java`, `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/internal/AccountingCoreEngineCore.java`, and `erp-domain/src/test/java/com/bigbrightpaints/erp/e2e/accounting/CriticalAccountingAxesIT.java` must not retain `Thread.sleep` in the canonical accounting proof path.

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
- `commands.release-proof`

## Validator Guidance

- Prefer this order of proof:
  1. strict runtime smoke
  2. `gate-release`
  3. targeted Maven suites for the touched risk area
  4. deploy-hardening plus contract/OpenAPI/docs alignment checks
  5. `release-proof` when a packet must prove the full staging/release lane end-to-end
- If a feature only changes docs/governance, do not start services and do not run user-testing.
- For docs-only packets, require `bash ci/lint-knowledgebase.sh` but do not escalate to runtime smoke or flow validation.
- If a feature touches accounting, dispatch, runtime security, CI proof, or canonical contracts, explicitly re-run the dependent proof pack for that area.
- When validating dispatch, the canonical public write is `POST /api/v1/dispatch/confirm`.
- When validating auth bootstrap, the canonical surface is `GET /api/v1/auth/me`; `/api/v1/auth/profile` is retired.
- When validating runtime policy mutation, the canonical public write surfaces are the superadmin tenant lifecycle/limits routes.

## Known Constraints

- The current strict compose path is a smoke surface only; authenticated business-flow proof still depends on targeted Maven suites unless a later feature introduces a clean bootstrap/auth fixture path.
- Direct `docker compose up` still parses the app service, so missing env values can break even dependency-only starts; export `ERP_SECURITY_AUDIT_PRIVATE_KEY` even when starting only `db`, `rabbitmq`, or `mailhog`.
- Old library/docs guidance may still reference retired routes until the mission cleans them; prefer `validation-contract.md`, `openapi.json`, and current controller annotations if guidance disagrees.
- Running Maven outside `erp-domain/` can break `.mvn` resolution.

## Flow Validator Guidance: strict-runtime

- Use the already-started compose boundary on `localhost:8081` and `localhost:9090`; do not restart, rebuild, or stop shared services from inside the flow validator.
- Restrict probes to canonical validation endpoints and absence checks needed for the assigned assertions. Record the exact HTTP status and any response body snippets needed to explain the result.
- Treat auth-required responses (`401`/`403`) as valid boundary evidence only for the bootstrap probe defined in this mission. Do not use retired routes as liveness checks.
- Stay within the approved runtime boundary (`5433`, `5672`, `8025`, `8081`, `9090`) and write only flow reports/evidence files.

## Flow Validator Guidance: jvm-tests

- Run only targeted Maven proof packs that map to the assigned assertions; for dispatch hard-cut validation, prefer `commands.targeted-dispatch-proof`, `commands.targeted-orchestrator-dispatch-proof`, and `commands.contract-guards`.
- Execute Maven from `erp-domain/` and do not run `mvn clean`, broad exploratory suites, or snapshot-refresh commands.
- Treat the checkout and `target/` trees as shared mutable state: keep JVM-test validators serialized and do not edit source files during validation.
- Record the exact test classes exercised and the specific behaviors they proved for each assigned assertion.

## Flow Validator Guidance: repo-static

- Stay read-only inside the assigned checkout; do not edit product files while validating assertions.
- Use direct evidence from the current repo state: canonical docs, retirement registry entries, repo-root surfaces, and governance scripts.
- Treat canonical spine assertions as link-and-classification checks: follow the reader path from `README.md`, root `ARCHITECTURE.md`, and `docs/INDEX.md` into the current docs spine and record where those paths land.
- Treat repo-hygiene assertions as retention checks: confirm that any repo-root or docs-root worklog/artifact surface that still exists has an explicit live governance or script reason.
- Treat docs-only governance assertions as executable-policy checks: compare the policy text in mission/root guidance against `scripts/enforce_codex_review_policy.sh` and record any mismatch.
- Safe concurrency boundary: multiple repo-static validators may read the same checkout concurrently, but each validator must write only its own flow report and evidence files.
