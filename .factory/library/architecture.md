# Architecture

High-level system map for the BigBright ERP backend and the documentation-refresh mission.

**What belongs here:** system shape, module relationships, canonical truth boundaries, and the architectural seams documentation workers must preserve when rewriting the docs tree.

---

## System Shape

Base package: `com.bigbrightpaints.erp`

- `core/` — shared infrastructure: security, config, exception handling, audit, idempotency, health, utilities
- `modules/` — domain modules: accounting, admin, auth, company, demo, factory, hr, inventory, invoice, portal, production, purchasing, rbac, reports, sales
- `orchestrator/` — background coordination, outbox/event publishing, command dispatch, scheduler surfaces
- `shared/dto/` — shared API envelopes and cross-cutting DTOs

The codebase is a modular monolith. Module boundaries exist in package structure and service ownership, but important business flows still cross modules heavily through facades, listeners, repositories, and read models.

## Canonical Documentation Model

The mission should produce a docs tree that explains the backend from five complementary angles:

1. **Module packets** — what a module owns: controllers, services, DTOs, entities, helpers, events, boundaries
2. **Flow packets** — how cross-module business flows actually work today, including the current definition of done and known gaps
3. **Frontend handoff packets** — host ownership, payload families, RBAC, and frontend-relevant contract notes
4. **Authoritative recommendations register** — the canonical answer set for formerly open product, bug, and plan decisions
5. **ADR index** — why the backend looks the way it does
6. **Deprecated/incomplete registry** — what is retired, partial, duplicated, or intentionally not end-to-end

No packet should silently become a second source of truth. Module docs own structure, flow docs own behavior, handoff docs own consumer framing, the authoritative recommendations register owns recommendation truth for resolved formerly-open items, ADRs own decisions, and deprecated docs own retirement notes.

## Mainline Catch-Up Override

Read-only comparison against `origin/main` shows that the branch-era canonical docs model above is no longer the only active target. Mainline frontend-facing docs now center on:

- `docs/frontend-portals/` — portal ownership, routes, workflows, states, and role boundaries
- `docs/frontend-api/` — shared API contract rules across portals

Workers doing catch-up must preserve backend truth from the earlier packet tree where useful, but they should align final canonical frontend-facing documentation to that mainline portal/API model and explicitly retire or mark older single-file handoff surfaces when they would otherwise compete.

## Core Architectural Invariants

- **Tenant scoping is mandatory.** Company-scoped data depends on company context and scoped queries. Auth, company runtime, and request-admission behavior are foundational.
- **`ApplicationException` + `ErrorCode` are the business error contract.** Module docs should describe domain failures in terms of this contract, not raw framework exceptions.
- **Accounting is the financial truth boundary.** Journals, settlements, period control, reconciliation, and reporting depend on explicit accounting ownership even when the initiating flow lives in another module.
- **Idempotency is a shared pattern with module-local implementations.** Many writes rely on shared idempotency helpers, but modules still layer their own replay logic and signatures.
- **Role/host boundaries are part of the architecture.** Admin/internal portal, dealer self-service portal, and superadmin/control-plane surfaces should be documented as distinct contract surfaces.
- **Legacy and canonical paths may coexist.** Docs must identify which path is authoritative and which path is retired, transitional, or dead.

## Canonical Dependency Edges

These are the dependency edges docs workers should treat as the primary cross-reference map when writing module and flow packets:

- `sales -> accounting` for AR/revenue/COGS, settlements, notes, ledger-facing truth
- `sales -> inventory` for reservation, dispatch, packaging-slip, and stock-facing execution seams
- `purchasing -> inventory` for GRN/stock-intake and return-stock seams
- `purchasing -> accounting` for AP, tax, settlement, and purchase-return financial truth
- `factory -> inventory` for raw-material consumption, finished-goods creation, and batch registration
- `factory -> accounting` for manufacturing/packing financial side effects where applicable
- `hr -> accounting` for payroll posting and payroll-payment seams
- `reports -> accounting/inventory/sales` for reporting truth sources and derived read models
- `portal -> accounting/sales/admin` for internal/admin read models
- `orchestrator -> sales/factory/accounting/inventory` for background coordination, event health, and cross-module command flows

If a docs worker finds a direct repository reach across one of these edges, it should be documented explicitly rather than normalized away.

## Domain Lanes

### Platform and Control Plane

- **auth** — login, refresh, logout, password reset, MFA, session/corridor rules
- **company** — tenant lifecycle, runtime admission, module gating, usage constraints
- **admin** — user management, approvals, exports, support, changelog, selected settings
- **rbac** — role and permission model
- **portal** — internal/admin portal read models and support/finance surfaces
- **orchestrator** — cross-module coordination, outbox publishing, event health, background jobs

### Operations and Inventory

- **production** — catalog/setup surfaces, items/brands/readiness/imports
- **inventory** — stock, reservations, adjustments, batch traceability, opening stock, valuation, dispatch-adjacent reads/writes
- **factory** — production logs, packing, packaging mappings, cost allocation, batch registration

### Commercial and Finance

- **sales** — dealer/customer flows, order lifecycle, credit controls, dispatch ownership, dealer portal
- **invoice** — invoice lifecycle and invoice-facing API surfaces
- **purchasing** — suppliers, PO, GRN, purchase invoice, returns, AP-adjacent seams
- **accounting** — journals, ledgers, settlements, notes, period control, reconciliation, imports
- **reports** — trial balance, financial statements, GST, aging, dashboards, exports
- **hr** — employees, leave, attendance, payroll runs/calculation

## Canonical Truth Boundaries

### Setup and Readiness

- Catalog/setup truth lives primarily on the production/catalog surface.
- Packaging/setup truth lives on packaging-mapping/configuration surfaces, not inside execution flows.
- Readiness exists as an explicit cross-cutting concept; docs should explain which downstream flows it gates.

### Operations

- Production logging owns raw-material/WIP execution truth.
- Packing owns packaging-material consumption and finished-goods creation truth.
- Inventory owns stock visibility, adjustments, traceability, and reservation-adjacent truth.

### Commercial

- Sales/order-to-cash owns commercial lifecycle truth for dealer/customer orders and credit decisions.
- Dispatch is a critical cross-module seam: the controller/module location and the financial posting owner may differ, and docs must explain both ownership layers clearly.
- Purchasing/procure-to-pay owns supplier lifecycle, PO/GRN, purchase invoice, and returns truth.

### Finance

- Accounting owns financial posting, corrections, period controls, settlement truth, and formal reporting boundaries.
- HR/payroll owns payroll calculation truth; the accounting-host payment seam must be documented as a cross-module boundary rather than implied away.
- Reports may mix live and snapshot-backed data depending on surface; docs should disclose which source-of-truth strategy applies.

## Cross-Module Patterns Docs Must Preserve

- **Facade ownership** — many financial side effects route through accounting facades or listener bridges. Key examples to surface when relevant include `AccountingFacade`, `AccountingCoreEngineCore`, and flow-specific coordinating services such as `SalesCoreEngine`, `PurchaseInvoiceEngine`, and payroll posting/payment seams.
- **Internal event bridges** — Spring events and transactional listeners create hidden but important module couplings. Key examples include inventory/accounting event listeners, sales-order creation listeners, audit listeners, and orchestrator outbox/event paths.
- **Shared entity/read-model drift risks** — some flows depend on duplicated read models or delayed synchronization
- **Configuration-guarded safety** — certain risks are controlled by feature flags or config posture rather than hard architectural prevention
- **Deprecated/compatibility aliases** — docs must surface them as compatibility seams, not canonical behavior

## Persistence, Migration, and API Contract Notes

- The repo contains both legacy and v2 migration tracks; docs workers must explain the active posture and not treat both tracks as equally live without saying so.
- Multi-tenant isolation is enforced through company-scoped data and request context; docs should call out where entities, repositories, and runtime admission rely on that assumption.
- `openapi.json` is the canonical public API snapshot for payload and route inventory, but workers must still cross-check controllers, DTOs, and validation paths because the OpenAPI is structurally broad and semantically thin.
- Public host/path ownership should be documented in one place and reused across module docs, flow docs, and frontend handoff docs rather than re-invented in each packet.

## Dispatch Ownership Clarification

Dispatch is intentionally documented as a two-layer seam:

- **Transport/controller location** may sit on one module surface
- **Commercial/accounting ownership** may be asserted by another module's service path

Docs workers must explicitly answer both of these questions in any dispatch-related packet:
1. Which controller/host owns the public dispatch entrypoint today?
2. Which module/service path owns the authoritative business and financial side effects triggered by dispatch?

If those answers differ, the packet must say so plainly rather than collapsing them into one owner.

## Frontend Handoff Model

Frontend handoff packets should answer these questions for each surface:

- which host/path family is canonical
- which roles/actors can read or mutate it
- which payload families/DTO groups matter
- which module and flow packets own the implementation truth
- which deprecated or internal-only paths frontend should avoid

At minimum, workers should expect separate handoff concerns for:

- admin/internal portal surfaces
- dealer/self-service portal surfaces
- operational setup/execution surfaces
- finance/reporting/approval surfaces

## Evidence Sources for Docs Workers

When documenting a surface, workers should prefer evidence in this order:

1. controller annotations and DTOs for route/payload truth
2. service/facade/engine code for lifecycle and side-effect truth
3. events/listeners and config for hidden coupling and runtime gating
4. tests for executed/guarded behavior
5. existing docs only as secondary inputs to preserve or retire

## Public Contract Surfaces That Must Stay In Sync

When a worker updates any of these contract surfaces, related docs should be updated together:

- `openapi.json`
- `docs/endpoint-inventory.md`
- `erp-domain/docs/endpoint_inventory.tsv`
- module packets under the new docs tree
- flow packets under the new docs tree
- frontend handoff packets under the new docs tree
- ADRs when the packet changes architectural truth
- deprecated/incomplete registry when the packet retires or supersedes a surface

## Documentation-Mission Guidance

- Prefer **code-grounded explanation** over aspirational architecture.
- When a surface is partial, brittle, deprecated, or controlled only by config, say so explicitly.
- Preserve the distinction between **module ownership** and **flow ownership**; they often differ.
- Record decisions and known seams in docs rather than hiding them in review artifacts.
