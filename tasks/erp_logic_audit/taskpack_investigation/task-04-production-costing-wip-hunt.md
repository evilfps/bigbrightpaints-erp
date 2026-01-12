# Task 04 — Production Costing + WIP Logic Hunt

## Scope
- Workflows: production logs, raw material issue, semi-finished receipt, packing, WIP → FG journals, wastage.
- Portals: Manufacturing (factory), Accounting.
- Modules (primary): `factory`, `inventory`, `accounting`, `reports`, `production`.

## ERP expectation
- Material issues reduce RM stock and record traceable movements (by batch where applicable).
- Production/packing creates FG batches and inventory movements that can be traced to production_code and packing records.
- WIP/FG journals reflect the same cost basis as movements/batches (FIFO RM consumption + packaging cost).
- Wastage is recorded and does not silently disappear from valuation vs GL.

## Where to inspect in code
- Production log creation + RM consumption:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/ProductionLogService.java`
- Packing + packaging material consumption + WIP journals:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/service/PackingService.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/factory/domain/PackingRecord.java`
- Inventory movement references:
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/InventoryMovement.java`
  - `erp-domain/src/main/java/com/bigbrightpaints/erp/modules/inventory/domain/RawMaterialMovement.java`

## Evidence to gather

### SQL probes (starting set)
- Movement journal linkage (production-related references will appear here):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql`
- Inventory valuation + reconciliation (look for production-heavy variance):
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`

### GET-only API probes
- `bash tasks/erp_logic_audit/EVIDENCE_QUERIES/curl/01_accounting_reports_gets.sh`

### Additional probes (create if needed)
- Add a production-focused SQL that joins:
  - `production_logs.production_code`
  - `inventory_movements(reference_type='PRODUCTION_LOG', reference_id=production_code)`
  - WIP/FG journal references posted by production/packing services

## What counts as a confirmed flaw (LF)
- A production/packing flow creates FG batches/movements without the corresponding WIP→FG journal (or vice versa) in a way that cannot be reconciled.
- Production cost fields (material_cost_total, unit_cost) drift from the actual consumed batch costs in a deterministic way.
- Wastage handling produces valuation vs GL variance beyond tolerance with a reproducible chain.

## Why tests might still pass
- Production flows are often under-tested with realistic batch layers and packaging consumption.
- Unit/integration tests may not assert GL tie-outs for WIP and finished goods.

## Deliverable
- Confirmed LF items appended to `tasks/erp_logic_audit/LOGIC_FLAWS.md` with evidence.
- Unproven hypotheses recorded as LEADs in `tasks/erp_logic_audit/HUNT_NOTEBOOK.md`.

