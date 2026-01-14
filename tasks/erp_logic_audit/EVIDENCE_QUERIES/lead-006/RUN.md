# LEAD-006 Evidence Run

## Objective
Verify whether inventory auto-posting event handlers are active and assess existing movement↔journal drift.

## Command log
```bash
# Search for InventoryMovementEvent publishers
TS=$(date -u +"%Y%m%dT%H%M%SZ"); (rg -n "InventoryMovementEvent" -S erp-domain/src/main/java || echo "no InventoryMovementEvent publishers found") \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/${TS}_inventory_movement_event_search.txt

# Orphan movements without journal linkage (company_id=5)
TS=$(date -u +"%Y%m%dT%H%M%SZ"); PGPASSWORD=erp psql -h localhost -p 55432 -U erp -d erp_domain \
  -v company_id=5 -f tasks/erp_logic_audit/EVIDENCE_QUERIES/SQL/02_orphans_movements_without_journal.sql \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/${TS}_orphans_movements_without_journal.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/*_inventory_movement_event_search.txt`
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-006/OUTPUTS/*_orphans_movements_without_journal.txt`
