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
- Flyway for DB migrations (136 versioned scripts, V1-V136)
- MapStruct for DTO mapping (CentralMapperConfig)
- JaCoCo coverage gates (Tier A packages + bundle minimum)

## Entity Base
- `VersionedEntity` - base class with optimistic locking
- Company-scoped entities have `company` field for tenant isolation

## Error Handling
- `ApplicationException` with `ErrorCode` enum for domain errors
- `GlobalExceptionHandler` maps exceptions to HTTP responses
- **CONVENTION**: Always use ApplicationException for business errors, never raw IllegalArgumentException/IllegalStateException
