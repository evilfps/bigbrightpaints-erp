# Architecture

Architectural decisions, patterns discovered, and conventions.

**What belongs here:** Module structure, patterns, conventions, entity relationships, cross-module contracts.

---

## Module Structure
Base package: `com.bigbrightpaints.erp`
- `core/` - Shared infrastructure (security, config, audit, exceptions, utilities)
- `modules/` - Domain modules (accounting, admin, auth, company, factory, hr, inventory, invoice, portal, production, purchasing, rbac, reports, sales)
- `orchestrator/` - Cross-module orchestration (command dispatch, event publishing, workflows, scheduling)
- `shared/dto/` - Shared DTOs (ApiResponse, PageResponse, ErrorResponse)
- `config/` - App-level config (CORS, RabbitMQ, Jackson)

## Per-Module Convention
Each module follows: `domain/` (entities + repos), `service/`, `controller/`, `dto/`, optionally `event/`, `config/`

## Key Patterns
- Multi-tenant via `CompanyContextHolder` (thread-local company context)
- Idempotency via signature hashing + DB unique constraints
- Outbox pattern for reliable event publishing (EventPublisherService)
- ShedLock for distributed scheduler coordination
- Flyway v2 is the active migration path in prod profile (`prod` includes `flyway-v2`, locations `classpath:db/migration_v2`)
- MapStruct for DTO mapping (CentralMapperConfig)
- JaCoCo coverage gates (Tier A packages + bundle minimum)

## Entity Base
- `VersionedEntity` - base class with optimistic locking
- Company-scoped entities have `company` field for tenant isolation

## Error Handling
- `ApplicationException` with `ErrorCode` enum for domain errors
- `GlobalExceptionHandler` maps exceptions to HTTP responses
- **CONVENTION**: Always use ApplicationException for business errors, never raw IllegalArgumentException/IllegalStateException

## Tenant & Admin Runtime Conventions
- Tenant lifecycle states are `ACTIVE`, `SUSPENDED`, `DEACTIVATED`.
- `DEACTIVATED` tenants are denied all API access.
- `SUSPENDED` tenants are read-only (write operations are denied).
- Module access gates treat `AUTH`, `ACCOUNTING`, `SALES`, and `INVENTORY` as always-on core modules.
- Optional modules are controlled via company `enabled_modules` and enforced by `ModuleGatingInterceptor` + `ModuleGatingService`.

## ERP Truth-Stabilization Mission Notes
- Highest-risk O2C/P2P hotspots are `SalesCoreEngine`, `InvoiceService`, `GoodsReceiptService`, `PurchaseInvoiceEngine`, `InventoryAccountingEventListener`, `SupplierService`, and `DealerService`.
- For this mission, workflow state and accounting state must stay separate in touched documents.
- Posting truth must have one canonical trigger per touched workflow boundary; duplicate-truth listeners and dead fallback paths should be removed when a feature makes them obsolete.
- O2C dispatch posting has one allowed accounting path: `SalesCoreEngine.confirmDispatch -> AccountingFacade.postCogsJournal/postSalesJournal`. Orchestrator batch-dispatch and fulfillment endpoints must fail closed or redirect callers to the canonical sales dispatch confirm endpoint; they must never mint `DISPATCH-*` journals.
- The mission normalizes linked business references across order or proforma, production requirement, packaging slip, dispatch, invoice, journal, settlement, return, note, and reversal artifacts.
- Flyway `migration_v2` is the only valid migration track for this mission.

## Lane 01 Tenant Runtime Canonicalization Notes
- Canonical tenant/runtime policy writer: `PUT /api/v1/companies/{id}/tenant-runtime/policy`.
- Canonical persistence/enforcement source for this slice: `modules.company.service.TenantRuntimeEnforcementService`.
- The stale admin-side writer (`PUT /api/v1/admin/tenant-runtime/policy`) and any separate privileged-path recognition must be retired or collapsed in the same packet when touched.
- Admin runtime metrics remain an in-scope tenant-scoped reader, but they must map canonical `auditChainId`/`updatedAt` to `policyReference`/`policyUpdatedAt` and align defaults with the canonical source.
- `CompanyContextFilter`, `TenantRuntimeEnforcementService`, `AuthService`, and `TenantRuntimeEnforcementInterceptor` are the critical code surfaces for this packet.
- See `.factory/library/tenant-runtime-control-plane.md` for the worker-facing packet contract and catching-lane notes.

## Catalog Surface Consolidation Notes
- Historically, the catalog flow was split across three public hosts: `/api/v1/accounting/catalog/**`, `/api/v1/catalog/**`, and `/api/v1/production/**`. The surviving canonical public host is now `/api/v1/catalog/**`.
- `ProductionCatalogService` remains the downstream-ready write path behind the canonical public host, and duplicate product mutation behavior on retired hosts must stay removed.
- The packet contract is to keep only `/api/v1/catalog/**` as the public host, keep `POST /api/v1/catalog/brands` separate from product create, and keep `POST /api/v1/catalog/products` as the only public product-create surface.
- Explicit persisted family/group linkage is required for multi-SKU creates; do not rely on naming conventions alone.
- Preserve reserved SKU semantics such as `-BULK` and keep downstream finished-good/raw-material readiness in the same write path.
- See `.factory/library/catalog-surface-consolidation.md` for mission-specific hotspots, cleanup targets, and packet guardrails.
