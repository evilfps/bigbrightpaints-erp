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
- **Correlation rule:** when one O2C/P2P proof spans multiple HTTP requests but must emit one flow-stable audit correlation id, reuse the same `X-Correlation-Id` header across every request in that flow and verify the audit read surface returns that exact value on the related rows

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
- `AccountingEndpointContractTest`
- `SettlementControllerIdempotencyHeaderParityTest`
- `ReconciliationControllerSessionEndpointsTest`
- `StatementReportControllerTaxEndpointsTest`
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
- `bash scripts/reset_final_validation_runtime.sh` reliably rebuilds the local compose boundary, but the compose-built app still comes up without seeded `app_users` in the database (`select count(*) from app_users;` returned `0` during M2 validation, and the issue still reproduced during M4 validation). Treat its seeded-actor banner as non-authoritative for now and verify auth fixtures before planning authenticated runtime proof.
- M7 runtime note (2026-04-07): the same `app_users = 0` condition still reproduced after `reset_final_validation_runtime.sh`. The working workaround was to insert a PLATFORM-scoped `ROLE_SUPER_ADMIN` + `ROLE_ADMIN` user directly into `app_users`/`user_roles`, then use `POST /api/v1/superadmin/tenants/onboard` to create isolated tenant fixtures. Also note that `GET /api/v1/superadmin/tenants/coa-templates` currently returns `403 COMPANY_CONTROL_ACCESS_DENIED` because `CompanyContextFilter` misclassifies `coa-templates` as a company-id control route; use the known template code `INDIAN_STANDARD` instead of discovering it from that endpoint. Tenant-admin/dealer bootstrap passwords still arrive through MailHog and can be normalized with `POST /api/v1/auth/password/change`.
- M7 rerun note (2026-04-08): the reset banner is still non-authoritative for more than actors alone. A fresh `reset_final_validation_runtime.sh` run still reported seeded actors even though `companies = 0`, `app_users = 0`, and `invoices = 0` in `erp_db`, so strict-runtime invoice/PDF proofs had to restore a minimal MOCK fixture (admin/accounting/sales users + dealer + invoice) directly in the database before live API validation could continue.
- M8 reconciliation rerun note (2026-04-08): `GET /api/v1/accounting/reconciliation/subledger` can fail on the local validation runtime if the active tenant has `companies.state_code = null` or if the latest `OPEN` accounting period is future-dated. For the M8VAL fixture, setting `state_code='KA'` and closing the stray May 2026 period let subledger discrepancy sync succeed against April 2026. Inter-company reconciliation also needs at least one second company row/tenant in the runtime; otherwise the endpoint returns `companyBId=null` with no pairings.
- M10 runtime note (2026-04-09): local compose strict runtime was aligned so once `GET /api/v1/auth/me` returns an app-boundary status (`200/401/403`), `http://localhost:9090/actuator/health` and `/actuator/health/readiness` should also return `200 UP` under the validation compose profile (`erp.environment.validation.enabled=false`). Treat any recurring `503/503` alongside a usable app boundary as a regression and capture endpoint status evidence.
- M9 audit note (updated 2026-04-08/09): the public `GET /api/v1/accounting/audit/events` surface is suitable for event/action/source-module coverage and may now include `correlationId` in feed rows on the current runtime, but the authoritative proof for one flow-stable correlation across related accounting rows is still `GET /api/v1/accounting/audit/transactions/{journalEntryId}`. Use the transaction-detail `eventTrail` to compare correlation ids across linked O2C/P2P journals.
- Current M4 runtime workaround: after `reset_final_validation_runtime.sh`, manually seed at least one PLATFORM super-admin (and, if useful, a MOCK admin/accounting user) directly into `erp_db.app_users` plus `user_roles`, then authenticate through `/api/v1/auth/login` and use the super-admin to create fresh tenant data through `/api/v1/superadmin/tenants/onboard`. MailHog (`http://localhost:8025`) captures the tenant-admin credential email, which is the cleanest way to obtain the temporary password for the newly onboarded company.
- M4 period-close validation needs two tenant actors: `POST /api/v1/accounting/periods/{id}/request-close` can be performed by a tenant `ROLE_ACCOUNTING` user, while `POST /api/v1/accounting/periods/{id}/approve-close` must be performed by a different tenant `ROLE_ADMIN` user or the API returns `400 Maker-checker violation: requester and reviewer cannot be the same actor`. Creating the extra tenant accounting user via `POST /api/v1/admin/users` works; MailHog again provides the temporary password that must be rotated before use.
- `POST /api/v1/accounting/periods/{id}/reopen` does work with a tenant-scoped `ROLE_SUPER_ADMIN` token, but the global PLATFORM super-admin is still denied on tenant business routes with `SUPER_ADMIN_PLATFORM_ONLY`. For local validation, seed `ROLE_SUPER_ADMIN` onto a tenant-scoped validation user in `app_users/user_roles` if no product surface exists to mint that actor.
- M4 rerun note (2026-04-07): after rotating the emailed tenant-admin password on a freshly onboarded tenant, both `GET /api/v1/accounting/month-end/checklist?periodId={id}` and `POST /api/v1/accounting/month-end/checklist/{periodId}` returned `200` with canonical checklist data on tenant `UTM4R2P`.
- M4 rerun note (2026-04-07): `POST /api/v1/inventory/opening-stock` is now enabled in the compose-backed validation runtime; fresh-tenant proof on `UTM4R2O` returned `200`, and the posted opening-stock journal was visible through `GET /api/v1/inventory/opening-stock` plus `GET /api/v1/accounting/audit/transactions`.
- M5 runtime note (2026-04-07): procurement/AP validation still exposed a tenant-bootstrap gap where freshly onboarded companies can have `company.state_code = null`, which breaks GST-bearing purchase invoice setup until the company/supplier state metadata is corrected. The current workaround used a local company-state patch plus supplier `stateCode` update to finish M5 proof, and the underlying product fix is now tracked against the purchasing invoice flow.
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
- Check docs-only governance assertions as executable-policy checks: compare the policy text in mission/root guidance against `AGENTS.md` and record any mismatch.
- Safe concurrency boundary: multiple repo-static validators may read the same checkout concurrently, but each validator must write only its own flow report and evidence files.
