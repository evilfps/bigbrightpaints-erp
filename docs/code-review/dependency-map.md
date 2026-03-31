# Dependency map

## Method

This map is based on static inspection of cross-module imports under `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/**` plus spot checks of `orchestrator/**`. Imports from `core/`, `shared/`, `config/`, and `orchestrator/` are treated as shared-platform dependencies and are not counted as business-module couplings unless they create a hotspot themselves.

## Cross-module dependencies

### Outbound module dependency summary

| Module | Outbound dependencies observed | Count |
| --- | --- | --- |
| reports | accounting, admin, company, factory, inventory, invoice, production, purchasing, sales | 9 |
| accounting | company, hr, inventory, invoice, production, purchasing, reports, sales | 8 |
| portal | accounting, company, factory, hr, inventory, invoice, reports, sales | 8 |
| sales | accounting, auth, company, factory, inventory, invoice, production, rbac | 8 |
| admin | accounting, auth, company, hr, inventory, rbac, sales | 7 |
| factory | accounting, company, inventory, production, sales | 5 |
| inventory | accounting, company, production, purchasing, sales | 5 |
| invoice | accounting, company, inventory, sales | 4 |
| production | accounting, company, factory, inventory | 4 |
| company | accounting, auth, rbac | 3 |
| purchasing | accounting, company, inventory | 3 |
| auth | company, rbac | 2 |
| hr | accounting, company | 2 |
| demo | none | 0 |
| rbac | none | 0 |

### Orchestrator dependency summary

`orchestrator/service/IntegrationCoordinator.java` imports module services from `accounting`, `company`, `factory`, `hr`, `inventory`, `invoice`, `reports`, and `sales`. `orchestrator/service/CommandDispatcher.java` stays thinner and mostly delegates into `IntegrationCoordinator`, `EventPublisherService`, and idempotency/tracing infrastructure.

## Dependency clusters

| Cluster | Main files | Why the cluster exists |
| --- | --- | --- |
| Tenant and control plane | `modules/company/controller/CompanyController.java`, `core/security/CompanyContextFilter.java`, `modules/company/service/ModuleGatingInterceptor.java`, `modules/auth/controller/AuthController.java`, `modules/rbac/controller/RoleController.java` | Tenant admission, company lifecycle, module enablement, and role enforcement are centralized and affect every other business area. |
| Commercial workflow | `modules/sales/service/SalesCoreEngine.java`, `modules/invoice/service/InvoiceService.java`, `modules/accounting/service/AccountingFacade.java` | Order approval, dispatch, invoicing, and settlement cross sales, inventory, invoice, and accounting boundaries. |
| Operations workflow | `modules/factory/service/BulkPackingOrchestrator.java`, `modules/inventory/service/FinishedGoodsDispatchEngine.java`, `modules/production/service/ProductionCatalogService.java`, `modules/purchasing/service/PurchaseOrderService.java` | Manufacturing, stock, purchasing, and dispatch depend on shared product, batch, and accounting state. |
| Read-model and dashboard layer | `modules/reports/service/ReportService.java`, `modules/reports/service/InventoryValuationQueryService.java`, `modules/portal/service/EnterpriseDashboardService.java`, `orchestrator/controller/DashboardController.java` | Reporting and dashboards aggregate directly from many operational repositories instead of consuming a dedicated reporting contract. |

## Coupling hotspots

| Hotspot | Evidence | Why it matters |
| --- | --- | --- |
| Reports as a direct multi-domain read model | `modules/reports/service/ReportService.java`, `modules/reports/service/InventoryValuationQueryService.java` | The reports layer imports accounting, sales, inventory, factory, invoice, purchasing, company, and production types directly. Schema or domain-shape changes in those modules are likely to ripple into report logic and export surfaces. |
| Portal dashboard as a second aggregator | `modules/portal/service/EnterpriseDashboardService.java` | The portal layer reaches into invoice, sales order, settlement, journal, production log, packing record, packaging slip, and report services in one class. This makes the user-facing dashboard sensitive to internal entity design across many modules. |
| Sales workflow engine as the commercial nexus | `modules/sales/service/SalesCoreEngine.java` | Sales order creation and status progression directly touch accounting services, finished-goods reservation/dispatch state, invoice repositories, production products, and factory task creation. A single order-flow change can affect inventory, production, invoicing, and posting behavior. |
| Accounting as the financial sink for operational modules | `modules/accounting/service/AccountingCoreEngine.java`, `modules/accounting/service/AccountingFacade.java` | Accounting imports payroll, invoice, raw-material movement, finished-good batches, suppliers, and dealers. This keeps financial truth close to operations, but it also means accounting changes have broad blast radius across commercial and operational workflows. |
| Orchestrator duplicates some integration ownership | `orchestrator/service/IntegrationCoordinator.java`, `orchestrator/service/CommandDispatcher.java`, `orchestrator/service/EventPublisherService.java` | The orchestrator layer is intentionally cross-cutting, but it now acts as both command surface and service aggregator. That creates a second place where commercial/operations contracts must stay aligned with direct module controllers. |
| Generic `/api/v1` controllers depend on path conventions | `modules/sales/controller/SalesController.java`, `modules/reports/controller/ReportController.java`, `modules/inventory/controller/RawMaterialController.java`, `modules/admin/controller/ChangelogController.java`, `modules/company/service/ModuleGatingInterceptor.java` | Several controllers use a broad base mapping and rely on method-level subpaths for ownership. Because `ModuleGatingInterceptor` resolves modules by string prefix, route naming becomes part of the enforcement contract and is easier to break than package-level type boundaries. |
| Admin settings aggregates cross-module approvals | `modules/admin/controller/AdminSettingsController.java` | Admin settings and approvals pull together exports, tenant runtime policy, credit requests, credit overrides, payroll approvals, and period-close approvals. This makes the admin module a policy/control-plane hub with dependencies on accounting, HR, inventory, and sales. |

## Ownership boundary implications

- **Safest local ownership:** `auth`, `rbac`, and parts of `company` are the least entangled business modules.
- **Most change-sensitive seams:** `sales`, `accounting`, `reports`, `portal`, and `orchestrator` have the highest likelihood of cross-module blast radius.
- **Infrastructure-owned seams:** tenant admission (`CompanyContextFilter.java`), module gating (`ModuleGatingInterceptor.java`), idempotency (`core/idempotency/IdempotencyReservationService.java`), and outbox publication (`EventPublisherService.java`) are effectively platform contracts rather than module-local details.
- **Later review priority:** flow documents for order-to-cash, manufacturing/inventory, finance/reporting, and orchestrator jobs should assume high coupling and explicitly trace shared state transitions rather than treating modules as isolated.
