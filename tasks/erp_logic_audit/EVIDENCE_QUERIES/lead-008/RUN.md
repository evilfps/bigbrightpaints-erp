# LEAD-008 Evidence Run

## Objective
Verify whether inventory revaluation events are emitted and whether journal entry dates align with event dates.

## Command log
```bash
TS=$(date -u +"%Y%m%dT%H%M%SZ"); (rg -n "InventoryValuationChangedEvent" -S erp-domain/src/main/java || echo "no InventoryValuationChangedEvent publishers found") \
  > tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-008/OUTPUTS/${TS}_inventory_reval_event_search.txt
```

## Outputs captured
- `tasks/erp_logic_audit/EVIDENCE_QUERIES/lead-008/OUTPUTS/*_inventory_reval_event_search.txt`
