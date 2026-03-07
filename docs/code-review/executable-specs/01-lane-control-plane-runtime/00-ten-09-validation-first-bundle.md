# TEN-09 validation-first bundle

## 1. Header
- finding IDs: `TEN-09`, `VAL-CTRL-004`
- lane: `lane-01-control-plane-runtime`
- implementer: `Factory-droid packet-governance worker`
- reviewer: `Factory-droid packet-governance worker direct evidence review`
- branch: `Factory-droid`
- target environment: repository source review + `MIGRATION_SET=v2` targeted tests + approved compose-backed `prod,flyway-v2` runtime on `8081/9090`

## 2. Claim under test
`TEN-09` questioned whether `GET /api/v1/admin/tenant-runtime/metrics` and `GET /api/v1/portal/{dashboard,operations,workforce}` were effectively undocumented or drifted from frontend/runtime expectations.

This bundle re-proves the current contract against controller code, DTOs, targeted tests, `openapi.json`, and live compose-backed responses before any Lane 01 backend scope widens.

## 3. Source-of-truth review

### Controller and DTO code inspected
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/admin/dto/TenantRuntimeMetricsDto.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/controller/PortalInsightsController.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/portal/dto/{DashboardInsights,OperationsInsights,WorkforceInsights}.java`
- `erp-domain/src/main/java/com/bigbrightpaints/erp/core/config/JacksonConfig.java`

### Tests inspected / executed
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/admin/controller/AdminSettingsControllerTenantRuntimeContractTest.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/portal/PortalInsightsControllerIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/OpenApiSnapshotIT.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/modules/company/{CompanyControllerIT,SuperAdminControllerIT}.java`
- `erp-domain/src/test/java/com/bigbrightpaints/erp/truthsuite/runtime/{TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest}.java`

### OpenAPI and runtime surfaces inspected
- `openapi.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-openapi-contract.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-runtime-summary.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-runtime-metrics-uttaab2.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-{dashboard,operations,workforce}-uttaab2.json`

## 4. Exact commands and artifacts

### Commands run
1. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn -T8 test -Pgate-fast -Djacoco.skip=true`
2. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=AdminSettingsControllerTenantRuntimeContractTest,CompanyControllerIT,SuperAdminControllerIT,TS_RuntimeCompanyContextFilterExecutableCoverageTest,TS_RuntimeTenantPolicyControlExecutableCoverageTest' > '/home/realnigga/Desktop/Mission-control/docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-verification-tests.log' 2>&1`
3. `cd /home/realnigga/Desktop/Mission-control/erp-domain && MIGRATION_SET=v2 mvn test -Djacoco.skip=true -pl . '-Dtest=PortalInsightsControllerIT,OpenApiSnapshotIT' > '/home/realnigga/Desktop/Mission-control/docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-openapi-tests.log' 2>&1`
4. `docker compose -f /home/realnigga/Desktop/Mission-control/docker-compose.yml up -d rabbitmq`
5. `docker compose -f /home/realnigga/Desktop/Mission-control/docker-compose.yml up -d mailhog`
6. `cd /home/realnigga/Desktop/Mission-control && SPRING_PROFILES_ACTIVE='prod,flyway-v2' ERP_CORS_ALLOWED_ORIGINS='https://app.bigbrightpaints.com' ERP_CORS_ALLOW_TAILSCALE_HTTP_ORIGINS='true' DB_PORT=5433 SPRING_MAIL_HOST='mailhog' SPRING_MAIL_PORT='1025' SPRING_MAIL_USERNAME='' SPRING_MAIL_PASSWORD='' SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH='false' SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE='false' SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED='false' docker compose up -d --build app`
7. `docker exec erp_db psql -U erp -d erp_domain -c "update app_users set password_hash = crypt('Factory123!', gen_salt('bf')) where email in ('uat.superadmin@example.com','ut.taab.admin2@example.com');"`
8. Runtime probe script that logged in as `ut.taab.admin2@example.com` for `UTTAAB2`, fetched the four TEN-09 routes, captured `GET /actuator/health`, and wrote the JSON evidence files listed below.
9. OpenAPI extraction script that wrote the narrowed path/schema proof to `ten-09-openapi-contract.json`.

### Saved artifacts
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-verification-tests.log`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-openapi-tests.log`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-openapi-contract.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-runtime-summary.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-login-uttaab2.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-runtime-metrics-uttaab2.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-dashboard-uttaab2.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-operations-uttaab2.json`
- `docs/code-review/executable-specs/01-lane-control-plane-runtime/artifacts/ten-09-portal-workforce-uttaab2.json`

## 5. Evidence and route matrix

| Surface | Code contract | Published contract | Runtime result | Verdict |
| --- | --- | --- | --- | --- |
| `GET /api/v1/admin/tenant-runtime/metrics` | `AdminSettingsController.tenantRuntimeMetrics()` returns `ApiResponse<TenantRuntimeMetricsDto>`; DTO fields are `companyCode`, `holdState`, `holdReason`, quota counters, `policyReference`, `policyUpdatedAt` | `openapi.json` publishes `ApiResponseTenantRuntimeMetricsDto` with `timestamp` and `policyUpdatedAt` typed as `string` `date-time` | Live response returned wrapper keys `success/message/data/timestamp`; data keys matched DTO field names, but `timestamp` and `policyUpdatedAt` serialized as numeric epoch values instead of strings | **Mismatch: confirmed backend defect** |
| `GET /api/v1/portal/dashboard` | `PortalInsightsController.dashboard()` returns `ApiResponse<DashboardInsights>` with `highlights`, `pipeline`, `hrPulse` | `openapi.json` publishes `ApiResponseDashboardInsights` with the same nested keys and wrapper `timestamp` typed as `string` `date-time` | Live response returned `highlights`, `pipeline`, and `hrPulse` exactly as published, but wrapper `timestamp` serialized as numeric epoch | **Mismatch limited to wrapper timestamp serialization** |
| `GET /api/v1/portal/operations` | `PortalInsightsController.operations()` returns `ApiResponse<OperationsInsights>` with `summary`, `supplyAlerts`, `automationRuns` | `openapi.json` publishes `ApiResponseOperationsInsights` with the same nested keys and wrapper `timestamp` typed as `string` `date-time` | Live response returned `summary`, `supplyAlerts`, and `automationRuns` exactly as published, but wrapper `timestamp` serialized as numeric epoch | **Mismatch limited to wrapper timestamp serialization** |
| `GET /api/v1/portal/workforce` | `PortalInsightsController.workforce()` returns `ApiResponse<WorkforceInsights>` with `squads`, `moments`, `leaders` | `openapi.json` publishes `ApiResponseWorkforceInsights` with the same nested keys and wrapper `timestamp` typed as `string` `date-time` | Live response returned `squads`, `moments`, and `leaders` exactly as published, but wrapper `timestamp` serialized as numeric epoch | **Mismatch limited to wrapper timestamp serialization** |

### High-signal proof points
- `AdminSettingsControllerTenantRuntimeContractTest` passed 8 tests and proves the tenant-runtime metrics route remains tenant-admin readable.
- `PortalInsightsControllerIT` passed 3 tests and proves the dashboard/operations/workforce payload families exist and return data wrappers.
- `OpenApiSnapshotIT` passed 2 tests and keeps the repository snapshot aligned for the admin metrics path; the portal path publication was re-checked directly from `openapi.json` and extracted into `ten-09-openapi-contract.json`.
- The feature verification subset passed 54 tests total (`BUILD SUCCESS`) in `ten-09-verification-tests.log`.
- Live compose-backed probes returned `200` for all four questioned routes, but every response serialized the wrapper `timestamp` as a numeric epoch, and metrics also serialized `policyUpdatedAt` numerically.
- `JacksonConfig` registers `JavaTimeModule` but does not disable timestamp serialization, which matches the numeric runtime behavior and explains the contract mismatch.
- `GET http://localhost:9090/actuator/health` still returned `503 {"status":"DOWN"}` while the target `8081` routes were usable; this is recorded as degraded actuator evidence, not as a waiver.

## 6. Verdict
`confirmed backend defect`

The questioned surfaces are **not** undocumented route inventions and **not** a frontend-only misunderstanding: the live backend currently mismatches the published OpenAPI contract on timestamp serialization for all four TEN-09 responses. The nested business payload families (`TenantRuntimeMetricsDto`, `DashboardInsights`, `OperationsInsights`, `WorkforceInsights`) are present and structurally aligned, but the wrapper `timestamp` field and metrics `policyUpdatedAt` field ship as numeric epochs while `openapi.json` publishes date-time strings.

## 7. Required notes
- **Evidence supporting the verdict:** runtime snapshots, narrowed OpenAPI extract, controller/DTO review, and passing targeted tests all point to one narrow defect: live serialization parity for time fields.
- **Work now allowed:** a narrow Lane 01 backend packet to restore runtime/OpenAPI parity for `ApiResponse.timestamp` and `TenantRuntimeMetricsDto.policyUpdatedAt` on the surviving control-plane/portal surfaces, or an equally narrow contract decision if the published OpenAPI is intentionally changed instead.
- **Work explicitly not allowed:** do not widen this into portal/dashboard redesign, new tenant-runtime fields, new routes, or broader metrics-convergence work before the serialization/parity defect is packetized separately.
- **Frontend/operator consequence:** frontend and operator consumers should treat the current packet as proof that the route family already exists and that the only confirmed backend gap is timestamp-type parity, not missing tenant-runtime or portal payload surfaces.

## 8. Approval
- reviewer sign-off: `Factory-droid packet-governance worker` signs off that TEN-09 remains a prove-first item and is now classified as a narrow backend contract defect, not a missing-surface backlog item.
- lane owner acknowledgement: Lane 01 packet 0 remains proof-only; no further backend expansion for TEN-09 should open until the timestamp-parity follow-up is packetized explicitly.
