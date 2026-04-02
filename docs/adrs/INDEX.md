# Architecture Decision Records

Last reviewed: 2026-04-02

This index lists accepted architectural and product decisions already embodied by the orchestrator-erp backend. Each ADR explains **why** a decision was made, not just **what** was decided. ADRs are written against current implemented behavior, not future aspirations.

## ADR Template

Every ADR follows a consistent structure:

| Section | Purpose |
| --- | --- |
| **Status** | Current state (Accepted, Deprecated, Superseded) |
| **Context** | What forces were at play when the decision was made |
| **Decision** | What was decided and how it works today |
| **Alternatives Rejected** | What options were considered and why they were not chosen |
| **Consequences** | What happened as a result — including trade-offs |
| **Cross-references** | Links to related ADRs and canonical docs |

## Naming Scheme

ADR files are numbered sequentially: `ADR-NNN-short-description.md`. Numbers are assigned in order of creation. The existing ADR-001 was created by the architecture-allowlist governance work. ADRs 002–006 were seeded by the docs-foundation-adr milestone.

## Accepted Decisions

| ADR | Title | Summary |
| --- | --- | --- |
| [ADR-001](ADR-001-allowlist-cross-module-imports-baseline.md) | Allowlist Cross-Module Import Edges | Documents the nine intentional cross-module import couplings permitted by the architecture allowlist |
| [ADR-002](ADR-002-multi-tenant-auth-scoping.md) | Multi-Tenant Auth Scoping via JWT Company Claims | Tenant context is derived from the JWT `companyCode` claim and enforced by `CompanyContextFilter`; super admins are limited to platform control-plane operations |
| [ADR-003](ADR-003-outbox-pattern-for-cross-module-events.md) | Outbox Pattern for Cross-Module Event Publishing | Domain events are persisted to an outbox table within the business transaction, then published to RabbitMQ by a scheduled job with fencing, retry, and fail-closed ambiguous-state handling |
| [ADR-004](ADR-004-layered-audit-surfaces.md) | Layered Audit Surfaces | Three coexisting audit surfaces — platform audit, enterprise audit trail, and accounting event store — with distinct ownership, write semantics, and failure modes |
| [ADR-005](ADR-005-flyway-v2-hard-cut-migration-posture.md) | Flyway V2 Hard-Cut Migration Posture | Flyway v2 is the active migration track; many migrations are forward-only with snapshot/PITR recovery rather than SQL undo scripts |
| [ADR-006](ADR-006-portal-and-host-boundary-separation.md) | Portal and Host Boundary Separation | Admin/internal portal (`/api/v1/portal/*`) and dealer/self-service portal (`/api/v1/dealer-portal/*`) are separate host boundaries with independent controllers and RBAC guards |

## How to Add a New ADR

1. Create `docs/adrs/ADR-NNN-short-description.md` using the template sections above.
2. Add the `Last reviewed: YYYY-MM-DD` marker near the top.
3. Set status to `Accepted` (or `Proposed` if the decision is not yet implemented).
4. Add a row to the table above.
5. Link the ADR from any relevant module or flow packet.
6. Run `bash ci/lint-knowledgebase.sh` to verify freshness markers and links.

## Cross-references

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — full architecture reference
- [docs/RELIABILITY.md](../RELIABILITY.md) — reliability posture and known safety gaps
