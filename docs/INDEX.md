# BigBright ERP — Backend Documentation Index

Last reviewed: 2026-03-29

This is the canonical entrypoint for backend documentation. Every major docs section is linked from here. If a packet is not reachable through this index, it is not part of the canonical docs tree.

---

## Architecture and Platform

| Document | Purpose |
| --- | --- |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | Runtime architecture, module map, cross-module boundaries, data model, security, and event contracts |
| [docs/RELIABILITY.md](RELIABILITY.md) | Reliability posture: idempotency patterns, retry/dead-letter handling, outbox guarantees, and known safety gaps |
| [docs/SECURITY.md](SECURITY.md) | Security review policy, high-risk change classes, and R2 approval workflow |
| [docs/CONVENTIONS.md](CONVENTIONS.md) | Truth-first writing rules, cross-link expectations, implemented-vs-planned language, and stale-doc handling policy |

## Modules

| Document | Purpose |
| --- | --- |
| [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md) | Canonical inventory of every live backend module with ownership summary and links to per-module documentation packets |

Module packets explain what each module owns: controllers, services, DTOs, entities, helpers, events, and cross-module boundaries. Six modules have canonical documentation packets today:

| Module | Description |
| --- | --- |
| [accounting](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) | Journals, ledgers, settlements, period controls, reconciliation, and imports |
| [auth](modules/auth.md) | Login, refresh, logout, MFA, password reset, must-change-password corridor, token/session revocation, JWT-based tenant scoping, and security filter chain |
| [company](modules/company.md) | Tenant lifecycle, runtime admission, module gating, licensing checks, tenant onboarding, super-admin control plane, and company-scoping assumptions |
| [hr](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/hr/AGENTS.md) | Employees, leave, attendance, payroll runs, and payroll posting/payment |
| [inventory](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/AGENTS.md) | Stock, reservations, adjustments, batch traceability, opening stock, and valuation |
| [sales](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/AGENTS.md) | Dealer/customer flows, order lifecycle, credit controls, and dispatch ownership |

## Flows

Flow packets explain cross-module behavior: actors, entrypoints, preconditions, lifecycle, completion boundary, and current limitations.

| Document | Purpose |
| --- | --- |
| [docs/flows/FLOW-INVENTORY.md](flows/FLOW-INVENTORY.md) | Canonical inventory of the eleven major backend flow families with ownership summary, cross-module participants, and links to flow packets |

The flow inventory covers auth/identity, tenant/admin management, catalog/setup readiness, manufacturing/packing, inventory management, order-to-cash, procure-to-pay, invoice/dealer-finance, accounting/period close, HR/payroll, and reporting/export. Individual flow packets for each family will be added in later milestones.

## Architecture Decision Records (ADRs)

ADRs explain accepted current decisions already embodied by the backend — why the architecture looks the way it does today.

| Document | Purpose |
| --- | --- |
| [docs/adrs/INDEX.md](adrs/INDEX.md) | ADR index: lists all accepted architecture and product decisions with links to individual ADR files |

The seeded ADR set covers multi-tenant auth scoping (ADR-002), outbox/idempotency strategy (ADR-003), audit layering (ADR-004), migration posture (ADR-005), and portal/host boundaries (ADR-006).

## Frontend Handoff

Frontend handoff packets explain canonical hosts, payload families, RBAC assumptions, and read/write boundaries for each major surface. They link back to canonical module and flow docs instead of duplicating truth.

> **Status:** Frontend handoff packets for operational, commercial, and finance surfaces will be added in the `docs-frontend-handoff-library` feature.

## Deprecated and Incomplete Surfaces

The deprecated/incomplete registry lists retired, partial, duplicated, or dead-end surfaces. Every entry points to a canonical replacement or explicitly states that no replacement exists.

> **Status:** The deprecated/incomplete registry will be added in the `docs-consolidation-deprecated-registry` feature.

## Governance and Agents

| Document | Purpose |
| --- | --- |
| [docs/agents/CATALOG.md](agents/CATALOG.md) | Agent/role catalog: responsibilities and required evidence before handoff |
| [docs/agents/PERMISSIONS.md](agents/PERMISSIONS.md) | Agent permission boundaries: what each role may and must not do |
| [docs/agents/WORKFLOW.md](agents/WORKFLOW.md) | Review and remediation workflow: packet types, review ordering, and merge-readiness gates |
| [docs/agents/ENTERPRISE_MODE.md](agents/ENTERPRISE_MODE.md) | Enterprise policy mode: high-risk change detection, R2 triggers, and escalation rules |
| [docs/agents/ORCHESTRATION_LAYER.md](agents/ORCHESTRATION_LAYER.md) | Orchestration layer governance: outbox, event, scheduler, and background-coordination boundaries |

## Approvals

| Document | Purpose |
| --- | --- |
| [docs/approvals/R2-CHECKPOINT.md](approvals/R2-CHECKPOINT.md) | Active R2 checkpoint evidence for the current high-risk packet |
| [docs/approvals/R2-CHECKPOINT-TEMPLATE.md](approvals/R2-CHECKPOINT-TEMPLATE.md) | Template for creating new R2 checkpoints |

## Runbooks

| Document | Purpose |
| --- | --- |
| [docs/runbooks/rollback.md](runbooks/rollback.md) | Rollback procedures for applied migrations and coordinated app/schema cuts |
| [docs/runbooks/migrations.md](runbooks/migrations.md) | Migration forward plans, dry-run commands, and rollback strategies |

## Historical Docs

The following historical docs remain in the repo for reference but are **not** the canonical source of truth. They may be stale, partial, or contradictory with the current implementation.

| Document | Status |
| --- | --- |
| [architecture.md](architecture.md) | Superseded by [docs/ARCHITECTURE.md](ARCHITECTURE.md) |
| [developer-guide.md](developer-guide.md) | Superseded by the module packets and flow packets in the new docs tree |
| [endpoint-inventory.md](endpoint-inventory.md) | Reference only; use `openapi.json` and module packets as primary truth |
