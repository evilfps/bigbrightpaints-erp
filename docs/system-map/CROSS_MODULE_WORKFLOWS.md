# Cross-Module Workflows

Terminology note:
- Use `partner` as the canonical cross-module entity term.
- Use `dealer` and `supplier` only where the role-specific workflow matters.

## Order-to-Cash (O2C)
- Owner module/service: `sales` (`SalesController`, `SalesService`, `SalesFulfillmentService`)
- Handoff module/service: `inventory` (`FinishedGoodsService`) -> `accounting` (`SalesJournalService`, `AccountingFacade`, `DealerLedgerService`)
- Key invariants:
  - Dispatch confirmation drives one invoice (`invoices`) and one AR journal + one COGS journal reference.
  - Sales order, packaging slip, invoice, and dealer ledger remain link-consistent by id.
  - Idempotency keys and references must prevent duplicate postings.
- Duplicate/overlap risks:
  - Dispatch also exists via `orchestrator` paths, which are deprecated for canonical API execution.
  - `SalesService.dispatchOrder` and orchestrator dispatch logic share responsibility in code; this should stay single-source through canonical path.

## Procure-to-Pay (P2P)
- Owner module/service: `purchasing` (`PurchasingService`, `SupplierService`)
- Handoff module/service: `inventory` (`RawMaterialService`) -> `accounting` (`AccountingService`)
- Key invariants:
  - GRN intake can’t exceed PO requirement.
  - Raw material movements link to purchase records.
  - Purchase invoice, partner settlement (supplier role), and journal lines are balanced and idempotent.
- Duplicate/overlap risks:
  - Legacy helper methods in multiple services for partner settlement helpers and idempotency key normalization.
  - Migration overlap between purchasing/inventory schemas (`V27`, `V120` family and related v2 migrations) requires periodic overlap scan.

## Production-to-Pack
- Owner module/service: `production` (`ProductionCatalogService`) + `factory` (`PackingService`, `BulkPackingService`)
- Handoff module/service: `inventory` (`FinishedGoodsService`, `RawMaterialService`) -> `accounting` (`AccountingFacade`)
- Key invariants:
  - Production/log/bulk pack operations generate deterministic movement/reference linkage.
  - Packaging operations cannot silently duplicate movements/journals on retries.
  - Inventory quantities remain non-negative and FIFO/non-FIFO paths remain configured.
- Duplicate/overlap risks:
  - Factory packing and accounting posting boundaries have partial overlap in older paths; ensure posting goes through canonical accounting boundary.
  - Shared naming/behavior for `PackingService` and `InventoryMovement` updates can diverge.

## Payroll
- Owner module/service: `hr` (`PayrollService`, `PayrollCalculationService`)
- Handoff module/service: `accounting` (`AccountingService`, `AccountingFacade`)
- Key invariants:
  - Payroll run state is linear (`DRAFT -> CALCULATED -> APPROVED -> POSTED -> PAID`).
  - `AccountingPeriodService`/period lock constraints apply.
  - Posted journal remains linked to run and attendance lines.
- Duplicate/overlap risks:
  - Accounting-side payroll payment helpers and HR-side accounting hooks can diverge on state transitions.
  - Existing duplicate DTO/helper methods around payroll references increase risk.

## Period Close
- Owner module/service: `accounting` (`AccountingPeriodService`, `AccountingPeriodSnapshotService`)
- Handoff module/service: `reports` (`ReportService`) and `reconciliation` flows via `accounting` service callers
- Key invariants:
  - Ledger entries posted under closed periods are blocked unless override policy is explicitly used.
  - Close and reopen are explicit and should be reflected in snapshots.
  - Subledger totals (`AR/AP` partner subledgers: dealer/supplier, and payroll) remain reconciled at close boundaries.
- Duplicate/overlap risks:
  - Period hooks in multiple modules (accounting + reporting) can apply inconsistent assumptions on close status.
  - Legacy period-close workarounds in scripts/handlers must stay aligned with canonical service checks.
