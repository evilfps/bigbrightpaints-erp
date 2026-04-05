# orchestrator-erp — Architecture Entrypoint

Last reviewed: 2026-04-02

This repo-root file is a signpost into the canonical `orchestrator-erp` docs spine. Use [docs/INDEX.md](docs/INDEX.md) for navigation, [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full code-grounded runtime architecture, and [docs/deprecated/INDEX.md](docs/deprecated/INDEX.md) for retired or reference-only surfaces.

## Canonical Docs Spine

- [docs/INDEX.md](docs/INDEX.md) — canonical documentation entrypoint
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — canonical runtime architecture reference
- [docs/frontend-portals/README.md](docs/frontend-portals/README.md) — canonical portal ownership docs
- [docs/frontend-api/README.md](docs/frontend-api/README.md) — canonical shared frontend/API contract docs
- [docs/deprecated/INDEX.md](docs/deprecated/INDEX.md) — retirement and reference-only registry

## System Shape

orchestrator-erp is a modular monolith built with Spring Boot. The base package remains `com.bigbrightpaints.erp`.

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
