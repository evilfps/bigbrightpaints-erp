# Backend Feature Catalog

Last reviewed: 2026-04-02

This document is a reader-friendly summary of the complete orchestrator-erp backend feature landscape. It lets a human read one place and understand exactly what the backend currently contains, with links to deeper canonical packets for detailed exploration.

For the authoritative module and flow inventories, see [Module Inventory](modules/MODULE-INVENTORY.md) and [Flow Inventory](flows/FLOW-INVENTORY.md).

---

## How to Use This Catalog

- **Quick orientation**: Browse the feature families below to find the area you're interested in.
- **Deep dive**: Click the linked packet names to reach full module/flow documentation.
- **Find deprecated surfaces**: Each section includes a "Deprecated/Non-Canonical Surfaces" table flagging retired, partial, or no-replacement areas.
- **Understand boundaries**: Each feature entry includes the owning module, key entrypoints, and cross-module seams.

---

## Platform and Cross-Cutting Features

These features support the entire backend and are consumed by all domain areas.

| Feature Family | Description | Owning Module | Key Entrypoints | Deeper Docs |
| --- | --- | --- | --- | --- |
| **Authentication & Identity** | Login, token refresh, logout, MFA, password reset, must-change-password corridor, session/token revocation, JWT tenant scoping | `auth` | `/api/v1/auth/**`, `/api/v1/auth/mfa/**` | [auth.md](modules/auth.md), [auth-identity flow](flows/auth-identity.md) |
| **Tenant & Company Management** | Company CRUD, runtime admission, module gating, licensing checks, company-scoping assumptions, tenant lifecycle | `company` | `/api/v1/companies/**` | [company.md](modules/company.md), [tenant-admin-management flow](flows/tenant-admin-management.md) |
| **Admin & Portal RBAC** | Admin user management, support tickets, export approvals, dealer portal host ownership, role-action matrices, RBAC enforcement | `admin`, `portal`, `rbac` | `/api/v1/admin/**`, `/api/v1/portal/**`, `/api/v1/superadmin/**` | [admin-portal-rbac.md](modules/admin-portal-rbac.md), [tenant-admin-management flow](flows/tenant-admin-management.md) |
| **Core Security & Error** | Security filter chain (JWT, company context, must-change-password), exception/error contract (ApplicationException, ErrorCode), fail-open vs fail-closed boundaries | cross-cutting | filter chain, exception handlers | [core-security-error.md](modules/core-security-error.md) |
| **Core Audit, Runtime & Settings** | Platform audit, enterprise audit trail, accounting event store, runtime-gating split, global vs tenant settings risk | cross-cutting | audit listeners, runtime services | [core-audit-runtime-settings.md](modules/core-audit-runtime-settings.md), [ADR-004](adrs/ADR-004-layered-audit-surfaces.md) |
| **Core Idempotency** | Shared idempotency infrastructure, module-local idempotency implementations, replay semantics | cross-cutting | Idempotency-Key header processing | [core-idempotency.md](modules/core-idempotency.md), [ADR-003](adrs/ADR-003-outbox-pattern-for-cross-module-events.md) |
| **Orchestration & Events** | Outbox publishing, command dispatch, Spring event bridges, schedulers, retry/dead-letter, feature flags | `orchestrator` | outbox tables, event listeners, scheduled jobs | [orchestrator.md](modules/orchestrator.md), [ADR-003](adrs/ADR-003-outbox-pattern-for-cross-module-events.md) |
| **Database & Migration** | Persistence technology, schema areas, Flyway v2 migration posture, legacy-track constraints, data imports | cross-cutting | Flyway tables, entity repositories | [db-migration.md](platform/db-migration.md), [ADR-005](adrs/ADR-005-flyway-v2-hard-cut-migration-posture.md) |
| **Configuration & Feature Toggles** | Security, licensing, mail, export-approval, module/runtime gating, integration switches | cross-cutting | application.yml, feature flags | [config-feature-toggles.md](platform/config-feature-toggles.md) |
| **Health & Runtime Gating** | Health endpoints, integration health, module/runtime admission gates, operator-facing checks | cross-cutting | `/actuator/health`, custom health indicators | [health-readiness-gating.md](platform/health-readiness-gating.md) |

### Deprecated/Non-Canonical Surfaces (Platform)

| Surface | Status | Replacement |
| --- | --- | --- |
| Legacy super-admin forgot-password alias (`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`) | Deprecated (V157 hard cut) | Use `POST /api/v1/auth/password/forgot` |
| Email-only recovery identity (without companyCode) | Deprecated | Require `email + companyCode` for identity verification |
| `/api/v1/support/**` (shared host) | Deprecated | Use host-specific paths: `/api/v1/portal/support/tickets` or `/api/v1/dealer-portal/support/tickets` |

---

## Operations Features

These features handle catalog, inventory, and manufacturing operations.

| Feature Family | Description | Owning Module | Key Entrypoints | Deeper Docs |
| --- | --- | --- | --- | --- |
| **Catalog & Setup Readiness** | Brands, items, item import, SKU readiness evaluation, packaging material definitions, packaging mapping/rules | `production` | `/api/v1/catalog/**` | [catalog-setup.md](modules/catalog-setup.md), [catalog-setup-readiness flow](flows/catalog-setup-readiness.md) |
| **Inventory Management** | Stock summaries (FG/RM), batch traceability, reservations, adjustments, opening stock import, valuation, dispatch execution, inventory–accounting event bridge | `inventory` | `/api/v1/inventory/**`, `/api/v1/finished-goods/**`, `/api/v1/raw-materials/**`, `/api/v1/dispatch/**` | [inventory.md](modules/inventory.md), [inventory-management flow](flows/inventory-management.md) |
| **Factory / Manufacturing** | Production plan creation, production logs with WIP cost, packing into child SKU variants, batch registration, cost allocation, dispatch handoff | `factory` | `/api/v1/factory/**` | [factory.md](modules/factory.md), [manufacturing-packing flow](flows/manufacturing-packing.md) |

### Deprecated/Non-Canonical Surfaces (Operations)

| Surface | Status | Replacement |
| --- | --- | --- |
| `POST /api/v1/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| `POST /api/v1/accounting/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| Internal `ProductionCatalogService` listing methods | Non-canonical (internal only) | Use `CatalogService.searchItems()` |
| `POST /api/v1/factory/production-batches` | Retired (hard cut) | Use `POST /api/v1/factory/production/logs` |
| `GET /api/v1/factory/production-batches` | Retired (hard cut) | Use `GET /api/v1/factory/production/logs` |
| `X-Idempotency-Key` header on packing requests | Rejected (400 error) | Use `Idempotency-Key` (canonical header) |
| `ProductionBatch` entity | Unmaintained (unused) | Use `ProductionLog` entity |
| Legacy dispatch confirm path | Retired | Use `POST /api/v1/dispatch/confirm` with canonical payload |
| Legacy raw-material intake endpoints | No replacement | Disabled via `erp.raw-material.intake.enabled=false`. Raw material stock intake now occurs through GRN processing in procure-to-pay |

---

## Commercial Features

These features handle sales, purchasing, and commercial finance.

| Feature Family | Description | Owning Module | Key Entrypoints | Deeper Docs |
| --- | --- | --- | --- | --- |
| **Sales / Order-to-Cash (O2C)** | Dealer/customer management, sales order lifecycle (draft → confirmed → dispatched), credit controls, inventory reservation, dispatch confirmation, auto-invoice, dealer receipt/collection, settlement, dunning | `sales` | `/api/v1/sales/**`, `/api/v1/dealers/**`, `/api/v1/credit/**`, `/api/v1/dealer-portal/**` | [sales.md](modules/sales.md), [order-to-cash flow](flows/order-to-cash.md) |
| **Procure-to-Pay (P2P)** | Supplier lifecycle, purchase orders, GRN (goods receipt), purchase invoices, purchase returns, supplier settlements, AP boundaries | `purchasing` | `/api/v1/purchasing/**`, `/api/v1/suppliers/**` | [purchasing.md](modules/purchasing.md), [procure-to-pay flow](flows/procure-to-pay.md) |
| **Invoice & Dealer Finance** | Invoice issuance (auto after dispatch), invoice listing/detail, portal finance views (ledger, invoices, aging), dealer invoice PDF, settlement linkage, portal finance isolation | `invoice`, `portal` | `/api/v1/invoices/**`, `/api/v1/portal/finance/**`, `/api/v1/dealer-portal/**` | [invoice.md](modules/invoice.md) *(see also)* [admin-portal-rbac.md](modules/admin-portal-rbac.md), [invoice-dealer-finance flow](flows/invoice-dealer-finance.md) |

### Deprecated/Non-Canonical Surfaces (Commercial)

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/sales/dealers` | Deprecated (frontend alias) | Use `/api/v1/dealers` |
| Legacy idempotency key resolution | Deprecated | Use canonical `Idempotency-Key` header |
| Legacy order statuses (BOOKED, SHIPPED, etc.) | Legacy compatibility only | Use canonical order statuses in `SalesOrderStatus` enum |
| `POST /api/v1/sales/promotions` | Planned foundation | Sales targets only; promotions endpoint infrastructure exists for future activation |
| `X-Idempotency-Key` header on GRN | Rejected (400 error) | Use `Idempotency-Key` |
| PO creation idempotency | Not supported | Rely on order number uniqueness |
| `GET /api/v1/dealers/{id}/ledger` | Retired (410 Gone) | Use `/api/v1/portal/finance/ledger?dealerId={id}` |
| `GET /api/v1/dealers/{id}/invoices` | Retired (410 Gone) | Use `/api/v1/portal/finance/invoices?dealerId={id}` |

---

## Finance and Reporting Features

These features handle accounting, financial reporting, and HR/payroll.

| Feature Family | Description | Owning Module | Key Entrypoints | Deeper Docs |
| --- | --- | --- | --- | --- |
| **Accounting / Period Close** | Manual/automated journal posting, bank reconciliation, sub-ledger reconciliation, GST reconciliation, period lock, period close (maker-checker), corrections via reversal/notes, opening balance import, Tally XML import | `accounting` | `/api/v1/accounting/**`, `/api/v1/migration/**` | [AGENTS.md](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md), [accounting-period-close flow](flows/accounting-period-close.md) |
| **HR & Payroll** | Employee management, leave management, attendance tracking, salary structure templates, payroll run lifecycle (create → calculate → approve → post → mark-paid), statutory deductions (PF/ESI/TDS/prof-tax), payroll posting to accounting, payroll payment | `hr` | `/api/v1/hr/**`, `/api/v1/payroll/**` | [hr.md](modules/hr.md), [hr-payroll flow](flows/hr-payroll.md) |
| **Reporting & Export** | Trial balance, P&L, balance sheet (flat + hierarchy), cash flow, GST return, inventory valuation, inventory reconciliation, aged debtors/receivables, wastage report, production cost breakdown, export request/approval/download, reconciliation dashboard, balance warnings | `reports` | `/api/v1/reports/**`, `/api/v1/exports/**` | [reports.md](modules/reports.md), [reporting-export flow](flows/reporting-export.md) |

### Deprecated/Non-Canonical Surfaces (Finance/Reporting)

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/hr/payroll-runs` | Deprecated (410 Gone) | Use `/api/v1/payroll/runs` |
| `POST /api/v1/hr/payroll-runs` | Deprecated (410 Gone) | Use `/api/v1/payroll/runs` |
| Audit digest CSV export functionality | Deprecated | Use standard export workflow (`POST /api/v1/exports/request` → `GET /api/v1/exports/{requestId}/download`) |
| Direct period reopen from CLOSED status | Not supported | Create adjustment journal entries instead |
| Editing posted journals | Not supported | Reverse and re-create via correction entries |
| `GET /api/v1/reports/cash-flow` (without date filtering) | Non-canonical | Use with explicit date range parameters; heuristic classification may produce inconsistent results |
| `GET /api/v1/reports/aged-debtors` vs `/api/v1/reports/aging/receivables` | Split ownership | May have inconsistent behavior; prefer `/api/v1/reports/aging/receivables` |

---

## Cross-Module Flow Summary

The backend's major cross-module flows connect the feature families above:

| Flow | Path | Key Modules |
| --- | --- | --- |
| **Auth/Identity** | Login → token → scoped requests → logout | auth, company, rbac, admin |
| **Tenant/Admin** | Super-admin onboarding → tenant lifecycle → admin operations | company, admin, auth, accounting |
| **Catalog/Setup** | Brand/item CRUD → import → SKU readiness → packaging setup | production, inventory, factory, accounting |
| **Manufacturing/Packing** | Production plan → log → packing → batch → dispatch | factory, inventory, accounting, production |
| **Inventory** | Stock monitoring → adjustments → opening stock → valuation | inventory, accounting, sales, purchasing |
| **Order-to-Cash** | Dealer → order → reservation → dispatch → invoice → receipt → settlement | sales, inventory, accounting, invoice, portal |
| **Procure-to-Pay** | Supplier → PO → GRN → invoice → payment → settlement | purchasing, inventory, accounting |
| **Invoice/Dealer Finance** | Dispatch → auto-invoice → settlement → portal views | invoice, sales, accounting, portal |
| **Accounting/Period Close** | Journal → reconciliation → GST → period lock → close | accounting, sales, purchasing, inventory, factory, hr |
| **HR/Payroll** | Employee → leave/attendance → payroll run → posting → payment | hr, accounting |
| **Reporting/Export** | Report generation → export request → approval → download | reports, accounting, inventory, sales, factory, admin |

---

## Quick Reference: Module Directory

The source-of-truth directory is `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/`:

| Module | Status | Documentation |
| --- | --- | --- |
| `accounting` | Live | [AGENTS.md](../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) |
| `admin` | Live | [admin-portal-rbac.md](modules/admin-portal-rbac.md) |
| `auth` | Live | [auth.md](modules/auth.md) |
| `company` | Live | [company.md](modules/company.md) |
| `demo` | Live | Source reference only *(per-module packet pending)* |
| `factory` | Live | [factory.md](modules/factory.md) |
| `hr` | Live | [hr.md](modules/hr.md) |
| `inventory` | Live | [inventory.md](modules/inventory.md) |
| `invoice` | Live | Source reference only *(per-module packet pending)* |
| `portal` | Live | [admin-portal-rbac.md](modules/admin-portal-rbac.md) |
| `production` | Live | [catalog-setup.md](modules/catalog-setup.md) |
| `purchasing` | Live | [purchasing.md](modules/purchasing.md) |
| `rbac` | Live | [admin-portal-rbac.md](modules/admin-portal-rbac.md) |
| `reports` | Live | Source reference only *(per-module packet pending)* |
| `sales` | Live | [sales.md](modules/sales.md) |

---

## Cross-references

- [docs/INDEX.md](INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](modules/MODULE-INVENTORY.md) — exhaustive module inventory
- [docs/flows/FLOW-INVENTORY.md](flows/FLOW-INVENTORY.md) — exhaustive flow inventory
- [docs/deprecated/INDEX.md](deprecated/INDEX.md) — complete deprecated/incomplete registry
- [docs/adrs/INDEX.md](adrs/INDEX.md) — architecture decision records
- [docs/CONVENTIONS.md](CONVENTIONS.md) — documentation conventions
