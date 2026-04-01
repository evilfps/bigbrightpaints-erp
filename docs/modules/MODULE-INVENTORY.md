# Backend Module Inventory

Last reviewed: 2026-03-30

This document is the canonical inventory of every live backend module in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/`. Each entry names the module, summarises its ownership, and links to its canonical documentation packet.

A reader can discover module ownership from this inventory without grepping the source tree.

---

## Inventory

| Module | Ownership Summary | Canonical Packet |
| --- | --- | --- |
| **accounting** | Financial truth boundary: journals, ledgers, chart of accounts, period controls, settlements, corrections, reconciliation, opening balance import, Tally XML import, payroll posting seam | [AGENTS.md](../../erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/AGENTS.md) |
| **admin** | Admin/management surfaces: admin settings, tenant administration, support ticket access, and enterprise admin operations | [admin-portal-rbac.md](admin-portal-rbac.md) |
| **auth** | Authentication corridor: login, refresh, logout, MFA, password reset, must-change-password, token/session revocation, JWT-based tenant scoping, security filter chain | [auth.md](auth.md) |
| **company** | Tenant/company lifecycle: company CRUD, runtime admission, module gating, licensing checks, company-scoping assumptions | [company.md](company.md) |
| **demo** | Demo/sample data seeding: demo data controller for setting up sample data in non-production environments | [No per-module packet — demo is a test-only surface, documented in the deprecated registry](../deprecated/INDEX.md) |
| **factory** | Manufacturing execution: production logs, packing, packaging mappings, batch registration, cost allocation (WIP/consumption), and dispatch handoff boundary | [factory.md](factory.md) |
| **hr** | HR and payroll truth: employee management, leave, attendance, payroll run lifecycle, payroll calculation, payroll posting, and payroll payment on the accounting host | [hr.md](hr.md) |
| **inventory** | Stock truth boundary: stock summaries, batch traceability, reservations, adjustments, opening stock import, valuation, dispatch execution, and inventory–accounting event bridge | [inventory.md](inventory.md) |
| **invoice** | Invoice lifecycle: invoice issuance, settlement behavior, ledger/aging/statement surfaces, and invoice-related DTO families | [invoice.md](invoice.md) |
| **portal** | Dealer self-service portal: dealer-facing endpoints, portal finance views, portal-specific DTOs and isolation boundaries | [admin-portal-rbac.md](admin-portal-rbac.md) |
| **production** | Catalog and product surfaces: brands, items, readiness, SKU setup, packaging material definitions, and product/variant management | [catalog-setup.md](catalog-setup.md) |
| **purchasing** | Procure-to-pay truth: supplier lifecycle, purchase orders, GRN, purchase invoices, purchase returns, settlements, and AP boundaries | [purchasing.md](purchasing.md) |
| **rbac** | Role-based access control: role definitions, permission matrices, role assignment restrictions, and RBAC config | [admin-portal-rbac.md](admin-portal-rbac.md) |
| **reports** | Reporting surfaces: trial balance, P&L, balance sheet, cash flow, GST, aging, dashboards, and export/report generation | [reports.md](reports.md) |
| **sales** | Commercial lifecycle truth: dealer/customer management, order lifecycle, credit controls, dispatch coordination, dealer portal | [sales.md](sales.md) |

---

## Cross-Cutting Infrastructure Packets

The following packets document cross-cutting platform infrastructure that lives outside the `modules/` tree:

| Packet | Ownership Summary | Canonical Packet |
| --- | --- | --- |
| **core security/error** | Security filter chain (JWT authentication, company-context resolution, must-change-password corridor), exception/error contract (ApplicationException, ErrorCode, global exception handlers), fail-open vs fail-closed boundaries | [core-security-error.md](core-security-error.md) |
| **core audit/runtime/settings** | Audit-surface ownership (platform audit, enterprise audit trail, accounting event store), runtime-gating split (filter chain, enforcement services, portal interceptor), global-versus-tenant settings risk, audit routing for exception paths | [core-audit-runtime-settings.md](core-audit-runtime-settings.md) |
| **database/migration** | Persistence technology, schema areas, entity/repository conventions, Flyway v2 migration posture, profile activation, legacy-track constraints, and data-import entry surfaces | [db-migration.md](../platform/db-migration.md) |
| **orchestrator** | Background coordination: outbox publishing, command dispatch, Spring event bridges, schedulers, retry/dead-letter behavior, feature flags, and deprecated/dead orchestration seams | [orchestrator.md](orchestrator.md) |

## Coverage Notes

- The source-of-truth directory listing is `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/`. This inventory covers all 15 live module directories: `accounting`, `admin`, `auth`, `company`, `demo`, `factory`, `hr`, `inventory`, `invoice`, `portal`, `production`, `purchasing`, `rbac`, `reports`, `sales`.
- Six modules have dedicated canonical packets: `accounting` and `hr` (AGENTS.md in source), `inventory` ([inventory.md](inventory.md)), `factory` ([factory.md](factory.md)), `production` ([catalog-setup.md](catalog-setup.md)), and `sales` ([sales.md](sales.md)). Three modules (`admin`, `portal`, `rbac`) share a combined packet at [admin-portal-rbac.md](admin-portal-rbac.md). Two modules have auth/company-level packets: `auth` ([auth.md](auth.md)) and `company` ([company.md](company.md)). Three cross-cutting infrastructure packets are documented: `core security/error` ([core-security-error.md](core-security-error.md)), `core audit/runtime/settings` ([core-audit-runtime-settings.md](core-audit-runtime-settings.md)), and `core idempotency` ([core-idempotency.md](core-idempotency.md)). One module (`demo`) is a test-only surface with no per-module packet — documented with an explicit no-replacement note pointing to the deprecated registry.
- When a per-module packet is created for a module currently pointing to its source directory, the corresponding inventory row should be updated to link to the new packet.

## Cross-references

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/CONVENTIONS.md](../CONVENTIONS.md) — documentation conventions
