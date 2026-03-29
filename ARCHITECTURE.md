# BigBright ERP — Architecture

Last reviewed: 2026-03-29

This is the root architecture document for the BigBright ERP backend. For the full code-grounded architecture reference, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## System Shape

BigBright ERP is a modular monolith built with Spring Boot. The base package is `com.bigbrightpaints.erp`.

- **core** — shared infrastructure: security, config, exception handling, audit, idempotency, health, utilities
- **modules** — domain modules: accounting, admin, auth, company, demo, factory, hr, inventory, invoice, portal, production, purchasing, rbac, reports, sales
- **orchestrator** — background coordination: outbox/event publishing, command dispatch, scheduler surfaces
- **shared/dto** — shared API envelopes and cross-cutting DTOs

## Key Architectural Invariants

- **Tenant scoping is mandatory.** All company-scoped data depends on company context and scoped queries.
- **`ApplicationException` + `ErrorCode` are the business error contract.**
- **Accounting is the financial truth boundary.** Journals, settlements, period control, and reconciliation depend on explicit accounting ownership.
- **Idempotency is a shared pattern with module-local implementations.**
- **Role/host boundaries are part of the architecture.** Admin/internal portal, dealer self-service portal, and superadmin/control-plane surfaces are distinct contract surfaces.

## Key Cross-Module Dependencies

- Sales → Accounting (AR/revenue/COGS, settlements)
- Sales → Inventory (reservation, dispatch, stock execution)
- Purchasing → Inventory (GRN/stock-intake, returns)
- Purchasing → Accounting (AP, tax, settlement)
- Factory → Inventory (raw-material consumption, finished-goods creation, batch registration)
- Factory → Accounting (manufacturing/packing financial side effects)
- HR → Accounting (payroll posting and payment seams)

## Further Reading

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — full runtime architecture reference
- [docs/RELIABILITY.md](docs/RELIABILITY.md) — reliability posture and safety gaps
- [docs/SECURITY.md](docs/SECURITY.md) — security review policy
- [docs/INDEX.md](docs/INDEX.md) — canonical documentation index
