# Fix Taskpack — Finished-Goods FIFO Valuation vs Reservations (Quantity Basis Alignment)

Confirmed flaw: **LF-004**

Status: **DRAFT (planning only; no implementation in audit run)**

## Scope
- Align finished-goods valuation to a consistent quantity basis:
  - value “on-hand including reserved” or
  - value “available only”
  but do not mix `FinishedGood.current_stock` with FIFO slices based on `FinishedGoodBatch.quantity_available`.

## ERP expectation (what “correct” means)
- Inventory valuation method and quantity basis must match:
  - what the inventory control account expects, and
  - what COGS uses on dispatch (cost layer consumption basis).
- Reservations should not distort valuation cost layering (unless explicitly designed to).

## Primary evidence (baseline + after)
- SQL:
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/06_inventory_valuation_fifo.sql`
  - `tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/07_inventory_control_vs_valuation.sql`
- API:
  - `/api/v1/reports/inventory-valuation`
  - `/api/v1/reports/inventory-reconciliation`

## Milestones (implementation plan)
### M1 — Decide valuation basis and document it (hard gate)
- Decide:
  - valuation includes reserved stock (common), or
  - valuation excludes reserved stock (if “available” is the reporting basis).
- Document in `erp-domain/docs/ACCOUNTING_MODEL_AND_POSTING_CONTRACT.md` and report docs.

### M2 — Add targeted valuation tests
- Add an integration test that creates:
  - FG with at least 2 batches at different costs
  - a reservation that reduces `quantity_available`
  - asserts valuation matches the chosen basis (FIFO across total on-hand if that’s the decision).

### M3 — Implement valuation correction
- Update the valuation calculation to use a consistent quantity basis for both:
  - “required quantity” and
  - “FIFO slice quantities”.
- Ensure rounding/tolerance is consistent with currency rules.

### M4 — Reconciliation proof
- Re-run inventory reconciliation report on seeded/golden dataset and confirm drift is eliminated (or reduced to tolerance).

## Verification gates (required when implementing)
- `mvn -f erp-domain/pom.xml -DskipTests compile`
- `mvn -f erp-domain/pom.xml -Dcheckstyle.failOnViolation=false checkstyle:check`
- `mvn -f erp-domain/pom.xml test`

## Definition of Done
- LF-004 eliminated: valuation does not “re-price” reserved layers using last-cost filler.
- New tests cover multi-layer + reservation scenarios.
- Inventory valuation ties to inventory control within tolerance for golden scenarios (after opening balances are correctly posted).

