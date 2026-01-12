# Task 03 — Inventory Valuation + COGS Logic Hunt

## Scope
- Workflows: inventory valuation, inventory reconciliation, dispatch COGS posting, reservations/backorders.
- Portals: Accounting, Inventory, Sales/Dispatch.
- Modules (primary): `inventory`, `reports`, `accounting`, `sales`.

## ERP expectation
- Stock quantities (masters, batches, reservations, movements) are internally consistent:
  - current stock aligns with batch totals in the chosen “source-of-truth” model
  - reserved stock never exceeds current stock (unless explicitly allowed + audited)
- Valuation method used for reporting is consistent with the method used for posting COGS/inventory relief.
- Dispatch must not ship stock without corresponding cost relief (COGS journal and inventory relief).
- Inventory reconciliation variance must be within tolerance for “green” close.

## Where to inspect in code
- Inventory valuation + reconciliation:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/reports/service/ReportService.java`
    - `inventoryValuation`, `inventoryReconciliation`, `computeInventoryTotals`, FIFO slice logic
- Reservations and dispatch quantities:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/service/FinishedGoodsService.java`
    - `reserveForOrder`, `releaseReservationsForOrder`, `confirmDispatch`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryReservation.java`
- COGS posting inputs:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/sales/service/SalesService.java` (`confirmDispatch` cost building)
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/accounting/service/AccountingFacade.java` (`postCogsJournal`)

## Evidence to gather

### SQL probes
- FIFO valuation computation and reconciliation:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/06_inventory_valuation_fifo.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`
- Movement journal linkage (interpret by reference type):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
- Orphan reservations:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/12_orphan_reservations.sql`
- Dispatch slips without resolvable COGS journals:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/03_dispatch_slips_without_cogs_journal.sql`

### GET-only API probes
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

## What counts as a confirmed flaw (LF)
- Inventory reconciliation variance is outside tolerance and you can show the mismatch is due to a deterministic logic path (not “bad data entry”).
- Dispatch produces inventory movements but does not produce (or cannot resolve) the corresponding COGS journal.
- Reservations/backorders create persistent “stuck” states (e.g., reserved stock drift) with reproducible steps.

## Why tests might still pass
- Tests may validate happy paths without realistic stock layers/batches.
- Valuation logic is often untested against drift scenarios (partial dispatch, backorders, mixed stock sources).

## Deliverable
- Confirmed LF items in `tasks/erp_logic_audit/LOGIC_FLAWS.md` with evidence + repro.
- Leads recorded in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md` if unproven.

