# Architecture

Architectural decisions, patterns discovered, and conventions.

**What belongs here:** module structure, patterns, conventions, entity relationships, and cross-module contracts relevant to the current mission.

---

## Module Structure

Base package: `com.bigbrightpaints.erp`

- `core/` - shared infrastructure (security, config, audit, exceptions, utilities)
- `modules/` - domain modules (accounting, admin, auth, company, factory, hr, inventory, invoice, portal, production, purchasing, rbac, reports, sales)
- `orchestrator/` - cross-module orchestration and command dispatch
- `shared/dto/` - shared API envelopes and common DTOs

## General Conventions

- Per-module layout is `domain/`, `service/`, `controller/`, `dto/`, with optional `event/` and `config/`.
- `ApplicationException` + `ErrorCode` are the business-error contract. Do not use raw `IllegalArgumentException` / `IllegalStateException` for domain failures.
- `VersionedEntity` is the optimistic-locking base type.
- Company/tenant scoped data relies on company context and scoped queries.
- Flyway v2 is the only migration track valid for ERP-38.
- `SystemRole` default-permission changes do not remove retired grants from existing database roles by themselves; `RoleService` synchronization is additive-only, so role-ownership moves need explicit cleanup/backfill proof for upgraded tenants.

## ERP-21 Portal Split Notes

- The portal split is a host-ownership packet, not a compatibility packet.
- Dealer-facing finance must live only on `/api/v1/dealer-portal/**`.
- Internal/admin finance must live only on `/api/v1/portal/finance/**`.
- Admin/ops support must live only on `/api/v1/portal/support/tickets/**`.
- Dealer support must live only on `/api/v1/dealer-portal/support/tickets/**`.
- Shared `/api/v1/support/**` must be removed, not merely forbidden.
- Retired dealer-finance routes must be probed with actors that used to be authorized on them; a dealer-only `403` is not enough proof of retirement.
- The packet must update `openapi.json`, endpoint inventories, accounting/portal handoff docs, and touched workflow docs together so the host split is published consistently.

## ERP-38 Canonical Factory Flow Notes

- Setup truth remains outside factory execution:
  - product/item setup lives on `/api/v1/catalog/items`
  - packaging setup lives on `/api/v1/factory/packaging-mappings`
- Execution truth is hard-cut to:
  - `POST /api/v1/factory/production/logs`
  - `POST /api/v1/factory/packing-records`
  - `POST /api/v1/sales/dispatch/confirm`
- Factory must not act like a second inventory-admin or accounting-admin workspace.
- `ProductionLogService.createLog(...)` remains the manufacturing-truth entrypoint and `POST /api/v1/factory/production/logs` is the sole public batch-create route.
- `PackingController` now exposes one pack mutation path only: `POST /api/v1/factory/packing-records`. The retired `POST /api/v1/factory/pack` and `POST /api/v1/factory/packing-records/{productionLogId}/complete` routes are removed.
- The surviving pack contract must not auto-create default size variants or default finished-goods targets on behalf of the operator.
- The surviving pack idempotency contract is `Idempotency-Key` only.
- Dispatch posting ownership stays on the sales path through `SalesCoreEngine.confirmDispatch(...)`.
- `/api/v1/dispatch/**` should end as prepared-slip reads only.

## Cross-Module Truth Boundaries

- Production logging must keep raw-material consumption, WIP, and semi-finished truth aligned.
- Packing must keep packaging-material consumption and finished-goods truth aligned.
- Dispatch must keep finished-goods reduction, invoice issuance, AR/COGS posting, and slip/order linkage aligned.
- ERP-38 may touch sales/inventory/orchestrator code only where needed to preserve the single canonical dispatch-confirm owner. It must not redesign unrelated sales-order lifecycle behavior.

## Public Contract Surfaces That Must Stay In Sync

### ERP-21 portal split

- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- `docs/accounting-portal-endpoint-map.md`
- `docs/accounting-portal-frontend-engineer-handoff.md`
- touched workflow/code-review docs for portal, support, finance, and tenant governance
- `.factory/library/frontend-handoff.md`
- `.factory/library/frontend-v2.md`

### ERP-38 canonical factory flow

- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- `docs/workflows/manufacturing-and-packaging.md`
- `docs/workflows/inventory-management.md`
- `docs/workflows/sales-order-to-cash.md`
- `docs/code-review/flows/manufacturing-inventory.md`
- `docs/code-review/flows/order-to-cash.md`
- `.factory/library/frontend-handoff.md`
- `.factory/library/frontend-v2.md`
