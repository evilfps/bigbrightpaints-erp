# BigBright ERP Architecture (Code-Grounded)

Last reviewed: 2026-03-29

This document captures the runtime architecture implemented in `erp-domain` and is intended as an engineering reference for cross-module behavior, invariants, and dependency boundaries.

## 1) Runtime topology and module map

### 1.1 System topology

```
Clients → Controllers → Services/Engines → Domain/Repositories → DB
                      → AccountingFacade/Core → DB
                      → Spring Events → Services
                      → Orchestrator Outbox → DB
```

### 1.2 Module inventory and layering

- Modules are organized under `com.bigbrightpaints.erp.modules/*` with module-local `domain`, `service`, `controller`, and DTO packages.
- Central cross-cutting infrastructure lives in `core/*` (security filters, exceptions, audit, config) and shared orchestrations in `orchestrator/*`.
- Security and tenant runtime admission starts in `CompanyContextFilter` before module code executes.
- Optional module access is enforced by `ModuleGatingInterceptor` + `ModuleGatingService`, while core modules (`AUTH`, `ACCOUNTING`, `SALES`, `INVENTORY`) remain always-on.

### 1.3 Cross-module accounting dependency model

Most operational modules post financial effects through accounting facade/core APIs:

- Sales/O2C dispatch + invoicing → accounting postings
- Purchasing/GRN/returns → inventory movements + accounting entries
- Factory/production/packing → WIP/consumption/value journals
- Payroll run posting/payment → payroll journals

Key facade entrypoint: `JournalCreationRequest` encapsulates debit/credit lines, source module/reference, and period date semantics.

## 2) End-to-end business flow architecture

### 2.1 O2C (Order-to-Cash)

Order → Reserve → Dispatch → Invoice → AR

- Order creation uses idempotency key + payload signature replay semantics.
- Reservation allocates by configured costing selection methods and records reservation movements.
- Dispatch confirmation enforces slip/order linkage consistency and reconciles existing financial markers on replay.
- Invoice issuance requires single-active-slip semantics and delegates dispatch confirmation if needed.

### 2.2 P2P (Procure-to-Pay)

PO → GRN → Inventory/AP → Purchase Returns

- PO lifecycle transitions are explicit and constrained.
- GRN requires `Idempotency-Key`, hashes request payload, verifies replay signatures, and enforces remaining quantity boundaries.
- Raw material receipt emits inventory movement events for accounting integration.

### 2.3 M2S (Manufacturing-to-Stock)

Production Issue → WIP → Packing → FG Batches

- Production logs consume raw material batches atomically and persist movement traceability.
- Material issue + labor/overhead journals are posted to WIP/consumption accounts.
- Packing idempotency uses reserved keys/hash with replay resolution.

### 2.4 Payroll → Accounting

Payroll Run → Calculation → Posting → Payment

- Payroll run identity/idempotency is period+type keyed with signature checks.
- Posting enforces required payroll account availability and deduction classification constraints.
- Journal posting path goes through standardized lines into accounting core.

### 2.5 Tenant lifecycle / runtime controls

- Onboarding seeds company, template accounts, default accounting pointers, first admin, and baseline period/settings.
- Lifecycle transitions are constrained (`ACTIVE ↔ SUSPENDED`, irreversible from `DEACTIVATED`) and audited.
- Request-path tenant enforcement blocks claim/header mismatches.

## 3) Data model and schema evolution strategy

- Tenant/security core tables in `V1__core_auth_rbac.sql`.
- Accounting base in `V2__accounting_core.sql`.
- Sales/invoice/dealer and packaging slip tables in `V3__sales_invoice.sql`.
- Inventory/factory/production tables in `V4__inventory_production.sql`.
- Purchasing + HR/payroll tables in `V5__purchasing_hr.sql`.
- Orchestrator outbox/audit/scheduler structures in `V6__orchestrator.sql`.

The repo uses a dual-track Flyway strategy: legacy `db/migration/*` and active `db/migration_v2/*`. The `prod,flyway-v2` profile group makes `migration_v2` the operational baseline.

## 4) Security, tenancy, and authorization architecture

- `CompanyContextFilter` enforces JWT company claim presence, header/claim consistency, lifecycle-aware write/read admission.
- Endpoint method guards via `@PreAuthorize`.
- Runtime module gating interceptor for optional modules.
- Idempotency + concurrency hardening via key normalization, payload hash/signature assertions, and optimistic/pessimistic locking.

## 5) Eventing and integration contracts

- Inventory movement/value changes emitted via Spring events and consumed by accounting listener.
- Orchestrator reliability layer with outbox + command/audit persistence.

## 6) Cross-module dependency boundaries

```
Sales → Inventory, Invoice, Accounting
Purchasing → Inventory, Accounting
Factory → Inventory, Accounting
HR → Accounting
Company → Security, ModuleGating
Admin → Company, Auth
Inventory -.events.→ Accounting
```

## 7) Operational guardrails and profile behavior

- Production defaults enabled by profile default (`spring.profiles.default: prod`), grouped to include `flyway-v2`.
- Critical integration toggles include inventory/accounting events, GitHub support sync, opening-stock guards, and security/audit controls.

## 8) Architectural strengths and known trade-offs

**Strengths:** strong idempotency discipline, clear maker-checker workflow for period close, company/tenant enforcement early in request chain, accounting posting centralization.

**Trade-offs:** mixed coexistence of historical flow services and newer decomposed engines/facades; dual migration trees require parity discipline.

## 9) Source index (quick jump)

| Area | Package path (under `erp-domain/src/main/java/com/bigbrightpaints/erp/`) |
| --- | --- |
| Sales/O2C | modules/sales |
| Inventory dispatch/reservation/slips | modules/inventory/service |
| Invoice linkage | modules/invoice/service/InvoiceService.java |
| Accounting core/period/reconciliation | modules/accounting/internal, modules/accounting/service |
| Purchasing/P2P | modules/purchasing |
| Factory/M2S | modules/factory/service |
| Payroll | modules/hr |
| Tenant/runtime security | core/security, modules/company/service |
| Migration/import | modules/accounting/service, modules/inventory/service |

## Cross-references

- [docs/RELIABILITY.md](RELIABILITY.md) — reliability and safety gaps
- [docs/SECURITY.md](SECURITY.md) — security review policy
- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [../AGENTS.md](../AGENTS.md) — repository agent governance
