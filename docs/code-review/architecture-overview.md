# Architecture overview

## Scope and evidence

This review is based on static inspection of the Spring Boot backend under `erp-domain/src/main/java/com/bigbrightpaints/erp`, runtime/config files in `erp-domain/src/main/resources`, and local deployment topology in `docker-compose.yml`. The goal of this file is to describe the platform shape, controller entrypoints, module boundaries, and shared infrastructure without changing application code.

## Platform shape

- `erp-domain/src/main/java/com/bigbrightpaints/erp/ErpDomainApplication.java` is the single Spring Boot entrypoint and enables retry plus scheduling.
- The source tree is split into platform packages and business packages:
  - `config/` for app-wide web configuration such as `CorsConfig`.
  - `controller/` for top-level non-module surfaces such as `IntegrationHealthController`.
  - `core/` for shared security, audit, exception, idempotency, health, config, and utility infrastructure.
  - `modules/` for business domains (`accounting`, `admin`, `auth`, `company`, `factory`, `hr`, `inventory`, `invoice`, `portal`, `production`, `purchasing`, `rbac`, `reports`, `sales`, plus `demo`).
  - `orchestrator/` for cross-module command dispatch, outbox/eventing, tracing, and schedulers.
  - `shared/dto/` for common API envelopes such as `ApiResponse`, `ErrorResponse`, and `PageResponse`.
- `erp-domain/src/main/resources/application.yml` defaults to the `prod` profile and groups `prod` with `flyway-v2`; `application-flyway-v2.yml` points Flyway at `classpath:db/migration_v2`.
- `docker-compose.yml` overrides the application container to run on `8081` with a management port on `9090`, and wires PostgreSQL, RabbitMQ, and MailHog beside the app container.

## Entrypoints and controller surfaces

### Platform-level entry surfaces

| Surface | Code reference | Notes |
| --- | --- | --- |
| Application bootstrap | `erp-domain/src/main/java/com/bigbrightpaints/erp/ErpDomainApplication.java` | Boots the Spring container, retry support, and scheduled jobs. |
| Integration health | `erp-domain/src/main/java/com/bigbrightpaints/erp/controller/IntegrationHealthController.java` | Top-level `/api/integration/health` surface outside the module tree. |
| Enterprise audit trail API | `erp-domain/src/main/java/com/bigbrightpaints/erp/core/audittrail/web/EnterpriseAuditTrailController.java` | Shared audit ingestion/query surface under `/api/v1/audit`. |
| Orchestrator commands | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/OrchestratorController.java` | Cross-module workflow entrypoints under `/api/v1/orchestrator`. |
| Orchestrator dashboards | `erp-domain/src/main/java/com/bigbrightpaints/erp/orchestrator/controller/DashboardController.java` | Aggregated admin/factory/finance dashboard surface. |

### Module controller inventory

| Module | Primary controller surfaces | Representative code references |
| --- | --- | --- |
| accounting | `/api/v1/accounting`, `/api/v1/accounting/configuration`, `/api/v1/accounting/payroll/payments`, `/api/v1/migration` | `modules/accounting/controller/AccountingController.java`, `AccountingConfigurationController.java`, `JournalController.java`, `TallyImportController.java` |
| admin | `/api/v1/admin`, `/api/v1/admin/users`, `/api/v1/changelog` | `modules/admin/controller/AdminSettingsController.java`, `AdminUserController.java`, `ChangelogController.java` |
| auth | `/api/v1/auth`, `/api/v1/auth/mfa` | `modules/auth/controller/AuthController.java`, `MfaController.java` |
| company | `/api/v1/companies`, `/api/v1/superadmin`, `/api/v1/superadmin/tenants` | `modules/company/controller/CompanyController.java`, `SuperAdminController.java`, `SuperAdminTenantOnboardingController.java` |
| factory | `/api/v1/factory`, `/api/v1/factory/packaging-mappings`, `/api/v1/factory/production/logs` | `modules/factory/controller/FactoryController.java`, `PackagingMappingController.java`, `PackingController.java`, `ProductionLogController.java` |
| hr | `/api/v1/hr`, `/api/v1/payroll` | `modules/hr/controller/HrController.java`, `HrPayrollController.java` |
| inventory | `/api/v1/dispatch`, `/api/v1/finished-goods`, `/api/v1/inventory/adjustments`, `/api/v1/inventory/batches`, plus raw-material routes under `/api/v1` | `modules/inventory/controller/DispatchController.java`, `FinishedGoodController.java`, `InventoryAdjustmentController.java`, `InventoryBatchController.java`, `OpeningStockImportController.java`, `RawMaterialController.java` |
| invoice | `/api/v1/invoices` | `modules/invoice/controller/InvoiceController.java` |
| portal | `/api/v1/portal`, `/api/v1/portal/support/tickets` | `modules/portal/controller/PortalInsightsController.java`, `PortalSupportTicketController.java` |
| production | `/api/v1/catalog` | `modules/production/controller/CatalogController.java` |
| purchasing | `/api/v1/purchasing`, `/api/v1/purchasing/raw-material-purchases`, `/api/v1/suppliers` | `modules/purchasing/controller/PurchasingWorkflowController.java`, `RawMaterialPurchaseController.java`, `SupplierController.java` |
| rbac | `/api/v1/admin/roles` | `modules/rbac/controller/RoleController.java` |
| reports | `/api/v1/reports/**`, export routes under `/api/v1/exports/**` | `modules/reports/controller/ReportController.java` |
| sales | `/api/v1/dealers`, `/api/v1/dealer-portal`, `/api/v1/dealer-portal/support/tickets`, `/api/v1/credit/override-requests`, plus `/api/v1/sales/**` routes | `modules/sales/controller/DealerController.java`, `DealerPortalController.java`, `DealerPortalSupportTicketController.java`, `CreditLimitOverrideController.java`, `SalesController.java` |
| demo | `/api/v1/demo` | `modules/demo/controller/DemoController.java` |

## Module boundaries

The dominant ownership convention is `modules/<module>/{controller,service,domain}`. Most modules keep HTTP entrypoints, business services, and JPA entities inside that package boundary, then pull shared concerns from `core/`, `shared/`, or `orchestrator/` as needed.

| Boundary | Owned responsibility | Boundary notes |
| --- | --- | --- |
| `modules/auth` + `modules/rbac` | Authentication, MFA/reset flows, roles and permissions | Identity is business-owned in module packages, but enforcement is centralized in `core/security/SecurityConfig.java`. |
| `modules/company` | Tenant/company lifecycle, scoped tenant binding, module enablement, super-admin control plane | `CompanyController.java`, `CompanyLifecycleState.java`, `CompanyModule.java`, and `ModuleGatingInterceptor.java` define tenant/runtime boundaries that other modules depend on. |
| `modules/accounting` | Journals, accounts, period controls, reconciliations, settlement logic | Accounting is a business module, but it is also a sink for side effects from sales, purchasing, inventory, invoice, and HR flows via classes like `AccountingCoreEngine.java` and `AccountingFacade.java`. |
| `modules/sales` + `modules/invoice` | Dealer/customer-facing order lifecycle, credit workflows, invoicing | `SalesCoreEngine.java` and `InvoiceController.java` show the commercial boundary, but sales directly reaches inventory, production, factory tasks, and accounting. |
| `modules/inventory`, `modules/production`, `modules/factory`, `modules/purchasing`, `modules/hr` | Stock, catalog, manufacturing, purchasing, and payroll operations | These modules own operational state, but several workflow engines cross their boundaries directly instead of through thinner contracts. |
| `modules/reports` + `modules/portal` | Read models, dashboards, reporting, export surfaces | These are aggregation layers rather than isolated domains; they depend on many other modules' repositories and DTOs. |
| `orchestrator` | Cross-module workflow coordination, eventing, traceability, scheduling | It sits above module boundaries and calls multiple business services via `CommandDispatcher.java` and `IntegrationCoordinator.java`. |

## Shared infrastructure

### Security, tenant context, and admission control

- `core/security/SecurityConfig.java` installs a stateless JWT filter chain, exposes only specific public auth/changelog/health routes, and keeps Swagger gated unless explicitly allowed outside `prod`.
- `core/security/CompanyContextFilter.java` is the main request admission boundary for tenant context. It validates company headers versus JWT claims, enforces lifecycle/read-only restrictions, blocks unsafe unauthenticated tenant selection, and delegates runtime admission to `TenantRuntimeEnforcementService`.
- `core/security/CompanyContextHolder.java` carries tenant identity in a thread-local, while `modules/company/service/CompanyContextService.java` resolves the active `Company` entity from that thread-local for downstream services.
- `modules/company/service/ModuleGatingInterceptor.java` performs path-prefix-based module enforcement using `CompanyModule.java`; this keeps optional modules behind tenant settings but also means route naming conventions are part of the architecture contract.

### Error handling and API envelopes

- `core/exception/GlobalExceptionHandler.java` normalizes business, validation, and malformed-request failures into `ApiResponse.failure(...)` payloads and injects trace IDs.
- Shared response envelopes live in `shared/dto/ApiResponse.java`, `shared/dto/ErrorResponse.java`, and `shared/dto/PageResponse.java`, which lets controllers across modules return a consistent outer shape.

### Persistence, migration, and optimistic locking

- `core/domain/VersionedEntity.java` gives shared optimistic-locking support through JPA `@Version` fields.
- `orchestrator/repository/OutboxEvent.java` extends `VersionedEntity` and shows the platform’s pattern for concurrent update safety in shared infrastructure tables.
- `application-flyway-v2.yml` makes Flyway v2 the active schema track for the production profile group; the `db/migration_v2/` tree contains cross-cutting schema milestones such as `V1__core_auth_rbac.sql`, `V6__orchestrator.sql`, `V24__tenant_onboarding_coa_templates.sql`, and later module-specific expansions like `V45__raw_material_adjustments.sql`.

### Configuration, CORS, and API metadata

- `config/CorsConfig.java` resolves the active CORS configuration from `core/config/SystemSettingsService`, so cross-origin policy is partly data-driven rather than purely file-based.
- `core/config/OpenApiConfig.java` defines the OpenAPI metadata and root server description for the HTTP surface.
- `application.yml` centralizes security toggles, RabbitMQ, mail, rate limiting, export policy, licensing, and orchestrator timing settings.

### Eventing, orchestration, and scheduling

- `orchestrator/service/CommandDispatcher.java` starts idempotent orchestration leases and turns workflow actions into outbox-backed domain events.
- `orchestrator/service/IntegrationCoordinator.java` is the main cross-module façade for orchestrated work; it touches sales, factory, inventory, invoice, accounting, HR, and reports services.
- `orchestrator/service/EventPublisherService.java` persists and publishes outbox events, applies publish leases, emits health metrics, and holds ambiguous broker outcomes for reconciliation instead of blindly retrying.
- `orchestrator/scheduler/OutboxPublisherJob.java` registers the outbox publisher schedule, and `orchestrator/scheduler/SchedulerService.java` persists cron metadata for registered jobs.

## Architectural observations

- The codebase has a strong package-level convention, but several business flows are coordinated inside large service classes instead of explicit anti-corruption layers.
- Tenant and module boundaries are enforced centrally before most business logic runs, which is a strength, but that enforcement depends heavily on path naming consistency.
- The largest architectural pressure points are not the controllers themselves; they are the workflow engines and read-model aggregators that span many modules. Those hotspots are mapped in [dependency-map.md](./dependency-map.md).
