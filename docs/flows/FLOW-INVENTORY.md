# Backend Flow Inventory

Last reviewed: 2026-04-26

This document is the canonical inventory of major backend flows in `orchestrator-erp`. Each entry names the flow family, summarises its lifecycle, identifies the owning module and key cross-module participants, and links to the canonical flow packet where the full behaviour is documented.

A reader can discover the current flow landscape from this inventory without grepping source code.

---

## Inventory

| # | Flow Family | Summary | Owning Module(s) | Key Cross-Module Participants | Canonical Packet |
| --- | --- | --- | --- | --- | --- |
| 1 | **Auth / Identity** | Login, token refresh, logout, MFA setup/verify, password change/forgot/reset, must-change-password corridor, session/refresh-token revocation, JWT-based tenant scoping | `auth` | `company` (tenant context), `rbac` (role resolution), `admin` (user lifecycle) | [auth-identity.md](auth-identity.md) |
| 2 | **Tenant / Admin Management** | Super-admin tenant onboarding (company + admin user + seeded CoA + default period), tenant lifecycle/limits/modules control plane, admin user CRUD, force-reset-password, admin email change, force-logout, support interventions, export approval gate, changelog publishing | `company`, `admin` | `auth` (credential/bootstrap), `rbac` (role assignment), `accounting` (CoA seeding, period creation) | [tenant-admin-management.md](tenant-admin-management.md) |
| 3 | **Catalog / Setup Readiness** | Brand and item CRUD, item import, SKU readiness evaluation, packaging material definitions, packaging mapping/rules setup | `production` | `inventory` (stock readiness), `factory` (packaging-mapping consumption), `accounting` (mirror accounts) | [catalog-setup-readiness.md](catalog-setup-readiness.md) |
| 4 | **Manufacturing / Packing** | Production plan creation, production log with raw-material consumption and WIP cost, packing into child SKU variants with packaging-material deduction, batch status progression to FULLY_PACKED, cost allocation traceability | `factory` | `inventory` (RM consumption, FG creation, batch registration), `accounting` (WIP/cost posting), `production` (catalog SKU resolution) | [manufacturing-packing.md](manufacturing-packing.md) |
| 5 | **Inventory Management** | Stock monitoring (FG/RM summaries, low-stock alerts, expiring batches), stock adjustments (write-downs, recounts), opening stock import with readiness gating, batch traceability and movement history | `inventory` | `accounting` (adjustment posting, valuation), `factory` (batch creation), `sales` (reservations), `purchasing` (GRN stock intake) | [inventory-management.md](inventory-management.md) |
| 6 | **Order-to-Cash (O2C)** | Dealer onboarding, sales order lifecycle (draft → confirmed → dispatched), credit limit management and override requests, inventory reservation on confirm, packaging slip generation, dispatch confirmation with stock reduction and accounting posting, auto-invoice generation, dealer receipt/collection, dealer settlement, dunning | `sales` | `inventory` (reservation, dispatch execution), `accounting` (AR posting, receipts, settlements, dealer ledger), `invoice` (invoice generation), `factory` (dispatch reads) | [order-to-cash.md](order-to-cash.md) |
| 7 | **Procure-to-Pay (P2P)** | Supplier onboarding, purchase order lifecycle (draft → approved → received), GRN with stock intake and accounting posting, purchase invoice capture (AP truth), purchase returns with allocation, supplier settlement and auto-settle | `purchasing` | `inventory` (GRN stock intake, return stock), `accounting` (AP posting, settlements, supplier ledger, tax) | [procure-to-pay.md](procure-to-pay.md) |
| 8 | **Invoice / Dealer Finance** | Invoice issuance (auto after dispatch), invoice listing and detail, internal and dealer portal finance views (ledger, invoices, aging, dealer invoice PDF), invoice settlement linkage, portal finance isolation boundaries | `invoice`, `portal` | `sales` (dispatch → invoice trigger), `accounting` (AR ledger, settlements), `portal` (dealer self-service reads) | [invoice-dealer-finance.md](invoice-dealer-finance.md) |
| 9 | **Accounting / Period Close** | Manual and automated journal posting, bank reconciliation sessions, subledger reconciliation, GST reconciliation, discrepancy resolution, month-end checklist, period close request/approve/reject (maker-checker), controlled reopen, corrections via reversal/notes, opening balance import, Tally XML import | `accounting` | `sales` (AR/revenue journals), `purchasing` (AP/tax journals), `inventory` (adjustment/valuation journals), `factory` (WIP/cost journals), `hr` (payroll posting) | [accounting-period-close.md](accounting-period-close.md) |
| 10 | **HR / Payroll** | Employee management, leave management, attendance tracking, salary structure templates, payroll run lifecycle (create → calculate → approve → post → mark-paid), statutory deductions (PF/ESI/TDS/prof-tax), payroll posting to accounting, payroll payment on accounting host | `hr` | `accounting` (payroll posting seam, payroll payment seam, required accounts) | [hr-payroll.md](hr-payroll.md) |
| 11 | **Reporting / Export** | Trial balance, P&L, balance sheet (flat + hierarchy), cash flow, GST return, account statement, inventory valuation, inventory reconciliation, aged debtors, receivables aging, production/cost reports, workflow shortcuts, export request/approval/download gate, reconciliation dashboard, balance warnings | `reports` | `accounting` (financial report data), `inventory` (stock/valuation data), `sales` (commercial report data), `factory` (production cost data), `admin` (approval inbox) | [reporting-export.md](reporting-export.md) |
| 12 | **Accounting Workflow Architecture** | Client-shareable connected accounting architecture across O2C, P2P, settlement, period close, reporting, export approvals, sensitive disclosures, and workflow shortcuts | `accounting`, `reports` | `sales`, `purchasing`, `inventory`, `factory`, `portal`, `admin` | [accounting-workflow-architecture.md](accounting-workflow-architecture.md) |

---

## Coverage Notes

- This inventory enumerates the **twelve major backend flow families** currently identifiable from controller routes, service ownership, and cross-module dependency edges in `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/`.
- The flow families align with the domain lanes described in [docs/ARCHITECTURE.md](../ARCHITECTURE.md):
  - **Platform and control plane:** Auth/Identity, Tenant/Admin Management
  - **Operations:** Catalog/Setup Readiness, Manufacturing/Packing, Inventory Management
  - **Commercial and finance:** Order-to-Cash, Procure-to-Pay, Invoice/Dealer Finance, Accounting/Period Close, HR/Payroll
  - **Reporting:** Reporting/Export, Accounting Workflow Architecture
- All twelve flow packets have been created and are linked from this inventory. Each packet contains behaviour-first documentation including current definitions of done, canonical vs non-canonical paths, and known limitations.
- Historical workflow guides under [docs/workflows/](../workflows/) remain available as reference but are not the canonical flow packets. The replacement mapping is tracked in [docs/deprecated/INDEX.md](../deprecated/INDEX.md).

## Supporting Workflow Guides (Historical)

The following historical workflow guides document operational step-by-step procedures for several flow families. They remain useful as archival operational references, but the canonical flow packets already supersede them:

| Guide | Flow Family |
| --- | --- |
| [docs/workflows/admin-and-tenant-management.md](../workflows/admin-and-tenant-management.md) | Tenant / Admin Management |
| [docs/workflows/manufacturing-and-packaging.md](../workflows/manufacturing-and-packaging.md) | Manufacturing / Packing |
| [docs/workflows/inventory-management.md](../workflows/inventory-management.md) | Inventory Management |
| [docs/workflows/sales-order-to-cash.md](../workflows/sales-order-to-cash.md) | Order-to-Cash |
| [docs/workflows/purchase-to-pay.md](../workflows/purchase-to-pay.md) | Procure-to-Pay |
| [docs/workflows/accounting-and-period-close.md](../workflows/accounting-and-period-close.md) | Accounting / Period Close |
| [docs/workflows/payroll.md](../workflows/payroll.md) | HR / Payroll |
| [docs/workflows/data-migration.md](../workflows/data-migration.md) | Data Migration (cross-cutting) |

## Key Route Prefixes by Flow Family

| Flow Family | Primary Route Prefixes |
| --- | --- |
| Auth / Identity | `/api/v1/auth/**`, `/api/v1/auth/mfa/**`, `GET /api/v1/auth/me` |
| Tenant / Admin Management | `/api/v1/superadmin/**`, `/api/v1/companies/**`, `/api/v1/admin/**`, `/api/v1/changelog/**` |
| Catalog / Setup Readiness | `/api/v1/catalog/**` |
| Manufacturing / Packing | `/api/v1/factory/**` |
| Inventory Management | `/api/v1/inventory/**`, `/api/v1/finished-goods/**`, `/api/v1/raw-materials/**`, `/api/v1/dispatch/**` |
| Order-to-Cash | `/api/v1/sales/**`, `/api/v1/dealers/**`, `/api/v1/credit/**`, `/api/v1/dealer-portal/**` |
| Procure-to-Pay | `/api/v1/purchasing/**`, `/api/v1/suppliers/**` |
| Invoice / Dealer Finance | `/api/v1/invoices/**`, `/api/v1/portal/finance/**` |
| Accounting / Period Close | `/api/v1/accounting/**`, `/api/v1/migration/**` |
| HR / Payroll | `/api/v1/hr/**`, `/api/v1/payroll/**` |
| Reporting / Export | `/api/v1/reports/**`, `/api/v1/exports/**`, `/api/v1/admin/approvals` |
| Accounting Workflow Architecture | `/api/v1/accounting/**`, `/api/v1/reports/**`, `/api/v1/exports/**`, `/api/v1/admin/approvals`, `/api/v1/portal/finance/**`, `/api/v1/dealer-portal/**` |

## Cross-references

- [docs/INDEX.md](../INDEX.md) — canonical documentation index
- [docs/modules/MODULE-INVENTORY.md](../modules/MODULE-INVENTORY.md) — canonical module inventory
- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture reference
- [docs/CONVENTIONS.md](../CONVENTIONS.md) — documentation conventions
