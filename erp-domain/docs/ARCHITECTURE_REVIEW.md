# Architecture Review

## Observed Module Boundaries
- core: security, audit, system settings, number sequences, shared config.
- shared: API response wrapper, shared DTOs/utilities.
- orchestrator: cross-module command dispatch, tracing, outbox/audit, dashboards.
- modules:
  - admin, auth, rbac, company, portal, demo
  - accounting, reports, purchasing, inventory, hr, invoice
  - factory, production
  - sales

## Recommended Mapping to Major Modules
- ADMIN
  - admin, auth, rbac, company, portal, demo, orchestrator dashboards, integration health.
- ACCOUNTING (includes reports/purchasing/inventory/HR)
  - accounting, reports, purchasing, inventory, hr/payroll, supplier/stock/dispatch endpoints.
- FACTORY_PRODUCTION
  - factory (plans, batches, logs, packing), production catalog.
- SALES
  - sales orders, promotions, targets, credit requests, invoices, dispatch confirmation.
- DEALERS
  - dealer management + dealer portal self-service.

## Coupling and Duplication Patterns
- Sales -> Accounting: SalesService posts AR/COGS and ledger updates via AccountingService/AccountingFacade.
- Sales -> Inventory: order creation/reservation and dispatch confirmation flows reach FinishedGoodsService.
- Factory -> Inventory: production logs/packing drive finished goods batches and inventory movement.
- Accounting -> HR: payroll batch payments and payroll postings are tied to accounting journals.
- Duplicate API surfaces:
  - Dealer directory endpoints exposed under both `/api/v1/dealers` and `/api/v1/sales/dealers`.
  - Payroll runs exposed under both `/api/v1/hr/payroll-runs` and `/api/v1/payroll/runs`.
  - Orchestrator dispatch alias `/api/v1/orchestrator/dispatch/{orderId}` duplicates `/api/v1/orchestrator/dispatch`.
- DTO duplication:
  - `PayrollRunDto` exists both in `modules/hr/dto` and as nested records in `PayrollService`.

## Notable Design Notes
- JWT auth enforced via method-level security, with public auth endpoints and actuator health.
- Flyway migrations are extensive (91 files) with additive forward migrations for payroll and performance indexes.
- Multi-company support via company context filter and `X-Company-Id` header on orchestrator flows.
- Accounting posting contract documented in `docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md`.
