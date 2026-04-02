# orchestrator-erp Deprecated, Unmaintained, and Incomplete Surfaces Registry

Last reviewed: 2026-04-02

This registry documents retired, partial, duplicated, or dead-end surfaces in the `orchestrator-erp` backend. Every entry points to a canonical replacement or explicitly states that no replacement exists.

Repo-root `README.md` and `ARCHITECTURE.md` are current entrypoints into the canonical docs spine and are therefore not retired surfaces.

For surfaces still under active development or fully maintained, see the [Module Inventory](../modules/MODULE-INVENTORY.md) and [Flow Inventory](../flows/FLOW-INVENTORY.md).

---

## Registry Entry Format

Each entry follows this pattern:
- **Surface**: Name of the deprecated/unmaintained/incomplete surface
- **Status**: Current status (Retired, Deprecated, Non-canonical, No replacement, etc.)
- **Replacement**: Canonical replacement path or explicit no-replacement note

---

## Authentication and Identity

| Surface | Status | Replacement |
| --- | --- | --- |
| Legacy super-admin forgot-password alias (`POST /api/v1/superadmin/tenants/{id}/support/admin-password-reset`) | Deprecated (V157 hard cut) | Use `POST /api/v1/auth/password/forgot` for standard password reset flow |
| Email-only recovery identity (without companyCode) | Deprecated | Require `email + companyCode` for identity verification |

**Module Reference**: [auth.md](../modules/auth.md), [auth-identity flow](../flows/auth-identity.md)

---

## Catalog and Production

| Surface | Status | Replacement |
| --- | --- | --- |
| `POST /api/v1/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| `POST /api/v1/accounting/catalog/products` | Retired (hard cut) | Use `POST /api/v1/catalog/items` |
| Internal `ProductionCatalogService` listing methods (`listBrands()`, `listBrandProducts()`, `listProducts()`) | Non-canonical (internal only) | Use `CatalogService.searchItems()` for public catalog browsing |

**Module Reference**: [catalog-setup.md](../modules/catalog-setup.md), [catalog-setup-readiness flow](../flows/catalog-setup-readiness.md)

---

## Factory and Manufacturing

| Surface | Status | Replacement |
| --- | --- | --- |
| `POST /api/v1/factory/production-batches` | Retired (hard cut) | Use `POST /api/v1/factory/production/logs` |
| `GET /api/v1/factory/production-batches` | Retired (hard cut) | Use `GET /api/v1/factory/production/logs` |
| `X-Idempotency-Key` header on packing requests | Rejected (400 error) | Use `Idempotency-Key` (canonical header) |
| `X-Request-Id` header on packing requests | Rejected (400 error) | Use `Idempotency-Key` (canonical header) |
| `ProductionBatch` entity | Unmaintained (unused) | Use `ProductionLog` entity for production tracking |
| Historical orchestrator dispatch shortcut (`POST /api/v1/orchestrator/factory/dispatch/{batchId}`) | Retired (hard cut) | No orchestrator replacement. Use `POST /api/v1/dispatch/confirm` for the canonical dispatch write |

**Module Reference**: [factory.md](../modules/factory.md), [manufacturing-packing flow](../flows/manufacturing-packing.md)

---

## Inventory Management

| Surface | Status | Replacement |
| --- | --- | --- |
| Legacy dispatch confirm path (`/api/v1/dispatch/confirm` with different payload structure) | Retired | Use `POST /api/v1/dispatch/confirm` with canonical payload |
| Legacy raw-material intake endpoints | No replacement | Disabled via `erp.raw-material.intake.enabled=false`. Raw material stock intake now occurs through [procure-to-pay flow](../flows/procure-to-pay.md) via GRN (Goods Receipt Note) processing |

**Module Reference**: [inventory.md](../modules/inventory.md), [inventory-management flow](../flows/inventory-management.md)

---

## Sales and Order-to-Cash

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/sales/dealers` | Deprecated (frontend alias) | Use `/api/v1/dealers` |
| Legacy idempotency key resolution | Deprecated | Use canonical `Idempotency-Key` header |
| Legacy order statuses (BOOKED, SHIPPED, etc.) | Legacy compatibility only | Use canonical order statuses defined in `SalesOrderStatus` enum |
| `POST /api/v1/sales/promotions` | Planned foundation | Sales targets only; promotions endpoint infrastructure exists for future activation |

**Module Reference**: [sales.md](../modules/sales.md), [order-to-cash flow](../flows/order-to-cash.md)

---

## Purchasing and Procure-to-Pay

| Surface | Status | Replacement |
| --- | --- | --- |
| `X-Idempotency-Key` header on GRN | Rejected (400 error) | Use `Idempotency-Key` |
| PO creation idempotency | Not supported | Rely on order number uniqueness for idempotency |

**Module Reference**: [purchasing.md](../modules/purchasing.md), [procure-to-pay flow](../flows/procure-to-pay.md)

---

## Invoices and Dealer Finance

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/dealers/{id}/ledger` | Retired (410 Gone) | Use `/api/v1/portal/finance/ledger?dealerId={id}` |
| `GET /api/v1/dealers/{id}/invoices` | Retired (410 Gone) | Use `/api/v1/portal/finance/invoices?dealerId={id}` |

**Module Reference**: [invoice.md](../modules/invoice.md), [invoice-dealer-finance flow](../flows/invoice-dealer-finance.md)

---

## HR and Payroll

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/hr/payroll-runs` | Deprecated (410 Gone) | Use `/api/v1/payroll/runs` |
| `POST /api/v1/hr/payroll-runs` | Deprecated (410 Gone) | Use `/api/v1/payroll/runs` |
| Any `/api/v1/hr/**` endpoints outside standard payroll flow | Deprecated | Use `/api/v1/hr/employees` for employee management, `/api/v1/payroll/runs` for payroll operations |

**Module Reference**: [hr.md](../modules/hr.md), [hr-payroll flow](../flows/hr-payroll.md)

---

## Accounting and Reporting

| Surface | Status | Replacement |
| --- | --- | --- |
| Audit digest CSV export functionality | Deprecated | Use standard export workflow (`POST /api/v1/exports/request` → `GET /api/v1/exports/{requestId}/download`) with appropriate report types |
| Direct period reopen from CLOSED status | Not supported | Closed periods cannot be reopened; create adjustment journal entries instead |
| Editing posted journals | Not supported | Must reverse and re-create via correction entries |

**Module Reference**: [reports.md](../modules/reports.md), [accounting-period-close flow](../flows/accounting-period-close.md), [reporting-export flow](../flows/reporting-export.md)

---

## Reporting and Export

| Surface | Status | Replacement |
| --- | --- | --- |
| `GET /api/v1/reports/cash-flow` (without date filtering) | Non-canonical | Use with explicit date range parameters; be aware heuristic classification may produce inconsistent results |
| `GET /api/v1/reports/aged-debtors` vs `/api/v1/reports/aging/receivables` | Split ownership | May have inconsistent behavior; prefer `/api/v1/reports/aging/receivables` |

**Module Reference**: [reports.md](../modules/reports.md), [reporting-export flow](../flows/reporting-export.md)

---

## Tenant and Admin Management

| Surface | Status | Replacement |
| --- | --- | --- |
| `/api/v1/support/**` (shared host) | Deprecated | Use host-specific paths: `/api/v1/portal/support/tickets` or `/api/v1/dealer-portal/support/tickets` |

**Module Reference**: [admin-portal-rbac.md](../modules/admin-portal-rbac.md), [tenant-admin-management flow](../flows/tenant-admin-management.md)

---

## Historical Documentation (Non-Canonical)

The following historical docs remain in the repo for reference but are **not** the canonical source of truth:

| Document | Status | Replacement |
| --- | --- | --- |
| [docs/frontend-handoff-platform.md](../frontend-handoff-platform.md) | Reference only (superseded) | Use [docs/frontend-portals/README.md](../frontend-portals/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) |
| [docs/frontend-handoff-operations.md](../frontend-handoff-operations.md) | Reference only (superseded) | Use [docs/frontend-portals/README.md](../frontend-portals/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) |
| [docs/frontend-handoff-commercial.md](../frontend-handoff-commercial.md) | Reference only (superseded) | Use [docs/frontend-portals/README.md](../frontend-portals/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) |
| [docs/frontend-handoff-finance.md](../frontend-handoff-finance.md) | Reference only (superseded) | Use [docs/frontend-portals/README.md](../frontend-portals/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) |
| [docs/accounting-portal-frontend-engineer-handoff.md](../accounting-portal-frontend-engineer-handoff.md) | Reference only (historical deep handoff) | Use [docs/frontend-portals/accounting/README.md](../frontend-portals/accounting/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) |
| [docs/ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md](../ACCOUNTING_PORTAL_SCOPE_GUARDRAIL.md) | Reference only (retained scope lock) | Use [docs/frontend-portals/accounting/README.md](../frontend-portals/accounting/README.md) and [docs/frontend-api/README.md](../frontend-api/README.md) for current portal ownership and API boundary truth |
| [docs/accounting-portal-endpoint-map.md](../accounting-portal-endpoint-map.md) | Reference only (curated parity snapshot) | Use [docs/frontend-portals/accounting/README.md](../frontend-portals/accounting/README.md), [docs/frontend-api/README.md](../frontend-api/README.md), and `openapi.json` for current contract truth |
| [docs/AUDIT_TRAIL_OWNERSHIP.md](../AUDIT_TRAIL_OWNERSHIP.md) | Reference only (narrow change-control contract) | Use [docs/modules/core-audit-runtime-settings.md](../modules/core-audit-runtime-settings.md) for the canonical audit ownership overview |
| [docs/workflows/admin-and-tenant-management.md](../workflows/admin-and-tenant-management.md) | Reference only (superseded) | Use [docs/flows/tenant-admin-management.md](../flows/tenant-admin-management.md) |
| [docs/workflows/manufacturing-and-packaging.md](../workflows/manufacturing-and-packaging.md) | Reference only (superseded) | Use [docs/flows/manufacturing-packing.md](../flows/manufacturing-packing.md) |
| [docs/workflows/inventory-management.md](../workflows/inventory-management.md) | Reference only (superseded) | Use [docs/flows/inventory-management.md](../flows/inventory-management.md) |
| [docs/workflows/sales-order-to-cash.md](../workflows/sales-order-to-cash.md) | Reference only (superseded) | Use [docs/flows/order-to-cash.md](../flows/order-to-cash.md) |
| [docs/workflows/purchase-to-pay.md](../workflows/purchase-to-pay.md) | Reference only (superseded) | Use [docs/flows/procure-to-pay.md](../flows/procure-to-pay.md) |
| [docs/workflows/accounting-and-period-close.md](../workflows/accounting-and-period-close.md) | Reference only (superseded) | Use [docs/flows/accounting-period-close.md](../flows/accounting-period-close.md) |
| [docs/workflows/payroll.md](../workflows/payroll.md) | Reference only (superseded) | Use [docs/flows/hr-payroll.md](../flows/hr-payroll.md) |
| [docs/workflows/data-migration.md](../workflows/data-migration.md) | Reference only (historical migration checklist) | Use [docs/runbooks/migrations.md](../runbooks/migrations.md) for current rollout truth |
| [docs/developer-guide.md](../developer-guide.md) | Non-canonical (superseded) | Use [docs/INDEX.md](../INDEX.md), [docs/modules/MODULE-INVENTORY.md](../modules/MODULE-INVENTORY.md), and [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) |
| [docs/endpoint-inventory.md](../endpoint-inventory.md) | Reference only | Use `openapi.json` and module packets as primary truth |
| [docs/migration-guide.md](../migration-guide.md) | Retired reference only | Use [docs/runbooks/migrations.md](../runbooks/migrations.md) for current migration rollout truth; there is no maintained canonical replacement for the old CSV/Tally appendix |

---

## Related Documentation

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/CONVENTIONS.md](../CONVENTIONS.md) — documentation conventions including stale-doc handling policy
- [docs/modules/MODULE-INVENTORY.md](../modules/MODULE-INVENTORY.md) — live module inventory
- [docs/flows/FLOW-INVENTORY.md](../flows/FLOW-INVENTORY.md) — live flow inventory

---

## Contributing to This Registry

When documenting deprecated surfaces in new or updated packets:
1. Add the surface to the appropriate section in this registry
2. Include the current status (Retired, Deprecated, Non-canonical, No replacement)
3. Point to the canonical replacement or explicitly state that no replacement exists
4. Add a cross-reference to the module or flow packet where the surface is documented
