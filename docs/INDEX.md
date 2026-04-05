# orchestrator-erp — Backend Documentation Index

Last reviewed: 2026-04-02

This is the canonical entrypoint for backend documentation. Every major docs section is linked from here. If a packet is not reachable through this index, it is not part of the canonical docs tree.

`README.md` and repo-root [`ARCHITECTURE.md`](../ARCHITECTURE.md) are signposts into this spine. Public runtime and deployment truth lives under `docs/`; `.factory/library/*` remains internal worker guidance rather than canonical reader-facing documentation.

---

## Repo-Root Entry Points

| Document | Purpose |
| --- | --- |
| [README.md](../README.md) | Repository overview and setup entrypoint that routes readers into the canonical docs spine |
| [ARCHITECTURE.md](../ARCHITECTURE.md) | Repo-root architecture signpost; use [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) for the full runtime architecture reference |

## Architecture and Platform

| Document | Purpose |
| --- | --- |
| [docs/ARCHITECTURE.md](ARCHITECTURE.md) | Runtime architecture, module map, cross-module boundaries, data model, security, and event contracts |
| [docs/RELIABILITY.md](RELIABILITY.md) | Reliability posture: idempotency patterns, retry/dead-letter handling, outbox guarantees, and known safety gaps |
| [docs/SECURITY.md](SECURITY.md) | Security review policy, high-risk change classes, and R2 approval workflow |
| [docs/CONVENTIONS.md](CONVENTIONS.md) | Truth-first writing rules, cross-link expectations, implemented-vs-planned language, and stale-doc handling policy |
| [docs/platform/db-migration.md](platform/db-migration.md) | Persistence technology, schema areas, entity/repository conventions, Flyway v2 migration posture, profile activation, legacy-track constraints, and data-import entry surfaces |
| [docs/platform/config-feature-toggles.md](platform/config-feature-toggles.md) | High-impact platform settings and feature toggles: security, licensing, mail/notification, export-approval, module/runtime gating, integration, accounting-event, inventory, orchestrator, seed, and benchmark switches with scope and default caveats |
| [docs/platform/health-readiness-gating.md](platform/health-readiness-gating.md) | Operator-facing health and readiness endpoints, integration-health surfaces, module-gating mechanics, runtime-admission gates, and caveats around which checks to trust |

## Quick Reference: Backend Feature Catalog

| Document | Purpose |
| --- | --- |
| [docs/BACKEND-FEATURE-CATALOG.md](BACKEND-FEATURE-CATALOG.md) | Reader-friendly summary of the complete backend feature landscape — exhaustive coverage of platform, operations, commercial, and finance/reporting features with links to deeper module/flow/ADR packets and explicit deprecated/non-canonical surface flags |

## Authoritative Recommendations

| Document | Purpose |
| --- | --- |
| [docs/RECOMMENDATIONS.md](RECOMMENDATIONS.md) | **Canonical recommendations register** — single authoritative surface for user-approved verdicts on formerly open items from flow docs and module packets. Classifies each item as Bug to Fix Now, Future Work (high/medium/low priority), or Accepted Product Decision. All open-decision sections in flow/module packets should defer to this register. |

## Modules

| Document | Purpose |
| --- | --- |
| [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md) | Canonical inventory of every live backend module with ownership summary and links to per-module documentation packets |

Module packets explain what each module owns: controllers, services, DTOs, entities, helpers, events, and cross-module boundaries. Ten modules have canonical documentation packets today:

| Module | Description |
| --- | --- |
| [accounting](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) | Journals, ledgers, settlements, period controls, reconciliation, and imports |
| [admin/portal/rbac](modules/admin-portal-rbac.md) | Admin user management, system settings, changelog, export approvals, support tickets, portal insights/finance, dealer self-service host ownership, role-action matrices, and RBAC enforcement |
| [auth](modules/auth.md) | Login, refresh, logout, MFA, password reset, must-change-password corridor, token/session revocation, JWT-based tenant scoping, and security filter chain |
| [company](modules/company.md) | Tenant lifecycle, runtime admission, module gating, licensing checks, tenant onboarding, super-admin control plane, and company-scoping assumptions |
| [core security/error](modules/core-security-error.md) | Security filter chain (JWT, company context, must-change-password corridor), exception/error contract (`ApplicationException`, `ErrorCode`, global handlers), fail-open vs fail-closed boundaries — first slice of the three-part core platform contract |
| [core audit/runtime/settings](modules/core-audit-runtime-settings.md) | Audit-surface ownership (platform audit, enterprise audit trail, accounting event store), runtime-gating split (three enforcement layers), global-versus-tenant settings risk — second slice of the three-part core platform contract |
| [core idempotency](modules/core-idempotency.md) | Shared idempotency infrastructure (key normalization, reservation, signature building), module-local idempotency implementations, contract inconsistencies, and the reconciled core platform contract reference — third/integrating slice |
| [orchestrator](modules/orchestrator.md) | Background coordination: outbox publishing, command dispatch, Spring event bridges, schedulers, retry/dead-letter behavior, feature flags, and deprecated/dead orchestration seams |
| [hr](modules/hr.md) | Employees, leave, attendance, payroll runs, and payroll posting/payment |
| [inventory](modules/inventory.md) | Stock truth boundary: stock summaries, batches, adjustments, opening stock import, valuation, traceability, dispatch execution, and inventory–accounting event bridge |
| [factory/manufacturing](modules/factory.md) | Manufacturing execution: production logs, packing, packaging mappings, batch registration, cost allocation, dispatch handoff boundary, deprecated seams, and replay/config caveats |
| [sales](modules/sales.md) | Dealer/customer management, order lifecycle, credit controls, dispatch coordination, dealer self-service, and canonical O2C path |
| [production/catalog](modules/catalog-setup.md) | Catalog and setup readiness: brands, items, import, SKU readiness evaluation, packaging-material definitions, payload families, and setup prerequisites for downstream flows |
| [purchasing/procure-to-pay](modules/purchasing.md) | Supplier lifecycle, purchase orders, goods receipt (GRN), purchase invoices, purchase returns, supplier settlements, and explicit stock-truth vs AP-truth boundaries |

## Flows

Flow packets explain cross-module behavior: actors, entrypoints, preconditions, lifecycle, completion boundary, and current limitations.

| Document | Purpose |
| --- | --- |
| [docs/flows/FLOW-INVENTORY.md](flows/FLOW-INVENTORY.md) | Canonical inventory of the eleven major backend flow families with ownership summary, cross-module participants, and links to flow packets |

The flow inventory covers auth/identity, tenant/admin management, catalog/setup readiness, manufacturing/packing, inventory management, order-to-cash, procure-to-pay, invoice/dealer-finance, accounting/period close, HR/payroll, and reporting/export. All eleven flow packets are now available.

## Architecture Decision Records (ADRs)

ADRs explain accepted current decisions already embodied by the backend — why the architecture looks the way it does today.

| Document | Purpose |
| --- | --- |
| [docs/adrs/INDEX.md](adrs/INDEX.md) | ADR index: lists all accepted architecture and product decisions with links to individual ADR files |

The seeded ADR set covers multi-tenant auth scoping (ADR-002), outbox/idempotency strategy (ADR-003), audit layering (ADR-004), migration posture (ADR-005), and portal/host boundaries (ADR-006).

## Canonical Frontend Documentation

> **This is the canonical source for frontend contracts.** The new mainline docs structure replaces the older frontend-handoff model.

| Document | Purpose |
| --- | --- |
| [docs/frontend-portals/README.md](frontend-portals/README.md) | **Canonical portal ownership map** — six portal shells (superadmin, tenant-admin, accounting, sales, factory, dealer-client), each with routes, API contracts, workflows, role-boundaries, states-and-errors, and playwright-journeys |
| [docs/frontend-api/README.md](frontend-api/README.md) | **Canonical shared API contracts** — bootstrap rules (`GET /api/v1/auth/me` as sole entry), tenant scoping (`companyCode`), retired-route warnings, and shared topic files (auth/company-scope, pagination/filters, exports/approvals, idempotency/errors, accounting-reference-chains, dto-examples) |

### Portal Details

| Portal | Folder | Contents |
| --- | --- | --- |
| Superadmin | [docs/frontend-portals/superadmin/](frontend-portals/superadmin/) | Control-plane ownership, cross-tenant operations |
| Tenant Admin | [docs/frontend-portals/tenant-admin/](frontend-portals/tenant-admin/) | Tenant-scoped admin, export approvals, reporting |
| Accounting | [docs/frontend-portals/accounting/](frontend-portals/accounting/) | Financial reports, period controls, journal access |
| Sales | [docs/frontend-portals/sales/](frontend-portals/sales/) | Order management, dealer management, credit controls |
| Factory | [docs/frontend-portals/factory/](frontend-portals/factory/) | Production logs, packing, dispatch execution |
| Dealer Client | [docs/frontend-portals/dealer-client/](frontend-portals/dealer-client/) | Self-service portal, own orders/invoices/ledger |

## Legacy Frontend Handoff (Reference Only)

> **⚠️ These documents are NON-CANONICAL / REFERENCE ONLY.** The canonical frontend documentation is now at `docs/frontend-portals/` and `docs/frontend-api/` above. These older handoff files are retained for reference but should not be used as the source of truth.

| Document | Status |
| --- | --- |
| [docs/frontend-handoff-platform.md](frontend-handoff-platform.md) | Reference only — superseded by `docs/frontend-portals/` and `docs/frontend-api/` |
| [docs/frontend-handoff-operations.md](frontend-handoff-operations.md) | Reference only — superseded by `docs/frontend-portals/` and `docs/frontend-api/` |
| [docs/frontend-handoff-commercial.md](frontend-handoff-commercial.md) | Reference only — superseded by `docs/frontend-portals/` and `docs/frontend-api/` |
| [docs/frontend-handoff-finance.md](frontend-handoff-finance.md) | Reference only — superseded by `docs/frontend-portals/` and `docs/frontend-api/` |
| [docs/accounting-portal-frontend-engineer-handoff.md](accounting-portal-frontend-engineer-handoff.md) | Reference only — superseded by `docs/frontend-portals/accounting/` |

## Historical Workflow Guides (Reference Only)

> **⚠️ These workflow guides are NON-CANONICAL / REFERENCE ONLY.** Canonical lifecycle truth lives in `docs/flows/*`. The guides below are retained as historical step-by-step references and are also registered in `docs/deprecated/INDEX.md`.

| Document | Status |
| --- | --- |
| [docs/workflows/admin-and-tenant-management.md](workflows/admin-and-tenant-management.md) | Reference only — superseded by `docs/flows/tenant-admin-management.md` |
| [docs/workflows/manufacturing-and-packaging.md](workflows/manufacturing-and-packaging.md) | Reference only — superseded by `docs/flows/manufacturing-packing.md` |
| [docs/workflows/inventory-management.md](workflows/inventory-management.md) | Reference only — superseded by `docs/flows/inventory-management.md` |
| [docs/workflows/sales-order-to-cash.md](workflows/sales-order-to-cash.md) | Reference only — superseded by `docs/flows/order-to-cash.md` |
| [docs/workflows/purchase-to-pay.md](workflows/purchase-to-pay.md) | Reference only — superseded by `docs/flows/procure-to-pay.md` |
| [docs/workflows/accounting-and-period-close.md](workflows/accounting-and-period-close.md) | Reference only — superseded by `docs/flows/accounting-period-close.md` |
| [docs/workflows/payroll.md](workflows/payroll.md) | Reference only — superseded by `docs/flows/hr-payroll.md` |
| [docs/workflows/data-migration.md](workflows/data-migration.md) | Reference only — use `docs/runbooks/migrations.md` for current migration rollout truth |

## Deprecated and Incomplete Surfaces

The deprecated/incomplete registry lists retired, partial, duplicated, or dead-end surfaces. Every entry points to a canonical replacement or explicitly states that no replacement exists.

| Document | Purpose |
| --- | --- |
| [docs/deprecated/INDEX.md](deprecated/INDEX.md) | Canonical registry of all deprecated, unmaintained, incomplete, or dead-end surfaces with replacement links or explicit no-replacement notes |

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

## Retained Reference Docs

The following docs remain in the repo for reference but are **not** part of the canonical docs spine. They may be stale, partial, or narrower than the current implementation.

| Document | Status |
| --- | --- |
| [developer-guide.md](developer-guide.md) | Non-canonical — superseded by the module packets and flow packets in the docs tree |
| [ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md](ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md) | Reference only — retained accounting-portal scope lock; use `docs/frontend-portals/accounting/README.md` and `docs/frontend-api/README.md` for current portal truth |
| [AUDIT_TRAIL_OWNERSHIP.md](AUDIT_TRAIL_OWNERSHIP.md) | Reference only — retained audit de-dup/change-control contract; use `docs/modules/core-audit-runtime-settings.md` for the canonical audit ownership overview |
| [accounting-portal-endpoint-map.md](accounting-portal-endpoint-map.md) | Reference only — curated parity snapshot; use `docs/frontend-portals/accounting/README.md`, `docs/frontend-api/README.md`, and `openapi.json` for current contract truth |
| [endpoint-inventory.md](endpoint-inventory.md) | Reference only; use `openapi.json` and module packets as primary truth |
| [migration-guide.md](migration-guide.md) | Retired reference only — current migration rollout guidance lives in `docs/runbooks/migrations.md`; the legacy CSV/Tally appendix is archival |
