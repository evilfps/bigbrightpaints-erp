# User Testing

Testing surface: tools, URLs, setup steps, isolation notes, and known quirks for active ERP validation packets, including ERP-21 and ERP-38.

**What belongs here:** how to validate the surviving `batch -> pack -> dispatch` flow, which commands to run, and which runtime surfaces to trust.

---

## Validation Surface

- **Type:** REST API + Maven regression/integration suites
- **Base URL:** `http://localhost:8081`
- **Actuator:** `http://localhost:9090/actuator/health`
- **MailHog UI:** `http://localhost:8025`

## Testing Tools

- `mvn` from `erp-domain/`
- `curl`
- Docker Compose through `.factory/services.yaml`
- repo reset harness: `bash scripts/reset_final_validation_runtime.sh`

## Validation Concurrency

- **api:** max concurrent validators **1**
- **jvm-tests:** max concurrent validators **1**

Rationale: the ERP-38 mission uses one shared checkout and one shared compose runtime on fixed ports, so validators must not compete for `erp-domain/target`, Surefire reports, or the seeded runtime state.

## Setup Steps

1. From the repo root, run `bash .factory/init.sh`.
2. For authenticated runtime validation, run `bash scripts/reset_final_validation_runtime.sh`.
3. Wait for `http://localhost:9090/actuator/health` or, if actuator is degraded, probe `http://localhost:8081/api/v1/auth/me`.
4. Use the seeded validation actors from the reset harness output.
5. Run targeted ERP-38 commands from `.factory/services.yaml` instead of broad exploratory reruns.

## Seeded Validation Actors

- `validation.admin@example.com` - admin/accounting/sales on `MOCK`
- `validation.sales@example.com` - sales on `MOCK`
- `validation.factory@example.com` - factory on `MOCK`
- `validation.accounting@example.com` - accounting on `MOCK`
- `validation.dealer@example.com` - dealer portal user `VALID-DEALER` on `MOCK`
- `validation.rival.dealer@example.com` - dealer portal user `RIVAL-DEALER` on `RIVAL`

If you need a deterministic password, export `ERP_VALIDATION_SEED_PASSWORD` before running the reset harness.

## ERP-38 Key Commands

- `commands.erp38-targeted-setup`
- `commands.erp38-targeted-batch-pack`
- `commands.erp38-targeted-dispatch`
- `commands.erp38-contract-guards`
- `commands.gate-fast`
- `commands.verify-local`
- `commands.final-validation-reset`
- `commands.final-validation-targeted-tests`

## Runtime Probe Guidance

- Use removed-route negative probes to confirm retired surfaces are actually absent.
- Use `validation.factory@example.com` for factory-read redaction and pack-flow probes.
- Use `validation.sales@example.com` for canonical dispatch-confirm probes.
- For pack validation, prove that `Idempotency-Key` is the only accepted replay input.
- For dispatch validation, prove that `/api/v1/dispatch/**` is read-only and that `POST /api/v1/sales/dispatch/confirm` is the only write owner.
- For cross-flow validation, prove that packed sellable output is what becomes dispatchable; unpacked/semi-finished output must not dispatch.

## Flow Validator Guidance: api

- Treat the validator-launched reset runtime on `http://localhost:8081` as the shared API surface; do not rerun Docker Compose or the reset harness from a subagent unless the parent validator explicitly instructs you to recover a broken session.
- Stay inside repo-owned ports `5433`, `5672`, `8025`, `8081`, and `9090`, and do not touch other local services or the shared checkout outside this worktree.
- Prefer deterministic proof in this order: targeted seeded runtime probes, OpenAPI/docs inspection, then targeted Maven suites listed in `.factory/services.yaml`.
- If you must create runtime data, keep it namespaced for your assigned assertion group and avoid mutating global/shared settings that would affect later validators.
- Record concrete request/response observations, exact docs/OpenAPI locations, and any friction or blocker details in the flow report so the parent validator can update synthesis without re-running your work.

## High-Signal Proof By Milestone

### setup-truth

- `CatalogControllerCanonicalProductIT`
- `CatalogServiceCanonicalCoverageTest`
- `ProductionCatalogServiceCanonicalEntryTest`
- `SkuReadinessServiceTest`
- `PackagingMaterialServiceTest`

### batch-pack-hard-cut

- `ProductionLogWipPostingRegressionIT`
- `ProductionLaborOverheadWipIT`
- `ProductionLogListDetailLazyLoadRegressionIT`
- `ProductionLogPackingStatusRegressionIT`
- `PackingControllerTest`
- `PackingServiceTest`
- `FactoryPackagingCostingIT`
- `TS_PackingIdempotencyAndFacadeBoundaryTest`

### dispatch-contract-hard-cut

- `DispatchControllerTest`
- `DispatchOperationalBoundaryIT`
- `DispatchConfirmationIT`
- `TS_O2CDispatchCanonicalPostingTest`
- `TS_O2COrchestratorDispatchRemovalRegressionTest`
- `ErpInvariantsSuiteIT`
- `OrderFulfillmentE2ETest`

## ERP-21 Validation Surface

- **Type:** REST API + Maven regression/integration suites + contract/doc guards
- **Base URL:** `http://localhost:8081`
- **Canonical hosts to prove:**
  - `/api/v1/dealer-portal/**`
  - `/api/v1/portal/finance/**`
  - `/api/v1/portal/support/tickets/**`
  - `/api/v1/dealer-portal/support/tickets/**`
- **Retired hosts to probe:** duplicate dealer-finance routes plus `/api/v1/support/**`

## ERP-21 Key Commands

- `commands.erp21-runtime-reset`
- `commands.erp21-targeted-finance`
- `commands.erp21-targeted-support`
- `commands.erp21-contract-guards`
- `commands.gate-fast`
- `commands.gate-release`
- `commands.gate-reconciliation`

## ERP-21 Probe Guidance

- Use the reset runtime on `8081` for authenticated probes.
- Use `validation.dealer@example.com` for canonical dealer-portal finance/support probes and `validation.rival.dealer@example.com` for cross-dealer or cross-tenant denial probes.
- Prove canonical host success first, then prove retired host absence.
- Probe retired finance routes with the actor that used to be allowed on them (`admin`, `accounting`, or `sales` as applicable); a dealer-only `403` is not enough retirement proof.
- Treat actuator degradation as non-blocking only if the target `8081` endpoints for ERP-21 still succeed.
- Runtime validation may be skipped only for docs-only changes.

## Known Issues

- Plain `prod,flyway-v2` compose boot can come up with an empty DB. Use the reset harness before authenticated runtime validation.
- The ERP-21 validation seed now includes deterministic dealer invoices for both seeded dealer actors. Use `GET /api/v1/dealer-portal/invoices` to discover `VAL-MOCK-INV-001` and `VAL-RIVAL-INV-001` before proving own invoice PDF `200` and cross-dealer invoice PDF `404` behavior.
- The current `validation-seed` runtime starts with raw materials `RM-RESIN`, `RM-PIG`, and `RM-CAN` only; `RM-CAN` is not typed as `PACKAGING`, and there are no seeded size variants. Positive `POST /api/v1/factory/packing-records` runtime proof therefore needs extra fixtures beyond the default seed. Use the targeted batch-pack Maven suites for positive pack completion/costing/status assertions unless you explicitly seed packaging-grade fixtures first.
- Running Maven outside `erp-domain/` can break `.mvn/settings.xml` resolution.
- Direct `docker compose up -d db rabbitmq mailhog` still needs `JWT_SECRET` and `ERP_SECURITY_ENCRYPTION_KEY` in the environment because the compose file parses the app service too.
- If actuator reports DOWN but the app answers `8081`, verify the target API endpoints directly before treating the runtime as unavailable.
- If runtime validation is temporarily unavailable, continue with deterministic Maven evidence and record the limitation explicitly in synthesis.
